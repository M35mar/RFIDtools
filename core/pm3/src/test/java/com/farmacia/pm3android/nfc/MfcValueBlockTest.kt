// Value-block layout and ACL decisions for the Classic credit tool. These are
// pure functions, so the tests build the three access bytes directly.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfcValueBlockTest {

    @Test
    fun `encode and decode round trip`() {
        val block = MfcValueBlock.encode(1337, 4)

        assertEquals(1337, MfcValueBlock.value(block))
        assertEquals(4, MfcValueBlock.address(block))
        assertTrue(MfcValueBlock.isValid(block, 4))
        assertArrayEquals(block, MfcValueBlock.encode(1337, 4))
    }

    @Test
    fun `address must match the block number in the tool`() {
        val block = MfcValueBlock.encode(10, 5)

        assertTrue(MfcValueBlock.isConsistent(block))
        assertFalse(MfcValueBlock.isValid(block, 4))
        assertTrue(MfcValueBlock.isValid(block, 5))
    }

    @Test
    fun `broken complement is rejected`() {
        val block = MfcValueBlock.encode(10, 4)
        block[5] = (block[5].toInt() xor 0x01).toByte()

        assertFalse(MfcValueBlock.isConsistent(block))
    }

    @Test
    fun `access condition 000 allows both operations with either key`() {
        val access = accessFor(0x00)

        assertEquals(MfcKeyRequirement.ANY, MfcAccess.incrementKeyRequirement(access, 0))
        assertEquals(MfcKeyRequirement.ANY, MfcAccess.decrementKeyRequirement(access, 0))
    }

    @Test
    fun `access condition 001 is decrement only`() {
        val access = accessFor(0x01)

        assertEquals(MfcKeyRequirement.NONE, MfcAccess.incrementKeyRequirement(access, 0))
        assertEquals(MfcKeyRequirement.ANY, MfcAccess.decrementKeyRequirement(access, 0))
    }

    @Test
    fun `access condition 110 requires key B for both operations`() {
        val access = accessFor(0x06)

        assertEquals(MfcKeyRequirement.B, MfcAccess.incrementKeyRequirement(access, 0))
        assertEquals(MfcKeyRequirement.B, MfcAccess.decrementKeyRequirement(access, 0))
    }

    @Test
    fun `read-only conditions refuse both operations`() {
        for (condition in listOf(0x02, 0x03, 0x04, 0x05, 0x07)) {
            val access = accessFor(condition)
            assertEquals(
                "increment $condition",
                MfcKeyRequirement.NONE,
                MfcAccess.incrementKeyRequirement(access, 0),
            )
            assertEquals(
                "decrement $condition",
                MfcKeyRequirement.NONE,
                MfcAccess.decrementKeyRequirement(access, 0),
            )
        }
    }

    @Test
    fun `4K large sectors use one access group per five blocks`() {
        val access = accessFor(0x06, group = 2)

        assertEquals(0x06, MfcAccess.condition(access, 10, 16))
        assertEquals(0x06, MfcAccess.condition(access, 14, 16))
        assertEquals(MfcKeyRequirement.B, MfcAccess.incrementKeyRequirement(access, 10, 16))
        // The next group is still the default 000.
        assertEquals(0x00, MfcAccess.condition(access, 5, 16))
    }

    @Test
    fun `block availability prefers key A and falls back to key B`() {
        val both = MfcValueBlockInfo(
            sector = 1,
            block = 4,
            value = 10,
            increment = MfcKeyRequirement.ANY,
            decrement = MfcKeyRequirement.ANY,
            hasKeyA = true,
            hasKeyB = true,
        )
        assertEquals(MfcKeyType.A, both.incrementKey)

        val onlyB = both.copy(hasKeyA = false, hasKeyB = true)
        assertEquals(MfcKeyType.B, onlyB.decrementKey)

        val keyBMissing = both.copy(
            hasKeyA = true,
            hasKeyB = false,
            increment = MfcKeyRequirement.B,
        )
        assertFalse(keyBMissing.canIncrement)
        assertEquals(null, keyBMissing.incrementKey)
    }
}

/**
 * Three access bytes for [condition] on one of the four groups. The default
 * nibbles are C1/C2 all zero and C3 group 3 set, which is the factory `FF 07
 * 80` trailer. [group] 0..3 maps to block 0..2 and the trailer for a 4-block
 * sector, or block groups 0/5/10/15 for a 4K large sector.
 */
private fun accessFor(condition: Int, group: Int = 0): ByteArray {
    var c1 = 0
    var c2 = 0
    var c3 = 0x8
    val bit = 1 shl group
    c1 = if (condition and 0x04 != 0) c1 or bit else c1 and bit.inv()
    c2 = if (condition and 0x02 != 0) c2 or bit else c2 and bit.inv()
    c3 = if (condition and 0x01 != 0) c3 or bit else c3 and bit.inv()

    val byte6 = ((c2 xor 0x0F) shl 4) or (c1 xor 0x0F)
    val byte7 = (c1 shl 4) or (c3 xor 0x0F)
    val byte8 = (c3 shl 4) or c2
    return byteArrayOf(byte6.toByte(), byte7.toByte(), byte8.toByte())
}
