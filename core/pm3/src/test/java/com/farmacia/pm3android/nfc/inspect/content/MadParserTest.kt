// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MadParserTest {

    private fun sectorZero(
        publisher: Int = 1,
        gpb: Int = 0xC1,
        aids: List<Int> = listOf(MadParser.AID_NDEF, 0x0002, 0x1234),
    ): ByteArray {
        val sector = ByteArray(64) { 0x00 }
        val uid = byteArrayOf(0x04, 0x11, 0x22, 0x33)
        uid.copyInto(sector, 0)
        sector[4] = (uid[0].toInt() xor uid[1].toInt() xor
            uid[2].toInt() xor uid[3].toInt()).toByte()
        sector[5] = 0x08
        sector[6] = 0x04
        sector[17] = publisher.toByte()
        aids.forEachIndexed { index, aid ->
            sector[18 + index * 2] = (aid ushr 8).toByte()
            sector[19 + index * 2] = aid.toByte()
        }
        for (index in 48..53) sector[index] = 0xFF.toByte()
        sector[54] = 0xFF.toByte()
        sector[55] = 0x07
        sector[56] = 0x80.toByte()
        sector[57] = gpb.toByte()
        for (index in 58..63) sector[index] = 0xFF.toByte()
        sector[16] = MadCrc.compute(sector.copyOfRange(17, 48)).toByte()
        return sector
    }

    private fun sectorSixteen(info: Int = 0x11, aids: List<Int> = emptyList()): ByteArray {
        val sector = ByteArray(64) { 0x00 }
        sector[1] = info.toByte()
        aids.forEachIndexed { index, aid ->
            sector[2 + index * 2] = (aid ushr 8).toByte()
            sector[3 + index * 2] = aid.toByte()
        }
        for (index in 48..53) sector[index] = 0xFF.toByte()
        sector[54] = 0xFF.toByte()
        sector[55] = 0x07
        sector[56] = 0x80.toByte()
        sector[57] = 0x69
        for (index in 58..63) sector[index] = 0xFF.toByte()
        sector[0] = MadCrc.compute(sector.copyOfRange(1, 48)).toByte()
        return sector
    }

    @Test
    fun `crc matches the published check value`() {
        assertEquals(0x99, MadCrc.compute("123456789".toByteArray()))
    }

    @Test
    fun `mad1 decodes the AIDs and the publisher sector`() {
        val mad = MadParser.parse(sectorZero())!!
        assertEquals(1, mad.version)
        assertEquals(1, mad.publisherSector)
        assertTrue(mad.directoryAvailable)
        assertTrue(mad.multiApplication)
        assertTrue(mad.crcOk)
        assertEquals(15, mad.entries.size)
        assertEquals(0x03E1, mad.entries[0].aid)
        assertEquals("NDEF", mad.entries[0].name)
        assertEquals(1, mad.entries[0].sector)
        assertEquals("reserved", mad.entries[1].name)
        assertNull(mad.entries[2].name)
        assertEquals("free", mad.entries[3].name)
        assertEquals(listOf(0x03E1, 0x0002, 0x1234), mad.assigned.map { it.aid })
    }

    @Test
    fun `mad2 adds sectors 17 to 39`() {
        val mad2Aids = MutableList(23) { 0x0000 }
        mad2Aids[0] = 0x0004
        val mad = MadParser.parse(
            sectorZero(gpb = 0xC2),
            sectorSixteen(aids = mad2Aids),
        )!!
        assertEquals(2, mad.version)
        assertEquals(38, mad.entries.size)
        val entry = mad.entries.first { it.sector == 17 }
        assertEquals(0x0004, entry.aid)
        assertEquals("card holder info", entry.name)
        assertTrue(mad.crcOk)
    }

    @Test
    fun `mad2 is ignored on a version 1 card`() {
        val mad = MadParser.parse(
            sectorZero(gpb = 0xC1),
            sectorSixteen(aids = listOf(0x0004)),
        )!!
        assertEquals(1, mad.version)
        assertEquals(15, mad.entries.size)
    }

    @Test
    fun `a bad CRC is reported without dropping the entries`() {
        val sector = sectorZero()
        sector[18] = 0x03
        sector[19] = 0xE2.toByte()
        val mad = MadParser.parse(sector)!!
        assertFalse(mad.crcOk)
        assertEquals(0x03E2, mad.entries[0].aid)
    }

    @Test
    fun `a bad MAD2 CRC makes the whole directory suspect`() {
        val mad2 = sectorSixteen(aids = listOf(0x0004))
        mad2[3] = 0x05
        val mad = MadParser.parse(sectorZero(gpb = 0xC2), mad2)!!
        assertFalse(mad.crcOk)
    }

    @Test
    fun `DA zero is reported but the directory is still decoded`() {
        val mad = MadParser.parse(sectorZero(gpb = 0x01))!!
        assertFalse(mad.directoryAvailable)
        assertTrue(mad.crcOk)
    }

    @Test
    fun `unknown version and short sector are rejected`() {
        assertNull(MadParser.parse(sectorZero(gpb = 0x83)))
        assertNull(MadParser.parse(ByteArray(32)))
    }

    @Test
    fun `aid names cover the bookkeeping range only`() {
        assertEquals("free", MadParser.aidName(0x0000))
        assertEquals("defect", MadParser.aidName(0x0001))
        assertEquals("not applicable", MadParser.aidName(0x0005))
        assertEquals("NDEF", MadParser.aidName(MadParser.AID_NDEF))
        assertNull(MadParser.aidName(0x1234))
    }

    @Test
    fun `entries are big endian`() {
        val sector = sectorZero(aids = listOf(0xABCD))
        val mad = MadParser.parse(sector)
        assertNotNull(mad)
        assertEquals(0xABCD, mad!!.entries[0].aid)
    }
}
