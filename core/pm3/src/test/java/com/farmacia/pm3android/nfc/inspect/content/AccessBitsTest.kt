// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessBitsTest {

    /** Factory trailer: `FF 07 80 69` between the keys. */
    private fun trailer(access: Triple<Int, Int, Int> = Triple(0xFF, 0x07, 0x80)): ByteArray {
        val out = ByteArray(16) { 0xFF.toByte() }
        out[6] = access.first.toByte()
        out[7] = access.second.toByte()
        out[8] = access.third.toByte()
        out[9] = 0x69
        return out
    }

    /** Encodes one condition per block group, the inverse of [AccessBits.parse]. */
    private fun conditions(vararg values: Int): Triple<Int, Int, Int> {
        var c1 = 0
        var c2 = 0
        var c3 = 0
        values.forEachIndexed { group, value ->
            c1 = c1 or (((value shr 2) and 1) shl group)
            c2 = c2 or (((value shr 1) and 1) shl group)
            c3 = c3 or ((value and 1) shl group)
        }
        val byte6 = (((c2.inv() and 0x0F) shl 4) or (c1.inv() and 0x0F))
        val byte7 = ((c1 shl 4) or (c3.inv() and 0x0F))
        val byte8 = ((c3 shl 4) or c2)
        return Triple(byte6, byte7, byte8)
    }

    @Test
    fun `factory trailer is transport for data and writable ACL for the trailer`() {
        val access = AccessBits.parse(trailer())
        assertNotNull(access)
        access!!
        // 000: read and write with either key.
        access.data.forEach { condition ->
            assertEquals(setOf(AccessKey.A, AccessKey.B), condition.read)
            assertEquals(setOf(AccessKey.A, AccessKey.B), condition.write)
        }
        // The trailer uses 001: Key B can read the ACL, Key A can write it.
        assertEquals(emptySet<AccessKey>(), access.keyA.read)
        assertEquals(setOf(AccessKey.A), access.keyA.write)
        assertEquals(setOf(AccessKey.A), access.accessBits.read)
        assertEquals(setOf(AccessKey.A), access.accessBits.write)
        assertEquals(setOf(AccessKey.A), access.keyB.read)
        assertEquals(setOf(AccessKey.A), access.keyB.write)
        assertTrue(AccessBits.isConsistent(trailer()))
    }

    @Test
    fun `each group decodes with its own condition`() {
        val access = AccessBits.parse(trailer(conditions(2, 4, 6, 1)))
        assertNotNull(access)
        access!!
        assertEquals(setOf(AccessKey.A, AccessKey.B), access.data[0].read)
        assertTrue(access.data[0].readable)
        assertEquals(emptySet<AccessKey>(), access.data[0].write)
        assertEquals(setOf(AccessKey.B), access.data[1].write)
        assertEquals(setOf(AccessKey.B), access.data[2].increment)
        assertEquals(setOf(AccessKey.A, AccessKey.B), access.data[2].decrement)
        assertEquals(setOf(AccessKey.A), access.accessBits.write)
    }

    @Test
    fun `condition 111 grants nothing`() {
        val access = AccessBits.parse(trailer(conditions(7, 7, 7, 7)))
        assertNotNull(access)
        access!!
        access.data.forEach {
            assertFalse(it.readable)
            assertTrue(it.write.isEmpty())
            assertTrue(it.increment.isEmpty())
            assertTrue(it.decrement.isEmpty())
        }
        assertTrue(access.keyA.read.isEmpty())
        assertTrue(access.keyA.write.isEmpty())
    }

    @Test
    fun `condition 011 needs key B`() {
        val access = AccessBits.parse(trailer(conditions(3, 3, 3, 3)))
        access!!
        assertEquals(setOf(AccessKey.B), access.data[0].read)
        assertEquals(setOf(AccessKey.B), access.data[0].write)
    }

    @Test
    fun `inconsistent nibbles are flagged but still decoded`() {
        val broken = trailer()
        broken[6] = 0x00
        assertFalse(AccessBits.isConsistent(broken))
        assertNotNull(AccessBits.parse(broken))
    }

    @Test
    fun `short trailer is not decoded`() {
        assertNull(AccessBits.parse(ByteArray(8)))
        assertFalse(AccessBits.isConsistent(ByteArray(8)))
    }

    @Test
    fun `compact strings are stable for the report`() {
        val access = AccessBits.parse(trailer(conditions(2, 3, 6, 1)))!!
        assertEquals("R:A|B W:-", access.data[0].compact())
        assertEquals("R:B W:B", access.data[1].compact())
        assertEquals("R:A|B W:B I:B D:A|B", access.data[2].compact())
        assertEquals(
            "KeyA R:- W:A · ACL R:A W:A · KeyB R:A W:A",
            access.trailerCompact(),
        )
    }
}
