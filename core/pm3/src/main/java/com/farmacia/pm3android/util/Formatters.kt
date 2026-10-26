// Small formatting helpers shared by the screens.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.util

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    return DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
    ).format(Date(epochMillis))
}

fun formatCount(value: Long): String =
    NumberFormat.getIntegerInstance().format(value)
