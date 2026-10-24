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
    }

    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var deviceInput: InputStream? = null
    private var deviceOutput: OutputStream? = null

    /** Counted down once accept() is reached, see the class contract. */
    private val acceptStarted = CountDownLatch(1)
    private var acceptThread: Thread? = null
    private var running = false

    /**
     * Starts listening and begins forwarding as soon as the client connects.
     * The streams stay owned by the caller: [stop] must not close them.
     */
    @Throws(Exception::class)
    fun start(deviceInput: InputStream, deviceOutput: OutputStream) {
        check(server == null) { "already started" }
        this.deviceInput = deviceInput
        this.deviceOutput = deviceOutput
        running = true
        // Bind before acceptLoop() runs: the loop returns as soon as server
        // is null, and without this the native client gets ECONNREFUSED.
        server = LocalServerSocket(socketName)
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
            val server = server ?: break
            val socket = try {
                server.accept()
            } catch (ignored: Exception) {
                break
            }
            val previous = client
            if (previous != null) {
                try {
                    previous.close()
                } catch (ignored: Exception) {
                }
            }
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
            }
        } catch (ignored: Exception) {
        }
    }

    /** Closes the listener and any live client connection. */
    fun stop() {
        running = false
        try {
            client?.close()
            client = null
            server?.close()
            server = null
        } catch (ignored: Exception) {
        }
        // The USB reader may be blocked inside bulkTransfer(): closing the
        // caller side stream is what unblocks it.
    }
}
