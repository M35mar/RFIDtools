// eMRTD / eID probe: names an ICAO 9303 document (passport, ID card) or a
// national eID applet before any authenticated session. EF.CardAccess tells
// whether PACE is advertised; GET CHALLENGE tells whether BAC is still enabled.
// Reading DG1..DG16 needs the MRZ or the PACE password, so it is left to the
// APDU console and to a future session implementation.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.nfc.inspect.EmrtdDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.Iso7816
import com.farmacia.pm3android.nfc.inspect.IsoDepTransport
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object EmrtdProbe : HfProbe {

    override val id = "emrtd"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withIsoDep { transport ->
            val abort = context.abort
            for (candidate in CANDIDATES) {
                val selection = try {
                    // ICAO 9303 selects the application with P2 0x0C (no FCI).
                    Iso7816.select(transport, candidate.aid, p2 = 0x0C)
                } catch (error: Throwable) {
                    if (abort(error)) throw error
                    continue
                }
                if (!selection.ok) continue

                val cardAccess = readCardAccess(transport, abort)
                val pace = when (cardAccess) {
                    is CardAccessResult.Read -> hasPaceOid(cardAccess.data)
                    CardAccessResult.Absent -> false
                    CardAccessResult.Unknown -> null
                }
                val bac = getChallenge(transport)

                val details = EmrtdDetails(
                    aid = candidate.aid,
                    label = candidate.label,
                    cardAccess = (cardAccess as? CardAccessResult.Read)?.data,
                    // null stays null: an unreadable EF.CardAccess means
                    // "unknown", never "PACE unsupported".
                    paceSupported = pace,
                    bacSupported = bac,
                    family = candidate.family,
                )
                return@withIsoDep ProbeOutcome(
                    details = details,
                    identity = TagIdentifier.emrtdIdentity(
                        snapshot = context.snapshot,
                        details = details,
                        family = candidate.family,
                        label = candidate.label,
                    ),
                )
            }
            null
        }

    /** EF.CardAccess (0x011C) is readable before PACE on a PACE card. */
    private fun readCardAccess(
        transport: IsoDepTransport,
        abort: (Throwable) -> Boolean,
    ): CardAccessResult {
        // P1 0x02 is an EF under the current DF; a few documents keep it in MF.
        var sawError = false
        for (p1 in intArrayOf(0x02, 0x00)) {
            val selected = try {
                Iso7816.selectFile(transport, 0x011C, p1 = p1)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                return CardAccessResult.Unknown
            }
            if (!selected.ok) {
                // Some cards reject P1 0x02: try the other location before
                // deciding that the file is absent.
                if (selected.sw != Iso7816.SW_FILE_NOT_FOUND) sawError = true
                continue
            }

            val response = try {
                Iso7816.readBinary(transport, 0, MAX_CARD_ACCESS)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                return CardAccessResult.Unknown
            }
            if (response.sw != Iso7816.SW_OK || response.data.isEmpty()) {
                return CardAccessResult.Unknown
            }
            // A full buffer may leave bytes unread, so the file is not proven
            // complete and PACE stays unknown.
            if (response.data.size >= MAX_CARD_ACCESS) return CardAccessResult.Unknown
            return CardAccessResult.Read(response.data)
        }
        return if (sawError) CardAccessResult.Unknown else CardAccessResult.Absent
    }

    /** GET CHALLENGE (00 84 00 00 08) answers on a BAC-enabled document. */
    private fun getChallenge(transport: IsoDepTransport): Boolean {
        val response = runCatching {
            Iso7816.exchange(
                transport,
                byteArrayOf(0x00, 0x84.toByte(), 0x00, 0x00, 0x08),
            )
        }.getOrNull() ?: return false
        return response.sw == Iso7816.SW_OK && response.data.size == 8
    }

    /**
     * True when the CardAccess structure carries an id-PACE OID. id-TA and the
     * chip-authentication OIDs share the 0.4.0.127.0.7.2.2 prefix, so the whole
     * id-PACE arc ending in 4 must match.
     */
    private fun hasPaceOid(cardAccess: ByteArray): Boolean =
        findPaceOid(cardAccess, 0, cardAccess.size)

    private fun findPaceOid(data: ByteArray, start: Int, end: Int): Boolean {
        var index = start
        while (index < end) {
            val tag = data[index].toInt() and 0xFF
            index++
            if (tag and 0x1F == 0x1F) {
                while (index < end && data[index].toInt() and 0x80 != 0) index++
                index++
            }
            if (index >= end) return false
            val first = data[index].toInt() and 0xFF
            index++
            var length = first
            if (first == 0x81) {
                if (index >= end) return false
                length = data[index].toInt() and 0xFF
                index++
            } else if (first == 0x82) {
                if (index + 1 >= end) return false
                length = ((data[index].toInt() and 0xFF) shl 8) or
                    (data[index + 1].toInt() and 0xFF)
                index += 2
            } else if (first and 0x80 != 0) {
                return false
            }
            if (index + length > end) return false
            if (tag == 0x06 && hasPrefix(data, index, length, PACE_OID)) return true
            val constructed = tag and 0x20 != 0
            if (constructed && findPaceOid(data, index, index + length)) return true
            index += length
        }
        return false
    }

    private fun hasPrefix(data: ByteArray, offset: Int, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size || offset + prefix.size > data.size) return false
        for (index in prefix.indices) {
            if (data[offset + index] != prefix[index]) return false
        }
        return true
    }

    /** EF.CardAccess read: the complete bytes, a proven absence, or unknown. */
    private sealed class CardAccessResult {
        class Read(val data: ByteArray) : CardAccessResult()
        object Absent : CardAccessResult()
        object Unknown : CardAccessResult()
    }

    private class Candidate(
        val aidHex: String,
        val label: String,
        val family: TagFamily,
    ) {
        val aid: ByteArray get() = aidHex.hexToBytesOrNull() ?: ByteArray(0)
    }

    private val CANDIDATES = listOf(
        Candidate("A0000002471001", "eMRTD · passport / ID card (ICAO 9303)", TagFamily.EMRTD),
        Candidate("A000000167413000FF", "German eID (eIDAS)", TagFamily.EID),
        Candidate("A0000000309000", "CIE 3.0 (Italian eID)", TagFamily.EID),
    )

    /** id-PACE: 0.4.0.127.0.7.2.2.4, the prefix of every PACE OID. */
    private val PACE_OID = byteArrayOf(
        0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04,
    )

    /** A few hundred bytes in practice; the cap bounds a crafted file. */
    private const val MAX_CARD_ACCESS = 4096
}
