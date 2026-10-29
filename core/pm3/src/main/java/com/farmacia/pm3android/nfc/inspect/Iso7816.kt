// Small ISO 7816-4 helper shared by the probes and the APDU console. It
// handles the two status words that need another exchange: 61xx (GET
// RESPONSE) and 6Cxx (wrong Le, retry with the suggested length).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.ByteArrayOutputStream

class ApduParseException(message: String) : Exception(message)

object Iso7816 {

    const val SW_OK = 0x9000
    const val SW_END_OF_FILE = 0x6282
    const val SW_FILE_NOT_FOUND = 0x6A82

    class Response(val data: ByteArray, val sw: Int) {
        val ok: Boolean get() = sw == SW_OK
    }

    /** SELECT by DF name (P1 0x04, P2 0x00: return the FCI). */
    fun select(
        transport: IsoDepTransport,
        name: ByteArray,
        p1: Int = 0x04,
        p2: Int = 0x00,
    ): Response = exchange(transport, selectCommand(name, p1, p2))

    fun selectCommand(name: ByteArray, p1: Int = 0x04, p2: Int = 0x00): ByteArray =
        apdu(0x00, 0xA4, p1, p2, name, 0x00)

    /** SELECT by file identifier (P2 0x0C); P1 0x02 selects an EF under the current DF. */
    fun selectFile(transport: IsoDepTransport, fileId: Int, p1: Int = 0x00): Response =
        exchange(
            transport,
            byteArrayOf(
                0x00,
                0xA4.toByte(),
                p1.toByte(),
                0x0C,
                0x02,
                (fileId ushr 8).toByte(),
                fileId.toByte(),
            ),
        )

    /**
     * READ BINARY in chunks of at most 0xFF bytes. A short answer or an
     * end-of-file status stops the loop and returns what the card gave.
     */
    fun readBinary(transport: IsoDepTransport, offset: Int, length: Int): Response {
        if (length <= 0) return Response(ByteArray(0), SW_OK)
        val out = ByteArrayOutputStream()
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, 0xFF)
            val response = exchange(
                transport,
                byteArrayOf(
                    0x00,
                    0xB0.toByte(),
                    ((position ushr 8) and 0xFF).toByte(),
                    (position and 0xFF).toByte(),
                    chunk.toByte(),
                ),
            )
            out.write(response.data)
            position += response.data.size
            remaining -= response.data.size
            if (response.sw != SW_OK && response.sw != SW_END_OF_FILE) {
                return Response(out.toByteArray(), response.sw)
            }
            if (response.sw == SW_END_OF_FILE || response.data.size < chunk) break
        }
        return Response(out.toByteArray(), SW_OK)
    }

    /** Sends one APDU and drains 61xx / 6Cxx until a final status word. */
    fun exchange(transport: IsoDepTransport, command: ByteArray): Response {
        val out = ByteArrayOutputStream()
        var current = command
        var response = transport.transceive(current)
        var guard = 0
        while (guard++ < MAX_ROUNDS) {
            if (response.size < 2) return Response(out.toByteArray(), 0)
            val sw = swOf(response)
            out.write(response, 0, response.size - 2)

            when (sw ushr 8) {
                0x61 -> {
                    response = transport.transceive(
                        byteArrayOf(
                            0x00,
                            0xC0.toByte(),
                            0x00,
                            0x00,
                            (sw and 0xFF).toByte(),
                        ),
                    )
                }

                0x6C -> {
                    if (current.size < 5) return Response(out.toByteArray(), sw)
                    val retry = current.copyOf()
                    retry[retry.size - 1] = (sw and 0xFF).toByte()
                    current = retry
                    out.reset()
                    response = transport.transceive(retry)
                }

                else -> return Response(out.toByteArray(), sw)
            }
        }
        return Response(out.toByteArray(), swOf(response))
    }

    /** Builds a short APDU, adding Le only when it is not null. */
    fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray, le: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(cla)
        out.write(ins)
        out.write(p1)
        out.write(p2)
        if (data.isNotEmpty()) {
            out.write(data.size)
            out.write(data)
        }
        if (le != null) out.write(le)
        return out.toByteArray()
    }

    /**
     * Parses a hex APDU typed by the user. Spaces, tabs and ':' are ignored;
     * a '#' starts a comment. The result must be at least a 4-byte header.
     */
    fun parseApdu(raw: String): ByteArray {
        val compact = raw.substringBefore('#').filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (compact.isEmpty()) throw ApduParseException("empty APDU")
        if (compact.length % 2 != 0) throw ApduParseException("odd number of hex digits")
        if (!compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw ApduParseException("not hexadecimal")
        }
        val bytes = ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        if (bytes.size < 4) throw ApduParseException("APDU shorter than 4 bytes")
        return bytes
    }

    fun swOf(response: ByteArray): Int {
        if (response.size < 2) return 0
        return ((response[response.size - 2].toInt() and 0xFF) shl 8) or
            (response[response.size - 1].toInt() and 0xFF)
    }

    /**
     * Short description of a status word. It follows the ISO 7816-4 table used
     * by the Proxmark3 client (`apduinfo.c`), restricted to the common cases.
     */
    fun swDescription(sw: Int): String = when (sw) {
        0x9000 -> "OK"
        0x6200 -> "Warning: no information"
        0x6282 -> "End of file reached"
        0x6283 -> "Selected file invalidated"
        0x6300 -> "Authentication failed"
        0x6381 -> "Warning: file filled up"
        0x6700 -> "Wrong length"
        0x6800 -> "No information"
        0x6881 -> "Logical channel not supported"
        0x6882 -> "Secure messaging not supported"
        0x6981 -> "Command incompatible with file structure"
        0x6982 -> "Security status not satisfied"
        0x6983 -> "Authentication method blocked"
        0x6984 -> "Reference data invalidated"
        0x6985 -> "Conditions of use not satisfied"
        0x6986 -> "Command not allowed"
        0x6A80 -> "Incorrect data"
        0x6A81 -> "Function not supported"
        0x6A82 -> "File or application not found"
        0x6A83 -> "Record not found"
        0x6A84 -> "Not enough memory"
        0x6A85 -> "Lc inconsistent with TLV structure"
        0x6A86 -> "Incorrect P1/P2"
        0x6A87 -> "Lc inconsistent with P1/P2"
        0x6A88 -> "Referenced data not found"
        0x6B00 -> "Wrong parameters P1/P2"
        0x6D00 -> "Instruction not supported"
        0x6E00 -> "Class not supported"
        0x6F00 -> "No precise diagnosis"
        else -> when {
            sw ushr 8 == 0x63 && (sw and 0xF0) == 0xC0 -> "Wrong PIN, ${sw and 0x0F} tries left"
            sw ushr 8 == 0x61 -> "More data available"
            sw ushr 8 == 0x6C -> "Wrong Le, use ${sw and 0xFF}"
            else -> "Unknown status"
        }
    }

    private const val MAX_ROUNDS = 8
}
