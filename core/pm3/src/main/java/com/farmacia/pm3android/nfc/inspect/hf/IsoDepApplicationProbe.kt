// Fallback ISO-DEP probe: when no specific family answered, keep the ATS, the
// historical bytes and the applications found by the AID scan. It is the probe
// that guarantees every ISO-DEP card gets a result, so it runs after the
// technology specific ones.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.ApplicationDetails
import com.farmacia.pm3android.nfc.inspect.ApplicationKind
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.IsoDepAppProbe
import com.farmacia.pm3android.nfc.inspect.IsoDepDetails
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object IsoDepApplicationProbe : HfProbe {

    override val id = "iso-dep-app"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.ISO_DEP)

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withIsoDep { transport ->
            val abort = context.abort
            val application: ApplicationDetails? = try {
                IsoDepAppProbe.probe(transport)
            } catch (error: Throwable) {
                if (abort(error)) throw error
                null
            }

            val details = IsoDepDetails(
                ats = context.snapshot.ats,
                historicalBytes = context.snapshot.historicalBytes,
                applications = listOfNotNull(application),
                smartMx = application?.kind == ApplicationKind.CIPURSE ||
                    application?.kind == ApplicationKind.PIV,
            )
            val identity = if (application != null) {
                TagIdentifier.identify(context.snapshot, application)
            } else {
                TagIdentifier.identify(context.snapshot, details)
            }
            ProbeOutcome(details = details, identity = identity)
        }
}
