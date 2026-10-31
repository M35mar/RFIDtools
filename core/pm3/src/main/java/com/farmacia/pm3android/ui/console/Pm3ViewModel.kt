// ViewModel shared by all tabs. The native client accepts one command at a
// time, so PM3 work is serialised with a busy flag; file operations have a
// separate flag so they can run while the console is idle.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.farmacia.pm3android.BuildConfig
import com.farmacia.pm3android.R
import com.farmacia.pm3android.data.AppPreferences
import com.farmacia.pm3android.data.AppSettings
import com.farmacia.pm3android.data.ConnectionMode
import com.farmacia.pm3android.data.Pm3File
import com.farmacia.pm3android.data.Pm3FileFilter
import com.farmacia.pm3android.data.Pm3Script
import com.farmacia.pm3android.data.Pm3ScriptStore
import com.farmacia.pm3android.data.ScriptLanguage
import com.farmacia.pm3android.data.ThemeMode
import com.farmacia.pm3android.pm3.BluetoothAdapterState
import com.farmacia.pm3android.pm3.BluetoothDeviceInfo
import com.farmacia.pm3android.pm3.BluetoothLink
import com.farmacia.pm3android.pm3.BluetoothPermissions
import com.farmacia.pm3android.pm3.Pm3Client
import com.farmacia.pm3android.pm3.Pm3ConnectionState
import com.farmacia.pm3android.pm3.Pm3PythonRunner
import com.farmacia.pm3android.pm3.Pm3Resources
import com.farmacia.pm3android.pm3.Pm3Transport
import com.farmacia.pm3android.update.ApkInstaller
import com.farmacia.pm3android.update.ReleaseCheck
import com.farmacia.pm3android.update.ReleaseInfo
import com.farmacia.pm3android.update.UpdateManager
import com.farmacia.pm3android.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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

        private const val BLUETOOTH_SCAN_MS = 15_000L
        private val NATIVE_BLUETOOTH_CONNECT =
            Regex("^hw\\s+connect\\s+(bt|ble):", RegexOption.IGNORE_CASE)
    }

    private val client = Pm3Client(application)
    private val preferences = AppPreferences(application)
    private val updateManager = UpdateManager(application)

    val connectionState: StateFlow<Pm3ConnectionState> = client.state
    val connectLog: StateFlow<String> = client.connectLog
    val settings: StateFlow<AppSettings> = preferences.settings

    private val deviceFlow = MutableStateFlow<Pm3DeviceInfo?>(null)
    val device: StateFlow<Pm3DeviceInfo?> = deviceFlow

    // One unified Bluetooth list: bonded Classic units and BLE scan results.
    private val bluetoothDevicesFlow = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDeviceInfo>> = bluetoothDevicesFlow

    private val bluetoothScanningFlow = MutableStateFlow(false)
    val bluetoothScanning: StateFlow<Boolean> = bluetoothScanningFlow

    private val bluetoothStateFlow = MutableStateFlow(BluetoothAdapterState.UNSUPPORTED)
    val bluetoothState: StateFlow<BluetoothAdapterState> = bluetoothStateFlow

    private val bondedBluetooth = ConcurrentHashMap<String, BluetoothDeviceInfo>()
    private val scannedBluetooth = ConcurrentHashMap<String, BluetoothDeviceInfo>()
    private var bluetoothScanJob: Job? = null

    private val filesFlow = MutableStateFlow<List<Pm3File>>(emptyList())
    val files: StateFlow<List<Pm3File>> = filesFlow

    private val scriptsFlow = MutableStateFlow<List<Pm3Script>>(emptyList())
    val scripts: StateFlow<List<Pm3Script>> = scriptsFlow

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

    private val updateStateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = updateStateFlow

    /** Release to show in the startup dialog; null once dismissed. */
    private val updatePromptFlow = MutableStateFlow<ReleaseInfo?>(null)
    val updatePrompt: StateFlow<ReleaseInfo?> = updatePromptFlow

    private var updateJob: Job? = null

    var execJob: Job? = null
        private set

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // A detach only matters when the active link is USB: the
                    // Bluetooth transports stay up while the cable is pulled.
                    val wasConnected = connectionState.value != Pm3ConnectionState.DISCONNECTED &&
                        client.transport.value == Pm3Transport.USB
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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                    refreshBluetooth()
                    if (state == BluetoothAdapter.STATE_TURNING_OFF ||
                        state == BluetoothAdapter.STATE_OFF
                    ) {
                        stopBluetoothScan()
                        abortBluetoothIfActive()
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )
                    val selected = preferences.settings.value.bluetoothAddress
                    if (device != null && selected != null &&
                        !selected.equals(device.address, ignoreCase = true)
                    ) {
                        return
                    }
                    abortBluetoothIfActive()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBluetooth()
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
        ContextCompat.registerReceiver(
            application,
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        refreshDevice()
        refreshBluetooth()
        refreshFiles()
        refreshScripts()
        if (preferences.settings.value.autoCheckUpdates) {
            checkForUpdates(manual = false)
        }
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

    fun refreshBluetooth() {
        bluetoothStateFlow.value = if (!client.bluetoothSupported()) {
            BluetoothAdapterState.UNSUPPORTED
        } else {
            client.bluetoothState()
        }
        val bonded = if (bluetoothStateFlow.value == BluetoothAdapterState.ON) {
            client.bondedClassicDevices()
        } else {
            emptyList()
        }
        bondedBluetooth.clear()
        bonded.forEach { bondedBluetooth[bluetoothKey(it)] = it }
        publishBluetoothDevices()
    }

    fun setConnectionMode(mode: ConnectionMode) {
        if (mode == preferences.settings.value.connectionMode) return
        // Transport changes only while idle: the native client owns one link.
        if (busyFlow.value || connectionState.value != Pm3ConnectionState.DISCONNECTED) return
        stopBluetoothScan()
        preferences.setConnectionMode(mode)
        if (mode == ConnectionMode.BLUETOOTH) refreshBluetooth()
    }

    fun selectBluetoothDevice(info: BluetoothDeviceInfo) {
        preferences.setBluetoothDevice(info.address, info.name, info.link)
    }

    fun startBluetoothScan() {
        if (bluetoothScanningFlow.value) return
        val app = getApplication<Application>()
        refreshBluetooth()
        when (bluetoothStateFlow.value) {
            BluetoothAdapterState.ON -> Unit
            BluetoothAdapterState.PERMISSION_REQUIRED -> {
                notify(app.getString(R.string.device_bt_permissions))
                return
            }
            BluetoothAdapterState.OFF -> {
                notify(app.getString(R.string.device_bt_disabled))
                return
            }
            BluetoothAdapterState.UNSUPPORTED -> {
                notify(app.getString(R.string.device_bt_unsupported))
                return
            }
        }
        if (!BluetoothPermissions.has(app, scan = true)) {
            notify(app.getString(R.string.device_bt_permissions))
            return
        }

        scannedBluetooth.clear()
        publishBluetoothDevices()
        bluetoothScanningFlow.value = true

        val onFound: (BluetoothDeviceInfo) -> Unit = { info ->
            scannedBluetooth[bluetoothKey(info)] = info
            publishBluetoothDevices()
        }
        // Classic discovery and the BLE scan run together; each stops on its
        // own system timeout, so the shared job only bounds the UI state.
        val classicStarted = client.classicBluetooth.startDiscovery(onFound) {}
        val leStarted = client.bleBluetooth.startScan(onFound) {}
        if (!classicStarted && !leStarted) {
            bluetoothScanningFlow.value = false
            notify(app.getString(R.string.device_bt_empty))
            return
        }

        bluetoothScanJob?.cancel()
        bluetoothScanJob = viewModelScope.launch {
            delay(BLUETOOTH_SCAN_MS)
            stopBluetoothScan()
        }
    }

    fun stopBluetoothScan() {
        bluetoothScanJob?.cancel()
        bluetoothScanJob = null
        client.classicBluetooth.stopDiscovery()
        client.bleBluetooth.stopScan()
        bluetoothScanningFlow.value = false
    }

    private fun abortBluetoothIfActive() {
        val transport = client.transport.value
        if (transport != Pm3Transport.BT_CLASSIC && transport != Pm3Transport.BT_LE) return
        if (connectionState.value == Pm3ConnectionState.DISCONNECTED) return
        // A user-initiated disconnect already tears the link down; do not
        // report it as a lost connection.
        if (busyFlow.value) return
        notify(getApplication<Application>().getString(R.string.device_bt_lost_message))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.abortNow() }
            busyFlow.value = false
        }
    }

    private fun bluetoothKey(info: BluetoothDeviceInfo): String =
        "${info.address}|${info.link}"

    private fun publishBluetoothDevices() {
        val merged = LinkedHashMap<String, BluetoothDeviceInfo>()
        bondedBluetooth.forEach { (key, info) -> merged[key] = info }
        scannedBluetooth.forEach { (key, info) ->
            val existing = merged[key]
            merged[key] = if (existing == null) {
                info
            } else {
                existing.copy(
                    name = existing.name ?: info.name,
                    rssi = info.rssi ?: existing.rssi,
                    bonded = existing.bonded || info.bonded,
                )
            }
        }
        bluetoothDevicesFlow.value = merged.values.sortedWith(
            compareByDescending<BluetoothDeviceInfo> { it.isProxmark }
                .thenByDescending { it.bonded }
                .thenBy { it.displayName.lowercase() },
        )
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
        val settings = preferences.settings.value
        val transport = when {
            settings.connectionMode == ConnectionMode.USB -> Pm3Transport.USB
            settings.bluetoothLink == BluetoothLink.LE -> Pm3Transport.BT_LE
            else -> Pm3Transport.BT_CLASSIC
        }
        val address = settings.bluetoothAddress
        submitWork {
            appendQuietly(client.connect(transport, address))
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
        // The embedded client is built without BlueZ: transport selection is
        // app-side, so `hw connect bt:/ble:` cannot work here.
        if (NATIVE_BLUETOOTH_CONNECT.containsMatchIn(command)) {
            appendLine("> $command")
            addHistory(command)
            appendLine(getApplication<Application>().getString(R.string.console_bt_transport_hint))
            return true
        }
        // Python scripts run in the Chaquopy runtime, not in the native client.
        // Intercept only an exact `script run name.py ...` so other client
        // commands keep their normal path.
        parsePythonCommand(command)?.let { (script, arguments) ->
            return runPythonScript(script, arguments, command)
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

    fun refreshScripts() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                scriptsFlow.value = withContext(Dispatchers.IO) {
                    Pm3ScriptStore.list(app)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                scriptsFlow.value = emptyList()
            }
        }
    }

    fun importScript(uri: Uri) {
        fileOpBusyFlow.value = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    queryDisplayName(uri)
                        ?: throw IOException(app.getString(R.string.common_input_unavailable))
                }
                val imported = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IOException(app.getString(R.string.common_input_unavailable))
                    Pm3ScriptStore.import(app, name, input)
                }
                notify(app.getString(R.string.scripts_imported, imported.name))
                refreshScripts()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.scripts_import_failed, errorText(t)))
            } finally {
                fileOpBusyFlow.value = false
            }
        }
    }

    fun deleteScript(script: Pm3Script) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    Pm3ScriptStore.delete(app, script)
                }
                if (deleted) {
                    notify(app.getString(R.string.scripts_deleted))
                    refreshScripts()
                } else {
                    notify(app.getString(R.string.scripts_delete_failed, script.name))
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                notify(app.getString(R.string.scripts_delete_failed, script.name))
            }
        }
    }

    /**
     * Runs a user script through the normal console path. Arguments are kept
     * verbatim because this is the public `script run` interface; the file
     * name itself was sanitized on import.
     */
    fun runScript(script: Pm3Script, arguments: String): Boolean {
        val suffix = arguments.trim().take(512)
        val command = if (suffix.isEmpty()) {
            "script run ${script.name}"
        } else {
            "script run ${script.name} $suffix"
        }
        return if (script.language == ScriptLanguage.PYTHON) {
            runPythonScript(script, suffix, command)
        } else {
            runCommand(command)
        }
    }

    private fun runPythonScript(
        script: Pm3Script,
        arguments: String,
        command: String,
    ): Boolean {
        if (busyFlow.value || connectionState.value != Pm3ConnectionState.CONNECTED) {
            return false
        }
        appendLine("> $command")
        addHistory(command)
        val app = getApplication<Application>()
        return submitWork {
            try {
                val output = withContext(Dispatchers.IO) {
                    // Run the installed mirror so sibling modules in
                    // .proxmark3/pyscripts are importable. Fall back to the
                    // original if the mirror is missing.
                    val mirror = File(
                        Pm3ScriptStore.activeDirectory(app, ScriptLanguage.PYTHON),
                        script.name,
                    )
                    val executable = if (mirror.isFile) mirror else script.file
                    Pm3PythonRunner.run(app, executable, arguments)
                }
                appendQuietly(output)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                appendQuietly(
                    app.getString(R.string.scripts_python_failed, errorText(t)),
                )
            }
        }
    }

    /**
     * Recognises `script run name.py [args]` and resolves the script in the
     * installed pyscripts directory. The name is kept to a single path
     * component so a console command cannot escape that directory.
     */
    private fun parsePythonCommand(command: String): Pair<Pm3Script, String>? {
        if (!command.startsWith("script run ")) return null
        val rest = command.removePrefix("script run ").trim()
        val name = rest.substringBefore(' ')
        if (!name.endsWith(".py", ignoreCase = true) ||
            '/' in name ||
            '\\' in name
        ) {
            return null
        }
        val app = getApplication<Application>()
        val file = File(Pm3ScriptStore.activeDirectory(app, ScriptLanguage.PYTHON), name)
        if (!file.isFile) return null
        val script = Pm3Script(
            file = file,
            name = file.name,
            language = ScriptLanguage.PYTHON,
            size = file.length(),
            lastModified = file.lastModified(),
        )
        val arguments = rest.removePrefix(name).trim().take(512)
        return script to arguments
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
            appendLine("RFIDtools diagnostics")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("app commit: ${BuildConfig.APP_GIT_COMMIT}")
            appendLine("client commit: ${BuildConfig.PM3_CLIENT_COMMIT}")
            appendLine("connection: ${connectionState.value}")
            appendLine("transport: ${client.transport.value ?: "-"}")
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
            val settings = preferences.settings.value
            appendLine("mode: ${settings.connectionMode}")
            appendLine("bluetooth: ${bluetoothStateFlow.value}")
            appendLine(
                "bluetooth device: " +
                    (settings.bluetoothAddress?.let { address ->
                        BluetoothDeviceInfo(
                            name = settings.bluetoothName,
                            address = address,
                            link = settings.bluetoothLink,
                        ).maskedAddress + " (${settings.bluetoothLink})"
                    } ?: "none"),
            )
            appendLine("bluetooth scan: ${bluetoothScanningFlow.value}")
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
    fun setAutoCheckUpdates(enabled: Boolean) = preferences.setAutoCheckUpdates(enabled)

    // -----------------------------------------------------------------------
    // In-app updates
    // -----------------------------------------------------------------------

    fun checkForUpdates(manual: Boolean) {
        val current = updateStateFlow.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        updateJob?.cancel()
        updateStateFlow.value = UpdateState.Checking
        val app = getApplication<Application>()
        updateJob = viewModelScope.launch {
            try {
                when (val result = updateManager.check(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.APPLICATION_ID,
                )) {
                    is ReleaseCheck.Newer -> {
                        updateStateFlow.value = UpdateState.Available(result.release)
                        if (!manual &&
                            preferences.lastNotifiedUpdateVersion() != result.release.versionName
                        ) {
                            updatePromptFlow.value = result.release
                        }
                    }

                    is ReleaseCheck.Current ->
                        updateStateFlow.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)

                    is ReleaseCheck.Incompatible -> {
                        // Debug builds are not published as release assets, so
                        // an automatic check there is not a user-facing error.
                        updateStateFlow.value = if (manual) {
                            UpdateState.Error(
                                app.getString(
                                    R.string.update_no_asset,
                                    result.release.versionName,
                                ),
                            )
                        } else {
                            UpdateState.Idle
                        }
                    }
                }
            } catch (t: CancellationException) {
                updateStateFlow.value = UpdateState.Idle
                throw t
            } catch (t: Throwable) {
                if (manual) {
                    updateStateFlow.value = UpdateState.Error(errorText(t))
                } else {
                    // A background check must never bother the user with a
                    // snackbar when the phone is offline or GitHub is down.
                    updateStateFlow.value = UpdateState.Idle
                }
            }
        }
    }

    fun dismissUpdatePrompt() {
        updatePromptFlow.value?.let { preferences.markUpdateNotified(it.versionName) }
        updatePromptFlow.value = null
    }

    fun downloadUpdate() {
        val available = updateStateFlow.value as? UpdateState.Available ?: return
        val asset = available.release.asset ?: return
        if (updateStateFlow.value is UpdateState.Downloading) return
        updateJob?.cancel()
        updateStateFlow.value = UpdateState.Downloading(
            release = available.release,
            bytesRead = 0L,
            totalBytes = asset.sizeBytes,
        )
        val app = getApplication<Application>()
        notify(app.getString(R.string.update_download_started))
        updateJob = viewModelScope.launch {
            try {
                val file = updateManager.download(asset) { read, total ->
                    updateStateFlow.value = UpdateState.Downloading(
                        release = available.release,
                        bytesRead = read,
                        totalBytes = total,
                    )
                }
                updateStateFlow.value = UpdateState.Ready(available.release, file)
                installUpdate()
            } catch (t: CancellationException) {
                updateStateFlow.value = UpdateState.Available(available.release)
                throw t
            } catch (t: Throwable) {
                updateStateFlow.value = UpdateState.Error(errorText(t))
                notify(app.getString(R.string.update_download_failed, errorText(t)))
            }
        }
    }

    fun installUpdate() {
        val ready = updateStateFlow.value as? UpdateState.Ready ?: return
        val app = getApplication<Application>()
        if (!ApkInstaller.canInstall(app)) {
            updateStateFlow.value = ready.copy(needsPermission = true)
            notify(app.getString(R.string.update_permission_message))
            ApkInstaller.openInstallPermission(app)
            return
        }
        if (!ApkInstaller.install(app, ready.file)) {
            updateStateFlow.value = ready.copy(needsPermission = true)
            notify(app.getString(R.string.update_install_failed))
        }
    }

    fun notify(text: String) {
        messagesFlow.tryEmit(text)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        runCatching { getApplication<Application>().unregisterReceiver(bluetoothReceiver) }
        stopBluetoothScan()
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
