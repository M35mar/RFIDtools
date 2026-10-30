// State holder for the MIFARE Classic credit/rides tool.
//
// The flow is deliberately two-phase: scan with a key file, choose a value
// block, then present the tag again so INCREMENT/DECREMENT + TRANSFER happen on
// a fresh session. The capture and the key map stay in memory between the two
// taps; only the card must be re-presented.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.net.Uri
import android.nfc.Tag
import android.nfc.TagLostException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3Resources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MfcValueStage {
    /** Key-file selection, before the first scan. */
    SETUP,

    /** Reader mode is active, waiting for the tag to scan. */
    SCAN_WAITING,

    /** The card is being read. */
    SCAN_READING,

    /** Value blocks are listed; no reader mode. */
    LIST,

    /** Reader mode is active, waiting for the tag to write. */
    WRITE_WAITING,

    /** The operation is running and being verified. */
    WRITE_WORKING,
}

data class MfcValueState(
    val visible: Boolean = false,
    val stage: MfcValueStage = MfcValueStage.SETUP,
    val keyFiles: List<String> = emptyList(),
    val selectedKeyFile: String? = null,
    val keyFileLoading: Boolean = false,
    val keyFileError: String? = null,
    val uidHex: String = "",
    val tagLabel: String = "",
    val sectorsRead: Int = 0,
    val sectorsTotal: Int = 0,
    val valueBlocks: List<MfcValueBlockInfo> = emptyList(),
    val pendingBlock: MfcValueBlockInfo? = null,
    val pendingOperation: MfcValueOperation? = null,
    val pendingAmount: Int = 0,
    val error: String? = null,
    val success: MfcValueChange? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
) {
    val readerActive: Boolean
        get() = visible && (
            stage == MfcValueStage.SCAN_WAITING ||
                stage == MfcValueStage.SCAN_READING ||
                stage == MfcValueStage.WRITE_WAITING ||
                stage == MfcValueStage.WRITE_WORKING
            )

    val busy: Boolean
        get() = stage == MfcValueStage.SCAN_READING || stage == MfcValueStage.WRITE_WORKING
}

class MfcValueViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(MfcValueState())
    val state: StateFlow<MfcValueState> = stateFlow

    private var keyFileBytes: ByteArray? = null
    private var keyFileLabel: String? = null
    private var capture: MfcCapture? = null
    private var scanUid = ""
    private var pendingBlock: MfcValueBlockInfo? = null
    private var pendingOperation: MfcValueOperation? = null
    private var pendingAmount = 0

    fun start() {
        clearPending()
        capture = null
        scanUid = ""
        keyFileBytes = null
        keyFileLabel = null
        stateFlow.value = MfcValueState(visible = true)
        refreshKeyFiles()
    }

    fun close() {
        if (stateFlow.value.busy) return
        clearPending()
        capture = null
        scanUid = ""
        keyFileBytes = null
        keyFileLabel = null
        stateFlow.value = MfcValueState()
    }

    // -----------------------------------------------------------------------
    // Key file
    // -----------------------------------------------------------------------

    fun selectKeyFile(name: String) {
        val file = File(Pm3Resources.home(getApplication()), name)
        stateFlow.value = stateFlow.value.copy(
            keyFileLoading = true,
            keyFileError = null,
            selectedKeyFile = null,
        )
        // A different key file changes what can be read, so an old capture is
        // not reused.
        capture = null
        scanUid = ""
        clearPending()
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val read = file.readBytes()
                    // The layout depends on the card size, but a document with
                    // no six-byte key is unusable on every card.
                    MfcKeyFileParser.parse(read, MfcSize.CLASSIC_1K, name)
                    read
                }
                if (!stateFlow.value.visible) return@launch
                keyFileBytes = bytes
                keyFileLabel = name
                stateFlow.value = stateFlow.value.copy(
                    stage = MfcValueStage.SETUP,
                    selectedKeyFile = name,
                    keyFileLoading = false,
                    keyFileError = null,
                    uidHex = "",
                    valueBlocks = emptyList(),
                    pendingBlock = null,
                    pendingOperation = null,
                    pendingAmount = 0,
                    error = null,
                    success = null,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                keyFileBytes = null
                keyFileLabel = null
                stateFlow.value = stateFlow.value.copy(
                    stage = MfcValueStage.SETUP,
                    selectedKeyFile = null,
                    keyFileLoading = false,
                    keyFileError = text(R.string.nfc_dump_keys_error, errorText(t)),
                )
            }
        }
    }

    fun clearKeyFile() {
        keyFileBytes = null
        keyFileLabel = null
        capture = null
        scanUid = ""
        clearPending()
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.SETUP,
            selectedKeyFile = null,
            keyFileLoading = false,
            keyFileError = null,
            uidHex = "",
            valueBlocks = emptyList(),
            pendingBlock = null,
            pendingOperation = null,
            pendingAmount = 0,
            error = null,
            success = null,
        )
    }

    fun importKeyFile(uri: Uri) {
        stateFlow.value = stateFlow.value.copy(keyFileLoading = true, keyFileError = null)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    NfcFileStore.import(getApplication(), uri)
                }
                if (!stateFlow.value.visible) return@launch
                refreshKeyFilesNow()
                selectKeyFile(relativeName(file))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    keyFileLoading = false,
                    keyFileError = text(R.string.nfc_import_error, errorText(t)),
                )
            }
        }
    }

    private fun refreshKeyFiles() {
        viewModelScope.launch { refreshKeyFilesNow() }
    }

    private suspend fun refreshKeyFilesNow() {
        val names = withContext(Dispatchers.IO) {
            MfcKeyFiles.listIn(Pm3Resources.home(getApplication()))
                .map(::relativeName)
        }
        if (!stateFlow.value.visible) return
        stateFlow.value = stateFlow.value.copy(keyFiles = names)
    }

    private fun relativeName(file: File): String =
        file.relativeTo(Pm3Resources.home(getApplication())).path.replace('\\', '/')

    // -----------------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------------

    /** Starts reader mode; the key file, when selected, is already loaded. */
    fun beginScan(): Boolean {
        val current = stateFlow.value
        if (current.busy || current.keyFileLoading) return false
        capture = null
        scanUid = ""
        clearPending()
        stateFlow.value = current.copy(
            stage = MfcValueStage.SCAN_WAITING,
            uidHex = "",
            tagLabel = "",
            sectorsRead = 0,
            sectorsTotal = 0,
            valueBlocks = emptyList(),
            pendingBlock = null,
            pendingOperation = null,
            pendingAmount = 0,
            error = null,
            success = null,
            progressDone = 0,
            progressTotal = 0,
        )
        return true
    }

    fun cancelWaiting() {
        val current = stateFlow.value
        when (current.stage) {
            MfcValueStage.SCAN_WAITING -> stateFlow.value = current.copy(
                stage = MfcValueStage.SETUP,
            )

            MfcValueStage.WRITE_WAITING -> {
                clearPending()
                stateFlow.value = current.copy(
                    stage = MfcValueStage.LIST,
                    pendingBlock = null,
                    pendingOperation = null,
                    pendingAmount = 0,
                )
            }

            else -> Unit
        }
    }

    /** Returns to the setup form and forgets the current capture. */
    fun backToSetup() {
        if (stateFlow.value.busy) return
        capture = null
        scanUid = ""
        clearPending()
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.SETUP,
            uidHex = "",
            tagLabel = "",
            sectorsRead = 0,
            sectorsTotal = 0,
            valueBlocks = emptyList(),
            pendingBlock = null,
            pendingOperation = null,
            pendingAmount = 0,
            error = null,
            success = null,
            progressDone = 0,
            progressTotal = 0,
        )
    }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Validates the amount and starts the second presentation. Returns false
     * when the form is invalid; [MfcValueState.error] then explains why.
     */
    fun startWrite(info: MfcValueBlockInfo, operation: MfcValueOperation, amountText: String): Boolean {
        val current = stateFlow.value
        if (current.busy || current.stage != MfcValueStage.LIST) return false
        if (info !in current.valueBlocks) return false
        val allowed = when (operation) {
            MfcValueOperation.INCREMENT -> info.canIncrement
            MfcValueOperation.DECREMENT -> info.canDecrement
        }
        if (!allowed) {
            stateFlow.value = current.copy(error = text(R.string.value_error_permission))
            return false
        }
        val amount = amountText.toIntOrNull()
        if (amount == null || amount <= 0) {
            stateFlow.value = current.copy(error = text(R.string.value_error_amount))
            return false
        }
        val expected = when (operation) {
            MfcValueOperation.INCREMENT -> info.value.toLong() + amount
            MfcValueOperation.DECREMENT -> info.value.toLong() - amount
        }
        if (expected < Int.MIN_VALUE || expected > Int.MAX_VALUE ||
            (operation == MfcValueOperation.DECREMENT && expected < 0)
        ) {
            stateFlow.value = current.copy(error = text(R.string.value_error_range))
            return false
        }
        pendingBlock = info
        pendingOperation = operation
        pendingAmount = amount
        stateFlow.value = current.copy(
            stage = MfcValueStage.WRITE_WAITING,
            pendingBlock = info,
            pendingOperation = operation,
            pendingAmount = amount,
            error = null,
            success = null,
        )
        return true
    }

    // -----------------------------------------------------------------------
    // Tag callback
    // -----------------------------------------------------------------------

    fun onTag(tag: Tag) {
        when (stateFlow.value.stage) {
            MfcValueStage.SCAN_WAITING -> scan(tag)
            MfcValueStage.WRITE_WAITING -> write(tag)
            else -> Unit
        }
    }

    private fun scan(tag: Tag) {
        val keyFile = keyFileBytes?.let { bytes ->
            KeyFileSource(keyFileLabel ?: "chiavi", bytes)
        }
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.SCAN_READING,
            error = null,
            success = null,
            progressDone = 0,
            progressTotal = 0,
        )
        viewModelScope.launch {
            try {
                val read = withContext(Dispatchers.IO) {
                    val transport = AndroidClassicTransport(tag)
                    try {
                        MfcReader(transport).read(
                            keyFile = keyFile,
                            requireComplete = false,
                        ) { done, total ->
                            if (stateFlow.value.visible) {
                                stateFlow.value = stateFlow.value.copy(
                                    progressDone = done,
                                    progressTotal = total,
                                )
                            }
                        }
                    } finally {
                        transport.close()
                    }
                }
                val blocks = withContext(Dispatchers.IO) { MfcValueTool.scan(read) }
                if (!stateFlow.value.visible) return@launch
                capture = read
                scanUid = tag.id.toHex()
                stateFlow.value = stateFlow.value.copy(
                    stage = MfcValueStage.LIST,
                    uidHex = scanUid,
                    tagLabel = read.size.label,
                    sectorsRead = read.readableSectors.size,
                    sectorsTotal = read.size.sectors,
                    valueBlocks = blocks,
                    error = null,
                    success = null,
                    progressDone = read.size.blocks,
                    progressTotal = read.size.blocks,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                failScan(R.string.value_error_lost)
            } catch (t: TagAuthException) {
                failScan(R.string.value_error_keys)
            } catch (t: Throwable) {
                failScan(R.string.value_error_scan, errorText(t))
            }
        }
    }

    private fun write(tag: Tag) {
        val read = capture
        val info = pendingBlock
        val operation = pendingOperation
        if (read == null || info == null || operation == null) {
            backToSetup()
            return
        }
        val uid = tag.id.toHex()
        val expectedUid = scanUid.ifEmpty {
            read.uidHex.takeIf { hex -> hex.any { it != '0' } } ?: ""
        }
        if (expectedUid.isNotEmpty() && !uid.equals(expectedUid, ignoreCase = true)) {
            clearPending()
            stateFlow.value = stateFlow.value.copy(
                stage = MfcValueStage.LIST,
                error = text(R.string.value_error_uid),
                success = null,
            )
            return
        }
        val amount = pendingAmount
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.WRITE_WORKING,
            error = null,
            success = null,
        )
        viewModelScope.launch {
            try {
                val change = withContext(Dispatchers.IO) {
                    val transport = AndroidClassicTransport(tag)
                    try {
                        MfcValueTool.apply(
                            transport = transport,
                            capture = read,
                            info = info,
                            operation = operation,
                            amount = amount,
                        )
                    } finally {
                        transport.close()
                    }
                }
                if (!stateFlow.value.visible) return@launch
                // The block was read back and verified, so the list can be
                // updated without a full rescan. The key map is still valid.
                val blocks = stateFlow.value.valueBlocks.map {
                    if (it.block == change.block) it.copy(value = change.value) else it
                }
                clearPending()
                stateFlow.value = stateFlow.value.copy(
                    stage = MfcValueStage.LIST,
                    valueBlocks = blocks,
                    pendingBlock = null,
                    pendingOperation = null,
                    pendingAmount = 0,
                    error = null,
                    success = change,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                failWrite(R.string.value_error_lost)
            } catch (t: TagAuthException) {
                failWrite(R.string.value_error_keys)
            } catch (t: MfcValuePermissionException) {
                failWrite(R.string.value_error_permission)
            } catch (t: MfcValueChangedException) {
                failWrite(R.string.value_error_changed, t.current)
            } catch (t: MfcValueRangeException) {
                failWrite(R.string.value_error_range)
            } catch (t: MfcValueVerifyException) {
                failWrite(R.string.value_error_verify)
            } catch (t: Throwable) {
                failWrite(R.string.value_error_write, errorText(t))
            }
        }
    }

    private fun failScan(message: Int, vararg args: Any) {
        if (!stateFlow.value.visible) return
        capture = null
        scanUid = ""
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.SETUP,
            error = text(message, *args),
        )
    }

    /**
     * After a failed write the card may have staged or committed something, so
     * the old capture is discarded and the user scans again.
     */
    private fun failWrite(message: Int, vararg args: Any) {
        if (!stateFlow.value.visible) return
        capture = null
        scanUid = ""
        clearPending()
        stateFlow.value = stateFlow.value.copy(
            stage = MfcValueStage.SETUP,
            uidHex = "",
            tagLabel = "",
            sectorsRead = 0,
            sectorsTotal = 0,
            valueBlocks = emptyList(),
            pendingBlock = null,
            pendingOperation = null,
            pendingAmount = 0,
            success = null,
            error = text(message, *args),
        )
    }

    private fun clearPending() {
        pendingBlock = null
        pendingOperation = null
        pendingAmount = 0
    }

    private fun text(message: Int, vararg args: Any): String =
        getApplication<Application>().getString(message, *args)

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
