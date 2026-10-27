// MIFARE Classic reader against an in-memory card. The fake enforces the keys
// and lets a single block require Key B, so the re-authentication path used for
// read conditions 011 is exercised instead of being skipped.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MfcReaderTest {

    @Test
    fun `a complete key file reads every block`() {
        val card = ReadableClassic(keyA = keys(0xA0), keyB = keys(0xB0))

        val capture = MfcReader(card).read(keyFile("hf-mf-04A1B29F-key.bin", 0xA0, 0xB0))

        assertEquals(MfcSize.CLASSIC_1K, capture.size)
        assertEquals("04A1B29F", capture.uidHex)
        assertEquals("hf-mf-04A1B29F-dump", capture.defaultName)
        assertEquals(64, capture.readableBlocks.size)
        assertEquals(16, capture.readableSectors.size)
        assertTrue(capture.unreadableBlocks.isEmpty())
        assertEquals(capture.toBin().size, MfcSize.CLASSIC_1K.bytes)
        // The key that opened the sector is stored in the trailer, like the
        // client does, so the dump can be restored without the key file.
        assertArrayEquals(keys(0xA0), capture.toBin().copyOfRange(3 * 16, 3 * 16 + 6))
        // Both halves are known, so the saved key file matches `hf mf dump`.
        assertEquals(16 * 12, capture.keysToBin()!!.size)
    }

    @Test
    fun `a flat key list opens the card`() {
        val card = ReadableClassic(keyA = keys(0xA0), keyB = keys(0xB0))
        // Twelve bytes are not a sector layout: the file is a flat candidate
        // list, like the small files some tools write.
        val flat = keys(0xA0) + keys(0xB0)

        val capture = MfcReader(card).read(KeyFileSource("keys.bin", flat))

        assertEquals(16, capture.readableSectors.size)
        assertEquals(64, capture.readableBlocks.size)
        assertEquals("A0A0A0A0A0A0", capture.keys!!.keyAFor(0)!!.toHex())
    }

    @Test
    fun `a key file for another size is only tried as candidates`() {
        val card = ReadableClassic(keyA = keys(0xA0), keyB = keys(0xB0))
        // Five sectors: a MIFARE Mini layout, not the 1K card in the reader.
        // A binary file that does not match the tag geometry is treated as a
        // list of candidate keys, and none of these opens the card.
        val wrong = KeyFileSource("mini-key.bin", ByteArray(5 * 12) { 0x11 })

        assertThrows(TagAuthException::class.java) {
            MfcReader(card).read(keyFile = wrong)
        }
    }

    @Test
    fun `a sector with an unknown key stays zero and is reported`() {
        val card = ReadableClassic(
            keyA = keys(0xA0),
            keyB = keys(0xB0),
            unknownSector = 5,
        )

        val capture = MfcReader(card).read(keyFile("keys.bin", 0xA0, 0xB0))

        assertEquals(15, capture.readableSectors.size)
        assertTrue(5 !in capture.readableSectors)
        assertEquals((20..23).toList(), capture.unreadableBlocks)
        assertTrue(
            capture.toBin().copyOfRange(20 * 16, 24 * 16).all { it == 0.toByte() },
        )
        // Key B of sector 5 is unknown: only the Key A half is written, so a
        // later restore keeps the key already on the destination card.
        assertEquals(16 * 6, capture.keysToBin()!!.size)
    }

    @Test
    fun `a block that needs Key B is read after re-authenticating`() {
        val card = ReadableClassic(keyA = keys(0xA0), keyB = keys(0xB0), keyBOnly = setOf(4))

        val capture = MfcReader(card).read(keyFile("keys.bin", 0xA0, 0xB0))

        assertTrue(4 in capture.readableBlocks)
        assertEquals(card.data[4 * 16].toInt(), capture.toBin()[4 * 16].toInt())
        assertEquals(64, capture.readableBlocks.size)
        assertArrayEquals(keys(0xA0), capture.toBin().copyOfRange(7 * 16, 7 * 16 + 6))
        assertArrayEquals(keys(0xB0), capture.toBin().copyOfRange(7 * 16 + 10, 7 * 16 + 16))
        assertEquals(16 * 12, capture.keysToBin()!!.size)
    }

    @Test
    fun `a key file without the factory key does not fall back to FF`() {
        // The card is factory-fresh, but the selected file only holds other
        // keys: the read must fail instead of silently using FFFFFFFFFFFF.
        val card = ReadableClassic(keyA = keys(0xFF), keyB = keys(0xFF))

        assertThrows(TagAuthException::class.java) {
            MfcReader(card).read(keyFile("other-card-key.bin", 0xA0, 0xB0))
        }
    }

    @Test
    fun `a strict read refuses a partial dump`() {
        val card = ReadableClassic(
            keyA = keys(0xA0),
            keyB = keys(0xB0),
            unknownSector = 5,
        )

        val error = assertThrows(MfcIncompleteReadException::class.java) {
            MfcReader(card).read(
                keyFile = keyFile("hf-mf-04A1B29F-key.bin", 0xA0, 0xB0),
                requireComplete = true,
            )
        }

        assertEquals(listOf(5), error.missingSectors)
        assertEquals(4, error.unreadableBlocks)
    }

    @Test
    fun `the dump keeps the proven keys so it restores without a key file`() {
        val card = ReadableClassic(keyA = keys(0xA0), keyB = keys(0xB0))

        val capture = MfcReader(card).read(keyFile("hf-mf-04A1B29F-key.bin", 0xA0, 0xB0))
        val dump = MfcDump("hf-mf-04A1B29F-dump.bin", capture.size, capture.toBin())

        assertTrue(dump.keysInTrailers())
        assertEquals("A0A0A0A0A0A0", dump.trailerKeyA(0).toHex())
        assertEquals("B0B0B0B0B0B0", dump.trailerKeyB(0).toHex())
    }

    @Test
    fun `text key files are parsed per sector`() {
        val text = (0 until 16).joinToString("\n") {
            "FFFFFFFFFFFF FFFFFFFFFFFF"
        }.toByteArray(Charsets.UTF_8)

        val keys = MfcKeyFileParser.parse(text, MfcSize.CLASSIC_1K, "keys.txt")

        assertEquals(6, keys.keyAFor(15)!!.size)
        assertTrue(keys.keyAFor(0)!!.all { it == 0xFF.toByte() })
        assertTrue(keys.keyBFor(15)!!.all { it == 0xFF.toByte() })
        assertNull(keys.keyAFor(16))
    }

    // -----------------------------------------------------------------------

    private fun keys(value: Int): ByteArray = ByteArray(6) { value.toByte() }

    /** Key file bytes: every Key A, then every Key B, as the client writes them. */
    private fun keyFile(name: String, keyA: Int, keyB: Int): KeyFileSource {
        val sectorBytes = 16 * 6
        val bytes = ByteArray(sectorBytes * 2)
        for (sector in 0 until 16) {
            keys(keyA).copyInto(bytes, sector * 6)
            keys(keyB).copyInto(bytes, sectorBytes + sector * 6)
        }
        return KeyFileSource(name, bytes)
    }
}

/**
 * MIFARE Classic 1K that only answers after authentication. Every block holds
 * its own index, block 0 carries a valid four-byte UID plus BCC/SAK/ATQA.
 */
private class ReadableClassic(
    private val keyA: ByteArray,
    private val keyB: ByteArray,
    /** Sector whose keys are not in the file; [manualKey] opens it. */
    private val unknownSector: Int = -1,
    /** Key that a manual entry supplies for [unknownSector]. */
    private val manualKey: ByteArray? = null,
    /** Blocks that refuse a Key A session, like read condition 011. */
    private val keyBOnly: Set<Int> = emptySet(),
    private val sectors: Int = 16,
) : ClassicTransport {

    val data = ByteArray(sectors * 4 * MfcGeometry.BLOCK_SIZE)

    private var session = -1
    private var sessionWithKeyA = false

    init {
        for (block in 0 until sectors * 4) {
            data.fill(block.toByte(), block * 16, block * 16 + 16)
        }
        val uid = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x9F.toByte())
        val bcc = (uid[0].toInt() xor uid[1].toInt() xor
            uid[2].toInt() xor uid[3].toInt()).toByte()
        (uid + bcc + byteArrayOf(0x08, 0x44, 0x00)).copyInto(data, 0)
    }

    override val sectorCount: Int get() = sectors

    override fun blockCount(sector: Int): Int = 4

    override fun authenticate(sector: Int, key: ByteArray, keyA: Boolean): Boolean {
        val expected = when {
            sector == unknownSector -> manualKey
            keyA -> this.keyA
            else -> this.keyB
        }
        val ok = expected != null && key.contentEquals(expected)
        if (ok) {
            session = sector
            sessionWithKeyA = keyA
        } else if (session == sector) {
            session = -1
        }
        return ok
    }

    override fun readBlock(block: Int): ByteArray {
        if (session != MfcGeometry.sectorOfBlock(block)) {
            throw IOException("block $block: not authenticated")
        }
        if (sessionWithKeyA && block in keyBOnly) {
            throw IOException("block $block: needs key B")
        }
        return data.copyOfRange(block * 16, block * 16 + 16)
    }

    override fun writeBlock(block: Int, data: ByteArray) {
        throw IOException("read-only fake")
    }

    override fun close() = Unit

    private fun keys(value: Int): ByteArray = ByteArray(6) { value.toByte() }
}
