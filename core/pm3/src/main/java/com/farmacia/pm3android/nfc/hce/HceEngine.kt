// Pure-Kotlin Host Card Emulation (HCE) engine:
// 1. NFC Forum Type 4 Tag Emulator (SELECT AID D2760000850101, SELECT CC E103,
//    SELECT NDEF E104, READ BINARY 00B0)
// 2. Custom APDU Responder (longest hex-prefix match -> configured response)
// 3. Thread-safe HceRegistry singleton holding live APDU exchange logs and
//    Android 15+ (API 35+) reader PollingFrame sniffer events.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.hce

import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.nfc.toHex
import com.farmacia.pm3android.nfc.toHexSpaced
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

enum class HceMode {
    TYPE4_NDEF,
    CUSTOM_APDU,
    POLLING_SNIFFER,
}

data class ApduRule(
    val label: String,
    val commandPrefixHex: String,
    val responseHex: String,
)

data class HceConfig(
    val enabled: Boolean = false,
    val mode: HceMode = HceMode.TYPE4_NDEF,
    val ndefMessageBytes: ByteArray = ByteArray(0),
    val rules: List<ApduRule> = defaultRules(),
) {
    companion object {
        fun defaultRules(): List<ApduRule> = listOf(
            ApduRule("SELECT NDEF v2", "00A4040007D2760000850101", "9000"),
            ApduRule("GET UID (PC/SC)", "FFCA000000", "041122334455669000"),
        )
    }
}

data class HceApduEvent(
    val id: Long,
    val timestampMs: Long,
    val commandHex: String,
    val responseHex: String,
    val note: String,
)

data class HcePollingEvent(
    val id: Long,
    val timestampUs: Long,
    val deltaUs: Long,
    val frameType: String,
    val dataHex: String,
    val gain: Int,
    val annotation: String,
)

/**
 * Stateful ISO 7816-4 / NFC Forum Type 4 Tag v2.0 read-only emulator.
 */
class Type4TagEmulator(ndefMessage: ByteArray) {

    private enum class SelectedFile { NONE, CC, NDEF }

    private var appSelected = false
    private var selectedFile = SelectedFile.NONE

    private val ndefFile: ByteArray = buildNdefFile(ndefMessage)
    private val ccFile: ByteArray = buildCcFile(maxNdefSize = maxOf(2048, ndefFile.size))

    fun reset() {
        appSelected = false
        selectedFile = SelectedFile.NONE
    }

    data class Reply(val bytes: ByteArray, val note: String)

    fun process(apdu: ByteArray): Reply {
        if (apdu.size < 4) return Reply(SW_WRONG_LENGTH, "APDU < 4B")
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        if (cla != 0x00) return Reply(SW_CLA_NOT_SUPPORTED, "CLA != 00")

        return when (ins) {
            0xA4 -> handleSelect(apdu, p1, p2)
            0xB0 -> handleReadBinary(apdu, p1, p2)
            0xD6 -> Reply(SW_CONDITIONS_NOT_SATISFIED, "UPDATE BINARY (Read-Only)")
            else -> Reply(SW_INS_NOT_SUPPORTED, "INS 0x%02X".format(ins))
        }
    }

    private fun handleSelect(apdu: ByteArray, p1: Int, p2: Int): Reply {
        if (apdu.size < 5) return Reply(SW_WRONG_LENGTH, "SELECT senza Lc")
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return Reply(SW_WRONG_LENGTH, "SELECT Lc errato")
        val data = apdu.copyOfRange(5, 5 + lc)

        // SELECT BY DF NAME (AID)
        if (p1 == 0x04) {
            if (data.contentEquals(AID_NDEF_V2) || data.contentEquals(AID_NDEF_V1)) {
                appSelected = true
                selectedFile = SelectedFile.NONE
                return Reply(SW_OK, "SELECT NDEF Tag App")
            }
            return Reply(SW_FILE_NOT_FOUND, "AID sconosciuto (${data.toHex()})")
        }

        // SELECT Elementary File (CC E103 or NDEF E104)
        if (p1 == 0x00 && (p2 == 0x0C || p2 == 0x00) && lc == 2) {
            if (!appSelected) return Reply(SW_FILE_NOT_FOUND, "App non selezionata")
            val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return when (fid) {
                0xE103 -> {
                    selectedFile = SelectedFile.CC
                    Reply(SW_OK, "SELECT CC (E103)")
                }
                0xE104 -> {
                    selectedFile = SelectedFile.NDEF
                    Reply(SW_OK, "SELECT NDEF (E104)")
                }
                else -> Reply(SW_FILE_NOT_FOUND, "EF 0x%04X non trovato".format(fid))
            }
        }

        return Reply(SW_INCORRECT_P1P2, "SELECT P1/P2 non supportati")
    }

    private fun handleReadBinary(apdu: ByteArray, p1: Int, p2: Int): Reply {
        if ((p1 and 0x80) != 0) return Reply(SW_INCORRECT_P1P2, "SFI non supportato")
        val fileBytes = when (selectedFile) {
            SelectedFile.CC -> ccFile
            SelectedFile.NDEF -> ndefFile
            SelectedFile.NONE -> return Reply(SW_CONDITIONS_NOT_SATISFIED, "Nessun EF selezionato")
        }
        val offset = (p1 shl 8) or p2
        if (offset > fileBytes.size) return Reply(SW_WRONG_OFFSET, "Offset > EOF")
        val leRaw = if (apdu.size >= 5) apdu[apdu.lastIndex].toInt() and 0xFF else 0
        val requested = if (leRaw == 0) 256 else leRaw
        val available = (fileBytes.size - offset).coerceAtLeast(0)
        val count = minOf(requested, available)
        val slice = fileBytes.copyOfRange(offset, offset + count)
        val label = if (selectedFile == SelectedFile.CC) "CC" else "NDEF"
        return Reply(slice + SW_OK, "READ $label [$offset..${offset + count})")
    }

    companion object {
        val AID_NDEF_V2 = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val AID_NDEF_V1 = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x00)
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)
        val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        val SW_INCORRECT_P1P2 = byteArrayOf(0x6A.toByte(), 0x86.toByte())
        val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        val SW_CLA_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00)

        private fun buildNdefFile(ndefMessage: ByteArray): ByteArray {
            val len = ndefMessage.size.coerceAtMost(0xFFFE)
            val out = ByteArray(2 + len)
            out[0] = ((len ushr 8) and 0xFF).toByte()
            out[1] = (len and 0xFF).toByte()
            ndefMessage.copyInto(out, destinationOffset = 2, endIndex = len)
            return out
        }

        private fun buildCcFile(maxNdefSize: Int): ByteArray = byteArrayOf(
            0x00, 0x0F, // CCLEN = 15
            0x20, // Mapping Version 2.0
            0x01, 0x00, // MLe = 256 bytes per READ BINARY
            0x00, 0xFF.toByte(), // MLc = 255 bytes
            0x04, // NDEF File Control TLV tag
            0x06, // TLV length = 6
            0xE1.toByte(), 0x04, // NDEF File ID = E104
            ((maxNdefSize ushr 8) and 0xFF).toByte(),
            (maxNdefSize and 0xFF).toByte(),
            0x00, // Read access: free
            0xFF.toByte(), // Write access: read-only
        )
    }
}

object CustomApduMatcher {
    fun match(apdu: ByteArray, rules: List<ApduRule>): Pair<ByteArray, String> {
        val hex = apdu.toHex().uppercase()
        val best = rules
            .filter { rule ->
                val cleanPrefix = rule.commandPrefixHex.filter { !it.isWhitespace() }.uppercase()
                cleanPrefix.isNotEmpty() && hex.startsWith(cleanPrefix)
            }
            .maxByOrNull { it.commandPrefixHex.length }
        if (best != null) {
            val resp = best.responseHex.hexToBytesOrNull()
                ?: Type4TagEmulator.SW_OK
            return resp to "Rule: ${best.label}"
        }
        return Type4TagEmulator.SW_FILE_NOT_FOUND to "Nessuna regola (6A82)"
    }
}

/**
 * Process-wide state bridge between [Pm3HostApduService] and the Compose UI.
 */
object HceRegistry {

    private const val MAX_EVENTS = 200

    private val nextId = AtomicLong(1L)
    private val lock = Any()

    private val configFlow = MutableStateFlow(HceConfig())
    val config: StateFlow<HceConfig> = configFlow

    private val apduLogFlow = MutableStateFlow<List<HceApduEvent>>(emptyList())
    val apduLog: StateFlow<List<HceApduEvent>> = apduLogFlow

    private val pollingLogFlow = MutableStateFlow<List<HcePollingEvent>>(emptyList())
    val pollingLog: StateFlow<List<HcePollingEvent>> = pollingLogFlow

    private val sessionCountFlow = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = sessionCountFlow

    private var emulator = Type4TagEmulator(ByteArray(0))
    private var lastPollingTimestampUs: Long = 0L

    fun updateConfig(newConfig: HceConfig) {
        synchronized(lock) {
            val ndefChanged = !configFlow.value.ndefMessageBytes.contentEquals(newConfig.ndefMessageBytes)
            configFlow.value = newConfig
            if (ndefChanged || !newConfig.enabled) {
                emulator = Type4TagEmulator(newConfig.ndefMessageBytes)
            }
        }
    }

    fun clearLogs() {
        synchronized(lock) {
            apduLogFlow.value = emptyList()
            pollingLogFlow.value = emptyList()
            sessionCountFlow.value = 0
            lastPollingTimestampUs = 0L
        }
    }

    fun onDeactivated() {
        synchronized(lock) {
            emulator.reset()
        }
    }

    fun handleCommandApdu(commandApdu: ByteArray): ByteArray {
        synchronized(lock) {
            val cfg = configFlow.value
            if (!cfg.enabled) {
                return Type4TagEmulator.SW_FILE_NOT_FOUND
            }
            val (response, note) = when (cfg.mode) {
                HceMode.TYPE4_NDEF -> {
                    val reply = emulator.process(commandApdu)
                    if (reply.note.startsWith("SELECT NDEF Tag App")) {
                        sessionCountFlow.value += 1
                    }
                    reply.bytes to reply.note
                }
                HceMode.CUSTOM_APDU -> {
                    sessionCountFlow.value += 1
                    CustomApduMatcher.match(commandApdu, cfg.rules)
                }
                HceMode.POLLING_SNIFFER -> {
                    Type4TagEmulator.SW_FILE_NOT_FOUND to "Observe/Sniffer attivo (6A82)"
                }
            }
            val entry = HceApduEvent(
                id = nextId.getAndIncrement(),
                timestampMs = System.currentTimeMillis(),
                commandHex = commandApdu.toHexSpaced(),
                responseHex = response.toHexSpaced(),
                note = note,
            )
            apduLogFlow.value = (listOf(entry) + apduLogFlow.value).take(MAX_EVENTS)
            return response
        }
    }

    fun recordPollingFrame(
        frameType: String,
        data: ByteArray,
        gain: Int,
        timestampUs: Long,
    ) {
        synchronized(lock) {
            val delta = if (lastPollingTimestampUs > 0L && timestampUs >= lastPollingTimestampUs) {
                timestampUs - lastPollingTimestampUs
            } else {
                0L
            }
            lastPollingTimestampUs = timestampUs
            val hex = data.toHexSpaced()
            val annotation = annotatePollingPayload(frameType, data)
            val entry = HcePollingEvent(
                id = nextId.getAndIncrement(),
                timestampUs = timestampUs,
                deltaUs = delta,
                frameType = frameType,
                dataHex = hex.ifEmpty { "—" },
                gain = gain,
                annotation = annotation,
            )
            pollingLogFlow.value = (listOf(entry) + pollingLogFlow.value).take(MAX_EVENTS)
        }
    }

    fun annotatePollingPayload(frameType: String, data: ByteArray): String {
        if (data.isEmpty()) return frameType
        val b0 = data[0].toInt() and 0xFF
        return when {
            b0 == 0x6A && data.size >= 2 -> {
                val sub = when (data[1].toInt() and 0xFF) {
                    0x01 -> "Transit"
                    0x02 -> "Access / HomeKey / CarKey"
                    else -> "v${data[1].toInt() and 0xFF}"
                }
                "Apple ECP ($sub)"
            }
            data.size == 1 && b0 == 0x26 -> "ISO 14443-A REQA"
            data.size == 1 && b0 == 0x52 -> "ISO 14443-A WUPA"
            b0 == 0x05 && data.size >= 3 -> "ISO 14443-B REQB/WUPB"
            data.size >= 5 && (data[1].toInt() and 0xFF) == 0x00 -> "FeliCa SENSF_REQ"
            else -> frameType
        }
    }
}
