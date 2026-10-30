// Parsers and probes added for the HF expansion: ISO 7816 framing, ISO 15693
// system information, NDEF Type 4 and the registry chain. Everything runs on
// the JVM with fake transports, like the existing DESFire and Calypso tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import com.farmacia.pm3android.nfc.inspect.content.NdefKind
import com.farmacia.pm3android.nfc.inspect.content.NdefStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HfProbeTest {

    @Test
    fun `apdu parser ignores spaces and comments`() {
        val parsed = Iso7816.parseApdu("00 A4 04 00 07 D2 76 00 00 85 01 01 00 # select")
        assertEquals(13, parsed.size)
        assertEquals(0xA4, parsed[1].toInt() and 0xFF)
    }

    @Test
    fun `status word description covers the common cases`() {
        assertEquals("OK", Iso7816.swDescription(0x9000))
        assertEquals("File or application not found", Iso7816.swDescription(0x6A82))
        assertEquals("Wrong Le, use 16", Iso7816.swDescription(0x6C10))
    }

    @Test
    fun `iso15693 system info decodes memory geometry`() {
        val uid = ByteArray(8) { (0xE0 - it).toByte() }
        val response = byteArrayOf(
            0x00,
            0x0F,
        ) + uid + byteArrayOf(
            0x00,
            0x00,
            0x03,
            0x03,
            0x01,
        )
        val info = Iso15693.parseSystemInfo(response)
        assertNotNull(info)
        assertEquals(4, info?.blockCount)
        assertEquals(4, info?.blockSize)
        assertEquals(16, info?.memoryBytes)
        assertEquals(1, info?.icReference)
    }

    @Test
    fun `ndef type 4 reads a text record`() {
        val transport = FakeIsoDep(ndefResponses())
        val content = NdefType4.read(transport)
        assertNotNull(content)
        val ndef = content!!.ndef
        assertEquals(NdefStatus.FOUND, ndef.status)
        assertEquals(1, ndef.records.size)
        assertEquals(NdefKind.TEXT, ndef.records[0].kind)
        assertEquals("hi", ndef.records[0].text)
    }

    @Test
    fun `registry names an NXP ISO 15693 label`() {
        // Android hands the UID little endian: reverse it to get E0 04 ...
        val tagId = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0xE0.toByte())
        val snapshot = TagSnapshot(uid = tagId, techs = listOf(NfcTech.NFC_V))
        val session = FakeSession(nfcV = FakeNfcV())
        val log = CommandLog()
        val result = HfProbeRegistry.run(
            ProbeContext(snapshot = snapshot, session = session, log = log),
        )

        assertEquals(TagFamily.ICODE, result.identity.family)
        assertEquals("NXP ICODE (ISO 15693)", result.identity.label)
        assertEquals(16, result.identity.memoryBytes)
        assertTrue(result.details is Iso15693Details)
    }

    @Test
    fun `type 5 ndef starts right after the capability container`() {
        val tagId = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0xE0.toByte())
        val snapshot = TagSnapshot(uid = tagId, techs = listOf(NfcTech.NFC_V))
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = snapshot,
                session = FakeSession(nfcV = FakeNfcV(ndef = true)),
                log = CommandLog(),
            ),
        )

        val ndef = result.content?.ndef
        assertNotNull(ndef)
        assertEquals(NdefStatus.FOUND, ndef!!.status)
        assertEquals("hi", ndef.records.single().text)
    }

    @Test
    fun `felica type 3 ndef is read from the ndef service`() {
        val idm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val snapshot = TagSnapshot(uid = idm, techs = listOf(NfcTech.NFC_F))
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = snapshot,
                session = FakeSession(nfcF = FakeNfcF(idm)),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.FELICA, result.identity.family)
        assertTrue(result.identity.notes.contains(TagNote.FELICA_NO_P2P))
        val ndef = result.content?.ndef
        assertNotNull(ndef)
        assertEquals(NdefStatus.FOUND, ndef!!.status)
        assertEquals("hi", ndef.records.single().text)
    }

    @Test
    fun `emrtd probe reports pace and bac from card access`() {
        val snapshot = TagSnapshot(
            uid = ByteArray(4) { it.toByte() },
            techs = listOf(NfcTech.NFC_A, NfcTech.ISO_DEP),
        )
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = snapshot,
                session = FakeSession(isoDep = FakeEmrtd()),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.EMRTD, result.identity.family)
        assertTrue(result.identity.notes.contains(TagNote.EMRTD_PACE))
        assertTrue(result.identity.notes.contains(TagNote.EMRTD_NEEDS_MRZ))
        val details = result.details as EmrtdDetails
        assertTrue(details.paceSupported)
        assertTrue(details.bacSupported)
    }

    @Test
    fun `page memory read stops at the first refused chunk`() {
        val memory = PageMemoryReader.read(FakePageTag(blocks = 16))

        assertEquals(16, memory.blockCount)
        assertEquals(64, memory.sizeBytes)
        assertTrue(memory.endReached)
        assertEquals(0x05, memory.pages[0][0].toInt() and 0xFF)
        assertEquals("05 11 22 33", memory.rows[0].hex)
    }

    @Test
    fun `my-d move lean is named from its sixteen block memory`() {
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = myDSnapshot(),
                session = FakeSession(nfcA = FakePageTag(blocks = 16)),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.MY_D, result.identity.family)
        assertEquals("Infineon my-d move lean (SLE66R01L)", result.identity.label)
        assertEquals(64, result.identity.memoryBytes)
        assertTrue(result.identity.notes.contains(TagNote.MY_D_VARIANT_INFERRED))
        val details = result.details as PageMemoryDetails
        assertEquals(16, details.blockCount)
        assertEquals(4, details.pages[0].size)
        val ndef = result.content?.ndef
        assertNotNull(ndef)
        assertEquals(NdefStatus.FOUND, ndef!!.status)
        assertEquals("hi", ndef.records.single().text)
    }

    @Test
    fun `my-d move full memory is named SLE66R01P`() {
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = myDSnapshot(),
                session = FakeSession(nfcA = FakePageTag(blocks = 38)),
                log = CommandLog(),
            ),
        )

        assertEquals("Infineon my-d move (SLE66R01P)", result.identity.label)
        // RD4B answers four blocks at a time, so the 38-block part yields the
        // last complete group at block 36: 36 blocks, 144 bytes.
        assertEquals(36 * 4, result.identity.memoryBytes)
        assertEquals(36, (result.details as PageMemoryDetails).blockCount)
    }

    @Test
    fun `atqa helper reads the little endian byte order`() {
        assertEquals(0x0044, Atqa.value(byteArrayOf(0x44, 0x00)))
        assertEquals(0x0004, Atqa.value(byteArrayOf(0x04, 0x00)))
        assertNull(Atqa.value(byteArrayOf(0x44)))
    }

    @Test
    fun `my-d is recognised when the controller reports atqa 0x4400`() {
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = myDSnapshot(atqa = 0x4400),
                session = FakeSession(nfcA = FakePageTag(blocks = 16)),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.MY_D, result.identity.family)
        assertEquals("Infineon my-d move lean (SLE66R01L)", result.identity.label)
    }

    @Test
    fun `my-d is recognised from the memory when the atqa is missing`() {
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = myDSnapshot(atqa = null),
                session = FakeSession(nfcA = FakePageTag(blocks = 16)),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.MY_D, result.identity.family)
        assertTrue(result.identity.dumpable)
    }

    @Test
    fun `classic clone that refuses the page command keeps its identity`() {
        val snapshot = TagSnapshot(
            uid = byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            techs = listOf(NfcTech.NFC_A),
            atqa = 0x0004,
            sak = 0x08,
        )
        val result = HfProbeRegistry.run(
            ProbeContext(
                snapshot = snapshot,
                session = FakeSession(nfcA = FakePageTag(blocks = 0)),
                log = CommandLog(),
            ),
        )

        assertEquals(TagFamily.MIFARE_CLASSIC, result.identity.family)
        assertEquals("MIFARE Classic 1K", result.identity.label)
        assertTrue(result.identity.notes.contains(TagNote.CLASSIC_WITHOUT_TECH))
        assertNull(result.details)
    }

    private fun myDSnapshot(atqa: Int? = 0x0044) = TagSnapshot(
        uid = byteArrayOf(0x05, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
        techs = listOf(NfcTech.NFC_A),
        atqa = atqa,
        sak = 0x00,
    )

    /**
     * A Type 2 tag: pages 0-2 identification, page 3 the capability container
     * and page 4 the NDEF TLV with the text record "hi".
     */
    private class FakePageTag(private val blocks: Int) : RawTagTransport {
        override fun transceive(data: ByteArray): ByteArray {
            val command = data.getOrNull(0)?.toInt()?.and(0xFF)
            val start = data.getOrNull(1)?.toInt()?.and(0xFF)
            // A four-block window that would run past the end is refused, which
            // is how a real 38-block part ends a read at block 36.
            if (command != PageMemoryReader.CMD_READ_4_BLOCKS ||
                start == null ||
                start + 4 > blocks
            ) {
                throw IOException("NAK")
            }
            return ByteArray(16) { offset ->
                val page = start + offset / 4
                pageBytes(page)[offset % 4]
            }
        }

        private fun pageBytes(page: Int): ByteArray = when (page) {
            0 -> byteArrayOf(0x05, 0x11, 0x22, 0x33)
            1 -> byteArrayOf(0x44, 0x55, 0x66, 0x77)
            2 -> byteArrayOf(0x00, 0x00, 0x00, 0x00)
            3 -> byteArrayOf(0xE1.toByte(), 0x10, 0x0C, 0x00)
            4 -> byteArrayOf(0x03, 0x09, 0xD1.toByte(), 0x01)
            5 -> byteArrayOf(0x05, 0x54, 0x02, 0x65)
            6 -> byteArrayOf(0x6E, 0x68, 0x69, 0x00)
            else -> ByteArray(4)
        }
    }

    private fun ndefResponses(): Map<String, ByteArray> {
        // Standard 15-byte Type 4 capability container: CCLEN, version, MLe,
        // MLc, TL 0x04, L 0x06, NDEF file id E104, max size 0x0020, R/W.
        val cc = byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, 0x3B,
            0x00, 0x34,
            0x04,
            0x06,
            0xE1.toByte(), 0x04,
            0x00, 0x20,
            0x00,
            0x00,
        )
        // Text record: "hi", language "en".
        val message = byteArrayOf(
            0xD1.toByte(), 0x01, 0x05, 0x54,
            0x02, 0x65, 0x6E, 0x68, 0x69,
        )
        return mapOf(
            "00A4040007D276000085010100" to byteArrayOf(0x90.toByte(), 0x00),
            "00A4000C02E103" to byteArrayOf(0x90.toByte(), 0x00),
            "00B000000F" to cc + byteArrayOf(0x90.toByte(), 0x00),
            "00A4000C02E104" to byteArrayOf(0x90.toByte(), 0x00),
            "00B0000002" to byteArrayOf(0x00, 0x09, 0x90.toByte(), 0x00),
            "00B0000209" to message + byteArrayOf(0x90.toByte(), 0x00),
        )
    }

    private class FakeIsoDep(
        private val responses: Map<String, ByteArray>,
    ) : IsoDepTransport {
        override fun transceive(command: ByteArray): ByteArray {
            val key = command.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            return responses[key] ?: byteArrayOf(0x6A.toByte(), 0x82.toByte())
        }
    }

    private class FakeEmrtd : IsoDepTransport {
        override fun transceive(command: ByteArray): ByteArray {
            val key = command.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            return when (key) {
                "00A4040C07A000000247100100" -> OK
                "00A4020C02011C" -> OK
                "00B0000040" -> CARD_ACCESS + OK
                "0084000008" -> ByteArray(8) + OK
                else -> byteArrayOf(0x6A, 0x82.toByte())
            }
        }

        private companion object {
            val OK = byteArrayOf(0x90.toByte(), 0x00)

            /** SEQUENCE { OBJECT IDENTIFIER id-PACE } */
            val CARD_ACCESS = byteArrayOf(
                0x30, 0x0A,
                0x06, 0x08,
                0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04,
            )
        }
    }

    private class FakeNfcV(private val ndef: Boolean = false) : NfcVTransport {
        override fun transceive(command: ByteArray): ByteArray {
            val opcode = command.getOrNull(1)?.toInt()?.and(0xFF)
            return when (opcode) {
                Iso15693.CMD_GET_SYSTEM_INFO -> byteArrayOf(
                    0x00,
                    0x0F,
                    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0xE0.toByte(),
                    0x00,
                    0x00,
                    0x03,
                    0x03,
                    0x01,
                )

                Iso15693.CMD_READ_SINGLE_BLOCK -> block(command.getOrNull(2)?.toInt()?.and(0xFF))

                else -> byteArrayOf(0x01)
            }
        }

        private fun block(index: Int?): ByteArray = when {
            index == 0 && ndef -> byteArrayOf(
                0x00,
                0xE1.toByte(), 0x10, 0x12, 0x00,
            )

            index == 0 -> byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
            !ndef -> byteArrayOf(0x00, 0x03, 0x00, 0xFE.toByte(), 0x00)
            // NDEF TLV 03 09 followed by "hi" as a text record.
            index == 1 -> byteArrayOf(0x00, 0x03, 0x09, 0xD1.toByte(), 0x01)
            index == 2 -> byteArrayOf(0x00, 0x05, 0x54, 0x02, 0x65)
            else -> byteArrayOf(0x00, 0x6E, 0x68, 0x69, 0x00)
        }
    }

    private class FakeNfcF(private val idm: ByteArray) : NfcFTransport {
        override fun transceive(command: ByteArray): ByteArray = when (command.getOrNull(1)?.toInt()?.and(0xFF)) {
            // Request Response: PMm follows the IDm.
            0x04 -> byteArrayOf(0x12, 0x05) + idm + ByteArray(8) { it.toByte() }

            // Request System Code: one system code 0x0001.
            0x0C -> byteArrayOf(0x0D, 0x0D) + idm + byteArrayOf(0x01, 0x00, 0x01)

            // Request Service: the NDEF service 0x000B exists.
            0x02 -> byteArrayOf(0x0D, 0x03) + idm + byteArrayOf(0x01, 0x00, 0x0B)

            // Read Without Encryption: status 00 00, one block of NDEF TLV.
            0x06 -> byteArrayOf(0x1D, 0x07) + idm + byteArrayOf(
                0x00, 0x00, 0x01,
                0x03, 0x09, 0xD1.toByte(), 0x01, 0x05, 0x54, 0x02, 0x65,
                0x6E, 0x68, 0x69, 0x00, 0x00, 0x00, 0x00, 0x00,
            )

            else -> byteArrayOf(0x01)
        }
    }

    private class FakeSession(
        private val nfcV: NfcVTransport? = null,
        private val nfcF: NfcFTransport? = null,
        private val isoDep: IsoDepTransport? = null,
        private val nfcA: RawTagTransport? = null,
    ) : TagSession {
        override fun <T> withIsoDep(block: (IsoDepTransport) -> T): T? =
            isoDep?.let { block(it) }

        override fun <T> withNfcA(block: (RawTagTransport) -> T): T? = nfcA?.let { block(it) }

        override fun <T> withNfcV(block: (NfcVTransport) -> T): T? =
            nfcV?.let { block(it) }

        override fun <T> withNfcF(block: (NfcFTransport) -> T): T? =
            nfcF?.let { block(it) }

        override fun <T> withNfcB(block: (NfcBTransport) -> T): T? = null
    }
}
