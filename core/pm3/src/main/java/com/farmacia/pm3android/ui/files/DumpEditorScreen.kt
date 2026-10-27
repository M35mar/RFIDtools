// Full-screen editor for a .bin dump opened from the Data tab.
//
// Rows are blocks (MIFARE Classic) or pages (Ultralight/NTAG); tapping one opens
// a hex dialog. Rows the restore engine will not write (UID, lock bits, OTP,
// configuration area) are still editable but carry a badge.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.DumpFamily
import com.farmacia.pm3android.nfc.DumpRow
import com.farmacia.pm3android.nfc.DumpRowDetails
import com.farmacia.pm3android.nfc.DumpRowRole
import com.farmacia.pm3android.nfc.EditableDump
import com.farmacia.pm3android.nfc.hexToBytesOrNull
import com.farmacia.pm3android.nfc.toAscii
import com.farmacia.pm3android.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

private sealed interface EditorItem {
    data class SectorHeader(val sector: Int) : EditorItem
    data class RowItem(val row: DumpRow) : EditorItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumpEditorScreen(state: DumpEditorState, viewModel: DumpEditorViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = state.editIndex == null && !state.confirmDiscard) {
        viewModel.requestClose()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.dump?.name ?: stringResource(R.string.dump_editor_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            state.dump?.let { dump ->
                                Text(
                                    text = stringResource(
                                        R.string.dump_editor_file_info,
                                        dump.label,
                                        dump.rows.size * dump.rowSize,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::requestClose, enabled = !state.saving) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.dump_editor_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = viewModel::save,
                            enabled = state.dirty && !state.saving,
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = stringResource(R.string.dump_editor_save),
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val dump = state.dump
                if (dump != null && !state.loading) {
                    EditorBottomBar(
                        state = state,
                        onRevert = viewModel::revertAll,
                        onSave = viewModel::save,
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.loading -> LoadingBox()
                    state.dump == null -> ErrorBox(
                        message = state.error ?: stringResource(R.string.dump_editor_load_failed, ""),
                        onClose = viewModel::close,
                    )

                    else -> DumpContent(
                        dump = state.dump,
                        state = state,
                        onEdit = viewModel::beginEdit,
                    )
                }
            }
        }
    }

    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.dump_editor_unsaved_title),
            body = stringResource(
                R.string.dump_editor_unsaved_body,
                state.dump?.name.orEmpty(),
            ),
            confirmLabel = stringResource(R.string.dump_editor_discard),
            onConfirm = viewModel::discardAndClose,
            onDismiss = viewModel::cancelDiscard,
            destructive = true,
        )
    }

    val editingRow = state.editingRow
    val openDump = state.dump
    if (editingRow != null && openDump != null) {
        EditRowDialog(
            state = state,
            row = editingRow,
            family = openDump.family,
            rowCount = openDump.rows.size,
            onValueChange = viewModel::setEditHex,
            onApply = viewModel::applyEdit,
            onDismiss = viewModel::dismissEdit,
        )
    }
}

@Composable
private fun ColumnScope.DumpContent(
    dump: EditableDump,
    state: DumpEditorState,
    onEdit: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val entries = remember(dump) {
        buildList {
            if (dump.family == DumpFamily.CLASSIC) {
                var lastSector = -1
                dump.rows.forEach { row ->
                    val sector = row.sector ?: return@forEach
                    if (sector != lastSector) {
                        lastSector = sector
                        add(EditorItem.SectorHeader(sector))
                    }
                    add(EditorItem.RowItem(row))
                }
            } else {
                dump.rows.forEach { add(EditorItem.RowItem(it)) }
            }
        }
    }

    var jumpText by rememberSaveable { mutableStateOf("") }
    var jumpError by remember { mutableStateOf(false) }

    fun jump() {
        val index = jumpText.trim().toIntOrNull()
        if (index == null || index !in dump.rows.indices) {
            jumpError = true
            return
        }
        val target = entries.indexOfFirst { it is EditorItem.RowItem && it.row.index == index }
        if (target >= 0) {
            jumpError = false
            scope.launch { listState.animateScrollToItem(target) }
        }
    }

    SummaryCard(dump)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = jumpText,
            onValueChange = {
                jumpText = it.filter { ch -> ch.isDigit() }.take(3)
                jumpError = false
            },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.dump_editor_jump_label)) },
            placeholder = { Text(stringResource(R.string.dump_editor_jump_hint)) },
            singleLine = true,
            isError = jumpError,
            supportingText = if (jumpError) {
                { Text(stringResource(R.string.dump_editor_jump_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { jump() }),
        )
        Button(onClick = { jump() }) {
            Text(stringResource(R.string.dump_editor_jump_action))
        }
    }

    if (state.saving) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.dump_editor_saving),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = entries,
            key = { item ->
                when (item) {
                    is EditorItem.SectorHeader -> "sector-${item.sector}"
                    is EditorItem.RowItem -> "row-${item.row.index}"
                }
            },
        ) { item ->
            when (item) {
                is EditorItem.SectorHeader -> Text(
                    text = stringResource(R.string.dump_editor_sector, item.sector),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )

                is EditorItem.RowItem -> DumpRowCard(
                    family = dump.family,
                    row = item.row,
                    modified = dump.isModified(item.row.index),
                    enabled = !state.saving,
                    onClick = { onEdit(item.row.index) },
                )
            }
        }
        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun SummaryCard(dump: EditableDump) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InfoLine(
                label = stringResource(R.string.dump_editor_type),
                value = dump.label,
            )
            InfoLine(
                label = stringResource(R.string.dump_editor_uid),
                value = dump.uidHex.ifEmpty { "—" },
                monospace = true,
            )
            InfoLine(
                label = stringResource(R.string.dump_editor_rows),
                value = dump.rows.size.toString(),
            )
            InfoLine(
                label = stringResource(R.string.dump_editor_state),
                value = if (dump.dirty) {
                    stringResource(R.string.dump_editor_modified, dump.modifiedCount)
                } else {
                    stringResource(R.string.dump_editor_clean)
                },
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun DumpRowCard(
    family: DumpFamily,
    row: DumpRow,
    modified: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (modified) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rowTitle(family, row.index),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (modified) {
                    Text(
                        text = stringResource(R.string.dump_editor_modified_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = row.hex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.dump_editor_ascii, row.ascii),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.details.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = roleLabel(row.role),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!row.writtenByRestore) {
                    Text(
                        text = stringResource(R.string.dump_editor_not_written),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorBottomBar(
    state: DumpEditorState,
    onRevert: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.dirty) {
                    OutlinedButton(
                        onClick = onRevert,
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dump_editor_revert))
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = state.dirty && !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.dump_editor_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditRowDialog(
    state: DumpEditorState,
    row: DumpRow,
    family: DumpFamily,
    rowCount: Int,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val compact = state.editHex.filterNot { it.isWhitespace() }
    val valid = compact.length == row.byteCount * 2 &&
        compact.all { it.digitToIntOrNull(16) != null }
    val preview = state.editHex.hexToBytesOrNull()
        ?.takeIf { it.size == row.byteCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dump_editor_edit_title, rowTitle(family, row.index))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.editHex,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.dump_editor_hex_label)) },
                    singleLine = true,
                    isError = state.editError != null,
                    supportingText = {
                        Text(
                            text = state.editError ?: stringResource(
                                R.string.dump_editor_hex_help,
                                row.byteCount * 2,
                            ),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                if (preview != null) {
                    Text(
                        text = stringResource(R.string.dump_editor_ascii, preview.toAscii()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DumpRowDetails.describe(family, row.index, rowCount, preview).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = roleLabel(row.role),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!row.writtenByRestore) {
                    Text(
                        text = stringResource(R.string.dump_editor_not_written_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = valid) {
                Text(stringResource(R.string.dump_editor_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.dump_editor_loading),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorBox(message: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onClose) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun rowTitle(family: DumpFamily, index: Int): String = stringResource(
    if (family == DumpFamily.CLASSIC) {
        R.string.dump_editor_row_block
    } else {
        R.string.dump_editor_row_page
    },
    index,
)

@Composable
private fun roleLabel(role: DumpRowRole): String = stringResource(
    when (role) {
        DumpRowRole.DATA -> R.string.dump_editor_role_data
        DumpRowRole.USER -> R.string.dump_editor_role_user
        DumpRowRole.TRAILER -> R.string.dump_editor_role_trailer
        DumpRowRole.UID -> R.string.dump_editor_role_uid
        DumpRowRole.BCC -> R.string.dump_editor_role_bcc
        DumpRowRole.LOCK -> R.string.dump_editor_role_lock
        DumpRowRole.OTP -> R.string.dump_editor_role_otp
        DumpRowRole.CONFIG0 -> R.string.dump_editor_role_cfg0
        DumpRowRole.CONFIG1 -> R.string.dump_editor_role_cfg1
        DumpRowRole.PASSWORD -> R.string.dump_editor_role_pwd
        DumpRowRole.PACK -> R.string.dump_editor_role_pack
    },
)
