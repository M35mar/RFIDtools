// Main scaffold: top status bar, bottom navigation over the five screens and
// a snackbar host for one-shot messages. The ViewModel is activity-scoped so
// console commands run from any tab appear in the same log.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.farmacia.pm3android.R
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.data.ConnectionMode
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.console.ConsoleScreen
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.device.DeviceScreen
import com.farmacia.pm3android.ui.files.DumpDiffScreen
import com.farmacia.pm3android.ui.files.DumpDiffViewModel
import com.farmacia.pm3android.ui.files.DumpEditorScreen
import com.farmacia.pm3android.ui.files.DumpEditorViewModel
import com.farmacia.pm3android.ui.files.FilesScreen
import com.farmacia.pm3android.ui.nfc.ApduConsoleScreen
import com.farmacia.pm3android.ui.nfc.HceEmulatorScreen
import com.farmacia.pm3android.ui.nfc.HceViewModel
import com.farmacia.pm3android.ui.nfc.MfcValueScreen
import com.farmacia.pm3android.ui.nfc.MfuCounterScreen
import com.farmacia.pm3android.ui.nfc.MagicUidScreen
import com.farmacia.pm3android.ui.nfc.NdefWriterScreen
import com.farmacia.pm3android.ui.nfc.NfcRestoreScreen
import com.farmacia.pm3android.ui.nfc.NfcScreen
import com.farmacia.pm3android.ui.nfc.TagInspectScreen
import com.farmacia.pm3android.ui.nfc.Type2PasswordScreen
import com.farmacia.pm3android.ui.nfc.Type2PasswordViewModel
import com.farmacia.pm3android.ui.settings.SettingsScreen
import com.farmacia.pm3android.ui.theme.Pm3Theme
import com.farmacia.pm3android.ui.theme.pm3Accents
import com.farmacia.pm3android.ui.theme.pm3Chrome
import com.farmacia.pm3android.ui.update.UpdatePromptDialog
import com.farmacia.pm3android.nfc.ApduConsoleViewModel
import com.farmacia.pm3android.nfc.MfcValueViewModel
import com.farmacia.pm3android.nfc.MfuCounterViewModel
import com.farmacia.pm3android.nfc.MagicUidViewModel
import com.farmacia.pm3android.nfc.NdefWriterViewModel
import com.farmacia.pm3android.nfc.NfcRestoreViewModel
import com.farmacia.pm3android.nfc.TagInspectViewModel
import com.farmacia.pm3android.update.UpdateState

private data class Tab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    Tab(R.string.nav_device, Icons.Filled.Usb),
    Tab(R.string.nav_console, Icons.Filled.Terminal),
    Tab(R.string.nav_nfc, Icons.Filled.Nfc),
    Tab(R.string.nav_files, Icons.Filled.Folder),
    Tab(R.string.nav_settings, Icons.Filled.Info),
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
    val counterViewModel: MfuCounterViewModel = viewModel()
    val valueViewModel: MfcValueViewModel = viewModel()
    val uidViewModel: MagicUidViewModel = viewModel()
    val ndefWriterViewModel: NdefWriterViewModel = viewModel()
    val diffViewModel: DumpDiffViewModel = viewModel()
    val type2PwdViewModel: Type2PasswordViewModel = viewModel()
    val hceViewModel: HceViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val files by viewModel.files.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val device by viewModel.device.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val updatePrompt by viewModel.updatePrompt.collectAsState()
    val restoreState by restoreViewModel.state.collectAsState()
    val editorState by editorViewModel.state.collectAsState()
    val inspectState by inspectViewModel.state.collectAsState()
    val apduState by apduViewModel.state.collectAsState()
    val counterState by counterViewModel.state.collectAsState()
    val valueState by valueViewModel.state.collectAsState()
    val uidState by uidViewModel.state.collectAsState()
    val ndefWriterState by ndefWriterViewModel.state.collectAsState()
    val diffState by diffViewModel.state.collectAsState()
    val type2PwdState by type2PwdViewModel.state.collectAsState()
    val hceState by hceViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Long commands run in a foreground service, so Android 13+ needs the
    // notification permission. Ask only when the first long operation starts:
    // a user who only browses the NFC tab never sees the prompt.
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(busy) {
        if (!busy || notificationAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            // TopAppBar and Bottom NavigationBar use dark high-contrast chrome
            // in both themes so system icons stay crisp and readable.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
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
                        val accents = MaterialTheme.pm3Accents
                        val chrome = MaterialTheme.pm3Chrome
                        val tabAccent = when (selected) {
                            0 -> accents.usb
                            1 -> accents.info
                            2 -> accents.hf
                            3 -> accents.lf
                            else -> accents.script
                        }
                        Column {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = chrome.topBarBg,
                                    titleContentColor = Color(0xFFF8FAFC),
                                    actionIconContentColor = Color(0xFFF8FAFC),
                                ),
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = tabAccent.copy(alpha = 0.22f),
                                        ) {
                                            Icon(
                                                imageVector = TABS[selected].icon,
                                                contentDescription = null,
                                                tint = tabAccent,
                                                modifier = Modifier
                                                    .padding(5.dp)
                                                    .size(20.dp),
                                            )
                                        }
                                        Text(
                                            text = stringResource(TABS[selected].labelRes),
                                            color = Color(0xFFF8FAFC),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                actions = {
                                    if (selected == 0 || selected == 1 ||
                                        connectionState != Pm3ConnectionState.DISCONNECTED
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            ConnectionPill(
                                                state = connectionState,
                                                deviceAttached = device != null,
                                                mode = settings.connectionMode,
                                                bluetoothConfigured = settings.bluetoothAddress != null,
                                            )
                                            if (selected == 1) {
                                                val canConnect = !busy &&
                                                    connectionState != Pm3ConnectionState.CONNECTED &&
                                                    if (settings.connectionMode == ConnectionMode.USB) {
                                                        device != null
                                                    } else {
                                                        settings.bluetoothAddress != null
                                                    }
                                                CompactConnectPill(
                                                    state = connectionState,
                                                    busy = busy,
                                                    canConnect = canConnect,
                                                    mode = settings.connectionMode,
                                                    onConnect = viewModel::connect,
                                                    onDisconnect = viewModel::disconnect,
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                },
                            )
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = chrome.chromeDivider,
                            )
                        }
                    },
                    bottomBar = {
                        val accents = MaterialTheme.pm3Accents
                        val chrome = MaterialTheme.pm3Chrome
                        Column {
                            HorizontalDivider(
                                thickness = 1.5.dp,
                                color = chrome.chromeDivider,
                            )
                            NavigationBar(
                                containerColor = chrome.bottomBarBg,
                                tonalElevation = 0.dp,
                            ) {
                                TABS.forEachIndexed { index, tab ->
                                    val itemAccent = when (index) {
                                        0 -> accents.usb
                                        1 -> accents.info
                                        2 -> accents.hf
                                        3 -> accents.lf
                                        else -> accents.script
                                    }
                                    val showUpdateBadge = index == TABS.lastIndex &&
                                        (updateState is UpdateState.Available ||
                                            updateState is UpdateState.Downloading ||
                                            updateState is UpdateState.Ready)
                                    val updateBadgeDescription =
                                        stringResource(R.string.update_badge_desc)
                                    NavigationBarItem(
                                        selected = selected == index,
                                        onClick = { selected = index },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = itemAccent,
                                            selectedTextColor = itemAccent,
                                            unselectedIconColor = chrome.bottomBarUnselected,
                                            unselectedTextColor = chrome.bottomBarUnselected,
                                            indicatorColor = itemAccent.copy(alpha = 0.22f),
                                        ),
                                        icon = {
                                            if (showUpdateBadge) {
                                                BadgedBox(
                                                    badge = { Badge(containerColor = accents.danger) },
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
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    // All tabs share the same ViewModel instance and state.
                    // Consume the Scaffold insets: a tab that adds imePadding()
                    // must only pad for the keyboard area the bottom bar does
                    // not already reserve.
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    ) {
                        when (selected) {
                            0 -> DeviceScreen(viewModel)
                            1 -> ConsoleScreen(viewModel = viewModel)
                            2 -> NfcScreen(
                                viewModel = viewModel,
                                restoreViewModel = restoreViewModel,
                                onIdentify = inspectViewModel::start,
                                onOpenApduConsole = apduViewModel::start,
                                onOpenCounterTool = counterViewModel::start,
                                onOpenValueTool = valueViewModel::start,
                                onOpenUidTool = uidViewModel::start,
                                onOpenNdefWriter = ndefWriterViewModel::start,
                                onOpenType2Pwd = { type2PwdViewModel.start() },
                                onOpenHce = hceViewModel::start,
                                onOpenDiff = {
                                    viewModel.refreshFiles()
                                    diffViewModel.open()
                                },
                                onFormatClassic = restoreViewModel::startFormatClassic,
                            )
                            3 -> FilesScreen(
                                viewModel,
                                onRestore = restoreViewModel::open,
                                onEditDump = editorViewModel::open,
                                onOpenDiff = {
                                    viewModel.refreshFiles()
                                    diffViewModel.open()
                                },
                            )
                            else -> SettingsScreen(viewModel)
                        }
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
                        onOpenCounterTool = counterViewModel::start,
                        onOpenValueTool = valueViewModel::start,
                        onOpenType2Pwd = { uid -> type2PwdViewModel.start(uid) },
                        onCloneNdef = { text, isUri ->
                            inspectViewModel.close()
                            ndefWriterViewModel.startWithTextOrUri(text, isUri)
                        },
                        onEmulateNdef = { text, isUri ->
                            inspectViewModel.close()
                            hceViewModel.startWithClonedNdef(text, isUri)
                        },
                        onHandOffToPm3 = { cmd ->
                            inspectViewModel.close()
                            selected = 1
                            viewModel.handOffCommandFromNfc(cmd)
                        },
                    )
                }

                if (diffState.visible) {
                    DumpDiffScreen(
                        state = diffState,
                        files = files,
                        viewModel = diffViewModel,
                        onClose = diffViewModel::close,
                    )
                }

                if (type2PwdState.visible) {
                    Type2PasswordScreen(
                        state = type2PwdState,
                        viewModel = type2PwdViewModel,
                        onClose = type2PwdViewModel::close,
                    )
                }

                if (hceState.visible) {
                    HceEmulatorScreen(
                        state = hceState,
                        viewModel = hceViewModel,
                        onClose = hceViewModel::close,
                    )
                }

                // The two write tools sit above the inspector so the user can
                // open them from a tag that was just read and go back to it
                // afterwards.
                if (counterState.visible) {
                    MfuCounterScreen(
                        state = counterState,
                        viewModel = counterViewModel,
                        onClose = counterViewModel::close,
                    )
                }

                if (valueState.visible) {
                    MfcValueScreen(
                        state = valueState,
                        viewModel = valueViewModel,
                        onClose = valueViewModel::close,
                    )
                }

                if (uidState.visible) {
                    MagicUidScreen(
                        state = uidState,
                        viewModel = uidViewModel,
                        onClose = uidViewModel::close,
                    )
                }

                if (ndefWriterState.visible) {
                    NdefWriterScreen(
                        state = ndefWriterState,
                        viewModel = ndefWriterViewModel,
                        onClose = ndefWriterViewModel::close,
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
    mode: ConnectionMode,
    bluetoothConfigured: Boolean,
) {
    val accents = MaterialTheme.pm3Accents
    val (text, color) = when {
        state == Pm3ConnectionState.CONNECTED ->
            stringResource(R.string.status_connected) to accents.success
        state == Pm3ConnectionState.CONNECTING ->
            stringResource(R.string.status_connecting) to accents.lf
        mode == ConnectionMode.BLUETOOTH && bluetoothConfigured ->
            stringResource(R.string.status_bluetooth_ready) to accents.bluetooth
        mode == ConnectionMode.BLUETOOTH ->
            stringResource(R.string.status_bluetooth_not_configured) to MaterialTheme.colorScheme.outline
        deviceAttached ->
            stringResource(R.string.status_usb_attached) to accents.usb
        else ->
            stringResource(R.string.status_usb_not_attached) to MaterialTheme.colorScheme.outline
    }
    StatusPill(text = text, color = color)
}

@Composable
private fun CompactConnectPill(
    state: Pm3ConnectionState,
    busy: Boolean,
    canConnect: Boolean,
    mode: ConnectionMode,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val accents = MaterialTheme.pm3Accents
    val connectAccent = if (mode == ConnectionMode.BLUETOOTH) accents.bluetooth else accents.usb
    val (label, accent, enabled, onClick) = when (state) {
        Pm3ConnectionState.CONNECTED -> Quad(
            stringResource(R.string.action_disconnect),
            if (busy) accents.lf else accents.danger,
            !busy,
            onDisconnect,
        )
        Pm3ConnectionState.CONNECTING -> Quad(
            "…",
            accents.lf,
            false,
            {},
        )
        Pm3ConnectionState.DISCONNECTED -> Quad(
            stringResource(R.string.action_connect),
            if (canConnect) connectAccent else MaterialTheme.colorScheme.outline,
            canConnect,
            onConnect,
        )
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (state == Pm3ConnectionState.DISCONNECTED && canConnect) {
            accent.copy(alpha = 0.30f)
        } else {
            accent.copy(alpha = 0.20f)
        },
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.70f else 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (busy || state == Pm3ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.8.dp,
                    color = accents.lf,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled || busy || state == Pm3ConnectionState.CONNECTING) {
                    Color(0xFFF8FAFC)
                } else {
                    Color(0xFF94A3B8)
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
