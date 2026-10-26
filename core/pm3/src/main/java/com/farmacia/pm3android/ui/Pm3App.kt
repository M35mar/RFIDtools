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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.StatusPill
import com.farmacia.pm3android.ui.console.ConsoleScreen
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.device.DeviceScreen
import com.farmacia.pm3android.ui.files.FilesScreen
import com.farmacia.pm3android.ui.settings.SettingsScreen
import com.farmacia.pm3android.ui.theme.Pm3Theme
import com.farmacia.pm3android.ui.tools.ToolsScreen

private data class Tab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val content: @Composable (Pm3ViewModel) -> Unit,
)

private val TABS = listOf(
    Tab(R.string.nav_device, Icons.Filled.Usb) { DeviceScreen(it) },
    Tab(R.string.nav_console, Icons.AutoMirrored.Filled.List) { ConsoleScreen(viewModel = it) },
    Tab(R.string.nav_tools, Icons.Filled.PlayArrow) { ToolsScreen(it) },
    Tab(R.string.nav_files, Icons.Filled.Share) { FilesScreen(it) },
    Tab(R.string.nav_settings, Icons.Filled.Info) { SettingsScreen(it) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pm3App(
    requestedTab: Int? = null,
    onTabRequestHandled: () -> Unit = {},
) {
    val viewModel: Pm3ViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val device by viewModel.device.collectAsState()
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

    Pm3Theme(settings) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(TABS[selected].labelRes)) },
                        actions = {
                            ConnectionPill(connectionState, device != null)
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    viewModel.refreshDevice()
                                    viewModel.refreshFiles()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.action_refresh),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        TABS.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.labelRes),
                                    )
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
                    TABS[selected].content(viewModel)
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
