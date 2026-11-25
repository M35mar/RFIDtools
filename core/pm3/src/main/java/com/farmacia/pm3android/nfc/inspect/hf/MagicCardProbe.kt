// Non-destructive Magic card probe for Classic-SAK NfcA cards.
//
// Detects Gen4 GTU (CF..CC), Gen3 / unlocked UFUID (raw 30 00 without Crypto1)
// and 7-byte Classic clones without writing anything to the tag.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.DetailRow
import com.farmacia.pm3android.nfc.inspect.DetailSection
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.MagicCardDetector
import com.farmacia.pm3android.nfc.inspect.MagicKind
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object MagicCardProbe : HfProbe {

    override val id = "magic-classic"

    override fun matches(context: ProbeContext): Boolean {
        val snapshot = context.snapshot
        if (!snapshot.hasTech(NfcTech.NFC_A) || snapshot.hasTech(NfcTech.ISO_DEP)) return false
        val sak = snapshot.sak?.and(0xFF) ?: return false
        return TagIdentifier.isClassicSak(sak)
    }

    override fun run(context: ProbeContext): ProbeOutcome? {
        val detection = MagicCardDetector.probeClassicLike(
            snapshot = context.snapshot,
            session = context.session,
            abort = context.abort,
        ) ?: return null
        // A plain 7-byte clone is already covered by the CLASSIC_WITHOUT_TECH note.
        if (detection.kind == MagicKind.CLASSIC_7B_CLONE) return null

        val baseIdentity = TagIdentifier.identify(context.snapshot, null)
        val details = GenericDetails(
            sections = listOf(
                DetailSection(
                    title = "Magic / Clone Detection",
                    rows = listOf(
                        DetailRow("Detected Type", detection.kind.badge),
                        DetailRow("Evidence", detection.detail),
                    ),
                ),
            ),
            summary = "${baseIdentity.label} · ${detection.kind.badge}",
        )
        return ProbeOutcome(
            identity = baseIdentity,
            details = details,
        )
    }
}
