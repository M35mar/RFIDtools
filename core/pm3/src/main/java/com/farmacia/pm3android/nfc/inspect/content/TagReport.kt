// Text of the diagnostic report: identity, notes, MAD/NDEF and the sector
// table. Pure Kotlin so it can be tested without a phone; NfcReport writes it
// next to the dumps as `<base>-report.txt`.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.MfuDumpFiles
import com.farmacia.pm3android.nfc.inspect.ApplicationDetails
import com.farmacia.pm3android.nfc.inspect.CalypsoDetails
import com.farmacia.pm3android.nfc.inspect.CalypsoFileKind
import com.farmacia.pm3android.nfc.inspect.CalypsoFileParser
import com.farmacia.pm3android.nfc.inspect.DesfireApplication
import com.farmacia.pm3android.nfc.inspect.DesfireDetails
import com.farmacia.pm3android.nfc.inspect.DesfireFile
import com.farmacia.pm3android.nfc.inspect.DesfireFileType
import com.farmacia.pm3android.nfc.inspect.EmrtdDetails
import com.farmacia.pm3android.nfc.inspect.FelicaDetails
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.Iso15693Details
import com.farmacia.pm3android.nfc.inspect.IsoDepDetails
import com.farmacia.pm3android.nfc.inspect.PageMemoryDetails
import com.farmacia.pm3android.nfc.inspect.PlusDetails
import com.farmacia.pm3android.nfc.inspect.TagDetails
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.VersionInfo
import com.farmacia.pm3android.nfc.toHex
import com.farmacia.pm3android.nfc.toHexSpaced
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TagReport {

    private const val MAX_NAME = 80
    private const val MAX_TEXT = 160
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Base name of the report, taken from the saved dump when there is one so
     * the two files sort together, otherwise from the family and the UID.
     */
    fun baseName(identity: TagIdentity, dumpName: String? = null): String {
        val fromDump = dumpName?.let { name ->
            val stem = name.substringBeforeLast('.', name)
            if (stem.endsWith(MfuDumpFiles.DUMP_SUFFIX)) {
                stem.removeSuffix(MfuDumpFiles.DUMP_SUFFIX)
            } else {
                stem
            }
        }
        if (!fromDump.isNullOrBlank()) return sanitize(fromDump)
        return sanitize(
            when (identity.family) {
                TagFamily.MIFARE_CLASSIC, TagFamily.MIFARE_PLUS -> "hf-mf-${identity.uidHex}"
                TagFamily.MIFARE_ULTRALIGHT, TagFamily.NTAG -> "hf-mfu-${identity.uidHex}"
                else -> "tag-${identity.uidHex}"
            },
        )
    }

    fun build(
        identity: TagIdentity,
        content: TagContent?,
        details: TagDetails? = null,
        dumpName: String? = null,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        appendLine("RFIDtools tag report")
        appendLine("Generated: ${TIMESTAMP.format(generatedAt)}")
        appendLine()

        appendLine("Identity")
        appendLine("  Label: ${identity.label}")
        appendLine("  Family: ${identity.family.name}")
        appendLine("  Confidence: ${identity.confidence.name}")
        appendLine("  UID: ${identity.uidHex}")
        appendLine("  UID length: ${identity.uidLength} bytes")
        appendLine("  Vendor: ${identity.vendor ?: "unknown"}")
        identity.atqa?.let { appendLine("  ATQA: 0x%04X".format(it)) }
        identity.sak?.let { appendLine("  SAK: 0x%02X".format(it)) }
        identity.memoryBytes?.let { appendLine("  Memory: $it bytes") }
        if (identity.techs.isNotEmpty()) {
            appendLine("  Technologies: ${identity.techs.joinToString(", ") { shortTech(it) }}")
        }
        appendLine()

        if (identity.notes.isNotEmpty()) {
            appendLine("Notes")
            identity.notes.forEach { appendLine("  - ${noteText(it)}") }
            appendLine()
        }

        val version = identity.version
        if (version != null) {
            appendLine("GET_VERSION")
            appendLine("  Vendor: ${version.vendorName}")
            appendLine("  Product: ${version.productName}")
            appendLine("  Version: ${version.versionText}")
            version.storageBytes?.let { appendLine("  Storage: $it bytes") }
            appendLine("  Product type: 0x%02X".format(version.productType))
            appendLine("  Storage code: 0x%02X".format(version.storageCode))
            appendLine("  Protocol: 0x%02X".format(version.protocol))
            appendLine()
        }

        if (details != null) {
            when (details) {
                is DesfireDetails -> appendDesfire(this, details)
                is PlusDetails -> appendPlus(this, details)
                is CalypsoDetails -> appendCalypso(this, details)
                is Iso15693Details -> appendIso15693(this, details)
                is FelicaDetails -> appendFelica(this, details)
                is ApplicationDetails -> appendApplication(this, details)
                is EmrtdDetails -> appendEmrtd(this, details)
                is IsoDepDetails -> appendIsoDep(this, details)
                is GenericDetails -> appendGeneric(this, details)
                is PageMemoryDetails -> appendPageMemory(this, details)
            }
        }

        val classic = content?.classic
        val ultralight = content?.ultralight
        if (classic != null) {
            appendClassic(this, classic)
        } else if (ultralight != null) {
            appendUltralight(this, ultralight)
        } else if (content != null && content.ndef.status != NdefStatus.ABSENT) {
            // Type 3, 4 and 5: the message was read over its own transport.
            appendNdef(this, content.ndef)
        }

        appendLine("Dump")
        if (dumpName != null) {
            appendLine("  File: $dumpName")
        } else {
            appendLine("  No dump file: the read was not complete or was not saved.")
        }
    }

    /** Safe file stem: no separators or control characters, never hidden. */
    fun sanitize(raw: String): String {
        val cleaned = FORBIDDEN.replace(raw.trim(), "_").trimEnd(' ', '.')
        val base = if (cleaned.isBlank() || cleaned.startsWith(".")) "tag" else cleaned
        return base.take(MAX_NAME)
    }

    private fun appendDesfire(out: StringBuilder, details: DesfireDetails) {
        val version = details.version
        out.appendLine("DESFire")
        out.appendLine("  Product: ${version.label}")
        out.appendLine("  Vendor: ${version.vendorName}")
        out.appendLine(
            "  Hardware: 0x%02X/0x%02X/0x%02X".format(
                version.hardwareVendor,
                version.hardwareType,
                version.hardwareSubtype,
            ),
        )
        out.appendLine("  Software: ${version.softwareMajor}.${version.softwareMinor}")
        version.storageBytes?.let { out.appendLine("  Storage: $it bytes") }
        if (version.generation.isNotEmpty()) {
            out.appendLine("  Generation: ${version.generation}")
        }
        details.picc?.let { picc ->
            picc.freeMemory?.let { out.appendLine("  Free memory: $it bytes") }
            picc.keySettings?.let {
                val keys = picc.keyCount?.let { count -> ", $count key(s)" }.orEmpty()
                out.appendLine("  PICC key settings: 0x%02X%s".format(it, keys))
            }
            picc.keyVersion?.let { out.appendLine("  PICC master key version: 0x%02X".format(it)) }
            if (picc.locked) out.appendLine("  PICC directory: authentication required")
        }
        out.appendLine(
            "  Applications: " +
                when {
                    details.applications == null -> "not read"
                    details.applications.isEmpty() -> "none"
                    else -> details.applications.size.toString()
                },
        )
        details.applications.orEmpty().forEach { application ->
            appendDesfireApplication(out, application)
        }
        out.appendLine()
    }

    private fun appendDesfireApplication(out: StringBuilder, application: DesfireApplication) {
        out.appendLine("  Application ${application.idHex}")
        application.dfName?.let { name ->
            val ascii = name.joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                if (value in 0x20..0x7E) value.toChar().toString() else "."
            }
            out.appendLine("    DF name: $ascii (${name.toHex()})")
        }
        application.keySettings?.let {
            val keys = application.keyCount?.let { count -> ", $count key(s)" }.orEmpty()
            out.appendLine("    Key settings: 0x%02X%s".format(it, keys))
        }
        val files = application.files
        if (files == null) {
            out.appendLine("    Files: not read")
        } else if (files.isEmpty()) {
            out.appendLine("    Files: none")
        } else {
            out.appendLine("    Files: ${files.size}")
            files.forEach { file -> appendDesfireFile(out, file) }
        }
        if (application.locked) {
            out.appendLine("    Authentication required for some reads")
        }
    }

    private fun appendDesfireFile(out: StringBuilder, file: DesfireFile) {
        val type = when (file.type) {
            DesfireFileType.STANDARD_DATA -> "standard data"
            DesfireFileType.BACKUP_DATA -> "backup data"
            DesfireFileType.VALUE -> "value"
            DesfireFileType.LINEAR_RECORD -> "linear record"
            DesfireFileType.CYCLIC_RECORD -> "cyclic record"
            DesfireFileType.UNKNOWN -> "unknown"
        }
        val geometry = when {
            file.sizeBytes != null -> ", ${file.sizeBytes} bytes"
            file.recordSize != null && file.recordCount != null ->
                ", ${file.recordCount} x ${file.recordSize} bytes"

            else -> ""
        }
        val rights = file.accessRights?.let { ", access 0x%04X".format(it) }.orEmpty()
        val limits = if (file.lowerLimit != null && file.upperLimit != null) {
            ", range ${file.lowerLimit}..${file.upperLimit}"
        } else {
            ""
        }
        out.appendLine("      File 0x%02X: %s%s%s%s".format(file.id, type, geometry, rights, limits))
        file.data?.let { out.appendLine("        Data: ${it.toHex()}") }
    }

    private fun appendPlus(out: StringBuilder, details: PlusDetails) {
        out.appendLine("MIFARE Plus")
        appendVersion(out, details.version)
        details.software?.let { software ->
            out.appendLine("  Software version: ${software.versionText}")
        }
        details.securityLevel?.let { out.appendLine("  Security level: $it") }
        details.storageBytes?.let { out.appendLine("  Storage: $it bytes") }
        details.sectorRead?.let { read ->
            out.appendLine(
                "  Sector read: ${read.sector} (key ${if (read.keyB) "B" else "A"})",
            )
            read.blocks.forEach { block ->
                out.appendLine("    Block ${block.number}: ${block.data.toHexSpaced()}")
            }
        }
        out.appendLine()
    }

    private fun appendVersion(out: StringBuilder, version: VersionInfo) {
        out.appendLine("  Vendor: ${version.vendorName}")
        out.appendLine("  Product: ${version.productName}")
        out.appendLine("  Version: ${version.versionText}")
        out.appendLine("  Product type: 0x%02X".format(version.productType))
        out.appendLine("  Storage code: 0x%02X".format(version.storageCode))
        out.appendLine("  Protocol: 0x%02X".format(version.protocol))
    }

    private fun appendCalypso(out: StringBuilder, details: CalypsoDetails) {
        out.appendLine("Calypso")
        out.appendLine("  DF name: ${details.dfName.toHex()}")
        details.label?.let { out.appendLine("  Application: $it") }
        out.appendLine("  CLA: 0x%02X".format(details.cla ?: 0))
        out.appendLine("  ISO 14443-B: ${yesNo(details.iso14443B)}")
        details.serial?.let { out.appendLine("  Serial: ${it.toHex()}") }
        details.startup?.let { startup ->
            out.appendLine(
                "  Startup: session 0x%02X, platform 0x%02X, app 0x%02X/0x%02X".format(
                    startup.sessionModifications,
                    startup.platform,
                    startup.applicationType,
                    startup.applicationSubtype,
                ),
            )
            out.appendLine(
                "  Software: issuer 0x%02X, version 0x%02X, revision 0x%02X".format(
                    startup.softwareIssuer,
                    startup.softwareVersion,
                    startup.softwareRevision,
                ),
            )
        }
        details.icc?.let { icc ->
            out.appendLine("  ICC record: ${icc.raw.toHex()}")
            icc.serial?.let { out.appendLine("    Serial: ${it.toHex()}") }
            icc.countryCode?.let { out.appendLine("    Country: $it") }
            icc.manufacturer?.let { out.appendLine("    Manufacturer: 0x%02X".format(it)) }
            icc.initializationDate?.let { out.appendLine("    Initialized: $it") }
            icc.check?.let { check ->
                val state = when (icc.checkValid) {
                    true -> "ok"
                    false -> "wrong"
                    null -> "unknown"
                }
                out.appendLine("    Check byte: 0x%02X ($state)".format(check))
            }
        }
        details.environment?.let { environment ->
            out.appendLine(
                "  Environment: version ${environment.version ?: "?"}, " +
                    "network " + (environment.networkId?.let { "0x%06X".format(it) } ?: "unknown"),
            )
            out.appendLine("    Raw: ${environment.raw.toHex()}")
        }
        details.files.forEach { file ->
            out.appendLine(
                "  File ${file.kind.name} (SFI 0x%02X, ${file.records.size} record(s))"
                    .format(file.sfi),
            )
            if (file.kind == CalypsoFileKind.COUNTERS) {
                CalypsoFileParser.counterValues(file.records.first())
                    .forEachIndexed { index, value ->
                        out.appendLine("    Counter ${index + 1}: $value")
                    }
            } else {
                file.records.forEachIndexed { index, record ->
                    out.appendLine("    Record ${index + 1}: ${record.toHex()}")
                }
            }
        }
        if (details.locked) out.appendLine("  Ticket files need keys")
        out.appendLine()
    }

    private fun appendIso15693(out: StringBuilder, details: Iso15693Details) {
        out.appendLine("ISO 15693")
        out.appendLine("  UID: ${details.uid.toHex()}")
        details.manufacturer?.let { out.appendLine("  Manufacturer: $it") }
        details.dsfid?.let { out.appendLine("  DSFID: 0x%02X".format(it)) }
        details.afi?.let { out.appendLine("  AFI: 0x%02X".format(it)) }
        details.blocks?.let { out.appendLine("  Blocks: $it") }
        details.blockSize?.let { out.appendLine("  Block size: $it bytes") }
        details.icReference?.let { out.appendLine("  IC reference: 0x%02X".format(it)) }
        details.infoFlags?.let { out.appendLine("  Info flags: 0x%02X".format(it)) }
        details.firstBlock?.let { out.appendLine("  Block 0: ${it.toHexSpaced()}") }
        out.appendLine()
    }

    private fun appendFelica(out: StringBuilder, details: FelicaDetails) {
        out.appendLine("FeliCa")
        out.appendLine("  IDm: ${details.idm.toHex()}")
        details.pmm?.let { out.appendLine("  PMm: ${it.toHex()}") }
        details.manufacturer?.let { out.appendLine("  Manufacturer: 0x%04X".format(it)) }
        details.systemCode?.let { out.appendLine("  System code: 0x%04X".format(it)) }
        details.systemCodes.forEach { out.appendLine("  System code: 0x%04X".format(it)) }
        details.ndefBlock?.let { out.appendLine("  NDEF block 0: ${it.toHexSpaced()}") }
        out.appendLine()
    }

    private fun appendApplication(out: StringBuilder, details: ApplicationDetails) {
        out.appendLine("ISO-DEP application")
        out.appendLine("  AID: ${details.aidHex}")
        details.label?.let { out.appendLine("  Label: $it") }
        out.appendLine("  Kind: ${details.kind.name}")
        details.provider?.let { out.appendLine("  Provider: $it") }
        out.appendLine()
    }

    private fun appendEmrtd(out: StringBuilder, details: EmrtdDetails) {
        out.appendLine("eMRTD / eID")
        out.appendLine("  AID: ${details.aidHex}")
        details.label?.let { out.appendLine("  Label: $it") }
        out.appendLine("  PACE: ${if (details.paceSupported) "yes" else "no"}")
        out.appendLine("  BAC: ${if (details.bacSupported) "yes" else "no"}")
        details.cardAccess?.let { out.appendLine("  EF.CardAccess: ${it.toHexSpaced()}") }
        out.appendLine()
    }

    private fun appendIsoDep(out: StringBuilder, details: IsoDepDetails) {
        out.appendLine("ISO-DEP")
        details.ats?.let { out.appendLine("  ATS: ${it.toHexSpaced()}") }
        details.historicalBytes?.let { out.appendLine("  Historical bytes: ${it.toHexSpaced()}") }
        details.applications.forEach { application ->
            out.appendLine("  AID: ${application.aidHex} ${application.label.orEmpty()}".trimEnd())
        }
        out.appendLine()
    }

    /** Page memory of a Type 2 chip read over raw NfcA (my-d move and similar). */
    private fun appendPageMemory(out: StringBuilder, details: PageMemoryDetails) {
        out.appendLine("Memory (${details.blockCount} blocks, ${details.sizeBytes} bytes, ${details.source})")
        details.rows.forEach { row ->
            out.appendLine("  %03X  %s  %s".format(row.index, row.hex, row.ascii))
        }
        out.appendLine()
    }

    private fun appendGeneric(out: StringBuilder, details: GenericDetails) {
        details.summary?.let { out.appendLine(it) }
        details.sections.forEach { section ->
            out.appendLine(section.title)
            section.rows.forEach { row -> out.appendLine("  ${row.label}: ${row.value}") }
            section.table?.let { table ->
                table.rows.forEach { cells -> out.appendLine("  ${cells.joinToString(" | ")}") }
            }
            out.appendLine()
        }
    }

    private fun appendClassic(out: StringBuilder, content: ClassicContent) {
        content.mad?.let { mad ->
            appendMad(out, mad)
        }
        appendNdef(out, content.ndef)

        out.appendLine("Sectors (${content.readableSectors} of ${content.totalSectors} readable)")
        content.sectors.forEach { sector ->
            val role = when (sector.role) {
                SectorRole.MANUFACTURER -> "manufacturer"
                SectorRole.MAD1 -> "MAD1"
                SectorRole.MAD2 -> "MAD2"
                SectorRole.NDEF -> "NDEF"
                SectorRole.DATA -> "data"
            }
            val aid = sector.aid?.let { entry ->
                val name = sector.aidName?.let { " $it" }.orEmpty()
                ", AID 0x%04X%s".format(entry, name)
            }.orEmpty()
            out.appendLine("  Sector ${sector.sector} (${sector.blocks} blocks, $role$aid)")
            if (!sector.readable) {
                out.appendLine("    Not readable")
                return@forEach
            }
            sector.access?.let { out.appendLine("    Access: ${it.trailerCompact()}") }
            if (sector.access != null && !sector.accessConsistent) {
                out.appendLine("    Access bits: inconsistent")
            }
            if (sector.valueBlock) out.appendLine("    Value block: yes")
            sector.firstDataHex?.let { out.appendLine("    First data block: $it") }
        }
        out.appendLine()
    }

    private fun appendMad(out: StringBuilder, mad: MadDirectory) {
        out.appendLine("MAD")
        out.appendLine("  Version: ${mad.version}")
        out.appendLine("  Publisher sector: ${mad.publisherSector}")
        out.appendLine("  Multi application: ${yesNo(mad.multiApplication)}")
        out.appendLine("  Directory available: ${yesNo(mad.directoryAvailable)}")
        out.appendLine("  CRC: ${if (mad.crcOk) "ok" else "wrong"}")
        if (mad.assigned.isEmpty()) {
            out.appendLine("  No AIDs assigned")
        } else {
            mad.assigned.forEach { entry ->
                val name = entry.name?.let { " $it" }.orEmpty()
                out.appendLine("  ${entry.aidHex}$name -> sector ${entry.sector}")
            }
        }
        out.appendLine()
    }

    private fun appendUltralight(out: StringBuilder, content: UltralightContent) {
        out.appendLine("Type 2 memory")
        out.appendLine("  Pages: ${content.pages}")
        val cc = content.cc
        if (cc == null) {
            out.appendLine("  Capability container: none (the tag does not advertise NDEF)")
        } else {
            out.appendLine(
                "  Capability container: version ${cc.versionMajor}.${cc.versionMinor}, " +
                    "${cc.capacityBytes} bytes, read 0x%X, write 0x%X".format(
                        cc.readAccess,
                        cc.writeAccess,
                    ),
            )
        }
        appendNdef(out, content.ndef)
        out.appendLine("Counters")
        content.counters.forEachIndexed { index, counter ->
            val value = counter.value?.toString() ?: "not supported"
            val tearing = if (counter.tearing) " (tearing)" else ""
            out.appendLine("  Counter ${index + 1}: $value$tearing")
        }
        out.appendLine()
    }

    private fun appendNdef(out: StringBuilder, ndef: NdefContent) {
        out.appendLine("NDEF")
        when (ndef.status) {
            NdefStatus.ABSENT -> {
                out.appendLine("  No NDEF message")
                out.appendLine()
                return
            }

            NdefStatus.TRUNCATED -> {
                out.appendLine("  NDEF data is truncated")
                out.appendLine()
                return
            }

            NdefStatus.INVALID -> {
                out.appendLine("  NDEF data is not valid")
                out.appendLine()
                return
            }

            NdefStatus.FOUND -> Unit
        }
        out.appendLine("  ${ndef.records.size} record(s), ${ndef.bytes} bytes")
        ndef.records.forEachIndexed { index, record ->
            out.appendLine("  ${index + 1}. ${recordText(record)}")
        }
        out.appendLine()
    }

    private fun recordText(record: NdefRecord): String {
        val text = record.text
        return if (text == null) {
            "${record.kind.name} ${quote(record.type)} (${record.payloadBytes} bytes)"
        } else {
            "${record.kind.name} ${quote(text)} (${record.payloadBytes} bytes)"
        }
    }

    private fun noteText(note: TagNote): String = when (note) {
        TagNote.UID_7_BYTE ->
            "7-byte UID: Plus, my-d move or a clone. Readable only through the page commands."
        TagNote.UID_10_BYTE ->
            "10-byte UID: ISO 15693 tag, not a MIFARE memory."
        TagNote.CLASSIC_WITHOUT_TECH ->
            "Classic SAK without the MifareClassic tech: likely a clone."
        TagNote.VERSION_UNAVAILABLE ->
            "No GET_VERSION answer: the variant is a guess."
        TagNote.DESFIRE_NO_DUMP ->
            "DESFire is a keyed filesystem, not a linear memory."
        TagNote.PLUS_NEEDS_KEYS ->
            "Plus at level 2 or 3 needs AES keys before any read."
        TagNote.PLUS_SL2_UNSUPPORTED ->
            "Plus level 2 keeps its data behind an AES gate: not readable here."
        TagNote.VENDOR_MAYBE_CLONE ->
            "The UID vendor byte says NXP; clones report it too."
        TagNote.CALYPSO_KEYS ->
            "Calypso keeps its ticket files behind keys."
        TagNote.ISO_B_WEAK ->
            "Some phone NFC controllers are unreliable on ISO 14443-B."
        TagNote.DESFIRE_LOCKED ->
            "The free DESFire directory commands answered authentication required."
        TagNote.EMRTD_NEEDS_MRZ ->
            "The document needs the MRZ (BAC) or the PACE password before its files can be read."
        TagNote.EMRTD_PACE ->
            "The document advertises PACE: BAC may be disabled."
        TagNote.EMRTD_BIOMETRIC ->
            "DG2/DG3 carry biometric data: read them only on explicit request."
        TagNote.EID_PROPRIETARY ->
            "The identity card uses its own applet: its protocol needs the APDU console."
        TagNote.SLE66R_PROPRIETARY ->
            "SLE66R/my-d commands are proprietary and partly not public."
        TagNote.MY_D_VARIANT_INFERRED ->
            "Variant inferred from the memory size: 16 blocks for SLE66R01L, 38 for SLE66R01P."
        TagNote.SMARTMX_NO_MEMORY ->
            "SmartMX is a secure element: applets only, no linear memory."
        TagNote.ISO_15693_PASSWORD ->
            "The ISO 15693 memory is password protected."
        TagNote.ISO15693_NO_SYSTEM_INFO ->
            "The tag did not answer GET SYSTEM INFO: the memory geometry is partial."
        TagNote.ST25_VARIANT ->
            "The ST25 part answered; the exact variant needs a vendor command."
        TagNote.FELICA_NO_P2P ->
            "NFC-DEP (ISO 18092 peer-to-peer) is not exposed by the Android NFC API."
        TagNote.NDEF_READ_FAILED ->
            "NDEF was found but cannot be read completely."
        TagNote.APDU_ONLY ->
            "Only the APDU console can go further on this card."
    }

    private fun shortTech(tech: String): String = tech.substringAfterLast('.')

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun quote(text: String): String {
        val clipped = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "..." else text
        return buildString {
            append('"')
            clipped.forEach { char ->
                when (char) {
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '"' -> append("\\\"")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
