// Classic value-block scan and increment/decrement against an in-memory card.
// The fake records the commands and can be made to ignore TRANSFER, so both
// the happy path and the read-back verification are covered.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MfcValueToolTest {

    @Test
    fun `scan finds the value block and its permissions`() {
        val (capture, _) = card(value = 100)

        val blocks = MfcValueTool.scan(capture)

        assertEquals(1, blocks.size)
        val info = blocks.single()
        assertEquals(1, info.sector)
        assertEquals(4, info.block)
        assertEquals(100, info.value)
        assertTrue(info.canIncrement)
        assertTrue(info.canDecrement)
    }

    @Test
    fun `increment stages and transfers the new value`() {
        val (capture, data) = card(value = 100)
        val info = MfcValueTool.scan(capture).single()
        val transport = FakeValueClassic(data)

        val change = MfcValueTool.apply(
            transport,
            capture,
            info,
            MfcValueOperation.INCREMENT,
            50,
        )

        assertEquals(100, change.previous)
        assertEquals(150, change.value)
        assertEquals(150, MfcValueBlock.value(block(data, 4)))
        assertTrue(transport.lastKeyA)
        assertEquals(1, transport.transfers)
    }

    @Test
    fun `decrement stages and transfers the new value`() {
        val (capture, data) = card(value = 100)
        val info = MfcValueTool.scan(capture).single()
        val transport = FakeValueClassic(data)

        val change = MfcValueTool.apply(
            transport,
            capture,
            info,
            MfcValueOperation.DECREMENT,
            30,
        )

        assertEquals(70, change.value)
        assertEquals(70, MfcValueBlock.value(block(data, 4)))
    }

    @Test
    fun `001 is decrement only`() {
        val (capture, _) = card(value = 100, access = accessFor(0x01))
        val info = MfcValueTool.scan(capture).single()

        assertFalse(info.canIncrement)
        assertTrue(info.canDecrement)
        try {
            MfcValueTool.apply(
                FakeValueClassic(capture.toBin()),
                capture,
                info,
                MfcValueOperation.INCREMENT,
                10,
            )
            fail("expected MfcValuePermissionException")
        } catch (_: MfcValuePermissionException) {
            // expected
        }
    }

    @Test
    fun `110 requires key B`() {
        val (capture, data) = card(
            value = 100,
            access = accessFor(0x06),
            hasKeyA = true,
            hasKeyB = true,
        )
        val info = MfcValueTool.scan(capture).single()
        assertTrue(info.canIncrement)
        assertEquals(MfcKeyType.B, info.incrementKey)

        val transport = FakeValueClassic(data)
        val change = MfcValueTool.apply(
            transport,
            capture,
            info,
            MfcValueOperation.INCREMENT,
            10,
        )

        assertEquals(110, change.value)
        assertFalse(transport.lastKeyA)
    }

    @Test
    fun `110 without key B is not operable`() {
        val (capture, _) = card(
            value = 100,
            access = accessFor(0x06),
            hasKeyA = true,
            hasKeyB = false,
        )
        val info = MfcValueTool.scan(capture).single()

        assertFalse(info.canIncrement)
        assertFalse(info.canDecrement)
        assertEquals(null, info.incrementKey)
    }

    @Test
    fun `value changed after the scan aborts the operation`() {
        val (capture, data) = card(value = 100)
        val info = MfcValueTool.scan(capture).single()
        MfcValueBlock.encode(90, 4).copyInto(data, 4 * MfcGeometry.BLOCK_SIZE)
        val transport = FakeValueClassic(data)

        try {
            MfcValueTool.apply(
                transport,
                capture,
                info,
                MfcValueOperation.INCREMENT,
                10,
            )
            fail("expected MfcValueChangedException")
        } catch (e: MfcValueChangedException) {
            assertEquals(90, e.current)
        }
        assertEquals(0, transport.transfers)
    }

    @Test
    fun `ignored transfer fails the read-back check`() {
        val (capture, data) = card(value = 100)
        val info = MfcValueTool.scan(capture).single()
        val transport = FakeValueClassic(data, ignoreTransfer = true)

        try {
            MfcValueTool.apply(
                transport,
                capture,
                info,
                MfcValueOperation.INCREMENT,
                50,
            )
            fail("expected MfcValueVerifyException")
        } catch (e: MfcValueVerifyException) {
            assertEquals(150, e.expected)
            assertEquals(100, e.actual)
        }
    }

    @Test
    fun `decrement below zero is refused before touching the card`() {
        val (capture, data) = card(value = 100)
        val info = MfcValueTool.scan(capture).single()
        val transport = FakeValueClassic(data)

        try {
            MfcValueTool.apply(
                transport,
                capture,
                info,
                MfcValueOperation.DECREMENT,
                200,
            )
            fail("expected MfcValueRangeException")
        } catch (_: MfcValueRangeException) {
            // expected
        }
        assertEquals(0, transport.increments)
        assertEquals(0, transport.transfers)
    }

    @Test
    fun `wrong address is not a value block of that block number`() {
        val (capture, _) = card(value = 100, address = 5)

        assertTrue(MfcValueTool.scan(capture).isEmpty())
    }

    @Test
    fun `unreadable trailer hides the block`() {
        val (capture, _) = card(value = 100)
        val limited = MfcCapture(
            size = capture.size,
            data = capture.toBin(),
            keys = capture.keys,
            readableBlocks = capture.readableBlocks - 7,
            readableSectors = capture.readableSectors,
        )

        assertTrue(MfcValueTool.scan(limited).isEmpty())
    }
}

private val KEY_A = byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte())
private val KEY_B = byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte())

private fun card(
    value: Int,
    access: ByteArray = accessFor(0x00),
    address: Int = 4,
    hasKeyA: Boolean = true,
    hasKeyB: Boolean = true,
): Pair<MfcCapture, ByteArray> {
    val data = ByteArray(MfcSize.CLASSIC_1K.bytes)
    // Four-byte UID plus BCC, so MfcCapture.uid is stable.
    data[0] = 0x04
    data[1] = 0xA1.toByte()
    data[2] = 0xB2.toByte()
    data[3] = 0xC3.toByte()
    data[4] = (data[0].toInt() xor data[1].toInt() xor data[2].toInt() xor data[3].toInt()).toByte()

    for (sector in 0 until MfcSize.CLASSIC_1K.sectors) {
        val first = MfcGeometry.firstBlockOfSector(sector)
        val count = MfcGeometry.blocksInSector(sector)
        val trailer = first + count - 1
        val base = trailer * MfcGeometry.BLOCK_SIZE
        if (hasKeyA) KEY_A.copyInto(data, base)
        if (hasKeyB) KEY_B.copyInto(data, base + 10)
        val acl = if (sector == 1) access else accessFor(0x00)
        acl.copyInto(data, base + 6)
    }

    MfcValueBlock.encode(value, address).copyInto(data, 4 * MfcGeometry.BLOCK_SIZE)
    val keyA = List(MfcSize.CLASSIC_1K.sectors) {
        if (hasKeyA) KEY_A.copyOf() else ByteArray(6)
    }
    val keyB = List(MfcSize.CLASSIC_1K.sectors) {
        if (hasKeyB) KEY_B.copyOf() else ByteArray(6)
    }
    val capture = MfcCapture(
        size = MfcSize.CLASSIC_1K,
        data = data.copyOf(),
        keys = MfcKeys(keyA, keyB, "test"),
        readableBlocks = (0 until MfcSize.CLASSIC_1K.blocks).toSet(),
        readableSectors = (0 until MfcSize.CLASSIC_1K.sectors).toSet(),
    )
    return capture to data
}

private fun block(data: ByteArray, index: Int): ByteArray =
    data.copyOfRange(
        index * MfcGeometry.BLOCK_SIZE,
        (index + 1) * MfcGeometry.BLOCK_SIZE,
    )

private class FakeValueClassic(
    private val data: ByteArray,
    private val ignoreTransfer: Boolean = false,
) : ClassicTransport {

    override val sectorCount: Int = MfcSize.CLASSIC_1K.sectors

    private var authenticatedSector = -1
    private val pending = mutableMapOf<Int, Int>()
    var lastKeyA = false
    var increments = 0
    var transfers = 0

    override fun blockCount(sector: Int): Int = MfcGeometry.blocksInSector(sector)

    override fun authenticate(sector: Int, key: ByteArray, keyA: Boolean): Boolean {
        val expected = if (keyA) KEY_A else KEY_B
        if (!key.contentEquals(expected)) return false
        authenticatedSector = sector
        lastKeyA = keyA
        return true
    }

    override fun readBlock(block: Int): ByteArray = block(data, block)

    override fun writeBlock(block: Int, data: ByteArray) {
        throw IOException("writeBlock non usato dal tool")
    }

    override fun increment(block: Int, value: Int) {
        checkSession(block)
        increments++
        pending[block] = current(block) + value
    }

    override fun decrement(block: Int, value: Int) {
        checkSession(block)
        increments++
        pending[block] = current(block) - value
    }

    override fun transfer(block: Int) {
        checkSession(block)
        transfers++
        val value = pending.remove(block) ?: return
        if (ignoreTransfer) return
        MfcValueBlock.encode(value, block).copyInto(data, block * MfcGeometry.BLOCK_SIZE)
    }

    override fun close() = Unit

    private fun current(block: Int): Int = MfcValueBlock.value(block(data, block))

    private fun checkSession(block: Int) {
        val sector = block / 4
        if (sector != authenticatedSector) {
            throw IOException("nessuna sessione autenticata per il settore $sector")
        }
    }
}

/**
 * Same helper as MfcValueBlockTest: three access bytes for one group, starting
 * from the factory C3 group 3.
 */
private fun accessFor(condition: Int, group: Int = 0): ByteArray {
    val bit = 1 shl group
    var c1 = 0
    var c2 = 0
    var c3 = 0x8
    c1 = if (condition and 0x04 != 0) c1 or bit else c1 and bit.inv()
    c2 = if (condition and 0x02 != 0) c2 or bit else c2 and bit.inv()
    c3 = if (condition and 0x01 != 0) c3 or bit else c3 and bit.inv()

    return byteArrayOf(
        (((c2 xor 0x0F) shl 4) or (c1 xor 0x0F)).toByte(),
        ((c1 shl 4) or (c3 xor 0x0F)).toByte(),
        ((c3 shl 4) or c2).toByte(),
    )
}
