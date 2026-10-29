// MIFARE Plus probe over the native NfcA frames. GET_VERSION is 0x60 and, on
// a Plus card, answers in three 0xAF frames. It runs after the ISO-DEP probes
// so a Plus level 3 card, which only answers the native command, is still
// named.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.PlusDetails
import com.farmacia.pm3android.nfc.inspect.PlusInfo
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.RawTagTransport
import com.farmacia.pm3android.nfc.inspect.TagIdentifier

object PlusProbe : HfProbe {

    override val id = "plus"

    override fun matches(context: ProbeContext): Boolean =
        context.snapshot.hasTech(NfcTech.NFC_A) &&
            !context.snapshot.hasTech(NfcTech.MIFARE_CLASSIC) &&
            // The discovery snapshot already read GET_VERSION on a non-ISO-DEP
            // NfcA card, so there is nothing left to probe there.
            context.snapshot.version == null &&
            context.snapshot.details !is PlusDetails

    override fun run(context: ProbeContext): ProbeOutcome? =
        context.session.withNfcA { transport ->
            val details = readVersion(transport, context.snapshot.sak) ?: return@withNfcA null
            ProbeOutcome(
                details = details,
                identity = TagIdentifier.identify(context.snapshot, details),
            )
        }

    private fun readVersion(transport: RawTagTransport, sak: Int?): PlusDetails? {
        val first = runCatching { transport.transceive(byteArrayOf(0x60)) }.getOrNull()
            ?: return null
        if (first.isEmpty()) return null
        val frames = mutableListOf(first)
        if ((first[0].toInt() and 0xFF) == 0xAF) {
            repeat(2) {
                val next = runCatching { transport.transceive(byteArrayOf(0xAF.toByte())) }
                    .getOrNull() ?: return@repeat
                frames += next
            }
        }

        val hardware: ByteArray
        val software: ByteArray?
        if (first.size >= 8 && (first[0].toInt() and 0xFF) == 0xAF) {
            hardware = first.copyOfRange(1, 8)
            software = frames.getOrNull(1)?.takeIf { it.size >= 8 }?.copyOfRange(1, 8)
        } else if (first.size >= 28) {
            hardware = first.copyOfRange(0, 7)
            software = first.copyOfRange(7, 14)
        } else {
            return null
        }
        return PlusInfo.fromVersion(hardware, software, sak)
    }
}
