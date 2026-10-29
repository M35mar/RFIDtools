// Main scaffold: top status bar, bottom navigation over the five screens and
// a snackbar host for one-shot messages. The ViewModel is activity-scoped so
// console commands run from any tab appear in the same log.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.farmacia.pm3android.R
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.console.ConsoleScreen
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.device.DeviceScreen
import com.farmacia.pm3android.ui.files.DumpEditorScreen
import com.farmacia.pm3android.ui.files.DumpEditorViewModel
import com.farmacia.pm3android.ui.files.FilesScreen
import com.farmacia.pm3android.ui.nfc.ApduConsoleScreen
import com.farmacia.pm3android.ui.nfc.NfcRestoreScreen
import com.farmacia.pm3android.ui.nfc.NfcScreen
import com.farmacia.pm3android.ui.nfc.TagInspectScreen
import com.farmacia.pm3android.ui.settings.SettingsScreen
import com.farmacia.pm3android.ui.theme.Pm3Theme
import com.farmacia.pm3android.ui.update.UpdatePromptDialog
import com.farmacia.pm3android.nfc.ApduConsoleViewModel
import com.farmacia.pm3android.nfc.NfcRestoreViewModel
import com.farmacia.pm3android.nfc.TagInspectViewModel
import com.farmacia.pm3android.update.UpdateState

private data class Tab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val content: @Composable (
        Pm3ViewModel,
        NfcRestoreViewModel,
        DumpEditorViewModel,
        TagInspectViewModel,
        ApduConsoleViewModel,
    ) -> Unit,
)

private val TABS = listOf(
    Tab(R.string.nav_device, Icons.Filled.Usb) { viewModel, _, _, _, _ -> DeviceScreen(viewModel) },
    Tab(R.string.nav_console, Icons.AutoMirrored.Filled.List) { viewModel, _, _, _, _ ->
        ConsoleScreen(viewModel = viewModel)
    },
    Tab(R.string.nav_nfc, Icons.Filled.Nfc) { viewModel, restore, _, inspect, apdu ->
        NfcScreen(
            viewModel = viewModel,
            restoreViewModel = restore,
            onIdentify = inspect::start,
            onOpenApduConsole = apdu::start,
        )
    },
    Tab(R.string.nav_files, Icons.Filled.Share) { viewModel, restore, editor, _, _ ->
        FilesScreen(
            viewModel,
            onRestore = restore::open,
            onEditDump = editor::open,
        )
    },
    Tab(R.string.nav_settings, Icons.Filled.Info) { viewModel, _, _, _, _ ->
        SettingsScreen(viewModel)
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pm3App(
    requestedTab: Int? = null,
    onTabRequestHandled: () -> Unit = {},
) {
    val viewModel: Pm3ViewModel = viewModel()
    val restoreViewModel: NfcRestoreViewModel = viewModel()
    val editorViewModel: DumpEditorViewModel = viewModel()
    val inspectViewModel: TagInspectViewModel = viewModel()
    val apduViewModel: ApduConsoleViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val updatePrompt by viewModel.updatePrompt.collectAsState()
    val restoreState by restoreViewModel.state.collectAsState()
    val editorState by editorViewModel.state.collectAsState()
    val inspectState by inspectViewModel.state.collectAsState()
    val apduState by apduViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // requestedTab is a one-shot shortcut/navigation request, not the initial
    // state: rotation restores `selected` and must not jump back to a tab.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            if (requestedTab in TABS.indices) {
                selected = requestedTab
            }
            onTabRequestHandled()
        }
    }

    val view = LocalView.current
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val activity = view.context as? Activity
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // A dump created with the phone NFC reader lands in the app folder, so the
    // Files tab must be refreshed to show it without a manual pull.
    LaunchedEffect(restoreState.savedName) {
        if (restoreState.savedName != null) viewModel.refreshFiles()
    }

    // The editor writes to an existing file: refresh the Data tab so size and
    // modification date stay in sync with what was saved.
    LaunchedEffect(editorState.savedToken) {
        if (editorState.savedToken > 0) viewModel.refreshFiles()
    }

    Pm3Theme(settings) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(TABS[selected].labelRes)) },
                            actions = {
                                if (selected == 0 || selected == 1) {
                                    ConnectionPill(connectionState, device != null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { index, tab ->
                                val showUpdateBadge = index == TABS.lastIndex &&
                                    (updateState is UpdateState.Available ||
                                        updateState is UpdateState.Downloading ||
                                        updateState is UpdateState.Ready)
                                val updateBadgeDescription =
                                    stringResource(R.string.update_badge_desc)
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { selected = index },
                                    icon = {
                                        if (showUpdateBadge) {
                                            BadgedBox(
                                                badge = { Badge() },
                                                modifier = Modifier.semantics {
                                                    contentDescription = updateBadgeDescription
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = stringResource(tab.labelRes),
                                            )
                                        }
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    // All tabs share the same ViewModel instance and state.
                    Box(modifier = Modifier.padding(padding)) {
                        TABS[selected].content(
                            viewModel,
                            restoreViewModel,
                            editorViewModel,
                            inspectViewModel,
                            apduViewModel,
                        )
                    }
                }

                // The dump editor covers the bottom navigation, like the NFC
                // overlay: the Data tab is not reachable while it is open.
                updatePrompt?.let { release ->
                    UpdatePromptDialog(
                        release = release,
                        currentVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                        onInstall = {
                            viewModel.dismissUpdatePrompt()
                            viewModel.downloadUpdate()
                        },
                        onDismiss = viewModel::dismissUpdatePrompt,
                    )
                }

                if (editorState.visible) {
                    DumpEditorScreen(
                        state = editorState,
                        viewModel = editorViewModel,
                    )
                }

                // The NFC restore overlay lives above the Scaffold so it
                // covers the bottom navigation and keeps reader mode active
                // while a tag is being written.
                if (restoreState.visible) {
                    NfcRestoreScreen(
                        state = restoreState,
                        viewModel = restoreViewModel,
                        onClose = restoreViewModel::close,
                    )
                }

                // The inspector is a second NFC overlay, independent from the
                // restore flow: it only reads and never writes the tag.
                if (inspectState.visible) {
                    TagInspectScreen(
                        state = inspectState,
                        viewModel = inspectViewModel,
                        onClose = inspectViewModel::close,
                        onOpenDumpFlow = {
                            // The full read flow owns key files and passwords:
                            // hand the tag over instead of duplicating them here.
                            inspectViewModel.close()
                            restoreViewModel.startDump()
                        },
                    )
                }

                // The APDU console is the manual escape hatch: same overlay
                // level, its own ISO-DEP connection.
                if (apduState.visible) {
                    ApduConsoleScreen(
                        state = apduState,
                        viewModel = apduViewModel,
                        onClose = apduViewModel::close,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(
    state: Pm3ConnectionState,
    deviceAttached: Boolean,
) {
    val (text, color) = when {
        state == Pm3ConnectionState.CONNECTED ->
            stringResource(R.string.status_connected) to MaterialTheme.colorScheme.primary
        state == Pm3ConnectionState.CONNECTING ->
            stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.tertiary
        deviceAttached ->
            stringResource(R.string.status_usb_attached) to MaterialTheme.colorScheme.secondary
        else ->
            stringResource(R.string.status_usb_not_attached) to MaterialTheme.colorScheme.outline
    }
    StatusPill(text = text, color = color)
}
