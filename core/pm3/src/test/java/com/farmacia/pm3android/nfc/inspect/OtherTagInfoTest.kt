// ISO 15693 and FeliCa parsers: the display order of the UID and the block
// geometry the inspector shows without a key.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtherTagInfoTest {

    @Test
    fun `iso15693 uid is reversed to display order`() {
        val raw = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            Iso15693Info.displayUid(raw),
        )
    }

    @Test
    fun `iso15693 details keep dsfid and the first block`() {
        val block = byteArrayOf(0x03, 0x00, 0xFE.toByte(), 0x00)
        val details = Iso15693Info.details(
            tagId = ByteArray(8) { it.toByte() },
            dsfid = 0xE0,
            afi = 0x00,
            firstBlock = block,
        )

        assertEquals(0xE0, details.dsfid)
        assertEquals(0x00, details.afi)
        assertEquals(4, details.blockSize)
        assertArrayEquals(block, details.firstBlock)
    }

    @Test
    fun `empty first block does not invent a size`() {
        val details = Iso15693Info.details(ByteArray(8), firstBlock = ByteArray(0))

        assertNull(details.firstBlock)
        assertNull(details.blockSize)
    }

    @Test
    fun `felica details keep idm and optional codes`() {
        val idm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val details = FelicaInfo.details(
            idm = idm,
            pmm = byteArrayOf(0x01, 0x02),
            manufacturer = 0x0003,
            systemCode = 0x88B4,
        )

        assertArrayEquals(idm, details.idm)
        assertEquals(0x0003, details.manufacturer)
        assertEquals(0x88B4, details.systemCode)
    }
}
