// MIFARE Ultralight EV1 read path against an in-memory tag. The fake speaks
// the same frames as the real card, so geometry, authentication, counters and
// the PACK/password patches of the dump are all exercised.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MfuReaderTest {

    @Test
    fun `48 byte tag is captured as a 136 byte dump`() {
        val tag = FakeTag(pages(20), version = version(0x0B))

        val capture = MfuReader(tag).read()
        val bin = MfuDumpWriter.toBin(capture)

        assertEquals(20, capture.pages.size)
        assertEquals("MF0UL11 (48 B)", capture.layout.variant)
        assertEquals(136, bin.size)
        assertEquals(19, bin[11].toInt() and 0xFF)
        assertArrayEquals(tag.signature, bin.copyOfRange(12, 44))
        // READ_CNT index and READ_TEARING flag are packed together.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01, 0x01), bin.copyOfRange(48, 52))
    }

    @Test
    fun `128 byte tag is read including the unaligned tail`() {
        val tag = FakeTag(pages(41), version = version(0x0E))

        val capture = MfuReader(tag).read()
        val bin = MfuDumpWriter.toBin(capture)

        assertEquals(41, capture.pages.size)
        assertEquals(220, bin.size)
        assertEquals(40, bin[11].toInt() and 0xFF)
        assertArrayEquals(tag.pages[38], capture.pages[38])
        assertArrayEquals(tag.pages[40], capture.pages[40])
        assertTrue(tag.reads.contains(37))
    }

    @Test
    fun `without a password the pack is zeroed like the client does`() {
        val tag = FakeTag(pages(20), version = version(0x0B)).also {
            it.pages[19] = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        }

        val bin = MfuDumpWriter.toBin(MfuReader(tag).read())

        val last = MFU_DUMP_HEADER_BYTES + 19 * 4
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x33, 0x44), bin.copyOfRange(last, last + 4))
        assertEquals(0, tag.authCalls)
    }

    @Test
    fun `a supplied password unlocks the tag and is stored like -p`() {
        val password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val tag = FakeTag(
            pages = pages(41),
            password = password,
            pack = byteArrayOf(0x12, 0x34),
            version = version(0x0E),
            authFrom = 37,
        )

        val capture = MfuReader(tag).read(password)
        val bin = MfuDumpWriter.toBin(capture)

        assertEquals(1, tag.authCalls)
        assertArrayEquals(byteArrayOf(0x12, 0x34), capture.pack)
        val pwdOffset = MFU_DUMP_HEADER_BYTES + 39 * 4
        assertArrayEquals(password, bin.copyOfRange(pwdOffset, pwdOffset + 4))
        val packOffset = MFU_DUMP_HEADER_BYTES + 40 * 4
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, tag.pages[40][2], tag.pages[40][3]),
            bin.copyOfRange(packOffset, packOffset + 4),
        )
        assertArrayEquals(tag.pages[36], capture.pages[36])
    }

    @Test(expected = TagAuthException::class)
    fun `a protected tag without a password asks for one`() {
        val tag = FakeTag(pages(41), version = version(0x0E), authFrom = 4)

        MfuReader(tag).read()
    }

    @Test
    fun `a refused password is reported instead of saving a partial dump`() {
        val tag = FakeTag(
            pages = pages(20),
            password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            version = version(0x0B),
        )

        try {
            MfuReader(tag).read(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            fail("expected TagAuthException")
        } catch (expected: TagAuthException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun `a non EV1 variant is rejected`() {
        val tag = FakeTag(pages(45), version = version(0x0F))

        try {
            MfuReader(tag).read()
            fail("expected TagUnusableException")
        } catch (expected: TagUnusableException) {
            assertTrue(expected.message.orEmpty().contains("MF0UL11"))
        }
    }

    @Test(expected = TagUnusableException::class)
    fun `a tag without GET_VERSION is rejected`() {
        val tag = FakeTag(pages(20), version = version(0x0B), answerVersion = false)

        MfuReader(tag).read()
    }

    @Test
    fun `missing counters and signature still produce a dump`() {
        val tag = FakeTag(pages(20), version = version(0x0B), answerExtras = false)

        val capture = MfuReader(tag).read()

        assertArrayEquals(ByteArray(4), capture.counters[0])
        assertArrayEquals(ByteArray(32), capture.signature)
        assertEquals(136, MfuDumpWriter.toBin(capture).size)
    }

    // -----------------------------------------------------------------------

    private fun version(size: Int): ByteArray =
        byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, size.toByte(), 0x03)

    private fun pages(count: Int): MutableList<ByteArray> =
        MutableList(count) { index ->
            byteArrayOf(index.toByte(), 0x10, 0x20, 0x30)
        }.also {
            it[0] = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x2F)
            it[1] = byteArrayOf(0x9E.toByte(), 0x6B, 0x80.toByte(), 0x33)
        }
}

/**
 * Tag map of an MF0UL11/MF0UL21. Pages at or above [authFrom] refuse READ until
 * PWD_AUTH succeeded, which is how the password path is verified.
 */
private class FakeTag(
    val pages: MutableList<ByteArray>,
    var password: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
    private var pack: ByteArray = byteArrayOf(0x00, 0x00),
    private val version: ByteArray = byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0E, 0x03),
    private val authFrom: Int = Int.MAX_VALUE,
    private val answerVersion: Boolean = true,
    private val answerExtras: Boolean = true,
) : UltralightTransport {

    val signature: ByteArray = ByteArray(32) { (it + 1).toByte() }
    val reads = mutableListOf<Int>()
    var authCalls = 0
        private set
    private var authenticated = false

    override fun transceive(command: ByteArray): ByteArray =
        when (command[0].toInt() and 0xFF) {
            0x60 -> if (answerVersion) version.copyOf() else throw IOException("no version")
            0x1B -> {
                authCalls++
                if (!command.copyOfRange(1, 5).contentEquals(password)) {
                    throw IOException("wrong password")
                }
                authenticated = true
                pack.copyOf(2)
            }
            0x30 -> read(command[1].toInt() and 0xFF)
            0x39 -> {
                if (!answerExtras) throw IOException("no counters")
                byteArrayOf(0x00, 0x00, command[1])
            }
            0x3E -> {
                if (!answerExtras) throw IOException("no tearing")
                byteArrayOf(command[1])
            }
            0x3C -> if (answerExtras) signature.copyOf() else throw IOException("no signature")
            else -> throw IOException("unsupported command")
        }

    private fun read(start: Int): ByteArray {
        reads += start
        if (start + 3 >= pages.size) throw IOException("READ $start past the last page")
        if (start + 3 >= authFrom && !authenticated) throw IOException("READ $start needs PWD_AUTH")
        val out = ByteArray(16)
        for (offset in 0 until 4) pages[start + offset].copyInto(out, offset * 4)
        return out
    }

    override fun close() = Unit
}
