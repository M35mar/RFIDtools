// Full-screen Host Card Emulation (HCE) & Reader Polling Sniffer UI:
// 1. Type 4 NDEF Tag Emulator (URI, Text, vCard, Wi-Fi)
// 2. Custom APDU Responder (Prefix -> Response rules + live APDU exchange log)
// 3. Android 15+ Passive Reader Sniffer (Observe Mode + PollingFrame inspector)
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.app.Application
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.nfc.NdefBuilder
import com.farmacia.pm3android.nfc.NdefWriteKind
import com.farmacia.pm3android.nfc.WifiAuth
import com.farmacia.pm3android.nfc.hce.ApduRule
import com.farmacia.pm3android.nfc.hce.HceConfig
import com.farmacia.pm3android.nfc.hce.HceMode
import com.farmacia.pm3android.nfc.hce.HceRegistry
import com.farmacia.pm3android.nfc.hce.Pm3HostApduService
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HceUiState(
    val visible: Boolean = false,
    val mode: HceMode = HceMode.TYPE4_NDEF,
    val active: Boolean = false,
    val ndefKind: NdefWriteKind = NdefWriteKind.URI,
    val uri: String = "https://github.com/M35mar/RFIDtools",
    val text: String = "",
    val vcardName: String = "",
    val vcardPhone: String = "",
    val ssid: String = "",
    val wifiPassword: String = "",
    val rules: List<ApduRule> = HceConfig.defaultRules(),
    val newRuleLabel: String = "",
    val newRulePrefix: String = "",
    val newRuleResponse: String = "9000",
    val observeModeEnabled: Boolean = false,
)

class HceViewModel(application: Application) : AndroidViewModel(application) {

    private val stateFlow = MutableStateFlow(HceUiState())
    val state: StateFlow<HceUiState> = stateFlow

    fun start() {
        stateFlow.value = stateFlow.value.copy(visible = true)
        syncRegistry()
    }

    fun startWithClonedNdef(content: String, isUri: Boolean) {
        stateFlow.value = if (isUri) {
            stateFlow.value.copy(
                visible = true,
                mode = HceMode.TYPE4_NDEF,
                ndefKind = NdefWriteKind.URI,
                uri = content,
                active = true,
            )
        } else {
            stateFlow.value.copy(
                visible = true,
                mode = HceMode.TYPE4_NDEF,
                ndefKind = NdefWriteKind.TEXT,
                text = content,
                active = true,
            )
        }
        syncRegistry()
    }

    fun close() {
        stateFlow.value = stateFlow.value.copy(visible = false, active = false, observeModeEnabled = false)
        syncRegistry()
    }

    fun setMode(mode: HceMode) {
        stateFlow.value = stateFlow.value.copy(mode = mode)
        syncRegistry()
    }

    fun toggleActive() {
        val next = !stateFlow.value.active
        stateFlow.value = stateFlow.value.copy(active = next)
        syncRegistry()
    }

    fun setNdefKind(kind: NdefWriteKind) {
        stateFlow.value = stateFlow.value.copy(ndefKind = kind)
        syncRegistry()
    }

    fun setUri(v: String) {
        stateFlow.value = stateFlow.value.copy(uri = v)
        syncRegistry()
    }

    fun setText(v: String) {
        stateFlow.value = stateFlow.value.copy(text = v)
        syncRegistry()
    }

    fun setVcardName(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardName = v)
        syncRegistry()
    }

    fun setVcardPhone(v: String) {
        stateFlow.value = stateFlow.value.copy(vcardPhone = v)
        syncRegistry()
    }

    fun setSsid(v: String) {
        stateFlow.value = stateFlow.value.copy(ssid = v)
        syncRegistry()
    }

    fun setWifiPassword(v: String) {
        stateFlow.value = stateFlow.value.copy(wifiPassword = v)
        syncRegistry()
    }

    fun setNewRuleLabel(v: String) {
        stateFlow.value = stateFlow.value.copy(newRuleLabel = v.take(40))
    }

    fun setNewRulePrefix(v: String) {
        stateFlow.value = stateFlow.value.copy(
            newRulePrefix = v.filter { it.digitToIntOrNull(16) != null }.uppercase(),
        )
    }

    fun setNewRuleResponse(v: String) {
        stateFlow.value = stateFlow.value.copy(
            newRuleResponse = v.filter { it.digitToIntOrNull(16) != null }.uppercase(),
        )
    }

    fun addRule() {
        val s = stateFlow.value
        if (s.newRulePrefix.length < 2 || s.newRulePrefix.length % 2 != 0) return
        if (s.newRuleResponse.length < 4 || s.newRuleResponse.length % 2 != 0) return
        val rule = ApduRule(
            label = s.newRuleLabel.ifBlank { "Custom ${s.rules.size + 1}" },
            commandPrefixHex = s.newRulePrefix,
            responseHex = s.newRuleResponse,
        )
        stateFlow.value = s.copy(
            rules = s.rules + rule,
            newRuleLabel = "",
            newRulePrefix = "",
        )
        syncRegistry()
    }

    fun removeRule(index: Int) {
        val next = stateFlow.value.rules.filterIndexed { i, _ -> i != index }
        stateFlow.value = stateFlow.value.copy(rules = next)
        syncRegistry()
    }

    fun setObserveModeFlag(enabled: Boolean) {
        stateFlow.value = stateFlow.value.copy(observeModeEnabled = enabled)
    }

    fun clearLogs() = HceRegistry.clearLogs()

    private fun buildEncodedNdef(s: HceUiState): ByteArray = when (s.ndefKind) {
        NdefWriteKind.URI -> {
            val uriTrimmed = s.uri.trim().ifEmpty { "https://" }
            NdefBuilder.encodeSingleRecordMessage(
                tnf = 0x01,
                type = byteArrayOf('U'.code.toByte()),
                payload = NdefBuilder.buildUriPayload(uriTrimmed),
            )
        }
        NdefWriteKind.TEXT -> NdefBuilder.encodeSingleRecordMessage(
            tnf = 0x01,
            type = byteArrayOf('T'.code.toByte()),
            payload = NdefBuilder.buildTextPayload(s.text),
        )
        NdefWriteKind.VCARD -> NdefBuilder.encodeSingleRecordMessage(
            tnf = 0x02,
            type = "text/vcard".toByteArray(Charsets.US_ASCII),
            payload = NdefBuilder.buildVCardPayload(
                name = s.vcardName.ifBlank { "Contact" },
                phone = s.vcardPhone,
            ),
        )
        NdefWriteKind.WIFI -> NdefBuilder.encodeSingleRecordMessage(
            tnf = 0x02,
            type = "application/vnd.wfa.wsc".toByteArray(Charsets.US_ASCII),
            payload = NdefBuilder.buildWifiWscPayload(
                ssid = s.ssid.ifBlank { "WiFi" },
                password = s.wifiPassword,
                auth = WifiAuth.WPA2_PERSONAL,
            ),
        )
        else -> ByteArray(0)
    }

    private fun syncRegistry() {
        val s = stateFlow.value
        HceRegistry.updateConfig(
            HceConfig(
                enabled = s.visible && s.active,
                mode = s.mode,
                ndefMessageBytes = buildEncodedNdef(s),
                rules = s.rules,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HceEmulatorScreen(
    state: HceUiState,
    viewModel: HceViewModel,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val adapter = remember(activity) { activity?.let { NfcAdapter.getDefaultAdapter(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val accents = MaterialTheme.pm3Accents
    val chrome = MaterialTheme.pm3Chrome

    val apduLog by HceRegistry.apduLog.collectAsState()
    val pollingLog by HceRegistry.pollingLog.collectAsState()
    val sessionCount by HceRegistry.sessionCount.collectAsState()

    val observeSupported = remember(adapter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && adapter != null) {
            runCatching { adapter.isObserveModeSupported }.getOrDefault(false)
        } else {
            false
        }
    }

    BackHandler { onClose() }

    // Register Pm3HostApduService as foreground preferred HCE service while on screen.
    DisposableEffect(lifecycleOwner, activity, adapter, state.observeModeEnabled) {
        val component = activity?.let { ComponentName(it, Pm3HostApduService::class.java) }
        val cardEmulation = adapter?.let { runCatching { CardEmulation.getInstance(it) }.getOrNull() }

        fun applyForeground() {
            if (activity != null && component != null) {
                runCatching { cardEmulation?.setPreferredService(activity, component) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                adapter != null && observeSupported
            ) {
                runCatching { adapter.setObserveModeEnabled(state.observeModeEnabled) }
            }
        }

        fun releaseForeground() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                adapter != null && observeSupported
            ) {
                runCatching { adapter.setObserveModeEnabled(false) }
            }
            if (activity != null) {
                runCatching { cardEmulation?.unsetPreferredService(activity) }
            }
        }

        applyForeground()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> applyForeground()
                Lifecycle.Event.ON_PAUSE -> releaseForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseForeground()
        }
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
                        title = { Text(stringResource(R.string.hce_title)) },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.nfc_back),
                                )
                            }
                        },
                        actions = {
                            StatusPill(
                                text = if (state.active) {
                                    stringResource(R.string.hce_status_on, sessionCount)
                                } else {
                                    stringResource(R.string.hce_status_off)
                                },
                                color = if (state.active) accents.success else MaterialTheme.colorScheme.outline,
                            )
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
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.mode == HceMode.TYPE4_NDEF,
                        onClick = { viewModel.setMode(HceMode.TYPE4_NDEF) },
                        leadingIcon = { Icon(Icons.Filled.Nfc, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.hce_tab_ndef)) },
                    )
                    FilterChip(
                        selected = state.mode == HceMode.CUSTOM_APDU,
                        onClick = { viewModel.setMode(HceMode.CUSTOM_APDU) },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.hce_tab_apdu)) },
                    )
                    FilterChip(
                        selected = state.mode == HceMode.POLLING_SNIFFER,
                        onClick = { viewModel.setMode(HceMode.POLLING_SNIFFER) },
                        leadingIcon = { Icon(Icons.Filled.Radar, null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.hce_tab_sniffer)) },
                    )
                }

                when (state.mode) {
                    HceMode.TYPE4_NDEF -> SectionCard(
                        title = stringResource(R.string.hce_ndef_card_title),
                        icon = Icons.Filled.Nfc,
                        accent = accents.hf,
                    ) {
                        Text(
                            text = stringResource(R.string.hce_ndef_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.ndefKind == NdefWriteKind.URI,
                                onClick = { viewModel.setNdefKind(NdefWriteKind.URI) },
                                label = { Text("URI") },
                            )
                            FilterChip(
                                selected = state.ndefKind == NdefWriteKind.TEXT,
                                onClick = { viewModel.setNdefKind(NdefWriteKind.TEXT) },
                                label = { Text(stringResource(R.string.ndef_kind_text)) },
                            )
                            FilterChip(
                                selected = state.ndefKind == NdefWriteKind.VCARD,
                                onClick = { viewModel.setNdefKind(NdefWriteKind.VCARD) },
                                label = { Text("vCard") },
                            )
                            FilterChip(
                                selected = state.ndefKind == NdefWriteKind.WIFI,
                                onClick = { viewModel.setNdefKind(NdefWriteKind.WIFI) },
                                label = { Text("Wi-Fi") },
                            )
                        }

                        when (state.ndefKind) {
                            NdefWriteKind.URI -> OutlinedTextField(
                                value = state.uri,
                                onValueChange = viewModel::setUri,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ndef_uri_label)) },
                                singleLine = true,
                            )
                            NdefWriteKind.TEXT -> OutlinedTextField(
                                value = state.text,
                                onValueChange = viewModel::setText,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ndef_text_label)) },
                            )
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
                            }
                            else -> {
                                OutlinedTextField(
                                    value = state.ssid,
                                    onValueChange = viewModel::setSsid,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.ndef_wifi_ssid)) },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = state.wifiPassword,
                                    onValueChange = viewModel::setWifiPassword,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.ndef_wifi_password)) },
                                    singleLine = true,
                                )
                            }
                        }

                        Button(
                            onClick = viewModel::toggleActive,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.active) accents.danger else accents.hf,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = if (state.active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (state.active) R.string.hce_stop else R.string.hce_start,
                                ),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    HceMode.CUSTOM_APDU -> SectionCard(
                        title = stringResource(R.string.hce_tab_apdu),
                        icon = Icons.Filled.SwapHoriz,
                        accent = accents.bluetooth,
                    ) {
                        state.rules.forEachIndexed { idx, rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.label, style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        text = "${rule.commandPrefixHex} → ${rule.responseHex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                IconButton(onClick = { viewModel.removeRule(idx) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = accents.danger)
                                }
                            }
                        }
                        HorizontalDivider()
                        OutlinedTextField(
                            value = state.newRuleLabel,
                            onValueChange = viewModel::setNewRuleLabel,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.hce_rule_name)) },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.newRulePrefix,
                                onValueChange = viewModel::setNewRulePrefix,
                                modifier = Modifier.weight(1f),
                                label = { Text("CMD Hex") },
                                placeholder = { Text("00A40400") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.newRuleResponse,
                                onValueChange = viewModel::setNewRuleResponse,
                                modifier = Modifier.weight(1f),
                                label = { Text("RESP Hex") },
                                placeholder = { Text("9000") },
                                singleLine = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::addRule, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                Text(stringResource(R.string.hce_rule_add))
                            }
                            Button(
                                onClick = viewModel::toggleActive,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.active) accents.danger else accents.bluetooth,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(if (state.active) R.string.hce_stop else R.string.hce_start))
                            }
                        }
                    }

                    HceMode.POLLING_SNIFFER -> SectionCard(
                        title = stringResource(R.string.hce_tab_sniffer),
                        icon = Icons.Filled.Radar,
                        accent = accents.lf,
                    ) {
                        InfoRow(
                            "Android API",
                            "${Build.VERSION.SDK_INT} (${if (Build.VERSION.SDK_INT >= 35) "PollingFrame OK" else "< 35"})",
                        )
                        InfoRow(
                            "Observe Mode HW",
                            stringResource(if (observeSupported) R.string.inspect_yes else R.string.inspect_no),
                        )
                        Text(
                            text = stringResource(R.string.hce_sniffer_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (observeSupported) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.hce_observe_toggle))
                                Switch(
                                    checked = state.observeModeEnabled,
                                    onCheckedChange = viewModel::setObserveModeFlag,
                                )
                            }
                        }
                    }
                }

                if (state.mode == HceMode.POLLING_SNIFFER) {
                    SectionCard(
                        title = "${stringResource(R.string.hce_polling_log_title)} (${pollingLog.size})",
                        accent = accents.info,
                    ) {
                        if (pollingLog.isEmpty()) {
                            Text(
                                text = stringResource(R.string.hce_polling_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedButton(onClick = viewModel::clearLogs) {
                                Text(stringResource(R.string.action_clear))
                            }
                            pollingLog.take(60).forEach { ev ->
                                Text(
                                    text = "[${ev.annotation}]  ${ev.dataHex}  (gain=${ev.gain}, Δ=${ev.deltaUs / 1000}ms)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                } else {
                    SectionCard(
                        title = "${stringResource(R.string.hce_apdu_log_title)} (${apduLog.size})",
                        accent = accents.info,
                    ) {
                        if (apduLog.isEmpty()) {
                            Text(
                                text = stringResource(R.string.hce_apdu_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedButton(onClick = viewModel::clearLogs) {
                                Text(stringResource(R.string.action_clear))
                            }
                            apduLog.take(50).forEach { ev ->
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        text = ">> ${ev.commandHex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = accents.usb,
                                    )
                                    Text(
                                        text = "<< ${ev.responseHex}  (${ev.note})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = accents.success,
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
