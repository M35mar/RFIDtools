// Forwards the Java-side USB streams to the abstract-namespace unix socket the
// Proxmark3 client connects to with its "socket:" transport (uart_posix.c).
//
// Ordering contract: start() only returns once accept() is (or is about to be)
// reached, because the native client connect() blocks waiting for the other
// end and would fail with ECONNREFUSED otherwise.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalSocketBridge(private val socketName: String) {

    companion object {
        private const val DEVICE_READ_BUFFER = 16 * 1024
        private const val SOCKET_READ_BUFFER = 4096
        private const val ACCEPT_WAIT_MS = 3000L
        private const val JOIN_WAIT_MS = 500L
    }

    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var deviceInput: InputStream? = null
    private var deviceOutput: OutputStream? = null

    @Volatile
    private var running = false

    /** Counted down once accept() is reached, see the class contract. */
    private val acceptStarted = CountDownLatch(1)
    private var acceptThread: Thread? = null

    /**
     * Starts listening and begins forwarding as soon as the client connects.
     * The streams are closed by [stop] as well: they belong to this transport.
     */
    @Throws(Exception::class)
    fun start(deviceInput: InputStream, deviceOutput: OutputStream) {
        check(server == null) { "already started" }
        this.deviceInput = deviceInput
        this.deviceOutput = deviceOutput
        running = true
        server = try {
            LocalServerSocket(socketName)
        } catch (t: Throwable) {
            running = false
            this.deviceInput = null
            this.deviceOutput = null
            throw t
        }
        acceptThread = thread(name = "pm3-bridge-accept") { acceptLoop() }
        if (!acceptStarted.await(ACCEPT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            stop()
            throw IllegalStateException("could not start the $socketName listener")
        }
    }

    private fun acceptLoop() {
        acceptStarted.countDown()
        // Only one client (the client's own session) is expected; a stray or
        // stale late connection just replaces the previous one.
        while (running) {
            val activeServer = server ?: break
            val socket = try {
                activeServer.accept()
            } catch (ignored: Exception) {
                break
            }
            if (!running) {
                runCatching { socket.close() }
                break
            }
            runCatching { client?.close() }
            client = socket
            val socketInput = socket.inputStream
            val socketOutput = socket.outputStream
            thread(name = "pm3-bridge-socket-in") { pumpSocketToUsb(socketInput) }
            thread(name = "pm3-bridge-usb-out") { pumpUsbToSocket(socketOutput) }
        }
    }

    /** Client commands: socket → USB. */
    private fun pumpSocketToUsb(socketInput: InputStream) {
        val buffer = ByteArray(SOCKET_READ_BUFFER)
        try {
            while (running) {
                val length = socketInput.read(buffer)
                if (length > 0) {
                    deviceOutput?.write(buffer, 0, length)
                    deviceOutput?.flush()
                }
                if (length < 0) {
                    break
                }
            }
        } catch (ignored: Exception) {
            // Endpoint or client is gone: the owning connection handles it.
        }
    }

    /** PM3 responses: USB → socket. */
    private fun pumpUsbToSocket(socketOutput: OutputStream) {
        val buffer = ByteArray(DEVICE_READ_BUFFER)
        try {
            while (running) {
                val length = deviceInput?.read(buffer) ?: break
                if (length > 0) {
                    socketOutput.write(buffer, 0, length)
                    socketOutput.flush()
                }
                if (length < 0) {
                    break
                }
            }
        } catch (ignored: Exception) {
        }
    }

    /** Closes the listener, the client connection and both transport streams. */
    fun stop() {
        running = false
        runCatching { client?.close() }
        client = null
        runCatching { server?.close() }
        server = null
        runCatching { deviceInput?.close() }
        deviceInput = null
        runCatching { deviceOutput?.close() }
        deviceOutput = null
        val thread = acceptThread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(JOIN_WAIT_MS) }
        }
        acceptThread = null
    }
}
