// Tools screen: curated command buttons, searchable and collapsible. Every
// command is syntax-checked against the pinned client (the command trees in
// client/src/cmd*.c). Parametric commands show a dialog to fill in values.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.SaveAlt
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
import com.farmacia.pm3android.data.FileCategory
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.scripts.ScriptsSection

/** Factories returning the common type, so list inference stays simple. */
private fun valueOf(
    name: String,
    @StringRes label: Int,
    placeholder: String,
    defaultValue: String = "",
    digitsOnly: Boolean = false,
): ToolParam = ToolValue(name, label, placeholder, defaultValue, digitsOnly)

private fun flagOf(@StringRes label: Int, literal: String): ToolParam =
    ToolSwitch(literal, label, literal)

/** A value the user types in; digitsOnly forbids anything but numbers. */
private data class ToolValue(
    val name: String,
    @StringRes val label: Int,
    val placeholder: String,
    val defaultValue: String = "",
    val digitsOnly: Boolean = false,
) : ToolParam

/** A checkbox that appends its literal (already with dashes) when checked. */
private data class ToolSwitch(
    val name: String,
    @StringRes val label: Int,
    val literal: String,
) : ToolParam

private sealed interface ToolParam

private fun ToolParam.asValue(): ToolValue? = this as? ToolValue
private fun ToolParam.asSwitch(): ToolSwitch? = this as? ToolSwitch

private data class ToolCommand(
    @StringRes val label: Int,
    /** Command template; "{name}" tokens are replaced by ToolValue entries. */
    val template: String,
    @StringRes val description: Int,
    val params: List<ToolParam> = emptyList(),
)

private data class ToolSectionData(
    @StringRes val title: Int,
    val commands: List<ToolCommand>,
)

private data class LocalizedToolCommand(
    val command: ToolCommand,
    val label: String,
    val description: String,
)

private data class LocalizedToolSection(
    val title: String,
    val commands: List<LocalizedToolCommand>,
)

private val LF_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_lf_em410x_label,
        "lf em 410x reader",
        R.string.tool_lf_em410x_desc,
    ),
    ToolCommand(
        R.string.tool_lf_hid_label,
        "lf hid reader",
        R.string.tool_lf_hid_desc,
    ),
    ToolCommand(
        R.string.tool_lf_ioprox_label,
        "lf io reader",
        R.string.tool_lf_ioprox_desc,
    ),
    ToolCommand(
        R.string.tool_lf_t55xx_detect_label,
        "lf t55xx detect",
        R.string.tool_lf_t55xx_detect_desc,
    ),
    ToolCommand(
        R.string.tool_lf_t55xx_dump_label,
        "lf t55xx dump",
        R.string.tool_lf_t55xx_dump_desc,
    ),
)

private val HF_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_hf_search_label,
        "hf search",
        R.string.tool_hf_search_desc,
    ),
    ToolCommand(
        R.string.tool_hf_14a_info_label,
        "hf 14a info",
        R.string.tool_hf_14a_info_desc,
    ),
)

private val MFC_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_mfc_info_label,
        "hf mf info",
        R.string.tool_mfc_info_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_dump_label,
        "hf mf dump",
        R.string.tool_mfc_dump_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_chk_label,
        "hf mf chk",
        R.string.tool_mfc_chk_desc,
    ),
    ToolCommand(
        R.string.tool_mfc_autopwn_label,
        "hf mf autopwn",
        R.string.tool_mfc_autopwn_desc,
    ),
)

private val MFU_EV1_COMMANDS = listOf(
    ToolCommand(
        R.string.tool_mfuev1_info_label,
        "hf mfu info",
        R.string.tool_mfuev1_info_desc,
    ),
    ToolCommand(
        R.string.tool_mfuev1_dump_label,
        "hf mfu dump",
        R.string.tool_mfuev1_dump_desc,
    ),
    ToolCommand(
        R.string.tool_mfuev1_restore_label,
        "hf mfu restore -f {file}",
        R.string.tool_mfuev1_restore_desc,
        listOf(
            valueOf(
                "file",
                R.string.tool_mfuev1_restore_param_file,
                "dump.bin",
            ),
        ),
    ),
    ToolCommand(
        R.string.tool_mfuev1_incr_label,
        "hf mfu incr -c {counter} -v {value}",
        R.string.tool_mfuev1_incr_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
            valueOf(
                "value",
                R.string.tool_mfuev1_increment_param,
                "1",
                defaultValue = "1",
                digitsOnly = true,
            ),
        ),
    ),
    ToolCommand(
        R.string.tool_mfuev1_countertear_label,
        "hf mfu countertear -c {counter}",
        R.string.tool_mfuev1_countertear_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
        ),
    ),
    ToolCommand(
        R.string.tool_mfuev1_reset_label,
        "script run mfuev1_counter_reset.lua -c {counter}",
        R.string.tool_mfuev1_reset_desc,
        listOf(
            valueOf(
                "counter",
                R.string.tool_mfuev1_counter_param,
                "0",
                defaultValue = "0",
                digitsOnly = true,
            ),
            flagOf(R.string.tool_param_skip_checks, "-f"),
        ),
    ),
)

@Composable
fun ToolsScreen(
    viewModel: Pm3ViewModel,
    onRestore: (java.io.File) -> Unit,
    onDump: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val history by viewModel.history.collectAsState()
    val files by viewModel.files.collectAsState()
    val enabled = connectionState == Pm3ConnectionState.CONNECTED && !busy

    var pendingCommand by remember { mutableStateOf<LocalizedToolCommand?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var pickingDump by remember { mutableStateOf(false) }

    val launch: (LocalizedToolCommand) -> Unit = { command ->
        if (command.command.params.isEmpty()) {
            viewModel.runCommand(command.command.template)
        } else {
            pendingCommand = command
        }
    }

    val sections = listOf(
        ToolSectionData(R.string.tool_section_lf, LF_COMMANDS),
        ToolSectionData(R.string.tool_section_hf_generic, HF_COMMANDS),
        ToolSectionData(R.string.tool_section_mfc, MFC_COMMANDS),
        ToolSectionData(R.string.tool_section_mfuev1, MFU_EV1_COMMANDS),
    )
    val localizedSections = sections.map { section ->
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
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.titleLarge)
        if (!enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.tools_disconnected),
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (query.isEmpty()) {
            PhoneNfcCard(
                onPick = {
                    viewModel.refreshFiles()
                    pickingDump = true
                },
                onDump = onDump,
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
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
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
                    viewModel.runCommand(
                        composeCommand(command.command, values, switches),
                    )
                },
            )
        }

        if (pickingDump) {
            DumpPickerDialog(
                files = files,
                onDismiss = { pickingDump = false },
                onPick = { file ->
                    pickingDump = false
                    onRestore(file)
                },
            )
        }
    }
}

@Composable
private fun PhoneNfcCard(onPick: () -> Unit, onDump: () -> Unit) {
    SectionCard(title = stringResource(R.string.nfc_title)) {
        Text(
            text = stringResource(R.string.nfc_tools_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onDump, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.nfc_dump_action))
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Nfc, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.nfc_pick_dump))
            }
        }
    }
}

@Composable
private fun DumpPickerDialog(
    files: List<Pm3File>,
    onDismiss: () -> Unit,
    onPick: (java.io.File) -> Unit,
) {
    val dumps = files.filter { it.category == FileCategory.DUMP }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nfc_pick_dump)) },
        text = {
            if (dumps.isEmpty()) {
                Text(stringResource(R.string.nfc_pick_empty))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    dumps.forEach { entry ->
                        OutlinedButton(
                            onClick = { onPick(entry.file) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    text = entry.file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entry.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
