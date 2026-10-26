// Direct CDC-ACM driver for the Proxmark3 over the Android USB host API.
//
// No serial-port library and no control transfers: the PM3 firmware implements
// CDC as a plain pair of bulk endpoints on one interface, so the whole driver
// is claim + bulkTransfer in both directions.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class UsbCdc(private val context: Context) {

    companion object {
        // From the firmware descriptors (common_arm/usb/usb_cdc_desc.c).
        private const val VENDOR_ID = 0x9ac4
        private const val PRODUCT_ID = 0x4b8f

        // bulkTransfer() may truncate larger requests.
        private const val MAX_TRANSFER_BYTES = 16 * 1024

        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 1000
        private const val WRITE_MAX_RETRIES = 100

        private const val ACTION_PERMISSION = "com.farmacia.pm3android.USB_PERMISSION"
    }

    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)

    fun isAvailable(): Boolean = usbManager != null

    /** The PM3 attached to the phone, or null. */
    fun findDevice(): UsbDevice? {
        return usbManager?.deviceList?.values?.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
        }
    }

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) == true

    /** Human-readable USB descriptor used by the diagnostics card. */
    fun describe(device: UsbDevice): String {
        val builder = StringBuilder()
        builder.appendLine("deviceName: ${device.deviceName}")
        builder.appendLine("vendorId: 0x%04X (%d)".format(device.vendorId, device.vendorId))
        builder.appendLine("productId: 0x%04X (%d)".format(device.productId, device.productId))
        builder.appendLine("deviceId: ${device.deviceId}")
        builder.appendLine("manufacturer: ${device.manufacturerName ?: "-"}")
        builder.appendLine("product: ${device.productName ?: "-"}")
        builder.appendLine(
            "serial: " + runCatching { device.serialNumber }.getOrNull().orEmpty().ifBlank { "-" },
        )
        builder.appendLine("deviceClass: ${device.deviceClass}")
        builder.appendLine("interfaces: ${device.interfaceCount}")
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            builder.appendLine(
                "  [$index] class=${iface.interfaceClass} " +
                    "subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} " +
                    "endpoints=${iface.endpointCount}",
            )
            for (endpointIndex in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(endpointIndex)
                builder.appendLine(
                    "    endpoint[$endpointIndex] " +
                        "address=0x%02X ".format(endpoint.address) +
                        "type=${endpointTypeName(endpoint.type)} " +
                        "dir=${if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}",
                )
            }
        }
        return builder.toString().trimEnd()
    }

    /** Shows the system permission dialog and waits for the answer. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) {
                        return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resume(
                            intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false,
                            ),
                        )
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
            manager.requestPermission(device, pendingIntent)
        }
    }

    /**
     * Claims the first interface providing a bulk in/out endpoint pair and
     * returns a handle serving the bidirectional streams the bridge needs.
     */
    fun open(device: UsbDevice): OpenedPort? {
        val manager = usbManager ?: return null
        val connection = manager.openDevice(device) ?: return null
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> input = endpoint
                        UsbConstants.USB_DIR_OUT -> output = endpoint
                    }
                }
            }
            if (input != null && output != null) {
                // true: reset the interface state, in case another process
                // (or a previous run) left a transfer pending.
                if (connection.claimInterface(iface, true)) {
                    return OpenedPort(connection, iface, input, output) {
                        manager.deviceList.values.any { it.deviceId == device.deviceId }
                    }
                }
            }
        }
        connection.close()
        return null
    }

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "unknown($type)"
    }

    inner class OpenedPort(
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val inputEndpoint: UsbEndpoint,
        private val outputEndpoint: UsbEndpoint,
        private val isAttached: () -> Boolean,
    ) {

        @Volatile
        private var closed = false

        private val inputStream = Input()
        private val outputStream = Output()

        fun streams(): Pair<InputStream, OutputStream> = inputStream to outputStream

        fun close() {
            if (closed) return
            closed = true
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
        }

        /**
         * Blocking stream from the PM3. A read returns at the first USB
         * packet; timeouts (device silent) keep waiting, a detached device is
         * reported immediately so the bridge can stop instead of spinning.
         */
        private inner class Input : InputStream() {

            private val staging = ByteArray(MAX_TRANSFER_BYTES)

            override fun read(): Int {
                val one = ByteArray(1)
                if (read(one, 0, 1) < 0) {
                    return -1
                }
                return one[0].toInt() and 0xFF
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) {
                    return 0
                }
                while (true) {
                    if (closed) {
                        throw IOException("USB port closed")
                    }
                    if (!isAttached()) {
                        throw IOException("USB device detached")
                    }
                    val result = connection.bulkTransfer(
                        inputEndpoint,
                        staging,
                        minOf(length, staging.size),
                        READ_TIMEOUT_MS,
                    )
                    if (result > 0) {
                        System.arraycopy(staging, 0, buffer, offset, result)
                        return result
                    }
                    // result <= 0: silent device (timeout) or a transient
                    // controller error. Loop, but the attachment check above
                    // ends the stream as soon as the PM3 is unplugged.
                }
            }

            override fun available(): Int = 0

            override fun close() {
                closed = true
            }
        }

        /** Bulk out. bulkTransfer is synchronous, so writes block, not queue. */
        private inner class Output : OutputStream() {

            override fun write(byteValue: Int) {
                write(byteArrayOf(byteValue.toByte()))
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                var index = offset
                var retries = 0
                while (index < offset + length) {
                    if (closed) {
                        throw IOException("USB port closed")
                    }
                    if (!isAttached()) {
                        throw IOException("USB device detached")
                    }
                    val result = connection.bulkTransfer(
                        outputEndpoint,
                        buffer,
                        index,
                        minOf(offset + length - index, MAX_TRANSFER_BYTES),
                        WRITE_TIMEOUT_MS,
                    )
                    if (result > 0) {
                        index += result
                        retries = 0
                    } else {
                        // Endpoint busy or controller busy: brief back-off
                        // with a generous retry budget before the link dies.
                        retries++
                        if (retries > WRITE_MAX_RETRIES) {
                            throw IOException("USB write failed")
                        }
                        runCatching { Thread.sleep(2) }
                    }
                }
            }

            override fun flush() {
                // bulkTransfer is synchronous: nothing to flush.
            }

            override fun close() {
                closed = true
            }
        }
    }
}
