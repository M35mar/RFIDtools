// Text of the diagnostic report: identity, notes, MAD/NDEF and the sector
// table. Pure Kotlin so it can be tested without a phone; NfcReport writes it
// next to the dumps as `<base>-report.txt`.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.MfuDumpFiles
import com.farmacia.pm3android.nfc.inspect.DesfireVersion
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
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
        desfire: DesfireVersion? = null,
        applications: List<Int>? = null,
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

        if (desfire != null) {
            appendLine("DESFire")
            appendLine("  Vendor: ${desfire.vendorName}")
            appendLine(
                "  Hardware: 0x%02X/0x%02X/0x%02X".format(
                    desfire.hardwareVendor,
                    desfire.hardwareType,
                    desfire.hardwareSubtype,
                ),
            )
            appendLine("  Software: ${desfire.softwareMajor}.${desfire.softwareMinor}")
            desfire.storageBytes?.let { appendLine("  Storage: $it bytes") }
            if (desfire.generation.isNotEmpty()) {
                appendLine("  Generation: ${desfire.generation}")
            }
            appendLine(
                "  Applications: " +
                    when {
                        applications == null -> "not read"
                        applications.isEmpty() -> "none"
                        else -> applications.joinToString(" · ") { "0x%06X".format(it) }
                    },
            )
            appendLine()
        }

        val classic = content?.classic
        val ultralight = content?.ultralight
        if (classic != null) {
            appendClassic(this, classic)
        } else if (ultralight != null) {
            appendUltralight(this, ultralight)
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
            "7-byte UID: clone or MIFARE Plus. Android cannot dump it."
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
        TagNote.VENDOR_MAYBE_CLONE ->
            "The UID vendor byte says NXP; clones report it too."
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
