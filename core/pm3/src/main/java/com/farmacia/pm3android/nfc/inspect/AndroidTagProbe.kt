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
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV

object AndroidTagProbe {

    /**
     * Reads the discovery data and, when it is meaningful, the free answers of
     * the technology: GET_VERSION on NfcA, DSFID and the first block on NfcV,
     * PMm on NfcF. Classic cards are skipped because 0x60 is not implemented.
     */
    fun snapshot(tag: Tag): TagSnapshot {
        val techs = tag.techList?.toList() ?: emptyList()
        val nfcA = NfcA.get(tag)
        val sak = nfcA?.sak?.toInt()?.and(0xFF)
        val classicTech = MifareClassic.get(tag) != null

        var version: ByteArray? = null
        var plus: PlusDetails? = null
        if (nfcA != null && shouldProbeVersion(techs, sak, classicTech)) {
            val frames = runCatching { readVersionFrames(nfcA) }.getOrDefault(emptyList())
            plus = runCatching { plusFromFrames(frames, sak) }.getOrNull()
            if (plus == null) {
                version = runCatching { versionFromFrames(frames) }.getOrNull()
            }
        }

        val iso15693 = runCatching { nfcVDetails(tag) }.getOrNull()
        val felica = runCatching { nfcFDetails(tag) }.getOrNull()
        val capacity = runCatching { Ndef.get(tag)?.maxSize }.getOrNull()

        return TagSnapshot(
            uid = tag.id ?: ByteArray(0),
            techs = techs,
            atqa = nfcA?.atqa?.let(::atqaOf),
            sak = sak,
            version = version,
            ndefCapacity = capacity?.takeIf { it > 0 },
            atqb = runCatching { NfcB.get(tag)?.applicationData }.getOrNull(),
            details = plus ?: iso15693 ?: felica,
        )
    }

    /**
     * Reconnects to a card that did not answer as ISO-DEP and tries the native
     * MIFARE Plus GET_VERSION. A card that is actually a DESFire simply does
     * not answer, so the caller keeps its previous identification.
     */
    fun probePlus(tag: Tag): PlusDetails? {
        val techs = tag.techList?.toList() ?: emptyList()
        if (!techs.contains(NfcTech.NFC_A)) return null
        val nfcA = NfcA.get(tag) ?: return null
        val sak = nfcA.sak.toInt() and 0xFF
        val frames = runCatching { readVersionFrames(nfcA) }.getOrDefault(emptyList())
        return runCatching { plusFromFrames(frames, sak) }.getOrNull()
    }

    /**
     * Classic cards only answer when they are not MIFARE Classic (Plus level 2
     * and 3 expose ISO-DEP or a non-Classic SAK). SAK 0x20 is shared with
     * DESFire, so it is probed by the ISO-DEP client first.
     */
    private fun shouldProbeVersion(
        techs: List<String>,
        sak: Int?,
        classicTech: Boolean,
    ): Boolean {
        if (classicTech) return false
        if (!techs.contains(NfcTech.ISO_DEP)) return true
        return sak != 0x20
    }

    /**
     * GET_VERSION is one frame on Ultralight/NTAG and three native frames on
     * MIFARE Plus (`0xAF`, `0xAF`, `0x90`).
     */
    private fun readVersionFrames(tech: NfcA): List<ByteArray> {
        tech.connect()
        try {
            val first = tech.transceive(byteArrayOf(0x60))?.copyOf() ?: return emptyList()
            val frames = mutableListOf(first)
            if (first.isEmpty() || first[0].toInt() and 0xFF != 0xAF) return frames
            repeat(2) {
                val next = tech.transceive(byteArrayOf(0xAF.toByte()))?.copyOf() ?: return frames
                frames += next
                if (next.isNotEmpty() && next[0].toInt() and 0xFF == 0x90) return frames
            }
            return frames
        } finally {
            runCatching { tech.close() }
        }
    }

    private fun plusFromFrames(
        frames: List<ByteArray>,
        sak: Int?,
    ): PlusDetails? {
        if (frames.isEmpty()) return null
        val first = frames[0]
        var hardware: ByteArray? = null
        var software: ByteArray? = null

        if (first.size >= 8 && first[0].toInt() and 0xFF == 0xAF) {
            hardware = first.copyOfRange(1, 8)
            val second = frames.getOrNull(1)
            if (second != null && second.size >= 8) software = second.copyOfRange(1, 8)
        } else if (first.size >= 28) {
            hardware = first.copyOfRange(0, 7)
            software = first.copyOfRange(7, 14)
        } else {
            return null
        }

        val details = PlusInfo.fromVersion(hardware, software, sak) ?: return null
        // A single frame that starts with 0xAF can only be a Plus answer.
        return details
    }

    private fun versionFromFrames(frames: List<ByteArray>): ByteArray? {
        val first = frames.firstOrNull() ?: return null
        if (first.isEmpty()) return null
        if (first[0].toInt() and 0xFF == 0xAF) return null
        return first.takeIf { it.size >= 7 }
    }

    private fun nfcVDetails(tag: Tag): Iso15693Details? {
        val tech = NfcV.get(tag) ?: return null
        val uid = tag.id ?: return null
        val dsfid = runCatching { tech.dsfId.toInt() and 0xFF }.getOrNull()
        val block = runCatching {
            tech.connect()
            try {
                // Read single block 0: flags byte, command, block number.
                val answer = tech.transceive(byteArrayOf(0x00, 0x20, 0x00)) ?: return@runCatching null
                if (answer.size > 1) answer.copyOfRange(1, answer.size) else null
            } finally {
                runCatching { tech.close() }
            }
        }.getOrNull()
        return Iso15693Info.details(uid, dsfid = dsfid, firstBlock = block)
    }

    private fun nfcFDetails(tag: Tag): FelicaDetails? {
        val tech = NfcF.get(tag) ?: return null
        val idm = tag.id ?: return null
        val manufacturer = runCatching { intOf(tech.manufacturer) }.getOrNull()
        val systemCode = runCatching { intOf(tech.systemCode) }.getOrNull()
        return FelicaInfo.details(
            idm = idm,
            manufacturer = manufacturer,
            systemCode = systemCode,
        )
    }

    private fun intOf(raw: ByteArray?): Int? {
        if (raw == null || raw.size < 2) return null
        return ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    }

    private fun atqaOf(raw: ByteArray): Int? {
        if (raw.size < 2) return null
        return ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    }
}
