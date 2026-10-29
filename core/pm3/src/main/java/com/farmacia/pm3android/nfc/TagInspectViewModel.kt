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
import com.farmacia.pm3android.nfc.inspect.CommandLog
import com.farmacia.pm3android.nfc.inspect.DesfireAuthException
import com.farmacia.pm3android.nfc.inspect.DesfireClient
import com.farmacia.pm3android.nfc.inspect.DesfireDetails
import com.farmacia.pm3android.nfc.inspect.DesfireReader
import com.farmacia.pm3android.nfc.inspect.HfProbeRegistry
import com.farmacia.pm3android.nfc.inspect.MemoryRow
import com.farmacia.pm3android.nfc.inspect.PlusAuthException
import com.farmacia.pm3android.nfc.inspect.PlusDetails
import com.farmacia.pm3android.nfc.inspect.PlusGeometry
import com.farmacia.pm3android.nfc.inspect.PlusReader
import com.farmacia.pm3android.nfc.inspect.PlusSectorRead
import com.farmacia.pm3android.nfc.inspect.ProbeContext
import com.farmacia.pm3android.nfc.inspect.TagDetails
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

    /** Memory read and on screen; no file has been written yet. */
    DONE,

    /** The user saved the memory that was on screen. */
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
    /** Memory on screen after a read: pages for Type 2, blocks for Classic. */
    val rows: List<MemoryRow> = emptyList(),
    val rowSize: Int = 4,
    /** Base name the save dialog proposes. */
    val suggestedName: String = "",
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

/** Where the optional authenticated DESFire read is. */
enum class DesfireReadStage {
    NONE,
    WAITING,
    READING,
    DONE,
    FAILED,
}

enum class DesfireReadError {
    TAG_LOST,
    AUTH,
    READ,
}

data class DesfireReadState(
    val stage: DesfireReadStage = DesfireReadStage.NONE,
    val keyNo: Int = 0,
    val error: DesfireReadError? = null,
    val filesRead: Int = 0,
)

/** Where the optional authenticated MIFARE Plus sector read is. */
enum class PlusReadStage {
    NONE,
    WAITING,
    READING,
    DONE,
    FAILED,
}

enum class PlusReadError {
    TAG_LOST,
    AUTH,
    READ,
}

data class PlusReadState(
    val stage: PlusReadStage = PlusReadStage.NONE,
    val sector: Int = 0,
    val keyB: Boolean = false,
    val error: PlusReadError? = null,
)

data class TagInspectState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val identity: TagIdentity? = null,
    /** Technology specific facts: DESFire, Plus, Calypso, ISO 15693, ... */
    val details: TagDetails? = null,
    /** Content of the capture, available once the memory has been read. */
    val content: TagContent? = null,
    /** Last command/response exchanges of the identification. */
    val commandLog: List<CommandLog.Entry> = emptyList(),
    val error: InspectError? = null,
    val dump: DumpState = DumpState(),
    val report: ReportState = ReportState(),
    /** Authenticated DESFire read with a key supplied by the user. */
    val desfireRead: DesfireReadState = DesfireReadState(),
    /** Authenticated MIFARE Plus sector read with an AES key. */
    val plusRead: PlusReadState = PlusReadState(),
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
            desfireRead.stage == DesfireReadStage.WAITING ||
                desfireRead.stage == DesfireReadStage.READING -> true
            plusRead.stage == PlusReadStage.WAITING ||
                plusRead.stage == PlusReadStage.READING -> true
            else -> identity == null && dump.stage == DumpStage.NONE
        }
}

class TagInspectViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(TagInspectState())

    val state: StateFlow<TagInspectState> = stateFlow

    /** AES key of a pending DESFire read; never copied into the UI state. */
    private var desfireKey: ByteArray? = null

    /** AES key of a pending MIFARE Plus sector read. */
    private var plusKey: ByteArray? = null

    /** Memory read but not saved yet; kept out of the UI state. */
    private var pendingCapture: PendingCapture? = null

    /** Opens the overlay, or restarts it for a new tag. */
    fun start() {
        desfireKey = null
        plusKey = null
        pendingCapture = null
        stateFlow.value = TagInspectState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        desfireKey = null
        plusKey = null
        pendingCapture = null
        stateFlow.value = TagInspectState()
    }

    fun retry() {
        desfireKey = null
        plusKey = null
        pendingCapture = null
        stateFlow.value = stateFlow.value.copy(
            identity = null,
            details = null,
            content = null,
            commandLog = emptyList(),
            error = null,
            dump = DumpState(),
            report = ReportState(),
            desfireRead = DesfireReadState(),
            plusRead = PlusReadState(),
        )
    }

    // -----------------------------------------------------------------------
    // Authenticated DESFire read
    // -----------------------------------------------------------------------

    /**
     * Stores the AES key and asks for the tag again. Returns false when the key
     * is not 16 bytes or the key number is outside 0..15, so the dialog can
     * show the error without leaving the screen.
     */
    fun startDesfireRead(keyHex: String, keyNo: Int): Boolean {
        val current = stateFlow.value
        if (!current.visible || current.details !is DesfireDetails) return false
        val key = keyHex.trim().hexToBytesOrNull() ?: return false
        if (key.size != 16 || keyNo !in 0..15) return false
        desfireKey = key
        stateFlow.value = current.copy(
            error = null,
            desfireRead = DesfireReadState(
                stage = DesfireReadStage.WAITING,
                keyNo = keyNo,
            ),
        )
        return true
    }

    fun cancelDesfireRead() {
        if (stateFlow.value.desfireRead.stage != DesfireReadStage.WAITING) return
        desfireKey = null
        stateFlow.value = stateFlow.value.copy(desfireRead = DesfireReadState())
    }

    fun retryDesfireRead() {
        val current = stateFlow.value
        if (current.desfireRead.stage != DesfireReadStage.FAILED) return
        if (desfireKey == null) return
        stateFlow.value = current.copy(
            desfireRead = current.desfireRead.copy(
                stage = DesfireReadStage.WAITING,
                error = null,
            ),
        )
    }

    private fun readDesfire(tag: Tag) {
        val current = stateFlow.value
        val key = desfireKey ?: return
        val details = current.details as? DesfireDetails ?: return
        val keyNo = current.desfireRead.keyNo
        stateFlow.value = current.copy(
            busy = true,
            desfireRead = current.desfireRead.copy(
                stage = DesfireReadStage.READING,
                error = null,
            ),
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    readDesfireWithKey(tag, details, key, keyNo)
                }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                val read = result.applications.orEmpty()
                    .sumOf { application -> application.files.orEmpty().count { it.data != null } }
                if (read > 0) desfireKey = null
                stateFlow.value = latest.copy(
                    busy = false,
                    details = result,
                    desfireRead = DesfireReadState(
                        stage = if (read > 0) DesfireReadStage.DONE else DesfireReadStage.FAILED,
                        keyNo = keyNo,
                        error = if (read > 0) null else DesfireReadError.AUTH,
                        filesRead = read,
                    ),
                )
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                desfireFailed(DesfireReadError.TAG_LOST)
            } catch (_: DesfireAuthException) {
                desfireFailed(DesfireReadError.AUTH)
            } catch (_: Throwable) {
                desfireFailed(DesfireReadError.READ)
            }
        }
    }

    private fun readDesfireWithKey(
        tag: Tag,
        details: DesfireDetails,
        key: ByteArray,
        keyNo: Int,
    ): DesfireDetails {
        val transport = AndroidIsoDepTransport(tag)
        try {
            val abort: (Throwable) -> Boolean = { error -> error is TagLostException }
            return DesfireReader.read(
                client = DesfireClient(transport),
                details = details,
                keyNo = keyNo,
                key = key,
                abort = abort,
            )
        } finally {
            transport.close()
        }
    }

    private fun desfireFailed(error: DesfireReadError) {
        val current = stateFlow.value
        if (!current.visible) return
        stateFlow.value = current.copy(
            busy = false,
            desfireRead = current.desfireRead.copy(
                stage = DesfireReadStage.FAILED,
                error = error,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Authenticated MIFARE Plus read
    // -----------------------------------------------------------------------

    /**
     * Stores the AES key of one sector and asks for the tag again. The key
     * number is derived from the sector and from A/B, so the user only picks a
     * sector. Returns false when the key or the sector is invalid.
     */
    fun startPlusRead(keyHex: String, sector: Int, keyB: Boolean): Boolean {
        val current = stateFlow.value
        val details = current.details as? PlusDetails ?: return false
        val key = keyHex.trim().hexToBytesOrNull() ?: return false
        val lastSector = PlusGeometry.sectorCount(details.storageBytes) - 1
        if (key.size != 16 || sector !in 0..lastSector) return false
        plusKey = key
        stateFlow.value = current.copy(
            error = null,
            plusRead = PlusReadState(
                stage = PlusReadStage.WAITING,
                sector = sector,
                keyB = keyB,
            ),
        )
        return true
    }

    fun cancelPlusRead() {
        if (stateFlow.value.plusRead.stage != PlusReadStage.WAITING) return
        plusKey = null
        stateFlow.value = stateFlow.value.copy(plusRead = PlusReadState())
    }

    fun retryPlusRead() {
        val current = stateFlow.value
        if (current.plusRead.stage != PlusReadStage.FAILED) return
        if (plusKey == null) return
        stateFlow.value = current.copy(
            plusRead = current.plusRead.copy(
                stage = PlusReadStage.WAITING,
                error = null,
            ),
        )
    }

    private fun readPlus(tag: Tag) {
        val current = stateFlow.value
        val key = plusKey ?: return
        val details = current.details as? PlusDetails ?: return
        val sector = current.plusRead.sector
        val keyB = current.plusRead.keyB
        stateFlow.value = current.copy(
            busy = true,
            plusRead = current.plusRead.copy(
                stage = PlusReadStage.READING,
                error = null,
            ),
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    readPlusSector(tag, key, sector, keyB)
                }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                val currentDetails = latest.details as? PlusDetails ?: details
                plusKey = null
                stateFlow.value = latest.copy(
                    busy = false,
                    details = currentDetails.withSector(result),
                    plusRead = PlusReadState(
                        stage = PlusReadStage.DONE,
                        sector = sector,
                        keyB = keyB,
                    ),
                )
            } catch (t: CancellationException) {
                throw t
            } catch (_: TagLostException) {
                plusFailed(PlusReadError.TAG_LOST)
            } catch (_: PlusAuthException) {
                plusFailed(PlusReadError.AUTH)
            } catch (_: Throwable) {
                plusFailed(PlusReadError.READ)
            }
        }
    }

    private fun readPlusSector(
        tag: Tag,
        key: ByteArray,
        sector: Int,
        keyB: Boolean,
    ): PlusSectorRead {
        val transport = AndroidNfcATransport(tag)
        try {
            return PlusReader.readSector(transport, key, sector, keyB)
        } finally {
            transport.close()
        }
    }

    private fun plusFailed(error: PlusReadError) {
        val current = stateFlow.value
        if (!current.visible) return
        stateFlow.value = current.copy(
            busy = false,
            plusRead = current.plusRead.copy(
                stage = PlusReadStage.FAILED,
                error = error,
            ),
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
        if (current.desfireRead.stage == DesfireReadStage.WAITING) {
            readDesfire(tag)
            return
        }
        if (current.plusRead.stage == PlusReadStage.WAITING) {
            readPlus(tag)
            return
        }
        // While a dump or an authenticated read is on screen, a new discovery
        // must not restart the identification behind the user's back.
        if (current.dump.stage != DumpStage.NONE) return
        if (current.desfireRead.stage != DesfireReadStage.NONE) return
        if (current.plusRead.stage != PlusReadStage.NONE) return
        stateFlow.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { inspect(tag) }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                stateFlow.value = latest.copy(
                    busy = false,
                    identity = result.identity,
                    details = result.details,
                    content = result.content,
                    commandLog = result.log,
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
        pendingCapture = null
        stateFlow.value = current.copy(
            dump = DumpState(stage = DumpStage.WAITING),
            report = ReportState(),
        )
    }

    private fun readDump(tag: Tag) {
        stateFlow.value = stateFlow.value.copy(dump = DumpState(stage = DumpStage.READING))
        val classic = MifareClassic.get(tag) != null
        val progress = RestoreProgress { done, total ->
            val current = stateFlow.value
            if (current.dump.stage == DumpStage.READING) {
                stateFlow.value = current.copy(
                    dump = current.dump.copy(progressDone = done, progressTotal = total),
                )
            }
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { capture(tag, classic, progress) }
                val latest = stateFlow.value
                if (!latest.visible) return@launch
                val missing = result.missing
                // A partial Classic capture is shown but never saved: it looks
                // valid and would restore zeroes later.
                pendingCapture = if (missing == null) result.capture else null
                stateFlow.value = latest.copy(
                    content = result.content,
                    dump = if (missing == null) {
                        DumpState(
                            stage = DumpStage.DONE,
                            rows = result.rows,
                            rowSize = result.rowSize,
                            suggestedName = result.suggestedName,
                        )
                    } else {
                        DumpState(
                            stage = DumpStage.INCOMPLETE,
                            error = DumpError.INCOMPLETE,
                            sectorsMissing = missing.sectorsMissing,
                            blocksMissing = missing.blocksMissing,
                            rows = result.rows,
                            rowSize = result.rowSize,
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
     * Writes the memory that is already on screen. The name comes from the save
     * dialog; an invalid one is rejected by the caller, which keeps the dialog
     * open and shows the error there.
     */
    fun saveDump(rawName: String) {
        val current = stateFlow.value
        if (current.dump.stage != DumpStage.DONE) return
        val name = MfuDumpFiles.fileName(rawName) ?: return
        val capture = pendingCapture ?: return
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    when (capture) {
                        is PendingCapture.Classic ->
                            NfcDumpSaver.save(context, name, classic = capture.capture)

                        is PendingCapture.Ultralight ->
                            NfcDumpSaver.save(context, name, ultralight = capture.capture)
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                null
            }
            val latest = stateFlow.value
            if (!latest.visible) return@launch
            stateFlow.value = latest.copy(
                dump = if (saved == null) {
                    // The memory stays on screen so the user can retry the save.
                    latest.dump.copy(error = DumpError.READ)
                } else {
                    latest.dump.copy(
                        stage = DumpStage.SAVED,
                        savedName = saved.dump.name,
                        savedKeyName = saved.keyFile?.name,
                        error = null,
                    )
                },
            )
        }
    }

    /**
     * Reads the whole tag with the factory keys and keeps the capture for a
     * later save. Nothing is written here: the screen shows the memory first.
     */
    private fun capture(
        tag: Tag,
        classic: Boolean,
        progress: RestoreProgress,
    ): CaptureResult {
        if (classic) {
            val transport = AndroidClassicTransport(tag)
            try {
                val capture = MfcReader(transport).read(progress = progress)
                return CaptureResult(
                    content = TagContentAnalyzer.of(capture),
                    rows = classicRows(capture),
                    rowSize = MfcGeometry.BLOCK_SIZE,
                    suggestedName = suggestedName("hf-mf-${capture.uidHex}-dump"),
                    capture = PendingCapture.Classic(capture),
                    missing = incomplete(capture),
                )
            } finally {
                transport.close()
            }
        }
        val transport = AndroidUltralightTransport(tag)
        try {
            val capture = MfuReader(transport).read(progress = progress)
            return CaptureResult(
                content = TagContentAnalyzer.of(capture),
                rows = capture.pages.mapIndexed { index, page -> MemoryRow(index, page) },
                rowSize = 4,
                suggestedName = suggestedName(capture.defaultName),
                capture = PendingCapture.Ultralight(capture),
            )
        } finally {
            transport.close()
        }
    }

    /** Hex rows of the blocks the Classic read actually returned. */
    private fun classicRows(capture: MfcCapture): List<MemoryRow> {
        val bytes = capture.toBin()
        val size = MfcGeometry.BLOCK_SIZE
        return capture.readableBlocks.sorted().map { block ->
            MemoryRow(block, bytes.copyOfRange(block * size, block * size + size))
        }
    }

    /** Same rule the full read uses, so the dialog and the saver agree. */
    private fun suggestedName(base: String): String = MfuDumpFiles.fileName(base) ?: base

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
        val rows: List<MemoryRow>,
        val rowSize: Int,
        val suggestedName: String,
        val capture: PendingCapture,
        val missing: Missing? = null,
    )

    /** Capture kept in memory between the read and the explicit save. */
    private sealed interface PendingCapture {
        class Classic(val capture: MfcCapture) : PendingCapture

        class Ultralight(val capture: MfuCapture) : PendingCapture
    }

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
        val details = current.details
        val dumpName = current.dump.savedName
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    val text = TagReport.build(
                        identity = identity,
                        content = content,
                        details = details,
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

    /**
     * Runs the probe registry: discovery snapshot first, then one probe per
     * technology. The registry stops at the first probe that names the card and
     * keeps the content and the command log of the whole chain.
     */
    private fun inspect(tag: Tag): Inspection {
        val log = CommandLog()
        val snapshot = AndroidTagProbe.snapshot(tag)
        val abort: (Throwable) -> Boolean = { error -> error is TagLostException }
        val context = ProbeContext(
            snapshot = snapshot,
            session = AndroidTagSession(tag, log),
            log = log,
            abort = abort,
        )
        val result = HfProbeRegistry.run(context)
        return Inspection(
            identity = result.identity,
            details = result.details,
            content = result.content,
            log = result.log,
        )
    }

    private class Inspection(
        val identity: TagIdentity,
        val details: TagDetails?,
        val content: TagContent?,
        val log: List<CommandLog.Entry>,
    )
}
