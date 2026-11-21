// APDU console overlay. Reader mode stays active while the screen is open; the
// first ISO-DEP tag opens the connection and every command is logged with the
// status word description, color-coded by SW outcome, with 1-tap presets.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.ApduConsoleState
import com.farmacia.pm3android.nfc.ApduConsoleViewModel
import com.farmacia.pm3android.nfc.ApduError
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.nfc.inspect.Iso7816
import com.farmacia.pm3android.nfc.toHexSpaced
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.copyText
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome

private data class ApduPreset(val label: String, val hex: String)

private val APDU_PRESETS = listOf(
    ApduPreset("Select PPSE", "00 A4 04 00 0E 32 50 41 59 2E 53 59 53 2E 44 44 46 30 31 00"),
    ApduPreset("Select NDEF", "00 A4 04 00 07 D2 76 00 00 85 01 01 00"),
    ApduPreset("DESFire GetVer", "90 60 00 00 00"),
    ApduPreset("DESFire Next", "90 AF 00 00 00"),
    ApduPreset("eMRTD Select", "00 A4 04 0C 07 A0 00 00 02 47 10 01"),
    ApduPreset("Get Challenge", "00 84 00 00 08"),
    ApduPreset("Calypso Select", "00 A4 04 00 08 31 54 49 43 2E 49 43 41 00"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApduConsoleScreen(
    state: ApduConsoleState,
    viewModel: ApduConsoleViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    var input by rememberSaveable { mutableStateOf("") }
    val accents = MaterialTheme.pm3Accents
    val chrome = MaterialTheme.pm3Chrome

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
                            actionIconContentColor = Color(0xFFF8FAFC),
                        ),
                        title = { Text(stringResource(R.string.apdu_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose, enabled = !state.busy) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nfc_back),
                                )
                            }
                        },
                        actions = {
                            if (state.exchanges.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        val dump = state.exchanges.joinToString("\n\n") { ex ->
                                            buildString {
                                                append("-> ").append(ex.command.toHexSpaced())
                                                if (ex.error != null) {
                                                    append("\n!! ").append(ex.error)
                                                } else {
                                                    append("\n<- ").append(
                                                        (ex.response ?: ByteArray(0)).toHexSpaced(),
                                                    )
                                                    ex.sw?.let { sw ->
                                                        append("\nSW %04X (%s)".format(sw, Iso7816.swDescription(sw)))
                                                    }
                                                }
                                            }
                                        }
                                        copyText(context, dump)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = stringResource(R.string.action_copy),
                                    )
                                }
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
                SectionCard(
                    title = stringResource(R.string.apdu_status_title),
                    accent = if (state.hasTag) accents.success else accents.bluetooth,
                ) {
                    Text(
                        text = when {
                            !nfcEnabled -> stringResource(R.string.apdu_nfc_off)
                            state.hasTag -> stringResource(R.string.apdu_tag_ready)
                            else -> stringResource(R.string.apdu_present_tag)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.hasTag) accents.success else MaterialTheme.colorScheme.onSurface,
                    )
                    state.error?.let { error ->
                        Text(
                            text = apduErrorText(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = accents.danger,
                        )
                    }
                }

                SectionCard(
                    title = stringResource(R.string.apdu_presets_title),
                    accent = accents.info,
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        APDU_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    input = preset.hex
                                    if (state.hasTag && !state.busy) {
                                        viewModel.send(preset.hex)
                                    }
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.apdu_command_title)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.apdu_command_label)) },
                        placeholder = { Text("00 A4 04 00 07 A0 00 00 02 47 10 01 00") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        singleLine = false,
                        minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.send(input) },
                            enabled = !state.busy && input.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(stringResource(R.string.apdu_send))
                        }
                        OutlinedButton(
                            onClick = viewModel::clearLog,
                            enabled = state.exchanges.isNotEmpty() && !state.busy,
                        ) {
                            Text(stringResource(R.string.apdu_clear))
                        }
                    }
                }

                if (state.exchanges.isNotEmpty()) {
                    SectionCard(title = stringResource(R.string.apdu_log_title)) {
                        state.exchanges.reversed().forEach { exchange ->
                            Text(
                                text = "→ " + exchange.command.toHexSpaced(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = accents.usb,
                            )
                            if (exchange.error != null) {
                                Text(
                                    text = exchange.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accents.danger,
                                )
                            } else {
                                Text(
                                    text = "← " + (exchange.response ?: ByteArray(0)).toHexSpaced(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                exchange.sw?.let { sw ->
                                    val swColor = when {
                                        sw == 0x9000 || sw == 0x9100 -> accents.success
                                        (sw and 0xFF00) == 0x6100 || sw == 0x91AF -> accents.info
                                        else -> accents.danger
                                    }
                                    Text(
                                        text = "SW %04X · %s".format(sw, Iso7816.swDescription(sw)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = swColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun apduErrorText(error: ApduError): String = stringResource(
    when (error) {
        ApduError.NO_TAG -> R.string.apdu_error_no_tag
        ApduError.TAG_LOST -> R.string.apdu_error_tag_lost
        ApduError.PARSE -> R.string.apdu_error_parse
        ApduError.TRANSCEIVE -> R.string.apdu_error_transceive
    },
)
