// MIFARE Plus: the GET_VERSION storage formula and the SAK to security level
// map used by the inspector.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlusInfoTest {

    private val hardware = byteArrayOf(0x04, 0x02, 0x01, 0x01, 0x00, 0x16, 0x03)
    private val software = byteArrayOf(0x04, 0x02, 0x01, 0x02, 0x01, 0x16, 0x03)

    @Test
    fun `storage code is the exponent of the size`() {
        assertEquals(2048, VersionInfo.parse(hardware)!!.plusStorageBytes)
        assertEquals(4096, PlusInfo.storageBytes(0x18))
        assertEquals(8192, PlusInfo.storageBytes(0x1A))
        assertNull(PlusInfo.storageBytes(0x00))
    }

    @Test
    fun `security level follows the sak`() {
        assertEquals(1, PlusInfo.securityLevel(0x08))
        assertEquals(1, PlusInfo.securityLevel(0x88))
        assertEquals(2, PlusInfo.securityLevel(0x10))
        assertEquals(3, PlusInfo.securityLevel(0x20))
        assertNull(PlusInfo.securityLevel(0x00))
    }

    @Test
    fun `plus version yields product, level and storage`() {
        val details = PlusInfo.fromVersion(hardware, software, sak = 0x20)

        assertNotNull(details)
        assertEquals(TagFamily.MIFARE_PLUS, details!!.version.family)
        assertEquals("MIFARE Plus EV1 2 KB", details.version.productName)
        assertEquals(3, details.securityLevel)
        assertEquals(2048, details.storageBytes)
        assertEquals("2.1", details.software?.versionText)
    }

    @Test
    fun `a desfire version is not reported as plus`() {
        val desfire = byteArrayOf(0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03)

        assertNull(PlusInfo.fromVersion(desfire, null, sak = 0x20))
        assertNull(PlusInfo.fromVersion(null, null, sak = 0x20))
    }
}
