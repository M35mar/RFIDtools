// Calypso parsing and transport: BER-TLV FCI, ICC record, CLA fallback and the
// AID prefix table. No phone needed: the APDUs are answered by a fake.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalypsoClientTest {

    @Test
    fun `fci exposes df name serial and startup`() {
        val dfName = hex("A00000040401250901")
        val serial = hex("0102030405060708")
        val startup = byteArrayOf(0x00, 0x02, 0x10, 0x01, 0x02, 0x03, 0x04)

        val parsed = CalypsoFci.parse(fci(dfName, serial, startup))

        assertArrayEquals(dfName, parsed.dfName)
        assertArrayEquals(serial, parsed.serial)
        assertNotNull(parsed.startup)
        assertEquals(0x02, parsed.startup!!.platform)
        assertEquals(0x10, parsed.startup.applicationType)
        assertEquals(0x01, parsed.startup.applicationSubtype)
        assertEquals(0x02, parsed.startup.softwareIssuer)
        assertEquals(0x03, parsed.startup.softwareVersion)
        assertEquals(0x04, parsed.startup.softwareRevision)
    }

    @Test
    fun `fci without the optional fields keeps the df name`() {
        val dfName = hex("A000000291D6200002")
        val parsed = CalypsoFci.parse(fci(dfName, ByteArray(0), ByteArray(0)))

        assertArrayEquals(dfName, parsed.dfName)
        assertNull(parsed.serial)
        assertNull(parsed.startup)
    }

    @Test
    fun `icc record is decoded`() {
        val icc = iccRecord()
        val parsed = CalypsoIccParser.parse(icc)!!

        assertArrayEquals(icc.copyOfRange(0x0C, 0x14), parsed.serial)
        assertEquals(250, parsed.countryCode)
        assertEquals(0x04, parsed.manufacturer)
        assertEquals(1990, parsed.initializationDate?.year)
        assertEquals(1, parsed.initializationDate?.monthValue)
        assertEquals(11, parsed.initializationDate?.dayOfMonth)
        assertEquals(checkValue(icc, 0x10, 0x1B), parsed.check)
        assertTrue(parsed.checkValid == true)
    }

    @Test
    fun `empty or short icc records are rejected`() {
        assertNull(CalypsoIccParser.parse(ByteArray(10)))
        assertNull(CalypsoIccParser.parse(ByteArray(29)))
    }

    @Test
    fun `ber tlv reads nested, long length and constructed values`() {
        val nested = fci(hex("A00000040401250901"), hex("0102030405060708"), ByteArray(7))
        assertArrayEquals(hex("A00000040401250901"), BerTlv.find(nested, 0x84))

        val longValue = ByteArray(200) { 0x42 }
        val longTlv = byteArrayOf(0x84.toByte(), 0x81.toByte(), 0xC8.toByte()) + longValue
        assertArrayEquals(longValue, BerTlv.find(longTlv, 0x84))
    }

    @Test
    fun `select retries with the other class byte`() {
        val fci = fci(hex("A00000040401250901"), hex("0102030405060708"), ByteArray(7))
        val transport = FakeTransport(
            frame(ByteArray(0), 0x6E00),
            frame(fci, 0x9000),
        )

        val selection = CalypsoClient(transport, iso14443B = false)
            .select(hex("A000000404"))

        assertTrue(selection.selected)
        assertEquals(0x94, selection.cla)
        // First frame: ISO 14443-A class with Le, second: ISO 14443-B class, no Le.
        assertEquals(0x00, transport.sent[0][0].toInt() and 0xFF)
        assertEquals(0x94, transport.sent[1][0].toInt() and 0xFF)
        assertEquals(4 + 1 + 5 + 1, transport.sent[0].size)
        assertEquals(4 + 1 + 5, transport.sent[1].size)
    }

    @Test
    fun `select builds the apdu with the aid as lc data`() {
        val transport = FakeTransport(frame(ByteArray(0), 0x9000))
        val aid = hex("315449432E494341")

        CalypsoClient(transport).select(aid)

        val sent = transport.sent.single()
        assertEquals(0x00, sent[0].toInt() and 0xFF)
        assertEquals(0xA4, sent[1].toInt() and 0xFF)
        assertEquals(0x04, sent[2].toInt() and 0xFF)
        assertEquals(aid.size, sent[4].toInt() and 0xFF)
        assertArrayEquals(aid, sent.copyOfRange(5, 5 + aid.size))
    }

    @Test
    fun `probe selects the first aid and reads the icc`() {
        val dfName = hex("A00000040401250901")
        val icc = iccRecord()
        val environment = environmentRecord()
        val transport = FakeTransport(
            frame(fci(dfName, icc.copyOfRange(0x0C, 0x14), ByteArray(7)), 0x9000),
            frame(icc, 0x9000),
            frame(environment, 0x9000),
            frame(ByteArray(0), 0x6A82),
            frame(ByteArray(0), 0x6A82),
            frame(ByteArray(0), 0x6A82),
            frame(ByteArray(0), 0x6A82),
            frame(ByteArray(0), 0x6A82),
        )

        val details = CalypsoProbe.read(CalypsoClient(transport))!!

        assertArrayEquals(dfName, details.dfName)
        assertEquals("Navigo", details.label)
        assertArrayEquals(icc.copyOfRange(0x0C, 0x14), details.serial)
        assertNotNull(details.icc)
        assertEquals(0x00, details.cla)
        assertFalse(details.iso14443B)
        assertNotNull(details.environment)
        assertEquals(2, details.environment?.version)
        assertEquals(0x000250, details.environment?.networkId)
        assertTrue(details.files.isEmpty())
    }

    @Test
    fun `environment record decodes version and network`() {
        val raw = environmentRecord()
        val environment = CalypsoEnvironmentParser.parse(raw)!!

        assertEquals(2, environment.version)
        assertEquals(0x000250, environment.networkId)
        assertArrayEquals(raw, environment.raw)
    }

    @Test
    fun `an empty environment record is ignored`() {
        assertNull(CalypsoEnvironmentParser.parse(ByteArray(0)))
        assertNull(CalypsoEnvironmentParser.parse(ByteArray(4)))
    }

    @Test
    fun `aid table recognises calypso prefixes`() {
        assertTrue(AidCatalog.isCalypso(hex("A00000040401250901")))
        assertTrue(AidCatalog.isCalypso(hex("A000000291D6200002")))
        assertTrue(AidCatalog.isCalypso("1TIC.ICA".toByteArray()))
        assertFalse(AidCatalog.isCalypso(hex("A0000000031010")))

        assertEquals("Navigo", AidCatalog.calypsoLabel(hex("A00000040401250901")))
        assertNull(AidCatalog.calypsoLabel(hex("A0000004049999")))

        val candidates = AidCatalog.calypsoCandidates()
        assertArrayEquals(hex(AidCatalog.CALYPSO_RID_SETRA), candidates.first())
        assertTrue(candidates.size >= 5)
    }

    @Test
    fun `free files stop at the first refused record`() {
        val contracts1 = hex("010203040506")
        val contracts2 = hex("0A0B0C0D0E0F")
        val counters = hex("0102030A0B0C")
        val transport = MapTransport(
            fci = fci(hex("A00000040401250901"), hex("0102030405060708"), ByteArray(7)),
            icc = iccRecord(),
            environment = environmentRecord(),
            files = mapOf(
                0x08 to listOf(hex("AA"), null),
                0x09 to listOf(contracts1, contracts2, null),
                0x19 to listOf(counters),
                0x1D to listOf(hex("BB"), null),
                0x1E to listOf(hex("CC")),
            ),
        )

        val details = CalypsoProbe.read(CalypsoClient(transport))!!
        val files = details.files.associateBy { it.kind }

        assertEquals(5, files.size)
        assertEquals(1, files[CalypsoFileKind.EVENTS_LOG]?.records?.size)
        assertEquals(2, files[CalypsoFileKind.CONTRACTS]?.records?.size)
        assertEquals(
            listOf(0x010203, 0x0A0B0C),
            CalypsoFileParser.counterValues(
                files[CalypsoFileKind.COUNTERS]!!.records.first(),
            ),
        )
        assertEquals(1, files[CalypsoFileKind.SPECIAL_EVENTS]?.records?.size)
        assertEquals(1, files[CalypsoFileKind.CONTRACT_LIST]?.records?.size)
    }

    @Test
    fun `counters are 24 bit big endian`() {
        assertEquals(
            listOf(0x010203, 0x0A0B0C),
            CalypsoFileParser.counterValues(hex("0102030A0B0C")),
        )
        assertTrue(CalypsoFileParser.counterValues(hex("0102")).isEmpty())
    }

    private class MapTransport(
        private val fci: ByteArray,
        private val icc: ByteArray,
        private val environment: ByteArray,
        private val files: Map<Int, List<ByteArray?>>,
    ) : IsoDepTransport {

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            return when (ins) {
                0xA4 -> frame(fci, 0x9000)
                0xB2 -> {
                    val record = command[2].toInt() and 0xFF
                    val sfi = (command[3].toInt() and 0xFF) shr 3
                    val data = when (sfi) {
                        0x02 -> if (record == 1) icc else null
                        0x07 -> if (record == 1) environment else null
                        else -> files[sfi]?.getOrNull(record - 1)
                    }
                    if (data == null) frame(ByteArray(0), 0x6A83) else frame(data, 0x9000)
                }

                else -> frame(ByteArray(0), 0x6D00)
            }
        }
    }

    private class FakeTransport(vararg answers: ByteArray) : IsoDepTransport {

        private val answers = answers.toList()
        val sent = mutableListOf<ByteArray>()
        private var index = 0

        override fun transceive(command: ByteArray): ByteArray {
            sent += command.copyOf()
            check(index < answers.size) { "no more fake answers" }
            return answers[index++]
        }
    }

    private companion object {

        fun hex(text: String): ByteArray = text.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        fun tlv(tag: Int, value: ByteArray): ByteArray =
            byteArrayOf(tag.toByte(), value.size.toByte()) + value

        fun fci(dfName: ByteArray, serial: ByteArray, startup: ByteArray): ByteArray {
            val inner = buildList {
                add(tlv(0x84, dfName))
                if (serial.isNotEmpty()) add(tlv(0xC7, serial))
                if (startup.isNotEmpty()) add(tlv(0x53, startup))
            }.fold(ByteArray(0)) { acc, part -> acc + part }
            return tlv(0x6F, tlv(0xA5, inner))
        }

        fun frame(data: ByteArray, status: Int): ByteArray {
            val out = ByteArray(data.size + 2)
            data.copyInto(out)
            out[data.size] = (status shr 8).toByte()
            out[data.size + 1] = status.toByte()
            return out
        }

        fun iccRecord(): ByteArray {
            val record = ByteArray(29)
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).copyInto(record, 0x0C)
            record[0x14] = 0x02
            record[0x15] = 0x50
            record[0x16] = 0x04
            record[0x19] = 0x00
            record[0x1A] = 0x0A
            record[0x1B] = checkValue(record, 0x10, 0x1B).toByte()
            return record
        }

        fun environmentRecord(): ByteArray {
            val raw = ByteArray(5)
            setBits(raw, 0, 6, 2)
            setBits(raw, 13, 24, 0x000250)
            return raw
        }

        fun setBits(data: ByteArray, offset: Int, length: Int, value: Int) {
            for (index in 0 until length) {
                val bit = (value shr (length - 1 - index)) and 0x01
                val position = offset + index
                val mask = 1 shl (7 - position % 8)
                val byteIndex = position / 8
                data[byteIndex] = if (bit == 1) {
                    (data[byteIndex].toInt() or mask).toByte()
                } else {
                    (data[byteIndex].toInt() and mask.inv()).toByte()
                }
            }
        }

        fun checkValue(data: ByteArray, from: Int, to: Int): Int {
            var zeroBits = 0
            for (index in from until to) {
                zeroBits += 8 - Integer.bitCount(data[index].toInt() and 0xFF)
            }
            return 5 + zeroBits
        }
    }
}
