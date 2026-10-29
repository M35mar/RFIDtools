// Authenticated DESFire EV1 session with AES. The handshake follows the
// challenge-response described by NXP and implemented in the Proxmark3 client:
// the card sends E(RndB), the reader answers E(RndA || RndB') in the same CBC
// chain, and the card returns E(RndA'). The session key is 4-byte interleaved
// halves of the two random values.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.security.SecureRandom

/** Communication mode of a DESFire file, from GetFileSettings. */
enum class DesfireCommMode {
    PLAIN,
    MACED,
    ENCRYPTED,
}

/** The card refused the key or the handshake did not verify. */
class DesfireAuthException(message: String) : Exception(message)

class DesfireSession internal constructor(
    internal val sessionKey: ByteArray,
    initialIv: ByteArray,
) {

    private var iv: ByteArray = initialIv.copyOf()

    /**
     * Sends one authenticated command. The response MAC is verified and
     * stripped; data of a plain or MACed file comes back as it was written.
     */
    fun command(
        client: DesfireClient,
        ins: Int,
        data: ByteArray,
        mode: DesfireCommMode,
    ): ByteArray {
        require(mode != DesfireCommMode.ENCRYPTED) {
            "i file cifrati non sono ancora supportati"
        }
        val commandMac = DesfireCrypto.cmac(
            sessionKey,
            iv,
            byteArrayOf(ins.toByte()) + data,
        )
        iv = commandMac.iv

        val payload = if (mode == DesfireCommMode.MACED) {
            data + commandMac.mac.copyOf(MAC_LENGTH)
        } else {
            data
        }

        val frame = client.exchange(ins, payload)
        if (!frame.ok) {
            throw DesfireException(frame.status, "DESFire: risposta 0x%02X".format(frame.status and 0xFF))
        }
        if (frame.data.size < MAC_LENGTH) return frame.data

        val body = frame.data.copyOfRange(0, frame.data.size - MAC_LENGTH)
        val received = frame.data.copyOfRange(frame.data.size - MAC_LENGTH, frame.data.size)
        val expected = DesfireCrypto.cmac(
            sessionKey,
            iv,
            body + byteArrayOf(0x00),
        )
        iv = expected.iv
        if (!expected.mac.copyOf(MAC_LENGTH).contentEquals(received)) {
            throw DesfireException(0, "DESFire: MAC della risposta non valido")
        }
        return body
    }

    companion object {

        const val MAC_LENGTH = 8

        private const val AUTH_AES = 0xAA
        private const val ADDITIONAL_FRAME = 0xAF

        private val ZERO = ByteArray(DesfireCrypto.BLOCK)
        private val RANDOM = SecureRandom()

        /**
         * Authenticates with a 16-byte AES key. [randomSource] exists for the
         * unit tests: on a phone RndA always comes from SecureRandom.
         */
        fun authenticate(
            client: DesfireClient,
            keyNo: Int,
            key: ByteArray,
            randomSource: () -> ByteArray = { ByteArray(DesfireCrypto.BLOCK).also(RANDOM::nextBytes) },
        ): DesfireSession {
            require(key.size == DesfireCrypto.BLOCK) { "la chiave AES deve essere di 16 byte" }

            val first = client.exchange(AUTH_AES, byteArrayOf(keyNo.toByte()))
            if (first.authError) {
                throw DesfireAuthException("DESFire: chiave rifiutata")
            }
            if (!first.additional || first.data.size != DesfireCrypto.BLOCK) {
                throw DesfireAuthException("DESFire: risposta di autenticazione inattesa")
            }

            val encRndB = first.data
            val rndB = DesfireCrypto.aesDecryptCbc(key, ZERO, encRndB)
            val rndA = randomSource()
            if (rndA.size != DesfireCrypto.BLOCK) {
                throw DesfireAuthException("DESFire: RndA di lunghezza errata")
            }

            val encBoth = DesfireCrypto.aesEncryptCbc(
                key,
                encRndB,
                rndA + DesfireCrypto.rotateLeft(rndB),
            )
            val second = client.exchange(ADDITIONAL_FRAME, encBoth)
            if (second.authError) {
                throw DesfireAuthException("DESFire: chiave rifiutata")
            }
            if (!second.ok || second.data.size != DesfireCrypto.BLOCK) {
                throw DesfireAuthException("DESFire: verifica del lettore fallita")
            }

            val ivAfter = encBoth.copyOfRange(DesfireCrypto.BLOCK, DesfireCrypto.BLOCK * 2)
            val expected = DesfireCrypto.aesDecryptCbc(key, ivAfter, second.data)
            if (!expected.contentEquals(DesfireCrypto.rotateLeft(rndA))) {
                throw DesfireAuthException("DESFire: il tag non ha confermato la chiave")
            }

            val sessionKey = rndA.copyOfRange(0, 4) +
                rndB.copyOfRange(0, 4) +
                rndA.copyOfRange(12, 16) +
                rndB.copyOfRange(12, 16)
            return DesfireSession(sessionKey, ZERO)
        }
    }
}
