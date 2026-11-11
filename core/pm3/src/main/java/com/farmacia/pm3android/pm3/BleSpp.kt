// Bluetooth LE transport for the Proxmark5 BWM.
//
// The BWM exposes a plain GATT byte pipe (service 0xAE86, characteristic
// 0xAE88 with WRITE/NOTIFY, no bonding). This class turns that callback-driven
// profile into the same blocking streams used by the Classic and USB paths.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Internal: the open() coroutine was cancelled while waiting for the GATT. */
private class LinkCancelledException : Exception()

class BleSpp(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ae86-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ae88-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 517
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICES_TIMEOUT_MS = 10_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val WRITE_RETRIES = 200
        // How often a blocking GATT wait checks for coroutine cancellation.
        private const val CANCEL_POLL_MS = 50L
    }

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    @Volatile
    private var activeLink: Link? = null

    /**
     * Fired once when an established link drops (GATT error, remote or adapter
     * disconnect) or when the transmit pump fails. Never fired for a close()
     * requested by the app. Runs on a GATT callback thread or the tx thread.
     */
    @Volatile
    var onLinkLost: ((String) -> Unit)? = null

    fun isSupported(): Boolean = adapter != null

    fun adapterState(): BluetoothAdapterState {
        val current = adapter ?: return BluetoothAdapterState.UNSUPPORTED
        if (!BluetoothPermissions.has(context, scan = false)) {
            return BluetoothAdapterState.PERMISSION_REQUIRED
        }
        return if (current.isEnabled) BluetoothAdapterState.ON else BluetoothAdapterState.OFF
    }

    @SuppressLint("MissingPermission")
    fun startScan(
        onFound: (BluetoothDeviceInfo) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        val current = adapter ?: return false
        if (!BluetoothPermissions.has(context, scan = true)) return false
        if (!current.isEnabled) return false
        if (scanCallback != null) return true
        val leScanner = current.bluetoothLeScanner ?: return false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val hasService = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                val name = record?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                if (!hasService && !looksLikeProxmark(name)) return
                val bonded = runCatching {
                    result.device.bondState == BluetoothDevice.BOND_BONDED
                }.getOrDefault(false)
                onFound(
                    BluetoothDeviceInfo(
                        name = name,
                        address = result.device.address,
                        link = BluetoothLink.LE,
                        bonded = bonded,
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                onFinished()
            }
        }

        return runCatching {
            scanner = leScanner
            scanCallback = callback
            leScanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
            true
        }.getOrElse {
            scanCallback = null
            scanner = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(callback) }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    suspend fun open(address: String): Pm3Port = withContext(Dispatchers.IO) {
        val current = adapter ?: throw IOException("Bluetooth non supportato")
        if (!BluetoothPermissions.has(context, scan = false)) {
            throw IOException("Permesso Bluetooth mancante")
        }
        if (!current.isEnabled) throw IOException("Bluetooth spento")
        if (!BluetoothAddress.isValid(address)) {
            throw IOException("Indirizzo Bluetooth non valido")
        }

        val device = try {
            current.getRemoteDevice(BluetoothAddress.normalize(address))
        } catch (t: IllegalArgumentException) {
            throw IOException("Indirizzo Bluetooth non valido", t)
        }

        val link = Link(
            context = context,
            device = device,
            onLost = { reason -> onLinkLost?.invoke(reason) },
            adapterEnabled = { isAdapterEnabled() },
        )
        activeLink = link
        // Poll this job inside the blocking GATT waits: a cancelled open()
        // must drop the connection instead of leaking it until the timeout.
        val job = coroutineContext[Job]
        try {
            link.connect { job?.isActive ?: true }
            val port = link.port()
            // Last cancellation check before handing the port out: a cancelled
            // caller must not receive (and forget) an open GATT link.
            coroutineContext.ensureActive()
            port
        } catch (t: LinkCancelledException) {
            cancelConnect()
            coroutineContext.ensureActive()
            throw IOException("Connessione annullata", t)
        } catch (t: CancellationException) {
            cancelConnect()
            throw t
        } catch (t: Throwable) {
            link.close()
            if (t is IOException) throw t
            throw IOException(t.message ?: "Connessione BLE non riuscita", t)
        } finally {
            if (activeLink === link) activeLink = null
        }
    }

    /** Aborts an in-flight [open] before the port exists. */
    fun cancelConnect() {
        activeLink?.close()
    }

    private fun looksLikeProxmark(name: String?): Boolean {
        val value = name?.uppercase().orEmpty()
        return value.contains("PROXMARK") || value.contains("PM5") || value.contains("PM3")
    }

    /** Adapter state without throwing when BLUETOOTH_CONNECT is gone. */
    private fun isAdapterEnabled(): Boolean =
        runCatching { adapter?.isEnabled == true }.getOrDefault(true)

    // Every GATT call is gated by the permission check in open().
    @SuppressLint("MissingPermission")
    private class Link(
        private val context: Context,
        private val device: BluetoothDevice,
        private val onLost: (String) -> Unit,
        private val adapterEnabled: () -> Boolean,
    ) : BluetoothGattCallback() {

        private val connected = CountDownLatch(1)
        private val services = CountDownLatch(1)
        private val subscribed = CountDownLatch(1)
        private val mtuDone = CountDownLatch(1)

        private val rx = BleByteBuffer()
        private val tx = BleByteBuffer()

        @Volatile
        private var gatt: BluetoothGatt? = null

        @Volatile
        private var characteristic: BluetoothGattCharacteristic? = null

        @Volatile
        private var descriptor: BluetoothGattDescriptor? = null

        @Volatile
        private var mtu = DEFAULT_MTU

        @Volatile
        private var error: String? = null

        @Volatile
        private var closed = false

        /** Set once open() completed: only an established link is "lost". */
        @Volatile
        private var established = false

        /** close() requested by the app: never reported through [onLost]. */
        @Volatile
        private var userClosed = false

        private val linkLostReported = AtomicBoolean(false)

        @Volatile
        private var writeLatch: CountDownLatch? = null

        @Volatile
        private var writeStatus = Int.MIN_VALUE

        private var writer: Thread? = null

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        fun connect(isActive: () -> Boolean) {
            val connection = device.connectGatt(
                context,
                false,
                this,
                BluetoothDevice.TRANSPORT_LE,
            ) ?: throw IOException("connectGatt non disponibile")
            gatt = connection

            await(connected, CONNECT_TIMEOUT_MS, "connessione BLE", isActive)

            if (!connection.discoverServices()) {
                throw IOException("Scoperta servizi BLE non avviata")
            }
            await(services, SERVICES_TIMEOUT_MS, "scoperta servizi BLE", isActive)

            val characteristic = characteristic
                ?: throw IOException("Caratteristica BLE non trovata")
            requestMtu(connection, isActive)
            subscribe(connection, characteristic, isActive)
            // Mark the link before the tx thread starts: a failure there is a
            // real link loss and must reach the listener.
            established = true
            startWriter()
            if (closed) throw IOException(error ?: "Connessione BLE chiusa")
        }

        fun port(): Pm3Port {
            val input = rx.inputStream()
            val output = tx.outputStream()
            return object : Pm3Port {
                override fun streams() = input to output
                override fun close() {
                    this@Link.close()
                }
            }
        }

        private fun requestMtu(connection: BluetoothGatt, isActive: () -> Boolean) {
            runCatching { connection.requestMtu(PREFERRED_MTU) }
            // A refused or unanswered MTU request is not fatal: the default
            // 23-byte ATT MTU still works, just with smaller chunks.
            if (awaitCountDown(mtuDone, MTU_TIMEOUT_MS, isActive)) {
                error?.let { throw IOException(it) }
            }
        }

        private fun subscribe(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            isActive: () -> Boolean,
        ) {
            if (!connection.setCharacteristicNotification(characteristic, true)) {
                throw IOException("Notifiche BLE non attivabili")
            }
            val descriptor = descriptor
                ?: throw IOException("Descrittore CCCD non trovato")

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                connection.writeDescriptor(descriptor)
            }
            if (!started) throw IOException("Attivazione notifiche BLE rifiutata")
            await(subscribed, SUBSCRIBE_TIMEOUT_MS, "attivazione notifiche BLE", isActive)
        }

        private fun startWriter() {
            writer = thread(name = "pm3-ble-tx", isDaemon = true) {
                val buffer = ByteArray(PREFERRED_MTU)
                try {
                    while (!closed) {
                        val chunkSize = maxOf(20, mtu - 3)
                        val read = tx.read(buffer, 0, minOf(buffer.size, chunkSize))
                        if (read < 0) break
                        if (read > 0) writeChunk(buffer.copyOf(read))
                    }
                } catch (t: Throwable) {
                    // Closing the link makes the pending read fail, so the
                    // client reports the broken transport instead of hanging.
                    // Keep the reason and announce it: a silent stop looked
                    // like a healthy but idle link.
                    if (!closed) {
                        val reason = t.message?.takeIf { it.isNotBlank() }
                            ?: "Trasmissione BLE interrotta"
                        error = reason
                        reportLinkLost(reason)
                        runCatching { closeInternal() }
                    }
                }
            }
        }

        private fun writeChunk(data: ByteArray) {
            val connection = gatt ?: throw IOException("GATT non connesso")
            val characteristic = characteristic
                ?: throw IOException("Caratteristica BLE assente")

            repeat(WRITE_RETRIES) {
                if (closed) throw IOException("Connessione BLE chiusa")
                val latch = CountDownLatch(1)
                writeStatus = Int.MIN_VALUE
                writeLatch = latch

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    connection.writeCharacteristic(
                        characteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    connection.writeCharacteristic(characteristic)
                }

                if (!started) {
                    writeLatch = null
                    Thread.sleep(5)
                    return@repeat
                }

                if (!latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    writeLatch = null
                    throw IOException("Timeout scrittura BLE")
                }
                writeLatch = null
                if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("Scrittura BLE fallita ($writeStatus)")
                }
                return
            }
            throw IOException("Scrittura BLE rifiutata")
        }

        private fun await(
            latch: CountDownLatch,
            timeoutMs: Long,
            label: String,
            isActive: () -> Boolean,
        ) {
            if (!awaitCountDown(latch, timeoutMs, isActive)) {
                throw IOException("Timeout $label")
            }
            error?.let { throw IOException(it) }
            if (closed) throw IOException("Connessione BLE chiusa")
        }

        /**
         * Waits for [latch] in short slices so a cancelled open() is noticed
         * long before the GATT timeout. Returns false on timeout.
         */
        private fun awaitCountDown(
            latch: CountDownLatch,
            timeoutMs: Long,
            isActive: () -> Boolean,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!latch.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!isActive()) throw LinkCancelledException()
                if (System.nanoTime() >= deadline) return false
            }
            return true
        }

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            this.gatt = gatt
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val reason = "Errore GATT ($status)"
                error = reason
                failAll()
                // Also release the GATT: the waiting handshake has to fail
                // and a failed status must not leak the client.
                reportLinkLost(reason)
                closeInternal()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.countDown()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val adapterOff = runCatching { !adapterEnabled() }.getOrDefault(false)
                    val reason = if (adapterOff) {
                        "Bluetooth spento"
                    } else {
                        "Dispositivo BLE disconnesso"
                    }
                    error = reason
                    failAll()
                    reportLinkLost(reason)
                    closeInternal()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error = "Scoperta servizi BLE fallita ($status)"
                failAll()
                return
            }
            val value = gatt.getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)
            if (value == null) {
                error = "Servizio BLE del Proxmark5 non trovato"
                failAll()
                return
            }
            characteristic = value
            descriptor = value.getDescriptor(CCCD_UUID)
            services.countDown()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribed.countDown()
            } else {
                error = "Attivazione notifiche BLE fallita ($status)"
                failAll()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this.mtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                maxOf(DEFAULT_MTU, mtu)
            } else {
                DEFAULT_MTU
            }
            mtuDone.countDown()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return
            writeStatus = status
            writeLatch?.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID || closed) return
            // The GATT callback must return at once: a full buffer drops the
            // chunk and the reader reports the overflow.
            rx.offer(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != CHARACTERISTIC_UUID || closed) return
            val value = characteristic.value ?: return
            rx.offer(value)
        }

        /** App-initiated teardown: it is not a link loss. */
        fun close() {
            userClosed = true
            closeInternal()
        }

        private fun closeInternal() {
            if (closed) return
            closed = true
            rx.close()
            tx.close()
            writer?.interrupt()
            writer = null
            val connection = gatt
            gatt = null
            runCatching { connection?.disconnect() }
            runCatching { connection?.close() }
            failAll()
        }

        /** Announces a dropped established link exactly once. */
        private fun reportLinkLost(reason: String) {
            if (userClosed || !established) return
            if (linkLostReported.compareAndSet(false, true)) {
                runCatching { onLost(reason) }
            }
        }

        private fun failAll() {
            connected.countDown()
            services.countDown()
            subscribed.countDown()
            mtuDone.countDown()
        }
    }
}
