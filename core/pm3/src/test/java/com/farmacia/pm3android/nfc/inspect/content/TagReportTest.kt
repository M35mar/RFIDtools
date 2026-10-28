// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.content

import com.farmacia.pm3android.nfc.inspect.DesfireVersion
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.TagConfidence
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagNote
import com.farmacia.pm3android.nfc.inspect.VersionInfo
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagReportTest {

    private fun identity(
        family: TagFamily = TagFamily.MIFARE_CLASSIC,
        label: String = "MIFARE Classic 1K",
        notes: List<TagNote> = listOf(TagNote.VENDOR_MAYBE_CLONE),
        version: VersionInfo? = null,
        techs: List<String> = listOf(NfcTech.NFC_A, NfcTech.MIFARE_CLASSIC),
    ) = TagIdentity(
        family = family,
        label = label,
        confidence = TagConfidence.HIGH,
        uid = byteArrayOf(0x04, 0x11, 0x22, 0x33),
        vendor = "NXP (or clone)",
        atqa = 0x0004,
        sak = 0x08,
        memoryBytes = 1024,
        techs = techs,
        version = version,
        notes = notes,
    )

    private fun access() = AccessBits.parse(
        byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 0x80.toByte(),
            0x69, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
        ),
    )

    private fun classicContent() = ClassicContent(
        uidHex = "04112233",
        sizeLabel = "MIFARE Classic 1K",
        sectors = listOf(
            ClassicSector(
                sector = 0,
                blocks = 4,
                readable = true,
                role = SectorRole.MAD1,
                aid = null,
                aidName = null,
                access = access(),
                accessConsistent = true,
                firstDataHex = "04A2B3C4",
                valueBlock = false,
            ),
            ClassicSector(
                sector = 1,
                blocks = 4,
                readable = true,
                role = SectorRole.NDEF,
                aid = 0x03E1,
                aidName = "NDEF",
                access = access(),
                accessConsistent = true,
                firstDataHex = "03 0A D1 01 06 54 02 65 6E",
                valueBlock = false,
            ),
            ClassicSector(
                sector = 2,
                blocks = 4,
                readable = false,
                role = SectorRole.DATA,
                aid = null,
                aidName = null,
                access = null,
                accessConsistent = false,
                firstDataHex = null,
                valueBlock = false,
            ),
        ),
        mad = MadDirectory(
            version = 1,
            publisherSector = 1,
            multiApplication = true,
            directoryAvailable = true,
            crcOk = true,
            entries = listOf(
                MadEntry(1, 0x03E1, "NDEF"),
                MadEntry(2, 0x0000, "free"),
            ),
        ),
        ndef = NdefContent(
            records = listOf(
                NdefRecord(NdefKind.TEXT, "T", "Ciao", 6),
                NdefRecord(NdefKind.URI, "U", "https://example.org", 18),
            ),
            bytes = 42,
            status = NdefStatus.FOUND,
        ),
        readableSectors = 2,
        totalSectors = 3,
    )

    @Test
    fun `classic report contains identity, notes, mad, ndef and sectors`() {
        val report = TagReport.build(
            identity = identity(),
            content = TagContent(classic = classicContent()),
            generatedAt = LocalDateTime.of(2026, 10, 28, 10, 0, 0),
        )
        assertTrue(report.startsWith("RFIDtools tag report\n"))
        assertTrue(report.contains("Generated: 2026-10-28 10:00:00"))
        assertTrue(report.contains("Label: MIFARE Classic 1K"))
        assertTrue(report.contains("Family: MIFARE_CLASSIC"))
        assertTrue(report.contains("UID: 04112233"))
        assertTrue(report.contains("Technologies: NfcA, MifareClassic"))
        assertTrue(report.contains("The UID vendor byte says NXP; clones report it too."))
        assertTrue(report.contains("Version: 1"))
        assertTrue(report.contains("0x03E1 NDEF -> sector 1"))
        assertFalse(report.contains("0x0000 free"))
        assertTrue(report.contains("2 record(s), 42 bytes"))
        assertTrue(report.contains("TEXT \"Ciao\" (6 bytes)"))
        assertTrue(report.contains("URI \"https://example.org\" (18 bytes)"))
        assertTrue(report.contains("Sector 1 (4 blocks, NDEF, AID 0x03E1 NDEF)"))
        assertTrue(report.contains("Sector 2 (4 blocks, data)"))
        assertTrue(report.contains("    Not readable"))
        assertTrue(report.contains("No dump file"))
    }

    @Test
    fun `dump name is recorded when the read was saved`() {
        val report = TagReport.build(
            identity = identity(),
            content = TagContent(classic = classicContent()),
            dumpName = "hf-mf-04112233-dump.bin",
        )
        assertTrue(report.contains("File: hf-mf-04112233-dump.bin"))
    }

    @Test
    fun `get version and desfire blocks are included`() {
        val version = VersionInfo.parse(
            byteArrayOf(
                0x00,
                0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03,
            ),
        )
        val desfire = DesfireVersion(
            hardwareVendor = 0x04,
            hardwareType = 0x01,
            hardwareSubtype = 0x01,
            hardwareMajor = 0x12,
            hardwareMinor = 0x34,
            storageCode = 0x18,
            protocolCode = 0x03,
            softwareMajor = 0x12,
            softwareMinor = 0x34,
        )
        val report = TagReport.build(
            identity = identity(family = TagFamily.DESFIRE, notes = emptyList(), version = version),
            content = null,
            desfire = desfire,
            applications = listOf(0x000000, 0x123456),
        )
        assertTrue(report.contains("GET_VERSION"))
        assertTrue(report.contains("Product: MIFARE DESFire"))
        assertTrue(report.contains("DESFire"))
        assertTrue(report.contains("Generation: EV1"))
        assertTrue(report.contains("Applications: 0x000000 · 0x123456"))
        assertTrue(report.contains("No dump file"))
    }

    @Test
    fun `desfire applications that were never read say so`() {
        val desfire = DesfireVersion(0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03, 1, 0)
        val report = TagReport.build(
            identity = identity(family = TagFamily.DESFIRE, notes = emptyList()),
            content = null,
            desfire = desfire,
            applications = null,
        )
        assertTrue(report.contains("Applications: not read"))
    }

    @Test
    fun `ultralight report shows the capability container, ndef and counters`() {
        val content = UltralightContent(
            uidHex = "04A2B305060708",
            pages = 45,
            cc = CapabilityContainer(
                versionMajor = 1,
                versionMinor = 0,
                capacityBytes = 144,
                readAccess = 0,
                writeAccess = 0,
            ),
            ndef = NdefContent.absent,
            counters = listOf(
                MfuCounter(5, false),
                MfuCounter(null, false),
                MfuCounter(7, true),
            ),
        )
        val report = TagReport.build(
            identity = identity(
                family = TagFamily.NTAG,
                label = "NTAG213",
                notes = emptyList(),
                techs = listOf(NfcTech.NFC_A, NfcTech.MIFARE_ULTRALIGHT),
            ),
            content = TagContent(ultralight = content),
        )
        assertTrue(report.contains("Type 2 memory"))
        assertTrue(report.contains("Pages: 45"))
        assertTrue(report.contains("version 1.0, 144 bytes, read 0x0, write 0x0"))
        assertTrue(report.contains("No NDEF message"))
        assertTrue(report.contains("Counter 1: 5"))
        assertTrue(report.contains("Counter 2: not supported"))
        assertTrue(report.contains("Counter 3: 7 (tearing)"))
    }

    @Test
    fun `truncated and invalid ndef are named`() {
        val truncated = TagReport.build(
            identity = identity(),
            content = TagContent(
                classic = classicContent().let {
                    ClassicContent(
                        it.uidHex,
                        it.sizeLabel,
                        it.sectors,
                        it.mad,
                        NdefContent(emptyList(), 4, NdefStatus.TRUNCATED),
                        it.readableSectors,
                        it.totalSectors,
                    )
                },
            ),
        )
        assertTrue(truncated.contains("NDEF data is truncated"))

        val invalid = TagReport.build(
            identity = identity(),
            content = TagContent(
                classic = classicContent().let {
                    ClassicContent(
                        it.uidHex,
                        it.sizeLabel,
                        it.sectors,
                        it.mad,
                        NdefContent(emptyList(), 0, NdefStatus.INVALID),
                        it.readableSectors,
                        it.totalSectors,
                    )
                },
            ),
        )
        assertTrue(invalid.contains("NDEF data is not valid"))
    }

    @Test
    fun `record text is quoted and escaped`() {
        val content = classicContent().let {
            ClassicContent(
                it.uidHex,
                it.sizeLabel,
                it.sectors,
                it.mad,
                NdefContent(
                    listOf(NdefRecord(NdefKind.TEXT, "T", "a\n\"b\"", 4)),
                    5,
                    NdefStatus.FOUND,
                ),
                it.readableSectors,
                it.totalSectors,
            )
        }
        val report = TagReport.build(identity(), TagContent(classic = content))
        assertTrue(report.contains("""TEXT "a\n\"b\"" (4 bytes)"""))
    }

    @Test
    fun `base name follows the dump and the family`() {
        val classic = identity()
        assertEquals(
            "hf-mf-04112233",
            TagReport.baseName(classic, "hf-mf-04112233-dump.bin"),
        )
        assertEquals("hf-mf-04112233", TagReport.baseName(classic))
        assertEquals(
            "hf-mfu-04112233",
            TagReport.baseName(identity(family = TagFamily.NTAG)),
        )
        assertEquals(
            "tag-04112233",
            TagReport.baseName(identity(family = TagFamily.DESFIRE)),
        )
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("a_b", TagReport.sanitize("a/b"))
        assertEquals("tag", TagReport.sanitize("   "))
        assertEquals("tag", TagReport.sanitize(".hidden"))
        assertTrue(TagReport.sanitize("x".repeat(200)).length <= 80)
    }
}
