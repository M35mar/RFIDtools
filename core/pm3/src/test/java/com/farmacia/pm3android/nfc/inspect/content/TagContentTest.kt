// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.MfcCapture
import com.farmacia.pm3android.nfc.MfcSize
import com.farmacia.pm3android.nfc.MfuCapture
import com.farmacia.pm3android.nfc.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagContentTest {

    private fun ndef(text: String): ByteArray {
        val payload = byteArrayOf(0x02, 'e'.code.toByte(), 'n'.code.toByte()) +
            text.toByteArray()
        val record = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'T'.code.toByte()) +
            payload
        return byteArrayOf(0x03, record.size.toByte()) + record + byteArrayOf(0xFE.toByte())
    }

    private fun putPages(pages: MutableList<ByteArray>, fromPage: Int, bytes: ByteArray) {
        bytes.forEachIndexed { index, byte ->
            val page = pages[fromPage + index / 4]
            page[index % 4] = byte
        }
    }

    private fun classicData(ndefSectors: Boolean = true): ByteArray {
        val data = ByteArray(MfcSize.CLASSIC_1K.bytes)
        // Sector 0: manufacturer block plus MAD1 pointing sector 1 at NDEF.
        val uid = byteArrayOf(0x04, 0x11, 0x22, 0x33)
        uid.copyInto(data, 0)
        data[4] = (uid[0].toInt() xor uid[1].toInt() xor
            uid[2].toInt() xor uid[3].toInt()).toByte()
        data[5] = 0x08
        data[6] = 0x04
        if (ndefSectors) {
            data[17] = 0x01 // publisher sector
            data[18] = 0x03
            data[19] = 0xE1.toByte()
        }
        trailer(data, sector = 0, gpb = 0xC1, crcOverMad = true)

        // Sector 1 carries the NDEF TLV.
        if (ndefSectors) {
            val tlv = ndef("Ciao")
            tlv.copyInto(data, 4 * 16)
        }
        for (sector in 1 until MfcSize.CLASSIC_1K.sectors) {
            trailer(data, sector, gpb = 0x69, crcOverMad = false)
        }
        return data
    }

    private fun trailer(data: ByteArray, sector: Int, gpb: Int, crcOverMad: Boolean) {
        val start = sector * 64
        val trailer = start + 48
        for (index in trailer until trailer + 6) data[index] = 0xFF.toByte()
        data[trailer + 6] = 0xFF.toByte()
        data[trailer + 7] = 0x07
        data[trailer + 8] = 0x80.toByte()
        data[trailer + 9] = gpb.toByte()
        for (index in trailer + 10 until trailer + 16) data[index] = 0xFF.toByte()
        if (crcOverMad) {
            data[start + 16] = MadCrc.compute(data.copyOfRange(start + 17, start + 48)).toByte()
        }
    }

    private fun capture(
        data: ByteArray,
        readable: Set<Int> = (0 until MfcSize.CLASSIC_1K.blocks).toSet(),
    ) = MfcCapture(
        size = MfcSize.CLASSIC_1K,
        data = data,
        keys = null,
        readableBlocks = readable,
        readableSectors = (0 until MfcSize.CLASSIC_1K.sectors).toSet(),
    )

    @Test
    fun `classic content decodes mad, roles and ndef`() {
        val content = TagContentAnalyzer.classic(capture(classicData()))
        assertEquals("04112233", content.uidHex)
        assertEquals(16, content.sectors.size)
        assertEquals(16, content.readableSectors)
        assertEquals(MfcSize.CLASSIC_1K.label, content.sizeLabel)

        val mad = content.mad
        assertNotNull(mad)
        assertTrue(mad!!.crcOk)
        assertEquals(0x03E1, mad.entries.first { it.sector == 1 }.aid)

        assertEquals(SectorRole.MAD1, content.sectors[0].role)
        assertEquals(SectorRole.NDEF, content.sectors[1].role)
        assertEquals(SectorRole.DATA, content.sectors[2].role)
        assertEquals(0x03E1, content.sectors[1].aid)

        assertTrue(content.ndef.found)
        assertEquals("Ciao", content.ndef.records[0].text)
    }

    @Test
    fun `classic access bits and first data block are included`() {
        val content = TagContentAnalyzer.classic(capture(classicData()))
        val sector = content.sectors[1]
        assertTrue(sector.accessConsistent)
        val access = sector.access
        assertNotNull(access)
        assertTrue(access!!.data[0].readable)
        val expected = ByteArray(16).also { ndef("Ciao").copyInto(it) }.toHex()
        assertEquals(expected, sector.firstDataHex)
    }

    @Test
    fun `an unreadable ndef sector makes the message incomplete`() {
        val readable = (0 until MfcSize.CLASSIC_1K.blocks).toSet() - 4
        val content = TagContentAnalyzer.classic(capture(classicData(), readable))
        assertFalse(content.sectors[1].readable)
        assertEquals(NdefStatus.TRUNCATED, content.ndef.status)
    }

    @Test
    fun `a value block is recognised`() {
        val data = classicData()
        val block = 8 * 16 // sector 2, block 0
        val value = byteArrayOf(0x64, 0x00, 0x00, 0x00)
        value.copyInto(data, block)
        value.map { (it.toInt() xor 0xFF).toByte() }.toByteArray().copyInto(data, block + 4)
        value.copyInto(data, block + 8)
        byteArrayOf(
            0x08,
            0xF7.toByte(),
            0x08,
            0xF7.toByte(),
        ).copyInto(data, block + 12)

        val content = TagContentAnalyzer.classic(capture(data))
        assertTrue(content.sectors[2].valueBlock)
        assertFalse(content.sectors[3].valueBlock)
    }

    @Test
    fun `ultralight content decodes the capability container and counters`() {
        val pages = MutableList(45) { ByteArray(4) }
        pages[0] = byteArrayOf(0x04, 0xA2.toByte(), 0xB3.toByte(), 0xC4.toByte())
        pages[1] = byteArrayOf(0x05, 0x06, 0x07, 0x08)
        pages[2] = ByteArray(4)
        pages[3] = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)
        putPages(pages, 4, ndef("Ciao"))

        val capture = MfuCapture(
            pages = pages,
            version = ByteArray(8),
            signature = ByteArray(32),
            counters = listOf(
                byteArrayOf(0x05, 0x00, 0x00, 0x00),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00),
                byteArrayOf(0x01, 0x00, 0x00, 0xBD.toByte()),
            ),
            pack = null,
            password = null,
        )

        val content = TagContentAnalyzer.ultralight(capture)
        assertEquals("04A2B305060708", content.uidHex)
        assertEquals(45, content.pages)
        val cc = content.cc
        assertNotNull(cc)
        assertEquals(1, cc!!.versionMajor)
        assertEquals(0, cc.versionMinor)
        assertEquals(144, cc.capacityBytes)
        assertTrue(content.ndef.found)
        assertEquals("Ciao", content.ndef.records[0].text)
        assertEquals(5, content.counters[0].value)
        assertFalse(content.counters[0].tearing)
        assertNull(content.counters[1].value)
        assertEquals(1, content.counters[2].value)
        assertTrue(content.counters[2].tearing)
    }

    @Test
    fun `a tag without capability container has no ndef`() {
        val pages = MutableList(16) { ByteArray(4) }
        pages[3] = ByteArray(4)
        val capture = MfuCapture(
            pages = pages,
            version = ByteArray(8),
            signature = ByteArray(32),
            counters = List(3) { ByteArray(4) },
            pack = null,
            password = null,
        )
        val content = TagContentAnalyzer.ultralight(capture)
        assertNull(content.cc)
        assertEquals(NdefStatus.ABSENT, content.ndef.status)
    }

    @Test
    fun `content exposes the ndef of either family`() {
        val classic = TagContentAnalyzer.of(capture(classicData()))
        assertTrue(classic.ndef.found)
        assertEquals(NdefStatus.ABSENT, TagContent().ndef.status)
    }
}
