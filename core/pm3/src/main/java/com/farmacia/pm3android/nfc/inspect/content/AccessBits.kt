// MIFARE Classic access conditions: the three bytes in a sector trailer, decoded
// with the NXP tables, plus a compact rendering for the sector table and the
// report. Pure Kotlin, so the matrix is unit-testable without a tag.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.MfcAccess

/** A key that may use a block: Key A or Key B. */
enum class AccessKey {
    A,
    B,
}

/** What one block group allows. An empty set means "never". */
class AccessCondition(
    val read: Set<AccessKey>,
    val write: Set<AccessKey>,
    val increment: Set<AccessKey>,
    val decrement: Set<AccessKey>,
) {
    val readable: Boolean get() = read.isNotEmpty()

    /** `R:A|B W:B I:- D:-`, language neutral for the sector table. */
    fun compact(): String = buildString {
        append("R:").append(keys(read))
        append(" W:").append(keys(write))
        if (increment.isNotEmpty() || decrement.isNotEmpty()) {
            append(" I:").append(keys(increment))
            append(" D:").append(keys(decrement))
        }
    }
}

/** A field of the sector trailer and the keys allowed to read or write it. */
class TrailerFieldAccess(
    val read: Set<AccessKey>,
    val write: Set<AccessKey>,
) {
    fun compact(): String = "R:${keys(read)} W:${keys(write)}"
}

/** Decoded sector trailer: three data block groups plus Key A/ACL/Key B. */
class SectorAccess(
    val data: List<AccessCondition>,
    val keyA: TrailerFieldAccess,
    val accessBits: TrailerFieldAccess,
    val keyB: TrailerFieldAccess,
) {
    init {
        require(data.size == 3) { "a Classic sector has three data block groups" }
    }

    /** `KeyA R:- W:A · ACL R:A W:- · KeyB R:A W:A` for the sector table. */
    fun trailerCompact(): String =
        "KeyA ${keyA.compact()} · ACL ${accessBits.compact()} · KeyB ${keyB.compact()}"
}

object AccessBits {

    /**
     * Decodes the trailer's bytes 6..8. Returns null when the trailer is too
     * short; the bits themselves are always readable, even if the card uses a
     * non-standard combination.
     */
    fun parse(trailer: ByteArray): SectorAccess? {
        if (trailer.size < 9) return null
        val bits = accessBytes(trailer)
        val data = (0 until 3).map { group -> dataTable[MfcAccess.condition(bits, group)] }
        val trailerRow = trailerTable[MfcAccess.condition(bits, 3)]
        return SectorAccess(
            data = data,
            keyA = trailerRow[0],
            accessBits = trailerRow[1],
            keyB = trailerRow[2],
        )
    }

    /**
     * True when the inverted nibbles agree with the plain ones. A dump with
     * broken access bits grants undefined permissions, so the report flags it
     * instead of presenting the decoded table as fact.
     */
    fun isConsistent(trailer: ByteArray): Boolean =
        trailer.size >= 9 && MfcAccess.isValid(accessBytes(trailer))

    private fun accessBytes(trailer: ByteArray): ByteArray =
        byteArrayOf(trailer[6], trailer[7], trailer[8])

    // Index = C1 C2 C3. Rows follow the data-block table of the NXP datasheet:
    // read/write/increment/decrement keys.
    private val dataTable: List<AccessCondition> = listOf(
        condition("A|B", "A|B", "A|B", "A|B"), // 000 transport
        condition("A|B", "-", "-", "A|B"), // 001 value block
        condition("A|B", "-", "-", "-"), // 010 read/write
        condition("B", "B", "-", "-"), // 011 read/write
        condition("A|B", "B", "-", "-"), // 100 read/write
        condition("B", "-", "-", "-"), // 101 read/write
        condition("A|B", "B", "B", "A|B"), // 110 value block
        condition("-", "-", "-", "-"), // 111 inaccessible
    )

    // Index = C1 C2 C3. Each row is Key A, access bits, Key B as read/write.
    private val trailerTable: List<List<TrailerFieldAccess>> = listOf(
        trailerRow("-", "A", "A", "-", "A", "A"), // 000
        trailerRow("-", "A", "A", "A", "A", "A"), // 001 transport
        trailerRow("-", "-", "A", "-", "A", "-"), // 010
        trailerRow("-", "B", "A|B", "B", "-", "B"), // 011
        trailerRow("-", "A", "A", "A", "-", "A"), // 100
        trailerRow("-", "-", "A|B", "B", "-", "-"), // 101
        trailerRow("-", "-", "A", "A", "-", "-"), // 110
        trailerRow("-", "-", "A|B", "-", "-", "-"), // 111
    )

    private fun condition(read: String, write: String, inc: String, dec: String) =
        AccessCondition(keys(read), keys(write), keys(inc), keys(dec))

    private fun trailerRow(
        keyARead: String,
        keyAWrite: String,
        aclRead: String,
        aclWrite: String,
        keyBRead: String,
        keyBWrite: String,
    ) = listOf(
        TrailerFieldAccess(keys(keyARead), keys(keyAWrite)),
        TrailerFieldAccess(keys(aclRead), keys(aclWrite)),
        TrailerFieldAccess(keys(keyBRead), keys(keyBWrite)),
    )

    private fun keys(text: String): Set<AccessKey> = when (text) {
        "A" -> setOf(AccessKey.A)
        "B" -> setOf(AccessKey.B)
        "A|B" -> setOf(AccessKey.A, AccessKey.B)
        else -> emptySet()
    }
}

private fun keys(keys: Set<AccessKey>): String = when {
    keys.isEmpty() -> "-"
    keys.size == 2 -> "A|B"
    keys.first() == AccessKey.A -> "A"
    else -> "B"
}
