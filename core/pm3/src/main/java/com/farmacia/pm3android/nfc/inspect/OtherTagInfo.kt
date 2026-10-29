// Parsers for the two rectangular technologies the phone can read without any
// key: ISO 15693 (NfcV) and FeliCa (NfcF). Android hands the ISO 15693 UID in
// little-endian order, so the display order is the reversed array.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object Iso15693Info {

    /** ISO 15693 UIDs are printed most significant byte first. */
    fun displayUid(tagId: ByteArray): ByteArray = tagId.reversedArray()

    fun details(
        tagId: ByteArray,
        dsfid: Int? = null,
        afi: Int? = null,
        firstBlock: ByteArray? = null,
    ): Iso15693Details {
        val block = firstBlock?.takeIf { it.isNotEmpty() }
        return Iso15693Details(
            uid = displayUid(tagId),
            dsfid = dsfid,
            afi = afi,
            firstBlock = block,
            blockSize = block?.size,
            manufacturer = manufacturer(tagId),
        )
    }

    /**
     * ISO 15693 UID layout: 0xE0, manufacturer code, six serial bytes. The
     * code is the second byte of the display order, not the first.
     */
    fun manufacturer(tagId: ByteArray): String? {
        val uid = displayUid(tagId)
        if (uid.size < 2) return null
        return when (uid[1].toInt() and 0xFF) {
            0x01 -> "Motorola"
            0x02 -> "STMicroelectronics"
            0x03 -> "Hitachi"
            0x04 -> "NXP"
            0x05 -> "Infineon"
            0x07 -> "Texas Instruments"
            0x08 -> "Fujitsu"
            0x0A -> "NEC"
            0x0C -> "Toshiba"
            0x0E -> "Samsung"
            0x0F -> "Hyundai"
            0x10 -> "LG"
            0x16 -> "EM Microelectronic"
            0x17 -> "SMARTRAC"
            0x34 -> "Mikron"
            else -> null
        }
    }
}

object FelicaInfo {

    fun details(
        idm: ByteArray,
        pmm: ByteArray? = null,
        manufacturer: Int? = null,
        systemCode: Int? = null,
    ): FelicaDetails = FelicaDetails(
        idm = idm.copyOf(),
        pmm = pmm?.copyOf(),
        manufacturer = manufacturer,
        systemCode = systemCode,
    )
}
