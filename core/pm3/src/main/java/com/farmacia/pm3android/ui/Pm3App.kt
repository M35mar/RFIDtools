// Main scaffold: bottom navigation over the five screens. The ViewModel is
// activity-scoped so console commands run from any tab appear in the log.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.farmacia.pm3android.ui.console.ConsoleScreen
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.device.DeviceScreen
import com.farmacia.pm3android.ui.files.FilesScreen
import com.farmacia.pm3android.ui.settings.SettingsScreen
import com.farmacia.pm3android.ui.tools.ToolsScreen

private data class Tab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: @Composable (Pm3ViewModel) -> Unit,
)

private val TABS = listOf(
    Tab("Dispositivo", Icons.Filled.Home) { DeviceScreen(it) },
    Tab("Console", Icons.Filled.List) { ConsoleScreen(viewModel = it) },
    Tab("Strumenti", Icons.Filled.PlayArrow) { ToolsScreen(it) },
    Tab("Dati", Icons.Filled.Share) { FilesScreen(it) },
    Tab("Info", Icons.Filled.Info) { _ -> SettingsScreen() },
)

@Composable
fun Pm3App() {
    val viewModel: Pm3ViewModel = viewModel()
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // All tabs share the same ViewModel instance and state.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(padding),
        ) {
            TABS[selected].content(viewModel)
        }
    }
}
