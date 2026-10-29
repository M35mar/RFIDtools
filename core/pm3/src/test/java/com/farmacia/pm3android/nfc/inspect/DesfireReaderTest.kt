// Authenticated DESFire read: a fake card implements the secure channel and
// checks that plain and MACed files come back with the expected content.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesfireReaderTest {

    private val key = hex("2B7E151628AED2A6ABF7158809CF4F3C")
    private val rndB = hex("16151413121110090807060504030201")

    private fun details(communication: Int) = DesfireDetails(
        version = DesfireVersion(0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03, 1, 0),
        applications = listOf(
            DesfireApplication(
                id = 0x123456,
                files = listOf(
                    DesfireFile(
                        id = 1,
                        type = DesfireFileType.STANDARD_DATA,
                        communication = communication,
                        accessRights = 0x0000,
                        sizeBytes = 16,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `plain file is read and the response mac is verified`() {
        val card = FakeCard(key, rndB, expected = ByteArray(16) { it.toByte() })

        val result = DesfireReader.read(
            DesfireClient(card),
            details(communication = 0),
            keyNo = 0,
            key = key,
        )

        val file = result.applications?.single()?.files?.single()!!
        assertArrayEquals(ByteArray(16) { it.toByte() }, file.data)
        assertTrue(card.authenticated)
    }

    @Test
    fun `maced file sends and verifies both macs`() {
        val content = ByteArray(16) { (0x40 + it).toByte() }
        val card = FakeCard(key, rndB, expected = content)

        val result = DesfireReader.read(
            DesfireClient(card),
            details(communication = 1),
            keyNo = 0,
            key = key,
        )

        val file = result.applications?.single()?.files?.single()!!
        assertArrayEquals(content, file.data)
        assertEquals(1, card.reads)
    }

    @Test
    fun `a refused key marks the application locked and keeps the metadata`() {
        val card = FakeCard(key, rndB, expected = ByteArray(16), refuseAuth = true)

        val result = DesfireReader.read(
            DesfireClient(card),
            details(communication = 0),
            keyNo = 0,
            key = key,
        )

        val application = result.applications?.single()!!
        assertTrue(application.locked)
        assertNull(application.files?.single()?.data)
    }

    @Test
    fun `a wrong response mac is refused`() {
        val card = FakeCard(key, rndB, expected = ByteArray(16), corruptMac = true)

        val result = DesfireReader.read(
            DesfireClient(card),
            details(communication = 0),
            keyNo = 0,
            key = key,
        )

        val application = result.applications?.single()!!
        assertTrue(application.locked)
        assertNull(application.files?.single()?.data)
    }

    private class FakeCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
        private val expected: ByteArray,
        private val refuseAuth: Boolean = false,
        private val corruptMac: Boolean = false,
    ) : IsoDepTransport {

        var authenticated = false
            private set

        var reads = 0
            private set

        private var sessionKey: ByteArray? = null
        private var iv = ByteArray(16)
        private lateinit var encRndB: ByteArray

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            val lc = command[4].toInt() and 0xFF
            val data = command.copyOfRange(5, 5 + lc)
            return when (ins) {
                0x5A -> frame(ByteArray(0), 0x9100)

                0xAA -> {
                    if (refuseAuth) {
                        frame(ByteArray(0), 0x91AE)
                    } else {
                        encRndB = DesfireCrypto.aesEncryptCbc(key, ByteArray(16), rndB)
                        frame(encRndB, 0x91AF)
                    }
                }

                0xAF -> {
                    val plain = DesfireCrypto.aesDecryptCbc(key, encRndB, data)
                    val rndA = plain.copyOfRange(0, 16)
                    val returned = plain.copyOfRange(16, 32)
                    check(returned.contentEquals(DesfireCrypto.rotateLeft(rndB)))
                    val answerIv = data.copyOfRange(16, 32)
                    val answer = DesfireCrypto.aesEncryptCbc(
                        key,
                        answerIv,
                        DesfireCrypto.rotateLeft(rndA),
                    )
                    sessionKey = rndA.copyOfRange(0, 4) +
                        rndB.copyOfRange(0, 4) +
                        rndA.copyOfRange(12, 16) +
                        rndB.copyOfRange(12, 16)
                    iv = ByteArray(16)
                    authenticated = true
                    frame(answer, 0x9100)
                }

                0xBD -> {
                    reads++
                    val effective = data.copyOfRange(0, minOf(7, data.size))
                    val commandMac = DesfireCrypto.cmac(
                        sessionKey!!,
                        iv,
                        byteArrayOf(0xBD.toByte()) + effective,
                    )
                    iv = commandMac.iv
                    if (data.size > 7) {
                        check(
                            commandMac.mac.copyOf(8)
                                .contentEquals(data.copyOfRange(7, data.size)),
                        )
                    }
                    val offset = (effective[1].toInt() and 0xFF) or
                        ((effective[2].toInt() and 0xFF) shl 8) or
                        ((effective[3].toInt() and 0xFF) shl 16)
                    val length = (effective[4].toInt() and 0xFF) or
                        ((effective[5].toInt() and 0xFF) shl 8) or
                        ((effective[6].toInt() and 0xFF) shl 16)
                    val body = expected.copyOfRange(offset, minOf(offset + length, expected.size))
                    val responseMac = DesfireCrypto.cmac(
                        sessionKey!!,
                        iv,
                        body + byteArrayOf(0x00),
                    )
                    iv = responseMac.iv
                    val mac = responseMac.mac.copyOf(8)
                    if (corruptMac) mac[0] = (mac[0].toInt() xor 0xFF).toByte()
                    frame(body + mac, 0x9100)
                }

                else -> frame(ByteArray(0), 0x9100)
            }
        }
    }

    private companion object {
        fun hex(text: String): ByteArray = text.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        fun frame(data: ByteArray, status: Int): ByteArray {
            val out = ByteArray(data.size + 2)
            data.copyInto(out)
            out[data.size] = (status shr 8).toByte()
            out[data.size + 1] = status.toByte()
            return out
        }
    }
}
