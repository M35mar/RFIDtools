// NDEF reading for the inspector: the Type 2 TLV container and the record
// format, enough to show what a tag advertises (text, URI, MIME, smart poster).
// The parser is total: malformed or truncated data produces a status, never an
// exception, because the input comes from an unknown card.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

/** What a record carries, after the payload has been decoded. */
enum class NdefKind {
    TEXT,
    URI,
    MIME,
    EXTERNAL,
    SMART_POSTER,
    EMPTY,
    UNKNOWN,
}

class NdefRecord(
    val kind: NdefKind,
    /** Record type: `T`, `U`, `Sp`, a MIME type, an external name, ... */
    val type: String,
    /** Decoded text or URI. Null when the payload is binary or unknown. */
    val text: String?,
    val payloadBytes: Int,
)

enum class NdefStatus {
    FOUND,
    /** No NDEF TLV in the data area. */
    ABSENT,
    /** A TLV or record claims more bytes than the tag holds. */
    TRUNCATED,
    /** The bytes are not a valid NDEF message. */
    INVALID,
}

class NdefContent(
    val records: List<NdefRecord>,
    val bytes: Int,
    val status: NdefStatus,
) {
    val found: Boolean get() = status == NdefStatus.FOUND

    companion object {
        val absent = NdefContent(emptyList(), 0, NdefStatus.ABSENT)
    }
}

object NdefParser {

    private const val TLV_NULL = 0x00
    private const val TLV_NDEF = 0x03
    private const val TLV_TERMINATOR = 0xFE

    /** Chunks nest one level in practice; the limit stops a crafted card. */
    private const val MAX_DEPTH = 2

    private val URI_PREFIXES = listOf(
        "", // 0x00
        "http://www.",
        "https://www.",
        "http://",
        "https://",
        "tel:",
        "mailto:",
        "ftp://anonymous:anonymous@",
        "ftp://ftp.",
        "ftps://",
        "sftp://",
        "smb://",
        "nfs://",
        "ftp://",
        "dav://",
        "news:",
        "telnet://",
        "imap:",
        "rtsp://",
        "urn:",
        "pop:",
        "sip:",
        "sips:",
        "tftp:",
        "btspp://",
        "btl2cap://",
        "btgoep://",
        "tcpobex://",
        "irdaobex://",
        "file://",
        "urn:epc:id:",
        "urn:epc:tag:",
        "urn:epc:pat:",
        "urn:epc:raw:",
        "urn:epc:",
        "urn:nfc:",
    )

    /**
     * Walks the Type 2 TLV area and parses the first NDEF TLV. Null TLVs and
     * lock/memory TLVs are skipped; the terminator ends the area.
     */
    fun parseTlv(data: ByteArray): NdefContent {
        var position = 0
        while (position < data.size) {
            val type = data[position].toInt() and 0xFF
            if (type == TLV_TERMINATOR) return NdefContent.absent
            if (type == TLV_NULL) {
                position++
                continue
            }
            if (position + 1 >= data.size) return truncated(0)
            var length = data[position + 1].toInt() and 0xFF
            var header = 2
            if (length == 0xFF) {
                if (position + 3 >= data.size) return truncated(0)
                length = ((data[position + 2].toInt() and 0xFF) shl 8) or
                    (data[position + 3].toInt() and 0xFF)
                header = 4
            }
            val start = position + header
            val end = start + length
            if (end > data.size) return truncated(data.size - start)
            if (type == TLV_NDEF) return parseMessage(data, start, end)
            position = end
        }
        return NdefContent.absent
    }

    /** Parses one NDEF message that already lives between [start] and [end]. */
    fun parseMessage(data: ByteArray, start: Int = 0, end: Int = data.size): NdefContent {
        if (end <= start) return NdefContent.absent
        val records = mutableListOf<NdefRecord>()
        var position = start
        var chunk: Chunk? = null

        while (position < end) {
            if (position + 2 > end) return truncated(end - position)
            val flags = data[position].toInt() and 0xFF
            val typeLength = data[position + 1].toInt() and 0xFF
            val short = flags and 0x10 != 0
            val hasId = flags and 0x08 != 0
            var cursor = position + 2

            val payloadLength: Int
            if (short) {
                if (cursor >= end) return truncated(end - position)
                payloadLength = data[cursor].toInt() and 0xFF
                cursor++
            } else {
                if (cursor + 4 > end) return truncated(end - position)
                payloadLength = readInt32(data, cursor)
                cursor += 4
            }

            val idLength = if (hasId) {
                if (cursor >= end) return truncated(end - position)
                val value = data[cursor].toInt() and 0xFF
                cursor++
                value
            } else {
                0
            }

            val typeStart = cursor
            val typeEnd = typeStart + typeLength
            val payloadStart = typeEnd + idLength
            val payloadEnd = payloadStart + payloadLength
            if (payloadEnd > end) return truncated(end - position)

            val tnf = flags and 0x07
            val type = String(data, typeStart, typeLength, Charsets.UTF_8)
            val payload = data.copyOfRange(payloadStart, payloadEnd)
            val chunked = flags and 0x20 != 0
            if (payloadLength < 0 || typeLength < 0 || idLength < 0) {
                return NdefContent(records, end - start, NdefStatus.INVALID)
            }

            val current = chunk
            val merged = if (current == null) {
                if (tnf == 0x06) return NdefContent(records, end - start, NdefStatus.INVALID)
                Chunk(tnf, type, payload)
            } else {
                if (tnf != 0x06) return NdefContent(records, end - start, NdefStatus.INVALID)
                current.appended(payload)
            }
            chunk = merged

            position = payloadEnd
            if (chunked) continue

            records += decode(merged, depth = 0)
            chunk = null
            if (flags and 0x40 != 0) break
        }

        if (chunk != null) return truncated(end - start)
        if (records.isEmpty()) return NdefContent(records, end - start, NdefStatus.INVALID)
        return NdefContent(records, end - start, NdefStatus.FOUND)
    }

    private fun decode(chunk: Chunk, depth: Int): NdefRecord {
        val payload = chunk.payload
        return when (chunk.tnf) {
            0x00 -> if (chunk.type.isEmpty() && payload.isEmpty()) {
                NdefRecord(NdefKind.EMPTY, "", null, 0)
            } else {
                NdefRecord(NdefKind.UNKNOWN, chunk.type, null, payload.size)
            }

            0x01 -> when (chunk.type) {
                "T" -> decodeText(payload)
                "U" -> decodeUri(payload)
                "Sp" -> decodeSmartPoster(payload, depth)
                else -> NdefRecord(
                    NdefKind.UNKNOWN,
                    chunk.type,
                    null,
                    payload.size,
                )
            }

            0x02 -> NdefRecord(
                kind = NdefKind.MIME,
                type = chunk.type,
                text = if (chunk.type.startsWith("text/", ignoreCase = true)) {
                    String(payload, Charsets.UTF_8)
                } else {
                    null
                },
                payloadBytes = payload.size,
            )

            0x03 -> NdefRecord(
                NdefKind.URI,
                chunk.type,
                chunk.type,
                payload.size,
            )

            0x04 -> NdefRecord(NdefKind.EXTERNAL, chunk.type, null, payload.size)

            else -> NdefRecord(NdefKind.UNKNOWN, chunk.type, null, payload.size)
        }
    }

    private fun decodeText(payload: ByteArray): NdefRecord {
        if (payload.isEmpty()) return NdefRecord(NdefKind.TEXT, "T", "", 0)
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val languageLength = status and 0x3F
        val textStart = minOf(1 + languageLength, payload.size)
        val text = if (utf16) {
            String(payload, textStart, payload.size - textStart, Charsets.UTF_16BE)
        } else {
            String(payload, textStart, payload.size - textStart, Charsets.UTF_8)
        }
        return NdefRecord(NdefKind.TEXT, "T", text, payload.size)
    }

    private fun decodeUri(payload: ByteArray): NdefRecord {
        if (payload.isEmpty()) return NdefRecord(NdefKind.URI, "U", "", 0)
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrNull(code).orEmpty()
        val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        return NdefRecord(NdefKind.URI, "U", prefix + rest, payload.size)
    }

    private fun decodeSmartPoster(payload: ByteArray, depth: Int): NdefRecord {
        if (depth >= MAX_DEPTH) {
            return NdefRecord(NdefKind.SMART_POSTER, "Sp", null, payload.size)
        }
        val nested = parseMessage(payload).records
        val text = nested.mapNotNull { it.text }.joinToString(" · ").ifEmpty { null }
        return NdefRecord(NdefKind.SMART_POSTER, "Sp", text, payload.size)
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun truncated(bytes: Int) =
        NdefContent(emptyList(), bytes.coerceAtLeast(0), NdefStatus.TRUNCATED)

    private class Chunk(
        val tnf: Int,
        val type: String,
        val payload: ByteArray,
    ) {
        fun appended(more: ByteArray): Chunk =
            Chunk(tnf, type, payload + more)
    }
}
