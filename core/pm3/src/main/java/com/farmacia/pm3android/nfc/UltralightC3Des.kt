// MIFARE Ultralight C 2-key 3DES mutual authentication (`0x1A` + `0xAF`)
// and NTAG 424 DNA SDM / SUN (Secure Dynamic Messaging) AES-128 CMAC verifier.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import com.farmacia.pm3android.nfc.inspect.DesfireCrypto
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object UltralightC3Des {

    private const val CMD_AUTH_1 = 0x1A
    private const val CMD_AUTH_2 = 0xAF
    private val RANDOM = SecureRandom()

    /** Factory default NXP MIFARE Ultralight C 16-byte 2K3DES key ("BREAKMEIFYOUCAN!"). */
    val DEFAULT_KEY: ByteArray = "49454D4B41455242214E4143554F5946".hexToBytesOrNull()!!

    /**
     * Executes mutual 2-key 3DES CBC challenge-response authentication on a
     * MIFARE Ultralight C tag over [transport]. Returns true if mutual auth succeeded.
     */
    fun authenticate(
        transport: UltralightTransport,
        key16: ByteArray = DEFAULT_KEY,
        randomSource: () -> ByteArray = { ByteArray(8).also(RANDOM::nextBytes) },
    ): Boolean {
        if (key16.size != 16) return false
        val key24 = key16 + key16.copyOfRange(0, 8) // K1 || K2 || K1 for 3DESede

        val step1 = try {
            transport.transceive(byteArrayOf(CMD_AUTH_1.toByte(), 0x00))
        } catch (_: IOException) {
            return false
        }
        // Card answers 0xAF || E(RndB) (9 bytes)
        if (step1.size != 9 || (step1[0].toInt() and 0xFF) != CMD_AUTH_2) return false
        val encRndB = step1.copyOfRange(1, 9)
        val rndB = tdesCbc(Cipher.DECRYPT_MODE, key24, ByteArray(8), encRndB)
        val rndA = randomSource().copyOf(8)
        val rndBRot = rotateLeft8(rndB)

        val encBoth = tdesCbc(Cipher.ENCRYPT_MODE, key24, encRndB, rndA + rndBRot)
        val ivNext = encBoth.copyOfRange(8, 16)

        val step2 = try {
            transport.transceive(byteArrayOf(CMD_AUTH_2.toByte()) + encBoth)
        } catch (_: IOException) {
            return false
        }
        // Card answers 0x00 || E(RndA') (9 bytes)
        if (step2.size != 9 || step2[0] != 0x00.toByte()) return false
        val decRndARot = tdesCbc(Cipher.DECRYPT_MODE, key24, ivNext, step2.copyOfRange(1, 9))
        return decRndARot.contentEquals(rotateLeft8(rndA))
    }

    internal fun tdesCbc(mode: Int, key24: ByteArray, iv8: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key24, "DESede"), IvParameterSpec(iv8))
        return cipher.doFinal(data)
    }

    private fun rotateLeft8(b: ByteArray): ByteArray = b.copyOfRange(1, 8) + b[0]
}

data class SdmParsedUrl(
    val rawUrl: String,
    val encryptedPiccDataHex: String? = null,
    val plainUidHex: String? = null,
    val plainCtrHex: String? = null,
    val cmacHex: String,
)

data class SdmVerifyOutcome(
    val valid: Boolean,
    val uidHex: String,
    val readCounter: Int,
)

object Ntag424SdmVerifier {

    private val ZERO_IV = ByteArray(16)

    /** Detects NTAG 424 DNA SDM / SUN query parameters in an NDEF URL. */
    fun detectInUrl(url: String): SdmParsedUrl? {
        val query = runCatching { URI(url).rawQuery }.getOrNull()
            ?: url.substringAfter('?', "").takeIf { it.isNotEmpty() }
            ?: return null
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else part.substring(0, eq).lowercase() to part.substring(eq + 1)
        }.toMap()

        val cmac = (params["cmac"] ?: params["sdmmac"] ?: params["m"])
            ?.takeIf { it.length == 16 && it.hexToBytesOrNull() != null }
            ?: return null
        val encPicc = (params["picc_data"] ?: params["e"] ?: params["enc"])
            ?.takeIf { it.length == 32 && it.hexToBytesOrNull() != null }
        val uid = params["uid"]?.takeIf { it.length == 14 && it.hexToBytesOrNull() != null }
        val ctr = (params["ctr"] ?: params["c"])?.takeIf { it.length == 6 && it.hexToBytesOrNull() != null }

        if (encPicc == null && (uid == null || ctr == null)) return null
        return SdmParsedUrl(
            rawUrl = url,
            encryptedPiccDataHex = encPicc?.uppercase(),
            plainUidHex = uid?.uppercase(),
            plainCtrHex = ctr?.uppercase(),
            cmacHex = cmac.uppercase(),
        )
    }

    /**
     * Verifies NTAG 424 DNA SDM/SUN parameters per NXP AN12196:
     *  - Decrypts `PICCData` with [sdmMetaReadKey] (or all-zero default key) when encrypted
     *  - Derives `KSesSDMFileReadMAC = CMAC(sdmFileReadKey, 3CC300010080 || UID || CtrLE)`
     *  - Computes truncated 8-byte odd-indexed CMAC (`S1, S3, ..., S15`) and compares with `cmacHex`.
     */
    fun verify(
        parsed: SdmParsedUrl,
        sdmFileReadKey: ByteArray = ByteArray(16),
        sdmMetaReadKey: ByteArray = sdmFileReadKey,
        macInputAscii: ByteArray = ByteArray(0),
    ): SdmVerifyOutcome? {
        if (sdmFileReadKey.size != 16 || sdmMetaReadKey.size != 16) return null
        val expectedMac = parsed.cmacHex.hexToBytesOrNull() ?: return null

        val uid: ByteArray
        val ctrLe: ByteArray
        if (parsed.encryptedPiccDataHex != null) {
            val enc = parsed.encryptedPiccDataHex.hexToBytesOrNull() ?: return null
            val dec = DesfireCrypto.aesDecryptCbc(sdmMetaReadKey, ZERO_IV, enc)
            val tag = dec[0].toInt() and 0xFF
            val hasUid = (tag and 0x80) != 0
            val hasCtr = (tag and 0x40) != 0
            val uidLen = tag and 0x0F
            if (!hasUid || uidLen != 7) return null
            uid = dec.copyOfRange(1, 8)
            ctrLe = if (hasCtr) dec.copyOfRange(8, 11) else byteArrayOf(0, 0, 0)
        } else {
            uid = parsed.plainUidHex?.hexToBytesOrNull() ?: return null
            val ctrBe = parsed.plainCtrHex?.hexToBytesOrNull() ?: return null
            // Plain URL counter is big-endian hex (e.g. "00002A"), SV2 uses 3-byte little-endian!
            ctrLe = byteArrayOf(ctrBe[2], ctrBe[1], ctrBe[0])
        }

        // SV2 = 3C C3 00 01 00 80 || UID (7B) || SDMReadCtr (3B LE) -> 16 bytes
        val sv2 = byteArrayOf(0x3C, 0xC3.toByte(), 0x00, 0x01, 0x00, 0x80.toByte()) + uid + ctrLe
        val kSesMac = DesfireCrypto.cmac(sdmFileReadKey, ZERO_IV, sv2).mac
        val fullMac = DesfireCrypto.cmac(kSesMac, ZERO_IV, macInputAscii).mac
        // Truncated SDM MAC takes the 8 odd-indexed bytes: fullMac[1], fullMac[3], ..., fullMac[15]
        val truncated = ByteArray(8) { i -> fullMac[i * 2 + 1] }
        val counterInt = (ctrLe[0].toInt() and 0xFF) or
            ((ctrLe[1].toInt() and 0xFF) shl 8) or
            ((ctrLe[2].toInt() and 0xFF) shl 16)

        return SdmVerifyOutcome(
            valid = truncated.contentEquals(expectedMac),
            uidHex = uid.toHex(),
            readCounter = counterInt,
        )
    }
}
