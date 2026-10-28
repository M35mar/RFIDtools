// GET_VERSION parsing: the client layout with its leading zero, the raw seven
// byte layout, and the reject paths.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionInfoTest {

    @Test
    fun `ntag213 is decoded from the client layout`() {
        val info = VersionInfo.parse(
            byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03),
        )!!

        assertEquals(0x04, info.vendorId)
        assertEquals(TagFamily.NTAG, info.family)
        assertEquals("NTAG213", info.productName)
        assertEquals(144, info.storageBytes)
        assertEquals(45, info.pageCount)
        assertEquals("NXP", info.vendorName)
    }

    @Test
    fun `seven byte answer is accepted without the leading zero`() {
        val info = VersionInfo.parse(
            byteArrayOf(0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03),
        )!!

        assertEquals(TagFamily.MIFARE_ULTRALIGHT, info.family)
        assertEquals("MIFARE Ultralight EV1 (48 B)", info.productName)
        assertEquals(48, info.storageBytes)
        assertEquals(20, info.pageCount)
    }

    @Test
    fun `plus and desfire keep their family`() {
        val plus = VersionInfo.parse(
            byteArrayOf(0x00, 0x04, 0x02, 0x01, 0x01, 0x00, 0x11, 0x03),
        )!!
        val desfire = VersionInfo.parse(
            byteArrayOf(0x00, 0x04, 0x01, 0x01, 0x01, 0x00, 0x18, 0x05),
        )!!

        assertEquals(TagFamily.MIFARE_PLUS, plus.family)
        assertTrue(plus.productName.startsWith("MIFARE Plus"))
        assertEquals(TagFamily.DESFIRE, desfire.family)
        assertEquals(2048, desfire.storageBytes)
    }

    @Test
    fun `ntag210u has priority over the plain 210`() {
        val info = VersionInfo.parse(
            byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x02, 0x00, 0x0B, 0x03),
        )!!

        assertEquals("NTAG210u", info.productName)
    }

    @Test
    fun `short and empty answers are rejected`() {
        assertNull(VersionInfo.parse(byteArrayOf(0x04, 0x03)))
        assertNull(VersionInfo.parse(ByteArray(0)))
        assertNull(VersionInfo.parse(ByteArray(8)))
    }
}
