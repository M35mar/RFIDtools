// Identity model shared by the phone-NFC inspector and its unit tests.
// Everything here is plain Kotlin: no Android types, so it can be tested on a
// JVM without a phone or a tag.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.toHex

/** Tag families the inspector can name. */
enum class TagFamily {
    MIFARE_CLASSIC,
    MIFARE_PLUS,
    MIFARE_ULTRALIGHT,
    NTAG,
    DESFIRE,
    CALYPSO,
    ISO_DEP,
    ISO_14443_B,
    ISO_15693,
    FELICA,
    EMV,
    CIPURSE,
    PIV,
    /** ICAO 9303 travel document: passport, ID card, residence permit. */
    EMRTD,
    /** National electronic identity card with its own applet (CIE, eID, ...). */
    EID,
    /** STMicroelectronics ST25 family: ST25TA, ST25TN, ST25TV, ST25DV. */
    ST25,
    /** NXP ICODE / ISO 15693 label ICs. */
    ICODE,
    /** Infineon SLE66R family, including my-d move. */
    SLE66R,
    /** Infineon my-d move lean, the reduced SLE66R variant. */
    MY_D,
    /** NXP SmartMX secure element: ISO-DEP applets, no linear memory. */
    SMARTMX,
    /** EM Microelectronic HF parts (EM4233, EM4237, EM4423, EM4425). */
    EM_MICROELECTRONIC,
    /** ISO/IEC 18092 passive communication, as exposed by Android NfcF. */
    ISO_18092,
    UNKNOWN,
}

/** How sure the identification is: HIGH means the tag named itself. */
enum class TagConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

object NfcTech {
    const val MIFARE_CLASSIC = "android.nfc.tech.MifareClassic"
    const val MIFARE_ULTRALIGHT = "android.nfc.tech.MifareUltralight"
    const val NFC_A = "android.nfc.tech.NfcA"
    const val NFC_B = "android.nfc.tech.NfcB"
    const val NFC_F = "android.nfc.tech.NfcF"
    const val NFC_V = "android.nfc.tech.NfcV"
    const val ISO_DEP = "android.nfc.tech.IsoDep"
    const val NDEF = "android.nfc.tech.Ndef"
}

/**
 * What the phone knows about a tag before any MIFARE or DESFire command runs:
 * the discovery data Android already decoded, plus an optional GET_VERSION.
 */
class TagSnapshot(
    val uid: ByteArray,
    /** `Tag.getTechList()` entries, for example `NfcTech.NFC_A`. */
    val techs: List<String> = emptyList(),
    /** ISO/IEC 14443-3A ATQA, when the tag exposes NfcA. */
    val atqa: Int? = null,
    /** ISO/IEC 14443-3A SAK, when the tag exposes NfcA. */
    val sak: Int? = null,
    /** Raw GET_VERSION response, when the tag answered 0x60. */
    val version: ByteArray? = null,
    /** NDEF capacity reported by Android, when the tag exposes Ndef. */
    val ndefCapacity: Int? = null,
    /** ISO 14443-3B ATQB bytes, when the tag exposes NfcB. */
    val atqb: ByteArray? = null,
    /** ISO 14443-4 ATS (answer to select), when the tag exposes IsoDep. */
    val ats: ByteArray? = null,
    /** ISO 14443-4 ATS historical bytes, when the transport exposed them. */
    val historicalBytes: ByteArray? = null,
    /** Technology specific facts collected by the probe, when any. */
    val details: TagDetails? = null,
) {
    val uidHex: String get() = uid.toHex()

    val uidLength: Int get() = uid.size

    fun hasTech(name: String): Boolean = techs.contains(name)
}

/** Why an identification is uncertain, or what the app cannot read. */
enum class TagNote {
    /** Seven-byte UID: clone or Plus, Android has no MifareClassic for it. */
    UID_7_BYTE,

    /** Ten-byte UID, the ISO 15693 layout. */
    UID_10_BYTE,

    /** A Classic SAK without the MifareClassic tech: some clones do this. */
    CLASSIC_WITHOUT_TECH,

    /** The tag answered no GET_VERSION, so the variant is a guess. */
    VERSION_UNAVAILABLE,

    /** DESFire is a keyed filesystem, not a linear memory to dump. */
    DESFIRE_NO_DUMP,

    /** Plus at level 2 or 3 needs AES keys before any read. */
    PLUS_NEEDS_KEYS,

    /** Plus level 2 keeps Crypto1 data behind an AES gate: not readable here. */
    PLUS_SL2_UNSUPPORTED,

    /** The UID vendor byte is NXP, but clones report it too. */
    VENDOR_MAYBE_CLONE,

    /** Calypso keeps its ticket files behind keys. */
    CALYPSO_KEYS,

    /** Some phone NFC controllers are unreliable on ISO 14443-B. */
    ISO_B_WEAK,

    /** The free DESFire directory commands answered "authentication required". */
    DESFIRE_LOCKED,

    /** An eMRTD needs the MRZ (BAC) or PACE before any data file can be read. */
    EMRTD_NEEDS_MRZ,

    /** The document advertises PACE; BAC may be disabled by the issuer. */
    EMRTD_PACE,

    /** DG2/DG3 carry biometric data: read only on explicit user request. */
    EMRTD_BIOMETRIC,

    /** A national eID applet with a proprietary protocol, not ICAO BAC. */
    EID_PROPRIETARY,

    /** Infineon SLE66R/my-d commands are proprietary and partly not public. */
    SLE66R_PROPRIETARY,

    /** The my-d move variant comes from the memory size, not from GET_VERSION. */
    MY_D_VARIANT_INFERRED,

    /** SmartMX is a secure element: applets only, no linear memory. */
    SMARTMX_NO_MEMORY,

    /** ISO 15693 user memory is protected by a password. */
    ISO_15693_PASSWORD,

    /** GET SYSTEM INFO did not answer, so the memory geometry is partial. */
    ISO15693_NO_SYSTEM_INFO,

    /** The ST25 part answered, but the exact variant needs a vendor command. */
    ST25_VARIANT,

    /** NFC-DEP (ISO 18092 peer-to-peer) is not exposed by the Android NFC API. */
    FELICA_NO_P2P,

    /** NDEF was found but the message could not be read completely. */
    NDEF_READ_FAILED,

    /** Only the APDU console can go further on this card. */
    APDU_ONLY,
}

/** Result of [TagIdentifier.identify], ready to be shown by the inspector UI. */
class TagIdentity(
    val family: TagFamily,
    /** Human readable name, for example `MIFARE Classic 1K`. */
    val label: String,
    val confidence: TagConfidence,
    val uid: ByteArray,
    val vendor: String?,
    val atqa: Int?,
    val sak: Int?,
    val memoryBytes: Int?,
    val techs: List<String>,
    val version: VersionInfo?,
    /** Machine readable notes, localised by the screen. */
    val notes: List<TagNote>,
) {
    val uidHex: String get() = uid.toHex()

    val uidLength: Int get() = uid.size

    /**
     * A dump needs a linear memory the app can read end to end: Android's
     * MifareClassic tech (Classic and Plus SL1 with a four-byte UID) or a
     * page addressable Ultralight/NTAG on top of NfcA.
     */
    val dumpable: Boolean
        get() = techs.contains(NfcTech.MIFARE_CLASSIC) ||
            family == TagFamily.MIFARE_ULTRALIGHT ||
            family == TagFamily.NTAG ||
            family == TagFamily.MY_D

    /** Same identity with extra notes, de-duplicated and order preserved. */
    fun withNotes(extra: List<TagNote>): TagIdentity = TagIdentity(
        family = family,
        label = label,
        confidence = confidence,
        uid = uid,
        vendor = vendor,
        atqa = atqa,
        sak = sak,
        memoryBytes = memoryBytes,
        techs = techs,
        version = version,
        notes = (notes + extra).distinct(),
    )
}
