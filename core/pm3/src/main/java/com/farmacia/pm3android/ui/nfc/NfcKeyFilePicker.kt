// Key-file picker shared by the restore overlay and the two write tools.
// The selected file lives in the app home, so the same picker works for a
// Classic key file and for a key file imported from phone storage.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.R

@Composable
fun NfcKeyFilePicker(
    title: String,
    selected: String?,
    files: List<String>,
    noneLabel: String,
    emptyLabel: String,
    help: String,
    error: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected ?: noneLabel,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (files.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(emptyLabel) },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                } else {
                    files.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                expanded = false
                                onSelect(name)
                            },
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                // "*/*" because many providers do not report a MIME type for
                // .bin files; NfcFileStore refuses anything that is not
                // .bin/.txt right after the pick.
                launcher.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.nfc_file_import))
        }
        if (selected != null) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.nfc_file_clear))
            }
        }
        Text(
            text = error ?: help,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
