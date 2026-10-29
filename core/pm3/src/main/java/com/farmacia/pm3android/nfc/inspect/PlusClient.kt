// MIFARE Plus identification. GET_VERSION is the native 0x60 command, not the
// ISO-wrapped DESFire one, and it answers in three frames of seven bytes:
// hardware, software and production data. The transport layer collects the
// frames; this object decodes them and derives the security level from the SAK.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object PlusInfo {

    /**
     * @param hardware seven bytes of the hardware block
     * @param software seven bytes of the software block, null when the second
     *   frame was not received
     * @param sak ISO 14443-3A SAK, null when unknown
     */
    fun fromVersion(
        hardware: ByteArray?,
        software: ByteArray?,
        sak: Int?,
    ): PlusDetails? {
        val hw = hardware?.let { VersionInfo.parse(it) } ?: return null
        if (hw.productType != VersionInfo.TYPE_PLUS) return null
        val sw = software?.let { VersionInfo.parse(it) }
        return PlusDetails(
            version = hw,
            software = sw,
            securityLevel = securityLevel(sak),
            storageBytes = hw.plusStorageBytes,
        )
    }

    /**
     * SAK map used by the client: 0x08/0x18 (and their seven-byte-UID variants
     * 0x88/0x98/0xB8) are level 1, 0x10/0x11 are level 2, 0x20 is level 3 or a
     * DESFire, which the caller rules out first.
     */
    fun securityLevel(sak: Int?): Int? = when (sak?.and(0xFF)) {
        0x08, 0x18, 0x88, 0x98, 0xB8 -> 1
        0x10, 0x11 -> 2
        0x20 -> 3
        else -> null
    }

    /** Storage size of a GET_VERSION storage code, e.g. 0x16 -> 2048. */
    fun storageBytes(code: Int): Int? =
        if (code and 0xFF == 0) null else 1 shl ((code and 0xFF) shr 1)
}

/**
 * Block geometry of a MIFARE Plus card. It follows the MIFARE Classic 4K map
 * used by the Proxmark3 client: 32 sectors of 4 blocks, then 8 sectors of 16
 * blocks. A 2K card stops at 32 sectors.
 */
object PlusGeometry {

    fun sectorCount(storageBytes: Int?): Int = when {
        storageBytes == null -> 40
        storageBytes <= 2048 -> 32
        else -> 40
    }

    fun blockCount(sector: Int): Int = if (sector < 32) 4 else 16

    fun firstBlock(sector: Int): Int =
        if (sector < 32) sector * 4 else 128 + (sector - 32) * 16

    fun blocks(sector: Int): List<Int> {
        val first = firstBlock(sector)
        return (0 until blockCount(sector)).map { first + it }
    }
}
