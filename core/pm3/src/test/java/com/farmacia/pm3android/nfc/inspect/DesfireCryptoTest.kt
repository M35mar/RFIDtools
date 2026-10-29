// DESFire crypto: the AES-CMAC must match the published RFC 4493 vectors, and
// the challenge-response handshake must derive the NXP session key.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DesfireCryptoTest {

    private val key = hex("2B7E151628AED2A6ABF7158809CF4F3C")
    private val zero = ByteArray(16)

    @Test
    fun `cmac matches the RFC 4493 vectors`() {
        assertArrayEquals(
            hex("BB1D6929E95937287FA37D129B756746"),
            DesfireCrypto.cmac(key, zero, ByteArray(0)).mac,
        )
        assertArrayEquals(
            hex("070A16B46B4D4144F79BDD9DD04A287C"),
            DesfireCrypto.cmac(key, zero, hex("6BC1BEE22E409F96E93D7E117393172A")).mac,
        )
        assertArrayEquals(
            hex("DFA66747DE9AE63030CA32611497C827"),
            DesfireCrypto.cmac(
                key,
                zero,
                hex("6BC1BEE22E409F96E93D7E117393172AAE2D8A571E03AC9C9EB76FAC45AF8E5130C81C46A35CE411"),
            ).mac,
        )
        assertArrayEquals(
            hex("51F0BEBF7E3B9D92FC49741779363CFE"),
            DesfireCrypto.cmac(
                key,
                zero,
                hex(
                    "6BC1BEE22E409F96E93D7E117393172A" +
                        "AE2D8A571E03AC9C9EB76FAC45AF8E51" +
                        "30C81C46A35CE411E5FBC1191A0A52EF" +
                        "F69F2445DF4F9B17AD2B417BE66C3710",
                ),
            ).mac,
        )
    }

    @Test
    fun `cmac chains from the previous iv`() {
        val first = DesfireCrypto.cmac(key, zero, byteArrayOf(0x01, 0x02))
        val second = DesfireCrypto.cmac(key, first.iv, byteArrayOf(0x03))

        assertArrayEquals(first.mac, first.iv)
        assertTrue(second.mac.contentEquals(second.iv))
        assertTrue(!second.mac.contentEquals(first.mac))
    }

    @Test
    fun `cbc encrypt and decrypt round trip`() {
        val plain = hex("00112233445566778899AABBCCDDEEFF")
        val iv = hex("000102030405060708090A0B0C0D0E0F")

        val encrypted = DesfireCrypto.aesEncryptCbc(key, iv, plain)
        assertArrayEquals(plain, DesfireCrypto.aesDecryptCbc(key, iv, encrypted))
    }

    @Test
    fun `rotate left moves the first byte to the end`() {
        assertArrayEquals(
            hex("0203040102030405060708090A0B0C01"),
            DesfireCrypto.rotateLeft(hex("010203040102030405060708090A0B0C")),
        )
    }

    @Test
    fun `ev1 handshake derives the session key`() {
        val rndA = hex("01020304050607080910111213141516")
        val rndB = hex("16151413121110090807060504030201")
        val card = FakeCard(key, rndB)

        val session = DesfireSession.authenticate(
            DesfireClient(card),
            keyNo = 0,
            key = key,
        ) { rndA }

        assertTrue(card.authenticated)
        assertArrayEquals(
            hex("01020304161514131314151604030201"),
            session.sessionKey,
        )
    }

    @Test
    fun `a wrong card answer fails the handshake`() {
        val card = FakeCard(key, ByteArray(16) { 0x55 }, wrongAnswer = true)

        try {
            DesfireSession.authenticate(DesfireClient(card), 0, key)
            fail("the handshake should not accept a wrong RndA")
        } catch (_: DesfireAuthException) {
            // expected
        }
    }

    private class FakeCard(
        private val key: ByteArray,
        private val rndB: ByteArray,
        private val wrongAnswer: Boolean = false,
    ) : IsoDepTransport {

        var authenticated = false
            private set

        private lateinit var encRndB: ByteArray

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            val lc = command[4].toInt() and 0xFF
            val data = command.copyOfRange(5, 5 + lc)
            return when (ins) {
                0xAA -> {
                    encRndB = DesfireCrypto.aesEncryptCbc(key, ByteArray(16), rndB)
                    frame(encRndB, 0x91AF)
                }

                0xAF -> {
                    val plain = DesfireCrypto.aesDecryptCbc(key, encRndB, data)
                    val rndA = plain.copyOfRange(0, 16)
                    val returnedRndB = plain.copyOfRange(16, 32)
                    check(returnedRndB.contentEquals(DesfireCrypto.rotateLeft(rndB)))
                    val iv = data.copyOfRange(16, 32)
                    val answer = if (wrongAnswer) {
                        DesfireCrypto.aesEncryptCbc(key, iv, ByteArray(16) { 0x33 })
                    } else {
                        DesfireCrypto.aesEncryptCbc(key, iv, DesfireCrypto.rotateLeft(rndA))
                    }
                    authenticated = !wrongAnswer
                    frame(answer, 0x9100)
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
