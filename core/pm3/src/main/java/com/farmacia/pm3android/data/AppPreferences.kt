// User-visible app settings. Android SharedPreferences is enough here: there
// are only a handful of values and no complex querying.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val consoleFontSizeSp: Int = 12,
    val confirmDelete: Boolean = true,
    val keepScreenOn: Boolean = false,
    val followConsole: Boolean = true,
    val autoCheckUpdates: Boolean = true,
)

class AppPreferences(context: Context) {

    private companion object {
        const val FILE = "pm3_android_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_FONT_SIZE = "console_font_size"
        const val KEY_CONFIRM_DELETE = "confirm_delete"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_FOLLOW_CONSOLE = "follow_console"
        const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        const val KEY_LAST_NOTIFIED_UPDATE = "last_notified_update"

        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 22
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    fun setThemeMode(mode: ThemeMode) = update { copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) = update { copy(dynamicColor = enabled) }
    fun setConsoleFontSize(sizeSp: Int) = update {
        copy(consoleFontSizeSp = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    }
    fun setConfirmDelete(enabled: Boolean) = update { copy(confirmDelete = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = update { copy(keepScreenOn = enabled) }
    fun setFollowConsole(enabled: Boolean) = update { copy(followConsole = enabled) }
    fun setAutoCheckUpdates(enabled: Boolean) = update { copy(autoCheckUpdates = enabled) }

    /** Version already shown in the startup update dialog, so it is not repeated. */
    fun lastNotifiedUpdateVersion(): String? =
        prefs.getString(KEY_LAST_NOTIFIED_UPDATE, null)

    fun markUpdateNotified(version: String) {
        prefs.edit { putString(KEY_LAST_NOTIFIED_UPDATE, version) }
    }

    fun backupJson(): String {
        val value = _settings.value
        return JSONObject()
            .put("format", 1)
            .put("themeMode", value.themeMode.name)
            .put("dynamicColor", value.dynamicColor)
            .put("consoleFontSizeSp", value.consoleFontSizeSp)
            .put("confirmDelete", value.confirmDelete)
            .put("keepScreenOn", value.keepScreenOn)
            .put("followConsole", value.followConsole)
            .put("autoCheckUpdates", value.autoCheckUpdates)
            .toString(2)
    }

    /** Restores the known fields; unknown/missing fields fall back to defaults. */
    fun restoreJson(text: String) {
        val json = JSONObject(text)
        val restored = AppSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(json.optString("themeMode", ThemeMode.SYSTEM.name))
            }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = json.optBoolean("dynamicColor", true),
            consoleFontSizeSp = json.optInt("consoleFontSizeSp", 12)
                .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
            confirmDelete = json.optBoolean("confirmDelete", true),
            keepScreenOn = json.optBoolean("keepScreenOn", false),
            followConsole = json.optBoolean("followConsole", true),
            autoCheckUpdates = json.optBoolean("autoCheckUpdates", true),
        )
        _settings.value = restored
        persist(restored)
    }

    private fun load(): AppSettings {
        val mode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        return AppSettings(
            themeMode = mode,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            consoleFontSizeSp = prefs.getInt(KEY_FONT_SIZE, 12)
                .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
            confirmDelete = prefs.getBoolean(KEY_CONFIRM_DELETE, true),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            followConsole = prefs.getBoolean(KEY_FOLLOW_CONSOLE, true),
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
        )
    }

    private inline fun update(transform: AppSettings.() -> AppSettings) {
        val updated = _settings.value.transform()
        _settings.value = updated
        persist(updated)
    }

    private fun persist(value: AppSettings) {
        prefs.edit {
            putString(KEY_THEME, value.themeMode.name)
            putBoolean(KEY_DYNAMIC, value.dynamicColor)
            putInt(KEY_FONT_SIZE, value.consoleFontSizeSp)
            putBoolean(KEY_CONFIRM_DELETE, value.confirmDelete)
            putBoolean(KEY_KEEP_SCREEN_ON, value.keepScreenOn)
            putBoolean(KEY_FOLLOW_CONSOLE, value.followConsole)
            putBoolean(KEY_AUTO_CHECK_UPDATES, value.autoCheckUpdates)
        }
    }
}
