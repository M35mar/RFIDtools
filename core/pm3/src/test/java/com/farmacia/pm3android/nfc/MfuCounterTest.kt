// EV1 counter engine against an in-memory tag. The fake answers the same
// frames as MF0UL11, so the delta computation, password authentication,
// tearing check and read-back verification are exercised without hardware.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MfuCounterTest {

    @Test
    fun `target above current increments by the delta and verifies`() {
        val tag = FakeCounterTag(counters = mutableListOf(10, 0, 0))

        val change = MfuCounterTool(tag).setCounter(0, 1337, null)

        assertEquals(1337, tag.counters[0])
        assertEquals(1327, tag.lastDelta)
        assertEquals(10, change.previous)
        assertEquals(1337, change.value)
        assertTrue(change.written)
        assertEquals(6, tag.lastIncrementCommandSize)
        assertTrue(change.tagLabel.contains("Ultralight EV1"))
    }

    @Test
    fun `equal target does not send increment`() {
        val tag = FakeCounterTag(counters = mutableListOf(10, 0, 0))

        val change = MfuCounterTool(tag).setCounter(0, 10, null)

        assertEquals(0, tag.increments)
        assertFalse(change.written)
        assertEquals(10, change.value)
    }

    @Test
    fun `lower target is refused`() {
        val tag = FakeCounterTag(counters = mutableListOf(10, 0, 0))

        try {
            MfuCounterTool(tag).setCounter(0, 5, null)
            fail("expected MfuCounterTargetException")
        } catch (e: MfuCounterTargetException) {
            assertEquals(10, e.current)
            assertEquals(5, e.target)
        }
        assertEquals(0, tag.increments)
    }

    @Test
    fun `NTAG is refused`() {
        val tag = FakeCounterTag(version = ntagVersion())

        try {
            MfuCounterTool(tag).setCounter(0, 10, null)
            fail("expected MfuCounterUnsupportedException")
        } catch (_: MfuCounterUnsupportedException) {
            // expected
        }
    }

    @Test
    fun `tearing state blocks the write`() {
        val tag = FakeCounterTag(
            counters = mutableListOf(10, 0, 0),
            tearing = 0x00,
        )

        try {
            MfuCounterTool(tag).setCounter(0, 20, null)
            fail("expected MfuCounterTearingException")
        } catch (_: MfuCounterTearingException) {
            // expected
        }
        assertEquals(0, tag.increments)
    }

    @Test
    fun `unknown tearing is reported but the write proceeds`() {
        val tag = FakeCounterTag(
            counters = mutableListOf(10, 0, 0),
            tearing = null,
        )

        val change = MfuCounterTool(tag).setCounter(0, 20, null)

        assertTrue(change.written)
        assertTrue(change.tearingUnknown)
        assertEquals(20, tag.counters[0])
    }

    @Test
    fun `tearing command failure is non blocking`() {
        val tag = FakeCounterTag(
            counters = mutableListOf(10, 0, 0),
            tearingFailure = true,
        )

        val change = MfuCounterTool(tag).setCounter(0, 20, null)

        assertTrue(change.written)
        assertTrue(change.tearingUnknown)
        assertEquals(20, tag.counters[0])
    }

    @Test
    fun `rejected increment reports the increment step`() {
        val tag = FakeCounterTag(
            counters = mutableListOf(10, 0, 0),
            rejectIncrement = true,
        )

        try {
            MfuCounterTool(tag).setCounter(0, 20, null)
            fail("expected MfuCounterIoException")
        } catch (e: MfuCounterIoException) {
            assertEquals(MfuCounterStep.INCREMENT, e.step)
        }
    }

    @Test
    fun `password is sent and a wrong one is refused`() {
        val password = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val tag = FakeCounterTag(
            counters = mutableListOf(0, 0, 0),
            password = password,
            requireAuth = true,
        )

        try {
            MfuCounterTool(tag).setCounter(0, 7, byteArrayOf(1, 2, 3, 4))
            fail("expected TagAuthException")
        } catch (_: TagAuthException) {
            // expected
        }
        assertEquals(0, tag.increments)

        val change = MfuCounterTool(tag).setCounter(0, 7, password)
        assertTrue(change.written)
        assertEquals(1, tag.authCount)
    }

    @Test
    fun `missing password is reported as authentication needed`() {
        val tag = FakeCounterTag(requireAuth = true)

        try {
            MfuCounterTool(tag).setCounter(0, 7, null)
            fail("expected TagAuthException")
        } catch (_: TagAuthException) {
            // expected
        }
    }

    @Test
    fun `unsupported counter slot is refused`() {
        val tag = FakeCounterTag(counters = mutableListOf(0xFFFFFF, 0, 0))

        try {
            MfuCounterTool(tag).setCounter(0, 7, null)
            fail("expected MfuCounterNotSupportedException")
        } catch (_: MfuCounterNotSupportedException) {
            // expected
        }
    }

    @Test
    fun `tag that ignores increment fails verification`() {
        val tag = FakeCounterTag(
            counters = mutableListOf(10, 0, 0),
            acceptIncrement = false,
        )

        try {
            MfuCounterTool(tag).setCounter(0, 20, null)
            fail("expected MfuCounterVerifyException")
        } catch (e: MfuCounterVerifyException) {
            assertEquals(20, e.expected)
            assertEquals(10, e.actual)
        }
    }
}

private fun ntagVersion() = byteArrayOf(
    0x00, 0x04, 0x04, 0x02, 0x01, 0x01, 0x0F, 0x03,
)

private fun ev1Version() = byteArrayOf(
    0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03,
)

/**
 * MF0UL11 fake: GET_VERSION, optional PWD_AUTH, three READ_CNT slots, the
 * tearing flag and INCREMENT_CNT with a three-byte delta.
 */
private class FakeCounterTag(
    private val version: ByteArray = ev1Version(),
    val counters: MutableList<Int> = mutableListOf(0, 0, 0),
    private val password: ByteArray? = null,
    private val tearing: Byte? = 0xBD.toByte(),
    private val acceptIncrement: Boolean = true,
    private val requireAuth: Boolean = false,
    private val tearingFailure: Boolean = false,
    private val rejectIncrement: Boolean = false,
) : UltralightTransport {

    var increments = 0
    var lastDelta = 0
    var lastIncrementCommandSize = 0
    var authCount = 0
    private var authenticated = false

    override fun transceive(command: ByteArray): ByteArray {
        if (command.isEmpty()) throw IOException("comando vuoto")
        return when (command[0].toInt() and 0xFF) {
            0x60 -> version

            0x1B -> {
                val sent = command.copyOfRange(1, minOf(5, command.size))
                if (password != null && sent.contentEquals(password)) {
                    authenticated = true
                    authCount++
                    byteArrayOf(0x00, 0x00)
                } else {
                    byteArrayOf()
                }
            }

            0x39 -> {
                if (requireAuth && !authenticated) throw IOException("autenticazione richiesta")
                val index = command[1].toInt() and 0xFF
                val value = counters[index]
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value ushr 8) and 0xFF).toByte(),
                    ((value ushr 16) and 0xFF).toByte(),
                )
            }

            0x3E -> when {
                tearingFailure -> throw IOException("READ_TEARING non supportato")
                tearing == null -> byteArrayOf()
                else -> byteArrayOf(tearing)
            }

            0xA5 -> {
                if (command.size != 6) {
                    throw IOException("INCREMENT_CNT frame di ${command.size} byte")
                }
                lastIncrementCommandSize = command.size
                if (requireAuth && !authenticated) throw IOException("autenticazione richiesta")
                if (rejectIncrement) throw IOException("INCREMENT_CNT rifiutato")
                increments++
                if (!acceptIncrement) return byteArrayOf(0x0A)
                val index = command[1].toInt() and 0xFF
                val delta = (command[2].toInt() and 0xFF) or
                    ((command[3].toInt() and 0xFF) shl 8) or
                    ((command[4].toInt() and 0xFF) shl 16)
                lastDelta = delta
                counters[index] += delta
                byteArrayOf(0x0A)
            }

            else -> throw IOException("comando inatteso")
        }
    }

    override fun close() = Unit
}
