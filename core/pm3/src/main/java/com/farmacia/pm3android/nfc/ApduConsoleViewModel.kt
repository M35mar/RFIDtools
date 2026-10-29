// APDU console: keeps one ISO-DEP connection open while the overlay is shown,
// sends the command typed by the user and logs the answer with its status word.
// It is the escape hatch for applets the automatic probes do not know.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.nfc.Tag
import android.nfc.TagLostException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.nfc.inspect.ApduParseException
import com.farmacia.pm3android.nfc.inspect.Iso7816
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ApduError {
    NO_TAG,
    TAG_LOST,
    PARSE,
    TRANSCEIVE,
}

class ApduExchange(
    val command: ByteArray,
    val response: ByteArray?,
    val sw: Int?,
    val error: String? = null,
)

data class ApduConsoleState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val hasTag: Boolean = false,
    val error: ApduError? = null,
    val exchanges: List<ApduExchange> = emptyList(),
) {
    val readerActive: Boolean get() = visible
}

class ApduConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(ApduConsoleState())

    val state: StateFlow<ApduConsoleState> = stateFlow

    private var transport: AndroidIsoDepTransport? = null

    fun start() {
        closeTransport()
        stateFlow.value = ApduConsoleState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        closeTransport()
        stateFlow.value = ApduConsoleState()
    }

    fun clearLog() {
        stateFlow.value = stateFlow.value.copy(exchanges = emptyList())
    }

    /** Called by reader mode; the first tag opens the ISO-DEP connection. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (!current.visible || current.busy || transport != null) return
        stateFlow.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                try {
                    AndroidIsoDepTransport(tag).also { transport = it }
                } catch (_: Throwable) {
                    null
                }
            }
            val latest = stateFlow.value
            if (!latest.visible) {
                opened?.close()
                transport = null
                return@launch
            }
            stateFlow.value = latest.copy(
                busy = false,
                hasTag = opened != null,
                error = if (opened == null) ApduError.NO_TAG else null,
            )
        }
    }

    /**
     * Sends the typed APDU. Returns false when the text is not a valid APDU or
     * no tag is connected, so the screen can keep the cursor in the field.
     */
    fun send(raw: String): Boolean {
        val current = stateFlow.value
        if (!current.visible || current.busy) return false
        val command = try {
            Iso7816.parseApdu(raw)
        } catch (_: ApduParseException) {
            stateFlow.value = current.copy(error = ApduError.PARSE)
            return false
        }
        val active = transport ?: run {
            stateFlow.value = current.copy(error = ApduError.NO_TAG)
            return false
        }
        stateFlow.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val exchange = try {
                val response = withContext(Dispatchers.IO) {
                    Iso7816.exchange(active, command)
                }
                ApduExchange(command, response.data, response.sw)
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                closeTransport()
                ApduExchange(command, null, null, "tag lost")
            } catch (_: Throwable) {
                ApduExchange(command, null, null, "transceive failed")
            }
            val latest = stateFlow.value
            if (!latest.visible) return@launch
            val lost = exchange.error != null && transport == null
            stateFlow.value = latest.copy(
                busy = false,
                hasTag = transport != null,
                error = when {
                    lost -> ApduError.TAG_LOST
                    exchange.error != null -> ApduError.TRANSCEIVE
                    else -> null
                },
                exchanges = (latest.exchanges + exchange).takeLast(MAX_EXCHANGES),
            )
        }
        return true
    }

    private fun closeTransport() {
        val current = transport
        transport = null
        if (current != null) {
            viewModelScope.launch(Dispatchers.IO) { current.close() }
        }
    }

    private companion object {
        const val MAX_EXCHANGES = 50
    }
}
