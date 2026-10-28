// Device screen: connection control, USB descriptor, diagnostics and the
// standard device interrogation commands. This is the home tab.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.copyText
import com.farmacia.pm3android.ui.components.shareText
import com.farmacia.pm3android.ui.console.Pm3DeviceInfo
import com.farmacia.pm3android.ui.console.Pm3ViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(viewModel: Pm3ViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.device_tab_device)) },
                icon = { Icon(Icons.Filled.Usb, contentDescription = null) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.device_tab_tools)) },
                icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            )
        }
        when (tab) {
            0 -> DeviceOverview(viewModel)
            else -> Pm3ToolsScreen(viewModel)
        }
    }
}

@Composable
private fun DeviceOverview(viewModel: Pm3ViewModel) {
    val device by viewModel.device.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()
    val context = LocalContext.current
    val connectionLogTitle = stringResource(R.string.device_connection_log)
    val diagnosticsTitle = stringResource(R.string.device_diagnostics)

    LaunchedEffect(Unit) {
        viewModel.refreshDevice()
    }

    val statusText = when {
        device == null -> stringResource(R.string.device_none)
        connectionState == Pm3ConnectionState.CONNECTED ->
            stringResource(R.string.device_connected, device?.product ?: device?.name.orEmpty())
        device?.hasPermission == true ->
            stringResource(R.string.device_ready, device?.name.orEmpty())
        else ->
            stringResource(R.string.device_permission_missing, device?.name.orEmpty())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleLarge)
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                OutlinedButton(onClick = viewModel::refreshDevice) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::connect,
                    enabled = !busy && device != null &&
                        connectionState != Pm3ConnectionState.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_connect))
                }
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    enabled = !busy && connectionState == Pm3ConnectionState.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_disconnect))
                }
            }
        }

        device?.let { info ->
            DeviceDetails(info)
        }

        SectionCard(title = stringResource(R.string.device_quick_commands)) {
            val commands = listOf(
                "hw version",
                "hw status",
                "hw ping",
                "hw tune",
                "mem info",
                "mem spiffs info",
                "hf search",
                "lf search",
            )
            commands.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { command ->
                        QuickCommand(
                            viewModel = viewModel,
                            command = command,
                            enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (connectLog.isNotBlank()) {
            SectionCard(title = stringResource(R.string.device_connection_log)) {
                SelectionContainer {
                    Text(
                        text = connectLog.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyText(context, connectLog) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_copy))
                    }
                    TextButton(
                        onClick = {
                            shareText(
                                context,
                                connectLog,
                                connectionLogTitle,
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.device_diagnostics)) {
            val diagnostics = viewModel.diagnostics()
            SelectionContainer {
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copyText(context, diagnostics) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.device_copy_diagnostics))
                }
                TextButton(
                    onClick = {
                        shareText(
                            context,
                            diagnostics,
                            diagnosticsTitle,
                        )
                    },
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.device_share_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun DeviceDetails(info: Pm3DeviceInfo) {
    SectionCard(title = stringResource(R.string.device_details)) {
        InfoRow(
            stringResource(R.string.device_manufacturer),
            info.manufacturer ?: stringResource(R.string.device_unknown_value),
        )
        InfoRow(
            stringResource(R.string.device_product),
            info.product ?: stringResource(R.string.device_unknown_value),
        )
        InfoRow(
            stringResource(R.string.device_serial),
            info.serial ?: stringResource(R.string.device_unknown_value),
        )
        InfoRow(
            stringResource(R.string.device_usb_ids),
            "0x%04X:0x%04X".format(info.vendorId, info.productId),
        )
        InfoRow(
            stringResource(R.string.device_interfaces),
            stringResource(
                R.string.device_interfaces_value,
                info.interfaceCount,
                info.endpointCount,
            ),
        )
        SelectionContainer {
            Text(
                text = info.descriptor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun QuickCommand(
    viewModel: Pm3ViewModel,
    command: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { viewModel.runCommand(command) },
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(command, style = MaterialTheme.typography.bodySmall)
    }
}
