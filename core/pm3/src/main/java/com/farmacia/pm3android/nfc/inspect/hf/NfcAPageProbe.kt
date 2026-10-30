// Page-addressed memory over raw NfcA. Android does not expose MifareUltralight
// for every Type 2 chip (the Infineon my-d move lean is the common case on
// transport tickets), but the chip still answers the 4-block read command, so
// the inspector can show the memory instead of declaring it unreadable.
//
// The probe only runs when GET_VERSION did not answer: a tag that names itself
// keeps the Ultralight/NTAG path and its dump support.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.PageMemory
import com.farmacia.pm3android.nfc.inspect.PageMemoryDetails
import com.farmacia.pm3android.nfc.inspect.PageMemoryReader
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagConfidence
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.TagSnapshot
import com.farmacia.pm3android.nfc.inspect.content.TagContentAnalyzer

object NfcAPageProbe : HfProbe {

    override val id = "nfcA-pages"

    /** Infineon manufacturer code in the first UID byte. */
    private const val VENDOR_INFINEON = 0x05

    /** ATQA of a double-size UID Type A card, in both byte orders. */
    private val ATQA_DOUBLE_UID = setOf(0x0044, 0x4400)

    /** Blocks of the two my-d move variants, from the Infineon datasheet. */
    private const val LEAN_BLOCKS = 16
    private const val MOVE_BLOCKS = 38

    /**
     * The full my-d move has 38 blocks, but RD4B answers four at a time: the
     * last complete group starts at block 36, so a tag that refuses the window
     * that would run past its end is still recognisable as the 152-byte part.
     */
    private const val MOVE_MIN_BLOCKS = 36

    override fun matches(context: ProbeContext): Boolean {
        val snapshot = context.snapshot
        return snapshot.hasTech(NfcTech.NFC_A) &&
            !snapshot.hasTech(NfcTech.ISO_DEP) &&
            !snapshot.hasTech(NfcTech.MIFARE_CLASSIC) &&
            !snapshot.hasTech(NfcTech.MIFARE_ULTRALIGHT) &&
            // GET_VERSION names Ultralight/NTAG and Plus: those keep their own path.
            snapshot.version == null
    }

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withNfcA { transport ->
            val memory = PageMemoryReader.read(transport, abort = context.abort)
            if (memory.blockCount == 0) return@withNfcA null
            val myD = isMyD(context.snapshot, memory)
            ProbeOutcome(
                details = PageMemoryDetails(
                    pages = memory.pages,
                    variantInferred = myD,
                ),
                content = TagContentAnalyzer.pages(
                    pages = memory.pages,
                    uidHex = context.snapshot.uidHex,
                ),
                identity = identity(context.snapshot, memory, myD),
            )
        }

    /**
     * An Infineon my-d move is a double-size UID card with a clean SAK 0x00 and
     * the Infineon manufacturer code. NTAG and Ultralight share ATQA and SAK but
     * answer GET_VERSION, so they never reach this probe. Some controllers hand
     * the ATQA back in either byte order, so both forms are accepted; when the
     * ATQA is unusual the memory size is the fallback, since only a my-d move
     * answers with 16 or 36+ blocks and no GET_VERSION.
     */
    private fun isMyD(snapshot: TagSnapshot, memory: PageMemory): Boolean {
        val vendor = snapshot.uid.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        if (vendor != VENDOR_INFINEON || snapshot.sak != 0x00) return false
        if (snapshot.atqa in ATQA_DOUBLE_UID) return true
        return snapshot.uid.size == 7 &&
            (memory.blockCount == LEAN_BLOCKS || memory.blockCount >= MOVE_MIN_BLOCKS)
    }

    private fun identity(
        snapshot: TagSnapshot,
        memory: PageMemory,
        myD: Boolean,
    ): TagIdentity = TagIdentity(
        family = if (myD) TagFamily.MY_D else TagFamily.UNKNOWN,
        label = labelOf(memory, myD),
        confidence = if (myD) TagConfidence.HIGH else TagConfidence.MEDIUM,
        uid = snapshot.uid,
        vendor = if (myD) "Infineon" else null,
        atqa = snapshot.atqa,
        sak = snapshot.sak,
        memoryBytes = memory.sizeBytes,
        techs = snapshot.techs,
        version = null,
        notes = if (myD) {
            listOf(TagNote.MY_D_VARIANT_INFERRED)
        } else {
            listOf(TagNote.VERSION_UNAVAILABLE)
        },
    )

    /**
     * The variant comes from the memory size: the lean part stops at block 16,
     * the full one at block 38 (36 when the last four-block window is refused).
     * Anything else is reported without a guess.
     */
    private fun labelOf(memory: PageMemory, myD: Boolean): String {
        if (!myD) return "NFC Forum Type 2 (page memory)"
        return when (memory.blockCount) {
            LEAN_BLOCKS -> "Infineon my-d move lean (SLE66R01L)"
            in MOVE_MIN_BLOCKS..MOVE_BLOCKS -> "Infineon my-d move (SLE66R01P)"
            else -> "Infineon my-d move (SLE66R01x)"
        }
    }
}
