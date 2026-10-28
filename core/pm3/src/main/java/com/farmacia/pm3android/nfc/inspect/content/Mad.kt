// MIFARE Application Directory: CRC-8/MIFARE-MAD, the MAD1 header in sector 0,
// the optional MAD2 header in sector 16 and the AID names worth showing.
// Layout per NXP AN10787 and the Proxmark3 client (client/src/mifare/mad.c).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

/** One MAD entry: the AID assigned to a sector. */
class MadEntry(
    val sector: Int,
    val aid: Int,
    /** Human readable name for the AIDs we know, null otherwise. */
    val name: String?,
) {
    val aidHex: String get() = "0x%04X".format(aid)
}

/** Decoded MAD header, from MAD1 plus MAD2 when the card has one. */
class MadDirectory(
    val version: Int,
    /** Sector whose data belongs to the card publisher, 0 when unset. */
    val publisherSector: Int,
    val multiApplication: Boolean,
    val directoryAvailable: Boolean,
    val crcOk: Boolean,
    val entries: List<MadEntry>,
) {
    /** Entries the card actually assigned: `free` and `defect` slots are noise. */
    val assigned: List<MadEntry> get() = entries.filter { it.aid != 0x0000 && it.aid != 0x0001 }
}

object MadCrc {

    /** CRC-8/MIFARE-MAD: poly 0x1D, init 0xC7, MSB first, no reflection. */
    fun compute(bytes: ByteArray): Int {
        var crc = 0xC7
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    (crc shl 1) xor 0x1D
                } else {
                    crc shl 1
                }
                crc = crc and 0xFF
            }
        }
        return crc
    }
}

object MadParser {

    private const val SECTOR_BYTES = 64
    private const val BLOCK_BYTES = 16

    /** GPB flags in the sector-0 trailer, byte 9. */
    private const val GPB_DA = 0x80
    private const val GPB_MA = 0x40
    private const val GPB_VERSION = 0x03

    /** AIDs 0x0000..0x0005 are reserved for directory bookkeeping. */
    private const val AID_ADMIN_MAX = 5

    const val AID_NDEF = 0x03E1

    private val ADMIN_NAMES = mapOf(
        0x0000 to "free",
        0x0001 to "defect",
        0x0002 to "reserved",
        0x0003 to "additional directory info",
        0x0004 to "card holder info",
        0x0005 to "not applicable",
    )

    /**
     * Decodes the MAD header. [sectorZero] is the whole sector 0 (64 bytes),
     * [sectorSixteen] the whole sector 16 when the card is a MAD2 card.
     *
     * Returns null when the sector is too short or the GPB does not announce a
     * MAD version the parser knows: an unrecognised header must not be shown
     * as a directory.
     */
    fun parse(sectorZero: ByteArray, sectorSixteen: ByteArray? = null): MadDirectory? {
        if (sectorZero.size < SECTOR_BYTES) return null
        val gpb = sectorZero[3 * BLOCK_BYTES + 9].toInt() and 0xFF
        val version = gpb and GPB_VERSION
        if (version != 1 && version != 2) return null

        val crc1 = MadCrc.compute(sectorZero.copyOfRange(BLOCK_BYTES + 1, 3 * BLOCK_BYTES))
        val crcOk = crc1 == (sectorZero[BLOCK_BYTES].toInt() and 0xFF)
        val publisher = (sectorZero[BLOCK_BYTES + 1].toInt() and 0x3F)
        val entries = mutableListOf<MadEntry>()
        for (index in 0 until 15) {
            val aid = aidAt(sectorZero, MAD1_AID_START + index * 2)
            entries += MadEntry(index + 1, aid, aidName(aid))
        }

        val withMad2 = version == 2 &&
            sectorSixteen != null &&
            sectorSixteen.size >= 3 * BLOCK_BYTES
        var crcOkAll = crcOk
        if (withMad2) {
            val crc2 = MadCrc.compute(sectorSixteen.copyOfRange(1, 3 * BLOCK_BYTES))
            crcOkAll = crcOkAll && crc2 == (sectorSixteen[0].toInt() and 0xFF)
            for (index in 0 until 23) {
                val aid = aidAt(sectorSixteen, 2 + index * 2)
                entries += MadEntry(index + 17, aid, aidName(aid))
            }
        }

        return MadDirectory(
            version = version,
            publisherSector = publisher,
            multiApplication = gpb and GPB_MA != 0,
            directoryAvailable = gpb and GPB_DA != 0,
            crcOk = crcOkAll,
            entries = entries,
        )
    }

    /** AID name for the entries worth naming, null for publisher-specific ones. */
    fun aidName(aid: Int): String? = when (aid) {
        in 0..AID_ADMIN_MAX -> ADMIN_NAMES[aid]
        AID_NDEF -> "NDEF"
        else -> null
    }

    private fun aidAt(data: ByteArray, offset: Int): Int {
        val high = data[offset].toInt() and 0xFF
        val low = data[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    /** MAD1 data starts in block 1: CRC, info byte, then 14 bytes of AIDs. */
    private const val MAD1_AID_START = BLOCK_BYTES + 2
}
