// Facade: everything the UI layer needs to talk to the in-process PM3 client.
//
// Connection sequence over the abstract-socket transport (the native client
// blocks in connect() until the bridge accepts):
//   1. install client resources into $HOME/.proxmark3
//   2. claim the CDC interface and get the USB streams
//   3. start the bridge listener ("pm3.android"), wait for accept()
//   4. nativeOpen("socket:pm3.android") — the client connects and handshakes
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Pm3ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

class Pm3Client(context: Context) {
    companion object {
        // Abstract socket name shared with the native client ("socket:pm3.android").
        private const val SOCKET_NAME = "pm3.android"
        private const val PORT = "socket:$SOCKET_NAME"
    }

    private val applicationContext = context.applicationContext

    private val usb = UsbCdc(applicationContext)
    private val lifeCycleMutex = Mutex()

    private val stateFlow =
        MutableStateFlow(Pm3ConnectionState.DISCONNECTED)
    private val connectLogFlow = MutableStateFlow("")

    private var bridge = AtomicReference<LocalSocketBridge?>(null)
    private var openedPort = AtomicReference<UsbCdc.OpenedPort?>(null)

    private var homeReady = false

    val state: StateFlow<Pm3ConnectionState>
        get() = stateFlow

    val connectLog: StateFlow<String>
        get() = connectLogFlow

    fun foundDevice(): UsbDevice? = usb.findDevice()

    suspend fun requestPermission(device: UsbDevice): Boolean =
        usb.requestPermission(device)

    /**
     * Connects to the PM3. Returns the client log of the attempt; on success
     * the state is CONNECTED, otherwise it ends back at DISCONNECTED.
     */
    suspend fun connect(): String = lifeCycleMutex.withLock {
        if (stateFlow.value == Pm3ConnectionState.CONNECTED) {
            return connectLogFlow.value
        }
        stateFlow.value = Pm3ConnectionState.CONNECTING
        connectLogFlow.value = ""
        try {
            prepareHome()
            val device = usb.findDevice()
                ?: error("Nessun Proxmark3 trovato sulla porta USB")
            if (!usb.requestPermission(device)) {
                error("Permesso USB negato")
            }
            val port = usb.open(device)
                ?: error("Impossibile aprire l'interfaccia CDC del PM3")

            val (input, output) = port.streams()
            val freshBridge = LocalSocketBridge(SOCKET_NAME)
            try {
                freshBridge.start(input, output)
            } catch (t: Throwable) {
                port.close()
                throw t
            }

            val openLog = withContext(Dispatchers.IO) {
                Pm3Native.nativeOpen(PORT)
            }

            return@withLock if (Pm3Native.nativeIsOpen()) {
                bridge.set(freshBridge)
                openedPort.set(port)
                connectLogFlow.value = openLog
                stateFlow.value = Pm3ConnectionState.CONNECTED
                openLog
            } else {
                freshBridge.stop()
                port.close()
                connectLogFlow.value = openLog
                stateFlow.value = Pm3ConnectionState.DISCONNECTED
                openLog
            }
        } catch (t: Throwable) {
            connectLogFlow.value = t.message ?: t.javaClass.simpleName
            stateFlow.value = Pm3ConnectionState.DISCONNECTED
            connectLogFlow.value
        }
    }

    suspend fun disconnect() = lifeCycleMutex.withLock {
        withContext(Dispatchers.IO) {
            Pm3Native.nativeClose()
        }
        bridge.getAndSet(null)?.stop()
        openedPort.getAndSet(null)?.close()
        stateFlow.value = Pm3ConnectionState.DISCONNECTED
    }

    /**
     * Runs one console command and returns everything it printed. Blocks for
     * as long as the command runs; keep off the main thread and serialise
     * calls at the UI layer (the client accepts one command at a time).
     */
    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        Pm3Native.nativeExec(cmd)
    }

    private fun prepareHome(): Boolean {
        if (homeReady) {
            return true
        }
        Pm3Resources.ensure(applicationContext)
        Pm3Native.nativeInit(Pm3Resources.home(applicationContext).absolutePath)
        homeReady = true
        return true
    }
}
