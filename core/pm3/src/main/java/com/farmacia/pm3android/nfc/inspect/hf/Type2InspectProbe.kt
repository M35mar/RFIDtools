// Deep inspection probe for MIFARE Ultralight (EV1, AES) and NTAG21x tags.
//
// Runs automatically on first tap in the NFC inspector when GET_VERSION
// identified a Type 2 chip: reads the 32-byte NXP ECC Originality Signature
// (READ_SIG 0x3C), monotonic counters + tearing flags (0x39 / 0x3E), decodes
// static/dynamic lock bits and CFG0/CFG1 security (AUTH0, PROT, CFGLCK,
// AUTHLIM), and extracts any NDEF message from user pages without needing a
// second tap.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect.hf

import com.farmacia.pm3android.nfc.MfuLayout
import com.farmacia.pm3android.nfc.inspect.DetailRow
import com.farmacia.pm3android.nfc.inspect.DetailSection
import com.farmacia.pm3android.nfc.inspect.GenericDetails
import com.farmacia.pm3android.nfc.inspect.HfProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.OriginalityGroup
import com.farmacia.pm3android.nfc.inspect.OriginalityStatus
import com.farmacia.pm3android.nfc.inspect.OriginalityVerifier
import com.farmacia.pm3android.nfc.inspect.PageMemoryReader
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.ProbeOutcome
import com.farmacia.pm3android.nfc.inspect.RawTagTransport
import com.farmacia.pm3android.nfc.inspect.TagFamily
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.VersionInfo
import com.farmacia.pm3android.nfc.inspect.content.TagContentAnalyzer
import com.farmacia.pm3android.nfc.toHex
import java.io.IOException

object Type2InspectProbe : HfProbe {

    override val id = "type2-inspect"

    private const val CMD_READ = 0x30
    private const val CMD_READ_CNT = 0x39
    private const val CMD_READ_TEARING = 0x3E
    private const val CMD_READ_SIG = 0x3C

    override fun matches(context: ProbeContext): Boolean {
        val snapshot = context.snapshot
        if (!snapshot.hasTech(NfcTech.NFC_A)) return false
        if (snapshot.hasTech(NfcTech.ISO_DEP) || snapshot.hasTech(NfcTech.MIFARE_CLASSIC)) return false
        val version = snapshot.version?.let(VersionInfo::parse) ?: return false
        return version.family == TagFamily.MIFARE_ULTRALIGHT || version.family == TagFamily.NTAG
    }

    override fun run(context: ProbeContext): ProbeOutcome? {
        val version = context.snapshot.version?.let(VersionInfo::parse) ?: return null
        val pageCount = version.pageCount ?: return null
        val abort = context.abort

        return context.session.withNfcA { transport ->
            val sections = mutableListOf<DetailSection>()

            // 1. Originality Signature (32B secp128r1 or 48B secp192r1 ECC signed by NXP/ST).
            val signature = attempt(abort) { readSignature(transport) }
            val sigRows = mutableListOf<DetailRow>()
            if (signature != null) {
                val group = if (signature.size == 48) OriginalityGroup.MFULAES else OriginalityGroup.MFUL
                val check = OriginalityVerifier.verify(context.snapshot.uid, signature, group)
                when (check.status) {
                    OriginalityStatus.VALID -> {
                        sigRows += DetailRow(
                            "ECC Signature (0x3C)",
                            "Present (${signature.size} B) · VALID · ${check.keyName} (${check.curveName})",
                        )
                    }
                    OriginalityStatus.ALL_ZEROS -> {
                        sigRows += DetailRow(
                            "ECC Signature (0x3C)",
                            "All zeros (unprogrammed Magic / clone)",
                        )
                    }
                    OriginalityStatus.INVALID -> {
                        sigRows += DetailRow(
                            "ECC Signature (0x3C)",
                            "Present (${signature.size} B) · INVALID (UID mismatch / clone)",
                        )
                    }
                    OriginalityStatus.UNSUPPORTED_LENGTH -> {
                        sigRows += DetailRow("ECC Signature (0x3C)", "Present (${signature.size} B)")
                    }
                }
                if (check.status != OriginalityStatus.ALL_ZEROS) {
                    sigRows += DetailRow("Sig[0..15]", signature.copyOfRange(0, 16).toHex())
                    sigRows += DetailRow("Sig[16..31]", signature.copyOfRange(16, 32).toHex())
                    if (signature.size >= 48) {
                        sigRows += DetailRow("Sig[32..47]", signature.copyOfRange(32, 48).toHex())
                    }
                }
            } else {
                sigRows += DetailRow("ECC Signature (0x3C)", "Not answered")
            }

            // 1b. NXP Silicon Wafer Coordinates & Counter (when UID starts with 0x04).
            OriginalityVerifier.decodeNxpSilicon(context.snapshot.uid)?.let { silicon ->
                sigRows += DetailRow(
                    "Wafer Info",
                    "X=${silicon.waferX}, Y=${silicon.waferY} · LotCnt=${silicon.waferCounter} · Site=${silicon.testSite}",
                )
            }

            // 2. Monotonic counters & tearing flags.
            val counterIndices = if (version.family == TagFamily.NTAG) listOf(2) else listOf(0, 1, 2)
            val counterRows = mutableListOf<DetailRow>()
            for (idx in counterIndices) {
                val cntBytes = attempt(abort) { readCounter(transport, idx) }
                val tearing = attempt(abort) { readTearing(transport, idx) }
                if (cntBytes != null) {
                    val value = (cntBytes[0].toInt() and 0xFF) or
                        ((cntBytes[1].toInt() and 0xFF) shl 8) or
                        ((cntBytes[2].toInt() and 0xFF) shl 16)
                    val tearSuffix = tearing?.let { t ->
                        val ok = if (t == 0xBD) "OK" else "TORN"
                        " · tearing 0x%02X (%s)".format(t, ok)
                    }.orEmpty()
                    val name = if (version.family == TagFamily.NTAG) "NFC Scan Counter" else "Counter #$idx"
                    counterRows += DetailRow(name, "$value$tearSuffix")
                }
            }

            // 3. Static lock bits (page 2) + CFG0/CFG1 security pages.
            val layout = MfuLayout(pageCount)
            val headerPages = attempt(abort) { read4(transport, 0) }
            val cfgPages = attempt(abort) { read4(transport, layout.cfg0) }
            val lockRows = mutableListOf<DetailRow>()

            if (headerPages != null && headerPages.size == 16) {
                val lock0 = headerPages[10].toInt() and 0xFF
                val lock1 = headerPages[11].toInt() and 0xFF
                val cc = headerPages.copyOfRange(12, 16)
                val staticLocked = lock0 != 0 || lock1 != 0
                lockRows += DetailRow(
                    "Static Lock (p2)",
                    "%02X %02X (%s)".format(lock0, lock1, if (staticLocked) "Locked bits set" else "Unlocked"),
                )
                if ((cc[0].toInt() and 0xFF) == 0xE1) {
                    val verMajor = (cc[1].toInt() and 0xF0) ushr 4
                    val verMinor = cc[1].toInt() and 0x0F
                    val areaBytes = (cc[2].toInt() and 0xFF) * 8
                    val access = when (cc[3].toInt() and 0xFF) {
                        0x00 -> "Read/Write"
                        0x0F -> "Read-Only"
                        else -> "0x%02X".format(cc[3].toInt() and 0xFF)
                    }
                    lockRows += DetailRow("NDEF CC (p3)", "v$verMajor.$verMinor · $areaBytes B · $access")
                }
            }

            if (cfgPages != null && cfgPages.size == 16) {
                val auth0 = cfgPages[3].toInt() and 0xFF
                val access = cfgPages[4].toInt() and 0xFF
                val prot = (access and 0x80) != 0
                val cfgLck = (access and 0x40) != 0
                val nfcCntEn = (access and 0x10) != 0
                val authLim = access and 0x07

                lockRows += DetailRow(
                    "Password (AUTH0)",
                    if (auth0 >= pageCount) "Disabled (0x%02X)".format(auth0)
                    else "Active from page $auth0 (0x%02X)".format(auth0),
                )
                lockRows += DetailRow(
                    "Protection (ACCESS)",
                    buildString {
                        append(if (prot) "Read & Write" else "Write only")
                        append(if (cfgLck) " · Config LOCKED" else " · Config open")
                        append(if (authLim == 0) " · Unlimited tries" else " · Limit $authLim tries")
                        if (version.family == TagFamily.NTAG && nfcCntEn) append(" · Counter ON")
                    },
                )
            } else {
                lockRows += DetailRow("Configuration", "Password-protected or unreadable")
            }

            // 4. Quick user-memory read for instant NDEF decoding (up to 64 pages = 256 B).
            val quickPages = attempt(abort) {
                PageMemoryReader.read(transport, maxBlocks = minOf(pageCount, 64), abort = abort)
            }
            val content = quickPages?.takeIf { it.blockCount >= 4 }?.let { mem ->
                TagContentAnalyzer.pages(mem.pages, context.snapshot.uidHex)
            }

            if (lockRows.isNotEmpty()) {
                sections += DetailSection("Security & Configuration", rows = lockRows)
            }
            if (counterRows.isNotEmpty()) {
                sections += DetailSection("Counters", rows = counterRows)
            }
            if (sigRows.isNotEmpty()) {
                sections += DetailSection("Originality", rows = sigRows)
            }

            val baseIdentity = TagIdentifier.identify(context.snapshot, null)
            ProbeOutcome(
                identity = baseIdentity,
                details = GenericDetails(sections = sections),
                content = content,
            )
        }
    }

    private fun read4(transport: RawTagTransport, startPage: Int): ByteArray? {
        val resp = transport.transceive(byteArrayOf(CMD_READ.toByte(), startPage.toByte()))
        return resp.takeIf { it.size == 16 }
    }

    private fun readSignature(transport: RawTagTransport): ByteArray? {
        val resp = transport.transceive(byteArrayOf(CMD_READ_SIG.toByte(), 0x00))
        return when {
            resp.size >= 48 -> resp.copyOf(48)
            resp.size >= 32 -> resp.copyOf(32)
            else -> null
        }
    }

    private fun readCounter(transport: RawTagTransport, index: Int): ByteArray? {
        val resp = transport.transceive(byteArrayOf(CMD_READ_CNT.toByte(), index.toByte()))
        return resp.takeIf { it.size >= 3 }?.copyOf(3)
    }

    private fun readTearing(transport: RawTagTransport, index: Int): Int? {
        val resp = transport.transceive(byteArrayOf(CMD_READ_TEARING.toByte(), index.toByte()))
        return resp.firstOrNull()?.toInt()?.and(0xFF)
    }

    private inline fun <T> attempt(abort: (Throwable) -> Boolean, block: () -> T?): T? =
        try {
            block()
        } catch (e: IOException) {
            if (abort(e)) throw e
            null
        }
}
