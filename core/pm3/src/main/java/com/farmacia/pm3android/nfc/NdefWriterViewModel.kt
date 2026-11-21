// ViewModel for the phone-NFC NDEF Writer / Formatter tool.
//
// Supports writing URI, Text, Wi-Fi (WSC) and empty/blank NDEF messages onto
// any Android NDEF or NdefFormatable tag (NTAG21x, Ultralight, DESFire Type 4,
// ISO 15693 Type 5, FeliCa Type 3, MIFARE Classic), with read-back verification.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NdefWriteStage {
    EDIT,
    WAITING,
    WRITING,
    DONE,
    ERROR,
}

enum class NdefWriteError {
    TAG_LOST,
    READ_ONLY,
    NOT_SUPPORTED,
    TOO_LARGE,
    VERIFY_FAILED,
    WRITE_FAILED,
}

data class NdefWriterState(
    val visible: Boolean = false,
    val stage: NdefWriteStage = NdefWriteStage.EDIT,
    val kind: NdefWriteKind = NdefWriteKind.URI,
    val uri: String = "https://",
    val text: String = "",
    val ssid: String = "",
    val wifiPassword: String = "",
    val wifiAuth: WifiAuth = WifiAuth.WPA2_PERSONAL,
    val uidHex: String = "",
    val bytesWritten: Int = 0,
    val verified: Boolean = false,
    val maxBytes: Int = 0,
    val error: NdefWriteError? = null,
) {
    val readerActive: Boolean
        get() = visible && (stage == NdefWriteStage.WAITING || stage == NdefWriteStage.WRITING)

    val busy: Boolean
        get() = stage == NdefWriteStage.WRITING

    val canPrepare: Boolean
        get() = when (kind) {
            NdefWriteKind.URI -> uri.trim().length > 4
            NdefWriteKind.TEXT -> text.isNotBlank()
            NdefWriteKind.WIFI -> ssid.isNotBlank() &&
                (wifiAuth == WifiAuth.OPEN || wifiPassword.isNotEmpty())
            NdefWriteKind.ERASE -> true
        }
}

class NdefWriterViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(NdefWriterState())
    val state: StateFlow<NdefWriterState> = stateFlow

    fun start() {
        stateFlow.value = NdefWriterState(visible = true, stage = NdefWriteStage.EDIT)
    }

    fun close() {
        if (stateFlow.value.busy) return
        stateFlow.value = NdefWriterState()
    }

    fun setKind(kind: NdefWriteKind) {
        stateFlow.value = stateFlow.value.copy(kind = kind, error = null)
    }

    fun setUri(uri: String) {
        stateFlow.value = stateFlow.value.copy(uri = uri, error = null)
    }

    fun setText(text: String) {
        stateFlow.value = stateFlow.value.copy(text = text, error = null)
    }

    fun setSsid(ssid: String) {
        stateFlow.value = stateFlow.value.copy(ssid = ssid, error = null)
    }

    fun setWifiPassword(password: String) {
        stateFlow.value = stateFlow.value.copy(wifiPassword = password, error = null)
    }

    fun setWifiAuth(auth: WifiAuth) {
        stateFlow.value = stateFlow.value.copy(wifiAuth = auth, error = null)
    }

    fun armWrite() {
        if (!stateFlow.value.canPrepare) return
        stateFlow.value = stateFlow.value.copy(
            stage = NdefWriteStage.WAITING,
            error = null,
            verified = false,
            uidHex = "",
        )
    }

    fun backToEdit() {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(
            stage = NdefWriteStage.EDIT,
            error = null,
        )
    }

    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (current.stage != NdefWriteStage.WAITING) return
        val message = runCatching { buildMessage(current) }.getOrNull() ?: return
        val messageBytes = message.toByteArray()
        val uid = (tag.id ?: ByteArray(0)).toHexSpaced()

        stateFlow.value = current.copy(
            stage = NdefWriteStage.WRITING,
            uidHex = uid,
            error = null,
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    writeAndVerify(tag, message, messageBytes)
                }
                stateFlow.value = stateFlow.value.copy(
                    stage = NdefWriteStage.DONE,
                    bytesWritten = messageBytes.size,
                    maxBytes = result.maxBytes,
                    verified = result.verified,
                    error = null,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: NdefWriteFailure) {
                stateFlow.value = stateFlow.value.copy(
                    stage = NdefWriteStage.ERROR,
                    maxBytes = t.maxBytes,
                    bytesWritten = messageBytes.size,
                    error = t.code,
                )
            } catch (t: TagLostException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = NdefWriteStage.ERROR,
                    error = NdefWriteError.TAG_LOST,
                )
            } catch (_: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = NdefWriteStage.ERROR,
                    error = NdefWriteError.WRITE_FAILED,
                )
            }
        }
    }

    private fun buildMessage(state: NdefWriterState): NdefMessage {
        val record = when (state.kind) {
            NdefWriteKind.URI -> NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_URI,
                ByteArray(0),
                NdefBuilder.buildUriPayload(state.uri),
            )
            NdefWriteKind.TEXT -> NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_TEXT,
                ByteArray(0),
                NdefBuilder.buildTextPayload(state.text),
            )
            NdefWriteKind.WIFI -> NdefRecord.createMime(
                NdefBuilder.MIME_WIFI_WSC,
                NdefBuilder.buildWifiWscPayload(state.ssid, state.wifiPassword, state.wifiAuth),
            )
            NdefWriteKind.ERASE -> NdefRecord(
                NdefRecord.TNF_EMPTY,
                ByteArray(0),
                ByteArray(0),
                ByteArray(0),
            )
        }
        return NdefMessage(arrayOf(record))
    }

    private class WriteResult(val verified: Boolean, val maxBytes: Int)

    private class NdefWriteFailure(val code: NdefWriteError, val maxBytes: Int = 0) : Exception()

    private fun writeAndVerify(tag: Tag, message: NdefMessage, expectedBytes: ByteArray): WriteResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) {
                    throw NdefWriteFailure(NdefWriteError.READ_ONLY, ndef.maxSize)
                }
                if (ndef.maxSize > 0 && expectedBytes.size > ndef.maxSize) {
                    throw NdefWriteFailure(NdefWriteError.TOO_LARGE, ndef.maxSize)
                }
                ndef.writeNdefMessage(message)
                // Read-back verification: confirm what is physically on the tag.
                val readBack = runCatching { ndef.ndefMessage?.toByteArray() }.getOrNull()
                val matches = readBack != null && readBack.contentEquals(expectedBytes)
                if (!matches) {
                    throw NdefWriteFailure(NdefWriteError.VERIFY_FAILED, ndef.maxSize)
                }
                return WriteResult(verified = true, maxBytes = ndef.maxSize)
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: throw NdefWriteFailure(NdefWriteError.NOT_SUPPORTED)
        formatable.connect()
        try {
            formatable.format(message)
        } finally {
            runCatching { formatable.close() }
        }
        // Verify after formatting via Ndef if Android exposes it immediately.
        val after = Ndef.get(tag)
        var verified = false
        var maxSize = 0
        if (after != null) {
            runCatching {
                after.connect()
                try {
                    maxSize = after.maxSize
                    val readBack = after.ndefMessage?.toByteArray()
                    verified = readBack != null && readBack.contentEquals(expectedBytes)
                } finally {
                    runCatching { after.close() }
                }
            }
        }
        return WriteResult(verified = verified, maxBytes = maxSize)
    }
}
