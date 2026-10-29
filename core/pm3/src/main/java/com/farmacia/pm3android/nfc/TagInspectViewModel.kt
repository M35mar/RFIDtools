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
import com.farmacia.pm3android.nfc.inspect.CalypsoClient
import com.farmacia.pm3android.nfc.inspect.CalypsoProbe
import com.farmacia.pm3android.nfc.inspect.DesfireAuthException
import com.farmacia.pm3android.nfc.inspect.DesfireClient
import com.farmacia.pm3android.nfc.inspect.DesfireDetails
import com.farmacia.pm3android.nfc.inspect.DesfireInspector
import com.farmacia.pm3android.nfc.inspect.DesfireReader
import com.farmacia.pm3android.nfc.inspect.IsoDepAppProbe
import com.farmacia.pm3android.nfc.inspect.NfcTech
import com.farmacia.pm3android.nfc.inspect.PlusAuthException
import com.farmacia.pm3android.nfc.inspect.PlusDetails
import com.farmacia.pm3android.nfc.inspect.PlusGeometry
import com.farmacia.pm3android.nfc.inspect.PlusReader
import com.farmacia.pm3android.nfc.inspect.PlusSectorRead
import com.farmacia.pm3android.nfc.inspect.TagDetails
import com.farmacia.pm3android.nfc.inspect.TagIdentifier
import com.farmacia.pm3android.nfc.inspect.TagIdentity
import com.farmacia.pm3android.nfc.inspect.TagSnapshot
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

    /** Opens the overlay, or restarts it for a new tag. */
    fun start() {
        desfireKey = null
        plusKey = null
        stateFlow.value = TagInspectState(visible = true)
    }

    fun close() {
        if (stateFlow.value.busy) return
        desfireKey = null
        plusKey = null
        stateFlow.value = TagInspectState()
    }

    fun retry() {
        desfireKey = null
        plusKey = null
        stateFlow.value = stateFlow.value.copy(
            identity = null,
            details = null,
            content = null,
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

    private fun inspect(tag: Tag): Inspection {
        val snapshot = AndroidTagProbe.snapshot(tag)
        var details = snapshot.details
        var identity = TagIdentifier.identify(snapshot, details)

        if (snapshot.hasTech(NfcTech.ISO_DEP)) {
            val isoDepDetails = probeIsoDep(tag, snapshot)
            if (isoDepDetails != null) {
                details = isoDepDetails
                identity = TagIdentifier.identify(snapshot, details)
            }
        }

        return Inspection(identity, details)
    }

    /**
     * ISO-DEP is shared by DESFire, Calypso, EMV and NDEF Type 4 cards. The
     * card is opened once and the probes run in the order most likely to
     * answer: Calypso first on ISO 14443-B, DESFire first everywhere else.
     * A MIFARE Plus level 3 only answers its native GET_VERSION, so it is the
     * last attempt, after the ISO-DEP field has been released.
     */
    private fun probeIsoDep(tag: Tag, snapshot: TagSnapshot): TagDetails? {
        val onIsoB = snapshot.hasTech(NfcTech.NFC_B) && !snapshot.hasTech(NfcTech.NFC_A)
        val abort: (Throwable) -> Boolean = { error -> error is TagLostException }
        var desfire: TagDetails? = null
        var other: TagDetails? = null

        try {
            val transport = AndroidIsoDepTransport(tag)
            try {
                if (onIsoB) {
                    other = calypso(transport, onIsoB, abort)
                    if (other == null) desfire = desfire(transport, abort)
                } else {
                    desfire = desfire(transport, abort)
                    if (desfire == null) other = calypso(transport, onIsoB, abort)
                }
                if (desfire == null && other == null) {
                    other = runCatching { IsoDepAppProbe.probe(transport) }.getOrNull()
                }
            } finally {
                transport.close()
            }
        } catch (lost: TagLostException) {
            throw lost
        } catch (_: Throwable) {
            // ISO-DEP refused every probe: keep the generic identification.
        }

        if (desfire != null) return desfire
        if (other != null) return other
        return runCatching { AndroidTagProbe.probePlus(tag) }.getOrNull()
    }

    private fun desfire(
        transport: AndroidIsoDepTransport,
        abort: (Throwable) -> Boolean,
    ): TagDetails? = try {
        DesfireInspector.read(DesfireClient(transport), abort)
    } catch (lost: TagLostException) {
        throw lost
    } catch (_: Throwable) {
        null
    }

    private fun calypso(
        transport: AndroidIsoDepTransport,
        iso14443B: Boolean,
        abort: (Throwable) -> Boolean,
    ): TagDetails? = try {
        CalypsoProbe.read(CalypsoClient(transport, iso14443B), abort)
    } catch (lost: TagLostException) {
        throw lost
    } catch (_: Throwable) {
        null
    }

    private class Inspection(
        val identity: TagIdentity,
        val details: TagDetails?,
    )
}
