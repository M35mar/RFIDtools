// Turns the discovery data Android gives us into a named tag family. The rules
// are ordered by evidence: GET_VERSION wins, then the tech list, then the SAK.
// Everything is pure Kotlin so the whole matrix is unit-testable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.toHex

object TagIdentifier {

    fun identify(snapshot: TagSnapshot): TagIdentity = identify(snapshot, snapshot.details)

    /**
     * Identification from the discovery data plus whatever the technology probe
     * managed to read. Details win over the tech list: a Calypso DF name is
     * stronger evidence than "the card speaks ISO-DEP".
     */
    fun identify(snapshot: TagSnapshot, details: TagDetails?): TagIdentity {
        val version = snapshot.version?.let { VersionInfo.parse(it) }
        val notes = mutableListOf<TagNote>()
        if (snapshot.uid.size == 7) notes += TagNote.UID_7_BYTE
        if (snapshot.uid.size == 10) notes += TagNote.UID_10_BYTE

        return when {
            details is CalypsoDetails -> calypso(snapshot, details, notes)
            details is DesfireDetails -> desfire(snapshot, details, notes)
            details is PlusDetails -> plus(snapshot, details, notes)
            details is ApplicationDetails -> application(snapshot, details, notes)
            details is Iso15693Details -> simple(
                snapshot,
                TagFamily.ISO_15693,
                "ISO 15693 (NfcV)",
                TagConfidence.HIGH,
                notes,
            )

            details is FelicaDetails -> simple(
                snapshot,
                TagFamily.FELICA,
                "FeliCa (NfcF)",
                TagConfidence.HIGH,
                notes,
            )

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
                TagFamily.ISO_14443_B,
                "ISO 14443-3B (NfcB)",
                TagConfidence.MEDIUM,
                notes,
            )

            snapshot.hasTech(NfcTech.NFC_A) -> nfcAOnly(snapshot, notes)
            else -> simple(
                snapshot,
                TagFamily.UNKNOWN,
                "Unknown tag",
                TagConfidence.LOW,
                notes,
            )
        }
    }

    /** True for SAKs Android reports for Classic and Plus level 1 cards. */
    fun isClassicSak(sak: Int): Boolean = SAK_BYTES.containsKey(sak and 0xFF)

    /**
     * Upgrades an ISO-DEP guess to a confirmed DESFire once GetVersion answered.
     * The UID and the discovery data are kept: GetVersion adds the generation,
     * the memory size and the NXP vendor.
     */
    fun refineWithDesfire(identity: TagIdentity, version: DesfireVersion): TagIdentity =
        TagIdentity(
            family = TagFamily.DESFIRE,
            label = version.label,
            confidence = TagConfidence.HIGH,
            uid = identity.uid,
            vendor = version.vendorName,
            atqa = identity.atqa,
            sak = identity.sak,
            memoryBytes = version.storageBytes,
            techs = identity.techs,
            version = null,
            notes = (identity.notes - TagNote.VERSION_UNAVAILABLE + TagNote.DESFIRE_NO_DUMP)
                .distinct(),
        )

    private fun calypso(
        snapshot: TagSnapshot,
        details: CalypsoDetails,
        notes: MutableList<TagNote>,
    ): TagIdentity {
        notes += TagNote.CALYPSO_KEYS
        if (details.iso14443B) notes += TagNote.ISO_B_WEAK
        val label = details.label
            ?: "Calypso · ${details.dfName.toHex()}"
        return TagIdentity(
            family = TagFamily.CALYPSO,
            label = label,
            confidence = TagConfidence.HIGH,
            uid = snapshot.uid,
            vendor = details.label?.let { "Calypso Networks Association" } ?: "Calypso",
            atqa = snapshot.atqa,
            sak = snapshot.sak,
            memoryBytes = null,
            techs = snapshot.techs,
            version = null,
            notes = notes.toList(),
        )
    }

    private fun desfire(
        snapshot: TagSnapshot,
        details: DesfireDetails,
        notes: MutableList<TagNote>,
    ): TagIdentity {
        notes += TagNote.DESFIRE_NO_DUMP
        if (details.picc?.locked == true) notes += TagNote.DESFIRE_LOCKED
        return TagIdentity(
            family = TagFamily.DESFIRE,
            label = details.version.label,
            confidence = TagConfidence.HIGH,
            uid = snapshot.uid,
            vendor = details.version.vendorName,
            atqa = snapshot.atqa,
            sak = snapshot.sak,
            memoryBytes = details.version.storageBytes,
            techs = snapshot.techs,
            version = null,
            notes = notes.distinct(),
        )
    }

    private fun plus(
        snapshot: TagSnapshot,
        details: PlusDetails,
        notes: MutableList<TagNote>,
    ): TagIdentity {
        val level = details.securityLevel
        if (level != null && level >= 2) notes += TagNote.PLUS_NEEDS_KEYS
        if (level == 2) notes += TagNote.PLUS_SL2_UNSUPPORTED
        return TagIdentity(
            family = TagFamily.MIFARE_PLUS,
            label = details.version.productName,
            confidence = TagConfidence.HIGH,
            uid = snapshot.uid,
            vendor = details.version.vendorName,
            atqa = snapshot.atqa,
            sak = snapshot.sak,
            memoryBytes = details.storageBytes ?: details.version.storageBytes,
            techs = snapshot.techs,
            version = details.version,
            notes = notes.distinct(),
        )
    }

    private fun application(
        snapshot: TagSnapshot,
        details: ApplicationDetails,
        notes: MutableList<TagNote>,
    ): TagIdentity = TagIdentity(
        family = when (details.kind) {
            ApplicationKind.EMV -> TagFamily.EMV
            ApplicationKind.CIPURSE -> TagFamily.CIPURSE
            ApplicationKind.PIV -> TagFamily.PIV
            else -> TagFamily.ISO_DEP
        },
        label = details.label ?: "ISO-DEP · ${details.aidHex}",
        confidence = TagConfidence.HIGH,
        uid = snapshot.uid,
        vendor = details.provider,
        atqa = snapshot.atqa,
        sak = snapshot.sak,
        memoryBytes = null,
        techs = snapshot.techs,
        version = null,
        notes = notes.toList(),
    )

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
            label = "ISO-DEP (DESFire or Plus SL3)",
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
        val plus = sak == 0x88 || sak == 0x98 || sak == 0xB8
        if (classic) notes += TagNote.CLASSIC_WITHOUT_TECH
        notes += TagNote.VERSION_UNAVAILABLE
        return TagIdentity(
            family = when {
                plus -> TagFamily.MIFARE_PLUS
                classic -> TagFamily.MIFARE_CLASSIC
                else -> TagFamily.UNKNOWN
            },
            label = if (classic) labelForSak(sak) else "ISO 14443-3A (NfcA)",
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
