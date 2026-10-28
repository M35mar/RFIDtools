// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefParserTest {

    private fun record(
        tnf: Int = 0x01,
        type: String = "T",
        payload: ByteArray,
        mb: Boolean = true,
        me: Boolean = true,
        short: Boolean = payload.size < 0x100,
        chunked: Boolean = false,
    ): ByteArray {
        var flags = tnf
        if (mb) flags = flags or 0x80
        if (me) flags = flags or 0x40
        if (short) flags = flags or 0x10
        if (chunked) flags = flags or 0x20
        val typeBytes = type.toByteArray()
        val length = if (short) {
            byteArrayOf(payload.size.toByte())
        } else {
            byteArrayOf(
                ((payload.size ushr 24) and 0xFF).toByte(),
                ((payload.size ushr 16) and 0xFF).toByte(),
                ((payload.size ushr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte(),
            )
        }
        return byteArrayOf(flags.toByte(), typeBytes.size.toByte()) +
            length + typeBytes + payload
    }

    private fun textPayload(text: String, language: String = "en"): ByteArray =
        byteArrayOf(language.length.toByte()) + language.toByteArray() + text.toByteArray()

    private fun tlv(message: ByteArray, prefix: ByteArray = ByteArray(0)): ByteArray {
        val header = if (message.size < 0xFF) {
            byteArrayOf(0x03, message.size.toByte())
        } else {
            byteArrayOf(
                0x03,
                0xFF.toByte(),
                ((message.size ushr 8) and 0xFF).toByte(),
                (message.size and 0xFF).toByte(),
            )
        }
        return prefix + header + message + byteArrayOf(0xFE.toByte())
    }

    @Test
    fun `text record is decoded`() {
        val content = NdefParser.parseTlv(tlv(record(payload = textPayload("Ciao"))))
        assertTrue(content.found)
        assertEquals(NdefStatus.FOUND, content.status)
        assertEquals(1, content.records.size)
        val record = content.records[0]
        assertEquals(NdefKind.TEXT, record.kind)
        assertEquals("T", record.type)
        assertEquals("Ciao", record.text)
    }

    @Test
    fun `uri record applies the prefix table`() {
        val payload = byteArrayOf(0x04) + "example.com/x".toByteArray()
        val content = NdefParser.parseTlv(tlv(record(type = "U", payload = payload)))
        assertEquals("https://example.com/x", content.records[0].text)
        assertEquals(NdefKind.URI, content.records[0].kind)
    }

    @Test
    fun `unknown uri prefix falls back to the raw text`() {
        val payload = byteArrayOf(0x7F) + "thing".toByteArray()
        val content = NdefParser.parseTlv(tlv(record(type = "U", payload = payload)))
        assertEquals("thing", content.records[0].text)
    }

    @Test
    fun `mime text is decoded and binary is not`() {
        val text = NdefParser.parseTlv(
            tlv(record(tnf = 0x02, type = "text/plain", payload = "hello".toByteArray())),
        )
        assertEquals(NdefKind.MIME, text.records[0].kind)
        assertEquals("hello", text.records[0].text)

        val binary = NdefParser.parseTlv(
            tlv(record(tnf = 0x02, type = "image/png", payload = byteArrayOf(1, 2, 3))),
        )
        assertEquals(NdefKind.MIME, binary.records[0].kind)
        assertNull(binary.records[0].text)
        assertEquals(3, binary.records[0].payloadBytes)
    }

    @Test
    fun `external and absolute uri records keep their type`() {
        val external = NdefParser.parseTlv(
            tlv(record(tnf = 0x04, type = "android.com:pkg", payload = "x".toByteArray())),
        )
        assertEquals(NdefKind.EXTERNAL, external.records[0].kind)
        assertEquals("android.com:pkg", external.records[0].type)
        assertNull(external.records[0].text)

        val absolute = NdefParser.parseTlv(
            tlv(record(tnf = 0x03, type = "https://example.org", payload = ByteArray(0))),
        )
        assertEquals(NdefKind.URI, absolute.records[0].kind)
        assertEquals("https://example.org", absolute.records[0].text)
    }

    @Test
    fun `empty and unknown records are labelled`() {
        val content = NdefParser.parseTlv(
            tlv(
                record(tnf = 0x00, type = "", payload = ByteArray(0), me = false) +
                    record(tnf = 0x05, type = "x", payload = ByteArray(2) { 0x01 }),
            ),
        )
        assertEquals(2, content.records.size)
        assertEquals(NdefKind.EMPTY, content.records[0].kind)
        assertEquals(NdefKind.UNKNOWN, content.records[1].kind)
    }

    @Test
    fun `multiple records are all returned`() {
        val message = record(payload = textPayload("One"), me = false) +
            record(type = "U", payload = byteArrayOf(0x01) + "x.it".toByteArray())
        val content = NdefParser.parseTlv(tlv(message))
        assertEquals(2, content.records.size)
        assertEquals("One", content.records[0].text)
        assertEquals("http://www.x.it", content.records[1].text)
    }

    @Test
    fun `long records use a four byte length`() {
        val payload = textPayload("A".repeat(300))
        val content = NdefParser.parseTlv(tlv(record(payload = payload, short = false)))
        assertTrue(content.found)
        assertEquals("A".repeat(300), content.records[0].text)
    }

    @Test
    fun `long tlv uses the ff length form`() {
        val payload = textPayload("B".repeat(300))
        val content = NdefParser.parseTlv(tlv(record(payload = payload, short = false)))
        assertTrue(content.found)
        assertEquals(300, content.records[0].text?.length)
    }

    @Test
    fun `null and lock tlvs are skipped`() {
        val message = record(payload = textPayload("Skip"))
        val prefix = byteArrayOf(0x00, 0x01, 0x03, 0x00, 0x00, 0x00)
        val content = NdefParser.parseTlv(tlv(message, prefix))
        assertTrue(content.found)
        assertEquals("Skip", content.records[0].text)
    }

    @Test
    fun `terminator before ndef means absent`() {
        assertEquals(NdefStatus.ABSENT, NdefParser.parseTlv(byteArrayOf(0xFE.toByte())).status)
        assertEquals(NdefStatus.ABSENT, NdefParser.parseTlv(ByteArray(0)).status)
    }

    @Test
    fun `tlv that claims more bytes than the tag holds is truncated`() {
        val content = NdefParser.parseTlv(byteArrayOf(0x03, 0x10, 0x00, 0x00))
        assertEquals(NdefStatus.TRUNCATED, content.status)
        assertFalse(content.found)
    }

    @Test
    fun `record that overruns the message is truncated`() {
        // NDEF TLV of 3 bytes: flags + type length + payload length, no payload.
        val content = NdefParser.parseTlv(byteArrayOf(0x03, 0x03, 0x11, 0x01, 0x05))
        assertEquals(NdefStatus.TRUNCATED, content.status)
    }

    @Test
    fun `chunked records are concatenated`() {
        val full = textPayload("Chunked")
        val first = record(payload = full.copyOfRange(0, 4), me = false, chunked = true)
        val second = record(tnf = 0x06, type = "", payload = full.copyOfRange(4, full.size))
        val content = NdefParser.parseTlv(tlv(first + second))
        assertTrue(content.found)
        assertEquals("Chunked", content.records[0].text)
    }

    @Test
    fun `a continuation without a first chunk is invalid`() {
        val content = NdefParser.parseTlv(
            tlv(record(tnf = 0x06, type = "", payload = byteArrayOf(1))),
        )
        assertEquals(NdefStatus.INVALID, content.status)
    }

    @Test
    fun `smart poster surfaces the nested text`() {
        val nested = record(payload = textPayload("Title"), me = false) +
            record(type = "U", payload = byteArrayOf(0x04) + "x.it".toByteArray())
        val content = NdefParser.parseTlv(tlv(record(type = "Sp", payload = nested)))
        assertEquals(NdefKind.SMART_POSTER, content.records[0].kind)
        assertEquals("Title · https://x.it", content.records[0].text)
    }

    @Test
    fun `utf16 text is decoded`() {
        val body = "Ciao".toByteArray(Charsets.UTF_16BE)
        val payload = byteArrayOf((0x80 or 2).toByte(), 'e'.code.toByte(), 'n'.code.toByte()) + body
        val content = NdefParser.parseTlv(tlv(record(payload = payload)))
        assertEquals("Ciao", content.records[0].text)
    }

    @Test
    fun `a declared length is reported`() {
        val message = record(payload = textPayload("Size"))
        val content = NdefParser.parseTlv(tlv(message))
        assertEquals(message.size, content.bytes)
        assertNotNull(content.records.firstOrNull())
    }
}
