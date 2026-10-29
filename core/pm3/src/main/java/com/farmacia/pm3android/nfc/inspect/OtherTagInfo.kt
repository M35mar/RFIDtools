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
        )
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
