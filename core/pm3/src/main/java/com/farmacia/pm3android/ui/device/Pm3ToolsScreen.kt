// PM3 tools, shown as the second sub-tab of the Device section: curated
// command buttons, searchable and collapsible. Every command is syntax-checked
// against the pinned client (the command trees in client/src/cmd*.c).
// Parametric commands show a dialog to fill in values.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.device

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.results.TagResultCard
import com.farmacia.pm3android.ui.scripts.ScriptsSection

private data class LocalizedToolCommand(
    val command: ToolCommand,
    val label: String,
    val description: String,
)

private data class LocalizedToolSection(
    val title: String,
    val commands: List<LocalizedToolCommand>,
)

@Composable
fun Pm3ToolsScreen(viewModel: Pm3ViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val history by viewModel.history.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy

    var pendingCommand by remember { mutableStateOf<LocalizedToolCommand?>(null) }
    var dangerPending by remember { mutableStateOf<Pair<String, LocalizedToolCommand>?>(null) }
    var search by rememberSaveable { mutableStateOf("") }

    val execute: (String, LocalizedToolCommand) -> Unit = { commandText, command ->
        if (command.command.danger == DangerLevel.SAFE) {
            viewModel.runCommand(commandText)
        } else {
            dangerPending = commandText to command
        }
    }

    val launch: (LocalizedToolCommand) -> Unit = { command ->
        if (command.command.params.isEmpty()) {
            execute(command.command.template, command)
        } else {
            pendingCommand = command
        }
    }

    val localizedSections = TOOL_SECTIONS.map { section ->
        LocalizedToolSection(
            title = stringResource(section.title),
            commands = section.commands.map { command ->
                LocalizedToolCommand(
                    command = command,
                    label = stringResource(command.label),
                    description = stringResource(command.description),
                )
            },
        )
    }
    val query = search.trim()
    val filteredSections = localizedSections.mapNotNull { section ->
        val commands = section.commands.filter { command ->
            query.isEmpty() ||
                command.label.contains(query, ignoreCase = true) ||
                command.command.template.contains(query, ignoreCase = true) ||
                command.description.contains(query, ignoreCase = true)
        }
        if (commands.isEmpty()) null else section.copy(commands = commands)
    }
    val recent = history.asReversed().take(5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.tools_disconnected),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        lastResult?.let { info ->
            TagResultCard(
                info = info,
                enabled = enabled,
                onRerun = { command -> viewModel.runCommand(command) },
            )
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.tools_search)) },
        )

        if (recent.isNotEmpty() && query.isEmpty()) {
            SectionCard(title = stringResource(R.string.tools_recent)) {
                recent.forEach { command ->
                    OutlinedButton(
                        onClick = { viewModel.runCommand(command) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(command, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (query.isEmpty()) {
            ScriptsSection(viewModel)
        }

        filteredSections.forEach { section ->
            ToolSection(section.title, section.commands, enabled, launch)
        }

        if (filteredSections.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        pendingCommand?.let { command ->
            ToolDialog(
                command = command,
                onDismiss = { pendingCommand = null },
                onConfirm = { values, switches ->
                    pendingCommand = null
                    execute(
                        composeCommand(command.command, values, switches),
                        command,
                    )
                },
            )
        }

        dangerPending?.let { (commandText, command) ->
            AlertDialog(
                onDismissRequest = { dangerPending = null },
                title = {
                    Text(
                        stringResource(
                            if (command.command.danger == DangerLevel.DESTRUCTIVE) {
                                R.string.danger_destructive_title
                            } else {
                                R.string.danger_write_title
                            },
                        ),
                    )
                },
                text = { Text(stringResource(R.string.danger_message, commandText)) },
                confirmButton = {
                    Button(
                        onClick = {
                            dangerPending = null
                            viewModel.runCommand(commandText)
                        },
                    ) {
                        Text(stringResource(R.string.danger_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dangerPending = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ToolDialog(
    command: LocalizedToolCommand,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>, Map<String, Boolean>) -> Unit,
) {
    val params = command.command.params
    val values = remember(command.command) {
        mutableStateMapOf<String, String>().apply {
            for (param in params) {
                param.asValue()?.let { put(it.name, it.defaultValue) }
            }
        }
    }
    val switches = remember(command.command) {
        mutableStateMapOf<String, Boolean>().apply {
            for (param in params) {
                param.asSwitch()?.let { put(it.name, false) }
            }
        }
    }
    val valid = params.asSequence()
        .mapNotNull { it.asValue() }
        .all { entryValid(it, values[it.name]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(command.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(command.description, style = MaterialTheme.typography.bodySmall)
                params.forEach { param ->
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
                            label = { Text(stringResource(value.label)) },
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
                            Text(
                                stringResource(switch.label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(values, switches) }, enabled = valid) {
                Text(stringResource(R.string.action_execute))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ToolSection(
    title: String,
    commands: List<LocalizedToolCommand>,
    enabled: Boolean,
    launch: (LocalizedToolCommand) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.common_less else R.string.common_more,
                    ),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    commands.forEach { entry ->
                        OutlinedButton(
                            onClick = { launch(entry) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
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
