// Device model shared by the Classic SPP and BLE transports. The UI shows one
// unified list: bonded Classic devices and BLE scan results live side by side.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

enum class BluetoothLink {
    CLASSIC,
    LE,
}

enum class BluetoothAdapterState {
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    OFF,
    ON,
}

data class BluetoothDeviceInfo(
    val name: String?,
    val address: String,
    val link: BluetoothLink,
    val bonded: Boolean = false,
    val rssi: Int? = null,
) {

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address

    /** Best-effort recognition of the two Proxmark wireless modules. */
    val isProxmark: Boolean
        get() {
            val value = name?.uppercase().orEmpty()
            return value.contains("PM3") ||
                value.contains("PROXMARK") ||
                value.contains("RDV4")
        }

    /**
     * Diagnostics are pasted into chats and issues: keep the first and last
     * octet, hide the middle of the address.
     */
    val maskedAddress: String
        get() {
            val parts = address.split(':')
            if (parts.size != 6) return address
            return "${parts[0]}:${parts[1]}:**:**:**:**"
        }
}

object BluetoothAddress {

    private val PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    fun isValid(value: String): Boolean = PATTERN.matches(value.trim())

    fun normalize(value: String): String = value.trim().uppercase()
}
