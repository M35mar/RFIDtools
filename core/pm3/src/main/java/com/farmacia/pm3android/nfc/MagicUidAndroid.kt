// Android side of the UID tool: reads the current manufacturer block/pages,
// writes the new UID and reads it back.
//
// The Android NFC API cannot send the Gen1a/Gen1b backdoor sequences (7-bit
// frames), so only direct-write Gen2/CUID cards, Gen3/APDU cards and
// Ultralight EV1 DirectWrite cards can be changed from the phone. A card that
// rejects both paths is reported as unsupported, with a hint to use Proxmark3.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import java.io.IOException

/** Outcome of a successful UID write. */
data class MagicUidResult(
    val family: MagicUidFamily,
    val oldUid: ByteArray,
    val newUid: ByteArray,
    val method: MagicUidMethod,
    /** The block or pages read back after the write, when the card allowed it. */
    val readBack: ByteArray? = null,
    /**
     * True when the card returned the new UID on read-back. Gen3 7-byte cards
     * only accept the UID-only APDU, which cannot be read back in the same
     * session, so this is false for them.
     */
    val verified: Boolean = false,
)

/** The sector-0 key did not open the card. */
class MagicUidKeyException(cause: Throwable? = null) :
    IOException("chiave Classic del settore 0 rifiutata", cause)

/** The current UID data could not be read. */
class MagicUidReadException(cause: Throwable? = null) :
    IOException("lettura del blocco 0 o delle pagine UID non riuscita", cause)

/** The card refused the write command. */
class MagicUidWriteException(cause: Throwable? = null) :
    IOException("scrittura del nuovo UID rifiutata", cause)

/** The card accepted the command but did not return the new UID. */
class MagicUidVerifyException(cause: Throwable? = null) :
    IOException("verifica del nuovo UID non riuscita", cause)

/** The card is not a writable magic card for the phone's NFC API. */
class MagicUidUnsupportedException(cause: Throwable? = null) :
    IOException("carta non compatibile: serve una magic card Gen2/Gen3 o Ultralight DirectWrite", cause)

/** The presented card does not have the UID length the user typed. */
class MagicUidLengthException(val cardBytes: Int, val typedBytes: Int) :
    IOException("la carta ha un UID di $cardBytes byte, ne sono stati inseriti $typedBytes")

object MagicUidAndroid {

    private val APDU_WRITE_BLOCK0 = byteArrayOf(
        0x90.toByte(), 0xF0.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0x10,
    )
    private val APDU_WRITE_UID = byteArrayOf(
        0x90.toByte(), 0xFB.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0x07,
    )

    /** Identifies the card family from the technologies it exposes. */
    fun detectFamily(tag: Tag): MagicUidFamily? {
        val classic = MifareClassic.get(tag)
        if (classic != null) {
            return when (classic.size) {
                MifareClassic.SIZE_4K -> MagicUidFamily.CLASSIC_4K
                MifareClassic.SIZE_1K -> MagicUidFamily.CLASSIC_1K
                else -> if (tag.id.size == MagicUidLayout.UID_7_BYTES) {
                    MagicUidFamily.CLASSIC_4K
                } else {
                    MagicUidFamily.CLASSIC_1K
                }
            }
        }

        if (MifareUltralight.get(tag) != null) return MagicUidFamily.ULTRALIGHT_EV1

        val nfcA = NfcA.get(tag) ?: return null
        // A Gen3 card may expose only NfcA. A 4-byte UID is always Classic;
        // for a 7-byte UID, SAK 0x00 is an Ultralight, anything else is Classic.
        if (tag.id.size == MagicUidLayout.UID_4_BYTES) return MagicUidFamily.CLASSIC_1K
        if (tag.id.size != MagicUidLayout.UID_7_BYTES) return null
        val sak = try {
            nfcA.connect()
            nfcA.sak
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { nfcA.close() }
        }
        return if (sak.toInt() == 0x00) {
            MagicUidFamily.ULTRALIGHT_EV1
        } else {
            MagicUidFamily.CLASSIC_4K
        }
    }

    /**
     * Writes [newUid] to [tag] and verifies the result. [newUid] must already
     * match the presented card's UID length.
     */
    fun write(
        tag: Tag,
        family: MagicUidFamily,
        newUid: ByteArray,
        key: ByteArray,
    ): MagicUidResult {
        require(newUid.size == MagicUidLayout.UID_4_BYTES ||
            newUid.size == MagicUidLayout.UID_7_BYTES)
        require(key.size == MagicUidLayout.KEY_BYTES)
        if (tag.id.size != newUid.size) {
            throw MagicUidLengthException(tag.id.size, newUid.size)
        }
        return when (family) {
            MagicUidFamily.ULTRALIGHT_EV1 -> writeUltralight(tag, newUid)
            MagicUidFamily.CLASSIC_1K,
            MagicUidFamily.CLASSIC_4K,
            -> writeClassic(tag, family, newUid, key)
        }
    }

    // -----------------------------------------------------------------------
    // MIFARE Classic
    // -----------------------------------------------------------------------

    private fun writeClassic(
        tag: Tag,
        family: MagicUidFamily,
        newUid: ByteArray,
        key: ByteArray,
    ): MagicUidResult {
        val oldUid = tag.id.copyOf()
        var current: ByteArray? = null

        if (MifareClassic.get(tag) != null) {
            val transport = AndroidClassicTransport(tag)
            try {
                if (!authenticateSectorZero(transport, key)) throw MagicUidKeyException()
                current = try {
                    transport.readBlock(0)
                } catch (lost: TagLostException) {
                    throw lost
                } catch (t: IOException) {
                    throw MagicUidReadException(t)
                }

                val plan = MagicUidLayout.buildClassic(family, newUid, current, oldUid)
                val block0 = plan.classicBlock0
                    ?: throw MagicUidWriteException()

                var directFailure: IOException? = null
                try {
                    transport.writeBlock(0, block0)
                } catch (lost: TagLostException) {
                    throw lost
                } catch (t: IOException) {
                    directFailure = t
                }

                if (directFailure == null) {
                    // The write changed the UID, so the authenticated session
                    // is no longer valid: a read-back may throw or return the
                    // old data. The WRITE itself has already succeeded.
                    val actual = runCatching { transport.readBlock(0) }.getOrNull()
                    val verified =
                        actual != null && MagicUidLayout.verifyClassicUid(plan, actual)
                    return MagicUidResult(
                        family = family,
                        oldUid = oldUid,
                        newUid = newUid,
                        method = MagicUidMethod.CLASSIC_DIRECT,
                        readBack = actual,
                        verified = verified,
                    )
                }
            } finally {
                transport.close()
            }
        }

        // Gen3 cards often refuse a normal WRITE to block 0 but accept the
        // magic APDUs. The session above is already closed.
        if (NfcA.get(tag) == null) throw MagicUidUnsupportedException()
        return writeClassicApdu(tag, family, newUid, current)
    }

    private fun authenticateSectorZero(transport: ClassicTransport, key: ByteArray): Boolean =
        transport.authenticate(0, key, keyA = true) ||
            transport.authenticate(0, key, keyA = false)

    private fun writeClassicApdu(
        tag: Tag,
        family: MagicUidFamily,
        newUid: ByteArray,
        current: ByteArray?,
    ): MagicUidResult {
        val oldUid = tag.id.copyOf()
        val transport = AndroidNfcATransport(tag)
        try {
            if (newUid.size == MagicUidLayout.UID_4_BYTES) {
                val currentBlock = current ?: runCatching { readBlock0(transport::transceive) }
                    .getOrElse { throw MagicUidReadException(it) }
                val plan = MagicUidLayout.buildClassic(family, newUid, currentBlock, oldUid)
                val block0 = plan.classicBlock0
                    ?: throw MagicUidWriteException()
                val response = try {
                    transport.transceive(APDU_WRITE_BLOCK0 + block0)
                } catch (lost: TagLostException) {
                    throw lost
                } catch (t: IOException) {
                    throw MagicUidWriteException(t)
                }
                if (!isOk(response)) throw MagicUidUnsupportedException()

                // Gen3 cards auto-correct BCC and may replace ATQA/SAK, so only
                // the UID is compared. A card that cannot be read back after
                // the write is still accepted on the APDU's status word.
                val actual = runCatching { readBlock0(transport::transceive) }.getOrNull()
                if (actual != null && !MagicUidLayout.verifyClassicUid(plan, actual)) {
                    throw MagicUidVerifyException()
                }
                return MagicUidResult(
                    family = family,
                    oldUid = oldUid,
                    newUid = newUid,
                    method = MagicUidMethod.CLASSIC_APDU,
                    readBack = actual ?: block0,
                    verified = actual != null,
                )
            }

            // 7-byte Gen3 cards: 90 F0 also rewrites block 0, while 90 FB only
            // changes the UID. The latter is the documented, safe path when the
            // block-0 layout cannot be confirmed.
            val response = try {
                transport.transceive(APDU_WRITE_UID + newUid)
            } catch (lost: TagLostException) {
                throw lost
            } catch (t: IOException) {
                throw MagicUidWriteException(t)
            }
            if (!isOk(response)) throw MagicUidUnsupportedException()

            val currentBlock = current ?: runCatching { readBlock0(transport::transceive) }.getOrNull()
            val plan = MagicUidLayout.buildClassic(family, newUid, currentBlock, oldUid)
            return MagicUidResult(
                family = family,
                oldUid = oldUid,
                newUid = newUid,
                method = MagicUidMethod.CLASSIC_APDU_UID,
                readBack = plan.classicBlock0,
                verified = false,
            )
        } finally {
            transport.close()
        }
    }

    private fun readBlock0(transceive: (ByteArray) -> ByteArray): ByteArray {
        val response = transceive(byteArrayOf(0x30, 0x00))
        if (response.size < 16) throw MagicUidReadException()
        return response.copyOf(16)
    }

    // -----------------------------------------------------------------------
    // MIFARE Ultralight
    // -----------------------------------------------------------------------

    private fun writeUltralight(tag: Tag, newUid: ByteArray): MagicUidResult {
        val oldUid = tag.id.copyOf()
        val transport = AndroidUltralightTransport(tag)
        try {
            val before = try {
                readPages(transport::transceive)
            } catch (lost: TagLostException) {
                throw lost
            } catch (t: IOException) {
                throw MagicUidReadException(t)
            }
            val plan = MagicUidLayout.buildUltralight(newUid, before)
            val pages = plan.ultralightPages ?: throw MagicUidWriteException()

            for ((index, page) in pages.withIndex()) {
                val command = byteArrayOf(0xA2.toByte(), index.toByte()) + page
                val response = try {
                    transport.transceive(command)
                } catch (lost: TagLostException) {
                    throw lost
                } catch (t: IOException) {
                    throw MagicUidWriteException(t)
                }
                // Type 2 WRITE answers with ACK 0x0A. A few clones return
                // nothing when the Android stack swallows the ACK.
                if (response.isNotEmpty() && response[0] != 0x0A.toByte()) {
                    throw MagicUidWriteException()
                }
            }

            val after = try {
                readPages(transport::transceive)
            } catch (lost: TagLostException) {
                throw lost
            } catch (t: IOException) {
                throw MagicUidVerifyException(t)
            }
            if (!MagicUidLayout.verifyUltralight(plan, after)) {
                throw MagicUidVerifyException()
            }
            return MagicUidResult(
                family = MagicUidFamily.ULTRALIGHT_EV1,
                oldUid = oldUid,
                newUid = newUid,
                method = MagicUidMethod.ULTRALIGHT_DIRECT,
                readBack = after.copyOf(12),
                verified = true,
            )
        } finally {
            transport.close()
        }
    }

    private fun readPages(transceive: (ByteArray) -> ByteArray): ByteArray {
        val response = transceive(byteArrayOf(0x30, 0x00))
        if (response.size < 12) throw MagicUidReadException()
        return response.copyOf(16.coerceAtMost(response.size))
    }

    private fun isOk(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
}
