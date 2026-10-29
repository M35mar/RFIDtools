// Order of the HF probes. ISO-DEP is shared by many card families, so the
// order is chosen from the technology Android discovered: Calypso first on
// ISO 14443-B, DESFire first everywhere else. A probe that names the card
// stops the chain; a probe that only adds details lets the next one run.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.inspect.hf.CalypsoReadProbe
import com.farmacia.pm3android.nfc.inspect.hf.DesfireProbe
import com.farmacia.pm3android.nfc.inspect.hf.EmrtdProbe
import com.farmacia.pm3android.nfc.inspect.hf.FelicaProbe
import com.farmacia.pm3android.nfc.inspect.hf.Iso15693Probe
import com.farmacia.pm3android.nfc.inspect.hf.IsoDepApplicationProbe
import com.farmacia.pm3android.nfc.inspect.hf.NfcAPageProbe
import com.farmacia.pm3android.nfc.inspect.hf.PlusProbe
import com.farmacia.pm3android.nfc.inspect.hf.Type4NdefProbe
import com.farmacia.pm3android.nfc.inspect.hf.VendorProbe

object HfProbeRegistry {

    private val isoDepOnA = listOf(
        DesfireProbe,
        EmrtdProbe,
        Type4NdefProbe,
        CalypsoReadProbe,
    )

    private val isoDepOnB = listOf(
        CalypsoReadProbe,
        EmrtdProbe,
        DesfireProbe,
        Type4NdefProbe,
    )

    private val nfcV = listOf(Iso15693Probe)

    private val nfcF = listOf(FelicaProbe)

    private val nfcA = listOf(PlusProbe, NfcAPageProbe)

    /** Runs after every technology specific probe: it always answers. */
    private val isoDepFallback = listOf(IsoDepApplicationProbe)

    private val always = listOf(VendorProbe)

    fun run(context: ProbeContext): ProbeResult {
        val base = TagIdentifier.identify(context.snapshot)
        var identity = base
        var details: TagDetails? = context.snapshot.details
        var content: com.farmacia.pm3android.nfc.inspect.content.TagContent? = null
        val notes = mutableListOf<TagNote>()

        for (probe in probesFor(context.snapshot)) {
            if (!probe.matches(context)) continue
            val outcome = try {
                probe.run(context)
            } catch (t: Throwable) {
                if (context.abort(t)) throw t
                null
            } ?: continue

            outcome.identity?.let { identity = it }
            outcome.details?.let { details = it }
            outcome.content?.let { content = it }
            notes += outcome.notes
            if (outcome.identity != null) break
        }

        // A probe may only have contributed details: re-run the pure rules so
        // the identity reflects them without touching the card again.
        if (details != null && identity === base) {
            identity = TagIdentifier.identify(context.snapshot, details)
        }
        if (notes.isNotEmpty()) identity = identity.withNotes(notes)

        return ProbeResult(
            identity = identity,
            details = details,
            content = content,
            log = context.log.snapshot(),
        )
    }

    private fun probesFor(snapshot: TagSnapshot): List<HfProbe> {
        val onIsoB = snapshot.hasTech(NfcTech.NFC_B) && !snapshot.hasTech(NfcTech.NFC_A)
        val isoDep = when {
            !snapshot.hasTech(NfcTech.ISO_DEP) -> emptyList()
            onIsoB -> isoDepOnB
            else -> isoDepOnA
        }
        val v = if (snapshot.hasTech(NfcTech.NFC_V)) nfcV else emptyList()
        val f = if (snapshot.hasTech(NfcTech.NFC_F)) nfcF else emptyList()
        val a = if (snapshot.hasTech(NfcTech.NFC_A)) nfcA else emptyList()
        val fallback = if (snapshot.hasTech(NfcTech.ISO_DEP)) isoDepFallback else emptyList()
        return isoDep + v + f + a + fallback + always
    }
}
