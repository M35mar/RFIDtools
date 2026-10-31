// Facade: everything the UI layer needs to talk to the in-process PM3 client.
//
// Connection sequence over the abstract-socket transport (the native client
// blocks in connect() until the bridge accepts):
//   1. install client resources into $HOME/.proxmark3
//   2. open the selected transport (USB CDC, Bluetooth Classic SPP or BLE)
//   3. start the bridge listener ("pm3.android"), wait for accept()
//   4. nativeOpen("socket:pm3.android") — the client connects and handshakes
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.content.Context
import android.hardware.usb.UsbDevice
import com.farmacia.pm3android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

enum class Pm3ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

enum class Pm3Transport {
    USB,
    BT_CLASSIC,
    BT_LE,
}

class Pm3Client(context: Context) {
    companion object {
        // Abstract socket name shared with the native client ("socket:pm3.android").
        private const val SOCKET_NAME = "pm3.android"
        private const val PORT = "socket:$SOCKET_NAME"
        private const val USB_PERMISSION_TIMEOUT_MS = 60_000L
    }

    private val applicationContext = context.applicationContext

    private val usb = UsbCdc(applicationContext)
    val classicBluetooth = BluetoothSpp(applicationContext)
    val bleBluetooth = BleSpp(applicationContext)

    private val lifeCycleMutex = Mutex()

    private val stateFlow = MutableStateFlow(Pm3ConnectionState.DISCONNECTED)
    private val connectLogFlow = MutableStateFlow("")
    private val transportFlow = MutableStateFlow<Pm3Transport?>(null)

    private val bridge = AtomicReference<LocalSocketBridge?>(null)
    private val openedPort = AtomicReference<Pm3Port?>(null)

    private var homeReady = false

    @Volatile
    private var abortRequested = false

    val state: StateFlow<Pm3ConnectionState>
        get() = stateFlow

    val connectLog: StateFlow<String>
        get() = connectLogFlow

    /** Transport that owns the current connection, null while disconnected. */
    val transport: StateFlow<Pm3Transport?>
        get() = transportFlow

    fun foundDevice(): UsbDevice? = usb.findDevice()

    fun hasPermission(device: UsbDevice): Boolean = usb.hasPermission(device)

    fun describeDevice(device: UsbDevice): String = usb.describe(device)

    fun bluetoothSupported(): Boolean = classicBluetooth.isSupported()

    fun bluetoothState(): BluetoothAdapterState = classicBluetooth.adapterState()

    fun bondedClassicDevices(): List<BluetoothDeviceInfo> = classicBluetooth.bondedDevices()

    /**
     * Connects to the PM3. Returns the client log of the attempt; on success
     * the state is CONNECTED, otherwise it ends back at DISCONNECTED.
     */
    suspend fun connect(
        transport: Pm3Transport = Pm3Transport.USB,
        bluetoothAddress: String? = null,
    ): String = lifeCycleMutex.withLock {
        if (stateFlow.value == Pm3ConnectionState.CONNECTED) {
            return connectLogFlow.value
        }
        if (stateFlow.value == Pm3ConnectionState.CONNECTING) {
            return applicationContext.getString(R.string.connect_already_running)
        }
        abortRequested = false
        stateFlow.value = Pm3ConnectionState.CONNECTING
        // Remember the transport while connecting too: a USB detach or a
        // Bluetooth ACL drop has to abort only its own link.
        transportFlow.value = transport
        connectLogFlow.value = ""

        var freshBridge: LocalSocketBridge? = null
        var freshPort: Pm3Port? = null

        try {
            prepareHome()

            freshPort = when (transport) {
                Pm3Transport.USB -> openUsbPort()
                Pm3Transport.BT_CLASSIC -> openClassicPort(bluetoothAddress)
                Pm3Transport.BT_LE -> openBlePort(bluetoothAddress)
            }

            val (input, output) = freshPort.streams()
            if (abortRequested) error(applicationContext.getString(R.string.connect_cancelled))
            freshBridge = LocalSocketBridge(SOCKET_NAME).also {
                it.start(input, output)
            }

            val openLog = withContext(Dispatchers.IO) {
                Pm3Native.nativeOpen(PORT)
            }

            if (abortRequested) error(applicationContext.getString(R.string.connect_cancelled))
            if (!Pm3Native.nativeIsOpen()) {
                val message = decorateError(
                    openLog.ifBlank {
                        applicationContext.getString(R.string.connect_client_open_failed)
                    },
                    transport,
                )
                connectLogFlow.value = message
                stateFlow.value = Pm3ConnectionState.DISCONNECTED
                transportFlow.value = null
                return@withLock message
            }

            if (abortRequested) error(applicationContext.getString(R.string.connect_cancelled))
            bridge.set(freshBridge)
            openedPort.set(freshPort)
            transportFlow.value = transport
            // Ownership moved to the client; do not clean these up below.
            freshBridge = null
            freshPort = null

            connectLogFlow.value = openLog
            stateFlow.value = Pm3ConnectionState.CONNECTED
            openLog
        } catch (t: CancellationException) {
            // The caller was cancelled (for example the ViewModel was cleared):
            // do not turn cancellation into a connection error, but never leave
            // the UI stuck in CONNECTING.
            stateFlow.value = Pm3ConnectionState.DISCONNECTED
            transportFlow.value = null
            throw t
        } catch (t: Throwable) {
            val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            connectLogFlow.value = message
            stateFlow.value = Pm3ConnectionState.DISCONNECTED
            transportFlow.value = null
            message
        } finally {
            if (freshBridge != null || freshPort != null) {
                // The native open may have succeeded even when we are not
                // taking ownership (cancellation or detach race).
                runCatching { Pm3Native.nativeClose() }
            }
            freshBridge?.stop()
            freshPort?.close()
        }
    }

    suspend fun disconnect() = lifeCycleMutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                Pm3Native.nativeClose()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            // Cleanup still has to happen; the UI only needs a state change and
            // a hint in the connection log.
            connectLogFlow.value = t.message?.takeIf { it.isNotBlank() }
                ?: t.javaClass.simpleName
        } finally {
            bridge.getAndSet(null)?.stop()
            openedPort.getAndSet(null)?.close()
            transportFlow.value = null
            stateFlow.value = Pm3ConnectionState.DISCONNECTED
        }
    }

    /**
     * Emergency teardown: close the transport first so a native read/write
     * blocked on the link cannot keep the client alive, then release the
     * client.
     */
    fun abortNow() {
        abortRequested = true
        classicBluetooth.cancelConnect()
        bleBluetooth.cancelConnect()
        bridge.getAndSet(null)?.stop()
        openedPort.getAndSet(null)?.close()
        runCatching { Pm3Native.nativeClose() }
        transportFlow.value = null
        stateFlow.value = Pm3ConnectionState.DISCONNECTED
    }

    /** Kept for callers that want a simple synchronous close. */
    fun closeNow() = abortNow()

    suspend fun resetResources() = lifeCycleMutex.withLock {
        check(stateFlow.value == Pm3ConnectionState.DISCONNECTED) {
            applicationContext.getString(R.string.connect_disconnect_first)
        }
        withContext(Dispatchers.IO) {
            Pm3Resources.reset(applicationContext)
            homeReady = false
        }
    }

    /**
     * Runs one console command and returns everything it printed. Blocks for
     * as long as the command runs; keep off the main thread and serialise
     * calls at the UI layer (the client accepts one command at a time).
     */
    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        Pm3Native.nativeExec(cmd)
    }

    private suspend fun openUsbPort(): Pm3Port {
        val device = usb.findDevice()
            ?: error(applicationContext.getString(R.string.connect_device_missing))
        val granted = withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) {
            usb.requestPermission(device)
        }
        if (granted == null) {
            error(applicationContext.getString(R.string.connect_permission_timeout))
        }
        if (!granted) {
            error(applicationContext.getString(R.string.connect_permission_denied))
        }
        if (abortRequested) error(applicationContext.getString(R.string.connect_cancelled))
        return usb.open(device)
            ?: error(applicationContext.getString(R.string.connect_cdc_open_failed))
    }

    private suspend fun openClassicPort(address: String?): Pm3Port {
        if (address.isNullOrBlank()) {
            error(applicationContext.getString(R.string.connect_bluetooth_no_device))
        }
        if (!classicBluetooth.isSupported()) {
            error(applicationContext.getString(R.string.connect_bluetooth_unsupported))
        }
        if (!BluetoothPermissions.has(applicationContext, scan = false)) {
            error(applicationContext.getString(R.string.connect_bluetooth_permission))
        }
        if (classicBluetooth.adapterState() != BluetoothAdapterState.ON) {
            error(applicationContext.getString(R.string.connect_bluetooth_disabled))
        }
        return classicBluetooth.open(address)
    }

    private suspend fun openBlePort(address: String?): Pm3Port {
        if (address.isNullOrBlank()) {
            error(applicationContext.getString(R.string.connect_bluetooth_no_device))
        }
        if (!bleBluetooth.isSupported()) {
            error(applicationContext.getString(R.string.connect_bluetooth_unsupported))
        }
        if (!BluetoothPermissions.has(applicationContext, scan = false)) {
            error(applicationContext.getString(R.string.connect_bluetooth_permission))
        }
        if (bleBluetooth.adapterState() != BluetoothAdapterState.ON) {
            error(applicationContext.getString(R.string.connect_bluetooth_disabled))
        }
        return bleBluetooth.open(address)
    }

    /** Adds the firmware/build hint that explains a Bluetooth handshake failure. */
    private fun decorateError(message: String, transport: Pm3Transport): String {
        val hint = when (transport) {
            Pm3Transport.USB -> null
            Pm3Transport.BT_CLASSIC ->
                applicationContext.getString(R.string.connect_bt_firmware_hint)
            Pm3Transport.BT_LE ->
                applicationContext.getString(R.string.connect_ble_firmware_hint)
        } ?: return message
        return when {
            message.isBlank() -> hint
            message.contains(hint) -> message
            else -> "$message\n$hint"
        }
    }

    private suspend fun prepareHome() {
        if (homeReady) {
            return
        }
        withContext(Dispatchers.IO) {
            Pm3Resources.ensure(applicationContext)
            val initialized = Pm3Native.nativeInit(
                Pm3Resources.home(applicationContext).absolutePath,
            )
            check(initialized) {
                applicationContext.getString(R.string.connect_native_init_failed)
            }
        }
        homeReady = true
    }
}
