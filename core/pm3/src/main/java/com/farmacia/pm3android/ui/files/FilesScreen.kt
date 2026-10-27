// Files screen: user-visible dumps/traces/logs. Client resources are filtered
// out by Pm3FileFilter; SAF import/export and a FileProvider share action are
// provided for moving files in and out of the app sandbox.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.FileCategory
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.ui.components.ConfirmDialog
import com.farmacia.pm3android.ui.components.EmptyState
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.util.formatBytes
import com.farmacia.pm3android.util.formatDate

@Composable
fun FilesScreen(
    viewModel: Pm3ViewModel,
    onRestore: (java.io.File) -> Unit,
    onEditDump: (java.io.File) -> Unit,
) {
    val files by viewModel.files.collectAsState()
    val loading by viewModel.filesLoading.collectAsState()
    val fileOpBusy by viewModel.fileOpBusy.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    var filter by remember { mutableStateOf<FileCategory?>(null) }
    var pendingDelete by remember { mutableStateOf<Pm3File?>(null) }
    var pendingExport by remember { mutableStateOf<Pm3File?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFile) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri != null && entry != null) {
            viewModel.exportFile(uri, entry)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshFiles()
    }

    val visible = files.filter { filter == null || it.category == filter }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.files_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (fileOpBusy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = viewModel::refreshFiles) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.files_description),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !fileOpBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.files_import))
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text(stringResource(R.string.files_filter_all)) },
            )
            FilterChip(
                selected = filter == FileCategory.DUMP,
                onClick = { filter = FileCategory.DUMP },
                label = { Text(stringResource(R.string.files_filter_dumps)) },
            )
            FilterChip(
                selected = filter == FileCategory.TRACE,
                onClick = { filter = FileCategory.TRACE },
                label = { Text(stringResource(R.string.files_filter_traces)) },
            )
            FilterChip(
                selected = filter == FileCategory.LOG,
                onClick = { filter = FileCategory.LOG },
                label = { Text(stringResource(R.string.files_filter_logs)) },
            )
            FilterChip(
                selected = filter == FileCategory.OTHER,
                onClick = { filter = FileCategory.OTHER },
                label = { Text(stringResource(R.string.files_filter_other)) },
            )
        }

        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.files_loading),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            visible.isEmpty() -> EmptyState(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.files_title),
                body = stringResource(R.string.files_empty),
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visible, key = { it.relativePath }) { entry ->
                    FileRow(
                        entry = entry,
                        enabled = !fileOpBusy,
                        onOpen = { onEditDump(entry.file) },
                        onRestore = { onRestore(entry.file) },
                        onShare = { shareFile(context, entry.file) },
                        onExport = {
                            pendingExport = entry
                            exportLauncher.launch(entry.file.name)
                        },
                        onDelete = {
                            if (settings.confirmDelete) {
                                pendingDelete = entry
                            } else {
                                viewModel.deleteFile(entry)
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.files_delete_title),
            body = stringResource(R.string.files_delete_body, entry.relativePath),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.deleteFile(entry) },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun FileRow(
    entry: Pm3File,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val lowerName = entry.file.name.lowercase()
    val editable = entry.category == FileCategory.DUMP &&
        lowerName.endsWith(".bin") &&
        !lowerName.endsWith("-key.bin")
    // Only editable rows use the clickable Card variant: the plain one keeps
    // the normal (non-disabled) colours for traces, logs and other dumps.
    if (editable) {
        Card(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FileRowContent(
                entry = entry,
                enabled = enabled,
                editable = true,
                onRestore = onRestore,
                onShare = onShare,
                onExport = onExport,
                onDelete = onDelete,
            )
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            FileRowContent(
                entry = entry,
                enabled = enabled,
                editable = false,
                onRestore = onRestore,
                onShare = onShare,
                onExport = onExport,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun FileRowContent(
    entry: Pm3File,
    enabled: Boolean,
    editable: Boolean,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.bodyMedium,
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
            Text(
                text = stringResource(
                    R.string.files_size_date,
                    formatBytes(entry.size),
                    formatDate(entry.lastModified),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (editable) {
                Text(
                    text = stringResource(R.string.dump_editor_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (entry.category == FileCategory.DUMP) {
            IconButton(onClick = onRestore, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Nfc,
                    contentDescription = stringResource(R.string.nfc_restore_action),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onShare, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.action_share),
            )
        }
        IconButton(onClick = onExport, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.SaveAlt,
                contentDescription = stringResource(R.string.action_export),
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun shareFile(context: Context, file: java.io.File) {
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.files_share_failed, it.message.orEmpty()),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
