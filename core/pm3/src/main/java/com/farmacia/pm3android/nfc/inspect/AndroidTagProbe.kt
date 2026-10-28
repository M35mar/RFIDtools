// Android side of the inspector: turns a discovered Tag into a TagSnapshot.
// This is the only place that touches the platform NFC classes; the
// classification itself lives in TagIdentifier and stays unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA

object AndroidTagProbe {

    /**
     * Reads discovery data and, when it is meaningful, GET_VERSION. Classic
     * cards are skipped: they do not implement 0x60 and the call would just
     * waste a field cycle. ISO-DEP cards are skipped too and probed with the
     * DESFire client instead.
     */
    fun snapshot(tag: Tag): TagSnapshot {
        val techs = tag.techList?.toList() ?: emptyList()
        val nfcA = NfcA.get(tag)
        val probeVersion = nfcA != null &&
            !techs.contains(NfcTech.ISO_DEP) &&
            !techs.contains(NfcTech.MIFARE_CLASSIC) &&
            MifareClassic.get(tag) == null
        val version = if (probeVersion && nfcA != null) readVersion(nfcA) else null
        val capacity = runCatching { Ndef.get(tag)?.maxSize }.getOrNull()
        return TagSnapshot(
            uid = tag.id ?: ByteArray(0),
            techs = techs,
            atqa = nfcA?.atqa?.let(::atqaOf),
            sak = nfcA?.sak?.toInt()?.and(0xFF),
            version = version,
            ndefCapacity = capacity?.takeIf { it > 0 },
        )
    }

    private fun atqaOf(raw: ByteArray): Int? {
        if (raw.size < 2) return null
        return ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    }

    /** GET_VERSION is optional: every failure only means "no version". */
    private fun readVersion(tech: NfcA): ByteArray? = try {
        tech.connect()
        val response = tech.transceive(byteArrayOf(0x60))
        response?.takeIf { it.size >= 7 }?.copyOf()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { tech.close() }
    }
}
