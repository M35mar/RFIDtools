// Full-screen overlay for incrementing or decrementing a MIFARE Classic value
// block (stored credit or ride count). The first tap scans the card with the
// chosen key file; the second tap applies INCREMENT/DECREMENT + TRANSFER.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.MfcKeyRequirement
import com.farmacia.pm3android.nfc.MfcKeyType
import com.farmacia.pm3android.nfc.MfcValueBlockInfo
import com.farmacia.pm3android.nfc.MfcValueChange
import com.farmacia.pm3android.nfc.MfcValueOperation
import com.farmacia.pm3android.nfc.MfcValueStage
import com.farmacia.pm3android.nfc.MfcValueState
import com.farmacia.pm3android.nfc.MfcValueViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard

private data class ValueEditor(
    val info: MfcValueBlockInfo,
    val operation: MfcValueOperation,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfcValueScreen(
    state: MfcValueState,
    viewModel: MfcValueViewModel,
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

    DisposableEffect(state.readerActive, nfcEnabled, session) {
        if (state.readerActive && nfcEnabled) {
            session?.start { tag -> viewModel.onTag(tag) }
        } else {
            session?.stop()
        }
        onDispose { session?.stop() }
    }

    var editor by remember { mutableStateOf<ValueEditor?>(null) }
    LaunchedEffect(state.stage) {
        if (state.stage != MfcValueStage.LIST) editor = null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.value_title)) },
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
                    MfcValueStage.SETUP -> ValueSetup(
                        state = state,
                        nfcEnabled = nfcEnabled,
                        nfcAvailable = session != null,
                        viewModel = viewModel,
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                    )

                    MfcValueStage.SCAN_WAITING -> ScanWaitingCard(
                        state = state,
                        viewModel = viewModel,
                    )

                    MfcValueStage.SCAN_READING -> ScanWorkingCard(state)

                    MfcValueStage.LIST -> ValueList(
                        state = state,
                        viewModel = viewModel,
                        onOperation = { info, operation ->
                            editor = ValueEditor(info, operation)
                        },
                    )

                    MfcValueStage.WRITE_WAITING -> WriteWaitingCard(
                        state = state,
                        viewModel = viewModel,
                    )

                    MfcValueStage.WRITE_WORKING -> WriteWorkingCard()
                }
            }
        }
    }

    editor?.let { current ->
        ValueAmountDialog(
            info = current.info,
            operation = current.operation,
            onDismiss = { editor = null },
            onConfirm = { amount ->
                viewModel.startWrite(current.info, current.operation, amount)
                editor = null
            },
        )
    }
}

@Composable
private fun ValueSetup(
    state: MfcValueState,
    nfcEnabled: Boolean,
    nfcAvailable: Boolean,
    viewModel: MfcValueViewModel,
    onOpenSettings: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.value_title)) {
        Text(
            text = stringResource(R.string.value_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.value_only_standard),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NfcKeyFilePicker(
            title = stringResource(R.string.nfc_dump_keys_title),
            selected = state.selectedKeyFile,
            files = state.keyFiles,
            noneLabel = stringResource(R.string.nfc_dump_keys_none),
            emptyLabel = stringResource(R.string.nfc_dump_keys_empty),
            help = stringResource(R.string.value_keys_help),
            error = state.keyFileError,
            onSelect = viewModel::selectKeyFile,
            onClear = viewModel::clearKeyFile,
            onImport = viewModel::importKeyFile,
        )
        if (state.keyFileLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (!nfcAvailable || !nfcEnabled) {
            ValueNfcCard(available = nfcAvailable, onOpenSettings = onOpenSettings)
        }
        state.error?.let { ValueError(it) }
        Button(
            onClick = { viewModel.beginScan() },
            enabled = nfcEnabled && nfcAvailable && !state.keyFileLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.value_scan_start))
        }
    }
}

@Composable
private fun ScanWaitingCard(state: MfcValueState, viewModel: MfcValueViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.value_scan_waiting_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.value_scan_waiting_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = viewModel::cancelWaiting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.nfc_cancel))
            }
        }
    }
}

@Composable
private fun ScanWorkingCard(state: MfcValueState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.value_scan_working),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (state.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = {
                        state.progressDone.toFloat() / state.progressTotal.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.value_scan_progress,
                        state.progressDone,
                        state.progressTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ValueList(
    state: MfcValueState,
    viewModel: MfcValueViewModel,
    onOperation: (MfcValueBlockInfo, MfcValueOperation) -> Unit,
) {
    SectionCard(title = stringResource(R.string.value_tag_title)) {
        InfoRow(stringResource(R.string.value_row_tag), state.tagLabel)
        InfoRow(stringResource(R.string.inspect_uid), state.uidHex)
        InfoRow(
            stringResource(R.string.value_row_sectors),
            stringResource(R.string.value_row_sectors_value, state.sectorsRead, state.sectorsTotal),
        )
        InfoRow(stringResource(R.string.value_row_blocks), state.valueBlocks.size.toString())
    }

    if (state.ricaricaMi) {
        SectionCard(title = stringResource(R.string.value_ricarica_title)) {
            Text(
                text = stringResource(R.string.value_ricarica_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.success?.let { SuccessCard(it) }
    state.error?.let { ValueError(it) }

    if (state.valueBlocks.isEmpty()) {
        SectionCard {
            Text(
                text = stringResource(
                    if (state.ricaricaMi) R.string.value_empty_ricarica else R.string.value_empty,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        state.valueBlocks.forEach { info ->
            ValueBlockCard(info = info, onOperation = onOperation)
        }
    }

    OutlinedButton(
        onClick = { viewModel.beginScan() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.value_scan_again))
    }
    TextButton(
        onClick = viewModel::backToSetup,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.value_change_keys))
    }
}

@Composable
private fun SuccessCard(change: MfcValueChange) {
    SectionCard(title = stringResource(R.string.value_success_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    R.string.value_success_body,
                    change.block,
                    change.previous,
                    change.value,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(
                if (change.operation == MfcValueOperation.INCREMENT) {
                    R.string.value_success_added
                } else {
                    R.string.value_success_removed
                },
                change.amount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (change.keyType == MfcKeyType.A) {
                    R.string.value_success_key_a
                } else {
                    R.string.value_success_key_b
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.value_success_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ValueBlockCard(
    info: MfcValueBlockInfo,
    onOperation: (MfcValueBlockInfo, MfcValueOperation) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.value_block_line, info.sector, info.block),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = info.value.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            if (!info.addressMatches) {
                Text(
                    text = stringResource(
                        R.string.value_address_nonstandard,
                        info.address,
                        info.block,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!info.trailerReadable || !info.aclValid) {
                Text(
                    text = stringResource(R.string.value_acl_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!info.canIncrement && !info.canDecrement) {
                Text(
                    text = stringResource(R.string.value_acl_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.value_acl_increment,
                        accessText(info, increment = true),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.value_acl_decrement,
                        accessText(info, increment = false),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOperation(info, MfcValueOperation.DECREMENT) },
                    enabled = info.canDecrement,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = null)
                    Text(stringResource(R.string.value_decrement))
                }
                Button(
                    onClick = { onOperation(info, MfcValueOperation.INCREMENT) },
                    enabled = info.canIncrement,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.value_increment))
                }
            }
        }
    }
}

@Composable
private fun accessText(info: MfcValueBlockInfo, increment: Boolean): String {
    val requirement = if (increment) info.increment else info.decrement
    val key = if (increment) info.incrementKey else info.decrementKey
    return when {
        key == MfcKeyType.A -> stringResource(R.string.value_acl_key_a)
        key == MfcKeyType.B -> stringResource(R.string.value_acl_key_b)
        requirement == MfcKeyRequirement.NONE -> stringResource(R.string.value_acl_denied)
        requirement == MfcKeyRequirement.B -> stringResource(R.string.value_acl_missing_b)
        requirement == MfcKeyRequirement.A -> stringResource(R.string.value_acl_missing_a)
        else -> stringResource(R.string.value_acl_denied)
    }
}

@Composable
private fun ValueAmountDialog(
    info: MfcValueBlockInfo,
    operation: MfcValueOperation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var amount by remember(info, operation) { mutableStateOf("") }
    val parsed = amount.toIntOrNull()
    val expected = when {
        parsed == null || parsed <= 0 -> null
        operation == MfcValueOperation.INCREMENT -> info.value.toLong() + parsed
        else -> info.value.toLong() - parsed
    }
    val valid = expected != null &&
        expected >= Int.MIN_VALUE &&
        expected <= Int.MAX_VALUE &&
        (operation != MfcValueOperation.DECREMENT || expected >= 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (operation == MfcValueOperation.INCREMENT) {
                        R.string.value_dialog_add_title
                    } else {
                        R.string.value_dialog_remove_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.value_block_line, info.sector, info.block),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.value_dialog_current, info.value))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() }.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.value_dialog_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (expected != null && valid) {
                    Text(stringResource(R.string.value_dialog_after, expected))
                } else if (parsed != null && parsed > 0) {
                    Text(
                        text = stringResource(R.string.value_error_range),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.value_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.nfc_cancel))
            }
        },
    )
}

@Composable
private fun WriteWaitingCard(state: MfcValueState, viewModel: MfcValueViewModel) {
    val info = state.pendingBlock
    val operation = state.pendingOperation
    if (info == null || operation == null) return
    val expected = when (operation) {
        MfcValueOperation.INCREMENT -> info.value.toLong() + state.pendingAmount
        MfcValueOperation.DECREMENT -> info.value.toLong() - state.pendingAmount
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.value_write_waiting_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.value_write_waiting_body,
                    info.block,
                    info.value,
                    expected,
                    stringResource(
                        if (operation == MfcValueOperation.INCREMENT) {
                            R.string.value_increment
                        } else {
                            R.string.value_decrement
                        },
                    ),
                    state.pendingAmount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = viewModel::cancelWaiting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.nfc_cancel))
            }
        }
    }
}

@Composable
private fun WriteWorkingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.value_write_working),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.value_write_working_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ValueNfcCard(available: Boolean, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(
                        if (available) R.string.nfc_status_off else R.string.nfc_status_unavailable,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (available) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nfc_off_settings))
                }
            }
        }
    }
}

@Composable
private fun ValueError(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
