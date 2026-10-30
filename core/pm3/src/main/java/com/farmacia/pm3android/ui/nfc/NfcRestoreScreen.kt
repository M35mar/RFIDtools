// Full-screen overlay used to write a dump to a tag with the phone's NFC
// reader. It is rendered above the main Scaffold so it is reachable from both
// the Files tab and the Tools tab.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.MfuDumpFiles
import com.farmacia.pm3android.nfc.NfcRestoreState
import com.farmacia.pm3android.nfc.NfcRestoreViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.RestoreKind
import com.farmacia.pm3android.nfc.RestoreStage
import com.farmacia.pm3android.nfc.RestoreWarning
import com.farmacia.pm3android.nfc.StepNote
import com.farmacia.pm3android.nfc.StepResult
import com.farmacia.pm3android.nfc.StepStatus
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcRestoreScreen(
    state: NfcRestoreState,
    viewModel: NfcRestoreViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }

    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabled = session?.isEnabled == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Reader mode must survive the WAITING -> WORKING transition: disabling it
    // would drop the field in the middle of the write.
    val listening = state.stage == RestoreStage.WAITING ||
        state.stage == RestoreStage.WORKING
    DisposableEffect(listening, session) {
        if (listening) {
            session?.start { tag -> viewModel.onTag(tag) }
        } else {
            session?.stop()
        }
        onDispose { session?.stop() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.nfc_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, enabled = !state.busy) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nfc_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.stage) {
                    RestoreStage.LOADING -> LoadingCard()
                    RestoreStage.WAITING -> WaitingCard(
                        state = state,
                        nfcEnabled = nfcEnabled,
                        viewModel = viewModel,
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                    )
                    RestoreStage.WORKING -> WorkingCard(state)
                    RestoreStage.NAMING -> NameCard(
                        state = state,
                        onNameChange = viewModel::setName,
                        onSaveKeysChange = viewModel::setSaveKeys,
                        onSave = viewModel::saveDump,
                        onClose = onClose,
                    )
                    RestoreStage.SAVED -> SavedCard(state = state, onClose = onClose)
                    RestoreStage.DONE -> DoneCard(
                        state = state,
                        onRetry = viewModel::retry,
                        onClose = onClose,
                    )
                    RestoreStage.PREVIEW -> PreviewCard(
                        state = state,
                        nfcEnabled = nfcEnabled,
                        nfcAvailable = session != null,
                        viewModel = viewModel,
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                        onClose = onClose,
                    )
                    RestoreStage.ERROR -> if (state.dumping) {
                        DumpErrorCard(
                            state = state,
                            onRetry = viewModel::retry,
                            onClose = onClose,
                        )
                    } else {
                        PreviewCard(
                            state = state,
                            nfcEnabled = nfcEnabled,
                            nfcAvailable = session != null,
                            viewModel = viewModel,
                            onOpenSettings = {
                                activity?.let { NfcSession.openSystemSettings(it) }
                            },
                            onClose = onClose,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.nfc_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PreviewCard(
    state: NfcRestoreState,
    nfcEnabled: Boolean,
    nfcAvailable: Boolean,
    viewModel: NfcRestoreViewModel,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    state.error?.let { message -> ErrorCard(message) }

    if (state.tagLabel.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.nfc_dump_title)) {
            InfoRow(stringResource(R.string.nfc_dump_file), state.dumpName)
            InfoRow(stringResource(R.string.nfc_tag_type), state.tagLabel)
            InfoRow(stringResource(R.string.nfc_uid), state.uidHex)
            InfoRow(
                stringResource(R.string.nfc_size),
                if (state.kind == RestoreKind.CLASSIC) {
                    stringResource(R.string.nfc_size_blocks, state.itemCount, state.byteSize)
                } else {
                    stringResource(R.string.nfc_size_pages, state.itemCount, state.byteSize)
                },
            )
        }

        SectionCard(title = stringResource(R.string.nfc_warnings_title)) {
            state.warnings.forEach { warning -> WarningRow(warning) }
        }

        OptionsCard(state = state, viewModel = viewModel)
    }

    if (!nfcAvailable) {
        NoticeCard(
            title = stringResource(R.string.nfc_unavailable_title),
            body = stringResource(R.string.nfc_unavailable_body),
        )
    } else if (!nfcEnabled) {
        NoticeCard(
            title = stringResource(R.string.nfc_off_title),
            body = stringResource(R.string.nfc_off_body),
        ) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.nfc_off_settings))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.stage == RestoreStage.ERROR) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.nfc_close))
            }
        }
        Button(
            onClick = viewModel::startWaiting,
            enabled = nfcEnabled && nfcAvailable && state.tagLabel.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.nfc_start))
        }
    }
}

@Composable
private fun OptionsCard(
    state: NfcRestoreState,
    viewModel: NfcRestoreViewModel,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.nfc_options),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.kind == RestoreKind.ULTRALIGHT) {
            val passwordValid = state.password.isEmpty() || state.password.length == 8
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.nfc_option_password)) },
                placeholder = { Text(stringResource(R.string.nfc_option_password_hint)) },
                singleLine = true,
                isError = !passwordValid,
                supportingText = {
                    Text(
                        if (passwordValid) {
                            stringResource(R.string.nfc_option_password_help)
                        } else {
                            stringResource(R.string.nfc_option_password_invalid)
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        } else {
            KeyFilePicker(
                state = state,
                onSelect = viewModel::selectKeyFile,
                onClear = viewModel::clearKeyFile,
                onImport = viewModel::importKeyFile,
            )
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WarningRow(warning: RestoreWarning) {
    val text = when (warning) {
        RestoreWarning.UID_KEPT -> stringResource(R.string.nfc_warn_uid)
        RestoreWarning.LOCK_PAGES -> stringResource(R.string.nfc_warn_lock)
        RestoreWarning.CONFIG -> stringResource(R.string.nfc_warn_config)
        RestoreWarning.KEYS_FROM_FILE -> stringResource(R.string.nfc_warn_keys)
        RestoreWarning.KEYS_MISSING -> stringResource(R.string.nfc_warn_keys_missing)
        RestoreWarning.PASSWORD_FROM_DUMP -> stringResource(R.string.nfc_warn_pwd_dump)
        RestoreWarning.PASSWORD_DEFAULT -> stringResource(R.string.nfc_warn_pwd_default)
        RestoreWarning.CONFIG_LOCKED -> stringResource(R.string.nfc_warn_cfg_locked)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WaitingCard(
    state: NfcRestoreState,
    nfcEnabled: Boolean,
    viewModel: NfcRestoreViewModel,
    onOpenSettings: () -> Unit,
) {
    if (!nfcEnabled) {
        NoticeCard(
            title = stringResource(R.string.nfc_off_title),
            body = stringResource(R.string.nfc_off_body),
        ) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.nfc_off_settings))
            }
        }
        OutlinedButton(onClick = viewModel::cancelWaiting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nfc_cancel))
        }
        return
    }

    state.error?.let { message -> ErrorCard(message) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(
                    if (state.dumping) R.string.nfc_dump_waiting_title
                    else R.string.nfc_waiting_title,
                ),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (state.dumping) R.string.nfc_dump_waiting_body
                    else R.string.nfc_waiting_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (state.dumpName.isNotEmpty()) {
                Text(
                    text = state.dumpName,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
        }
    }

    if (state.dumping) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.nfc_dump_password)) },
            placeholder = { Text(stringResource(R.string.nfc_option_password_hint)) },
            singleLine = true,
            isError = state.password.isNotEmpty() && state.password.length != 8,
            supportingText = { Text(stringResource(R.string.nfc_dump_password_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        // The tag technology is only known once a tag is presented, so both
        // the Classic keys and the Ultralight password stay available here.
        KeyFilePicker(
            state = state,
            onSelect = viewModel::selectKeyFile,
            onClear = viewModel::clearKeyFile,
            onImport = viewModel::importKeyFile,
        )
    }

    OutlinedButton(onClick = viewModel::cancelWaiting, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.nfc_cancel))
    }
}

/** Key file already in the Data tab, picked before the tag is presented. */
@Composable
private fun KeyFilePicker(
    state: NfcRestoreState,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    NfcKeyFilePicker(
        title = stringResource(R.string.nfc_dump_keys_title),
        selected = state.selectedKeyFile,
        files = state.keyFiles,
        noneLabel = stringResource(R.string.nfc_dump_keys_none),
        emptyLabel = stringResource(R.string.nfc_dump_keys_empty),
        help = stringResource(R.string.nfc_dump_keys_help),
        error = state.keyFileError,
        onSelect = onSelect,
        onClear = onClear,
        onImport = onImport,
    )
}

@Composable
private fun WorkingCard(state: NfcRestoreState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.dumping) R.string.nfc_dump_reading_title
                    else R.string.nfc_working_title,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (state.dumping) {
                    stringResource(
                        if (state.kind == RestoreKind.CLASSIC) {
                            R.string.nfc_dump_reading_blocks
                        } else {
                            R.string.nfc_dump_reading_body
                        },
                        state.progressDone,
                        state.progressTotal,
                    )
                } else {
                    stringResource(
                        R.string.nfc_working_body,
                        state.progressDone,
                        state.progressTotal,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val fraction = if (state.progressTotal > 0) {
                state.progressDone.toFloat() / state.progressTotal
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    if (state.dumping) R.string.nfc_dump_reading_hint
                    else R.string.nfc_working_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tag already read: the file name is chosen before anything is written. */
@Composable
private fun NameCard(
    state: NfcRestoreState,
    onNameChange: (String) -> Unit,
    onSaveKeysChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val nameValid = MfuDumpFiles.fileName(state.nameDraft) != null
    val classic = state.kind == RestoreKind.CLASSIC

    state.error?.let { message -> ErrorCard(message) }

    SectionCard(title = stringResource(R.string.nfc_dump_name_title)) {
        InfoRow(stringResource(R.string.nfc_tag_type), state.tagLabel)
        InfoRow(stringResource(R.string.nfc_uid), state.uidHex)
        InfoRow(
            stringResource(R.string.nfc_size),
            if (classic) {
                stringResource(R.string.nfc_size_blocks, state.itemCount, state.byteSize)
            } else {
                stringResource(R.string.nfc_size_pages, state.itemCount, state.byteSize)
            },
        )
        if (classic && state.sectorsTotal > 0) {
            InfoRow(
                stringResource(R.string.nfc_dump_sectors),
                stringResource(
                    R.string.nfc_dump_sectors_value,
                    state.sectorsRead,
                    state.sectorsTotal,
                ),
            )
        }
        if (state.unreadableBlocks > 0) {
            InfoRow(
                stringResource(R.string.nfc_dump_unreadable),
                state.unreadableBlocks.toString(),
            )
            Text(
                text = stringResource(R.string.nfc_dump_unreadable_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = state.nameDraft,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.nfc_dump_name_label)) },
            singleLine = true,
            isError = !nameValid,
            supportingText = { Text(stringResource(R.string.nfc_dump_name_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        if (classic) {
            CheckRow(
                checked = state.saveKeys,
                label = stringResource(R.string.nfc_dump_save_keys),
                onCheckedChange = onSaveKeysChange,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nfc_cancel))
        }
        Button(
            onClick = onSave,
            enabled = nameValid && !state.saving,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.nfc_dump_save))
        }
    }
}

@Composable
private fun SavedCard(state: NfcRestoreState, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null)
                Text(
                    text = stringResource(R.string.nfc_dump_saved_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            state.savedName?.let { name ->
                Text(name, style = MaterialTheme.typography.titleSmall)
            }
            state.savedKeyName?.let { name ->
                InfoRow(stringResource(R.string.nfc_dump_key_saved), name)
            }
            InfoRow(stringResource(R.string.nfc_tag_type), state.tagLabel)
            InfoRow(stringResource(R.string.nfc_uid), state.uidHex)
            Text(
                text = stringResource(R.string.nfc_dump_saved_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.nfc_close))
    }
}

@Composable
private fun DoneCard(
    state: NfcRestoreState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val report = state.report
    val partial = report != null && !report.ok
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (partial) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (partial) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(
                        if (partial) R.string.nfc_done_partial else R.string.nfc_done_ok,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (report != null) {
                Text(report.tagLabel, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResultCount(R.string.nfc_result_written, report.written)
                    ResultCount(R.string.nfc_result_unchanged, report.unchanged)
                    ResultCount(R.string.nfc_result_skipped, report.skipped)
                    ResultCount(R.string.nfc_result_failed, report.failed)
                }
            }
        }
    }

    if (report != null && report.highlights.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.nfc_detail_title)) {
            report.highlights.take(40).forEach { step ->
                StepRow(step, state.kind)
            }
            if (report.highlights.size > 40) {
                Text(
                    text = stringResource(R.string.nfc_detail_more, report.highlights.size - 40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nfc_retry))
        }
        Button(onClick = onClose, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nfc_close))
        }
    }
}

@Composable
private fun ResultCount(labelRes: Int, value: Int) {
    Column {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun StepRow(step: StepResult, kind: RestoreKind) {
    val label = if (kind == RestoreKind.CLASSIC) {
        stringResource(R.string.nfc_step_block, step.index)
    } else {
        stringResource(R.string.nfc_step_page, step.index)
    }
    val statusColor: Color = when (step.status) {
        StepStatus.WRITTEN, StepStatus.VERIFIED -> MaterialTheme.colorScheme.primary
        StepStatus.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(statusText(step.status)),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }
        step.note?.let { note ->
            Text(
                text = stringResource(noteText(note)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusText(status: StepStatus): Int = when (status) {
    StepStatus.WRITTEN -> R.string.nfc_status_written
    StepStatus.VERIFIED -> R.string.nfc_status_verified
    StepStatus.UNCHANGED -> R.string.nfc_status_unchanged
    StepStatus.SKIPPED -> R.string.nfc_status_skipped
    StepStatus.FAILED -> R.string.nfc_status_failed
}

private fun noteText(note: StepNote): Int = when (note) {
    StepNote.UID_KEPT -> R.string.nfc_note_uid
    StepNote.LOCK_BITS -> R.string.nfc_note_lock
    StepNote.OTP -> R.string.nfc_note_otp
    StepNote.CONFIG_LOCKED -> R.string.nfc_note_cfg_locked
    StepNote.CONFIG_UNKNOWN -> R.string.nfc_note_cfg_unknown
    StepNote.ACCESS_FIXED -> R.string.nfc_note_access_fixed
    StepNote.KEEP_CURRENT_KEYS -> R.string.nfc_note_keep_keys
    StepNote.READ_ONLY -> R.string.nfc_note_read_only
    StepNote.KEY_B_REQUIRED -> R.string.nfc_note_key_b
    StepNote.AUTH_FAILED -> R.string.nfc_note_auth
    StepNote.WRITE_FAILED -> R.string.nfc_note_write
    StepNote.VERIFY_FAILED -> R.string.nfc_note_verify
}

/** Read-side failure: only retry or close, nothing to write or preview. */
@Composable
private fun DumpErrorCard(
    state: NfcRestoreState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    state.error?.let { message -> ErrorCard(message) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nfc_close))
        }
        Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nfc_retry))
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = body, style = MaterialTheme.typography.bodySmall)
            action?.invoke()
        }
    }
}
