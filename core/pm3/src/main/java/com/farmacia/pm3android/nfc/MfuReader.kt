// Reads a MIFARE Ultralight EV1 the way `hf mfu dump` does: GET_VERSION for the
// geometry, optional PWD_AUTH, the page memory, the three counters with their
// tearing flags and READ_SIG. Only the transport is faked in tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.TagLostException
import java.io.IOException

class MfuReader(private val transport: UltralightTransport) {

    private companion object {
        const val CMD_READ = 0x30
        const val CMD_PWD_AUTH = 0x1B
        const val CMD_GET_VERSION = 0x60
        const val CMD_READ_CNT = 0x39
        const val CMD_READ_TEARING = 0x3E
        const val CMD_READ_SIG = 0x3C
        const val COUNTERS = 3
        const val SIGNATURE_BYTES = 32
    }

    /**
     * Full capture of the tag. [password] is only sent with PWD_AUTH and is
     * written into the dump like `-p` does; when null the tag must be readable
     * without authentication or [TagAuthException] is raised.
     */
    fun read(password: ByteArray? = null, progress: RestoreProgress? = null): MfuCapture {
        val version = readVersion()
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

    private fun readVersion(): ByteArray {
        val response = try {
            transport.transceive(byteArrayOf(CMD_GET_VERSION.toByte()))
        } catch (e: IOException) {
            if (e is TagLostException) throw e
            throw TagUnusableException("GET_VERSION non risponde", e)
        }
        if (response.size < 8) {
            throw TagUnusableException("GET_VERSION: risposta di ${response.size} byte")
        }
        return response.copyOf(8)
    }

    /** Byte 6 of GET_VERSION is the memory size: 0x0B = 20 pages, 0x0E = 41. */
    private fun pageCountOf(version: ByteArray): Int = when (version[6].toInt() and 0xFF) {
        0x0B -> 20
        0x0E -> 41
        else -> throw TagUnusableException(
            "solo MF0UL11 (48 B) e MF0UL21 (128 B): " +
                "il tag dichiara 0x%02X".format(version[6].toInt() and 0xFF),
        )
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
