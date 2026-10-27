// MIFARE Classic restore engine against an in-memory card. The fake enforces
// authentication, the per-block access conditions and the trailer key table,
// so a bug in the write order or in the pre-check fails the test instead of
// silently passing.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MfcRestorerTest {

    @Test
    fun `the transport configuration decodes as 000 for data and 001 for the trailer`() {
        val access = MfcAccess.DEFAULT
        assertTrue(MfcAccess.isValid(access))
        assertEquals(0x00, MfcAccess.condition(access, 0))
        assertEquals(0x00, MfcAccess.condition(access, 1))
        assertEquals(0x00, MfcAccess.condition(access, 2))
        assertEquals(0x01, MfcAccess.condition(access, 3))
        assertTrue(MfcAccess.isDataBlockWritable(access, 1))
        assertEquals(MfcKeyRequirement.A, MfcAccess.trailerWriteRequirement(access))
    }

    @Test
    fun `access bytes rebuilt from conditions match the factory value`() {
        assertEquals(
            MfcAccess.DEFAULT.copyOfRange(0, 3).toList(),
            accessBytes(0, 0, 0, 1).toList(),
        )
        assertEquals(0x02, MfcAccess.condition(accessBytes(0, 2, 0, 1), 1))
        assertFalse(MfcAccess.isDataBlockWritable(accessBytes(0, 2, 0, 1), 1))
        assertTrue(MfcAccess.isDataBlockWritable(accessBytes(0, 2, 0, 1), 0))
    }

    @Test
    fun `block 0 is never written even when the UID differs`() {
        val card = FakeClassic()
        val dump = dump()

        val report = MfcRestorer(card).restore(dump)

        assertFalse(card.writes.contains(0))
        assertEquals(StepStatus.SKIPPED, report.step(0).status)
        assertEquals(StepNote.UID_KEPT, report.step(0).note)
    }

    @Test
    fun `only the differing block is written`() {
        val card = FakeClassic()
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)

        val report = MfcRestorer(card).restore(dump(data))

        assertEquals(listOf(5), card.writes)
        assertEquals(StepStatus.VERIFIED, report.step(5).status)
        assertEquals(StepStatus.UNCHANGED, report.step(4).status)
        assertEquals(StepStatus.UNCHANGED, report.step(6).status)
        assertEquals(StepStatus.UNCHANGED, report.step(3).status)
    }

    @Test
    fun `a second restore is a no-op`() {
        val card = FakeClassic()
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)
        val dump = dump(data)

        MfcRestorer(card).restore(dump)
        card.writes.clear()
        val second = MfcRestorer(card).restore(dump)

        assertTrue(card.writes.isEmpty())
        assertEquals(0, second.written)
        assertTrue(second.ok)
    }

    @Test
    fun `a factory tag with a different UID gets the dump keys`() {
        val card = FakeClassic()
        val cardUid = byteArrayOf(0x04, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte())
        cardUid.copyInto(card.data, 0)
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)
        val keys = MfcKeys(
            keyA = List(16) { keyAOf() },
            keyB = List(16) { keyBOf() },
            sourceName = "other-tag-key.bin",
        )
        // A wrong key file must not influence the trailer: only the dump does.
        val options = MfcRestoreOptions(candidates = listOf(keys(0xCC)))

        val report = MfcRestorer(card).restore(dump(data, keys), options)

        assertTrue(report.ok)
        assertArrayEquals(cardUid, card.data.copyOfRange(0, 4))
        assertEquals(keyAOf().toList(), card.keyA[0].toList())
        assertEquals(keyBOf().toList(), card.keyB[0].toList())
        assertTrue(5 in card.writes)
    }

    @Test
    fun `a dump with its own trailer keys restores a factory tag`() {
        // The dump comes from another card: its keys are not the tag's. No key
        // file and no sibling key file are given, so the trailer keys are the
        // only source, exactly like a dump read by the phone reader.
        val card = FakeClassic()
        val data = classicData()
        for (sector in 0 until 16) {
            keyAOf().copyInto(data, trailerOf(sector) * 16)
            keyBOf().copyInto(data, trailerOf(sector) * 16 + 10)
        }
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)

        val report = MfcRestorer(card).restore(dump(data))

        assertTrue(report.ok)
        assertEquals(keyAOf().toList(), card.keyA[0].toList())
        assertEquals(keyBOf().toList(), card.keyB[0].toList())
        assertEquals(StepStatus.VERIFIED, report.step(5).status)
    }

    @Test
    fun `the trailer is skipped when the dump has no keys`() {
        val card = FakeClassic()
        val data = classicData()
        // A PM3 dump kept the access bits but zeroed both keys.
        for (sector in 0 until 16) {
            val trailer = trailerOf(sector) * 16
            ByteArray(16).copyInto(data, trailer)
            MfcAccess.DEFAULT.copyInto(data, trailer + 6)
        }

        val report = MfcRestorer(card).restore(dump(data))

        assertTrue(card.writes.isEmpty())
        assertEquals(StepStatus.SKIPPED, report.step(3).status)
        assertEquals(StepNote.KEEP_CURRENT_KEYS, report.step(3).note)
    }

    @Test
    fun `an invalid ACL in the dump is replaced with the factory value`() {
        val card = FakeClassic()
        accessBytes(0, 0, 0, 3).copyInto(card.data, 3 * 16 + 6)
        val data = classicData()
        // C1/C3 nibbles disagree with their complements.
        byteArrayOf(0xFF.toByte(), 0x07, 0x00, 0x69).copyInto(data, 3 * 16 + 6)

        val report = MfcRestorer(card).restore(dump(data))

        assertEquals(StepStatus.VERIFIED, report.step(3).status)
        assertEquals(StepNote.ACCESS_FIXED, report.step(3).note)
        assertEquals(
            MfcAccess.DEFAULT.toList(),
            card.data.copyOfRange(3 * 16 + 6, 3 * 16 + 10).toList(),
        )
    }

    @Test
    fun `auto repair makes a read-only block writable`() {
        val card = FakeClassic()
        // Block 1 of sector 1 is read-only on the card but writable in the dump.
        accessBytes(0, 2, 0, 1).copyInto(card.data, trailerOf(1) * 16 + 6)
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)

        val report = MfcRestorer(card).restore(dump(data))

        assertEquals(listOf(trailerOf(1), 5), card.writes)
        assertEquals(StepStatus.VERIFIED, report.step(5).status)
    }

    @Test
    fun `a block read-only in the dump aborts before any write`() {
        val card = FakeClassic()
        accessBytes(0, 2, 0, 1).copyInto(card.data, trailerOf(1) * 16 + 6)
        val data = classicData()
        accessBytes(0, 2, 0, 1).copyInto(data, trailerOf(1) * 16 + 6)
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)

        assertThrows(MfcPrecheckException::class.java) {
            MfcRestorer(card).restore(dump(data))
        }
        assertTrue(card.writes.isEmpty())
    }

    @Test
    fun `a data block that needs Key B is written after switching session`() {
        val card = FakeClassic()
        for (sector in 0 until 16) {
            keyAOf().copyInto(card.keyA[sector])
            keyBOf().copyInto(card.keyB[sector])
        }
        // Blocks 1 and 2 of sector 2 are writable with Key B only.
        accessBytes(0, 3, 3, 1).copyInto(card.data, trailerOf(2) * 16 + 6)
        val data = classicData()
        accessBytes(0, 3, 3, 1).copyInto(data, trailerOf(2) * 16 + 6)
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 9 * 16)
        val keys = MfcKeys(
            keyA = List(16) { keyAOf() },
            keyB = List(16) { keyBOf() },
            sourceName = "hf-mf-04A1B29F-key.bin",
        )

        val report = MfcRestorer(card).restore(dump(data, keys))

        assertEquals(StepStatus.VERIFIED, report.step(9).status)
        assertTrue(card.writes.contains(9))
    }

    @Test
    fun `auto repair installs the dump key B when the card key B is unknown`() {
        val card = FakeClassic()
        for (sector in 0 until 16) {
            keyAOf().copyInto(card.keyA[sector])
            // The card was reconfigured: its Key B is not the dump one.
            ByteArray(6) { 0xEE.toByte() }.copyInto(card.keyB[sector])
        }
        // Sector 2 keeps its blocks writable with Key B only (011), both on the
        // card and in the dump; the trailer itself is 001 and can be rewritten.
        accessBytes(0, 3, 3, 1).copyInto(card.data, trailerOf(2) * 16 + 6)
        val data = classicData()
        accessBytes(0, 3, 3, 1).copyInto(data, trailerOf(2) * 16 + 6)
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 9 * 16)
        val keys = MfcKeys(
            keyA = List(16) { keyAOf() },
            keyB = List(16) { keyBOf() },
            sourceName = "hf-mf-04A1B29F-key.bin",
        )

        val report = MfcRestorer(card).restore(dump(data, keys))

        assertEquals(StepStatus.VERIFIED, report.step(9).status)
        assertEquals(keyBOf().toList(), card.keyB[2].toList())
    }

    @Test
    fun `a trailer write uses Key B when the current ACL asks for it`() {
        val card = FakeClassic()
        for (sector in 0 until 16) {
            keyAOf().copyInto(card.keyA[sector])
            keyBOf().copyInto(card.keyB[sector])
        }
        // 011 on the trailer: only Key B may rewrite it.
        accessBytes(0, 0, 0, 3).copyInto(card.data, trailerOf(0) * 16 + 6)
        val data = classicData()
        val keys = MfcKeys(
            keyA = List(16) { keyAOf() },
            keyB = List(16) { keyBOf() },
            sourceName = "hf-mf-04A1B29F-key.bin",
        )

        val report = MfcRestorer(card).restore(dump(data, keys))

        assertEquals(StepStatus.VERIFIED, report.step(3).status)
        assertEquals(listOf(3), card.writes)
        assertEquals(
            MfcAccess.DEFAULT.toList(),
            card.data.copyOfRange(3 * 16 + 6, 3 * 16 + 10).toList(),
        )
    }

    @Test
    fun `an unopened sector aborts before any write`() {
        val card = FakeClassic()
        card.keyA[0] = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        card.keyB[0] = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 1 * 16)

        val error = assertThrows(MfcPrecheckException::class.java) {
            MfcRestorer(card).restore(dump(data))
        }

        assertEquals(listOf(0), error.unopenedSectors)
        assertTrue(card.writes.isEmpty())
    }

    @Test
    fun `a missing write key aborts before any write`() {
        val card = FakeClassic()
        // The card Key B is unknown and the dump has no Key B to install.
        for (sector in 0 until 16) {
            ByteArray(6) { 0xEE.toByte() }.copyInto(card.keyB[sector])
        }
        accessBytes(0, 3, 3, 1).copyInto(card.data, trailerOf(2) * 16 + 6)
        val data = classicData()
        accessBytes(0, 3, 3, 1).copyInto(data, trailerOf(2) * 16 + 6)
        // The dump has no Key B to install either.
        data.fill(0, trailerOf(2) * 16 + 10, trailerOf(2) * 16 + 16)
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 9 * 16)
        val keys = MfcKeys(
            keyA = List(16) { keyAOf() },
            keyB = emptyList(),
            sourceName = "key-a-only.bin",
        )

        val error = assertThrows(MfcPrecheckException::class.java) {
            MfcRestorer(card).restore(dump(data, keys))
        }

        assertTrue(error.missingKeys.any { it.sector == 2 && it.block == 9 && it.keyB })
        assertTrue(card.writes.isEmpty())
    }

    @Test
    fun `a dump of another size is refused before any write`() {
        val card = FakeClassic()
        val data = ByteArray(MfcSize.CLASSIC_4K.bytes)

        val error = assertThrows(MfcPrecheckException::class.java) {
            MfcRestorer(card).restore(
                MfcDump("hf-mf-04A1B29F-4k-dump.bin", MfcSize.CLASSIC_4K, data),
            )
        }

        assertEquals(MfcSize.CLASSIC_4K, error.dumpSize)
        assertEquals(16, error.tagSectors)
        assertTrue(card.writes.isEmpty())
    }

    @Test
    fun `a write is retried once`() {
        val card = FakeClassic(failFirstWrite = true)
        val data = classicData()
        byteArrayOf(0x11, 0x22, 0x33, 0x44).copyInto(data, 5 * 16)

        val report = MfcRestorer(card).restore(dump(data))

        assertEquals(StepStatus.VERIFIED, report.step(5).status)
        assertEquals(2, card.writeAttempts[5])
    }

    // -----------------------------------------------------------------------

    private fun dump(data: ByteArray = classicData(), keys: MfcKeys? = null) =
        MfcDump("hf-mf-04A1B29F-dump.bin", MfcSize.CLASSIC_1K, data, keys)

    private fun classicData(): ByteArray {
        val data = ByteArray(MfcSize.CLASSIC_1K.bytes)
        val uid = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x9F.toByte())
        val bcc = (uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt() xor uid[3].toInt()).toByte()
        (uid + bcc).copyInto(data, 0)
        for (sector in 0 until MfcSize.CLASSIC_1K.sectors) {
            val trailer = ByteArray(16) { 0xFF.toByte() }
            MfcAccess.DEFAULT.copyInto(trailer, 6)
            trailer.copyInto(data, trailerOf(sector) * 16)
        }
        return data
    }

    private fun trailerOf(sector: Int): Int =
        MfcGeometry.firstBlockOfSector(sector) + MfcGeometry.blocksInSector(sector) - 1

    private fun keyAOf(): ByteArray =
        byteArrayOf(
            0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(),
            0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(),
        )

    private fun keyBOf(): ByteArray =
        byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte())

    private fun keys(value: Int): ByteArray = ByteArray(6) { value.toByte() }

    /** Builds bytes 6..8 of a trailer from four access conditions. */
    private fun accessBytes(vararg conditions: Int): ByteArray {
        var c1 = 0
        var c2 = 0
        var c3 = 0
        conditions.forEachIndexed { index, condition ->
            if (condition and 0x04 != 0) c1 = c1 or (1 shl index)
            if (condition and 0x02 != 0) c2 = c2 or (1 shl index)
            if (condition and 0x01 != 0) c3 = c3 or (1 shl index)
        }
        return byteArrayOf(
            (((c2.inv() and 0x0F) shl 4) or (c1.inv() and 0x0F)).toByte(),
            (((c1 and 0x0F) shl 4) or (c3.inv() and 0x0F)).toByte(),
            (((c3 and 0x0F) shl 4) or (c2 and 0x0F)).toByte(),
        )
    }

    private fun RestoreReport.step(index: Int): StepResult = steps.first { it.index == index }
}

/**
 * MIFARE Classic 1K with 16 sectors. Keys, data ACLs and the trailer key table
 * are enforced like a real card.
 */
private class FakeClassic(
    private val sectors: Int = 16,
    private val failFirstWrite: Boolean = false,
) : ClassicTransport {

    val data = ByteArray(sectors * 4 * MfcGeometry.BLOCK_SIZE)
    val keyA = MutableList(sectors) { ByteArray(6) { 0xFF.toByte() } }
    val keyB = MutableList(sectors) { ByteArray(6) { 0xFF.toByte() } }
    val writes = mutableListOf<Int>()
    val writeAttempts = mutableMapOf<Int, Int>()

    private var authenticated = -1
    private var authenticatedWithKeyA = true
    private val failedOnce = mutableSetOf<Int>()

    init {
        // Every sector starts in the transport configuration, like a fresh card.
        for (sector in 0 until sectors) {
            val trailer = ByteArray(16) { 0xFF.toByte() }
            MfcAccess.DEFAULT.copyInto(trailer, 6)
            trailer.copyInto(data, (MfcGeometry.firstBlockOfSector(sector) + 3) * 16)
        }
    }

    override val sectorCount: Int get() = sectors

    override fun blockCount(sector: Int): Int = 4

    override fun authenticate(sector: Int, key: ByteArray, keyA: Boolean): Boolean {
        val match = if (keyA) key.contentEquals(this.keyA[sector]) else key.contentEquals(this.keyB[sector])
        if (match) {
            authenticated = sector
            authenticatedWithKeyA = keyA
        } else if (authenticated == sector) {
            authenticated = -1
        }
        return match
    }

    override fun readBlock(block: Int): ByteArray {
        val sector = MfcGeometry.sectorOfBlock(block)
        if (authenticated != sector) throw IOException("block $block: not authenticated")
        val copy = data.copyOfRange(block * 16, block * 16 + 16)
        if (MfcGeometry.isTrailer(block)) {
            // Key A is write-only. Key B is readable only with a Key A session
            // and access conditions that allow it.
            val access = copy.copyOfRange(6, 9)
            copy.fill(0.toByte(), 0, 6)
            if (authenticatedWithKeyA && keyBReadable(access)) {
                this.keyB[sector].copyInto(copy, 10)
            } else {
                copy.fill(0.toByte(), 10, 16)
            }
        }
        return copy
    }

    override fun writeBlock(block: Int, data: ByteArray) {
        val sector = MfcGeometry.sectorOfBlock(block)
        if (authenticated != sector) throw IOException("block $block: not authenticated")
        writeAttempts[block] = (writeAttempts[block] ?: 0) + 1
        if (failFirstWrite && failedOnce.add(block) && block != 0) {
            throw IOException("block $block: transient write error")
        }

        val trailer = MfcGeometry.firstBlockOfSector(sector) + 3
        val currentAccess = this.data.copyOfRange(trailer * 16 + 6, trailer * 16 + 9)
        val requirement = if (MfcGeometry.isTrailer(block)) {
            MfcAccess.trailerWriteRequirement(currentAccess)
        } else {
            val offset = block - MfcGeometry.firstBlockOfSector(sector)
            MfcAccess.writeKeyRequirement(currentAccess, offset)
        }
        when (requirement) {
            MfcKeyRequirement.NONE -> throw IOException("block $block: write protected")
            MfcKeyRequirement.A -> if (!authenticatedWithKeyA) {
                throw IOException("block $block: needs key A")
            }
            MfcKeyRequirement.B -> if (authenticatedWithKeyA) {
                throw IOException("block $block: needs key B")
            }
            MfcKeyRequirement.ANY -> Unit
        }

        data.copyInto(this.data, block * 16, 0, 16)
        writes += block
        if (MfcGeometry.isTrailer(block)) {
            data.copyOfRange(0, 6).copyInto(keyA[sector])
            data.copyOfRange(10, 16).copyInto(keyB[sector])
        }
    }

    override fun close() = Unit

    /** Mirrors MCT's `isKeyBReadable`: C1 = 0 and C2 = 0 or C3 = 0. */
    private fun keyBReadable(access: ByteArray): Boolean {
        val (c1, c2, c3) = MfcAccess.conditions(access, 3)
        return !c1 && (!c2 || !c3)
    }
}
