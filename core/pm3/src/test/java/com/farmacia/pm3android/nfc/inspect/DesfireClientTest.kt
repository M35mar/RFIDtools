// DESFire peeling: the wrapped APDU framing, the additional-frame drain and the
// free commands the inspector can call without a key.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DesfireClientTest {

    @Test
    fun `get version drains the additional frames`() {
        val transport = FakeTransport(
            frame(
                intArrayOf(
                    0x04, 0x01, 0x02, 0x01, 0x03, 0x19, 0x05,
                    0x04, 0x01, 0x02, 0x02, 0x00, 0x19, 0x05,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00,
                ),
                0x91AF,
            ),
            frame(intArrayOf(0x00, 0x00, 0x00, 0x00), 0x9100),
        )

        val version = DesfireClient(transport).getVersion()!!

        assertEquals("MIFARE DESFire EV2 4 KB", version.label)
        assertEquals(4096, version.storageBytes)
        assertEquals(2, version.softwareMajor)
        assertEquals(0, version.softwareMinor)
        assertEquals(2, transport.sent.size)
        assertArrayEquals(CALL_VERSION, transport.sent[0])
        assertArrayEquals(CALL_ADDITIONAL, transport.sent[1])
    }

    @Test
    fun `application ids are split in three byte groups`() {
        val transport = FakeTransport(
            frame(intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01), 0x9100),
        )

        assertEquals(listOf(0, 1), DesfireClient(transport).getApplicationIds())
        assertArrayEquals(CALL_APPLICATIONS, transport.sent[0])
    }

    @Test
    fun `application ids can span several frames`() {
        val transport = FakeTransport(
            frame(intArrayOf(0x00, 0x00, 0x00), 0x91AF),
            frame(intArrayOf(0x00, 0x00, 0x01), 0x9100),
        )

        assertEquals(listOf(0, 1), DesfireClient(transport).getApplicationIds())
    }

    @Test
    fun `a card without applications answers an empty list`() {
        val transport = FakeTransport(frame(intArrayOf(), 0x9100))

        assertTrue(DesfireClient(transport).getApplicationIds().isEmpty())
    }

    @Test
    fun `an illegal command surfaces its status byte`() {
        val transport = FakeTransport(frame(intArrayOf(), 0x911C))

        try {
            DesfireClient(transport).getDfNames()
            fail("expected DesfireException")
        } catch (error: DesfireException) {
            assertEquals(0x1C, error.statusByte)
        }
    }

    @Test
    fun `a short version answer is rejected`() {
        val transport = FakeTransport(frame(intArrayOf(0x04, 0x01, 0x02), 0x9100))

        assertNull(DesfireClient(transport).getVersion())
    }

    private class FakeTransport(vararg answers: ByteArray) : IsoDepTransport {

        private val answers = answers.toList()

        val sent = mutableListOf<ByteArray>()

        private var index = 0

        override fun transceive(command: ByteArray): ByteArray {
            sent += command.copyOf()
            if (index >= answers.size) {
                throw IllegalStateException("no more fake answers")
            }
            return answers[index++]
        }
    }

    private companion object {
        val CALL_VERSION = byteArrayOf(0x90.toByte(), 0x60, 0x00, 0x00, 0x00, 0x00)
        val CALL_ADDITIONAL = byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00, 0x00)
        val CALL_APPLICATIONS = byteArrayOf(0x90.toByte(), 0x6A, 0x00, 0x00, 0x00, 0x00)

        fun frame(data: IntArray, status: Int): ByteArray {
            val out = ByteArray(data.size + 2)
            for (index in data.indices) out[index] = data[index].toByte()
            out[data.size] = (status shr 8).toByte()
            out[data.size + 1] = status.toByte()
            return out
        }
    }
}
