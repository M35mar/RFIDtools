// MIFARE Plus SL3: the AES handshake, the Kenc/Kmac derivation and an encrypted
// sector read. The fake card implements the other side of the protocol.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlusSessionTest {

    private val key = hex("2B7E151628AED2A6ABF7158809CF4F3C")
    private val rndB = hex("16151413121110090807060504030201")
    private val rndA = hex("01020304050607080910111213141516")

    @Test
    fun `handshake derives the keys and reads four encrypted blocks`() {
        val card = FakeCard(key, rndB)
        val session = PlusSession.authenticate(card, 0x4000, key) { rndA }

        val blocks = session.readBlocks(card, firstBlock = 0, blockCount = 4)

        assertEquals(4, blocks.size)
        assertArrayEquals(ByteArray(16) { it.toByte() }, blocks[0])
        assertArrayEquals(ByteArray(16) { (16 + it).toByte() }, blocks[1])
        assertTrue(card.authenticated)
        assertEquals(1, card.reads)
    }

    @Test
    fun `a wrong card answer fails the handshake`() {
        val card = FakeCard(key, rndB, wrongAnswer = true)

        try {
            PlusSession.authenticate(card, 0x4000, key) { rndA }
            fail("the handshake should not accept a wrong RndA")
        } catch (_: PlusAuthException) {
            // expected
        }
    }

    @Test
    fun `a large sector is read in three-block frames`() {
        val card = FakeCard(key, rndB)

        val read = PlusReader.readSector(card, key, sector = 32, keyB = false)

        assertEquals(16, read.blocks.size)
        assertEquals(128, read.blocks.first().number)
        assertEquals(143, read.blocks.last().number)
        assertEquals(6, card.reads)
    }

    @Test
    fun `geometry follows the 4k map`() {
        assertEquals(32, PlusGeometry.sectorCount(2048))
        assertEquals(40, PlusGeometry.sectorCount(4096))
        assertEquals(4, PlusGeometry.blockCount(0))
        assertEquals(16, PlusGeometry.blockCount(32))
        assertEquals(0, PlusGeometry.firstBlock(0))
        assertEquals(128, PlusGeometry.firstBlock(32))
        assertEquals(listOf(128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143), PlusGeometry.blocks(32))
    }

    @Test
    fun `sector key numbers start at 4000`() {
        assertEquals(0x4000, PlusSession.keyNumber(0, keyB = false))
        assertEquals(0x4001, PlusSession.keyNumber(0, keyB = true))
        assertEquals(0x400A, PlusSession.keyNumber(5, keyB = false))
    }

    private class FakeCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
        private val wrongAnswer: Boolean = false,
    ) : RawTagTransport {

        var authenticated = false
            private set

        var reads = 0
            private set

        private var counter = 0
        private lateinit var ti: ByteArray
        private lateinit var kenc: ByteArray
        private lateinit var kmac: ByteArray

        override fun transceive(data: ByteArray): ByteArray {
            val command = data[0].toInt() and 0xFF
            return when (command) {
                0x70 -> {
                    val encRndB = DesfireCrypto.aesEncryptCbc(key, ByteArray(16), rndB)
                    ack(encRndB)
                }

                0x72 -> {
                    val plain = DesfireCrypto.aesDecryptCbc(
                        key,
                        ByteArray(16),
                        data.copyOfRange(1, 33),
                    )
                    val rndA = plain.copyOfRange(0, 16)
                    check(plain.copyOfRange(16, 32).contentEquals(DesfireCrypto.rotateLeft(rndB)))
                    ti = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                    kenc = derive(rndA, rndB, 11, 4, 0x11)
                    kmac = derive(rndA, rndB, 7, 0, 0x22)
                    authenticated = true
                    val caps = ByteArray(6)
                    val body = if (wrongAnswer) {
                        ti + ByteArray(16) { 0x7F } + caps + caps
                    } else {
                        ti + DesfireCrypto.rotateLeft(rndA) + caps + caps
                    }
                    ack(DesfireCrypto.aesEncryptCbc(key, ByteArray(16), body))
                }

                0x30 -> {
                    reads++
                    val header = data.copyOfRange(0, 4)
                    val received = data.copyOfRange(4, 12)
                    val expected = expectedMac(header)
                    check(received.contentEquals(expected))
                    val first = header[1].toInt() and 0xFF
                    val count = header[3].toInt() and 0xFF
                    val content = ByteArray(count * 16) { (first * 16 + it).toByte() }
                    counter++
                    val encrypted = DesfireCrypto.aesEncryptCbc(kenc, responseIv(), content)
                    ack(encrypted)
                }

                else -> throw IllegalStateException("unexpected command 0x%02X".format(command))
            }
        }

        private fun expectedMac(header: ByteArray): ByteArray {
            val input = byteArrayOf(
                0x30,
                (counter and 0xFF).toByte(),
                ((counter shr 8) and 0xFF).toByte(),
                ti[0], ti[1], ti[2], ti[3],
                header[1],
                0x00,
                header[3],
            )
            return DesfireCrypto.cmac(kmac, ByteArray(16), input).mac.copyOf(8)
        }

        private fun responseIv(): ByteArray {
            val iv = ByteArray(16)
            val low = (counter and 0xFF).toByte()
            iv[0] = low
            iv[4] = low
            iv[8] = low
            ti.copyInto(iv, 12)
            return iv
        }

        private fun derive(
            rndA: ByteArray,
            rndB: ByteArray,
            firstOffset: Int,
            xorOffset: Int,
            marker: Int,
        ): ByteArray {
            val input = ByteArray(16)
            rndA.copyInto(input, 0, firstOffset, firstOffset + 5)
            rndB.copyInto(input, 5, firstOffset, firstOffset + 5)
            for (index in 0 until 5) {
                input[10 + index] =
                    (rndA[xorOffset + index].toInt() xor rndB[xorOffset + index].toInt()).toByte()
            }
            input[15] = marker.toByte()
            return DesfireCrypto.aesEcbEncrypt(key, input)
        }

        private fun ack(body: ByteArray): ByteArray = byteArrayOf(0x90.toByte()) + body
    }

    private companion object {
        fun hex(text: String): ByteArray = text.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
