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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocalSocketBridge(private val socketName: String) {

    companion object {
        private const val DEVICE_READ_BUFFER = 16 * 1024
        private const val SOCKET_READ_BUFFER = 4096
        private const val ACCEPT_WAIT_MS = 3000L
        private const val JOIN_WAIT_MS = 500L
        private const val BIND_ATTEMPTS = 8
        private const val BIND_RETRY_MS = 150L
    }

    /**
     * Notified once per [start] when the client link dies (EOF or an I/O
     * failure), after the bridge has been torn down, so the owner can drop
     * the transport. A null cause is EOF. Runs on the failing pump thread.
     */
    @Volatile
    var onBridgeLost: ((Throwable?) -> Unit)? = null

    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var deviceInput: InputStream? = null
    private var deviceOutput: OutputStream? = null

    @Volatile
    private var running = false

    /**
     * Bumped by every start() and every accepted client: a pump that outlives
     * its connection must stop before it can touch the new one.
     */
    private val generation = AtomicInteger(0)

    private val lostReported = AtomicBoolean(false)

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
        lostReported.set(false)
        generation.incrementAndGet()
        // The abstract socket name is fixed and shared with the native client:
        // a previous listener that is still closing makes this bind fail with
        // "address already in use". Retry briefly instead of failing the connect.
        server = try {
            openServer()
        } catch (t: Throwable) {
            running = false
            this.deviceInput = null
            this.deviceOutput = null
            throw IllegalStateException(
                "cannot listen on $socketName: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
        acceptThread = thread(name = "pm3-bridge-accept") { acceptLoop() }
        if (!acceptStarted.await(ACCEPT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            stop()
            throw IllegalStateException("could not start the $socketName listener")
        }
    }

    private fun openServer(): LocalServerSocket {
        var last: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                return LocalServerSocket(socketName)
            } catch (t: Throwable) {
                last = t
                if (attempt < BIND_ATTEMPTS - 1) {
                    runCatching { Thread.sleep(BIND_RETRY_MS) }
                }
            }
        }
        throw last ?: IllegalStateException("cannot bind $socketName")
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
            // Invalidate the previous connection's pumps before its socket is
            // closed: their failure must not be mistaken for a bridge loss.
            val connectionGeneration = generation.incrementAndGet()
            runCatching { client?.close() }
            client = socket
            val socketInput: InputStream
            val socketOutput: OutputStream
            try {
                socketInput = socket.inputStream
                socketOutput = socket.outputStream
            } catch (t: Throwable) {
                runCatching { socket.close() }
                client = null
                continue
            }
            thread(name = "pm3-bridge-socket-in") {
                pumpSocketToUsb(socketInput, connectionGeneration)
            }
            thread(name = "pm3-bridge-usb-out") {
                pumpUsbToSocket(socketOutput, connectionGeneration)
            }
        }
    }

    private fun isCurrent(myGeneration: Int): Boolean =
        running && generation.get() == myGeneration

    /** Client commands: socket → USB. */
    private fun pumpSocketToUsb(socketInput: InputStream, myGeneration: Int) {
        val buffer = ByteArray(SOCKET_READ_BUFFER)
        try {
            while (true) {
                if (!isCurrent(myGeneration)) return
                val length = socketInput.read(buffer)
                if (!isCurrent(myGeneration)) return
                if (length < 0) break
                if (length > 0) {
                    deviceOutput?.write(buffer, 0, length)
                    deviceOutput?.flush()
                }
            }
            bridgeLost(myGeneration, null)
        } catch (t: Throwable) {
            bridgeLost(myGeneration, t)
        }
    }

    /** PM3 responses: USB → socket. */
    private fun pumpUsbToSocket(socketOutput: OutputStream, myGeneration: Int) {
        // Snapshot of the stream this pump was started for: a later start()
        // must not feed this pump bytes from a different device handle.
        val input = deviceInput ?: return
        val buffer = ByteArray(DEVICE_READ_BUFFER)
        try {
            while (true) {
                if (!isCurrent(myGeneration)) return
                val length = input.read(buffer)
                if (!isCurrent(myGeneration)) return
                if (length < 0) break
                if (length > 0) {
                    socketOutput.write(buffer, 0, length)
                    socketOutput.flush()
                }
            }
            bridgeLost(myGeneration, null)
        } catch (t: Throwable) {
            bridgeLost(myGeneration, t)
        }
    }

    /**
     * One pump ended (EOF or failure): close the client and the whole bridge.
     * A stale pump only exits; it must not tear down its successor.
     */
    private fun bridgeLost(myGeneration: Int, cause: Throwable?) {
        if (!isCurrent(myGeneration)) return
        runCatching { client?.close() }
        client = null
        // Tear the bridge down before notifying: a listener is then free to
        // reconnect on the same socket name from the callback.
        stop()
        if (lostReported.compareAndSet(false, true)) {
            runCatching { onBridgeLost?.invoke(cause) }
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
