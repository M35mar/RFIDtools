// Device screen: connection control and the standard device interrogation
// commands. This is the home tab.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.console.Pm3ViewModel

@Composable
fun DeviceScreen(viewModel: Pm3ViewModel) {
    val device by viewModel.device.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()

    LaunchedEffect(device) {
        viewModel.refreshDevice()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Proxmark3", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = when {
                        device == null -> "Nessun dispositivo USB collegato"
                        connectionState == Pm3ConnectionState.CONNECTED ->
                            "Connesso: ${device?.name} — ${device?.product ?: ""}"
                        else -> "Dispositivo pronto: ${device?.name} (permesso da richiedere)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::connect,
                enabled = !busy && connectionState != Pm3ConnectionState.CONNECTED,
                modifier = Modifier.weight(1f),
            ) {
                Text("Connetti")
            }
            OutlinedButton(
                onClick = viewModel::disconnect,
                enabled = !busy && connectionState == Pm3ConnectionState.CONNECTED,
                modifier = Modifier.weight(1f),
            ) {
                Text("Disconnetti")
            }
        }

        Text("Info sul dispositivo", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickCommand(viewModel, "hw version", enabled = !busy)
            QuickCommand(viewModel, "hw status", enabled = !busy)
            QuickCommand(viewModel, "hw ping", enabled = !busy)
        }

        if (connectLog.isNotBlank()) {
            Text("Log connessione", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = connectLog.trim().take(4000),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun QuickCommand(viewModel: Pm3ViewModel, command: String, enabled: Boolean) {
    OutlinedButton(
        onClick = { viewModel.runCommand(command) },
        enabled = enabled,
    ) {
        Text(command, style = MaterialTheme.typography.bodySmall)
    }
}
