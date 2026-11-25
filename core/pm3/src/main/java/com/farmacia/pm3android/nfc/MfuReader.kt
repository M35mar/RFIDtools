// Reads MIFARE Ultralight (Classic, C, EV1, AES) and NTAG21x (210/212/213/215/216)
// tags the way `hf mfu dump` does: GET_VERSION for geometry (with legacy page-probe
// fallback for UL/UL-C), optional PWD_AUTH, FAST_READ/READ page memory, counters
// with tearing flags, and READ_SIG. Only the transport is faked in tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.TagLostException
import java.io.IOException

class MfuReader(private val transport: UltralightTransport) {

    private companion object {
        const val CMD_READ = 0x30
        const val CMD_FAST_READ = 0x3A
        const val CMD_PWD_AUTH = 0x1B
        const val CMD_GET_VERSION = 0x60
        const val CMD_READ_CNT = 0x39
        const val CMD_READ_TEARING = 0x3E
        const val CMD_READ_SIG = 0x3C
        const val COUNTERS = 3
        const val SIGNATURE_BYTES = 32
        const val FAST_READ_WINDOW = 32
        const val LEGACY_MAX_PAGES = 48
    }

    /**
     * Full capture of the tag. [password] is only sent with PWD_AUTH and is
     * written into the dump like `-p` does; when null the tag must be readable
     * without authentication or [TagAuthException] is raised.
     */
    fun read(password: ByteArray? = null, progress: RestoreProgress? = null): MfuCapture {
        val version = tryReadVersion()
        if (version == null) {
            // Legacy MIFARE Ultralight (16 pages) or Ultralight C (48 pages)
            // without GET_VERSION: probe readable pages with READ 0x30.
            val legacyPages = readLegacyPages(progress)
            return MfuCapture(
                pages = legacyPages,
                version = ByteArray(8),
                signature = ByteArray(SIGNATURE_BYTES),
                counters = List(COUNTERS) { ByteArray(4) },
                pack = null,
                password = password,
            )
        }
        val pages = pageCountOf(version)
        val pack = password?.let { authenticate(it) }
        if (password != null && pack == null) {
            throw TagAuthException("il tag ha rifiutato la password")
        }
        val data = readPages(pages, password != null, progress)
        val counters = readCounters()
        return MfuCapture(
            pages = data,
            version = version,
            signature = readSignature(),
            counters = counters,
            pack = pack,
            password = password,
        )
    }

    // -----------------------------------------------------------------------
    // Tag commands
    // -----------------------------------------------------------------------

    private fun tryReadVersion(): ByteArray? {
        val response = try {
            transport.transceive(byteArrayOf(CMD_GET_VERSION.toByte()))
        } catch (e: IOException) {
            if (e is TagLostException) throw e
            return null
        }
        if (response.size < 8) return null
        return response.copyOf(8)
    }

    /**
     * Maps GET_VERSION (product type byte 2, storage code byte 6) to total
     * page count across Ultralight EV1, Ultralight AES and NTAG21x families.
     */
    private fun pageCountOf(version: ByteArray): Int {
        val productType = version[2].toInt() and 0xFF
        val storageCode = version[6].toInt() and 0xFF
        return when (productType) {
            0x03 -> when (storageCode) {
                0x0B -> 20  // MF0UL11 (48 B)
                0x0E -> 41  // MF0UL21 (128 B)
                0x0F -> 41  // MF0AES(H)20 (Ultralight AES)
                else -> throw TagUnusableException(
                    "variante Ultralight non supportata: 0x%02X".format(storageCode),
                )
            }
            0x04 -> when (storageCode) {
                0x0B -> 20  // NTAG210 / NTAG210u
                0x0E -> 41  // NTAG212
                0x0F -> 45  // NTAG213 / 213F / 213 TT
                0x11 -> 135 // NTAG215
                0x13 -> 231 // NTAG216 / 216F
                else -> throw TagUnusableException(
                    "variante NTAG non supportata: 0x%02X".format(storageCode),
                )
            }
            else -> when (storageCode) {
                0x0B -> 20
                0x0E -> 41
                0x0F -> 45
                0x11 -> 135
                0x13 -> 231
                else -> throw TagUnusableException(
                    "variante Type 2 non supportata: tipo 0x%02X, storage 0x%02X"
                        .format(productType, storageCode),
                )
            }
        }
    }

    private fun readLegacyPages(progress: RestoreProgress?): List<ByteArray> {
        // Attempt default NXP 2K3DES mutual auth first in case this is an Ultralight C
        // with read/write protection enabled under the factory key ("BREAKMEIFYOUCAN!").
        runCatching { UltralightC3Des.authenticate(transport, UltralightC3Des.DEFAULT_KEY) }
        val pages = mutableListOf<ByteArray>()
        var start = 0
        while (start < LEGACY_MAX_PAGES) {
            val chunk = try {
                readChunk(start)
            } catch (e: IOException) {
                if (e is TagLostException) throw e
                break
            }
            for (offset in 0 until 4) {
                if (start + offset < LEGACY_MAX_PAGES) {
                    pages += chunk.copyOfRange(offset * 4, offset * 4 + 4)
                }
            }
            start += 4
            progress?.onProgress(pages.size, LEGACY_MAX_PAGES)
        }
        if (pages.size < 16) {
            throw TagUnusableException("GET_VERSION non risponde e lettura pagine fallita")
        }
        return pages
    }

    private fun authenticate(password: ByteArray): ByteArray? = try {
        val response = transport.transceive(byteArrayOf(CMD_PWD_AUTH.toByte()) + password)
        if (response.size >= 2) response.copyOf(2) else null
    } catch (e: IOException) {
        if (e is TagLostException) throw e
        null
    }

    private fun readPages(
        total: Int,
        authenticated: Boolean,
        progress: RestoreProgress?,
    ): List<ByteArray> {
        // On larger NTAG215/216 tags, FAST_READ (0x3A) cuts transceive calls
        // by ~8x. Fall back seamlessly to standard 4-page READ (0x30) if the
        // chip or controller refuses FAST_READ.
        if (total > 45) {
            val fast = tryFastReadPages(total, progress)
            if (fast != null) return fast
        }
        val pages = MutableList(total) { ByteArray(4) }
        var done = 0
        for (start in mfuReadStarts(total)) {
            val chunk = try {
                readChunk(start)
            } catch (e: IOException) {
                if (e is TagLostException) throw e
                if (!authenticated) {
                    throw TagAuthException("la pagina $start richiede la password")
                }
                throw TagUnusableException("lettura interrotta alla pagina $start", e)
            }
            for (offset in 0 until 4) {
                val page = start + offset
                if (page < total) {
                    chunk.copyInto(pages[page], 0, offset * 4, offset * 4 + 4)
                }
            }
            done = minOf(done + 4, total)
            progress?.onProgress(done, total)
        }
        return pages
    }

    private fun tryFastReadPages(total: Int, progress: RestoreProgress?): List<ByteArray>? {
        val pages = MutableList(total) { ByteArray(4) }
        var start = 0
        while (start < total) {
            val end = minOf(start + FAST_READ_WINDOW - 1, total - 1)
            val expectedBytes = (end - start + 1) * 4
            val response = try {
                transport.transceive(
                    byteArrayOf(CMD_FAST_READ.toByte(), start.toByte(), end.toByte()),
                )
            } catch (e: IOException) {
                if (e is TagLostException) throw e
                return null
            }
            if (response.size != expectedBytes) return null
            for (i in 0..(end - start)) {
                response.copyInto(pages[start + i], 0, i * 4, i * 4 + 4)
            }
            start = end + 1
            progress?.onProgress(start, total)
        }
        return pages
    }

    private fun readChunk(start: Int): ByteArray {
        val response = transport.transceive(
            byteArrayOf(CMD_READ.toByte(), start.toByte()),
        )
        if (response.size != 16) {
            throw IOException("READ $start: risposta di ${response.size} byte")
        }
        return response
    }

    /**
     * Counters and tearing flags. Failures keep the entry at zero, which is
     * what the client writes when the tag does not answer.
     */
    private fun readCounters(): List<ByteArray> = (0 until COUNTERS).map { index ->
        val entry = ByteArray(4)
        val counter = try {
            transport.transceive(byteArrayOf(CMD_READ_CNT.toByte(), index.toByte()))
        } catch (e: IOException) {
            if (e is TagLostException) throw e
            null
        }
        if (counter != null && counter.size >= 3) counter.copyInto(entry, 0, 0, 3)
        val tearing = try {
            transport.transceive(byteArrayOf(CMD_READ_TEARING.toByte(), index.toByte()))
        } catch (e: IOException) {
            if (e is TagLostException) throw e
            null
        }
        if (tearing != null && tearing.isNotEmpty()) entry[3] = tearing[0]
        entry
    }

    /** READ_SIG, retried once: clones sometimes drop the first request. */
    private fun readSignature(): ByteArray {
        repeat(2) {
            val response = try {
                transport.transceive(byteArrayOf(CMD_READ_SIG.toByte(), 0x00))
            } catch (e: IOException) {
                if (e is TagLostException) throw e
                null
            }
            if (response != null && response.size >= SIGNATURE_BYTES) {
                return response.copyOf(SIGNATURE_BYTES)
            }
        }
        return ByteArray(SIGNATURE_BYTES)
    }
}

/**
 * Start pages of the READ chunks. The last chunk starts at `total - 4` because
 * reading past the last page returns a NAK, so an unaligned tail (41 pages)
 * must overlap the previous chunk.
 */
internal fun mfuReadStarts(total: Int): List<Int> {
    require(total >= 4) { "a dump needs at least four pages" }
    val last = total - 4
    val starts = mutableListOf<Int>()
    var start = 0
    while (start <= last) {
        starts += start
        start += 4
    }
    if (starts.lastOrNull() != last) starts += last
    return starts
}
