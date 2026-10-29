// Parser for the eight-byte GET_VERSION (0x60) answer used by MIFARE
// Ultralight, NTAG and Plus. The client wraps the seven card bytes in a leading
// zero, so both layouts are accepted.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

/**
 * Decoded GET_VERSION: vendor, product type/subtype, version, storage code.
 * Codes come from the NXP datasheets and the table in the Proxmark3 client.
 */
class VersionInfo(
    val vendorId: Int,
    val productType: Int,
    val productSubtype: Int,
    val major: Int,
    val minor: Int,
    val storageCode: Int,
    val protocol: Int,
) {
    val vendorName: String get() = vendorNameOf(vendorId)

    val family: TagFamily
        get() = when (productType) {
            TYPE_DESFIRE -> TagFamily.DESFIRE
            TYPE_PLUS -> TagFamily.MIFARE_PLUS
            TYPE_ULTRALIGHT -> TagFamily.MIFARE_ULTRALIGHT
            TYPE_NTAG -> TagFamily.NTAG
            else -> TagFamily.UNKNOWN
        }

    val versionText: String get() = "$major.$minor"

    /**
     * Storage declared by the tag, in bytes. DESFire and Ultralight/NTAG use a
     * lookup table; MIFARE Plus codes the exponent directly, so it needs its
     * own formula (see [plusStorageBytes]).
     */
    val storageBytes: Int?
        get() = if (productType == TYPE_PLUS) plusStorageBytes else STORAGE_BYTES[storageCode]

    /**
     * MIFARE Plus storage: the seven most significant bits are the base-2
     * exponent of the size, the least significant bit is set when the size is
     * between 2^n and 2^(n+1). 0x16 is 2 KB, 0x18 is 4 KB, 0x1A is 8 KB.
     */
    val plusStorageBytes: Int? get() = when {
        productType != TYPE_PLUS -> null
        storageCode and 0xFF == 0 -> null
        else -> 1 shl (storageCode shr 1)
    }

    /** True when the Plus code describes a range instead of an exact size. */
    val plusStorageIsRange: Boolean
        get() = productType == TYPE_PLUS && storageCode and 0x01 != 0

    /** Hardware generation of a MIFARE Plus card, empty when unknown. */
    val plusGeneration: String
        get() = when (productSubtype) {
            0x01 -> "EV1"
            0x02 -> "EV2"
            else -> ""
        }

    /** Total pages of an Ultralight/NTAG memory map, when the size is known. */
    val pageCount: Int? get() = PAGE_COUNTS[storageCode]

    /**
     * Product name for the families whose map is known exactly. Unknown codes
     * keep the family name instead of guessing a variant.
     */
    val productName: String
        get() = when (productType) {
            TYPE_ULTRALIGHT -> when (storageCode) {
                0x0B -> "MIFARE Ultralight EV1 (48 B)"
                0x0E -> "MIFARE Ultralight EV1 (128 B)"
                0x0F -> "MIFARE Ultralight AES"
                else -> "MIFARE Ultralight"
            }

            TYPE_NTAG -> when (storageCode) {
                0x0B -> if (productSubtype == 0x02) "NTAG210u" else "NTAG210"
                0x0E -> "NTAG212"
                0x0F -> "NTAG213"
                0x11 -> "NTAG215"
                0x13 -> "NTAG216"
                else -> "NTAG"
            }

            TYPE_PLUS -> buildString {
                append("MIFARE Plus")
                if (plusGeneration.isNotEmpty()) append(' ').append(plusGeneration)
                plusStorageBytes?.let { append(' ').append(sizeLabel(it)) }
            }

            TYPE_DESFIRE -> "MIFARE DESFire $versionText"
            else -> "Tag NXP"
        }

    companion object {
        const val TYPE_DESFIRE = 0x01
        const val TYPE_PLUS = 0x02
        const val TYPE_ULTRALIGHT = 0x03
        const val TYPE_NTAG = 0x04

        private const val VENDOR_NXP = 0x04

        /** Storage code of GET_VERSION to byte count. */
        private val STORAGE_BYTES = mapOf(
            0x0B to 48,
            0x0E to 128,
            0x0F to 144,
            0x10 to 208,
            0x11 to 496,
            0x13 to 872,
            // DESFire generations report the same field with its own codes.
            0x18 to 2048,
            0x19 to 4096,
            0x1A to 8192,
        )

        /**
         * Page count of the matching memory map. Ultralight EV1 48 B has four
         * configuration pages, every larger variant has five.
         */
        private val PAGE_COUNTS = mapOf(
            0x0B to 20,
            0x0E to 41,
            0x0F to 45,
            0x11 to 135,
            0x13 to 231,
        )

        /**
         * Parses a GET_VERSION response. A response shorter than seven bytes,
         * or one that does not start with a vendor id, is rejected: the caller
         * then keeps the SAK based identification.
         */
        fun parse(bytes: ByteArray): VersionInfo? {
            if (bytes.size < 7) return null
            val data = when (bytes[0].toInt() and 0xFF) {
                0x00 -> {
                    if (bytes.size < 8) return null
                    bytes.copyOfRange(1, 8)
                }

                else -> bytes.copyOfRange(0, 7)
            }
            val vendor = data[0].toInt() and 0xFF
            if (vendor == 0x00) return null
            val productType = data[1].toInt() and 0xFF
            return VersionInfo(
                vendorId = vendor,
                productType = productType,
                productSubtype = data[2].toInt() and 0xFF,
                major = data[3].toInt() and 0xFF,
                minor = data[4].toInt() and 0xFF,
                storageCode = data[5].toInt() and 0xFF,
                protocol = data[6].toInt() and 0xFF,
            )
        }

        fun vendorNameOf(vendorId: Int): String = when (vendorId) {
            VENDOR_NXP -> "NXP"
            0x03 -> "Hitachi"
            0x05 -> "Infineon"
            0x16 -> "EM Microelectronic"
            0x34 -> "Mikron"
            0x53 -> "Feiju"
            else -> "0x%02X".format(vendorId)
        }

        /** Human size of a memory, rounded to the marketing label. */
        fun sizeLabel(bytes: Int): String = when {
            bytes >= 1024 && bytes % 1024 == 0 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
