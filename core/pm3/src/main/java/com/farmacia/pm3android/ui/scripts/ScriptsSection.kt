// User script section for the Tools tab: import Lua/Cmd/Python files, run them
// through the client (or the embedded Python runtime), and remove them without
// touching bundled client resources.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.Pm3Script
import com.farmacia.pm3android.data.ScriptLanguage
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.ConfirmDialog
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.util.formatBytes

@Composable
fun ScriptsSection(viewModel: Pm3ViewModel) {
    val scripts by viewModel.scripts.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val fileOpBusy by viewModel.fileOpBusy.collectAsState()

    var pendingDelete by remember { mutableStateOf<Pm3Script?>(null) }
    var pendingRun by remember { mutableStateOf<Pm3Script?>(null) }
    var runArguments by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importScript) }

    val canRun = connectionState == Pm3ConnectionState.CONNECTED && !busy && !fileOpBusy

    SectionCard(title = stringResource(R.string.scripts_title)) {
        Text(
            text = stringResource(R.string.scripts_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !busy && !fileOpBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.scripts_import))
        }

        if (scripts.isEmpty()) {
            Text(
                text = stringResource(R.string.scripts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            scripts.forEach { script ->
                ScriptRow(
                    script = script,
                    canRun = canRun,
                    canEdit = !busy && !fileOpBusy,
                    onRun = {
                        runArguments = ""
                        pendingRun = script
                    },
                    onDelete = { pendingDelete = script },
                )
            }
        }
    }

    pendingDelete?.let { script ->
        ConfirmDialog(
            title = stringResource(R.string.scripts_delete_title),
            body = stringResource(R.string.scripts_delete_body, script.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.deleteScript(script) },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }

    pendingRun?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            title = { Text(stringResource(R.string.scripts_run_title, script.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.scripts_run_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = runArguments,
                        onValueChange = { runArguments = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.scripts_args)) },
                        placeholder = { Text("-h") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.runScript(script, runArguments)
                        pendingRun = null
                    },
                ) {
                    Text(stringResource(R.string.action_execute))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRun = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScriptRow(
    script: Pm3Script,
    canRun: Boolean,
    canEdit: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${languageLabel(script.language)} · ${formatBytes(script.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRun, enabled = canRun) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.action_execute),
            )
        }
        IconButton(onClick = onDelete, enabled = canEdit) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
            )
        }
    }
}

@Composable
private fun languageLabel(language: ScriptLanguage): String = when (language) {
    ScriptLanguage.LUA -> stringResource(R.string.scripts_language_lua)
    ScriptLanguage.CMD -> stringResource(R.string.scripts_language_cmd)
    ScriptLanguage.PYTHON -> stringResource(R.string.scripts_language_python)
}
