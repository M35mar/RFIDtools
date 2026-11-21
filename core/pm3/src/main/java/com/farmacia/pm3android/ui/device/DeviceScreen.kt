// Device screen: connection control, USB descriptor, diagnostics and the
// standard device interrogation commands. This is the home tab.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Intent
import android.provider.Settings
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.ConnectionMode
import com.farmacia.pm3android.pm3.BluetoothAdapterState
import com.farmacia.pm3android.pm3.BluetoothDeviceInfo
import com.farmacia.pm3android.pm3.BluetoothLink
import com.farmacia.pm3android.pm3.BluetoothPermissions
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.AccentBadge
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.copyText
import com.farmacia.pm3android.ui.components.shareText
import com.farmacia.pm3android.ui.console.Pm3DeviceInfo
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.theme.pm3Accents

@Composable
fun DeviceScreen(viewModel: Pm3ViewModel) {
    DeviceOverview(viewModel)
}

@Composable
private fun DeviceOverview(viewModel: Pm3ViewModel) {
    val device by viewModel.device.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val bluetoothDevices by viewModel.bluetoothDevices.collectAsState()
    val bluetoothScanning by viewModel.bluetoothScanning.collectAsState()
    val bluetoothState by viewModel.bluetoothState.collectAsState()
    val context = LocalContext.current
    val connectionLogTitle = stringResource(R.string.device_connection_log)
    val diagnosticsTitle = stringResource(R.string.device_diagnostics)
    val mode = settings.connectionMode
    val idle = connectionState == Pm3ConnectionState.DISCONNECTED && !busy
    val selectedBluetooth = remember(
        settings.bluetoothAddress,
        settings.bluetoothName,
        settings.bluetoothLink,
    ) {
        settings.bluetoothAddress?.let { address ->
            BluetoothDeviceInfo(
                name = settings.bluetoothName,
                address = address,
                link = settings.bluetoothLink,
                bonded = true,
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDevice()
        viewModel.refreshBluetooth()
    }

    // Paired devices change in the system settings, which the user may open
    // from this screen: re-read them every time the app comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDevice()
                viewModel.refreshBluetooth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsMessage = stringResource(R.string.device_bt_permissions)
    val disabledMessage = stringResource(R.string.device_bt_disabled)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (BluetoothPermissions.has(context, scan = true)) {
            viewModel.startBluetoothScan()
        } else {
            viewModel.notify(permissionsMessage)
        }
    }
    val startScan = {
        if (BluetoothPermissions.has(context, scan = true)) {
            viewModel.startBluetoothScan()
        } else {
            permissionLauncher.launch(
                BluetoothPermissions.required(scan = true).toTypedArray(),
            )
        }
    }
    val openBluetoothSettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }.onFailure {
            viewModel.notify(disabledMessage)
        }
    }

    val statusText = when {
        connectionState == Pm3ConnectionState.CONNECTED ->
            if (mode == ConnectionMode.BLUETOOTH) {
                stringResource(
                    R.string.device_bt_connected,
                    selectedBluetooth?.displayName.orEmpty(),
                )
            } else {
                stringResource(R.string.device_connected, device?.product ?: device?.name.orEmpty())
            }
        mode == ConnectionMode.BLUETOOTH ->
            selectedBluetooth?.let { stringResource(R.string.device_bt_ready, it.displayName) }
                ?: stringResource(R.string.device_bt_not_selected)
        device == null -> stringResource(R.string.device_none)
        device?.hasPermission == true ->
            stringResource(R.string.device_ready, device?.name.orEmpty())
        else ->
            stringResource(R.string.device_permission_missing, device?.name.orEmpty())
    }

    val canConnect = !busy && connectionState != Pm3ConnectionState.CONNECTED &&
        if (mode == ConnectionMode.USB) {
            device != null
        } else {
            settings.bluetoothAddress != null
        }

    val accents = MaterialTheme.pm3Accents
    val heroAccent = when {
        connectionState == Pm3ConnectionState.CONNECTED -> accents.success
        mode == ConnectionMode.BLUETOOTH -> accents.bluetooth
        else -> accents.usb
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.25.dp, heroAccent.copy(alpha = 0.35f)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                heroAccent.copy(alpha = 0.16f),
                                accents.info.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = heroAccent.copy(alpha = 0.18f),
                    ) {
                        Icon(
                            imageVector = if (mode == ConnectionMode.BLUETOOTH) {
                                Icons.Filled.Bluetooth
                            } else {
                                Icons.Filled.Usb
                            },
                            contentDescription = null,
                            tint = heroAccent,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(26.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = heroAccent,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshDevice()
                            viewModel.refreshBluetooth()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransportButton(
                        label = stringResource(R.string.device_transport_usb),
                        icon = Icons.Filled.Usb,
                        selected = mode == ConnectionMode.USB,
                        accent = accents.usb,
                        enabled = idle,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.USB) },
                        modifier = Modifier.weight(1f),
                    )
                    TransportButton(
                        label = stringResource(R.string.device_transport_bluetooth),
                        icon = Icons.Filled.Bluetooth,
                        selected = mode == ConnectionMode.BLUETOOTH,
                        accent = accents.bluetooth,
                        enabled = idle,
                        onClick = { viewModel.setConnectionMode(ConnectionMode.BLUETOOTH) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::connect,
                        enabled = canConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = heroAccent,
                            contentColor = Color.White,
                        ),
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
        }

        if (mode == ConnectionMode.BLUETOOTH) {
            BluetoothCard(
                devices = bluetoothDevices,
                scanning = bluetoothScanning,
                adapterState = bluetoothState,
                selected = selectedBluetooth,
                enabled = idle,
                onScan = startScan,
                onStopScan = viewModel::stopBluetoothScan,
                onSelect = viewModel::selectBluetoothDevice,
                onOpenSettings = openBluetoothSettings,
            )
        } else {
            device?.let { info ->
                DeviceDetails(info)
            }
        }

        if (connectLog.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.device_connection_log),
                icon = Icons.Filled.Terminal,
                accent = accents.info,
            ) {
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

        SectionCard(
            title = stringResource(R.string.device_diagnostics),
            icon = Icons.Filled.BugReport,
            accent = accents.script,
        ) {
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
private fun BluetoothCard(
    devices: List<BluetoothDeviceInfo>,
    scanning: Boolean,
    adapterState: BluetoothAdapterState,
    selected: BluetoothDeviceInfo?,
    enabled: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (BluetoothDeviceInfo) -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.device_bt_card_title),
        icon = Icons.Filled.Bluetooth,
        accent = MaterialTheme.pm3Accents.bluetooth,
    ) {
        when (adapterState) {
            BluetoothAdapterState.UNSUPPORTED ->
                Text(
                    stringResource(R.string.device_bt_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                )

            BluetoothAdapterState.PERMISSION_REQUIRED -> {
                Text(
                    stringResource(R.string.device_bt_permissions),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onScan, enabled = enabled) {
                    Text(stringResource(R.string.device_bt_scan))
                }
            }

            BluetoothAdapterState.OFF -> {
                Text(
                    stringResource(R.string.device_bt_disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenSettings, enabled = enabled) {
                    Text(stringResource(R.string.device_bt_settings))
                }
            }

            BluetoothAdapterState.ON -> {
                Text(
                    text = selected?.let {
                        stringResource(R.string.device_bt_selected, it.displayName)
                    } ?: stringResource(R.string.device_bt_not_selected),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = if (scanning) onStopScan else onScan,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (scanning) R.string.device_bt_stop_scan else R.string.device_bt_scan,
                            ),
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.device_bt_settings))
                    }
                }
                if (scanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.device_bt_scanning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    stringResource(R.string.device_bt_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.device_bt_slow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (devices.isEmpty()) {
                    Text(
                        stringResource(R.string.device_bt_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    devices.forEach { info ->
                        BluetoothDeviceRow(
                            info = info,
                            selected = selected,
                            enabled = enabled,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothDeviceRow(
    info: BluetoothDeviceInfo,
    selected: BluetoothDeviceInfo?,
    enabled: Boolean,
    onSelect: (BluetoothDeviceInfo) -> Unit,
) {
    val isSelected = selected != null &&
        selected.address.equals(info.address, ignoreCase = true) &&
        selected.link == info.link
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(info) },
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (info.link == BluetoothLink.LE) {
                    Icons.Filled.Bluetooth
                } else {
                    Icons.Filled.SettingsBluetooth
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AccentBadge(
                        text = stringResource(
                            if (info.link == BluetoothLink.LE) {
                                R.string.device_bt_badge_ble
                            } else {
                                R.string.device_bt_badge_spp
                            },
                        ),
                        color = if (info.link == BluetoothLink.LE) {
                            MaterialTheme.pm3Accents.info
                        } else {
                            MaterialTheme.pm3Accents.bluetooth
                        },
                    )
                }
                Text(
                    text = info.address +
                        if (info.bonded) {
                            " • ${stringResource(R.string.device_bt_bonded)}"
                        } else {
                            ""
                        } +
                        (info.rssi?.let { " • ${stringResource(R.string.device_bt_rssi, it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.device_bt_select),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeviceDetails(info: Pm3DeviceInfo) {
    SectionCard(
        title = stringResource(R.string.device_details),
        icon = Icons.Filled.DeveloperBoard,
        accent = MaterialTheme.pm3Accents.usb,
    ) {
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
private fun TransportButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White,
            ),
            modifier = modifier,
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            modifier = modifier,
        ) { content() }
    }
}
