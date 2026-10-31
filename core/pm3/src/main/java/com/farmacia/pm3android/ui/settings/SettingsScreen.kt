// Info and settings screen: appearance, console preferences, SAF backup/restore,
// client-resource reset and build/attribution information.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.settings

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.ui.components.ConfirmDialog
import com.farmacia.pm3android.ui.components.InfoRow
import com.farmacia.pm3android.ui.components.SectionCard
import com.farmacia.pm3android.ui.components.openUrl
import com.farmacia.pm3android.ui.console.Pm3ViewModel
import com.farmacia.pm3android.ui.update.UpdateSection
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: Pm3ViewModel) {
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val fileOpBusy by viewModel.fileOpBusy.collectAsState()
    val resourcesInstalled by viewModel.resourcesInstalled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    var showLicense by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::backupSettings) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::restoreSettings) }

    @SuppressLint("LocalContextResourcesRead")
    val licenseText = remember {
        context.resources.openRawResource(R.raw.license)
            .bufferedReader()
            .use { it.readText() }
    }

    @SuppressLint("LocalContextResourcesRead")
    val thirdPartyNotices = remember {
        buildString {
            append(
                context.resources.openRawResource(R.raw.chaquopy_license)
                    .bufferedReader()
                    .use { it.readText() },
            )
            append("\n\n")
            append(
                context.resources.openRawResource(R.raw.python_license)
                    .bufferedReader()
                    .use { it.readText() },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fileOpBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SectionCard(title = stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium)
            ThemeMode.values().forEach { mode ->
                val label = when (mode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.themeMode == mode,
                        onClick = null,
                    )
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            SettingSwitch(
                title = stringResource(R.string.settings_dynamic_color),
                checked = settings.dynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }

        SectionCard(title = stringResource(R.string.settings_console)) {
            Text(
                text = stringResource(R.string.settings_font_size, settings.consoleFontSizeSp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.consoleFontSizeSp.toFloat(),
                onValueChange = { viewModel.setConsoleFontSize(it.roundToInt()) },
                valueRange = 10f..22f,
                steps = 11,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_follow_console),
                checked = settings.followConsole,
                onCheckedChange = viewModel::setFollowConsole,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
            )
            SettingSwitch(
                title = stringResource(R.string.settings_confirm_delete),
                checked = settings.confirmDelete,
                onCheckedChange = viewModel::setConfirmDelete,
            )
        }

        SectionCard(title = stringResource(R.string.settings_data)) {
            InfoRow(
                stringResource(R.string.settings_resources),
                stringResource(
                    if (resourcesInstalled) {
                        R.string.settings_resources_installed
                    } else {
                        R.string.settings_resources_pending
                    },
                ),
            )
            OutlinedButton(
                onClick = { backupLauncher.launch("rfidtools-settings.json") },
                enabled = !fileOpBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Text(stringResource(R.string.settings_backup))
            }
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
                enabled = !fileOpBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null)
                Text(stringResource(R.string.settings_restore))
            }
            OutlinedButton(
                onClick = { showResetConfirm = true },
                enabled = !fileOpBusy && connectionState == Pm3ConnectionState.DISCONNECTED,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Text(stringResource(R.string.settings_reset_resources))
            }
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                text = stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodySmall,
            )
            InfoRow(
                stringResource(R.string.settings_version),
                stringResource(
                    R.string.settings_version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
            InfoRow(
                stringResource(R.string.settings_build),
                "${BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() }} / M35MAR RFIDtools",
            )
            InfoRow(
                stringResource(R.string.settings_client_commit),
                BuildConfig.PM3_CLIENT_COMMIT,
            )
            InfoRow(
                stringResource(R.string.settings_app_commit),
                BuildConfig.APP_GIT_COMMIT,
            )
            InfoRow(
                stringResource(R.string.settings_target),
                stringResource(R.string.settings_target_value),
            )
        }

        SectionCard(title = stringResource(R.string.settings_links)) {
            OutlinedButton(
                onClick = { openUrl(context, "https://github.com/M35mar/RFIDtools") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.settings_github))
            }
            OutlinedButton(
                onClick = { openUrl(context, "https://github.com/M35mar/RFIDtools/releases") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(stringResource(R.string.settings_releases))
            }
            OutlinedButton(
                onClick = { showLicense = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Text(stringResource(R.string.settings_license))
            }
            OutlinedButton(
                onClick = { openUrl(context, "https://www.paypal.com/biz/profile/acquistaliSRL") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_paypal),
                    contentDescription = null,
                )
                Text(stringResource(R.string.settings_donate))
            }
            UpdateSection(
                state = updateState,
                autoCheck = settings.autoCheckUpdates,
                onAutoCheckChange = viewModel::setAutoCheckUpdates,
                onCheck = { viewModel.checkForUpdates(manual = true) },
                onDownload = viewModel::downloadUpdate,
                onInstall = viewModel::installUpdate,
            )
        }

        SectionCard(title = stringResource(R.string.settings_home_paths)) {
            Text(
                text = stringResource(R.string.settings_client_prefs_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "HOME = filesDir\n" +
                    "\$HOME/.proxmark3/dictionaries\n" +
                    "\$HOME/.proxmark3/lualibs\n" +
                    "\$HOME/.proxmark3/luascripts\n" +
                    "\$HOME/.proxmark3/cmdscripts\n" +
                    "\$HOME/.proxmark3/pyscripts\n" +
                    "\$HOME/.proxmark3/resources\n" +
                    "\$HOME/.proxmark3/logs\n" +
                    "\$HOME/.proxmark3/traces\n" +
                    "\$HOME/.proxmark3/prefs.json",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.settings_license_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_license_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.settings_third_party_notices),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(thirdPartyNotices, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_resources_title),
            body = stringResource(R.string.settings_reset_resources_body),
            confirmLabel = stringResource(R.string.settings_reset_resources),
            onConfirm = viewModel::resetResources,
            onDismiss = { showResetConfirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}
