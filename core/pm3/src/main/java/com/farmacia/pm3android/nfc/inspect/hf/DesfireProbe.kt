// DESFire probe: the free PICC and application commands, plus the NDEF
// application when the card exposes one (a DESFire is a Type 4 tag then).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.DesfireClient
import com.farmacia.pm3android.nfc.inspect.DesfireInspector
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NdefType4
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object DesfireProbe : HfProbe {

    override val id = "desfire"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withIsoDep { transport ->
            val abort = context.abort
            val details = try {
                DesfireInspector.read(DesfireClient(transport), abort)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            } ?: return@withIsoDep null

            val content = try {
                NdefType4.read(transport)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            }

            ProbeOutcome(
                details = details,
                content = content,
                identity = TagIdentifier.identify(context.snapshot, details),
            )
        }
}
