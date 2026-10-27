// State holder for the in-app .bin dump editor.
//
// The overlay edits a copy of the file and only writes on demand; the save is
// atomic (temporary file next to the original, then rename) so a crash cannot
// leave a half-written dump behind.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.Pm3FileFilter
import com.farmacia.pm3android.nfc.DumpRow
import com.farmacia.pm3android.nfc.EditableDump
import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.pm3.Pm3Resources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DumpEditorState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dump: EditableDump? = null,
    val error: String? = null,
    val message: String? = null,
    val editIndex: Int? = null,
    val editHex: String = "",
    val editError: String? = null,
    val confirmDiscard: Boolean = false,
    val savedToken: Long = 0,
) {
    val editingRow: DumpRow? get() = editIndex?.let { dump?.rows?.getOrNull(it) }

    val dirty: Boolean get() = dump?.dirty == true
}

class DumpEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(DumpEditorState())
    val state: StateFlow<DumpEditorState> = stateFlow

    fun open(file: File) {
        if (stateFlow.value.saving || stateFlow.value.visible) return
        val token = stateFlow.value.savedToken
        stateFlow.value = DumpEditorState(visible = true, loading = true, savedToken = token)
        viewModelScope.launch {
            try {
                val dump = withContext(Dispatchers.IO) { EditableDump.load(file) }
                if (stateFlow.value.visible) {
                    stateFlow.value = stateFlow.value.copy(loading = false, dump = dump)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (stateFlow.value.visible) {
                    stateFlow.value = stateFlow.value.copy(
                        loading = false,
                        error = getApplication<Application>().getString(
                            R.string.dump_editor_load_failed,
                            errorText(t),
                        ),
                    )
                }
            }
        }
    }

    fun beginEdit(index: Int) {
        val dump = stateFlow.value.dump ?: return
        val row = dump.rows.getOrNull(index) ?: return
        stateFlow.value = stateFlow.value.copy(
            editIndex = index,
            editHex = row.hex,
            editError = null,
        )
    }

    fun setEditHex(value: String) {
        val row = stateFlow.value.editingRow ?: return
        val max = row.byteCount * 3
        val filtered = value.uppercase()
            .filter { it == ' ' || it.digitToIntOrNull(16) != null }
            .take(max)
        stateFlow.value = stateFlow.value.copy(editHex = filtered, editError = null)
    }

    fun dismissEdit() {
        stateFlow.value = stateFlow.value.copy(
            editIndex = null,
            editHex = "",
            editError = null,
        )
    }

    fun applyEdit() {
        val current = stateFlow.value
        val dump = current.dump ?: return
        val index = current.editIndex ?: return
        val row = dump.rows.getOrNull(index) ?: return
        val parsed = current.editHex.hexToBytesOrNull()
        if (parsed == null || parsed.size != row.byteCount) {
            stateFlow.value = current.copy(
                editError = getApplication<Application>().getString(
                    R.string.dump_editor_hex_invalid,
                    row.byteCount * 2,
                ),
            )
            return
        }
        stateFlow.value = current.copy(
            dump = dump.edit(index, parsed),
            editIndex = null,
            editHex = "",
            editError = null,
        )
    }

    fun revertAll() {
        val dump = stateFlow.value.dump ?: return
        if (!dump.dirty) return
        stateFlow.value = stateFlow.value.copy(dump = dump.revert())
    }

    fun save() {
        val current = stateFlow.value
        val dump = current.dump ?: return
        if (current.saving || !dump.dirty) return
        val app = getApplication<Application>()
        val home = Pm3Resources.home(app)
        if (!Pm3FileFilter.isInsideHome(home, dump.file)) {
            stateFlow.value = current.copy(
                message = app.getString(R.string.files_protected),
            )
            return
        }
        stateFlow.value = current.copy(saving = true, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeAtomic(dump.file, dump.toBytes()) }
                stateFlow.value = stateFlow.value.copy(
                    saving = false,
                    dump = dump.saved(),
                    savedToken = stateFlow.value.savedToken + 1,
                    message = app.getString(R.string.dump_editor_saved),
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                stateFlow.value = stateFlow.value.copy(
                    saving = false,
                    message = app.getString(R.string.dump_editor_save_failed, errorText(t)),
                )
            }
        }
    }

    /** Back arrow / system back: asks for confirmation when there are edits. */
    fun requestClose() {
        val current = stateFlow.value
        if (current.saving) return
        if (current.dirty) {
            stateFlow.value = current.copy(confirmDiscard = true)
        } else {
            close()
        }
    }

    fun cancelDiscard() {
        stateFlow.value = stateFlow.value.copy(confirmDiscard = false)
    }

    fun discardAndClose() {
        stateFlow.value = stateFlow.value.copy(confirmDiscard = false)
        close()
    }

    fun close() {
        val current = stateFlow.value
        if (current.saving) return
        stateFlow.value = DumpEditorState(savedToken = current.savedToken)
    }

    fun clearMessage() {
        stateFlow.value = stateFlow.value.copy(message = null)
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IllegalStateException("cartella non valida")
        val temp = File(parent, ".${file.name}.tmp")
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                file.writeBytes(bytes)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
