// AES helpers for the DESFire secure channel. The card uses plain AES-CBC with
// zeros as initial vector for the handshake, then a CBC-MAC (CMAC) whose state
// is the last ciphertext block of the previous command. The code uses
// javax.crypto so it runs both on Android and on the JVM unit tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** CMAC output plus the chaining vector the next command must use. */
internal class MacResult(val mac: ByteArray, val iv: ByteArray)

internal object DesfireCrypto {

    const val BLOCK = 16

    private val ZERO = ByteArray(BLOCK)

    fun aesEncryptCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        cbc(Cipher.ENCRYPT_MODE, key, iv, data)

    fun aesDecryptCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        cbc(Cipher.DECRYPT_MODE, key, iv, data)

    fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    /**
     * DESFire CMAC: a CBC-MAC with the NXP subkeys, chained from [iv]. The last
     * ciphertext block is both the MAC and the IV of the next command.
     */
    fun cmac(key: ByteArray, iv: ByteArray, data: ByteArray): MacResult {
        val l = aesEcbEncrypt(key, ZERO)
        val k1 = dbl(l)
        val k2 = dbl(k1)

        val complete = data.isNotEmpty() && data.size % BLOCK == 0
        val padded = if (complete) {
            data.copyOf()
        } else {
            val size = ((data.size / BLOCK) + 1) * BLOCK
            ByteArray(size).also {
                data.copyInto(it)
                it[data.size] = 0x80.toByte()
            }
        }
        val subkey = if (complete) k1 else k2
        for (index in 0 until BLOCK) {
            val at = padded.size - BLOCK + index
            padded[at] = (padded[at].toInt() xor subkey[index].toInt()).toByte()
        }

        val encrypted = aesEncryptCbc(key, iv, padded)
        val mac = encrypted.copyOfRange(encrypted.size - BLOCK, encrypted.size)
        return MacResult(mac = mac, iv = mac.copyOf())
    }

    /** Left rotation by one byte, used by the challenge-response handshake. */
    fun rotateLeft(data: ByteArray): ByteArray =
        if (data.isEmpty()) data.copyOf() else data.copyOfRange(1, data.size) + data[0]

    private fun dbl(block: ByteArray): ByteArray {
        val out = ByteArray(BLOCK)
        var carry = 0
        for (index in BLOCK - 1 downTo 0) {
            val value = block[index].toInt() and 0xFF
            out[index] = ((value shl 1) or carry).toByte()
            carry = (value ushr 7) and 1
        }
        if (block[0].toInt() and 0x80 != 0) {
            out[BLOCK - 1] = (out[BLOCK - 1].toInt() xor 0x87).toByte()
        }
        return out
    }

    private fun cbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.size % BLOCK == 0) { "AES-CBC data must be block aligned" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
