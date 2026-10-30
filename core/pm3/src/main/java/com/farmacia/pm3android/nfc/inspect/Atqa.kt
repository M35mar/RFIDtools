// ATQA/SENS_RES decoding. Android hands back the two bytes in the order the
// card sent them (LSB first), while ISO and every datasheet print the 16-bit
// value, so the first byte is the low byte: {0x44, 0x00} is 0x0044, not 0x4400.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object Atqa {

    /** ISO 14443-3A ATQA as a 16-bit value, or null for a short answer. */
    fun value(raw: ByteArray): Int? {
        if (raw.size < 2) return null
        return (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
    }
}
