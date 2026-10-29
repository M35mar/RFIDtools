// NFC tab: the phone reader as a first-class feature, independent from the
// Proxmark3. Reading creates a dump, writing restores one; the full-screen
// overlay keeps the reader mode active until the tag operation finishes.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.nfc.NfcAdapter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.FileCategory
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.nfc.NfcRestoreViewModel
import com.farmacia.pm3android.nfc.NfcSession
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.console.Pm3ViewModel

@Composable
fun NfcScreen(
    viewModel: Pm3ViewModel,
    restoreViewModel: NfcRestoreViewModel,
    onIdentify: () -> Unit,
    onOpenApduConsole: () -> Unit,
) {
    val files by viewModel.files.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val session = remember(activity) { activity?.let { NfcSession(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current

    var nfcEnabled by remember { mutableStateOf(session?.isEnabled == true) }
    var pickingDump by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabled = session?.isEnabled == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshFiles()
    }

    val available = session?.available ?: (NfcAdapter.getDefaultAdapter(context) != null)
    val dumps = files.filter { it.category == FileCategory.DUMP }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NfcHeroCard(
            available = available,
            enabled = nfcEnabled,
            onOpenSettings = { NfcSession.openSystemSettings(context) },
        )

        if (available) {
            Text(
                text = stringResource(R.string.nfc_screen_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NfcActionCard(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.nfc_identify_title),
                description = stringResource(R.string.nfc_identify_desc),
                supporting = stringResource(R.string.nfc_identify_supporting),
                enabled = nfcEnabled,
                onClick = onIdentify,
            )
            NfcActionCard(
                icon = Icons.Filled.Send,
                title = stringResource(R.string.apdu_title),
                description = stringResource(R.string.nfc_apdu_desc),
                supporting = stringResource(R.string.nfc_apdu_supporting),
                enabled = nfcEnabled,
                onClick = onOpenApduConsole,
            )
            NfcActionCard(
                icon = Icons.Filled.SaveAlt,
                title = stringResource(R.string.nfc_read_title),
                description = stringResource(R.string.nfc_read_desc),
                enabled = nfcEnabled,
                onClick = { restoreViewModel.startDump() },
            )
            NfcActionCard(
                icon = Icons.Filled.Nfc,
                title = stringResource(R.string.nfc_write_title),
                description = stringResource(R.string.nfc_write_desc),
                supporting = stringResource(R.string.nfc_dumps_available, dumps.size),
                enabled = nfcEnabled,
                onClick = {
                    viewModel.refreshFiles()
                    pickingDump = true
                },
            )
            SectionCard(title = stringResource(R.string.nfc_howto_title)) {
                NfcHint(
                    number = "1",
                    text = stringResource(R.string.nfc_howto_read),
                )
                NfcHint(
                    number = "2",
                    text = stringResource(R.string.nfc_howto_write),
                )
                NfcHint(
                    number = "3",
                    text = stringResource(R.string.nfc_howto_classic),
                )
            }
        }
    }

    if (pickingDump) {
        DumpPickerDialog(
            files = files,
            onDismiss = { pickingDump = false },
            onPick = { file ->
                pickingDump = false
                restoreViewModel.open(file)
            },
        )
    }
}

@Composable
private fun NfcHeroCard(
    available: Boolean,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val statusColor = when {
        !available -> MaterialTheme.colorScheme.outline
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val statusText = when {
        !available -> stringResource(R.string.nfc_status_unavailable)
        enabled -> stringResource(R.string.nfc_status_active)
        else -> stringResource(R.string.nfc_status_off)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(12.dp)
                            .size(30.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.nfc_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.nfc_screen_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = statusText, color = statusColor)
            }

            when {
                !available -> Text(
                    text = stringResource(R.string.nfc_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                )

                !enabled -> {
                    Text(
                        text = stringResource(R.string.nfc_off_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.nfc_off_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun NfcActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    supporting: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(26.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NfcHint(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
