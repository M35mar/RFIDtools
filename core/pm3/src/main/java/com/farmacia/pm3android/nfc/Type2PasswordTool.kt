// Dedicated Type 2 (NTAG21x / MIFARE Ultralight EV1) Password & Protection
// engine, including UID-derived PWD/PACK presets and mandatory read-back check.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import com.farmacia.pm3android.nfc.inspect.VersionInfo
import java.io.IOException
import java.util.zip.CRC32

enum class Type2PwdAction {
    SET_PROTECTION,
    REMOVE_PROTECTION,
}

data class Type2PwdResult(
    val uidHex: String,
    val tagLabel: String,
    val auth0Page: Int,
    val readProtected: Boolean,
    val verified: Boolean,
)

object Type2PasswordKeygen {

    /**
     * Common UID-derived 4-byte PWD + 2-byte PACK algorithm used by NTAG215
     * gaming figures / TagMo-compatible profiles:
     *   PWD[0] = UID[1] ^ UID[3] ^ 0xAA
     *   PWD[1] = UID[2] ^ UID[4] ^ 0x55
     *   PWD[2] = UID[3] ^ UID[5] ^ 0xAA
     *   PWD[3] = UID[4] ^ UID[6] ^ 0x55
     *   PACK   = 80 80
     */
    fun deriveAmiiboStyle(uid: ByteArray): Pair<ByteArray, ByteArray>? {
        if (uid.size != 7) return null
        val pwd = byteArrayOf(
            (uid[1].toInt() xor uid[3].toInt() xor 0xAA).toByte(),
            (uid[2].toInt() xor uid[4].toInt() xor 0x55).toByte(),
            (uid[3].toInt() xor uid[5].toInt() xor 0xAA).toByte(),
            (uid[4].toInt() xor uid[6].toInt() xor 0x55).toByte(),
        )
        val pack = byteArrayOf(0x80.toByte(), 0x80.toByte())
        return pwd to pack
    }

    /** Deterministic CRC32(UID) password + upper-16-bit PACK for quick fleet provisioning. */
    fun deriveCrc32(uid: ByteArray): Pair<ByteArray, ByteArray> {
        val crc = CRC32().apply { update(uid) }.value
        val pwd = byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
        val pack = byteArrayOf(
            ((crc ushr 24) xor (crc ushr 8)).and(0xFF).toByte(),
            ((crc ushr 16) xor crc).and(0xFF).toByte(),
        )
        return pwd to pack
    }
}

class Type2PasswordTool(private val transport: UltralightTransport) {

    companion object {
        private const val CMD_GET_VERSION = 0x60
        private const val CMD_READ = 0x30
        private const val CMD_WRITE = 0xA2
        private const val CMD_PWD_AUTH = 0x1B
        private const val ACK = 0x0A
        private const val ACCESS_PROT = 0x80
        private const val ACCESS_CFGLCK = 0x40
    }

    fun apply(
        uid: ByteArray,
        action: Type2PwdAction,
        currentPassword: ByteArray?,
        newPassword: ByteArray,
        newPack: ByteArray,
        protectReadAndWrite: Boolean,
        startPage: Int = 4,
    ): Type2PwdResult {
        require(newPassword.size == 4) { "PWD must be 4 bytes" }
        require(newPack.size == 2) { "PACK must be 2 bytes" }

        val rawVersion = try {
            transport.transceive(byteArrayOf(CMD_GET_VERSION.toByte())).takeIf { it.size >= 8 }
        } catch (_: IOException) {
            null
        } ?: throw TagUnusableException("Il tag non supporta GET_VERSION (serve NTAG21x o Ultralight EV1)")

        val info = VersionInfo.parse(rawVersion)
            ?: throw TagUnusableException("Risposta GET_VERSION non riconosciuta")
        val totalPages = info.pageCount
            ?: throw TagUnusableException("Taglia memoria sconosciuta per ${info.productName}")
        val layout = MfuLayout(totalPages)

        // Try reading CFG0..CFG1 before auth to inspect CFGLCK / AUTHLIM.
        var cfgChunk = tryRead4(layout.cfg0)
        if (cfgChunk != null) {
            val access = cfgChunk[4].toInt() and 0xFF
            if ((access and ACCESS_CFGLCK) != 0) {
                throw TagUnusableException("Configurazione bloccata permanentemente (CFGLCK=1)")
            }
            val auth0Now = cfgChunk[3].toInt() and 0xFF
            val authLim = access and 0x07
            if (auth0Now < totalPages && currentPassword == null && authLim in 1..7) {
                throw TagAuthException("Tag protetto con limite tentativi ($authLim): inserisci la password attuale")
            }
        }

        // Authenticate if currentPassword supplied or if config pages are read-protected.
        val candidates = buildList {
            currentPassword?.takeIf { it.size == 4 }?.let { add(it) }
            if (cfgChunk == null || (cfgChunk[3].toInt() and 0xFF) < totalPages) {
                if (currentPassword == null) add(MFU_DEFAULT_PASSWORD)
            }
        }
        for (cand in candidates) {
            if (tryAuth(cand)) break
        }

        cfgChunk = tryRead4(layout.cfg0)
            ?: throw TagAuthException("Password attuale mancante o rifiutata dal tag")
        val currentCfg0 = cfgChunk.copyOfRange(0, 4)
        val currentCfg1 = cfgChunk.copyOfRange(4, 8)
        if ((currentCfg1[0].toInt() and ACCESS_CFGLCK) != 0) {
            throw TagUnusableException("Configurazione bloccata permanentemente (CFGLCK=1)")
        }

        val targetAuth0: Int
        val targetAccess: Int
        val targetPwd: ByteArray
        val targetPackPage: ByteArray

        when (action) {
            Type2PwdAction.SET_PROTECTION -> {
                targetAuth0 = startPage.coerceIn(0, totalPages - 1)
                val baseAccess = currentCfg1[0].toInt() and 0xFF and ACCESS_CFGLCK.inv()
                targetAccess = if (protectReadAndWrite) {
                    baseAccess or ACCESS_PROT
                } else {
                    baseAccess and ACCESS_PROT.inv()
                }
                targetPwd = newPassword
                targetPackPage = byteArrayOf(newPack[0], newPack[1], 0x00, 0x00)
            }
            Type2PwdAction.REMOVE_PROTECTION -> {
                targetAuth0 = 0xFF
                targetAccess = (currentCfg1[0].toInt() and 0xFF) and ACCESS_PROT.inv() and ACCESS_CFGLCK.inv()
                targetPwd = MFU_DEFAULT_PASSWORD
                targetPackPage = ByteArray(4)
            }
        }

        // Safe write order: PACK -> PWD -> CFG0 (AUTH0) -> CFG1 (ACCESS), never setting CFGLCK.
        writePageChecked(layout.packPage, targetPackPage)
        writePageChecked(layout.pwdPage, targetPwd)

        val nextCfg0 = currentCfg0.copyOf().apply { this[3] = targetAuth0.toByte() }
        val nextCfg1 = currentCfg1.copyOf().apply { this[0] = targetAccess.toByte() }
        writePageChecked(layout.cfg0, nextCfg0)
        writePageChecked(layout.cfg1, nextCfg1)

        // Re-authenticate with targetPwd if needed and read back CFG0/CFG1 to verify!
        if (targetAuth0 <= layout.cfg0) {
            tryAuth(targetPwd)
        }
        val after = tryRead4(layout.cfg0)
            ?: throw IOException("Impossibile rileggere CFG0/CFG1 per verifica")
        val verified = after.copyOfRange(0, 4).contentEquals(nextCfg0) &&
            after.copyOfRange(4, 8).contentEquals(nextCfg1)
        if (!verified) {
            throw IOException("Verifica di rilettura fallita su CFG0/CFG1")
        }

        return Type2PwdResult(
            uidHex = uid.toHex(),
            tagLabel = info.productName,
            auth0Page = targetAuth0,
            readProtected = (targetAccess and ACCESS_PROT) != 0 && targetAuth0 < totalPages,
            verified = true,
        )
    }

    private fun tryRead4(page: Int): ByteArray? = try {
        transport.transceive(byteArrayOf(CMD_READ.toByte(), page.toByte()))
            .takeIf { it.size == 16 }
    } catch (_: IOException) {
        null
    }

    private fun tryAuth(pwd: ByteArray): Boolean = try {
        val r = transport.transceive(byteArrayOf(CMD_PWD_AUTH.toByte()) + pwd)
        r.size >= 2
    } catch (_: IOException) {
        false
    }

    private fun writePageChecked(page: Int, data: ByteArray) {
        val resp = transport.transceive(byteArrayOf(CMD_WRITE.toByte(), page.toByte()) + data.copyOf(4))
        if (resp.isNotEmpty() && (resp[0].toInt() and 0xFF) != ACK) {
            throw TagAuthException("Scrittura rifiutata alla pagina $page (serve autenticazione password)")
        }
    }
}
