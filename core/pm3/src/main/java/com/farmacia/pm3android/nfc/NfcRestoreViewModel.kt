// State holder for the phone-NFC restore overlay.
//
// The ViewModel owns the loaded dump and the restore job; the screen only
// renders the state and starts reader mode. All tag I/O runs off the main
// thread, but the reader mode itself is kept enabled by the screen until the
// write finishes (disabling it would drop the field mid-write).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import android.app.Application
import android.net.Uri
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
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

enum class RestoreStage {
    LOADING,
    PREVIEW,
    WAITING,
    WORKING,
    DONE,
    ERROR,

    /** A tag was read: the file name is chosen before saving. */
    NAMING,

    /** The dump was written to the app folder. */
    SAVED,
}

/** What the overlay is doing: reading a tag into a new dump, or the reverse. */
enum class NfcMode {
    RESTORE,
    DUMP,
}

enum class RestoreWarning {
    UID_KEPT,
    LOCK_PAGES,
    CONFIG,
    KEYS_FROM_FILE,
    KEYS_MISSING,
    PASSWORD_FROM_DUMP,
    PASSWORD_DEFAULT,
    CONFIG_LOCKED,
}

enum class RestoreKind {
    CLASSIC,
    ULTRALIGHT,
}

data class NfcRestoreState(
    val visible: Boolean = false,
    val mode: NfcMode = NfcMode.RESTORE,
    val stage: RestoreStage = RestoreStage.LOADING,
    val dumpName: String = "",
    val tagLabel: String = "",
    val uidHex: String = "",
    val kind: RestoreKind = RestoreKind.CLASSIC,
    val itemCount: Int = 0,
    val byteSize: Int = 0,
    val warnings: List<RestoreWarning> = emptyList(),
    val error: String? = null,
    val needsPassword: Boolean = false,
    val password: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val report: RestoreReport? = null,
    val nameDraft: String = "",
    val savedName: String? = null,
    val savedKeyName: String? = null,
    val saving: Boolean = false,
    /** Key files (`.bin`/`.txt`) found in the Data tab, by relative path. */
    val keyFiles: List<String> = emptyList(),
    val selectedKeyFile: String? = null,
    val keyFileError: String? = null,
    val sectorsRead: Int = 0,
    val sectorsTotal: Int = 0,
    val unreadableBlocks: Int = 0,
    val saveKeys: Boolean = true,
) {
    val busy: Boolean get() = stage == RestoreStage.LOADING || stage == RestoreStage.WORKING

    val dumping: Boolean get() = mode == NfcMode.DUMP
}

class NfcRestoreViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(NfcRestoreState())
    val state: StateFlow<NfcRestoreState> = stateFlow

    private var image: DumpImage? = null
    private var mfuCapture: MfuCapture? = null
    private var mfcCapture: MfcCapture? = null
    private var keyFileBytes: ByteArray? = null
    private var keyFileLabel: String? = null

    fun open(file: File) {
        image = null
        stateFlow.value = NfcRestoreState(
            visible = true,
            stage = RestoreStage.LOADING,
            dumpName = file.name,
        )
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { DumpLoader.load(file) }
                if (loaded is DumpImage.Ultralight && !loaded.dump.layout.supported) {
                    // The file opens in the viewer, but this phone cannot write
                    // an unknown page map: say so before asking for the tag.
                    stateFlow.value = stateFlow.value.copy(
                        stage = RestoreStage.ERROR,
                        error = getApplication<Application>().getString(
                            R.string.nfc_error_restore_unsupported,
                            loaded.dump.pages.size,
                        ),
                    )
                    return@launch
                }
                image = loaded
                stateFlow.value = preview(loaded)
                refreshFileLists()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.ERROR,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_load,
                        errorText(t),
                    ),
                )
            }
        }
    }

    fun close() {
        if (stateFlow.value.stage == RestoreStage.WORKING) return
        image = null
        mfuCapture = null
        mfcCapture = null
        keyFileBytes = null
        keyFileLabel = null
        stateFlow.value = NfcRestoreState()
    }

    /** Opens the overlay in read mode: present a tag, save it as a .bin dump. */
    fun startDump() {
        image = null
        mfuCapture = null
        mfcCapture = null
        keyFileBytes = null
        keyFileLabel = null
        stateFlow.value = NfcRestoreState(
            visible = true,
            mode = NfcMode.DUMP,
            stage = RestoreStage.WAITING,
        )
        // The key files live in the Data tab, so their names are read once when
        // the overlay opens; the file itself is loaded when the user picks it.
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) { fileList() }
            val current = stateFlow.value
            if (current.visible && current.dumping) {
                stateFlow.value = current.copy(keyFiles = names)
            }
        }
    }

    /** Selects a key file from the Data tab; parsing waits for the tag size. */
    fun selectKeyFile(name: String) {
        val file = File(Pm3Resources.home(getApplication()), name)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val read = file.readBytes()
                    // Reject a file that is not a key list right away: the
                    // sector layout depends on the tag size, but a document
                    // without six-byte keys is unusable at any size.
                    MfcKeyFileParser.parse(read, MfcSize.CLASSIC_1K, name)
                    read
                }
                keyFileBytes = bytes
                keyFileLabel = name
                stateFlow.value = stateFlow.value.copy(
                    selectedKeyFile = name,
                    keyFileError = null,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                keyFileBytes = null
                keyFileLabel = null
                stateFlow.value = stateFlow.value.copy(
                    selectedKeyFile = null,
                    keyFileError = getApplication<Application>().getString(
                        R.string.nfc_dump_keys_error,
                        errorText(t),
                    ),
                )
            }
        }
    }

    fun clearKeyFile() {
        keyFileBytes = null
        keyFileLabel = null
        stateFlow.value = stateFlow.value.copy(
            selectedKeyFile = null,
            keyFileError = null,
        )
    }

    /** Copies a key file chosen from phone storage into the Data tab. */
    fun importKeyFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    NfcFileStore.import(getApplication(), uri)
                }
                refreshFileListsNow()
                selectKeyFile(relativeName(file))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    keyFileError = getApplication<Application>().getString(
                        R.string.nfc_import_error,
                        errorText(t),
                    ),
                )
            }
        }
    }

    private suspend fun refreshFileListsNow() {
        val names = withContext(Dispatchers.IO) { fileList() }
        stateFlow.value = stateFlow.value.copy(keyFiles = names)
    }

    private fun refreshFileLists() {
        viewModelScope.launch { refreshFileListsNow() }
    }

    /** `.bin`/`.txt` files in the app folder, relative to it. */
    private fun fileList(): List<String> {
        val home = Pm3Resources.home(getApplication())
        return MfcKeyFiles.listIn(home).map(::relativeName)
    }

    private fun relativeName(file: File): String {
        val home = Pm3Resources.home(getApplication())
        return file.relativeTo(home).path.replace('\\', '/')
    }

    fun setSaveKeys(enabled: Boolean) {
        stateFlow.value = stateFlow.value.copy(saveKeys = enabled)
    }

    fun setName(value: String) {
        stateFlow.value = stateFlow.value.copy(
            nameDraft = value.take(MAX_NAME_LENGTH),
        )
    }

    fun setPassword(value: String) {
        stateFlow.value = stateFlow.value.copy(
            password = value.filter { it.isLetterOrDigit() }.take(8).uppercase(),
            needsPassword = false,
        )
    }

    fun startWaiting() {
        val current = stateFlow.value
        if (current.stage != RestoreStage.PREVIEW && current.stage != RestoreStage.ERROR) return
        stateFlow.value = current.copy(
            stage = RestoreStage.WAITING,
            error = null,
            progressDone = 0,
            report = null,
        )
    }

    fun cancelWaiting() {
        val current = stateFlow.value
        if (current.stage != RestoreStage.WAITING) return
        if (current.mode == NfcMode.DUMP) {
            close()
        } else {
            stateFlow.value = current.copy(stage = RestoreStage.PREVIEW)
        }
    }

    fun retry() {
        val current = stateFlow.value
        if (current.mode == NfcMode.DUMP) {
            if (current.stage != RestoreStage.ERROR) return
            mfuCapture = null
            mfcCapture = null
            stateFlow.value = current.copy(
                stage = RestoreStage.WAITING,
                error = null,
                progressDone = 0,
                progressTotal = 0,
                nameDraft = "",
                sectorsRead = 0,
                sectorsTotal = 0,
                unreadableBlocks = 0,
            )
            return
        }
        if (current.stage != RestoreStage.DONE && current.stage != RestoreStage.ERROR) return
        stateFlow.value = current.copy(
            stage = RestoreStage.WAITING,
            error = null,
            progressDone = 0,
            report = null,
        )
    }

    /** Called from the reader-mode callback on the main thread. */
    fun onTag(tag: Tag) {
        val current = stateFlow.value
        if (current.stage != RestoreStage.WAITING) return
        if (current.mode == NfcMode.DUMP) {
            readDump(tag)
            return
        }
        val image = image ?: return
        stateFlow.value = current.copy(
            stage = RestoreStage.WORKING,
            error = null,
            report = null,
            progressDone = 0,
        )
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { runRestore(tag, image) }
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.DONE,
                    report = report,
                    progressDone = report.steps.size,
                    progressTotal = report.steps.size,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.PREVIEW,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_tag_lost,
                    ),
                )
            } catch (t: TagAuthException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.PREVIEW,
                    needsPassword = true,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_password,
                    ),
                )
            } catch (t: MfcPrecheckException) {
                // Nothing was written: the tag is usable but the restore would
                // stop halfway, so explain what is missing and let the user fix
                // the key file.
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.PREVIEW,
                    error = precheckMessage(t),
                )
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.ERROR,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_restore,
                        errorText(t),
                    ),
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dump
    // -----------------------------------------------------------------------

    private fun readDump(tag: Tag) {
        if (MifareClassic.get(tag) != null) {
            readClassicDump(tag)
            return
        }
        // Some clones expose the Classic SAK without the MifareClassic tech:
        // report it instead of failing later on GET_VERSION.
        val sak = runCatching { NfcA.get(tag)?.sak?.toInt()?.and(0xFF) }.getOrNull()
        if (sak != null && sak in CLASSIC_SAK) {
            stateFlow.value = stateFlow.value.copy(
                stage = RestoreStage.ERROR,
                error = getApplication<Application>().getString(
                    R.string.nfc_dump_error_classic_tech,
                ),
            )
            return
        }
        readUltralightDump(tag)
    }

    /**
     * MIFARE Classic: the keys come from the key file picked in the Data tab.
     * With a file selected only its keys are tried; without one the factory key
     * is used. The read must be complete: a partial dump would restore zeroes.
     */
    private fun readClassicDump(tag: Tag) {
        val current = stateFlow.value
        stateFlow.value = current.copy(
            stage = RestoreStage.WORKING,
            kind = RestoreKind.CLASSIC,
            error = null,
            progressDone = 0,
            progressTotal = 0,
            savedName = null,
            savedKeyName = null,
            sectorsRead = 0,
            sectorsTotal = 0,
            unreadableBlocks = 0,
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val transport = AndroidClassicTransport(tag)
                    try {
                        val keyFile = keyFileBytes?.let { bytes ->
                            KeyFileSource(keyFileLabel ?: "chiavi", bytes)
                        }
                        MfcReader(transport).read(
                            keyFile = keyFile,
                            requireComplete = true,
                        ) { done, total ->
                            stateFlow.value = stateFlow.value.copy(
                                progressDone = done,
                                progressTotal = total,
                            )
                        }
                    } finally {
                        transport.close()
                    }
                }
                mfcCapture = result
                // Block 0 is normally readable, but a card that hides it still
                // has a usable name from the Android UID.
                val uid = result.uidHex
                    .takeIf { hex -> hex.isNotEmpty() && hex.any { it != '0' } }
                    ?: tag.id.toHex()
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.NAMING,
                    tagLabel = result.size.label,
                    uidHex = uid,
                    kind = RestoreKind.CLASSIC,
                    itemCount = result.size.blocks,
                    byteSize = result.size.bytes,
                    sectorsRead = result.readableSectors.size,
                    sectorsTotal = result.size.sectors,
                    unreadableBlocks = result.unreadableBlocks.size,
                    nameDraft = "hf-mf-$uid-dump",
                    progressDone = result.size.blocks,
                    progressTotal = result.size.blocks,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.WAITING,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_tag_lost,
                    ),
                )
            } catch (t: TagAuthException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.WAITING,
                    error = getApplication<Application>().getString(
                        R.string.nfc_dump_error_keys,
                    ),
                )
            } catch (t: MfcIncompleteReadException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.WAITING,
                    error = getApplication<Application>().getString(
                        R.string.nfc_dump_error_incomplete,
                        t.missingSectors.size,
                        t.unreadableBlocks,
                    ),
                )
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.ERROR,
                    error = getApplication<Application>().getString(
                        R.string.nfc_dump_error_read,
                        errorText(t),
                    ),
                )
            }
        }
    }

    private fun readUltralightDump(tag: Tag) {
        val current = stateFlow.value
        val password = parsePassword(current.password)
        if (current.password.isNotEmpty() && password == null) {
            stateFlow.value = current.copy(
                stage = RestoreStage.WAITING,
                error = getApplication<Application>().getString(
                    R.string.nfc_option_password_invalid,
                ),
            )
            return
        }
        stateFlow.value = current.copy(
            stage = RestoreStage.WORKING,
            kind = RestoreKind.ULTRALIGHT,
            error = null,
            progressDone = 0,
            progressTotal = 0,
            savedName = null,
            savedKeyName = null,
            sectorsRead = 0,
            sectorsTotal = 0,
            unreadableBlocks = 0,
        )
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val transport = AndroidUltralightTransport(tag)
                    try {
                        MfuReader(transport).read(password) { done, total ->
                            stateFlow.value = stateFlow.value.copy(
                                progressDone = done,
                                progressTotal = total,
                            )
                        }
                    } finally {
                        transport.close()
                    }
                }
                mfuCapture = result
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.NAMING,
                    tagLabel = result.layout.variant,
                    uidHex = result.uidHex,
                    kind = RestoreKind.ULTRALIGHT,
                    itemCount = result.pages.size,
                    byteSize = result.pages.size * 4,
                    nameDraft = result.defaultName,
                    progressDone = result.pages.size,
                    progressTotal = result.pages.size,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: TagLostException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.WAITING,
                    error = getApplication<Application>().getString(
                        R.string.nfc_error_tag_lost,
                    ),
                )
            } catch (t: TagAuthException) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.WAITING,
                    needsPassword = true,
                    error = getApplication<Application>().getString(
                        R.string.nfc_dump_error_password,
                    ),
                )
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    stage = RestoreStage.ERROR,
                    error = getApplication<Application>().getString(
                        R.string.nfc_dump_error_read,
                        errorText(t),
                    ),
                )
            }
        }
    }

    /** Writes the captured dump under the chosen name, never overwriting. */
    fun saveDump() {
        val current = stateFlow.value
        if (current.stage != RestoreStage.NAMING || current.saving) return
        val classic = mfcCapture
        val ultralight = mfuCapture
        if (classic == null && ultralight == null) return
        val name = MfuDumpFiles.fileName(current.nameDraft)
        if (name == null) {
            stateFlow.value = current.copy(
                error = getApplication<Application>().getString(R.string.nfc_dump_name_invalid),
            )
            return
        }
        val saveKeys = current.saveKeys
        stateFlow.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    NfcDumpSaver.save(
                        context = getApplication(),
                        name = name,
                        classic = classic,
                        ultralight = ultralight,
                        saveKeys = saveKeys,
                    )
                }
                mfcCapture = null
                mfuCapture = null
                if (stateFlow.value.stage == RestoreStage.NAMING) {
                    stateFlow.value = stateFlow.value.copy(
                        stage = RestoreStage.SAVED,
                        error = null,
                        saving = false,
                        dumpName = saved.dump.name,
                        savedName = saved.dump.name,
                        savedKeyName = saved.keyFile?.name,
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (stateFlow.value.stage == RestoreStage.NAMING) {
                    stateFlow.value = stateFlow.value.copy(
                        saving = false,
                        error = getApplication<Application>().getString(
                            R.string.nfc_dump_error_save,
                            errorText(t),
                        ),
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    private fun runRestore(tag: Tag, image: DumpImage): RestoreReport {
        val current = stateFlow.value
        val progress = RestoreProgress { done, total ->
            stateFlow.value = stateFlow.value.copy(
                progressDone = done,
                progressTotal = total,
            )
        }
        return when (image) {
            is DumpImage.Classic -> {
                val transport = AndroidClassicTransport(tag)
                try {
                    val dump = image.dump
                    val home = Pm3Resources.home(getApplication())
                    val parsed = current.selectedKeyFile
                        ?.let { MfcKeyFileParser.parse(File(home, it), dump.size) }
                    // The key file only opens the destination tag: the keys and
                    // the access bits written into a trailer always come from
                    // the dump. Nothing is overlaid any more.
                    val candidates = buildList {
                        parsed?.allKeys()?.let(::addAll)
                        dump.keys?.allKeys()?.let(::addAll)
                    }
                    MfcRestorer(transport).restore(
                        dump,
                        MfcRestoreOptions(candidates = candidates),
                        progress,
                    )
                } finally {
                    transport.close()
                }
            }

            is DumpImage.Ultralight -> {
                val transport = AndroidUltralightTransport(tag)
                try {
                    val restorer = MfuRestorer(transport)
                    val options = MfuRestoreOptions(
                        password = parsePassword(current.password),
                        writeConfig = true,
                    )
                    restorer.restore(image.dump, options, snapshot = null, progress = progress)
                } finally {
                    transport.close()
                }
            }
        }
    }

    private fun parsePassword(text: String): ByteArray? {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.length != 8) return null
        return compact.hexToBytesOrNull()
    }

    /** Turns the pre-check result into a localised, actionable message. */
    private fun precheckMessage(error: MfcPrecheckException): String {
        val res = getApplication<Application>()
        if (error.dumpSize != null) {
            return res.getString(
                R.string.nfc_error_precheck_size,
                error.dumpSize.label,
                error.tagSectors,
            )
        }
        val details = mutableListOf<String>()
        if (error.unopenedSectors.isNotEmpty()) {
            details += res.getString(
                R.string.nfc_precheck_unopened,
                error.unopenedSectors.joinToString(", "),
            )
        }
        if (error.missingKeys.isNotEmpty()) {
            val items = error.missingKeys.map { missing ->
                if (missing.block == null) {
                    res.getString(R.string.nfc_precheck_trailer, missing.sector)
                } else {
                    res.getString(
                        if (missing.keyB) {
                            R.string.nfc_precheck_block_b
                        } else {
                            R.string.nfc_precheck_block_a
                        },
                        missing.sector,
                        missing.block,
                    )
                }
            }
            details += res.getString(R.string.nfc_precheck_keys, items.joinToString(", "))
        }
        return res.getString(R.string.nfc_error_precheck, details.joinToString("; "))
    }

    // -----------------------------------------------------------------------
    // Preview
    // -----------------------------------------------------------------------

    private fun preview(image: DumpImage): NfcRestoreState = when (image) {
        is DumpImage.Classic -> {
            val dump = image.dump
            val warnings = buildList {
                add(RestoreWarning.UID_KEPT)
                add(RestoreWarning.CONFIG)
                if (dump.keys != null || dump.keysInTrailers()) {
                    add(RestoreWarning.KEYS_FROM_FILE)
                } else {
                    add(RestoreWarning.KEYS_MISSING)
                }
            }
            stateFlow.value.copy(
                stage = RestoreStage.PREVIEW,
                dumpName = dump.name,
                tagLabel = dump.size.label,
                uidHex = dump.uidHex,
                kind = RestoreKind.CLASSIC,
                itemCount = dump.size.blocks,
                byteSize = dump.size.bytes,
                warnings = warnings,
                progressDone = 0,
                progressTotal = dump.size.blocks,
            )
        }

        is DumpImage.Ultralight -> {
            val dump = image.dump
            val layout = dump.layout
            val warnings = buildList {
                add(RestoreWarning.UID_KEPT)
                add(RestoreWarning.LOCK_PAGES)
                add(RestoreWarning.CONFIG)
                if (dump.page(layout.pwdPage).any { it != 0.toByte() }) {
                    add(RestoreWarning.PASSWORD_FROM_DUMP)
                } else {
                    add(RestoreWarning.PASSWORD_DEFAULT)
                }
                if ((dump.page(layout.cfg1)[0].toInt() and 0x40) != 0) {
                    add(RestoreWarning.CONFIG_LOCKED)
                }
            }
            stateFlow.value.copy(
                stage = RestoreStage.PREVIEW,
                dumpName = dump.name,
                tagLabel = layout.variant,
                uidHex = dump.uidHex,
                kind = RestoreKind.ULTRALIGHT,
                itemCount = dump.pages.size,
                byteSize = dump.pages.size * 4,
                warnings = warnings,
                progressDone = 0,
                progressTotal = dump.pages.size,
            )
        }
    }

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private companion object {

        /**
         * SAKs of MIFARE Classic (and the ISO/IEC 14443-3 clones that report a
         * Classic SAK): 1K, 4K, Mini, Plus SL1, Infineon and the common clones.
         */
        val CLASSIC_SAK = setOf(
            0x08, 0x18, 0x09,
            0x28, 0x38,
            0x88, 0x98,
            0x10, 0x11,
            0xA8, 0xB8,
        )
    }
}

private const val MAX_NAME_LENGTH = 80
