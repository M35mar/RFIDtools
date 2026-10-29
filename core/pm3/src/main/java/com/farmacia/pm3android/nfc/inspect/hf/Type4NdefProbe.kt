// NDEF Type 4 probe for ISO-DEP cards that are not DESFire or Calypso: the
// NFC Forum application is selected, the capability container read and the
// message decoded. The result is the same NdefContent the Type 2 path uses.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.DetailRow
import com.farmacia.pm3android.nfc.inspect.DetailSection
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NdefType4
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus

object Type4NdefProbe : HfProbe {

    override val id = "ndef-type4"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withIsoDep { transport ->
            val abort = context.abort
            val content = try {
                NdefType4.read(transport)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            } ?: return@withIsoDep null

            val ndef = content.ndef
            if (!ndef.found && ndef.status != NdefStatus.TRUNCATED) return@withIsoDep null

            val rows = buildList {
                add(DetailRow("Record", ndef.records.size.toString()))
                add(DetailRow("Message bytes", ndef.bytes.toString()))
                ndef.records.firstOrNull()?.text?.let { add(DetailRow("Text", it)) }
            }
            val details = GenericDetails(
                sections = listOf(DetailSection("NDEF Type 4", rows = rows)),
                summary = "NDEF Type 4 (ISO-DEP)",
            )
            ProbeOutcome(
                details = details,
                content = content,
                identity = TagIdentifier.identify(context.snapshot, details),
                notes = if (ndef.status == NdefStatus.TRUNCATED) {
                    listOf(TagNote.NDEF_READ_FAILED)
                } else {
                    emptyList()
                },
            )
        }
}
