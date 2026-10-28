// Identification matrix: evidence order (GET_VERSION, tech list, SAK), the
// seven-byte-UID clone case and the families that cannot be dumped.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagIdentifierTest {

    private val fourByteUid = byteArrayOf(0x04, 0x11, 0x22, 0x33)
    private val sevenByteUid = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)

    @Test
    fun `classic 1k from the mifare tech`() {
        val identity = TagIdentifier.identify(
            TagSnapshot(
                uid = fourByteUid,
                techs = listOf(NfcTech.NFC_A, NfcTech.MIFARE_CLASSIC, NfcTech.NDEF),
                atqa = 0x0004,
                sak = 0x08,
            ),
        )

        assertEquals(TagFamily.MIFARE_CLASSIC, identity.family)
        assertEquals("MIFARE Classic 1K", identity.label)
        assertEquals(TagConfidence.HIGH, identity.confidence)
        assertEquals(1024, identity.memoryBytes)
        assertEquals("NXP (o clone)", identity.vendor)
        assertEquals(4, identity.uidLength)
        assertTrue(identity.dumpable)
        assertTrue(identity.notes.isEmpty())
    }

    @Test
    fun `plus level 1 is named from the sak`() {
        val identity = TagIdentifier.identify(
            TagSnapshot(
                uid = fourByteUid,
                techs = listOf(NfcTech.NFC_A, NfcTech.MIFARE_CLASSIC),
                sak = 0x88,
            ),
        )

        assertEquals(TagFamily.MIFARE_PLUS, identity.family)
        assertEquals("MIFARE Plus SL1 1K", identity.label)
        assertEquals(1024, identity.memoryBytes)
        assertTrue(identity.dumpable)
    }

    @Test
    fun `seven byte uid clone is named but not dumpable`() {
        val identity = TagIdentifier.identify(
            TagSnapshot(
                uid = sevenByteUid,
                techs = listOf(NfcTech.NFC_A),
                sak = 0x08,
            ),
        )

        assertEquals(TagFamily.MIFARE_CLASSIC, identity.family)
        assertEquals(TagConfidence.MEDIUM, identity.confidence)
        assertTrue(identity.notes.contains(TagNote.UID_7_BYTE))
        assertTrue(identity.notes.contains(TagNote.CLASSIC_WITHOUT_TECH))
        assertTrue(identity.notes.contains(TagNote.VERSION_UNAVAILABLE))
        assertFalse(identity.dumpable)
        assertEquals(7, identity.uidLength)
    }

    @Test
    fun `get version wins over the tech list`() {
        val identity = TagIdentifier.identify(
            TagSnapshot(
                uid = sevenByteUid,
                techs = listOf(NfcTech.NFC_A, NfcTech.NDEF),
                sak = 0x00,
                version = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03),
            ),
        )

        assertEquals(TagFamily.NTAG, identity.family)
        assertEquals("NTAG213", identity.label)
        assertEquals(TagConfidence.HIGH, identity.confidence)
        assertEquals(144, identity.memoryBytes)
        assertTrue(identity.dumpable)
        assertTrue(identity.notes.contains(TagNote.UID_7_BYTE))
    }

    @Test
    fun `desfire is identified from iso dep and cannot be dumped`() {
        val identity = TagIdentifier.identify(
            TagSnapshot(
                uid = sevenByteUid,
                techs = listOf(NfcTech.NFC_A, NfcTech.ISO_DEP),
                sak = 0x20,
            ),
        )

        assertEquals(TagFamily.ISO_DEP, identity.family)
        assertEquals(TagConfidence.MEDIUM, identity.confidence)
        assertTrue(identity.notes.contains(TagNote.VERSION_UNAVAILABLE))
        assertFalse(identity.dumpable)
    }

    @Test
    fun `iso15693 and felica are named from their tech`() {
        val vic = TagIdentifier.identify(
            TagSnapshot(uid = ByteArray(8), techs = listOf(NfcTech.NFC_V)),
        )
        val felica = TagIdentifier.identify(
            TagSnapshot(uid = ByteArray(8), techs = listOf(NfcTech.NFC_F)),
        )

        assertEquals(TagFamily.ISO_15693, vic.family)
        assertEquals(TagFamily.FELICA, felica.family)
        assertFalse(vic.dumpable)
        assertFalse(felica.dumpable)
    }

    @Test
    fun `desfire refinement keeps the uid and adds the note`() {
        val base = TagIdentifier.identify(
            TagSnapshot(
                uid = sevenByteUid,
                techs = listOf(NfcTech.NFC_A, NfcTech.ISO_DEP),
                sak = 0x20,
            ),
        )
        val version = DesfireVersion(
            hardwareVendor = 0x04,
            hardwareType = 0x01,
            hardwareSubtype = 0x02,
            hardwareMajor = 1,
            hardwareMinor = 3,
            storageCode = 0x19,
            protocolCode = 0x05,
            softwareMajor = 2,
            softwareMinor = 0,
        )

        val refined = TagIdentifier.refineWithDesfire(base, version)

        assertEquals(TagFamily.DESFIRE, refined.family)
        assertEquals("MIFARE DESFire EV2 4 KB", refined.label)
        assertEquals(TagConfidence.HIGH, refined.confidence)
        assertEquals("NXP", refined.vendor)
        assertEquals(4096, refined.memoryBytes)
        assertEquals(base.uidHex, refined.uidHex)
        assertTrue(refined.notes.contains(TagNote.DESFIRE_NO_DUMP))
        assertFalse(refined.notes.contains(TagNote.VERSION_UNAVAILABLE))
        assertFalse(refined.dumpable)
    }

    @Test
    fun `unknown tag stays unknown`() {
        val identity = TagIdentifier.identify(TagSnapshot(uid = fourByteUid))

        assertEquals(TagFamily.UNKNOWN, identity.family)
        assertEquals(TagConfidence.LOW, identity.confidence)
        assertNull(identity.memoryBytes)
        assertFalse(identity.notes.contains(TagNote.UID_7_BYTE))
    }
}
