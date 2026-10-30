// Format detection and parsing: no Android, no tag, just files.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class DumpParsersTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "pm3-nfc-${System.nanoTime()}")
        dir.mkdirs()
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `classic 1K binary and its key file are recognised`() {
        val data = ByteArray(MfcSize.CLASSIC_1K.bytes)
        data[0] = 0xDE.toByte()
        data[1] = 0xAD.toByte()
        data[2] = 0xBE.toByte()
        data[3] = 0xEF.toByte()
        data[4] = 0x22 // BCC of DE AD BE EF
        val dumpFile = file("hf-mf-DEADBEEF-dump.bin", data)
        file("hf-mf-DEADBEEF-key.bin", ByteArray(192) { 0xFF.toByte() })

        val image = DumpLoader.load(dumpFile)
        assertTrue(image is DumpImage.Classic)
        val dump = (image as DumpImage.Classic).dump
        assertEquals(MfcSize.CLASSIC_1K, dump.size)
        assertEquals("DEADBEEF", dump.uidHex)
        val keys = requireNotNull(dump.keys) { "the sibling key file was not loaded" }
        assertEquals("FFFFFFFFFFFF", keys.keyAFor(0)!!.toHex())
        assertEquals("FFFFFFFFFFFF", keys.keyBFor(15)!!.toHex())
    }

    @Test
    fun `classic text dump is recognised`() {
        val block = "00112233445566778899AABBCCDDEEFF"
        val text = buildString {
            repeat(64) { appendLine(block) }
        }
        val image = DumpLoader.load(file("hf-mf-DEADBEEF-dump.eml", text.toByteArray()))
        assertTrue(image is DumpImage.Classic)
        assertEquals(64, (image as DumpImage.Classic).dump.size.blocks)
    }

    @Test
    fun `mifare classic tool dump with sector headers is recognised`() {
        val text = buildString {
            appendLine("+Sector: 0")
            repeat(64) { appendLine("00112233445566778899AABBCCDDEEFF") }
        }
        val image = DumpLoader.load(file("dump.txt", text.toByteArray()))
        assertTrue(image is DumpImage.Classic)
        assertEquals(64, (image as DumpImage.Classic).dump.size.blocks)
    }

    @Test
    fun `an incomplete text dump is rejected`() {
        val text = buildString {
            appendLine("+Sector: 0")
            appendLine("04A1B2C3D4E5F60708090A0B0C0D0E0F")
            appendLine("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
            appendLine("00000000000000000000000000000000")
            appendLine("FFFFFFFFFFFF078069FFFFFFFFFFFF")
        }
        try {
            DumpLoader.load(file("partial.txt", text.toByteArray()))
            fail("4 blocks are not a complete MIFARE Classic dump")
        } catch (_: DumpFormatException) {
            // Expected: fromText requires a known card size.
        }
    }

    @Test
    fun `ultralight EV1 48 dump with new header is recognised`() {
        val pages = MutableList(20) { ByteArray(4) }
        pages[0] = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x9F.toByte())
        pages[1] = byteArrayOf(0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(), 0x07)
        val version = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03)
        val bytes = pages.toMfuBin(version, ByteArray(32))

        val image = DumpLoader.load(file("hf-mfu-04A1B2D4E5F607-dump.bin", bytes))
        assertTrue(image is DumpImage.Ultralight)
        val dump = (image as DumpImage.Ultralight).dump
        assertEquals(20, dump.pages.size)
        assertEquals("MF0UL11 (48 B)", dump.layout.variant)
        assertEquals(15, dump.layout.userLast)
        assertEquals(16, dump.layout.cfg0)
        assertEquals(18, dump.layout.pwdPage)
        assertEquals("04A1B2D4E5F607", dump.uidHex)
    }

    @Test
    fun `ultralight EV1 128 dump derives the 41 page map`() {
        val pages = MutableList(41) { ByteArray(4) }
        val version = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0E, 0x03)

        val image = DumpLoader.load(file("hf-mfu-11223344-dump.bin", pages.toMfuBin(version, null)))
        val dump = (image as DumpImage.Ultralight).dump
        assertEquals(41, dump.pages.size)
        assertEquals(35, dump.layout.userLast)
        assertEquals(36, dump.layout.lockPage)
        assertEquals(37, dump.layout.cfg0)
        assertEquals(40, dump.layout.packPage)
    }

    @Test
    fun `legacy 48 byte ultralight header is recognised through its BCC`() {
        val pages = MutableList(20) { ByteArray(4) }
        pages[0] = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x9F.toByte())
        pages[1] = byteArrayOf(0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(), 0x07)
        pages[2] = byteArrayOf(0xC0.toByte(), 0x00, 0x00, 0x00)
        val body = ByteArray(80)
        pages.forEachIndexed { index, page -> page.copyInto(body, index * 4) }

        val image = DumpLoader.load(file("legacy.bin", ByteArray(48) + body))
        assertTrue(image is DumpImage.Ultralight)
        assertEquals(20, (image as DumpImage.Ultralight).dump.pages.size)
    }

    @Test
    fun `plain ultralight page dump is recognised`() {
        val image = DumpLoader.load(file("plain.bin", ByteArray(80)))
        assertTrue(image is DumpImage.Ultralight)
        assertEquals(20, (image as DumpImage.Ultralight).dump.pages.size)
    }

    @Test
    fun `a 16 page page-dump is opened instead of being rejected`() {
        // my-d move lean written by the phone: 56-byte header plus 16 pages.
        val pages = MutableList(16) { index -> ByteArray(4) { (index * 4 + it).toByte() } }
        val image = DumpLoader.load(file("hf-myd-05112233-dump.bin", PageDumpWriter.toBin(pages)))
        assertTrue(image is DumpImage.Ultralight)
        val dump = (image as DumpImage.Ultralight).dump
        assertEquals(16, dump.pages.size)
        assertArrayEquals(pages[0], dump.pages[0])
        assertFalse(dump.layout.supported)
    }

    @Test
    fun `text key file is parsed`() {
        val keyFile = file("hf-mf-04A1B29F-key.txt", """
            FFFFFFFFFFFF FFFFFFFFFFFF
            A0A1A2A3A4A5 B0B1B2B3B4B5
        """.trimIndent().toByteArray())

        val keys = MfcKeyFileParser.parse(keyFile, MfcSize.CLASSIC_1K)
        assertEquals("FFFFFFFFFFFF", keys.keyAFor(0)!!.toHex())
        assertEquals("B0B1B2B3B4B5", keys.keyBFor(1)!!.toHex())
    }

    @Test
    fun `a flat binary file with a small header becomes a candidate list`() {
        // 2-byte header + 337 keys = 2024 bytes, the shape some exporters save
        // under a -key.bin name.
        val keys = (0 until 337).map { index ->
            byteArrayOf(
                (index and 0xFF).toByte(),
                ((index shr 8) and 0xFF).toByte(),
                0xA5.toByte(), 0x5A, 0x3C, 0xC3.toByte(),
            )
        }
        val bytes = byteArrayOf(0x00, 0x00) + keys.fold(ByteArray(0)) { acc, key -> acc + key }
        assertEquals(2024, bytes.size)

        val parsed = MfcKeyFileParser.parse(bytes, MfcSize.CLASSIC_1K, "hf-mf-04A1B29F-key.bin")

        assertEquals(337, parsed.candidates.size)
        assertNull(parsed.keyAFor(0))
    }

    @Test
    fun `only bin and txt key files are accepted`() {
        assertTrue(MfcKeyFiles.isKeyFile("hf-mf-04A1B29F-key.bin"))
        assertTrue(MfcKeyFiles.isKeyFile("keys.txt"))
        assertFalse(MfcKeyFiles.isKeyFile("hf-mf-04A1B29F-dump.bin"))
        assertFalse(MfcKeyFiles.isKeyFile("hf-mf-04A1B29F-dump.txt"))
        assertFalse(MfcKeyFiles.isKeyFile("dump.eml"))
        assertFalse(MfcKeyFiles.isKeyFile("keys.keys"))
        assertFalse(MfcKeyFiles.isKeyFile("keys.dic"))
    }

    @Test
    fun `a text dump is not accepted as a key file`() {
        val text = buildString {
            repeat(64) { appendLine("00112233445566778899AABBCCDDEEFF") }
        }

        assertThrows(DumpFormatException::class.java) {
            MfcKeyFileParser.parse(text.toByteArray(), MfcSize.CLASSIC_1K, "dump.txt")
        }
    }

    @Test
    fun `key file listing keeps bin and txt but skips dumps`() {
        file("hf-mf-AAAA-dump.bin", ByteArray(1024))
        file("hf-mf-AAAA-key.bin", ByteArray(192))
        file("keys.txt", "FFFFFFFFFFFF\n".toByteArray())
        file("notes.keys", "FFFFFFFFFFFF\n".toByteArray())

        val names = MfcKeyFiles.listIn(dir).map { it.name }

        assertEquals(listOf("hf-mf-AAAA-key.bin", "keys.txt"), names)
    }
}
