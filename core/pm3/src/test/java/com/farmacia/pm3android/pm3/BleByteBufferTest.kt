// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class BleByteBufferTest {

    @Test
    fun `queued bytes are read back in order`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        buffer.write(byteArrayOf(1, 2, 3))
        val out = ByteArray(3)
        assertEquals(3, buffer.read(out, 0, out.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
    }

    @Test
    fun `a read can span several written chunks`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        buffer.write(byteArrayOf(1, 2, 3, 4))
        buffer.write(byteArrayOf(5, 6, 7, 8))
        val out = ByteArray(6)
        assertEquals(6, buffer.read(out, 0, out.size))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)

        val rest = ByteArray(2)
        assertEquals(2, buffer.read(rest, 0, rest.size))
        assertArrayEquals(byteArrayOf(7, 8), rest)
    }

    @Test
    fun `a read larger than the queued data returns what is available`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        buffer.write(byteArrayOf(9, 10))
        val out = ByteArray(8)
        assertEquals(2, buffer.read(out, 0, out.size))
        assertEquals(9.toByte(), out[0])
        assertEquals(10.toByte(), out[1])
    }

    @Test
    fun `closing the buffer ends a blocked read`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        val started = CountDownLatch(1)
        var result = Int.MIN_VALUE
        val reader = thread {
            started.countDown()
            result = buffer.read(ByteArray(4), 0, 4)
        }
        started.await()
        buffer.close()
        reader.join(2_000)
        assertEquals(-1, result)
    }

    @Test
    fun `closing the buffer discards queued data and rejects writes`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        buffer.write(byteArrayOf(1, 2, 3))
        buffer.close()
        assertEquals(-1, buffer.read(ByteArray(3), 0, 3))

        assertThrows(IOException::class.java) {
            buffer.write(byteArrayOf(4))
        }
    }

    @Test
    fun `stream wrappers share the same queue`() {
        val buffer = BleByteBuffer(capacityBytes = 64)
        val output = buffer.outputStream()
        val input = buffer.inputStream()

        output.write(byteArrayOf(11, 12, 13))
        output.flush()

        val out = ByteArray(3)
        assertEquals(3, input.read(out))
        assertArrayEquals(byteArrayOf(11, 12, 13), out)

        output.close()
        assertEquals(-1, input.read())
    }
}
