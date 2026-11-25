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
    private val legacyDes: Boolean = false,
) {

    private var iv: ByteArray = initialIv.copyOf()

    /**
     * Sends one authenticated command. In MACed mode (AES) the response MAC is
     * verified and stripped; legacy DES/3DES sessions read plain/authenticated
     * payloads without AES-CMAC chaining.
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
        val useAesMac = mode == DesfireCommMode.MACED && !legacyDes
        val payload = if (useAesMac) {
            val commandMac = DesfireCrypto.cmac(
                sessionKey,
                iv,
                byteArrayOf(ins.toByte()) + data,
            )
            iv = commandMac.iv
            data + commandMac.mac.copyOf(MAC_LENGTH)
        } else {
            data
        }

        val frame = client.exchange(ins, payload)
        if (!frame.ok) {
            throw DesfireException(frame.status, "DESFire: risposta 0x%02X".format(frame.status and 0xFF))
        }
        if (legacyDes && mode == DesfireCommMode.MACED) {
            // Legacy DES/2K3DES appends 4-byte CRC/MAC on MACed reads; strip if >= 4.
            return if (frame.data.size >= 4) frame.data.copyOfRange(0, frame.data.size - 4) else frame.data
        }
        if (!useAesMac) return frame.data
        if (frame.data.size < MAC_LENGTH) {
            throw DesfireException(0, "DESFire: MAC di risposta di ${frame.data.size} byte")
        }

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

        private const val AUTH_LEGACY = 0x0A
        private const val AUTH_ISO_3DES = 0x1A
        private const val AUTH_AES = 0xAA
        private const val ADDITIONAL_FRAME = 0xAF

        private val ZERO = ByteArray(DesfireCrypto.BLOCK)
        private val ZERO_8 = ByteArray(8)
        private val RANDOM = SecureRandom()

        /**
         * Authenticates with a 16-byte AES key (or falls back to 2K3DES `0x1A` / `0x0A`
         * when the card rejects `0xAA`, and directly uses DES `0x0A` for 8-byte keys
         * such as factory `0000000000000000`).
         */
        fun authenticate(
            client: DesfireClient,
            keyNo: Int,
            key: ByteArray,
            randomSource: () -> ByteArray = { ByteArray(DesfireCrypto.BLOCK).also(RANDOM::nextBytes) },
        ): DesfireSession {
            if (key.size == 8) {
                return authenticateDes(client, keyNo, key + key)
            }
            require(key.size == DesfireCrypto.BLOCK) { "la chiave deve essere di 8 o 16 byte" }

            val first = client.exchange(AUTH_AES, byteArrayOf(keyNo.toByte()))
            if (!first.additional || first.data.size != DesfireCrypto.BLOCK) {
                if (first.authError) throw DesfireAuthException("DESFire: chiave rifiutata")
                // Card may be configured for 2K3DES instead of AES on this application:
                return authenticateDes(client, keyNo, key)
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

        private fun authenticateDes(
            client: DesfireClient,
            keyNo: Int,
            key16: ByteArray,
        ): DesfireSession {
            // Try ISO 2K3DES (0x1A) first, then native DESFire 0x0A.
            val isoFirst = client.exchange(AUTH_ISO_3DES, byteArrayOf(keyNo.toByte()))
            val useIso = isoFirst.additional && isoFirst.data.size == 8
            val first = if (useIso) isoFirst else client.exchange(AUTH_LEGACY, byteArrayOf(keyNo.toByte()))
            if (!first.additional || first.data.size != 8) {
                throw DesfireAuthException("DESFire DES/3DES: chiave rifiutata")
            }
            val key24 = key16 + key16.copyOfRange(0, 8)
            val cipher = javax.crypto.Cipher.getInstance("DESede/CBC/NoPadding")
            val spec = javax.crypto.spec.SecretKeySpec(key24, "DESede")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(ZERO_8))
            val rndB = cipher.doFinal(first.data)
            val rndA = ByteArray(8).also(RANDOM::nextBytes)
            val rndBRot = rndB.copyOfRange(1, 8) + rndB[0]

            val encBoth = if (useIso) {
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(first.data))
                cipher.doFinal(rndA + rndBRot)
            } else {
                // Native DESFire 0x0A uses "send mode" (decrypt operation with zero IV chaining)
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(ZERO_8))
                val b1 = cipher.doFinal(rndA)
                val xored = ByteArray(8) { i -> (rndBRot[i].toInt() xor b1[i].toInt()).toByte() }
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.IvParameterSpec(ZERO_8))
                b1 + cipher.doFinal(xored)
            }

            val second = client.exchange(ADDITIONAL_FRAME, encBoth)
            if (!second.ok || second.data.size != 8) {
                throw DesfireAuthException("DESFire DES/3DES: verifica fallita")
            }
            val sessionKey = rndA.copyOfRange(0, 4) + rndB.copyOfRange(0, 4) +
                rndA.copyOfRange(4, 8) + rndB.copyOfRange(4, 8)
            return DesfireSession(sessionKey, ZERO_8, legacyDes = true)
        }
    }
}
