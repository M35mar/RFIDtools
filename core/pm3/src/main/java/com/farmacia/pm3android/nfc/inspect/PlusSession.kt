// MIFARE Plus SL3 secure session over the raw NfcA frames. The handshake and
// the MAC layout follow the Proxmark3 client (mifare4.c): Authenticate (0x70),
// key number little-endian, RndB rotated by one byte, Kenc/Kmac derived from
// both random values with the AES key. A sector read uses command 0x30
// (encrypted, MAC on the command, no MAC on the response) so the answer is the
// encrypted blocks only.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.security.SecureRandom

/** A tag that accepts raw protocol frames, implemented by NfcA on Android. */
interface RawTagTransport {
    fun transceive(data: ByteArray): ByteArray
}

/** The card refused the key or the secure session did not verify. */
class PlusAuthException(message: String) : Exception(message)

class PlusSession internal constructor(
    private val kenc: ByteArray,
    private val kmac: ByteArray,
    private val ti: ByteArray,
) {

    private var readCounter: Int = 0

    internal val counter: Int get() = readCounter

    /**
     * Reads [blockCount] consecutive blocks starting at [firstBlock] in one
     * encrypted frame. Returns one 16-byte array per block.
     */
    fun readBlocks(
        transport: RawTagTransport,
        firstBlock: Int,
        blockCount: Int,
    ): List<ByteArray> {
        require(blockCount in 1..15) { "num. blocchi non valido" }
        require(firstBlock in 0..255) { "blocco non valido" }

        val command = READ_ENCRYPTED
        val header = byteArrayOf(
            command.toByte(),
            firstBlock.toByte(),
            0x00,
            blockCount.toByte(),
        )
        val mac = calculateMac(command, firstBlock, blockCount, readCounter)
        val response = transport.transceive(header + mac)
        val encrypted = payload(response, blockCount * 16)
        readCounter++

        val plain = DesfireCrypto.aesDecryptCbc(kenc, responseIv(readCounter), encrypted)
        return (0 until blockCount).map { index ->
            plain.copyOfRange(index * 16, (index + 1) * 16)
        }
    }

    private fun calculateMac(
        command: Int,
        firstBlock: Int,
        blockCount: Int,
        counter: Int,
    ): ByteArray {
        val input = byteArrayOf(
            command.toByte(),
            (counter and 0xFF).toByte(),
            ((counter shr 8) and 0xFF).toByte(),
            ti[0], ti[1], ti[2], ti[3],
            firstBlock.toByte(),
            0x00,
            blockCount.toByte(),
        )
        return DesfireCrypto.cmac(kmac, ByteArray(16), input).mac.copyOf(MAC_LENGTH)
    }

    /** Read IV used by the client: the low counter byte every four bytes, TI last. */
    private fun responseIv(counter: Int): ByteArray {
        val iv = ByteArray(16)
        val low = (counter and 0xFF).toByte()
        iv[0] = low
        iv[4] = low
        iv[8] = low
        ti.copyInto(iv, 12)
        return iv
    }

    companion object {

        const val MAC_LENGTH = 8
        private const val AUTH_FIRST = 0x70
        private const val AUTH_SECOND = 0x72
        private const val READ_ENCRYPTED = 0x30

        private val ZERO = ByteArray(16)
        private val RANDOM = SecureRandom()

        /**
         * Sector keys start at 0x4000: `0x4000 + sector * 2 + (key B ? 1 : 0)`.
         */
        fun keyNumber(sector: Int, keyB: Boolean): Int =
            0x4000 + sector * 2 + if (keyB) 1 else 0

        fun authenticate(
            transport: RawTagTransport,
            keyNumber: Int,
            key: ByteArray,
            randomSource: () -> ByteArray = { ByteArray(16).also(RANDOM::nextBytes) },
        ): PlusSession {
            require(key.size == 16) { "la chiave AES deve essere di 16 byte" }

            val first = transport.transceive(
                byteArrayOf(
                    AUTH_FIRST.toByte(),
                    (keyNumber and 0xFF).toByte(),
                    ((keyNumber shr 8) and 0xFF).toByte(),
                    0x00,
                ),
            )
            val encryptedRndB = payload(first, 16)
            val rndB = DesfireCrypto.aesDecryptCbc(key, ZERO, encryptedRndB)
            val rndA = randomSource()
            if (rndA.size != 16) throw PlusAuthException("MIFARE Plus: RndA di lunghezza errata")

            val second = transport.transceive(
                byteArrayOf(AUTH_SECOND.toByte()) +
                    DesfireCrypto.aesEncryptCbc(
                        key,
                        ZERO,
                        rndA + DesfireCrypto.rotateLeft(rndB),
                    ),
            )
            val raw = DesfireCrypto.aesDecryptCbc(key, ZERO, payload(second, 32))
            val ti = raw.copyOfRange(0, 4)
            val rndAPrime = raw.copyOfRange(4, 20)
            if (!rndAPrime.contentEquals(DesfireCrypto.rotateLeft(rndA))) {
                throw PlusAuthException("MIFARE Plus: il tag non ha confermato la chiave")
            }

            return PlusSession(
                kenc = deriveKey(key, rndA, rndB, 11, 4, 0x11),
                kmac = deriveKey(key, rndA, rndB, 7, 0, 0x22),
                ti = ti,
            )
        }

        /** Kenc/Kmac layout of the MIFARE Plus datasheet. */
        private fun deriveKey(
            key: ByteArray,
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

        /**
         * Accepts the answer with or without the ISO 14443-3 CRC: the Android
         * NFC stack strips it, the Proxmark3 traces keep it.
         */
        private fun payload(response: ByteArray, expected: Int): ByteArray {
            if (response.isEmpty() || (response[0].toInt() and 0xFF) != 0x90) {
                val code = if (response.isEmpty()) -1 else response[0].toInt() and 0xFF
                throw PlusAuthException("MIFARE Plus: risposta 0x%02X".format(code))
            }
            val body = response.copyOfRange(1, response.size)
            return when {
                body.size == expected -> body
                body.size == expected + 2 -> body.copyOf(expected)
                body.size > expected -> body.copyOf(expected)
                else -> throw PlusAuthException(
                    "MIFARE Plus: risposta di ${body.size} byte, attesi $expected",
                )
            }
        }
    }
}
