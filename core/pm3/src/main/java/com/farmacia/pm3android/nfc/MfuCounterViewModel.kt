// State holder for the Ultralight EV1 counter overlay.
//
// The screen only renders state and keeps reader mode on while the tag is
// waited for or written. The card commands live in [MfuCounterTool]; this class
// validates the form, remembers the requested target across the second tap and
// turns engine exceptions into localised messages.
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

enum class MfuCounterStage {
    /** Form with counter, target and optional password. */
    SETUP,

    /** Reader mode is active, waiting for the tag. */
    WAITING,

    /** The tag is being written and verified. */
    WORKING,

    /** Operation completed. */
    DONE,
}

data class MfuCounterState(
    val visible: Boolean = false,
    val stage: MfuCounterStage = MfuCounterStage.SETUP,
    val counterIndex: Int = 0,
    val target: String = "",
    val password: String = "",
    val error: String? = null,
    val result: MfuCounterChange? = null,
    val uidHex: String = "",
) {
    val readerActive: Boolean
        get() = visible && (stage == MfuCounterStage.WAITING || stage == MfuCounterStage.WORKING)

    val busy: Boolean get() = stage == MfuCounterStage.WORKING
}

class MfuCounterViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(MfuCounterState())
    val state: StateFlow<MfuCounterState> = stateFlow

    private var pendingTarget = 0
    private var pendingPassword: ByteArray? = null

    fun start() {
        pendingTarget = 0
        pendingPassword = null
        stateFlow.value = MfuCounterState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        pendingTarget = 0
        pendingPassword = null
        stateFlow.value = MfuCounterState()
    }

    fun setCounterIndex(index: Int) {
        if (index !in 0..2 || stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(counterIndex = index, error = null)
    }

    fun setTarget(text: String) {
        if (stateFlow.value.busy) return
        val digits = text.filter { it.isDigit() }.take(8)
        stateFlow.value = stateFlow.value.copy(target = digits, error = null)
    }

    fun setPassword(text: String) {
        if (stateFlow.value.busy) return
        val hex = text.filter { it.isLetterOrDigit() }
            .uppercase()
            .take(8)
        stateFlow.value = stateFlow.value.copy(password = hex, error = null)
    }

    /** Validates the form and starts waiting for the tag. */
    fun startWaiting(): Boolean {
        val current = stateFlow.value
        if (current.busy || current.stage == MfuCounterStage.WAITING) return false
        val target = current.target.toIntOrNull()
        if (target == null || target !in 0..COUNTER_MAX) {
            stateFlow.value = current.copy(error = text(R.string.counter_error_target))
            return false
        }
        val password = if (current.password.isBlank()) {
            null
        } else {
            parsePassword(current.password) ?: run {
                stateFlow.value = current.copy(error = text(R.string.counter_error_password_format))
                return false
            }
        }
        pendingTarget = target
        pendingPassword = password
        stateFlow.value = current.copy(
            stage = MfuCounterStage.WAITING,
            error = null,
            result = null,
            uidHex = "",
        )
        return true
    }

    fun cancelWaiting() {
        if (stateFlow.value.stage != MfuCounterStage.WAITING) return
        pendingTarget = 0
        pendingPassword = null
        stateFlow.value = stateFlow.value.copy(stage = MfuCounterStage.SETUP)
    }

    /** Returns to the form after a completed operation. */
    fun again() {
        if (stateFlow.value.busy) return
        pendingTarget = 0
        pendingPassword = null
        stateFlow.value = stateFlow.value.copy(
            stage = MfuCounterStage.SETUP,
            target = "",
            error = null,
            result = null,
            uidHex = "",
        )
    }

    /** Called by the screen when Android discovers the tag. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (!current.visible || current.stage != MfuCounterStage.WAITING) return
        val target = pendingTarget
        val password = pendingPassword
        stateFlow.value = current.copy(stage = MfuCounterStage.WORKING, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val transport = AndroidUltralightTransport(tag)
                    try {
                        MfuCounterTool(transport).setCounter(
                            index = current.counterIndex,
                            target = target,
                            password = password,
                        )
                    } finally {
                        transport.close()
                    }
                }
                if (!stateFlow.value.visible) return@launch
                stateFlow.value = stateFlow.value.copy(
                    stage = MfuCounterStage.DONE,
                    result = result,
                    uidHex = tag.id.toHex(),
                    error = null,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                fail(R.string.counter_error_lost)
            } catch (t: TagAuthException) {
                fail(R.string.counter_error_password)
            } catch (t: MfuCounterTargetException) {
                fail(R.string.counter_error_low, t.current)
            } catch (t: MfuCounterTearingException) {
                fail(R.string.counter_error_tearing)
            } catch (t: MfuCounterUnsupportedException) {
                fail(R.string.counter_error_unsupported)
            } catch (t: MfuCounterNotSupportedException) {
                fail(R.string.counter_error_not_supported)
            } catch (t: MfuCounterVerifyException) {
                fail(R.string.counter_error_verify)
            } catch (t: Throwable) {
                fail(R.string.counter_error_write, errorText(t))
            }
        }
    }

    private fun fail(message: Int, vararg args: Any) {
        if (!stateFlow.value.visible) return
        stateFlow.value = stateFlow.value.copy(
            stage = MfuCounterStage.SETUP,
            error = getApplication<Application>().getString(message, *args),
        )
    }

    private fun text(message: Int): String =
        getApplication<Application>().getString(message)

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    /** Four bytes from eight hex digits, or null when the field is malformed. */
    private fun parsePassword(value: String): ByteArray? {
        if (value.length != 8) return null
        val out = ByteArray(4)
        for (index in out.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private companion object {
        const val COUNTER_MAX = 0xFFFFFF
    }
}
