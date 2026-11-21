// Reusable Compose pieces: cards, status pill, info rows, empty states,
// confirmation dialogs and small platform helpers.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.farmacia.pm3android.R
import com.farmacia.pm3android.ui.theme.pm3Chrome
import java.io.File

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFF8FAFC),
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.22f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
        }
    }
}

@Composable
fun AccentBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.20f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val chrome = MaterialTheme.pm3Chrome
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chrome.cardBg,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = accent?.copy(alpha = 0.35f) ?: chrome.cardBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (icon != null) {
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.15f),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(16.dp),
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(
                    confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

fun copyText(context: Context, text: String) {
    // A very large clip can exceed the binder transaction limit; fall back to
    // the share sheet instead of crashing the click handler.
    val copied = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("RFIDtools", text))
    }.isSuccess
    if (copied) {
        Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
    } else {
        shareText(context, text, "RFIDtools")
    }
}

private const val MAX_INLINE_SHARE_CHARS = 100_000

fun shareText(context: Context, text: String, subject: String) {
    runCatching {
        val intent = if (text.length > MAX_INLINE_SHARE_CHARS) {
            // Avoid TransactionTooLargeException on long console logs by
            // sharing a temporary file via FileProvider instead of EXTRA_TEXT.
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "rfidtools-log.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }.onFailure {
        Toast.makeText(context, R.string.common_no_app, Toast.LENGTH_SHORT).show()
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        Toast.makeText(context, R.string.common_no_app, Toast.LENGTH_SHORT).show()
    }
}
