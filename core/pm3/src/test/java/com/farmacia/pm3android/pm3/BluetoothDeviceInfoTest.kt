// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDeviceInfoTest {

    @Test
    fun `address validation accepts canonical and lowercase forms`() {
        assertTrue(BluetoothAddress.isValid("00:11:22:AA:BB:CC"))
        assertTrue(BluetoothAddress.isValid("aa:bb:cc:dd:ee:ff"))
        assertTrue(BluetoothAddress.isValid("  00:11:22:33:44:55  "))
    }

    @Test
    fun `address validation rejects malformed values`() {
        assertFalse(BluetoothAddress.isValid(""))
        assertFalse(BluetoothAddress.isValid("00:11:22:33:44"))
        assertFalse(BluetoothAddress.isValid("00:11:22:33:44:55:66"))
        assertFalse(BluetoothAddress.isValid("00-11-22-33-44-55"))
        assertFalse(BluetoothAddress.isValid("GG:11:22:33:44:55"))
    }

    @Test
    fun `address normalization trims and uppercases`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.normalize(" aa:bb:cc:dd:ee:ff "))
    }

    @Test
    fun `display name falls back to the address`() {
        val unnamed = BluetoothDeviceInfo(null, "00:11:22:33:44:55", BluetoothLink.CLASSIC)
        assertEquals("00:11:22:33:44:55", unnamed.displayName)

        val blank = BluetoothDeviceInfo("   ", "00:11:22:33:44:55", BluetoothLink.LE)
        assertEquals("00:11:22:33:44:55", blank.displayName)

        val named = BluetoothDeviceInfo("Proxmark5", "00:11:22:33:44:55", BluetoothLink.LE)
        assertEquals("Proxmark5", named.displayName)
    }

    @Test
    fun `proxmark modules are recognised by name`() {
        assertTrue(info("PM3_RDV4.0").isProxmark)
        assertTrue(info("Proxmark5").isProxmark)
        assertTrue(info("pm3-rdv4").isProxmark)
        assertTrue(info("Proxmark RDV4").isProxmark)
        assertFalse(info("AirPods").isProxmark)
        assertFalse(BluetoothDeviceInfo(null, "00:11:22:33:44:55", BluetoothLink.LE).isProxmark)
    }

    @Test
    fun `masked address keeps only the first two octets`() {
        val value = BluetoothDeviceInfo("PM3", "AA:BB:CC:DD:EE:FF", BluetoothLink.CLASSIC)
        assertEquals("AA:BB:**:**:**:**", value.maskedAddress)

        val malformed = BluetoothDeviceInfo("PM3", "not-an-address", BluetoothLink.CLASSIC)
        assertEquals("not-an-address", malformed.maskedAddress)
    }

    private fun info(name: String) =
        BluetoothDeviceInfo(name, "00:11:22:33:44:55", BluetoothLink.CLASSIC)
}
