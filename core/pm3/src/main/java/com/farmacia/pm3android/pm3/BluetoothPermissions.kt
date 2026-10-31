// Runtime Bluetooth permissions. Android 12 moved the old install-time
// BLUETOOTH/BLUETOOTH_ADMIN pair to the runtime BLUETOOTH_CONNECT/SCAN pair;
// older releases gate discovery behind a location permission instead.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothPermissions {

    /**
     * @param scan true when the caller also starts Classic discovery or a BLE
     *   scan, false when it only lists bonded devices and connects.
     */
    fun required(scan: Boolean): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan) permissions += Manifest.permission.BLUETOOTH_SCAN
            return permissions
        }
        if (!scan) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun missing(context: Context, scan: Boolean): List<String> =
        required(scan).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun has(context: Context, scan: Boolean): Boolean = missing(context, scan).isEmpty()
}
