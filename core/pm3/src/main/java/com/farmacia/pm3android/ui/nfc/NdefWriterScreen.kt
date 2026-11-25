// Full-screen overlay for NDEF Writer / Formatter Pro (URI + tel/mailto/geo
// presets, Text, Wi-Fi WSC, vCard 3.0, App Launcher AAR, Bluetooth OOB, Erase).
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.NdefWriteError
import com.farmacia.pm3android.nfc.NdefWriteKind
import com.farmacia.pm3android.nfc.NdefWriteStage
import com.farmacia.pm3android.nfc.NdefWriterState
import com.farmacia.pm3android.nfc.NdefWriterViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.WifiAuth
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdefWriterScreen(
    state: NdefWriterState,
    viewModel: NdefWriterViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    val accents = MaterialTheme.pm3Accents
    val chrome = MaterialTheme.pm3Chrome

    BackHandler(enabled = !state.busy) {
        if (state.stage == NdefWriteStage.EDIT) onClose() else viewModel.backToEdit()
    }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabled = session?.isEnabled == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(state.readerActive, session) {
        if (state.readerActive) {
            session?.start { tag -> viewModel.onTag(tag) }
        } else {
            session?.stop()
        }
        onDispose { session?.stop() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = chrome.topBarBg,
                            titleContentColor = Color(0xFFF8FAFC),
                            navigationIconContentColor = Color(0xFFF8FAFC),
                        ),
                        title = { Text(stringResource(R.string.ndef_writer_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose, enabled = !state.busy) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nfc_back),
                                )
                            }
                        },
                    )
                    HorizontalDivider(color = chrome.chromeDivider)
                }
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
                    NdefWriteStage.EDIT -> {
                        SectionCard(
                            title = stringResource(R.string.ndef_writer_type_title),
                            icon = Icons.Filled.EditNote,
                            accent = accents.info,
                        ) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.URI,
                                    onClick = { viewModel.setKind(NdefWriteKind.URI) },
                                    leadingIcon = { Icon(Icons.Filled.Link, null, Modifier.size(16.dp)) },
                                    label = { Text(stringResource(R.string.ndef_kind_uri)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.usb.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.usb,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.TEXT,
                                    onClick = { viewModel.setKind(NdefWriteKind.TEXT) },
                                    leadingIcon = { Icon(Icons.Filled.TextFields, null, Modifier.size(16.dp)) },
                                    label = { Text(stringResource(R.string.ndef_kind_text)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.hf.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.hf,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.WIFI,
                                    onClick = { viewModel.setKind(NdefWriteKind.WIFI) },
                                    leadingIcon = { Icon(Icons.Filled.Wifi, null, Modifier.size(16.dp)) },
                                    label = { Text(stringResource(R.string.ndef_kind_wifi)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.script.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.script,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.VCARD,
                                    onClick = { viewModel.setKind(NdefWriteKind.VCARD) },
                                    leadingIcon = { Icon(Icons.Filled.ContactPage, null, Modifier.size(16.dp)) },
                                    label = { Text("vCard") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.lf.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.lf,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.APP_LAUNCHER,
                                    onClick = { viewModel.setKind(NdefWriteKind.APP_LAUNCHER) },
                                    leadingIcon = { Icon(Icons.Filled.Apps, null, Modifier.size(16.dp)) },
                                    label = { Text("App (AAR)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.info.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.info,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.BLUETOOTH,
                                    onClick = { viewModel.setKind(NdefWriteKind.BLUETOOTH) },
                                    leadingIcon = { Icon(Icons.Filled.Bluetooth, null, Modifier.size(16.dp)) },
                                    label = { Text("Bluetooth") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.bluetooth.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.bluetooth,
                                    ),
                                )
                                FilterChip(
                                    selected = state.kind == NdefWriteKind.ERASE,
                                    onClick = { viewModel.setKind(NdefWriteKind.ERASE) },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp)) },
                                    label = { Text(stringResource(R.string.ndef_kind_erase)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accents.danger.copy(alpha = 0.22f),
                                        selectedLabelColor = accents.danger,
                                    ),
                                )
                            }

                            when (state.kind) {
                                NdefWriteKind.URI -> {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        AssistChip(onClick = { viewModel.setUri("https://") }, label = { Text("https://") })
                                        AssistChip(onClick = { viewModel.setUri("tel:+39") }, label = { Text("tel:") })
                                        AssistChip(onClick = { viewModel.setUri("mailto:") }, label = { Text("mailto:") })
                                        AssistChip(onClick = { viewModel.setUri("geo:41.9028,12.4964") }, label = { Text("geo:") })
                                    }
                                    OutlinedTextField(
                                        value = state.uri,
                                        onValueChange = viewModel::setUri,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_uri_label)) },
                                        placeholder = { Text("https://example.com") },
                                        singleLine = true,
                                    )
                                }
                                NdefWriteKind.TEXT -> OutlinedTextField(
                                    value = state.text,
                                    onValueChange = viewModel::setText,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.ndef_text_label)) },
                                    minLines = 3,
                                )
                                NdefWriteKind.WIFI -> {
                                    OutlinedTextField(
                                        value = state.ssid,
                                        onValueChange = viewModel::setSsid,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_wifi_ssid)) },
                                        singleLine = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        WifiAuth.entries.forEach { auth ->
                                            val label = when (auth) {
                                                WifiAuth.WPA2_PERSONAL -> "WPA2"
                                                WifiAuth.WPA_PERSONAL -> "WPA"
                                                WifiAuth.OPEN -> stringResource(R.string.ndef_wifi_open)
                                            }
                                            FilterChip(
                                                selected = state.wifiAuth == auth,
                                                onClick = { viewModel.setWifiAuth(auth) },
                                                label = { Text(label) },
                                            )
                                        }
                                    }
                                    if (state.wifiAuth != WifiAuth.OPEN) {
                                        OutlinedTextField(
                                            value = state.wifiPassword,
                                            onValueChange = viewModel::setWifiPassword,
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(stringResource(R.string.ndef_wifi_password)) },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                        )
                                    }
                                }
                                NdefWriteKind.VCARD -> {
                                    OutlinedTextField(
                                        value = state.vcardName,
                                        onValueChange = viewModel::setVcardName,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_vcard_name)) },
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = state.vcardPhone,
                                        onValueChange = viewModel::setVcardPhone,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_vcard_phone)) },
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = state.vcardEmail,
                                        onValueChange = viewModel::setVcardEmail,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Email") },
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = state.vcardOrg,
                                        onValueChange = viewModel::setVcardOrg,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_vcard_org)) },
                                        singleLine = true,
                                    )
                                }
                                NdefWriteKind.APP_LAUNCHER -> OutlinedTextField(
                                    value = state.appPackage,
                                    onValueChange = viewModel::setAppPackage,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.ndef_aar_pkg)) },
                                    placeholder = { Text("com.farmacia.pm3android") },
                                    singleLine = true,
                                )
                                NdefWriteKind.BLUETOOTH -> {
                                    OutlinedTextField(
                                        value = state.btMac,
                                        onValueChange = viewModel::setBtMac,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Bluetooth MAC") },
                                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = state.btName,
                                        onValueChange = viewModel::setBtName,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(stringResource(R.string.ndef_bt_name)) },
                                        singleLine = true,
                                    )
                                }
                                NdefWriteKind.ERASE -> Text(
                                    text = stringResource(R.string.ndef_erase_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Button(
                                onClick = viewModel::armWrite,
                                enabled = state.canPrepare && nfcEnabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.kind == NdefWriteKind.ERASE) accents.danger else accents.hf,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.ndef_writer_arm))
                            }
                        }
                    }

                    NdefWriteStage.WAITING -> SectionCard(
                        title = stringResource(R.string.ndef_writer_waiting_title),
                        icon = Icons.Filled.Nfc,
                        accent = accents.usb,
                    ) {
                        Text(
                            text = stringResource(R.string.ndef_writer_waiting_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = viewModel::backToEdit) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }

                    NdefWriteStage.WRITING -> SectionCard(
                        title = stringResource(R.string.ndef_writer_writing),
                        accent = accents.lf,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Text(stringResource(R.string.uid_working_body))
                        }
                    }

                    NdefWriteStage.DONE -> SectionCard(
                        title = stringResource(R.string.ndef_writer_done_title),
                        icon = Icons.Filled.CheckCircle,
                        accent = accents.success,
                    ) {
                        InfoRow("UID", state.uidHex)
                        InfoRow(
                            stringResource(R.string.ndef_writer_bytes_label),
                            if (state.maxBytes > 0) "${state.bytesWritten} / ${state.maxBytes} B"
                            else "${state.bytesWritten} B",
                        )
                        InfoRow(
                            stringResource(R.string.uid_row_verified),
                            stringResource(
                                if (state.verified) R.string.uid_verified_yes else R.string.uid_verified_no,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::armWrite, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_again))
                            }
                            OutlinedButton(onClick = viewModel::backToEdit, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_edit))
                            }
                        }
                    }

                    NdefWriteStage.ERROR -> SectionCard(
                        title = stringResource(R.string.ndef_writer_error_title),
                        icon = Icons.Filled.ErrorOutline,
                        accent = accents.danger,
                    ) {
                        val message = when (state.error) {
                            NdefWriteError.TAG_LOST -> stringResource(R.string.uid_error_lost)
                            NdefWriteError.READ_ONLY -> stringResource(R.string.ndef_error_read_only)
                            NdefWriteError.NOT_SUPPORTED -> stringResource(R.string.ndef_error_not_supported)
                            NdefWriteError.TOO_LARGE -> stringResource(
                                R.string.ndef_error_too_large,
                                state.bytesWritten,
                                state.maxBytes,
                            )
                            NdefWriteError.VERIFY_FAILED -> stringResource(R.string.ndef_error_verify)
                            else -> stringResource(R.string.ndef_error_generic)
                        }
                        Text(message, color = accents.danger)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::armWrite, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_retry))
                            }
                            OutlinedButton(onClick = viewModel::backToEdit, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.ndef_writer_edit))
                            }
                        }
                    }
                }
            }
        }
    }
}
