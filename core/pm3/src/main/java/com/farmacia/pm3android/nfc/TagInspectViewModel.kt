// State holder for the tag inspector overlay. It owns no Android resources:
// the screen maps the error codes to strings, so the whole flow stays
// testable and the ViewModel survives rotation. A complete read is saved as a
// .bin dump; an incomplete one only produces an inline report.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import androidx.lifecycle.AndroidViewModel
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

/** Where the optional dump of the identified tag is. */
enum class DumpStage {
    /** No dump requested. */
    NONE,

    /** Waiting for the tag, which has to be presented again. */
    WAITING,
    READING,
    SAVED,

    /** The read missed sectors: nothing was written, the report is on screen. */
    INCOMPLETE,
    FAILED,
}

enum class DumpError {
    TAG_LOST,
    KEYS,
    PASSWORD,
    INCOMPLETE,
    READ,
}

class DumpState(
    val stage: DumpStage = DumpStage.NONE,
    val savedName: String? = null,
    val savedKeyName: String? = null,
    val error: DumpError? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val sectorsMissing: Int = 0,
    val blocksMissing: Int = 0,
)

class TagInspectState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val identity: TagIdentity? = null,
    val desfire: DesfireVersion? = null,
    /** Null means the list was never read, empty means the card has none. */
    val applications: List<Int>? = null,
    val error: InspectError? = null,
    val dump: DumpState = DumpState(),
) {
    /**
     * Reader mode stays enabled while a read is in progress: disabling it drops
     * the NFC field and kills the transceive. It is released once the result is
     * on screen, so the same tag is not read twice.
     */
    val readerActive: Boolean
        get() = when {
            !visible || error != null -> false
            dump.stage == DumpStage.WAITING || dump.stage == DumpStage.READING -> true
            else -> identity == null && dump.stage == DumpStage.NONE
        }
}

class TagInspectViewModel(application: Application) : AndroidViewModel(application) {

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
            dump = DumpState(),
        )
    }

    /** Called from the reader-mode callback on the main thread. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (!current.visible || current.busy) return
        if (current.dump.stage == DumpStage.WAITING) {
            readDump(tag)
            return
        }
        // While a dump is being read or is on screen, a new discovery must not
        // restart the identification behind the user's back.
        if (current.dump.stage != DumpStage.NONE) return
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

    // -----------------------------------------------------------------------
    // Dump
    // -----------------------------------------------------------------------

    /** Asks for the tag again: the field is gone once the result is shown. */
    fun startDump() {
        val current = stateFlow.value
        if (!current.visible || current.identity?.dumpable != true) return
        stateFlow.value = current.copy(dump = DumpState(stage = DumpStage.WAITING))
    }

    fun cancelDump() {
        val current = stateFlow.value
        if (current.dump.stage != DumpStage.WAITING) return
        stateFlow.value = current.copy(dump = DumpState())
    }

    fun retryDump() {
        val current = stateFlow.value
        if (current.dump.stage != DumpStage.INCOMPLETE && current.dump.stage != DumpStage.FAILED) {
            return
        }
        stateFlow.value = current.copy(dump = DumpState(stage = DumpStage.WAITING))
    }

    private fun readDump(tag: Tag) {
        stateFlow.value = stateFlow.value.copy(dump = DumpState(stage = DumpStage.READING))
        val classic = MifareClassic.get(tag) != null
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { captureAndSave(tag, classic) }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                stateFlow.value = latest.copy(
                    dump = DumpState(
                        stage = DumpStage.SAVED,
                        savedName = saved.dump.name,
                        savedKeyName = saved.keyFile?.name,
                    ),
                )
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                dumpFailed(DumpError.TAG_LOST)
            } catch (_: TagAuthException) {
                dumpFailed(if (classic) DumpError.KEYS else DumpError.PASSWORD)
            } catch (t: MfcIncompleteReadException) {
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                stateFlow.value = latest.copy(
                    dump = latest.dump.copy(
                        stage = DumpStage.INCOMPLETE,
                        error = DumpError.INCOMPLETE,
                        sectorsMissing = t.missingSectors.size,
                        blocksMissing = t.unreadableBlocks,
                    ),
                )
            } catch (_: Throwable) {
                dumpFailed(DumpError.READ)
            }
        }
    }

    /**
     * Reads the whole tag with the factory keys and saves it only when nothing
     * is missing: a partial dump looks valid and would restore zeroes later.
     */
    private fun captureAndSave(tag: Tag, classic: Boolean): SavedDump {
        val context = getApplication<Application>()
        val progress = RestoreProgress { done, total ->
            val current = stateFlow.value
            if (current.dump.stage == DumpStage.READING) {
                stateFlow.value = current.copy(
                    dump = current.dump.copy(progressDone = done, progressTotal = total),
                )
            }
        }
        if (classic) {
            val transport = AndroidClassicTransport(tag)
            try {
                val capture = MfcReader(transport).read(requireComplete = true, progress = progress)
                return NfcDumpSaver.save(
                    context = context,
                    name = "hf-mf-${capture.uidHex}-dump",
                    classic = capture,
                )
            } finally {
                transport.close()
            }
        }
        val transport = AndroidUltralightTransport(tag)
        try {
            val capture = MfuReader(transport).read(progress = progress)
            return NfcDumpSaver.save(
                context = context,
                name = capture.defaultName,
                ultralight = capture,
            )
        } finally {
            transport.close()
        }
    }

    private fun dumpFailed(error: DumpError) {
        val current = stateFlow.value
        if (!current.visible) return
        stateFlow.value = current.copy(
            dump = current.dump.copy(stage = DumpStage.FAILED, error = error),
        )
    }

    // -----------------------------------------------------------------------
    // Identification
    // -----------------------------------------------------------------------

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
