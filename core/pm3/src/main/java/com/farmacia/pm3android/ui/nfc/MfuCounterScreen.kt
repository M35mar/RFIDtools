// Full-screen overlay for setting a MIFARE Ultralight EV1 counter.
// The card is presented once: the tool reads the version and the current
// counter, increments by the delta to the chosen target and verifies the result.
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.farmacia.pm3android.nfc.MfuCounterStage
import com.farmacia.pm3android.nfc.MfuCounterState
import com.farmacia.pm3android.nfc.MfuCounterViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfuCounterScreen(
    state: MfuCounterState,
    viewModel: MfuCounterViewModel,
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.counter_title)) },
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
                    MfuCounterStage.SETUP -> CounterForm(
                        state = state,
                        nfcEnabled = nfcEnabled,
                        nfcAvailable = session != null,
                        viewModel = viewModel,
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                    )

                    MfuCounterStage.WAITING -> WaitingCard(state = state, viewModel = viewModel)
                    MfuCounterStage.WORKING -> WorkingCard()
                    MfuCounterStage.DONE -> DoneCard(
                        state = state,
                        viewModel = viewModel,
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun CounterForm(
    state: MfuCounterState,
    nfcEnabled: Boolean,
    nfcAvailable: Boolean,
    viewModel: MfuCounterViewModel,
    onOpenSettings: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.counter_title)) {
        Text(
            text = stringResource(R.string.counter_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.counter_only_up),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.counter_pick),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (index in 0..2) {
                val label = stringResource(R.string.counter_number, index + 1)
                if (index == state.counterIndex) {
                    Button(
                        onClick = { viewModel.setCounterIndex(index) },
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.setCounterIndex(index) },
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
        }
        OutlinedTextField(
            value = state.target,
            onValueChange = viewModel::setTarget,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.counter_target)) },
            placeholder = { Text(stringResource(R.string.counter_target_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(stringResource(R.string.counter_target_help)) },
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.counter_password)) },
            placeholder = { Text(stringResource(R.string.counter_password_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = { Text(stringResource(R.string.counter_password_help)) },
        )
        if (!nfcAvailable || !nfcEnabled) {
            NfcUnavailableCard(
                available = nfcAvailable,
                onOpenSettings = onOpenSettings,
            )
        }
        state.error?.let { ErrorText(it) }
        Button(
            onClick = { viewModel.startWaiting() },
            enabled = nfcEnabled && nfcAvailable && state.target.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.counter_start))
        }
    }
}

@Composable
private fun WaitingCard(state: MfuCounterState, viewModel: MfuCounterViewModel) {
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
                text = stringResource(R.string.counter_waiting_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.counter_waiting_body,
                    state.counterIndex + 1,
                    state.target,
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
private fun WorkingCard() {
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
                text = stringResource(R.string.counter_working),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.counter_working_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DoneCard(
    state: MfuCounterState,
    viewModel: MfuCounterViewModel,
    onClose: () -> Unit,
) {
    val result = state.result
    if (result == null) return
    SectionCard(
        title = stringResource(
            if (result.written) R.string.counter_done_title else R.string.counter_done_same_title,
        ),
    ) {
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
                text = if (result.written) {
                    stringResource(R.string.counter_done_body, result.index + 1, result.value)
                } else {
                    stringResource(R.string.counter_done_unchanged, result.index + 1, result.value)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        InfoRow(stringResource(R.string.counter_row_before), result.previous.toString())
        InfoRow(stringResource(R.string.counter_row_after), result.value.toString())
        InfoRow(stringResource(R.string.counter_row_tag), result.tagLabel)
        if (state.uidHex.isNotEmpty()) {
            InfoRow(stringResource(R.string.inspect_uid), state.uidHex)
        }
        if (result.tearingUnknown) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.counter_done_tearing_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            text = stringResource(R.string.counter_done_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::again,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.counter_done_again))
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.nfc_close))
        }
    }
}

@Composable
private fun NfcUnavailableCard(available: Boolean, onOpenSettings: () -> Unit) {
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
private fun ErrorText(text: String) {
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
