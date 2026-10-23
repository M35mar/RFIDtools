// Tools screen: curated command buttons for the common tag families. Each
// entry is syntax-checked against the pinned client (the command tree in
// client/src/cmd*.c); full flexibility remains on the console tab.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.console.Pm3ViewModel

private data class ToolCommand(val label: String, val command: String)

private val LF_COMMANDS = listOf(
    ToolCommand("EM410x ( lettura)", "lf em 410x reader"),
    ToolCommand("HID (lettura)", "lf hid reader"),
    ToolCommand("ioProx (lettura)", "lf io reader"),
    ToolCommand("T55xx (rileva modulazione)", "lf t55xx detect"),
    ToolCommand("T55xx (dump)", "lf t55xx dump"),
)

private val HF_COMMANDS = listOf(
    ToolCommand("Ricerca tag HF", "hf search"),
    ToolCommand("ISO14443A (info)", "hf 14a info"),
)

private val MFC_COMMANDS = listOf(
    ToolCommand("MIFARE Classic (info)", "hf mf info"),
    ToolCommand("MIFARE Classic (dump)", "hf mf dump"),
    ToolCommand("MIFARE Classic (chiavi note)", "hf mf chk"),
)

private val MFU_COMMANDS = listOf(
    ToolCommand("MIFARE UL (info)", "hf mfu info"),
    ToolCommand("MIFARE UL (dump)", "hf mfu dump"),
    ToolCommand("MIFARE UL (NDEF)", "hf mfu ndefread"),
)

@Composable
fun ToolsScreen(viewModel: Pm3ViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Strumenti", style = MaterialTheme.typography.titleLarge)
        if (!enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Connetti prima il Proxmark3 (scheda Dispositivo).",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ToolSection("LF", LF_COMMANDS, enabled, viewModel)
        ToolSection("HF — generico", HF_COMMANDS, enabled, viewModel)
        ToolSection("HF — MIFARE Classic", MFC_COMMANDS, enabled, viewModel)
        ToolSection("HF — MIFARE Ultralight", MFU_COMMANDS, enabled, viewModel)
    }
}

@Composable
private fun ToolSection(
    title: String,
    commands: List<ToolCommand>,
    enabled: Boolean,
    viewModel: Pm3ViewModel,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            for (entry in commands) {
                OutlinedButton(
                    onClick = { viewModel.runCommand(entry.command) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
