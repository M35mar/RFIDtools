// Console screen: PC-fidelity dark terminal canvas, full ANSI SGR & token
// highlighting, wide-table horizontal scroll, live search filter, tag result
// summary, command history stepper and USB/Bluetooth connection controls.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmacia.pm3android.R
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.ConfirmDialog
import com.farmacia.pm3android.ui.components.EmptyState
import com.farmacia.pm3android.ui.components.copyText
import com.farmacia.pm3android.ui.components.shareText
import com.farmacia.pm3android.ui.device.Pm3ToolsScreen
import com.farmacia.pm3android.ui.theme.TerminalPalette
import com.farmacia.pm3android.ui.theme.ansiColor
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome
import com.farmacia.pm3android.ui.theme.terminalPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(viewModel: Pm3ViewModel) {
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    val chrome = MaterialTheme.pm3Chrome

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = subTab,
            containerColor = chrome.cardBg,
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(stringResource(R.string.console_title))
                    }
                },
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(stringResource(R.string.device_tab_tools))
                    }
                },
            )
        }
        when (subTab) {
            0 -> ConsoleCommandPane(viewModel)
            else -> Pm3ToolsScreen(viewModel, onOpenConsole = { subTab = 0 })
        }
    }
}

@Composable
private fun ConsoleCommandPane(viewModel: Pm3ViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val waitingInput by viewModel.waitingInput.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val history by viewModel.history.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val consoleTitle = stringResource(R.string.console_title)
    val accents = MaterialTheme.pm3Accents

    var commandInput by rememberSaveable { mutableStateOf("") }
    var historyCursor by rememberSaveable { mutableIntStateOf(-1) }
    var wordWrap by rememberSaveable { mutableStateOf(true) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val follow = settings.followConsole
    val scrollState = rememberLazyListState()
    val hScrollState = rememberScrollState()
    val appended by viewModel.consoleCount.collectAsState()
    // Desktop-style high-contrast dark terminal canvas so ANSI colours shine.
    val palette = terminalPalette(forceDarkCanvas = true)
    val colored = settings.consoleColors

    val activeQuery = if (searchOpen) searchQuery.trim() else ""
    val visibleLines = remember(lines, activeQuery) {
        if (activeQuery.isEmpty()) {
            lines
        } else {
            lines.filter { ConsoleAnsi.stripAnsi(it.raw).contains(activeQuery, ignoreCase = true) }
        }
    }

    // Keyed on the total number of lines ever appended: lines.size saturates
    // at the 5000-line window, which used to stop auto-scroll.
    LaunchedEffect(appended, follow, activeQuery) {
        if (follow && activeQuery.isEmpty() && visibleLines.isNotEmpty()) {
            scrollState.scrollToItem(visibleLines.lastIndex)
        }
    }

    val terminalBorderColor by animateColorAsState(
        targetValue = when {
            busy -> accents.lf
            connectionState == Pm3ConnectionState.CONNECTED -> accents.hf.copy(alpha = 0.7f)
            else -> palette.border
        },
        label = "terminalBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = follow,
                onClick = { viewModel.setFollowConsole(!follow) },
                label = { Text(stringResource(R.string.console_follow)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accents.hf.copy(alpha = 0.2f),
                    selectedLabelColor = accents.hf,
                ),
            )
            FilterChip(
                selected = !wordWrap,
                onClick = { wordWrap = !wordWrap },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (wordWrap) R.string.console_wrap else R.string.console_wide,
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accents.usb.copy(alpha = 0.2f),
                    selectedLabelColor = accents.usb,
                    selectedLeadingIconColor = accents.usb,
                ),
            )
            FilterChip(
                selected = searchOpen,
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text(stringResource(R.string.console_search)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accents.script.copy(alpha = 0.2f),
                    selectedLabelColor = accents.script,
                    selectedLeadingIconColor = accents.script,
                ),
            )
            TextButton(
                onClick = { copyText(context, plainConsoleText(lines)) },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
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
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.console_share_log))
            }
            TextButton(
                onClick = { showClearDialog = true },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = null,
                    tint = if (lines.isNotEmpty()) accents.danger else Color.Unspecified,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.action_clear),
                    color = if (lines.isNotEmpty()) accents.danger else Color.Unspecified,
                )
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.console_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    }
                },
            )
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = palette.background,
            border = BorderStroke(1.5.dp, terminalBorderColor),
        ) {
            Box(modifier = Modifier.padding(10.dp)) {
                if (visibleLines.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Terminal,
                        title = consoleTitle,
                        body = stringResource(R.string.console_empty),
                    )
                } else {
                    val listModifier = if (wordWrap) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScrollState)
                    }
                    SelectionContainer {
                        LazyColumn(
                            state = scrollState,
                            modifier = listModifier,
                        ) {
                            items(visibleLines, key = { it.id }) { line ->
                                val text = remember(line.id, palette, colored, activeQuery) {
                                    consoleText(
                                        line = line.raw,
                                        palette = palette,
                                        colored = colored,
                                        highlightQuery = activeQuery,
                                    )
                                }
                                Text(
                                    text = text,
                                    fontSize = settings.consoleFontSizeSp.sp,
                                    lineHeight = (settings.consoleFontSizeSp * 1.4).sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = wordWrap,
                                    color = palette.normal,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = waitingInput) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = accents.lf.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, accents.lf.copy(alpha = 0.65f)),
            ) {
                Text(
                    text = stringResource(R.string.console_waiting_input),
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.lf,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (history.isNotEmpty() && connectionState == Pm3ConnectionState.CONNECTED && !waitingInput) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(
                        onClick = {
                            val next = if (historyCursor < 0) {
                                history.lastIndex
                            } else {
                                (historyCursor - 1).coerceAtLeast(0)
                            }
                            historyCursor = next
                            commandInput = history[next]
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.console_history_prev),
                            tint = accents.usb,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (historyCursor in history.indices) {
                                if (historyCursor < history.lastIndex) {
                                    historyCursor += 1
                                    commandInput = history[historyCursor]
                                } else {
                                    historyCursor = -1
                                    commandInput = ""
                                }
                            }
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.console_history_next),
                            tint = accents.usb,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = {
                        commandInput = it
                        historyCursor = -1
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState == Pm3ConnectionState.CONNECTED,
                    placeholder = {
                        Text(
                            stringResource(
                                if (waitingInput) {
                                    R.string.console_input_reply_placeholder
                                } else {
                                    R.string.console_placeholder
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    trailingIcon = {
                        if (!waitingInput) {
                            IconButton(
                                onClick = { historyOpen = true },
                                enabled = history.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.History,
                                    contentDescription = stringResource(R.string.console_history),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            when {
                                waitingInput -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.sendScriptInput(commandInput)
                                    commandInput = ""
                                }
                                busy -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.stopCurrent()
                                }
                                viewModel.runCommand(commandInput) -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    commandInput = ""
                                    historyCursor = -1
                                }
                            }
                        },
                    ),
                )
                DropdownMenu(
                    expanded = historyOpen,
                    onDismissRequest = { historyOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp),
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
                                    historyCursor = -1
                                    historyOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.console_clear_history),
                                    color = accents.danger,
                                )
                            },
                            onClick = {
                                viewModel.clearHistory()
                                historyCursor = -1
                                historyOpen = false
                            },
                        )
                    }
                }
            }
            if (waitingInput) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendScriptInput(commandInput)
                        commandInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accents.lf,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.console_send),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.stopCurrent()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accents.danger,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.console_stop))
                }
            } else if (busy) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.stopCurrent()
                    },
                    enabled = connectionState == Pm3ConnectionState.CONNECTED,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accents.danger,
                        contentColor = Color.White,
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
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            commandInput = ""
                            historyCursor = -1
                        }
                    },
                    enabled = connectionState == Pm3ConnectionState.CONNECTED &&
                        commandInput.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.console_send),
                        modifier = Modifier.size(18.dp),
                    )
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
private fun plainConsoleText(lines: List<ConsoleLine>): String =
    lines.joinToString("\n") { ConsoleAnsi.stripAnsi(it.raw) }

/**
 * Renders one console line with full ANSI SGR support (16-colour, bright,
 * background, 256-colour, 24-bit RGB, bold/dim/italic/underline) and PC-style
 * semantic token highlighting for plain lines, plus optional search highlight.
 */
private fun consoleText(
    line: String,
    palette: TerminalPalette,
    colored: Boolean,
    highlightQuery: String,
): AnnotatedString {
    if (!colored) {
        val plain = ConsoleAnsi.stripAnsi(line)
        return applySearchHighlight(AnnotatedString(plain), highlightQuery)
    }
    val styled = buildAnnotatedString {
        for (segment in ConsoleAnsi.highlight(line)) {
            val fg = when {
                segment.rgbColor != null -> Color(0xFF000000L or (segment.rgbColor.toLong() and 0xFFFFFFL))
                segment.color != null -> ansiColor(segment.color, dark = true)
                else -> palette.colorFor(segment.style)
            }.let { base -> if (segment.dim) base.copy(alpha = 0.62f) else base }

            val bg = when {
                segment.rgbBackground != null -> Color(0xFF000000L or (segment.rgbBackground.toLong() and 0xFFFFFFL))
                segment.background != null -> ansiColor(segment.background, dark = true).copy(alpha = 0.35f)
                else -> Color.Unspecified
            }

            val bold = segment.bold || segment.style == ConsoleStyle.PROMPT
            withStyle(
                SpanStyle(
                    color = fg,
                    background = bg,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (segment.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (segment.underline) TextDecoration.Underline else TextDecoration.None,
                ),
            ) {
                append(segment.text)
            }
        }
    }
    return applySearchHighlight(styled, highlightQuery)
}

private fun applySearchHighlight(base: AnnotatedString, query: String): AnnotatedString {
    if (query.isEmpty()) return base
    val text = base.text
    var start = text.indexOf(query, ignoreCase = true)
    if (start < 0) return base
    return buildAnnotatedString {
        append(base)
        while (start >= 0) {
            val end = start + query.length
            addStyle(
                SpanStyle(
                    background = Color(0xFFFDE047),
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                ),
                start,
                end,
            )
            start = text.indexOf(query, startIndex = end, ignoreCase = true)
        }
    }
}
