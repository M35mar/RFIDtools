// NFC tab: the phone reader as a first-class feature, independent from the
// Proxmark3. Reading creates a dump, writing restores one; the full-screen
// overlay keeps the reader mode active until the tag operation finishes.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.nfc

import android.nfc.NfcAdapter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SaveAlt
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.theme.pm3Accents

@Composable
fun NfcScreen(
    viewModel: Pm3ViewModel,
    restoreViewModel: NfcRestoreViewModel,
    onIdentify: () -> Unit,
    onOpenApduConsole: () -> Unit,
    onOpenCounterTool: () -> Unit,
    onOpenValueTool: () -> Unit,
    onOpenUidTool: () -> Unit,
    onOpenNdefWriter: () -> Unit,
    onOpenType2Pwd: () -> Unit = {},
    onOpenHce: () -> Unit = {},
    onOpenDiff: () -> Unit = {},
    onFormatClassic: () -> Unit = {},
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
            val accents = MaterialTheme.pm3Accents
            Text(
                text = stringResource(R.string.nfc_screen_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NfcTile(
                    icon = Icons.Filled.Nfc,
                    title = stringResource(R.string.nfc_read_title),
                    description = stringResource(R.string.nfc_read_desc),
                    accent = accents.usb,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onIdentify,
                )
                NfcTile(
                    icon = Icons.Filled.SaveAlt,
                    title = stringResource(R.string.nfc_write_title),
                    description = stringResource(R.string.nfc_write_desc),
                    accent = accents.hf,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.refreshFiles()
                        pickingDump = true
                    },
                )
                NfcTile(
                    icon = Icons.AutoMirrored.Filled.Send,
                    title = stringResource(R.string.apdu_title),
                    description = stringResource(R.string.nfc_apdu_desc),
                    accent = accents.bluetooth,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenApduConsole,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NfcTile(
                    icon = Icons.Filled.AddCircle,
                    title = stringResource(R.string.nfc_counter_title),
                    description = stringResource(R.string.nfc_counter_desc),
                    accent = accents.lf,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCounterTool,
                )
                NfcTile(
                    icon = Icons.Filled.CreditCard,
                    title = stringResource(R.string.nfc_value_title),
                    description = stringResource(R.string.nfc_value_desc),
                    accent = accents.script,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenValueTool,
                )
                NfcTile(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.nfc_uid_title),
                    description = stringResource(R.string.nfc_uid_desc),
                    accent = accents.danger,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenUidTool,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NfcTile(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.nfc_t2pwd_title),
                    description = stringResource(R.string.nfc_t2pwd_desc),
                    accent = accents.lf,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenType2Pwd,
                )
                NfcTile(
                    icon = Icons.Filled.CleaningServices,
                    title = stringResource(R.string.nfc_format_classic_title),
                    description = stringResource(R.string.nfc_format_classic_desc),
                    accent = accents.danger,
                    enabled = nfcEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onFormatClassic,
                )
                NfcTile(
                    icon = Icons.AutoMirrored.Filled.CompareArrows,
                    title = stringResource(R.string.nfc_diff_title),
                    description = stringResource(R.string.nfc_diff_desc),
                    accent = accents.info,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDiff,
                )
            }
            NfcWideTile(
                icon = Icons.Filled.EditNote,
                title = stringResource(R.string.nfc_ndef_title),
                description = stringResource(R.string.nfc_ndef_desc),
                accent = accents.info,
                enabled = nfcEnabled,
                onClick = onOpenNdefWriter,
            )
            NfcWideTile(
                icon = Icons.Filled.Radar,
                title = stringResource(R.string.nfc_hce_title),
                description = stringResource(R.string.nfc_hce_desc),
                accent = accents.hf,
                enabled = nfcEnabled,
                onClick = onOpenHce,
            )
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
    val accents = MaterialTheme.pm3Accents
    val statusColor = when {
        !available -> MaterialTheme.colorScheme.outline
        enabled -> accents.hf
        else -> accents.danger
    }
    val statusText = when {
        !available -> stringResource(R.string.nfc_status_unavailable)
        enabled -> stringResource(R.string.nfc_status_active)
        else -> stringResource(R.string.nfc_status_off)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.25.dp, statusColor.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.16f),
                            accents.usb.copy(alpha = 0.06f),
                        ),
                    ),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.18f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = null,
                        tint = statusColor,
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
private fun NfcTile(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.16f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NfcWideTile(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) accent.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.16f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = accent,
            )
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
