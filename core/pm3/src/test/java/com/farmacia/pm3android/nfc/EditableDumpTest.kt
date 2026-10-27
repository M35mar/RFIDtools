// Editable .bin model: row geometry, in-place patching and MFU header safety.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class EditableDumpTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "pm3-editor-${System.nanoTime()}")
        dir.mkdirs()
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun classic1k(): ByteArray = ByteArray(MfcSize.CLASSIC_1K.bytes).apply {
        this[0] = 0xDE.toByte()
        this[1] = 0xAD.toByte()
        this[2] = 0xBE.toByte()
        this[3] = 0xEF.toByte()
        this[4] = 0x22 // BCC of DE AD BE EF
    }

    @Test
    fun `classic dump exposes one row per block and patches only the edited block`() {
        val original = classic1k()
        val editable = EditableDump.load(file("hf-mf-DEADBEEF-dump.bin", original))

        assertEquals(DumpFamily.CLASSIC, editable.family)
        assertEquals(64, editable.rows.size)
        assertEquals(16, editable.rowSize)
        assertEquals(0, editable.headerBytes)
        assertFalse(editable.dirty)
        assertEquals("DEADBEEF", editable.uidHex)

        assertEquals(DumpRowRole.UID, editable.rows[0].role)
        assertFalse(editable.rows[0].writtenByRestore)
        assertEquals(DumpRowRole.DATA, editable.rows[5].role)
        assertEquals(DumpRowRole.TRAILER, editable.rows[7].role)
        assertTrue(editable.rows[5].writtenByRestore)

        val block = ByteArray(16) { 0xAB.toByte() }
        val edited = editable.edit(5, block)
        assertTrue(edited.dirty)
        assertEquals(setOf(5), edited.modifiedRows)
        assertTrue(edited.isModified(5))
        assertFalse(edited.isModified(6))

        val saved = edited.toBytes()
        for (offset in 0 until 16) {
            assertEquals(0xAB, saved[5 * 16 + offset].toInt() and 0xFF)
        }
        // Other blocks are untouched and the original instance still has the old bytes.
        assertArrayEquals(original.copyOfRange(0, 5 * 16), saved.copyOfRange(0, 5 * 16))
        assertArrayEquals(original, editable.toBytes())
    }

    @Test
    fun `revert and saved reset the dirty state`() {
        val editable = EditableDump.load(file("dump.bin", classic1k()))
        val edited = editable.edit(1, ByteArray(16) { 0x11 })

        val reverted = edited.revert()
        assertFalse(reverted.dirty)
        assertArrayEquals(editable.toBytes(), reverted.toBytes())

        val saved = edited.saved()
        assertFalse(saved.dirty)
        assertArrayEquals(edited.toBytes(), saved.toBytes())
    }

    @Test
    fun `ultralight dump keeps its 56 byte header when a page is edited`() {
        val pages = (0 until 45).map { index ->
            ByteArray(4) { offset -> (index * 4 + offset).toByte() }
        }
        val version = ByteArray(8) { 0x11 }
        val signature = ByteArray(32) { 0x22 }
        val bin = pages.toMfuBin(version, signature)
        val editable = EditableDump.load(file("hf-mfu-11223344-dump.bin", bin))

        assertEquals(DumpFamily.ULTRALIGHT, editable.family)
        assertEquals(45, editable.rows.size)
        assertEquals(4, editable.rowSize)
        assertEquals(56, editable.headerBytes)
        assertEquals(DumpRowRole.UID, editable.rows[0].role)
        assertEquals(DumpRowRole.BCC, editable.rows[1].role)
        assertEquals(DumpRowRole.LOCK, editable.rows[2].role)
        assertEquals(DumpRowRole.OTP, editable.rows[3].role)
        assertEquals(DumpRowRole.USER, editable.rows[10].role)
        assertEquals(DumpRowRole.CONFIG0, editable.rows[41].role)
        assertEquals(DumpRowRole.CONFIG1, editable.rows[42].role)
        assertEquals(DumpRowRole.PASSWORD, editable.rows[43].role)
        assertEquals(DumpRowRole.PACK, editable.rows[44].role)
        assertFalse(editable.rows[0].writtenByRestore)
        assertFalse(editable.rows[3].writtenByRestore)
        assertTrue(editable.rows[10].writtenByRestore)

        val edited = editable.edit(10, byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A))
        val out = edited.toBytes()
        assertEquals(bin.size, out.size)
        assertArrayEquals(bin.copyOfRange(0, 56), out.copyOfRange(0, 56))
        assertArrayEquals(
            byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A),
            out.copyOfRange(56 + 10 * 4, 56 + 11 * 4),
        )
    }

    @Test
    fun `hex and ascii are rendered for every row`() {
        val editable = EditableDump.load(file("hf-mf-DEADBEEF-dump.bin", classic1k()))

        assertEquals(
            "DE AD BE EF 22 00 00 00 00 00 00 00 00 00 00 00",
            editable.rows[0].hex,
        )
        assertTrue(editable.rows.all { it.hex.isNotEmpty() })
        assertEquals(47, editable.rows[0].hex.length)
        assertEquals("....\"...........", editable.rows[0].ascii)
        assertTrue(editable.rows[1].hex.isNotEmpty())
        assertEquals("................", editable.rows[1].ascii)
    }

    @Test
    fun `trailer and value blocks are decoded`() {
        val data = classic1k()
        // Sector 1 trailer: factory keys, FF 07 80 69, GPB 69.
        val trailer = ByteArray(16) { 0xFF.toByte() }
        MfcAccess.DEFAULT.copyInto(trailer, 6)
        trailer.copyInto(data, 7 * 16)
        // Block 4 is a valid MIFARE value block: 0x12345678 at address 0x04.
        val value = byteArrayOf(
            0x78, 0x56, 0x34, 0x12,
            0x87.toByte(), 0xA9.toByte(), 0xCB.toByte(), 0xED.toByte(),
            0x78, 0x56, 0x34, 0x12,
            0x04, 0xFB.toByte(), 0x04, 0xFB.toByte(),
        )
        value.copyInto(data, 4 * 16)

        val editable = EditableDump.load(file("dump.bin", data))

        assertEquals(DumpRowRole.TRAILER, editable.rows[7].role)
        assertTrue(editable.rows[7].details.any { it.startsWith("Key A FFFFFFFFFFFF") })
        assertTrue(editable.rows[7].details.any { it.contains("Access FF 07 80") })
        assertTrue(editable.rows[7].details.any { it.startsWith("Key B FFFFFFFFFFFF") })
        assertTrue(editable.rows[7].details.any { it.contains("T 001") })
        assertTrue(editable.rows[4].details.any { it == "value 305419896 · addr 0x04 · checked" })
        assertTrue(editable.rows[5].details.isEmpty())
    }

    @Test
    fun `ultralight configuration pages are decoded`() {
        val pages = (0 until 45).map { index ->
            ByteArray(4) { offset -> (index * 4 + offset).toByte() }
        }
        val bin = pages.toMfuBin(ByteArray(8), null)
        val editable = EditableDump.load(file("hf-mfu-11223344-dump.bin", bin))

        // NTAG213: page 41 = CFG0, 42 = CFG1, 43 = PWD, 44 = PACK.
        assertTrue(editable.rows[41].details.any { it.startsWith("MOD ") && it.contains("AUTH0") })
        assertTrue(editable.rows[42].details.any { it.startsWith("ACCESS ") && it.contains("VCTID") })
        assertTrue(editable.rows[43].details.any { it.startsWith("PWD ") })
        assertTrue(editable.rows[44].details.any { it.startsWith("PACK ") })
        assertEquals("....", editable.rows[44].ascii)
        assertTrue(editable.rows.all { it.hex.isNotEmpty() })
    }

    @Test
    fun `only bin files can be opened`() {
        try {
            EditableDump.load(file("dump.eml", "00".repeat(16).toByteArray()))
            fail("an .eml file must not open in the bin editor")
        } catch (_: DumpFormatException) {
            // Expected.
        }
    }

    @Test
    fun `a row cannot be resized`() {
        val editable = EditableDump.load(file("dump.bin", classic1k()))
        try {
            editable.edit(0, ByteArray(15))
            fail("a 15-byte block must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
