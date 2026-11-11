// Console screen: status, scrolled log, command history and input.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.ConfirmDialog
import com.farmacia.pm3android.ui.components.EmptyState
import com.farmacia.pm3android.ui.components.copyText
import com.farmacia.pm3android.ui.components.shareText
import com.farmacia.pm3android.ui.theme.TerminalPalette
import com.farmacia.pm3android.ui.theme.ansiColor
import com.farmacia.pm3android.ui.theme.terminalPalette

@Composable
fun ConsoleScreen(viewModel: Pm3ViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val history by viewModel.history.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val consoleTitle = stringResource(R.string.console_title)

    var commandInput by rememberSaveable { mutableStateOf("") }
    val follow = settings.followConsole
    var historyOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scrollState = rememberLazyListState()
    val appended by viewModel.consoleCount.collectAsState()
    val palette = terminalPalette()
    val darkTheme = isSystemInDarkTheme()
    val colored = settings.consoleColors

    // Keyed on the total number of lines ever appended: lines.size saturates
    // at the 5000-line window, which used to stop the auto-scroll for good.
    LaunchedEffect(appended, follow) {
        if (follow && lines.isNotEmpty()) {
            scrollState.scrollToItem(lines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.console_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            when (connectionState) {
                Pm3ConnectionState.CONNECTED ->
                    OutlinedButton(onClick = viewModel::disconnect, enabled = !busy) {
                        Text(stringResource(R.string.action_disconnect))
                    }
                Pm3ConnectionState.CONNECTING ->
                    OutlinedButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.status_connecting))
                    }
                Pm3ConnectionState.DISCONNECTED ->
                    Button(
                        onClick = viewModel::connect,
                        enabled = !busy && device != null,
                    ) {
                        Text(stringResource(R.string.action_connect))
                    }
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = follow,
                onClick = { viewModel.setFollowConsole(!follow) },
                label = { Text(stringResource(R.string.console_follow)) },
            )
            TextButton(
                // Copy the visible text, not the colour escapes.
                onClick = { copyText(context, plainConsoleText(lines)) },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.console_copy_all))
            }
            TextButton(
                onClick = {
                    shareText(
                        context,
                        plainConsoleText(lines),
                        consoleTitle,
                    )
                },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.console_share_log))
            }
            TextButton(
                onClick = { showClearDialog = true },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Clear, contentDescription = null)
                Text(stringResource(R.string.action_clear))
            }
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.padding(8.dp)) {
                if (lines.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Terminal,
                        title = stringResource(R.string.console_title),
                        body = stringResource(R.string.console_empty),
                    )
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = scrollState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(lines) { _, line ->
                                val text = remember(line, palette, darkTheme, colored) {
                                    if (colored) {
                                        consoleText(line, palette, darkTheme)
                                    } else {
                                        AnnotatedString(ConsoleAnsi.stripAnsi(line))
                                    }
                                }
                                Text(
                                    text = text,
                                    fontSize = settings.consoleFontSizeSp.sp,
                                    lineHeight = (settings.consoleFontSizeSp * 1.35).sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (colored) {
                                        Color.Unspecified
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    // Typing stays possible while the PM3 is busy so the IME
                    // Send action can also request a stop.
                    enabled = connectionState == Pm3ConnectionState.CONNECTED,
                    placeholder = { Text(stringResource(R.string.console_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = { historyOpen = true },
                            enabled = history.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = stringResource(R.string.console_history),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            when {
                                busy -> viewModel.stopCurrent()
                                viewModel.runCommand(commandInput) -> commandInput = ""
                            }
                        },
                    ),
                )
                DropdownMenu(
                    expanded = historyOpen,
                    onDismissRequest = { historyOpen = false },
                ) {
                    if (history.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_history_empty)) },
                            onClick = { historyOpen = false },
                        )
                    } else {
                        history.asReversed().forEach { command ->
                            DropdownMenuItem(
                                text = { Text(command, fontFamily = FontFamily.Monospace) },
                                onClick = {
                                    commandInput = command
                                    historyOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.console_clear_history)) },
                            onClick = {
                                viewModel.clearHistory()
                                historyOpen = false
                            },
                        )
                    }
                }
            }
            if (busy) {
                Button(
                    onClick = { viewModel.stopCurrent() },
                    enabled = connectionState == Pm3ConnectionState.CONNECTED,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.console_stop))
                }
            } else {
                Button(
                    onClick = {
                        if (viewModel.runCommand(commandInput)) {
                            commandInput = ""
                        }
                    },
                    enabled = connectionState == Pm3ConnectionState.CONNECTED &&
                        commandInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.console_send))
                }
            }
        }

        if (showClearDialog) {
            ConfirmDialog(
                title = stringResource(R.string.console_clear_confirm_title),
                body = stringResource(R.string.console_clear_confirm_body),
                confirmLabel = stringResource(R.string.action_clear),
                onConfirm = viewModel::clearConsole,
                onDismiss = { showClearDialog = false },
                destructive = true,
            )
        }
    }
}

/** Plain text for copy and share: the colour escapes are not part of the log. */
private fun plainConsoleText(lines: List<String>): String =
    lines.joinToString("\n") { ConsoleAnsi.stripAnsi(it) }

/**
 * One console line as a styled string. An explicit ANSI colour (from a script)
 * wins; every other run of text uses the colour of the line's classification.
 */
private fun consoleText(
    line: String,
    palette: TerminalPalette,
    dark: Boolean,
): AnnotatedString = buildAnnotatedString {
    for (segment in ConsoleAnsi.parse(line)) {
        val color = segment.color?.let { ansiColor(it, dark) } ?: palette.colorFor(segment.style)
        val bold = segment.bold || segment.style == ConsoleStyle.PROMPT
        withStyle(
            SpanStyle(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
        ) {
            append(segment.text)
        }
    }
}
