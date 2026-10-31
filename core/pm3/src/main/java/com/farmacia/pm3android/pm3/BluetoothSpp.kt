// Bluetooth Classic SPP transport for the RDV4 BlueShark module.
//
// The module exposes a standard serial-port profile (SPP, UUID 00001101...),
// so the app only needs an RFCOMM socket: the PM3 client keeps using its
// abstract "socket:" transport through LocalSocketBridge.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothSpp(private val context: Context) {

    companion object {
        /** Standard Bluetooth Serial Port Profile. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val CONNECT_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 150L
        private const val FALLBACK_CHANNEL = 1
    }

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    private var discoveryReceiver: BroadcastReceiver? = null

    fun isSupported(): Boolean = adapter != null

    fun adapterState(): BluetoothAdapterState {
        val current = adapter ?: return BluetoothAdapterState.UNSUPPORTED
        // isEnabled() is gated by BLUETOOTH_CONNECT on Android 12+.
        if (!BluetoothPermissions.has(context, scan = false)) {
            return BluetoothAdapterState.PERMISSION_REQUIRED
        }
        return if (current.isEnabled) BluetoothAdapterState.ON else BluetoothAdapterState.OFF
    }

    /**
     * Bonded devices that can speak SPP. LE-only entries are handled by
     * [BleSpp] instead; UNKNOWN is kept because some stacks do not classify a
     * bonded device before the first connection.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceInfo> {
        if (!BluetoothPermissions.has(context, scan = false)) return emptyList()
        val current = adapter ?: return emptyList()
        return runCatching {
            current.bondedDevices.orEmpty()
                .asSequence()
                .filter {
                    it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                        it.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                        it.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
                }
                .map {
                    BluetoothDeviceInfo(
                        name = it.name,
                        address = it.address,
                        link = BluetoothLink.CLASSIC,
                        bonded = true,
                    )
                }
                .sortedWith(compareByDescending<BluetoothDeviceInfo> { it.isProxmark }
                    .thenBy { it.displayName.lowercase() })
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Starts Classic discovery; [onFinished] fires on the system stop event. */
    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onFound: (BluetoothDeviceInfo) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        val current = adapter ?: return false
        if (!BluetoothPermissions.has(context, scan = true)) return false
        if (!current.isEnabled) return false
        if (discoveryReceiver != null) return true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        runCatching {
                            onFound(
                                BluetoothDeviceInfo(
                                    name = device.name,
                                    address = device.address,
                                    link = BluetoothLink.CLASSIC,
                                    bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                                    rssi = intent.getShortExtra(
                                        BluetoothDevice.EXTRA_RSSI,
                                        Short.MIN_VALUE,
                                    ).toInt().takeIf { it != Short.MIN_VALUE.toInt() },
                                ),
                            )
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopDiscovery()
                        onFinished()
                    }
                }
            }
        }

        return runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            discoveryReceiver = receiver
            check(current.startDiscovery()) { "discovery did not start" }
            true
        }.getOrElse {
            stopDiscovery()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val receiver = discoveryReceiver ?: return
        discoveryReceiver = null
        runCatching { adapter?.cancelDiscovery() }
        runCatching { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    suspend fun open(address: String): Pm3Port = withContext(Dispatchers.IO) {
        val current = adapter
            ?: throw IOException("Bluetooth non supportato")
        if (!BluetoothPermissions.has(context, scan = false)) {
            throw IOException("Permesso Bluetooth mancante")
        }
        if (!current.isEnabled) {
            throw IOException("Bluetooth spento")
        }
        if (!BluetoothAddress.isValid(address)) {
            throw IOException("Indirizzo Bluetooth non valido")
        }

        cancelRequested = false
        runCatching { if (current.isDiscovering) current.cancelDiscovery() }

        val device = try {
            current.getRemoteDevice(BluetoothAddress.normalize(address))
        } catch (t: IllegalArgumentException) {
            throw IOException("Indirizzo Bluetooth non valido", t)
        }

        var lastError: Throwable? = null
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            if (cancelRequested) throw IOException("Connessione annullata")
            val socket = try {
                if (attempt == CONNECT_ATTEMPTS - 1) {
                    createChannelSocket(device)
                } else {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            if (socket == null) {
                lastError = IOException("Socket SPP non disponibile")
                continue
            }

            activeSocket = socket
            try {
                runInterruptible { socket.connect() }
                if (cancelRequested) throw IOException("Connessione annullata")
                // Force the stream creation here: a half-open socket would
                // otherwise fail later, inside the bridge.
                socket.inputStream
                socket.outputStream
                return@withContext SppPort(socket)
            } catch (t: Throwable) {
                lastError = t
                runCatching { socket.close() }
            } finally {
                if (activeSocket === socket) activeSocket = null
            }
            runCatching { Thread.sleep(RETRY_BACKOFF_MS) }
        }
        throw IOException(
            lastError?.message?.let { "Connessione SPP non riuscita: $it" }
                ?: "Connessione SPP non riuscita",
            lastError,
        )
    }

    /** Aborts an in-flight [open] before the port exists. */
    fun cancelConnect() {
        cancelRequested = true
        runCatching { activeSocket?.close() }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun createChannelSocket(device: BluetoothDevice): BluetoothSocket? = runCatching {
        device.javaClass
            .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, FALLBACK_CHANNEL) as BluetoothSocket
    }.getOrNull()

    private class SppPort(private val socket: BluetoothSocket) : Pm3Port {

        private val input = socket.inputStream
        private val output = socket.outputStream

        @Volatile
        private var closed = false

        override fun streams() = input to output

        override fun close() {
            if (closed) return
            closed = true
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }
}
