// Pure-Kotlin JVM tests for NdefBuilder (URI prefix compression, Text RTD,
// Wi-Fi WSC credential TLV) and Type2InspectProbe + ISO 15693 extended commands.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import com.farmacia.pm3android.nfc.inspect.CommandLog
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.HfProbeRegistry
import com.farmacia.pm3android.nfc.inspect.Iso15693
import com.farmacia.pm3android.nfc.inspect.IsoDepTransport
import com.farmacia.pm3android.nfc.inspect.NfcBTransport
import com.farmacia.pm3android.nfc.inspect.NfcFTransport
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.NfcVTransport
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.RawTagTransport
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagSession
import com.farmacia.pm3android.nfc.inspect.TagSnapshot
import com.farmacia.pm3android.nfc.inspect.VersionInfo
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NdefBuilderTest {

    @Test
    fun `uri payload compresses https and https www prefixes`() {
        val https = NdefBuilder.buildUriPayload("https://example.com/nfc")
        assertEquals(0x04, https[0].toInt() and 0xFF)
        assertEquals("example.com/nfc", String(https.copyOfRange(1, https.size), Charsets.UTF_8))

        val httpsWww = NdefBuilder.buildUriPayload("https://www.example.com")
        assertEquals(0x02, httpsWww[0].toInt() and 0xFF)
        assertEquals("example.com", String(httpsWww.copyOfRange(1, httpsWww.size), Charsets.UTF_8))
    }

    @Test
    fun `text payload encodes language length and utf8 bytes`() {
        val payload = NdefBuilder.buildTextPayload("Ciao!", "it")
        assertEquals(2, payload[0].toInt() and 0xFF)
        assertEquals("itCiao!", String(payload.copyOfRange(1, payload.size), Charsets.UTF_8))
    }

    @Test
    fun `wifi wsc payload wraps credential tlv with ssid and key`() {
        val wsc = NdefBuilder.buildWifiWscPayload("LabRFID", "secret123", WifiAuth.WPA2_PERSONAL)
        // Outer TLV type 0x100E (Credential)
        assertEquals(0x10, wsc[0].toInt() and 0xFF)
        assertEquals(0x0E, wsc[1].toInt() and 0xFF)
        val bodyLen = ((wsc[2].toInt() and 0xFF) shl 8) or (wsc[3].toInt() and 0xFF)
        assertEquals(wsc.size - 4, bodyLen)
        val ascii = String(wsc, Charsets.ISO_8859_1)
        assertTrue(ascii.contains("LabRFID"))
        assertTrue(ascii.contains("secret123"))
    }

    @Test
    fun `iso15693 extended system info decodes 16-bit block count`() {
        val uid = ByteArray(8) { (0xE0 - it).toByte() }
        // 512 blocks (0x01FF little-endian = FF 01), 4-byte blocks (sizeCode = 0x03), IC = 0x25 (ST25DV16K)
        val response = byteArrayOf(0x00, 0x0F) + uid + byteArrayOf(
            0x00, 0x00,
            0xFF.toByte(), 0x01, 0x03,
            0x25,
        )
        val info = Iso15693.parseSystemInfo(response, extended = true)
        assertNotNull(info)
        assertEquals(512, info!!.blockCount)
        assertEquals(4, info.blockSize)
        assertEquals(2048, info.memoryBytes)
        assertEquals(0x25, info.icReference)

        val extRead = Iso15693.readAnyBlock(300)
        assertArrayEquals(byteArrayOf(0x00, 0x30, 0x2C, 0x01), extRead)
    }

    @Test
    fun `version info distinguishes ultralight aes from ntag213`() {
        val ulAes = VersionInfo.parse(byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0F, 0x03))
        val ntag213 = VersionInfo.parse(byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03))
        assertEquals(41, ulAes?.pageCount)
        assertEquals("MIFARE Ultralight AES", ulAes?.productName)
        assertEquals(45, ntag213?.pageCount)
        assertEquals("NTAG213", ntag213?.productName)
    }

    @Test
    fun `type2 inspect probe reads originality signature counters config and ndef`() {
        val version = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03) // NTAG213 (45 pages)
        val uid = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val pages = Array(45) { ByteArray(4) }
        // Page 2: static lock = 00 00, Page 3: CC = E1 10 12 00 (144 B R/W)
        pages[3] = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)
        // Page 4..7: NDEF TLV for https://a.co (10 bytes: D1 01 06 55 04 61 2E 63 6F -> wait len=5: 04 'a' '.' 'c' 'o')
        val ndefRecord = byteArrayOf(
            0xD1.toByte(), 0x01, 0x05, 0x55,
            0x04, 'a'.code.toByte(), '.'.code.toByte(), 'c'.code.toByte(), 'o'.code.toByte(),
        )
        val tlv = byteArrayOf(0x03, ndefRecord.size.toByte()) + ndefRecord + byteArrayOf(0xFE.toByte())
        for (i in tlv.indices) {
            pages[4 + i / 4][i % 4] = tlv[i]
        }
        // Page 41 (CFG0): AUTH0 = 0x20 (page 32)
        pages[41] = byteArrayOf(0x00, 0x00, 0x00, 0x20)
        // Page 42 (CFG1): ACCESS = 0x93 (PROT=1, NFC_CNT_EN=1, AUTHLIM=3)
        pages[42] = byteArrayOf(0x93.toByte(), 0x00, 0x00, 0x00)

        val signature = ByteArray(32) { (it + 1).toByte() }
        val fakeNfcA = object : RawTagTransport {
            override fun transceive(data: ByteArray): ByteArray {
                val cmd = data[0].toInt() and 0xFF
                return when (cmd) {
                    0x3C -> signature
                    0x39 -> byteArrayOf(0x2A, 0x00, 0x00) // counter = 42
                    0x3E -> byteArrayOf(0xBD.toByte()) // tearing OK
                    0x30 -> {
                        val start = data[1].toInt() and 0xFF
                        if (start >= pages.size) throw IOException("out of range")
                        ByteArray(16).also { out ->
                            for (p in 0 until 4) {
                                val src = pages[(start + p) % pages.size]
                                src.copyInto(out, p * 4)
                            }
                        }
                    }
                    else -> throw IOException("unsupported")
                }
            }
        }
        val session = object : TagSession {
            override fun <T> withIsoDep(block: (IsoDepTransport) -> T): T? = null
            override fun <T> withNfcA(block: (RawTagTransport) -> T): T? = block(fakeNfcA)
            override fun <T> withNfcV(block: (NfcVTransport) -> T): T? = null
            override fun <T> withNfcF(block: (NfcFTransport) -> T): T? = null
            override fun <T> withNfcB(block: (NfcBTransport) -> T): T? = null
        }

        val snapshot = TagSnapshot(
            uid = uid,
            techs = listOf(NfcTech.NFC_A, NfcTech.MIFARE_ULTRALIGHT),
            atqa = 0x0044,
            sak = 0x00,
            version = version,
        )
        val result = HfProbeRegistry.run(ProbeContext(snapshot, session, CommandLog()))

        assertEquals(TagFamily.NTAG, result.identity.family)
        assertEquals("NTAG213", result.identity.label)
        assertTrue(result.identity.dumpable)
        val details = result.details as? GenericDetails
        assertNotNull(details)
        val allText = details!!.sections.flatMap { it.rows }.joinToString("\n") { "${it.label}: ${it.value}" }
        assertTrue("Expected ECC signature in details:\n$allText", allText.contains("Present (32 B)"))
        assertTrue("Expected counter 42 in details:\n$allText", allText.contains("42"))
        assertTrue("Expected AUTH0 page 32 in details:\n$allText", allText.contains("page 32"))
        assertTrue("Expected Read & Write protection in details:\n$allText", allText.contains("Read & Write"))
        assertEquals(NdefStatus.FOUND, result.content?.ndef?.status)
    }
}
