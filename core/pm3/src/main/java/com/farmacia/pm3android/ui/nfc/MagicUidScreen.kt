// Full-screen overlay for changing the UID of a magic MIFARE card.
//
// One presentation only: the card is read, the manufacturer block/pages are
// rebuilt with the new UID, written, and read back. A confirmation dialog
// precedes the write because block 0 changes are irreversible.
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.MagicUidMethod
import com.farmacia.pm3android.nfc.MagicUidStage
import com.farmacia.pm3android.nfc.MagicUidState
import com.farmacia.pm3android.nfc.MagicUidViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.toHex
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicUidScreen(
    state: MagicUidState,
    viewModel: MagicUidViewModel,
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
                    title = { Text(stringResource(R.string.uid_title)) },
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
                    MagicUidStage.SETUP -> UidForm(
                        state = state,
                        nfcEnabled = nfcEnabled,
                        nfcAvailable = session != null,
                        viewModel = viewModel,
                        onOpenSettings = {
                            activity?.let { NfcSession.openSystemSettings(it) }
                        },
                    )

                    MagicUidStage.WAITING -> UidWaitingCard(state = state, viewModel = viewModel)
                    MagicUidStage.WORKING -> UidWorkingCard()
                    MagicUidStage.DONE -> UidDoneCard(
                        state = state,
                        viewModel = viewModel,
                        onClose = onClose,
                    )
                }
            }
        }
    }

    if (state.confirming) {
        AlertDialog(
            onDismissRequest = viewModel::cancelConfirm,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.uid_confirm_title)) },
            text = { Text(stringResource(R.string.uid_confirm_body, state.uid)) },
            confirmButton = {
                TextButton(onClick = { viewModel.startWaiting() }) {
                    Text(stringResource(R.string.uid_confirm_write))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelConfirm) {
                    Text(stringResource(R.string.nfc_cancel))
                }
            },
        )
    }
}

@Composable
private fun UidForm(
    state: MagicUidState,
    nfcEnabled: Boolean,
    nfcAvailable: Boolean,
    viewModel: MagicUidViewModel,
    onOpenSettings: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.uid_title)) {
        Text(
            text = stringResource(R.string.uid_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.uid_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = state.uid,
            onValueChange = viewModel::setUid,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.uid_uid_label)) },
            placeholder = { Text(stringResource(R.string.uid_uid_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = { Text(stringResource(R.string.uid_uid_help)) },
        )
        OutlinedTextField(
            value = state.key,
            onValueChange = viewModel::setKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.uid_key_label)) },
            placeholder = { Text(stringResource(R.string.uid_key_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = { Text(stringResource(R.string.uid_key_help)) },
        )
        TextButton(onClick = viewModel::useDefaultKey) {
            Text(stringResource(R.string.uid_use_default_key))
        }
        if (!nfcAvailable || !nfcEnabled) {
            MagicNfcUnavailableCard(
                available = nfcAvailable,
                onOpenSettings = onOpenSettings,
            )
        }
        state.error?.let { MagicErrorText(it) }
        Button(
            onClick = { viewModel.requestWrite() },
            enabled = nfcEnabled && nfcAvailable && state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.uid_start))
        }
    }
}

@Composable
private fun UidWaitingCard(state: MagicUidState, viewModel: MagicUidViewModel) {
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
                text = stringResource(R.string.uid_waiting_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.uid_waiting_body, state.uid),
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
private fun UidWorkingCard() {
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
                text = stringResource(R.string.uid_working),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.uid_working_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UidDoneCard(
    state: MagicUidState,
    viewModel: MagicUidViewModel,
    onClose: () -> Unit,
) {
    val result = state.result ?: return
    SectionCard(
        title = stringResource(
            if (result.verified) R.string.uid_done_title else R.string.uid_done_unverified_title,
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
                text = stringResource(R.string.uid_done_body, result.newUid.toHex()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        InfoRow(stringResource(R.string.uid_row_old), result.oldUid.toHex())
        InfoRow(stringResource(R.string.uid_row_new), result.newUid.toHex())
        InfoRow(stringResource(R.string.uid_row_card), result.family.label)
        InfoRow(stringResource(R.string.uid_row_method), methodLabel(result.method))
        InfoRow(
            stringResource(R.string.uid_row_verified),
            stringResource(
                if (result.verified) R.string.uid_verified_yes else R.string.uid_verified_no,
            ),
        )
        if (!result.verified) {
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
                    text = stringResource(R.string.uid_done_unverified),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            text = stringResource(R.string.uid_done_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::again,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.uid_done_again))
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
private fun methodLabel(method: MagicUidMethod): String = stringResource(
    when (method) {
        MagicUidMethod.CLASSIC_DIRECT -> R.string.uid_method_direct
        MagicUidMethod.CLASSIC_APDU -> R.string.uid_method_apdu
        MagicUidMethod.CLASSIC_APDU_UID -> R.string.uid_method_apdu_uid
        MagicUidMethod.ULTRALIGHT_DIRECT -> R.string.uid_method_ultralight
    },
)

@Composable
private fun MagicNfcUnavailableCard(available: Boolean, onOpenSettings: () -> Unit) {
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
private fun MagicErrorText(text: String) {
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
