// Files screen: the client writes dumps/traces/logs into $HOME (app private
// storage); files are shared out with ACTION_SEND through a FileProvider.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.files

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.farmacia.pm3android.ui.console.Pm3File
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import java.io.File

@Composable
fun FilesScreen(viewModel: Pm3ViewModel) {
    val files by viewModel.files.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshFiles()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dati salvati", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Il client scrive dump, tracce e log nella memoria privata " +
                    "dell'app (puliti disinstallando l'app).",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(files) { entry ->
                FileRow(entry, viewModel, context)
            }
        }
    }
}

@Composable
private fun FileRow(entry: Pm3File, viewModel: Pm3ViewModel, context: android.content.Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${java.text.NumberFormat.getIntegerInstance().format(entry.size)} byte — ${entry.file.parentFile?.name}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = { share(context, entry.file) }) {
            Text("Condividi")
        }
        OutlinedButton(onClick = { viewModel.deleteFile(entry) }) {
            Text("Elimina")
        }
    }
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}
