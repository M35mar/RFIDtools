// Pure-Kotlin Dump Diff / Compare engine (inspired by MIFARE Classic Tool).
//
// Compares any two dumps of the same family (Classic, Ultralight/NTAG, or
// ISO 15693) regardless of file container (.bin, .nfc, .json, .eml) and
// produces byte-level + semantic differences (Value Block deltas, trailer
// keys/access changes, AUTH0/ACCESS changes).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

data class DumpDiffRow(
    val index: Int,
    val sector: Int?,
    val before: ByteArray?,
    val after: ByteArray?,
    val changedByteIndices: Set<Int>,
    val semanticNotes: List<String>,
)

data class DumpDiffReport(
    val leftName: String,
    val rightName: String,
    val familyLabel: String,
    val totalRows: Int,
    val changedRows: List<DumpDiffRow>,
) {
    val identical: Boolean get() = changedRows.isEmpty()
}

object DumpDiff {

    fun compare(left: DumpImage, right: DumpImage): DumpDiffReport {
        return when {
            left is DumpImage.Classic && right is DumpImage.Classic ->
                compareClassic(left.dump, right.dump)
            left is DumpImage.Ultralight && right is DumpImage.Ultralight ->
                compareUltralight(left.dump, right.dump)
            left is DumpImage.Iso15693 && right is DumpImage.Iso15693 ->
                compareIso15693(left.dump, right.dump)
            else -> throw DumpFormatException(
                "Impossibile confrontare famiglie diverse: ${left.tagLabel} vs ${right.tagLabel}",
            )
        }
    }

    private fun compareClassic(left: MfcDump, right: MfcDump): DumpDiffReport {
        val total = maxOf(left.size.blocks, right.size.blocks)
        val diffs = mutableListOf<DumpDiffRow>()
        for (i in 0 until total) {
            val a = if (i < left.size.blocks) left.block(i) else null
            val b = if (i < right.size.blocks) right.block(i) else null
            val changed = changedOffsets(a, b)
            if (changed.isEmpty()) continue
            val notes = mutableListOf<String>()
            if (a != null && b != null) {
                if (i == 0) {
                    notes += "Manufacturer / UID block changed"
                } else if (MfcGeometry.isTrailer(i)) {
                    if (!a.copyOfRange(0, 6).contentEquals(b.copyOfRange(0, 6))) {
                        notes += "Key A: ${a.copyOfRange(0, 6).toHex()} → ${b.copyOfRange(0, 6).toHex()}"
                    }
                    if (!a.copyOfRange(6, 9).contentEquals(b.copyOfRange(6, 9))) {
                        notes += "Access: ${a.copyOfRange(6, 9).toHexSpaced()} → ${b.copyOfRange(6, 9).toHexSpaced()}"
                    }
                    if (!a.copyOfRange(10, 16).contentEquals(b.copyOfRange(10, 16))) {
                        notes += "Key B: ${a.copyOfRange(10, 16).toHex()} → ${b.copyOfRange(10, 16).toHex()}"
                    }
                } else {
                    val valA = if (MfcValueBlock.isConsistent(a)) MfcValueBlock.value(a) else null
                    val valB = if (MfcValueBlock.isConsistent(b)) MfcValueBlock.value(b) else null
                    if (valA != null && valB != null) {
                        val delta = valB.toLong() - valA.toLong()
                        val sign = if (delta >= 0) "+$delta" else "$delta"
                        notes += "Value Block: $valA → $valB ($sign)"
                    }
                }
            }
            diffs += DumpDiffRow(
                index = i,
                sector = MfcGeometry.sectorOfBlock(i),
                before = a,
                after = b,
                changedByteIndices = changed,
                semanticNotes = notes,
            )
        }
        return DumpDiffReport(
            leftName = left.name,
            rightName = right.name,
            familyLabel = left.size.label,
            totalRows = total,
            changedRows = diffs,
        )
    }

    private fun compareUltralight(left: MfuDump, right: MfuDump): DumpDiffReport {
        val total = maxOf(left.pages.size, right.pages.size)
        val layout = if (left.pages.size >= 8) MfuLayout(left.pages.size) else null
        val diffs = mutableListOf<DumpDiffRow>()
        for (i in 0 until total) {
            val a = left.pages.getOrNull(i)
            val b = right.pages.getOrNull(i)
            val changed = changedOffsets(a, b)
            if (changed.isEmpty()) continue
            val notes = mutableListOf<String>()
            if (layout != null && a != null && b != null && left.pages.size == right.pages.size) {
                when (i) {
                    0, 1 -> notes += "UID / BCC changed"
                    2, layout.lockPage -> notes += "Lock bits changed"
                    3 -> notes += "Capability Container / OTP changed"
                    layout.cfg0 -> notes += "CFG0 (AUTH0 0x%02X → 0x%02X)".format(
                        a[3].toInt() and 0xFF,
                        b[3].toInt() and 0xFF,
                    )
                    layout.cfg1 -> notes += "CFG1 (ACCESS 0x%02X → 0x%02X)".format(
                        a[0].toInt() and 0xFF,
                        b[0].toInt() and 0xFF,
                    )
                    layout.pwdPage -> notes += "PWD page changed"
                    layout.packPage -> notes += "PACK page changed"
                }
            }
            diffs += DumpDiffRow(
                index = i,
                sector = null,
                before = a,
                after = b,
                changedByteIndices = changed,
                semanticNotes = notes,
            )
        }
        return DumpDiffReport(
            leftName = left.name,
            rightName = right.name,
            familyLabel = left.layout.variant,
            totalRows = total,
            changedRows = diffs,
        )
    }

    private fun compareIso15693(left: Iso15693Dump, right: Iso15693Dump): DumpDiffReport {
        val total = maxOf(left.blocks.size, right.blocks.size)
        val diffs = mutableListOf<DumpDiffRow>()
        for (i in 0 until total) {
            val a = left.blocks.getOrNull(i)
            val b = right.blocks.getOrNull(i)
            val changed = changedOffsets(a, b)
            if (changed.isEmpty()) continue
            diffs += DumpDiffRow(
                index = i,
                sector = null,
                before = a,
                after = b,
                changedByteIndices = changed,
                semanticNotes = emptyList(),
            )
        }
        return DumpDiffReport(
            leftName = left.name,
            rightName = right.name,
            familyLabel = "ISO 15693 (${left.blockSize} B/block)",
            totalRows = total,
            changedRows = diffs,
        )
    }

    private fun changedOffsets(a: ByteArray?, b: ByteArray?): Set<Int> {
        if (a == null && b == null) return emptySet()
        if (a == null) return b!!.indices.toSet()
        if (b == null) return a.indices.toSet()
        val max = maxOf(a.size, b.size)
        val out = LinkedHashSet<Int>()
        for (idx in 0 until max) {
            if (idx >= a.size || idx >= b.size || a[idx] != b[idx]) out += idx
        }
        return out
    }
}
