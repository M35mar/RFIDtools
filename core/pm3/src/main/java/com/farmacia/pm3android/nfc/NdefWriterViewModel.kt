// ViewModel for the phone-NFC NDEF Writer / Formatter Pro tool.
//
// Supports URI (https, tel:, mailto:, geo:), UTF-8 Text, Wi-Fi (WSC), vCard 3.0,
// Android App Launcher (AAR), Bluetooth OOB pairing, and Erase on any NFC Forum
// Type 1-5 tag, with mandatory read-back verification.
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
    val vcardName: String = "",
    val vcardPhone: String = "",
    val vcardEmail: String = "",
    val vcardOrg: String = "",
    val appPackage: String = "",
    val btMac: String = "",
    val btName: String = "",
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
            NdefWriteKind.VCARD -> vcardName.isNotBlank()
            NdefWriteKind.APP_LAUNCHER -> appPackage.trim().contains('.')
            NdefWriteKind.BLUETOOTH -> btMac.hexToBytesOrNull()?.size == 6
            NdefWriteKind.ERASE -> true
        }
}

class NdefWriterViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(NdefWriterState())
    val state: StateFlow<NdefWriterState> = stateFlow

    fun start() {
        stateFlow.value = NdefWriterState(visible = true, stage = NdefWriteStage.EDIT)
    }

    fun startWithTextOrUri(content: String, isUri: Boolean) {
        stateFlow.value = if (isUri) {
            NdefWriterState(visible = true, kind = NdefWriteKind.URI, uri = content)
        } else {
            NdefWriterState(visible = true, kind = NdefWriteKind.TEXT, text = content)
        }
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

    fun setVcardName(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardName = v, error = null)
    }

    fun setVcardPhone(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardPhone = v, error = null)
    }

    fun setVcardEmail(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardEmail = v, error = null)
    }

    fun setVcardOrg(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardOrg = v, error = null)
    }

    fun setAppPackage(v: String) {
        stateFlow.value = stateFlow.value.copy(appPackage = v, error = null)
    }

    fun setBtMac(v: String) {
        stateFlow.value = stateFlow.value.copy(btMac = v, error = null)
    }

    fun setBtName(v: String) {
        stateFlow.value = stateFlow.value.copy(btName = v, error = null)
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
            } catch (_: TagLostException) {
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
            NdefWriteKind.VCARD -> NdefRecord.createMime(
                NdefBuilder.MIME_VCARD,
                NdefBuilder.buildVCardPayload(
                    name = state.vcardName,
                    phone = state.vcardPhone,
                    email = state.vcardEmail,
                    org = state.vcardOrg,
                ),
            )
            NdefWriteKind.APP_LAUNCHER -> NdefRecord.createApplicationRecord(state.appPackage.trim())
            NdefWriteKind.BLUETOOTH -> NdefRecord.createMime(
                NdefBuilder.MIME_BT_OOB,
                NdefBuilder.buildBluetoothOobPayload(state.btMac, state.btName),
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
