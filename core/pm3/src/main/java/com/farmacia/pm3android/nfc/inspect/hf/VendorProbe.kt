// Last-resort probe: a plain ISO 14443-3B card that never answered ISO-DEP.
// It keeps the ATQB application data so the screen can show it, and names the
// card without pretending to know its applet.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.DetailRow
import com.farmacia.pm3android.nfc.inspect.DetailSection
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object VendorProbe : HfProbe {

    override val id = "vendor"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.NFC_B) &&
            !context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? {
        val atqb = context.snapshot.atqb ?: return null
        val rows = buildList {
            add(DetailRow("ATQB", atqb.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }))
            add(DetailRow("UID", context.snapshot.uidHex))
        }
        val details = GenericDetails(
            sections = listOf(DetailSection("ISO 14443-3B", rows = rows)),
            summary = "ISO 14443-3B (NfcB)",
        )
        return ProbeOutcome(
            details = details,
            identity = TagIdentifier.iso14443BIdentity(context.snapshot),
        )
    }
}
