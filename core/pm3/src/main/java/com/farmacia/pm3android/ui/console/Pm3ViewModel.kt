// ViewModel shared by all tabs. The native client accepts one command at a
// time, so PM3 work is serialised with a busy flag; file operations have a
// separate flag so they can run while the console is idle.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.AppPreferences
import com.farmacia.pm3android.data.AppSettings
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.data.Pm3FileFilter
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.Pm3Client
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.pm3.Pm3Resources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class Pm3DeviceInfo(
    val name: String,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
    val interfaceCount: Int,
    val endpointCount: Int,
    val hasPermission: Boolean,
    val descriptor: String,
)

class Pm3ViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val MAX_CONSOLE_LINES = 5000
        const val MAX_HISTORY = 100
    }

    private val client = Pm3Client(application)
    private val preferences = AppPreferences(application)

    val connectionState: StateFlow<Pm3ConnectionState> = client.state
    val connectLog: StateFlow<String> = client.connectLog
    val settings: StateFlow<AppSettings> = preferences.settings

    private val deviceFlow = MutableStateFlow<Pm3DeviceInfo?>(null)
    val device: StateFlow<Pm3DeviceInfo?> = deviceFlow

    private val filesFlow = MutableStateFlow<List<Pm3File>>(emptyList())
    val files: StateFlow<List<Pm3File>> = filesFlow

    private val filesLoadingFlow = MutableStateFlow(false)
    val filesLoading: StateFlow<Boolean> = filesLoadingFlow

    private val fileOpBusyFlow = MutableStateFlow(false)
    val fileOpBusy: StateFlow<Boolean> = fileOpBusyFlow

    private val resourcesInstalledFlow = MutableStateFlow(
        Pm3Resources.isInstalled(application),
    )
    val resourcesInstalled: StateFlow<Boolean> = resourcesInstalledFlow

    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow

    /** Console output, one entry per line block (command or response chunk). */
    private val linesFlow = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = linesFlow

    private val historyFlow = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = historyFlow

    private val messagesFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = messagesFlow

    var execJob: Job? = null
        private set

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val wasConnected = connectionState.value != Pm3ConnectionState.DISCONNECTED
                    refreshDevice()
                    if (wasConnected) {
                        notify(getApplication<Application>().getString(R.string.device_detached_message))
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { client.abortNow() }
                            busyFlow.value = false
                        }
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        refreshDevice()
        refreshFiles()
    }

    fun refreshDevice() {
        val found = client.foundDevice()
        deviceFlow.value = found?.let { dev ->
            val serial = runCatching { dev.serialNumber }.getOrNull()
            var endpointCount = 0
            for (index in 0 until dev.interfaceCount) {
                endpointCount += dev.getInterface(index).endpointCount
            }
            Pm3DeviceInfo(
                name = dev.deviceName.substringAfterLast('/'),
                manufacturer = dev.manufacturerName,
                product = dev.productName,
                serial = serial,
                vendorId = dev.vendorId,
                productId = dev.productId,
                deviceId = dev.deviceId,
                interfaceCount = dev.interfaceCount,
                endpointCount = endpointCount,
                hasPermission = client.hasPermission(dev),
                descriptor = client.describeDevice(dev),
            )
        }
    }

    fun refreshFiles() {
        if (filesLoadingFlow.value) return
        filesLoadingFlow.value = true
        val home = Pm3Resources.home(getApplication())
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    home.walkTopDown()
                        .onEnter { dir -> shouldEnter(dir, home) }
                        .filter { it.isFile }
                        .mapNotNull { file ->
                            val relative = file.relativeTo(home).path.replace('\\', '/')
                            if (!Pm3FileFilter.isVisible(relative)) {
                                null
                            } else {
                                Pm3File(
                                    file = file,
                                    relativePath = relative,
                                    size = file.length(),
                                    lastModified = file.lastModified(),
                                    category = Pm3FileFilter.categoryOf(relative),
                                )
                            }
                        }
                        .sortedWith(
                            compareByDescending<Pm3File> { it.lastModified }
                                .thenBy { it.relativePath },
                        )
                        .toList()
                }
                filesFlow.value = list
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(getApplication<Application>().getString(
                    R.string.files_refresh_failed,
                    errorText(t),
                ))
            } finally {
                filesLoadingFlow.value = false
            }
        }
    }

    fun connect() {
        if (busyFlow.value || connectionState.value == Pm3ConnectionState.CONNECTED) {
            return
        }
        submitWork {
            appendQuietly(client.connect())
            refreshDevice()
            refreshResourcesState()
        }
    }

    fun disconnect() {
        if (busyFlow.value) return
        submitWork {
            client.disconnect()
            appendQuietly(getApplication<Application>().getString(R.string.device_disconnected_message))
            refreshDevice()
        }
    }

    /** Returns true if the command was accepted (used to keep the input text). */
    fun runCommand(cmd: String): Boolean {
        val command = cmd.trim()
        if (command.isEmpty() ||
            busyFlow.value ||
            connectionState.value != Pm3ConnectionState.CONNECTED
        ) {
            return false
        }
        appendLine("> $command")
        addHistory(command)
        return submitWork {
            try {
                appendQuietly(client.exec(command))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                appendQuietly(
                    getApplication<Application>().getString(
                        R.string.console_error,
                        errorText(t),
                    ),
                )
            }
        }
    }

    fun clearConsole() {
        linesFlow.value = emptyList()
    }

    fun clearHistory() {
        historyFlow.value = emptyList()
    }

    fun deleteFile(entry: Pm3File) {
        val home = Pm3Resources.home(getApplication())
        if (!Pm3FileFilter.isVisible(entry.relativePath) ||
            !Pm3FileFilter.isInsideHome(home, entry.file)
        ) {
            notify(getApplication<Application>().getString(R.string.files_protected))
            return
        }
        fileOpBusyFlow.value = true
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { entry.file.delete() }
                if (deleted) {
                    notify(getApplication<Application>().getString(R.string.files_deleted))
                    refreshFiles()
                } else {
                    notify(
                        getApplication<Application>().getString(
                            R.string.files_delete_failed,
                            entry.file.name,
                        ),
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(
                    getApplication<Application>().getString(
                        R.string.files_delete_failed,
                        entry.file.name,
                    ),
                )
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun importFile(uri: Uri) {
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val home = Pm3Resources.home(app)
                    val displayName = queryDisplayName(uri) ?: "import-${System.currentTimeMillis()}.bin"
                    val safeName = sanitizeFileName(displayName)
                    val destination = uniqueTarget(home, safeName)
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IOException(app.getString(R.string.common_input_unavailable))
                    input.use { source ->
                        destination.outputStream().use { sink -> source.copyTo(sink) }
                    }
                    destination
                }
                notify(app.getString(R.string.files_imported, target.name))
                refreshFiles()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.files_import_failed, errorText(t)))
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun exportFile(uri: Uri, entry: Pm3File) {
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw IOException(app.getString(R.string.common_output_unavailable))
                    output.use { sink ->
                        entry.file.inputStream().use { source -> source.copyTo(sink) }
                    }
                }
                notify(app.getString(R.string.files_exported))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.files_export_failed, errorText(t)))
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun backupSettings(uri: Uri) {
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw IOException(app.getString(R.string.common_output_unavailable))
                    output.bufferedWriter().use { it.write(preferences.backupJson()) }
                }
                notify(app.getString(R.string.settings_backup_done))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.settings_backup_failed, errorText(t)))
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun restoreSettings(uri: Uri) {
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IOException(app.getString(R.string.common_input_unavailable))
                    val text = input.bufferedReader().use { it.readText() }
                    preferences.restoreJson(text)
                }
                notify(app.getString(R.string.settings_restore_done))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.settings_restore_failed, errorText(t)))
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun resetResources() {
        if (connectionState.value != Pm3ConnectionState.DISCONNECTED) {
            notify(getApplication<Application>().getString(R.string.settings_resources_busy))
            return
        }
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                client.resetResources()
                refreshResourcesState()
                notify(app.getString(R.string.settings_resources_reset))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(t.message ?: t.javaClass.simpleName)
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun diagnostics(): String {
        val app = getApplication<Application>()
        val device = deviceFlow.value
        val log = connectLog.value
        return buildString {
            appendLine("PM3 Android diagnostics")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("app commit: ${BuildConfig.APP_GIT_COMMIT}")
            appendLine("client commit: ${BuildConfig.PM3_CLIENT_COMMIT}")
            appendLine("connection: ${connectionState.value}")
            appendLine("busy: ${busyFlow.value}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("home: ${Pm3Resources.home(app).absolutePath}")
            if (device == null) {
                appendLine("usb: no device")
            } else {
                appendLine("usb permission: ${device.hasPermission}")
                appendLine(device.descriptor)
            }
            if (log.isNotBlank()) {
                appendLine("connect log:")
                appendLine(log.trim())
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = preferences.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = preferences.setDynamicColor(enabled)
    fun setConsoleFontSize(sizeSp: Int) = preferences.setConsoleFontSize(sizeSp)
    fun setConfirmDelete(enabled: Boolean) = preferences.setConfirmDelete(enabled)
    fun setKeepScreenOn(enabled: Boolean) = preferences.setKeepScreenOn(enabled)
    fun setFollowConsole(enabled: Boolean) = preferences.setFollowConsole(enabled)

    fun notify(text: String) {
        messagesFlow.tryEmit(text)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        // Closing the native client can block behind a running command. Release
        // the transport synchronously, then let native cleanup finish off the
        // main thread so Activity teardown cannot hit an ANR.
        val client = client
        Thread({ runCatching { client.closeNow() } }, "pm3-close").apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshResourcesState() {
        resourcesInstalledFlow.value = Pm3Resources.isInstalled(getApplication())
    }

    private fun shouldEnter(dir: File, home: File): Boolean {
        val relative = dir.relativeTo(home).path.replace('\\', '/')
        if (relative == ".proxmark3") return true
        if (relative.startsWith(".proxmark3/")) {
            return relative == ".proxmark3/logs" ||
                relative.startsWith(".proxmark3/logs/") ||
                relative == ".proxmark3/traces" ||
                relative.startsWith(".proxmark3/traces/")
        }
        return true
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val invalid = "\\/:*?\"<>|"
        val cleaned = buildString {
            for (char in name) {
                append(if (char in invalid || char.code < 0x20) '_' else char)
            }
        }
            .trim()
            .trimStart('.')
            .take(128)
        return cleaned.ifBlank { "import-${System.currentTimeMillis()}.bin" }
    }

    private fun uniqueTarget(home: File, name: String): File {
        var candidate = File(home, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(home, "$stem-$counter$extension")
            counter++
        }
        return candidate
    }

    private fun errorText(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName


    private fun submitWork(block: suspend () -> Unit): Boolean {
        if (busyFlow.value) return false
        busyFlow.value = true
        execJob = viewModelScope.launch {
            try {
                block()
            } finally {
                busyFlow.value = false
            }
        }
        return true
    }

    private fun addHistory(command: String) {
        val current = historyFlow.value
        if (current.lastOrNull() == command) return
        historyFlow.value = (current + command).takeLast(MAX_HISTORY)
    }

    private fun appendLine(line: String) {
        linesFlow.value = (linesFlow.value + line).takeLast(MAX_CONSOLE_LINES)
    }

    /** Adds each non-empty line of a console output chunk. */
    private fun appendQuietly(text: String) {
        if (text.isBlank()) return
        val toAdd = text.split('\n')
            .map { it.trimEnd('\r') }
            .dropLastWhile { it.isEmpty() }
        if (toAdd.any { it.isNotEmpty() }) {
            linesFlow.value = (linesFlow.value + toAdd).takeLast(MAX_CONSOLE_LINES)
        }
    }
}
