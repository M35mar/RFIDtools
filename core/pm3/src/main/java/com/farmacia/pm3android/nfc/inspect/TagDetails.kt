// Typed description of everything the phone managed to read from a tag,
// grouped by technology. The classes are plain Kotlin: the Android probe fills
// them, the Compose screen and the text report only read them, and the unit
// tests build them without a phone.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.toHex

/** Extra facts about a tag, one subclass per technology family. */
sealed class TagDetails

// ---------------------------------------------------------------------------
// MIFARE DESFire
// ---------------------------------------------------------------------------

/** PICC level data: free memory, master key settings and their version. */
class DesfirePicc(
    val freeMemory: Int? = null,
    val keySettings: Int? = null,
    val keyCount: Int? = null,
    val keyVersion: Int? = null,
) {
    /** True when the card refuses the free directory commands without a key. */
    val locked: Boolean get() = keySettings == null && keyCount == null
}

/**
 * One DESFire application. Every field is nullable because a card may refuse a
 * single command (a key or a MAC is needed) while answering the others: the UI
 * shows the missing values as unavailable instead of hiding the application.
 */
class DesfireApplication(
    val id: Int,
    val dfName: ByteArray? = null,
    val keySettings: Int? = null,
    val keyCount: Int? = null,
    val fileIds: List<Int>? = null,
    val isoFileIds: List<DesfireIsoFile>? = null,
    val files: List<DesfireFile>? = null,
    /** At least one command answered "authentication required". */
    val locked: Boolean = false,
) {
    val idHex: String get() = "0x%06X".format(id)

    /** Same application with a new file list, used by the authenticated read. */
    fun withFiles(newFiles: List<DesfireFile>?, newLocked: Boolean = locked): DesfireApplication =
        DesfireApplication(
            id = id,
            dfName = dfName,
            keySettings = keySettings,
            keyCount = keyCount,
            fileIds = fileIds,
            isoFileIds = isoFileIds,
            files = newFiles,
            locked = newLocked,
        )
}

/** ISO file identifier of a DESFire file (the 16-bit id used over ISO 7816). */
class DesfireIsoFile(val fileId: Int, val isoId: Int)

enum class DesfireFileType {
    STANDARD_DATA,
    BACKUP_DATA,
    VALUE,
    LINEAR_RECORD,
    CYCLIC_RECORD,
    UNKNOWN,
}

/** Result of GetFileSettings: the file is described, not read. */
class DesfireFile(
    val id: Int,
    val type: DesfireFileType,
    /** Communication mode, 0 plain, 1 MACed, 3 enciphered (null if unknown). */
    val communication: Int? = null,
    val accessRights: Int? = null,
    val sizeBytes: Int? = null,
    val recordSize: Int? = null,
    val recordCount: Int? = null,
    val lowerLimit: Int? = null,
    val upperLimit: Int? = null,
    /** Raw content, filled only by an authenticated read. */
    val data: ByteArray? = null,
) {
    /** Same file with the content read from the card. */
    fun withData(bytes: ByteArray?): DesfireFile = DesfireFile(
        id = id,
        type = type,
        communication = communication,
        accessRights = accessRights,
        sizeBytes = sizeBytes,
        recordSize = recordSize,
        recordCount = recordCount,
        lowerLimit = lowerLimit,
        upperLimit = upperLimit,
        data = bytes,
    )
}

class DesfireDetails(
    val version: DesfireVersion,
    val picc: DesfirePicc? = null,
    /** Null means the list was never read, empty means the card has none. */
    val applications: List<DesfireApplication>? = null,
    val cardUid: ByteArray? = null,
) : TagDetails()

// ---------------------------------------------------------------------------
// MIFARE Plus
// ---------------------------------------------------------------------------

class PlusDetails(
    val version: VersionInfo,
    val software: VersionInfo? = null,
    /** 0, 1, 2 or 3 when it can be deduced, null when it cannot. */
    val securityLevel: Int? = null,
    val storageBytes: Int? = null,
    /** Blocks of the sector read with an AES key, when the user asked for it. */
    val sectorRead: PlusSectorRead? = null,
) : TagDetails() {
    fun withSector(read: PlusSectorRead): PlusDetails =
        PlusDetails(version, software, securityLevel, storageBytes, read)
}

/** One 16-byte block read in SL3. */
class PlusBlock(val number: Int, val data: ByteArray)

/** Result of an authenticated sector read. */
class PlusSectorRead(
    val sector: Int,
    val keyB: Boolean,
    val blocks: List<PlusBlock>,
)

// ---------------------------------------------------------------------------
// Calypso / BNEVT
// ---------------------------------------------------------------------------

/** The seven startup bytes carried by FCI tag 0x53. */
class CalypsoStartup(
    val sessionModifications: Int,
    val platform: Int,
    val applicationType: Int,
    val applicationSubtype: Int,
    val softwareIssuer: Int,
    val softwareVersion: Int,
    val softwareRevision: Int,
)

/**
 * Manufacturing record (EF 0x0002, record 1, 29 bytes). It is readable without
 * keys on most cards and carries the card serial and the country code.
 */
class CalypsoIcc(
    val raw: ByteArray,
    val serial: ByteArray?,
    val countryCode: Int?,
    val manufacturer: Int?,
    val initializationDate: java.time.LocalDate?,
    val check: Int?,
    val checkValid: Boolean?,
)

class CalypsoDetails(
    val dfName: ByteArray,
    /** Curated name from [AidCatalog], null when the AID is not in the table. */
    val label: String? = null,
    val serial: ByteArray? = null,
    val startup: CalypsoStartup? = null,
    val icc: CalypsoIcc? = null,
    /** Ticketing environment record, usually readable without a secure session. */
    val environment: CalypsoEnvironment? = null,
    /** Other standard files the card left readable without a secure session. */
    val files: List<CalypsoFile> = emptyList(),
    /** `0x94` for ISO 14443-B, `0x00` for everything else. */
    val cla: Int? = null,
    /** True when the card answered on ISO 14443-B. */
    val iso14443B: Boolean = false,
    /** Files of the selected application might need keys. */
    val locked: Boolean = false,
) : TagDetails()

/** Environment and Holder record: version and network identifier. */
class CalypsoEnvironment(
    val raw: ByteArray,
    val version: Int?,
    val networkId: Int?,
)

/** Standard Calypso files the inspector tries to read without a session. */
enum class CalypsoFileKind {
    EVENTS_LOG,
    CONTRACTS,
    COUNTERS,
    SPECIAL_EVENTS,
    CONTRACT_LIST,
}

class CalypsoFile(
    val kind: CalypsoFileKind,
    val sfi: Int,
    val records: List<ByteArray>,
)

// ---------------------------------------------------------------------------
// ISO 15693 / FeliCa / plain ISO-DEP applications
// ---------------------------------------------------------------------------

class Iso15693Details(
    /** UID in display order: Android hands it out the other way round. */
    val uid: ByteArray,
    val dsfid: Int? = null,
    val afi: Int? = null,
    /** First memory block, when the read was allowed. */
    val firstBlock: ByteArray? = null,
    val blockSize: Int? = null,
    val blocks: Int? = null,
) : TagDetails()

class FelicaDetails(
    /** IDm, the eight byte identifier Android exposes as the tag id. */
    val idm: ByteArray,
    val pmm: ByteArray? = null,
    val manufacturer: Int? = null,
    val systemCode: Int? = null,
) : TagDetails()

/** Kind of ISO-DEP application recognised from its AID. */
enum class ApplicationKind {
    EMV,
    CIPURSE,
    PIV,
    NDEF,
    ACCESS,
    OTHER,
}

class ApplicationDetails(
    val aid: ByteArray,
    val label: String? = null,
    val kind: ApplicationKind = ApplicationKind.OTHER,
    /** Free-form provider from the catalogue, for example `Visa`. */
    val provider: String? = null,
) : TagDetails() {
    val aidHex: String get() = aid.toHex()
}
