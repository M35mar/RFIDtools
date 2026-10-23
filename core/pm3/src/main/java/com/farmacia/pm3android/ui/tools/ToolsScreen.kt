// Tools screen: curated command buttons. Every command is syntax-checked
// against the pinned client (the command trees in client/src/cmd*.c).
// Parametric commands show a dialog to fill in the values, then the template
// placeholders ({name}) are substituted and the command goes to the console.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.console.Pm3ViewModel


/** Factories returning the common type, so list inference stays simple. */
private fun valueOf(
    name: String,
    label: String,
    placeholder: String,
    defaultValue: String = "",
    digitsOnly: Boolean = false,
): ToolParam = ToolValue(name, label, placeholder, defaultValue, digitsOnly)

private fun flagOf(label: String, literal: String): ToolParam = ToolSwitch(literal, label, literal)

/** A value the user types in; digitsOnly forbids anything but numbers. */
private data class ToolValue(
    val name: String,
    val label: String,
    val placeholder: String,
    val defaultValue: String = "",
    val digitsOnly: Boolean = false,
) : ToolParam

/** A checkbox that appends its literal (already with dashes) when checked. */
private data class ToolSwitch(
    val name: String,
    val label: String,
    val literal: String,
) : ToolParam

private sealed interface ToolParam

private fun ToolParam.asValue(): ToolValue? = this as? ToolValue
private fun ToolParam.asSwitch(): ToolSwitch? = this as? ToolSwitch

private data class ToolCommand(
    val label: String,
    /** Command template; "{name}" tokens are replaced by ToolValue entries. */
    val template: String,
    val params: List<ToolParam> = emptyList(),
)

private val LF_COMMANDS = listOf(
    ToolCommand("EM410x (lettura)", "lf em 410x reader"),
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

private val MFU_EV1_COMMANDS = listOf(
    ToolCommand("Info", "hf mfu info"),
    ToolCommand("Dump", "hf mfu dump"),
    ToolCommand(
        "Ripristina dump sul tag",
        "hf mfu restore -f {file}",
        listOf(
            valueOf("file", "Nome del file dump", "vedi scheda Dati"),
        ),
    ),
    ToolCommand(
        "Incrementa contatore",
        "hf mfu incr -c {counter} -v {value}",
        listOf(
            valueOf("counter", "Contatore (0-2)", "0", defaultValue = "0", digitsOnly = true),
            valueOf("value", "Incremento (0-16777215)", "1", defaultValue = "1", digitsOnly = true),
        ),
    ),
    ToolCommand(
        "Tear-off nativo (countertear)",
        "hf mfu countertear -c {counter}",
        listOf(
            valueOf("counter", "Contatore (0-2)", "0", defaultValue = "0", digitsOnly = true),
        ),
    ),
    ToolCommand(
        "Reset contatori (anti-tearing, script)",
        "script run mfuev1_counter_reset.lua -c {counter}",
        listOf(
            valueOf("counter", "Contatore (0-2)", "0", defaultValue = "0", digitsOnly = true),
            flagOf("Salta controlli preliminari", "-f"),
        ),
    ),
)

@Composable
fun ToolsScreen(viewModel: Pm3ViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy

    var pendingCommand by remember { mutableStateOf<ToolCommand?>(null) }

    /** No params: run now; else open the dialog first. */
    val launch: (ToolCommand) -> Unit = { command ->
        if (command.params.isEmpty()) {
            viewModel.runCommand(command.template)
        } else {
            pendingCommand = command
        }
    }

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

        ToolSection("LF", LF_COMMANDS, enabled, launch)
        ToolSection("HF — generico", HF_COMMANDS, enabled, launch)
        ToolSection("HF — MIFARE Classic", MFC_COMMANDS, enabled, launch)
        ToolSection("HF — MIFARE Ultralight", MFU_COMMANDS, enabled, launch)
        ToolSection("HF — MIFARE Ultralight Ev1", MFU_EV1_COMMANDS, enabled, launch)

        pendingCommand?.let { command ->
            ToolDialog(
                command = command,
                onDismiss = { pendingCommand = null },
                onConfirm = { values, switches ->
                    pendingCommand = null
                    viewModel.runCommand(composeCommand(command, values, switches))
                },
            )
        }
    }
}

private fun composeCommand(
    command: ToolCommand,
    values: Map<String, String>,
    switches: Map<String, Boolean>,
): String {
    var result = command.template
    for (param in command.params) {
        param.asValue()?.let { value ->
            result = result.replace(
                "{${value.name}}",
                values[value.name]?.takeIf { it.isNotBlank() } ?: value.defaultValue,
            )
        }
        param.asSwitch()?.let { switch ->
            if (switches[switch.name] == true) {
                result = "$result ${switch.literal}"
            }
        }
    }
    return result
}

private fun entryValid(entry: ToolValue, text: String?): Boolean {
    val content = text.orEmpty()
    return if (entry.digitsOnly) {
        content.isNotEmpty() && content.all { it.isDigit() }
    } else {
        content.isNotBlank()
    }
}

@Composable
private fun ToolDialog(
    command: ToolCommand,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>, Map<String, Boolean>) -> Unit,
) {
    val values = remember(command) {
        mutableStateMapOf<String, String>().apply {
            for (param in command.params) {
                param.asValue()?.let { put(it.name, it.defaultValue) }
            }
        }
    }
    val switches = remember(command) {
        mutableStateMapOf<String, Boolean>().apply {
            for (param in command.params) {
                param.asSwitch()?.let { put(it.name, false) }
            }
        }
    }
    val valid = command.params.toList().asSequence()
        .mapNotNull { it.asValue() }
        .all { entryValid(it, values[it.name]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(command.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                command.params.forEach { param ->
                    param.asValue()?.let { value ->
                        OutlinedTextField(
                            value = values[value.name].orEmpty(),
                            onValueChange = { input ->
                                values[value.name] = if (value.digitsOnly) {
                                    input.filter { it.isDigit() }
                                } else {
                                    input
                                }
                            },
                            label = { Text(value.label) },
                            placeholder = { Text(value.placeholder) },
                            singleLine = true,
                        )
                    }
                    param.asSwitch()?.let { switch ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(
                                checked = switches[switch.name] == true,
                                onCheckedChange = { checked -> switches[switch.name] = checked },
                            )
                            Text(switch.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(values, switches) }, enabled = valid) {
                Text("Esegui")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

@Composable
private fun ToolSection(
    title: String,
    commands: List<ToolCommand>,
    enabled: Boolean,
    launch: (ToolCommand) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            for (entry in commands) {
                OutlinedButton(
                    onClick = { launch(entry) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
