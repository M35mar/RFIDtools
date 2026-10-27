// MIFARE Ultralight EV1 restore engine against an in-memory tag. The fake
// speaks the same ISO 14443-3A frames as the engine, so page ranges, the
// password cascade and the configuration order are all exercised for real.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MfuRestorerTest {

    @Test
    fun `user pages are written and pages 0 to 3 are never touched`() {
        val tag = FakeEv1(factoryPages(41))
        val dump = ev1Dump { it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44) }

        val report = MfuRestorer(tag).restore(dump)

        assertEquals(listOf(5), tag.writes)
        assertEquals(StepStatus.SKIPPED, report.step(0).status)
        assertEquals(StepNote.UID_KEPT, report.step(0).note)
        assertEquals(StepStatus.SKIPPED, report.step(2).status)
        assertEquals(StepNote.LOCK_BITS, report.step(2).note)
        assertEquals(StepStatus.SKIPPED, report.step(3).status)
        assertEquals(StepNote.OTP, report.step(3).note)
        assertEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44).toList(), tag.pages[5].toList())
    }

    @Test
    fun `restoring the same dump twice changes nothing the second time`() {
        val tag = FakeEv1(factoryPages(41))
        val dump = ev1Dump { it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44) }

        MfuRestorer(tag).restore(dump)
        tag.writes.clear()
        val second = MfuRestorer(tag).restore(dump)

        assertTrue(tag.writes.isEmpty())
        assertEquals(0, second.written)
        assertTrue(second.ok)
    }

    @Test
    fun `configuration is written after the user pages as PACK PWD CFG0 CFG1`() {
        val tag = FakeEv1(factoryPages(41))
        val dump = ev1Dump {
            it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            it[37] = byteArrayOf(0x04, 0x00, 0x00, 0x00) // AUTH0 = 4
            it[38] = byteArrayOf(0x00, 0x05, 0x00, 0x00) // VCTID = 5
            it[39] = byteArrayOf(0x11, 0x22, 0x33, 0x44) // PWD
            it[40] = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00) // PACK
        }

        val report = MfuRestorer(tag).restore(dump)

        assertEquals(listOf(5, 40, 39, 37, 38), tag.writes)
        assertEquals(StepStatus.WRITTEN, report.step(39).status)
        assertEquals(StepStatus.WRITTEN, report.step(40).status)
        // The read-back uses the password that was just written.
        assertEquals(StepStatus.VERIFIED, report.step(37).status)
        assertEquals(StepStatus.VERIFIED, report.step(38).status)
        assertEquals(StepStatus.VERIFIED, report.step(5).status)
    }

    @Test
    fun `the current password is used when the dump does not carry one`() {
        val tagPages = factoryPages(41)
        tagPages[37] = byteArrayOf(0x04, 0x00, 0x00, 0x00)
        tagPages[38] = byteArrayOf(0x00, 0x05, 0x00, 0x00)
        val tag = FakeEv1(tagPages)
        val dump = ev1Dump {
            it[37] = byteArrayOf(0x04, 0x00, 0x00, 0x00)
            it[38] = byteArrayOf(0x00, 0x05, 0x00, 0x00)
            it[39] = ByteArray(4) // `hf mfu dump` without -k leaves PWD zeroed
            it[40] = ByteArray(4)
        }

        val report = MfuRestorer(tag).restore(dump)

        assertTrue(tag.writes.isEmpty())
        assertEquals(StepNote.KEEP_CURRENT_KEYS, report.step(39).note)
        assertEquals(StepNote.KEEP_CURRENT_KEYS, report.step(40).note)
    }

    @Test
    fun `a locked configuration is skipped`() {
        val tag = FakeEv1(factoryPages(41).also {
            it[38] = byteArrayOf(0x40, 0x00, 0x00, 0x00) // CFGLCK
        })
        val dump = ev1Dump {
            it[37] = byteArrayOf(0x04, 0x00, 0x00, 0x00)
            it[38] = byteArrayOf(0x00, 0x05, 0x00, 0x00)
            it[39] = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        }

        val report = MfuRestorer(tag).restore(dump)

        assertTrue(tag.writes.isEmpty())
        for (page in 37..40) {
            assertEquals(StepStatus.SKIPPED, report.step(page).status)
            assertEquals(StepNote.CONFIG_LOCKED, report.step(page).note)
        }
    }

    @Test
    fun `CFGLCK from the dump is never written`() {
        val tag = FakeEv1(factoryPages(41))
        val dump = ev1Dump {
            it[37] = byteArrayOf(0x04, 0x00, 0x00, 0x00)
            it[38] = byteArrayOf(0x40, 0x05, 0x00, 0x00) // CFGLCK in the dump
        }

        val report = MfuRestorer(tag).restore(dump)

        assertEquals(byteArrayOf(0x00, 0x05, 0x00, 0x00).toList(), tag.pages[38].toList())
        assertEquals(StepNote.CONFIG_LOCKED, report.step(38).note)
    }

    @Test
    fun `a dump for another variant is rejected before writing`() {
        val tag = FakeEv1(
            pages = factoryPages(41),
            version = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03),
        )
        val dump = ev1Dump { it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44) }

        var rejected = false
        try {
            MfuRestorer(tag).restore(dump)
        } catch (_: TagUnusableException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(tag.writes.isEmpty())
    }

    @Test(expected = TagAuthException::class)
    fun `an unknown password asks for one instead of writing blind`() {
        val tag = FakeEv1(
            pages = factoryPages(41),
            password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            authFrom = 4,
        )
        val dump = ev1Dump { it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44) }

        MfuRestorer(tag).restore(dump)
    }

    @Test
    fun `the supplied password unlocks a protected tag`() {
        val tag = FakeEv1(
            pages = factoryPages(41),
            password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            authFrom = 4,
        )
        val dump = ev1Dump { it[5] = byteArrayOf(0x11, 0x22, 0x33, 0x44) }

        val report = MfuRestorer(tag).restore(
            dump,
            MfuRestoreOptions(
                password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            ),
        )

        assertEquals(listOf(5), tag.writes)
        assertEquals(StepStatus.VERIFIED, report.step(5).status)
    }

    @Test
    fun `the 48 byte variant keeps its own page map`() {
        val tag = FakeEv1(
            factoryPages(20),
            version = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03),
        )
        val dump = ev1Dump(20) {
            it[15] = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            it[16] = byteArrayOf(0x04, 0x00, 0x00, 0x00)
            it[17] = byteArrayOf(0x00, 0x05, 0x00, 0x00)
        }

        val report = MfuRestorer(tag).restore(dump)

        assertEquals(listOf(15, 16, 17), tag.writes)
        assertEquals(StepStatus.VERIFIED, report.step(15).status)
        assertEquals("MF0UL11 (48 B)", report.tagLabel)
    }

    // -----------------------------------------------------------------------

    private fun ev1Dump(
        count: Int = 41,
        fill: (MutableList<ByteArray>) -> Unit = {},
    ): MfuDump {
        val pages = MutableList(count) { ByteArray(4) }
        pages[count - 2] = ByteArray(4) { 0xFF.toByte() }
        fill(pages)
        val version = if (count == 20) {
            byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0B, 0x03)
        } else {
            byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0E, 0x03)
        }
        return MfuDump("hf-mfu-04A1B29F-dump.bin", pages, version)
    }

    private fun factoryPages(count: Int): MutableList<ByteArray> =
        MutableList(count) { ByteArray(4) }.also {
            it[count - 2] = ByteArray(4) { 0xFF.toByte() }
        }

    private fun RestoreReport.step(index: Int): StepResult = steps.first { it.index == index }
}

/**
 * Ultralight EV1 with the page map of its real counterpart. Pages at or above
 * [authFrom] refuse READ/WRITE until PWD_AUTH succeeded, which is how the
 * password cascade is verified.
 */
private class FakeEv1(
    val pages: MutableList<ByteArray>,
    var password: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
    private var pack: ByteArray = byteArrayOf(0x00, 0x00),
    private val version: ByteArray = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0E, 0x03),
    private val authFrom: Int = Int.MAX_VALUE,
) : UltralightTransport {

    val writes = mutableListOf<Int>()
    private var authenticated = false

    override fun transceive(command: ByteArray): ByteArray = when (command[0].toInt() and 0xFF) {
        0x60 -> version.copyOf()
        0x1B -> {
            if (!command.copyOfRange(1, 5).contentEquals(password)) {
                throw IOException("wrong password")
            }
            authenticated = true
            pack.copyOf(2)
        }
        0x30 -> read(command[1].toInt() and 0xFF)
        0xA2 -> write(command[1].toInt() and 0xFF, command.copyOfRange(2, 6))
        else -> throw IOException("unsupported command")
    }

    private fun read(start: Int): ByteArray {
        if (start + 3 >= pages.size) throw IOException("READ $start past the last page")
        if (start + 3 >= authFrom && !authenticated) throw IOException("READ $start needs PWD_AUTH")
        val out = ByteArray(16)
        for (offset in 0 until 4) pages[start + offset].copyInto(out, offset * 4)
        return out
    }

    private fun write(page: Int, data: ByteArray): ByteArray {
        if (page >= pages.size) throw IOException("WRITE $page past the last page")
        if (page >= authFrom && !authenticated) throw IOException("WRITE $page needs PWD_AUTH")
        pages[page] = data.copyOf()
        writes += page
        // PWD and PACK take effect immediately, as on the real tag.
        if (page == pages.size - 2) password = pages[page].copyOf()
        if (page == pages.size - 1) pack = pages[page].copyOf(2)
        return byteArrayOf(0x0A)
    }

    override fun close() = Unit
}
