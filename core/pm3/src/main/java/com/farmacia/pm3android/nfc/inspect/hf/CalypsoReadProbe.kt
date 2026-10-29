// Calypso probe: select one of the known application identifiers and read the
// free records (ICC, environment, ticket files). Calypso Prime, Light and Move
// all answer the same standard commands; the difference is in the startup
// bytes, which the client already decodes.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.CalypsoClient
import com.farmacia.pm3android.nfc.inspect.CalypsoProbe
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object CalypsoReadProbe : HfProbe {

    override val id = "calypso"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withIsoDep { transport ->
            val iso14443B = context.snapshot.hasTech(NfcTech.NFC_B) &&
                !context.snapshot.hasTech(NfcTech.NFC_A)
            val abort = context.abort
            val details = try {
                CalypsoProbe.read(CalypsoClient(transport, iso14443B), abort)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            } ?: return@withIsoDep null

            ProbeOutcome(
                details = details,
                identity = TagIdentifier.identify(context.snapshot, details),
            )
        }
}
