// Turns the discovery data Android gives us into a named tag family. The rules
// are ordered by evidence: GET_VERSION wins, then the tech list, then the SAK.
// Everything is pure Kotlin so the whole matrix is unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

object TagIdentifier {

    fun identify(snapshot: TagSnapshot): TagIdentity {
        val version = snapshot.version?.let { VersionInfo.parse(it) }
        val notes = mutableListOf<TagNote>()
        if (snapshot.uid.size == 7) notes += TagNote.UID_7_BYTE
        if (snapshot.uid.size == 10) notes += TagNote.UID_10_BYTE

        return when {
            version != null -> fromVersion(snapshot, version, notes)
            snapshot.hasTech(NfcTech.MIFARE_CLASSIC) -> classic(snapshot, notes)
            snapshot.hasTech(NfcTech.ISO_DEP) -> isoDep(snapshot, notes)
            snapshot.hasTech(NfcTech.NFC_V) -> simple(
                snapshot,
                TagFamily.ISO_15693,
                "ISO 15693 (NfcV)",
                TagConfidence.MEDIUM,
                notes,
            )

            snapshot.hasTech(NfcTech.NFC_F) -> simple(
                snapshot,
                TagFamily.FELICA,
                "FeliCa (NfcF)",
                TagConfidence.MEDIUM,
                notes,
            )

            snapshot.hasTech(NfcTech.NFC_B) -> simple(
                snapshot,
                TagFamily.UNKNOWN,
                "ISO 14443-3B (NfcB)",
                TagConfidence.LOW,
                notes,
            )

            snapshot.hasTech(NfcTech.NFC_A) -> nfcAOnly(snapshot, notes)
            else -> simple(
                snapshot,
                TagFamily.UNKNOWN,
                "Tag non riconosciuto",
                TagConfidence.LOW,
                notes,
            )
        }
    }

    /** True for SAKs Android reports for Classic and Plus level 1 cards. */
    fun isClassicSak(sak: Int): Boolean = SAK_BYTES.containsKey(sak and 0xFF)

    private fun fromVersion(
        snapshot: TagSnapshot,
        version: VersionInfo,
        notes: MutableList<TagNote>,
    ): TagIdentity {
        val family = version.family
        if (family == TagFamily.DESFIRE) notes += TagNote.DESFIRE_NO_DUMP
        if (family == TagFamily.MIFARE_PLUS && !snapshot.hasTech(NfcTech.MIFARE_CLASSIC)) {
            notes += TagNote.PLUS_NEEDS_KEYS
        }
        return TagIdentity(
            family = family,
            label = version.productName,
            confidence = TagConfidence.HIGH,
            uid = snapshot.uid,
            vendor = version.vendorName,
            atqa = snapshot.atqa,
            sak = snapshot.sak,
            memoryBytes = version.storageBytes,
            techs = snapshot.techs,
            version = version,
            notes = notes.toList(),
        )
    }

    private fun classic(snapshot: TagSnapshot, notes: MutableList<TagNote>): TagIdentity {
        val sak = snapshot.sak?.and(0xFF)
        val label = sak?.let(::labelForSak) ?: "MIFARE Classic"
        return TagIdentity(
            family = if (sak == 0x88 || sak == 0x98 || sak == 0xB8) {
                TagFamily.MIFARE_PLUS
            } else {
                TagFamily.MIFARE_CLASSIC
            },
            label = label,
            confidence = if (snapshot.uid.size == 4) TagConfidence.HIGH else TagConfidence.MEDIUM,
            uid = snapshot.uid,
            vendor = vendorOfUid(snapshot.uid, TagFamily.MIFARE_CLASSIC),
            atqa = snapshot.atqa,
            sak = sak,
            memoryBytes = sak?.let { SAK_BYTES[it] },
            techs = snapshot.techs,
            version = null,
            notes = notes.toList(),
        )
    }

    private fun isoDep(snapshot: TagSnapshot, notes: MutableList<TagNote>): TagIdentity {
        notes += TagNote.VERSION_UNAVAILABLE
        return TagIdentity(
            family = TagFamily.ISO_DEP,
            label = "ISO-DEP (DESFire o Plus SL3)",
            confidence = TagConfidence.MEDIUM,
            uid = snapshot.uid,
            vendor = vendorOfUid(snapshot.uid, TagFamily.ISO_DEP),
            atqa = snapshot.atqa,
            sak = snapshot.sak,
            memoryBytes = null,
            techs = snapshot.techs,
            version = null,
            notes = notes.toList(),
        )
    }

    /**
     * NfcA without GET_VERSION and without MifareClassic. A Classic SAK here is
     * what a seven-byte-UID clone reports: the card is named, but Android gives
     * no Crypto1 path, so the memory stays unreadable.
     */
    private fun nfcAOnly(snapshot: TagSnapshot, notes: MutableList<TagNote>): TagIdentity {
        val sak = snapshot.sak?.and(0xFF)
        val classic = sak != null && isClassicSak(sak)
        if (classic) notes += TagNote.CLASSIC_WITHOUT_TECH
        notes += TagNote.VERSION_UNAVAILABLE
        return TagIdentity(
            family = if (classic) TagFamily.MIFARE_CLASSIC else TagFamily.UNKNOWN,
            label = if (classic && sak != null) labelForSak(sak) else "ISO 14443-3A (NfcA)",
            confidence = if (classic) TagConfidence.MEDIUM else TagConfidence.LOW,
            uid = snapshot.uid,
            vendor = vendorOfUid(snapshot.uid, TagFamily.MIFARE_CLASSIC),
            atqa = snapshot.atqa,
            sak = sak,
            memoryBytes = sak?.let { SAK_BYTES[it] },
            techs = snapshot.techs,
            version = null,
            notes = notes.toList(),
        )
    }

    private fun simple(
        snapshot: TagSnapshot,
        family: TagFamily,
        label: String,
        confidence: TagConfidence,
        notes: MutableList<TagNote>,
    ): TagIdentity = TagIdentity(
        family = family,
        label = label,
        confidence = confidence,
        uid = snapshot.uid,
        vendor = vendorOfUid(snapshot.uid, family),
        atqa = snapshot.atqa,
        sak = snapshot.sak,
        memoryBytes = null,
        techs = snapshot.techs,
        version = null,
        notes = notes.toList(),
    )

    private fun labelForSak(sak: Int): String = when (sak and 0xFF) {
        0x08 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Classic Mini"
        0x18 -> "MIFARE Classic 4K"
        0x28 -> "MIFARE Classic 2K"
        0x38 -> "MIFARE Classic 4K"
        0x88 -> "MIFARE Plus SL1 1K"
        0x98 -> "MIFARE Plus SL1 2K"
        0xB8 -> "MIFARE Plus SL1 4K"
        else -> "MIFARE Classic"
    }

    /**
     * Memory of the SAK we know. Plus level 1 uses the same geometry as the
     * Classic card it emulates; level 2 and 3 report different SAKs and are
     * named from GET_VERSION instead.
     */
    private val SAK_BYTES = mapOf(
        0x08 to 1024,
        0x09 to 320,
        0x18 to 4096,
        0x28 to 2048,
        0x38 to 4096,
        0x88 to 1024,
        0x98 to 2048,
        0xB8 to 4096,
    )

    /**
     * Manufacturer code in the first UID byte. Clones of a NXP card copy the
     * code, so a Classic card is reported as NXP-compatible rather than NXP.
     */
    private fun vendorOfUid(uid: ByteArray, family: TagFamily): String? {
        val code = uid.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return when (code) {
            0x04 -> if (family == TagFamily.MIFARE_CLASSIC) "NXP (o clone)" else "NXP"
            0x03 -> "Hitachi"
            0x05 -> "Infineon"
            0x16 -> "EM Microelectronic"
            0x34 -> "Mikron"
            0x53 -> "Feiju"
            else -> null
        }
    }
}
