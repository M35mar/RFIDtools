// Console screen: PM3 connection status, scrolled log, command input.
// This is the v1 console; other screens hop onto the same ViewModel.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.farmacia.pm3android.pm3.Pm3ConnectionState

@Composable
fun ConsoleScreen(viewModel: Pm3ViewModel = viewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val connectLog by viewModel.connectLog.collectAsState()
    val lines by viewModel.lines.collectAsState()

    var commandInput by remember { mutableStateOf("") }
    val scrollState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            scrollState.scrollToItem(lines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Console Proxmark3",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            when (connectionState) {
                Pm3ConnectionState.CONNECTED ->
                    OutlinedButton(onClick = viewModel::disconnect, enabled = !busy) {
                        Text("Disconnetti")
                    }
                Pm3ConnectionState.CONNECTING ->
                    OutlinedButton(onClick = {}, enabled = false) {
                        Text("Connessione…")
                    }
                Pm3ConnectionState.DISCONNECTED ->
                    Button(
                        onClick = viewModel::connect,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text("Connetti")
                    }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Stato: $connectionState", style = MaterialTheme.typography.bodySmall)
                if (connectLog.isNotBlank()) {
                    Text(
                        text = connectLog.trim().take(2000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Log: monospace, newest at the bottom
        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.padding(8.dp)) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(lines.size) { index ->
                        Text(
                            text = lines[index],
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                modifier = Modifier.weight(1f),
                enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy,
                placeholder = { Text("es. hw version") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        viewModel.runCommand(commandInput)
                        commandInput = ""
                    },
                ),
            )
            Button(
                onClick = {
                    viewModel.runCommand(commandInput)
                    commandInput = ""
                },
                enabled = connectionState == Pm3ConnectionState.CONNECTED &&
                    !busy && commandInput.isNotBlank(),
                modifier = Modifier.width(90.dp),
            ) {
                Text("Invia")
            }
            OutlinedButton(
                onClick = viewModel::clearConsole,
                enabled = !busy,
                modifier = Modifier.width(84.dp),
            ) {
                Text("Pulisci")
            }
        }
    }
}
