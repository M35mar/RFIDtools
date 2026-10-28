// State holder for the tag inspector overlay. It owns no Android resources:
// the screen maps the error codes to strings, so the whole flow stays
// testable and the ViewModel survives rotation.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.nfc.inspect.AndroidIsoDepTransport
import com.farmacia.pm3android.nfc.inspect.AndroidTagProbe
import com.farmacia.pm3android.nfc.inspect.DesfireClient
import com.farmacia.pm3android.nfc.inspect.DesfireVersion
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InspectError {
    TAG_LOST,
    READ_FAILED,
}

class TagInspectState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val identity: TagIdentity? = null,
    val desfire: DesfireVersion? = null,
    /** Null means the list was never read, empty means the card has none. */
    val applications: List<Int>? = null,
    val error: InspectError? = null,
) {
    /**
     * Reader mode must also stay enabled while a read is in progress: disabling
     * it drops the NFC field and kills the transceive. It is released once the
     * result is on screen, so the same tag is not read twice.
     */
    val readerActive: Boolean
        get() = visible && identity == null && error == null
}

class TagInspectViewModel : ViewModel() {

    private val stateFlow = MutableStateFlow(TagInspectState())

    val state: StateFlow<TagInspectState> = stateFlow

    /** Opens the overlay, or restarts it for a new tag. */
    fun start() {
        stateFlow.value = TagInspectState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        stateFlow.value = TagInspectState()
    }

    fun retry() {
        stateFlow.value = stateFlow.value.copy(
            identity = null,
            desfire = null,
            applications = null,
            error = null,
        )
    }

    /** Called from the reader-mode callback on the main thread. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (!current.visible || current.busy) return
        stateFlow.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { inspect(tag) }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                stateFlow.value = latest.copy(
                    busy = false,
                    identity = result.identity,
                    desfire = result.desfire,
                    applications = result.applications,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                fail(InspectError.TAG_LOST)
            } catch (_: Throwable) {
                fail(InspectError.READ_FAILED)
            }
        }
    }

    private fun fail(error: InspectError) {
        val current = stateFlow.value
        if (current.visible) {
            stateFlow.value = current.copy(busy = false, error = error)
        }
    }

    private fun inspect(tag: Tag): Inspection {
        val snapshot = AndroidTagProbe.snapshot(tag)
        var identity = TagIdentifier.identify(snapshot)
        var desfire: DesfireVersion? = null
        var applications: List<Int>? = null

        if (snapshot.hasTech(NfcTech.ISO_DEP)) {
            try {
                val transport = AndroidIsoDepTransport(tag)
                try {
                    val client = DesfireClient(transport)
                    val version = try {
                        client.getVersion()
                    } catch (lost: TagLostException) {
                        throw lost
                    } catch (_: Throwable) {
                        null
                    }
                    if (version != null) {
                        desfire = version
                        applications = try {
                            client.getApplicationIds()
                        } catch (lost: TagLostException) {
                            throw lost
                        } catch (_: Throwable) {
                            null
                        }
                        identity = TagIdentifier.refineWithDesfire(identity, version)
                    }
                } finally {
                    transport.close()
                }
            } catch (lost: TagLostException) {
                throw lost
            } catch (_: Throwable) {
                // ISO-DEP but not DESFire, or the card refused the free commands.
            }
        }

        return Inspection(identity, desfire, applications)
    }

    private class Inspection(
        val identity: TagIdentity,
        val desfire: DesfireVersion?,
        val applications: List<Int>?,
    )
}
