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
import com.farmacia.pm3android.nfc.inspect.AndroidTagProbe
import com.farmacia.pm3android.nfc.inspect.DesfireClient
import com.farmacia.pm3android.nfc.inspect.DesfireVersion
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.content.TagContent
import com.farmacia.pm3android.nfc.inspect.content.TagContentAnalyzer
import com.farmacia.pm3android.nfc.inspect.content.TagReport
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

data class DumpState(
    val stage: DumpStage = DumpStage.NONE,
    val savedName: String? = null,
    val savedKeyName: String? = null,
    val error: DumpError? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val sectorsMissing: Int = 0,
    val blocksMissing: Int = 0,
)

/** Where the text report of the identified tag is. */
enum class ReportStage {
    IDLE,
    SAVED,
    FAILED,
}

data class ReportState(
    val stage: ReportStage = ReportStage.IDLE,
    val savedName: String? = null,
)

data class TagInspectState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val identity: TagIdentity? = null,
    val desfire: DesfireVersion? = null,
    /** Null means the list was never read, empty means the card has none. */
    val applications: List<Int>? = null,
    /** Content of the capture, available once the memory has been read. */
    val content: TagContent? = null,
    val error: InspectError? = null,
    val dump: DumpState = DumpState(),
    val report: ReportState = ReportState(),
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
            content = null,
            error = null,
            dump = DumpState(),
            report = ReportState(),
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
        stateFlow.value = current.copy(
            dump = DumpState(stage = DumpStage.WAITING),
            report = ReportState(),
        )
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
        stateFlow.value = current.copy(
            dump = DumpState(stage = DumpStage.WAITING),
            report = ReportState(),
        )
    }

    private fun readDump(tag: Tag) {
        stateFlow.value = stateFlow.value.copy(dump = DumpState(stage = DumpStage.READING))
        val classic = MifareClassic.get(tag) != null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { captureAndSave(tag, classic) }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                val missing = result.missing
                stateFlow.value = latest.copy(
                    content = result.content,
                    dump = if (missing == null) {
                        DumpState(
                            stage = DumpStage.SAVED,
                            savedName = result.saved?.dump?.name,
                            savedKeyName = result.saved?.keyFile?.name,
                        )
                    } else {
                        DumpState(
                            stage = DumpStage.INCOMPLETE,
                            error = DumpError.INCOMPLETE,
                            sectorsMissing = missing.sectorsMissing,
                            blocksMissing = missing.blocksMissing,
                        )
                    },
                    report = ReportState(),
                )
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                dumpFailed(DumpError.TAG_LOST)
            } catch (_: TagAuthException) {
                dumpFailed(if (classic) DumpError.KEYS else DumpError.PASSWORD)
            } catch (_: Throwable) {
                dumpFailed(DumpError.READ)
            }
        }
    }

    /**
     * Reads the whole tag with the factory keys. The capture is analyzed even
     * when it is incomplete, so the inspector can show what was readable; the
     * `.bin` is written only when nothing is missing, because a partial dump
     * looks valid and would restore zeroes later.
     */
    private fun captureAndSave(tag: Tag, classic: Boolean): CaptureResult {
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
                val capture = MfcReader(transport).read(progress = progress)
                val content = TagContentAnalyzer.of(capture)
                val missing = incomplete(capture)
                if (missing != null) {
                    return CaptureResult(content = content, missing = missing)
                }
                val saved = NfcDumpSaver.save(
                    context = context,
                    name = "hf-mf-${capture.uidHex}-dump",
                    classic = capture,
                )
                return CaptureResult(content = content, saved = saved)
            } finally {
                transport.close()
            }
        }
        val transport = AndroidUltralightTransport(tag)
        try {
            val capture = MfuReader(transport).read(progress = progress)
            val saved = NfcDumpSaver.save(
                context = context,
                name = capture.defaultName,
                ultralight = capture,
            )
            return CaptureResult(
                content = TagContentAnalyzer.of(capture),
                saved = saved,
            )
        } finally {
            transport.close()
        }
    }

    /** Counts of an incomplete Classic capture, null when every block was read. */
    private fun incomplete(capture: MfcCapture): Missing? {
        val sectors = (0 until capture.size.sectors).count { sector ->
            sector !in capture.readableSectors ||
                (0 until MfcGeometry.blocksInSector(sector)).any { offset ->
                    MfcGeometry.firstBlockOfSector(sector) + offset !in capture.readableBlocks
                }
        }
        val blocks = capture.unreadableBlocks.size
        return if (sectors == 0 && blocks == 0) null else Missing(sectors, blocks)
    }

    private class CaptureResult(
        val content: TagContent,
        val saved: SavedDump? = null,
        val missing: Missing? = null,
    )

    private class Missing(val sectorsMissing: Int, val blocksMissing: Int)

    // -----------------------------------------------------------------------
    // Report
    // -----------------------------------------------------------------------

    /**
     * Writes identity, notes and content as `<base>-report.txt`, next to the
     * dumps. Available as soon as the identity is known, with or without a dump.
     */
    fun saveReport() {
        val current = stateFlow.value
        val identity = current.identity ?: return
        if (current.report.stage == ReportStage.SAVED || current.busy) return
        if (current.dump.stage == DumpStage.READING) return
        val content = current.content
        val desfire = current.desfire
        val applications = current.applications
        val dumpName = current.dump.savedName
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    val text = TagReport.build(
                        identity = identity,
                        content = content,
                        desfire = desfire,
                        applications = applications,
                        dumpName = dumpName,
                    )
                    NfcReport.save(
                        context = getApplication<Application>(),
                        baseName = TagReport.baseName(identity, dumpName),
                        text = text,
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                null
            }
            val latest = stateFlow.value
            if (!latest.visible || latest.identity !== identity) return@launch
            stateFlow.value = latest.copy(
                report = if (saved == null) {
                    ReportState(stage = ReportStage.FAILED)
                } else {
                    ReportState(stage = ReportStage.SAVED, savedName = saved.name)
                },
            )
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
