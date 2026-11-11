// Bounded byte queue used to turn the callback-driven BLE GATT profile into
// the blocking InputStream/OutputStream pair the transport bridge expects.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BleByteBuffer(private val capacityBytes: Int = 512 * 1024) {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var sizeBytes = 0

    @Volatile
    private var closed = false

    /**
     * Set when a non-blocking [offer] had to drop data. The reader throws on
     * its next read: a byte pipe with a hole cannot be resynchronised.
     */
    @Volatile
    var overflowed: Boolean = false
        private set

    fun write(data: ByteArray) {
        var index = 0
        while (index < data.size) {
            lock.withLock {
                while (sizeBytes >= capacityBytes && !closed) {
                    notFull.await()
                }
                if (closed) {
                    throw IOException("buffer closed")
                }
                val take = minOf(data.size - index, capacityBytes - sizeBytes)
                chunks.addLast(data.copyOfRange(index, index + take))
                sizeBytes += take
                index += take
                notEmpty.signalAll()
            }
        }
    }

    /**
     * Non-blocking write used from GATT callbacks: drops the whole chunk and
     * flags [overflowed] instead of waiting for the reader. Returns false when
     * the data was dropped or the buffer is closed.
     */
    fun offer(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        lock.withLock {
            if (closed) return false
            if (sizeBytes + data.size > capacityBytes) {
                overflowed = true
                return false
            }
            chunks.addLast(data.copyOf())
            sizeBytes += data.size
            notEmpty.signalAll()
            return true
        }
    }

    /** Blocks until data is available, the buffer is closed, or [length] is read. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (overflowed) {
            throw IOException("buffer overflow: data was dropped")
        }
        lock.withLock {
            while (sizeBytes == 0 && !closed) {
                notEmpty.await()
            }
            if (sizeBytes == 0) {
                return -1
            }
            var remaining = minOf(length, sizeBytes)
            val read = remaining
            var written = 0
            while (remaining > 0) {
                val head = chunks.first()
                val available = head.size - headOffset
                val take = minOf(available, remaining)
                System.arraycopy(head, headOffset, buffer, offset + written, take)
                headOffset += take
                sizeBytes -= take
                written += take
                remaining -= take
                if (headOffset == head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            notFull.signalAll()
            return read
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            chunks.clear()
            headOffset = 0
            sizeBytes = 0
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    fun inputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            this@BleByteBuffer.read(buffer, offset, length)

        override fun available(): Int = 0

        override fun close() {
            this@BleByteBuffer.close()
        }
    }

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(byteValue: Int) {
            write(byteArrayOf(byteValue.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            this@BleByteBuffer.write(buffer.copyOfRange(offset, offset + length))
        }

        override fun close() {
            this@BleByteBuffer.close()
        }
    }
}
