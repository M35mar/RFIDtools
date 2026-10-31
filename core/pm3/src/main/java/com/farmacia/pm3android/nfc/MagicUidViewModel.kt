// State holder for the "change UID" overlay.
//
// The user types the new UID and the Classic key, confirms the irreversible
// operation, then presents the card once. The Android writer detects the card
// family, writes and verifies it; this class only validates the form and turns
// the typed exceptions into localised messages.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.nfc.Tag
import android.nfc.TagLostException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MagicUidStage {
    /** UID/key form. */
    SETUP,

    /** Reader mode is active, waiting for the card. */
    WAITING,

    /** The card is being written and verified. */
    WORKING,

    /** The operation finished. */
    DONE,
}

data class MagicUidState(
    val visible: Boolean = false,
    val stage: MagicUidStage = MagicUidStage.SETUP,
    val uid: String = "",
    val key: String = DEFAULT_CLASSIC_KEY,
    /** Confirmation dialog is open before entering WAITING. */
    val confirming: Boolean = false,
    val error: String? = null,
    val result: MagicUidResult? = null,
) {
    val readerActive: Boolean
        get() = visible && (stage == MagicUidStage.WAITING || stage == MagicUidStage.WORKING)

    val busy: Boolean get() = stage == MagicUidStage.WORKING

    val canStart: Boolean get() = uid.isNotBlank() && key.isNotBlank() && !busy

    companion object {
        const val DEFAULT_CLASSIC_KEY = "FFFFFFFFFFFF"
    }
}

class MagicUidViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(MagicUidState())
    val state: StateFlow<MagicUidState> = stateFlow

    private var pendingUid: ByteArray? = null
    private var pendingKey: ByteArray? = null

    fun start() {
        pendingUid = null
        pendingKey = null
        stateFlow.value = MagicUidState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        pendingUid = null
        pendingKey = null
        stateFlow.value = MagicUidState()
    }

    fun setUid(text: String) {
        if (stateFlow.value.busy) return
        val hex = text.filter { it.isHexDigit() }.uppercase().take(14)
        stateFlow.value = stateFlow.value.copy(uid = hex, error = null)
    }

    fun setKey(text: String) {
        if (stateFlow.value.busy) return
        val hex = text.filter { it.isHexDigit() }.uppercase().take(12)
        stateFlow.value = stateFlow.value.copy(key = hex, error = null)
    }

    fun useDefaultKey() {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(
            key = MagicUidState.DEFAULT_CLASSIC_KEY,
            error = null,
        )
    }

    /**
     * Validates the form and opens the confirmation dialog. Returns false and
     * fills [MagicUidState.error] when something is missing or malformed.
     */
    fun requestWrite(): Boolean {
        val current = stateFlow.value
        if (current.busy || current.stage != MagicUidStage.SETUP) return false
        if (MagicUidLayout.parseUid(current.uid) == null) {
            stateFlow.value = current.copy(error = text(R.string.uid_error_uid_format))
            return false
        }
        if (MagicUidLayout.parseKey(current.key) == null) {
            stateFlow.value = current.copy(error = text(R.string.uid_error_key_format))
            return false
        }
        stateFlow.value = current.copy(confirming = true, error = null)
        return true
    }

    fun cancelConfirm() {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(confirming = false)
    }

    /** Confirms the dialog and starts reader mode. */
    fun startWaiting(): Boolean {
        val current = stateFlow.value
        if (current.busy) return false
        val uid = MagicUidLayout.parseUid(current.uid) ?: run {
            stateFlow.value = current.copy(
                confirming = false,
                error = text(R.string.uid_error_uid_format),
            )
            return false
        }
        val key = MagicUidLayout.parseKey(current.key) ?: run {
            stateFlow.value = current.copy(
                confirming = false,
                error = text(R.string.uid_error_key_format),
            )
            return false
        }
        pendingUid = uid
        pendingKey = key
        stateFlow.value = current.copy(
            stage = MagicUidStage.WAITING,
            confirming = false,
            error = null,
            result = null,
        )
        return true
    }

    fun cancelWaiting() {
        if (stateFlow.value.stage != MagicUidStage.WAITING) return
        pendingUid = null
        pendingKey = null
        stateFlow.value = stateFlow.value.copy(stage = MagicUidStage.SETUP)
    }

    /** Returns to the form after a completed operation. */
    fun again() {
        if (stateFlow.value.busy) return
        pendingUid = null
        pendingKey = null
        stateFlow.value = stateFlow.value.copy(
            stage = MagicUidStage.SETUP,
            uid = "",
            key = MagicUidState.DEFAULT_CLASSIC_KEY,
            confirming = false,
            error = null,
            result = null,
        )
    }

    /** Called by the screen when Android discovers the card. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (!current.visible || current.stage != MagicUidStage.WAITING) return
        val uid = pendingUid ?: return
        val key = pendingKey ?: return
        if (tag.id.size != uid.size) {
            fail(R.string.uid_error_length, tag.id.size, uid.size)
            return
        }

        val family = try {
            MagicUidAndroid.detectFamily(tag)
        } catch (_: Throwable) {
            null
        }
        if (family == null) {
            fail(R.string.uid_error_unsupported)
            return
        }

        stateFlow.value = current.copy(stage = MagicUidStage.WORKING, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MagicUidAndroid.write(tag, family, uid, key)
                }
                if (!stateFlow.value.visible) return@launch
                pendingUid = null
                pendingKey = null
                stateFlow.value = stateFlow.value.copy(
                    stage = MagicUidStage.DONE,
                    result = result,
                    error = null,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                fail(R.string.uid_error_lost)
            } catch (t: MagicUidLengthException) {
                fail(R.string.uid_error_length, t.cardBytes, t.typedBytes)
            } catch (t: MagicUidKeyException) {
                fail(R.string.uid_error_key)
            } catch (t: MagicUidUnsupportedException) {
                fail(R.string.uid_error_unsupported)
            } catch (t: MagicUidReadException) {
                fail(R.string.uid_error_read)
            } catch (t: MagicUidWriteException) {
                fail(R.string.uid_error_write)
            } catch (t: MagicUidVerifyException) {
                fail(R.string.uid_error_verify)
            } catch (t: Throwable) {
                fail(R.string.uid_error_generic, errorText(t))
            }
        }
    }

    private fun fail(message: Int, vararg args: Any) {
        if (!stateFlow.value.visible) return
        pendingUid = null
        pendingKey = null
        stateFlow.value = stateFlow.value.copy(
            stage = MagicUidStage.SETUP,
            confirming = false,
            error = getApplication<Application>().getString(message, *args),
            result = null,
        )
    }

    private fun text(message: Int): String =
        getApplication<Application>().getString(message)

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
