// Update UI shared by the startup dialog and the Info -> Link card.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.R
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.update.ReleaseInfo
import com.farmacia.pm3android.update.UpdateState
import com.farmacia.pm3android.util.formatBytes

@Composable
fun UpdatePromptDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.update_dialog_body,
                        release.versionName,
                        currentVersion,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_update_notes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onInstall) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(
                    text = stringResource(R.string.update_dialog_install),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dialog_later))
            }
        },
    )
}

@Composable
fun UpdateSection(
    state: UpdateState,
    autoCheck: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_updates),
            style = MaterialTheme.typography.titleMedium,
        )
        InfoRow(
            stringResource(R.string.settings_update_installed),
            BuildConfig.VERSION_NAME.removeSuffix("-debug"),
        )

        when (state) {
            is UpdateState.Idle -> Text(
                text = stringResource(R.string.settings_update_not_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UpdateState.Checking -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                Text(
                    text = stringResource(R.string.settings_update_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is UpdateState.UpToDate -> Text(
                text = stringResource(R.string.settings_update_current, state.currentVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            is UpdateState.Available -> {
                Text(
                    text = stringResource(
                        R.string.settings_update_available,
                        state.release.versionName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.release.notes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_update_notes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = state.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text(
                        text = stringResource(R.string.settings_update_download),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            is UpdateState.Downloading -> {
                Text(
                    text = stringResource(
                        R.string.settings_update_downloading,
                        state.release.versionName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_update_progress,
                            formatBytes(state.bytesRead),
                            formatBytes(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is UpdateState.Ready -> {
                Text(
                    text = stringResource(R.string.settings_update_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.needsPermission) {
                    Text(
                        text = stringResource(R.string.update_permission_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                    Text(
                        text = stringResource(R.string.settings_update_install),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            is UpdateState.Error -> Text(
                text = stringResource(R.string.settings_update_error, state.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            onClick = onCheck,
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text(
                text = stringResource(R.string.settings_update_check),
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = autoCheck,
                    role = Role.Switch,
                    onValueChange = onAutoCheckChange,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_update_auto),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = autoCheck, onCheckedChange = null)
        }
    }
}
