// Non-destructive Magic card & clone detector + Proxmark3 command bridge.
//
// Probes only read-only responses over NfcA: never writes a single bit to the
// card. Also builds the exact Proxmark3 CLI command for handing off cards that
// hit Android NFC controller limits.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.IOException

enum class MagicKind(val badge: String) {
    GEN4_GTU("Gen4 GTU / Ultimate Magic Card"),
    GEN3_APDU("Gen3 / APDU Magic"),
    DIRECT_WRITE_OR_UFUID("Gen2 / CUID / Unauthenticated Block 0"),
    TYPE2_CLONE("Magic / Cloned Type 2 (Ultralight / NTAG)"),
    CLASSIC_7B_CLONE("7-byte Classic Clone (no Crypto1 on phone)"),
}

data class MagicDetection(
    val kind: MagicKind,
    val detail: String,
)

object MagicCardDetector {

    // Read-only Gen4 GTU "Get Config" with default 4-byte password 00 00 00 00:
    // CF 00 00 00 00 CC -> returns 30-byte GTU config when default-unlocked.
    private val GEN4_GET_CFG = byteArrayOf(0xCF.toByte(), 0x00, 0x00, 0x00, 0x00, 0xCC.toByte())

    // Read-only MIFARE READ block 0 without Crypto1 auth: genuine Classic NACKs
    // or drops the frame; unlocked UFUID / Gen3 / some CUID clones return 16 B!
    private val RAW_READ_BLOCK0 = byteArrayOf(0x30, 0x00)

    /**
     * Safe, zero-write check for Classic-like NfcA tags. Must only be called
     * when the tag is NOT ISO-DEP (to avoid disturbing smartcard sessions).
     */
    fun probeClassicLike(
        snapshot: TagSnapshot,
        session: TagSession,
        abort: (Throwable) -> Boolean = { false },
    ): MagicDetection? {
        if (!snapshot.hasTech(NfcTech.NFC_A)) return null
        if (snapshot.hasTech(NfcTech.ISO_DEP)) return null

        val sak = snapshot.sak?.and(0xFF) ?: return null
        val isClassicSak = TagIdentifier.isClassicSak(sak)
        if (!isClassicSak) return null

        // 1. Try Gen4 GTU read-only config query (CF 00 00 00 00 CC).
        val gtuCfg = session.withNfcA { transport ->
            tryTransceive(transport, GEN4_GET_CFG, abort)
        }
        if (gtuCfg != null && gtuCfg.size >= 16) {
            return MagicDetection(
                MagicKind.GEN4_GTU,
                "Answered CF..CC config read (${gtuCfg.size} B, default password 00000000)",
            )
        }

        // 2. Try unauthenticated Type-2-style READ 30 00 on a Classic SAK card.
        // Genuine NXP Classic never answers 30 00 without Crypto1 auth; Gen3 and
        // unlocked UFUID cards return 16 bytes directly!
        val rawBlock0 = session.withNfcA { transport ->
            tryTransceive(transport, RAW_READ_BLOCK0, abort)
        }
        if (rawBlock0 != null && rawBlock0.size == 16) {
            return MagicDetection(
                MagicKind.GEN3_APDU,
                "Block 0 readable over raw NfcA without Crypto1 (Gen3 / unlocked UFUID)",
            )
        }

        // 3. 7-byte UID advertising Classic SAK 08/18 without MifareClassic tech.
        if (snapshot.uid.size == 7 && !snapshot.hasTech(NfcTech.MIFARE_CLASSIC)) {
            return MagicDetection(
                MagicKind.CLASSIC_7B_CLONE,
                "7-byte UID with Classic SAK 0x%02X; use Proxmark3 for Crypto1 access".format(sak),
            )
        }
        return null
    }

    /** Suggests the best one-tap Proxmark3 CLI command for [identity]. */
    fun suggestedPm3Command(identity: TagIdentity): String = when (identity.family) {
        TagFamily.MIFARE_CLASSIC -> "hf mf autopwn"
        TagFamily.MIFARE_ULTRALIGHT, TagFamily.NTAG -> "hf mfu info"
        TagFamily.DESFIRE -> "hf mfdes info"
        TagFamily.MIFARE_PLUS -> "hf mfp info"
        TagFamily.ISO_15693, TagFamily.ICODE, TagFamily.ST25, TagFamily.EM_MICROELECTRONIC -> "hf 15 info"
        TagFamily.FELICA, TagFamily.ISO_18092 -> "hf felica info"
        TagFamily.ISO_14443_B -> "hf 14b info"
        TagFamily.CALYPSO, TagFamily.EMV, TagFamily.EMRTD, TagFamily.EID,
        TagFamily.CIPURSE, TagFamily.PIV, TagFamily.SMARTMX, TagFamily.ISO_DEP -> "hf 14a info"
        TagFamily.MY_D, TagFamily.SLE66R -> "hf mfu info"
        TagFamily.UNKNOWN -> "auto"
    }

    private fun tryTransceive(
        transport: RawTagTransport,
        cmd: ByteArray,
        abort: (Throwable) -> Boolean,
    ): ByteArray? = try {
        transport.transceive(cmd)
    } catch (e: IOException) {
        if (abort(e)) throw e
        null
    }
}
