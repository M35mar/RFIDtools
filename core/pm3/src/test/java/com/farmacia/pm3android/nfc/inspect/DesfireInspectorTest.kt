// DESFire discovery without keys: the inspector must keep the PICC data and
// the applications the card answers, and mark only the refused reads as locked.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesfireInspectorTest {

    @Test
    fun `picc and applications are read with the free commands`() {
        val transport = FakeDesfire()
        val details = DesfireInspector.read(DesfireClient(transport))!!

        assertEquals("MIFARE DESFire EV1 2 KB", details.version.label)
        assertNotNull(details.picc)
        assertEquals(1024, details.picc?.freeMemory)
        assertEquals(0x0F, details.picc?.keySettings)
        assertEquals(4, details.picc?.keyCount)
        assertEquals(0x00, details.picc?.keyVersion)
        assertFalse(details.picc?.locked == true)

        val application = details.applications?.single()!!
        assertEquals(0x123456, application.id)
        assertEquals("TEST", application.dfName?.toString(Charsets.ISO_8859_1))
        assertEquals(0x01, application.keySettings)
        assertEquals(2, application.keyCount)
        assertEquals(listOf(1), application.fileIds)
        assertEquals(0x0002, application.isoFileIds?.single()?.isoId)
        assertEquals(1, application.isoFileIds?.single()?.fileId)

        val file = application.files?.single()!!
        assertEquals(DesfireFileType.STANDARD_DATA, file.type)
        assertEquals(32, file.sizeBytes)
        assertEquals(0xEEEE, file.accessRights)
        assertFalse(application.locked)
    }

    @Test
    fun `leaves the card on the picc level`() {
        val transport = FakeDesfire()

        DesfireInspector.read(DesfireClient(transport))

        assertEquals(0, transport.selected)
    }

    @Test
    fun `a refused application is marked as locked`() {
        val transport = FakeDesfire(lockApplications = true)
        val details = DesfireInspector.read(DesfireClient(transport))!!

        val application = details.applications?.single()!!
        assertEquals(0x123456, application.id)
        assertTrue(application.locked)
        assertNull(application.files)
        assertEquals(0, transport.selected)
    }

    @Test
    fun `tag loss is rethrown instead of swallowed`() {
        val transport = FakeDesfire(failVersion = true)

        try {
            DesfireInspector.read(DesfireClient(transport)) { it is IllegalStateException }
            throw AssertionError("expected the tag loss to propagate")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    private class FakeDesfire(
        private val lockApplications: Boolean = false,
        private val failVersion: Boolean = false,
    ) : IsoDepTransport {

        var selected = 0
            private set

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            val lc = command[4].toInt() and 0xFF
            val data = command.copyOfRange(5, 5 + lc)
            return when (ins) {
                0x60 -> if (failVersion) {
                    throw IllegalStateException("tag lost")
                } else {
                    frame(VERSION, 0x9100)
                }

                0x5A -> {
                    val id = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16)
                    if (id != 0 && lockApplications) {
                        frame(intArrayOf(), 0x91AE)
                    } else {
                        selected = id
                        frame(intArrayOf(), 0x9100)
                    }
                }

                0x45 -> frame(
                    intArrayOf(
                        if (selected == 0) 0x0F else 0x01,
                        if (selected == 0) 0x03 else 0x01,
                    ),
                    0x9100,
                )

                0x6E -> frame(intArrayOf(0x00, 0x04, 0x00), 0x9100)
                0x64 -> frame(intArrayOf(0x00), 0x9100)
                0x6A -> if (selected == 0) {
                    frame(intArrayOf(0x56, 0x34, 0x12), 0x9100)
                } else {
                    frame(intArrayOf(), 0x9100)
                }

                0x6D -> frame(
                    intArrayOf(0x56, 0x34, 0x12, 0x04, 'T'.code, 'E'.code, 'S'.code, 'T'.code),
                    0x9100,
                )

                0x6F -> frame(intArrayOf(0x01), 0x9100)
                0x61 -> frame(intArrayOf(0x00, 0x02, 0x01), 0x9100)
                0xF5 -> frame(
                    intArrayOf(0x00, 0x00, 0xEE, 0xEE, 0x00, 0x00, 0x20),
                    0x9100,
                )

                else -> frame(intArrayOf(), 0x91AE)
            }
        }
    }

    private companion object {
        val VERSION = intArrayOf(
            0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03,
            0x04, 0x01, 0x01, 0x12, 0x34, 0x18, 0x03,
            0x00, 0x00, 0x00, 0x00, 0x00,
        )

        fun frame(data: IntArray, status: Int): ByteArray {
            val out = ByteArray(data.size + 2)
            for (index in data.indices) out[index] = data[index].toByte()
            out[data.size] = (status shr 8).toByte()
            out[data.size + 1] = status.toByte()
            return out
        }
    }
}
