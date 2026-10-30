// Access-bit helpers for MIFARE Classic sector trailers.
//
// With `a` pointing at byte 6 of a trailer, the three bytes hold C1/C2/C3 plus
// their complements:
//
//   byte 6: high = ~C2, low = ~C1
//   byte 7: high = C1,  low = ~C3
//   byte 8: high = C3,  low = C2
//
// This mirrors `mfValidateAccessConditions` / `mf_get_accesscondition` in the
// PM3 client (client/src/mifare/mifare4.c). A dump whose nibbles do not agree
// came from a partial read and must not be written back: PM3 replaces it with
// the transport configuration, and so do we.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

/**
 * Which key opens a block for writing. `000` works with either key, the other
 * writable data conditions require Key B, and the remaining ones never allow a
 * plain write. Sector trailers use a different table, which can require Key A.
 */
enum class MfcKeyRequirement {
    ANY,
    A,
    B,
    NONE,
}

object MfcAccess {

    /** `FF 07 80 69`: the factory configuration (both keys may read and write). */
    val DEFAULT: ByteArray = byteArrayOf(0xFF.toByte(), 0x07, 0x80.toByte(), 0x69)

    /**
     * True when the inverted nibbles in [access] (bytes 6..8) agree with the
     * plain ones. A trailer that fails this check would grant undefined
     * permissions.
     */
    fun isValid(access: ByteArray): Boolean {
        if (access.size < 3) return false
        val b6 = access[0].toInt() and 0xFF
        val b7 = access[1].toInt() and 0xFF
        val b8 = access[2].toInt() and 0xFF

        val c1 = (b7 ushr 4) and 0x0F
        val c2 = b8 and 0x0F
        val c3 = (b8 ushr 4) and 0x0F

        return (b6 and 0x0F) == (c1 xor 0x0F) &&
            (b6 ushr 4) == (c2 xor 0x0F) &&
            (b7 and 0x0F) == (c3 xor 0x0F)
    }

    /**
     * Access condition of one block in its sector, encoded as
     * `C1 << 2 | C2 << 1 | C3` like `mf_get_accesscondition`.
     *
     * A 4K large sector has sixteen blocks but still four access groups: the
     * PM3 decoder describes block 0, 5, 10 and the trailer with groups 0..3.
     * [blocksInSector] selects that layout; the default is the 4-block sector.
     */
    fun condition(access: ByteArray, blockInSector: Int, blocksInSector: Int = 4): Int {
        val index = if (blocksInSector > 4) {
            (blockInSector / 5).coerceIn(0, 3)
        } else {
            blockInSector.coerceIn(0, 3)
        }
        val b7 = access[1].toInt() and 0xFF
        val b8 = access[2].toInt() and 0xFF
        val c1 = (b7 ushr (4 + index)) and 1
        val c2 = (b8 ushr index) and 1
        val c3 = (b8 ushr (4 + index)) and 1
        return (c1 shl 2) or (c2 shl 1) or c3
    }

    /**
     * Key needed for INCREMENT of a data block. Only `000` (either key) and
     * `110` (Key B) allow it; `001` is decrement-only.
     */
    fun incrementKeyRequirement(
        access: ByteArray,
        blockInSector: Int,
        blocksInSector: Int = 4,
    ): MfcKeyRequirement = when (condition(access, blockInSector, blocksInSector)) {
        0x00 -> MfcKeyRequirement.ANY
        0x06 -> MfcKeyRequirement.B
        else -> MfcKeyRequirement.NONE
    }

    /**
     * Key needed for DECREMENT of a data block: `000` and `001` accept either
     * key, `110` requires Key B. TRANSFER follows the same table as the
     * operation, so the same requirement is used for both commands.
     */
    fun decrementKeyRequirement(
        access: ByteArray,
        blockInSector: Int,
        blocksInSector: Int = 4,
    ): MfcKeyRequirement = when (condition(access, blockInSector, blocksInSector)) {
        0x00, 0x01 -> MfcKeyRequirement.ANY
        0x06 -> MfcKeyRequirement.B
        else -> MfcKeyRequirement.NONE
    }

    /** C1, C2, C3 for one block of the sector. */
    fun conditions(access: ByteArray, blockInSector: Int): Triple<Boolean, Boolean, Boolean> {
        val condition = condition(access, blockInSector)
        return Triple(
            condition and 0x04 != 0,
            condition and 0x02 != 0,
            condition and 0x01 != 0,
        )
    }

    /**
     * Whether a plain WRITE is allowed on a data block: 000 (transport),
     * 011 and 100 (key B) and 110 (key B). 001 is decrement-only, 010 and 101
     * are read-only, 111 is inaccessible. Key permissions are not considered:
     * the engine authenticates before writing.
     */
    fun isDataBlockWritable(access: ByteArray, blockInSector: Int): Boolean =
        when (condition(access, blockInSector)) {
            0x00, 0x03, 0x04, 0x06 -> true
            else -> false
        }

    /** True for the configurations a plain WRITE can never change. */
    fun isReadOnly(access: ByteArray, blockInSector: Int): Boolean =
        !isDataBlockWritable(access, blockInSector)

    /**
     * Key type needed to write a data block. Conditions `011`, `100` and `110`
     * only accept Key B; using a Key A session there is the classic reason a
     * differential restore leaves some blocks untouched.
     */
    fun writeKeyRequirement(access: ByteArray, blockInSector: Int): MfcKeyRequirement =
        when (condition(access, blockInSector)) {
            0x00 -> MfcKeyRequirement.ANY
            0x03, 0x04, 0x06 -> MfcKeyRequirement.B
            else -> MfcKeyRequirement.NONE
        }

    /**
     * Key type that may write the sector trailer. The bits mean something else
     * than for a data block, so the table is separate. It mirrors the values
     * MIFARE Classic Tool uses for `WriteKeyA` (`Common.getOperationRequirements`):
     * `000`/`001` need Key A, `011`/`100` need Key B and the remaining
     * conditions make the trailer read-only.
     */
    fun trailerWriteRequirement(access: ByteArray): MfcKeyRequirement =
        when (condition(access, 3)) {
            0x00, 0x01 -> MfcKeyRequirement.A
            0x03, 0x04 -> MfcKeyRequirement.B
            else -> MfcKeyRequirement.NONE
        }
}
