// Byte-level checks of the file `hf mfu dump` writes: the 56-byte header, the
// PACK/password patches and the round-trip through the app's own loader.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MfuDumpWriterTest {

    @Test
    fun `48 byte tag produces a 136 byte file with pages set to 19`() {
        val capture = capture(20)

        val bin = MfuDumpWriter.toBin(capture)

        assertEquals(136, bin.size)
        assertEquals(19, bin[11].toInt() and 0xFF)
        assertEquals(0, bin[8].toInt())
        assertEquals(0, bin[9].toInt())
        assertEquals(0, bin[10].toInt())
        assertArrayEquals(versionBytes(0x0B), bin.copyOfRange(0, 8))
    }

    @Test
    fun `128 byte tag produces a 220 byte file with pages set to 40`() {
        val bin = MfuDumpWriter.toBin(capture(41))

        assertEquals(220, bin.size)
        assertEquals(40, bin[11].toInt() and 0xFF)
        assertEquals(41 * 4, bin.size - MFU_DUMP_HEADER_BYTES)
    }

    @Test
    fun `signature and counters land where the client puts them`() {
        val capture = capture(20, signature = { offset -> ByteArray(32) { (offset + it).toByte() } })

        val bin = MfuDumpWriter.toBin(capture)

        assertArrayEquals(capture.signature, bin.copyOfRange(12, 44))
        assertArrayEquals(capture.counters[0], bin.copyOfRange(44, 48))
        assertArrayEquals(capture.counters[1], bin.copyOfRange(48, 52))
        assertArrayEquals(capture.counters[2], bin.copyOfRange(52, 56))
    }

    @Test
    fun `pack always overwrites the first two bytes of the last page`() {
        val pages = pageList(20).also {
            it[19] = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        }
        val bin = MfuDumpWriter.toBin(capture(20, pages))

        val last = MFU_DUMP_HEADER_BYTES + 19 * 4
        // No authentication: the client writes 00 00 and keeps the rest.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x33, 0x44), bin.copyOfRange(last, last + 4))
    }

    @Test
    fun `pack from the authentication replaces the page`() {
        val pages = pageList(20).also {
            it[19] = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        }
        val bin = MfuDumpWriter.toBin(
            capture(20, pages, pack = byteArrayOf(0x12, 0x34)),
        )

        val last = MFU_DUMP_HEADER_BYTES + 19 * 4
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x33, 0x44), bin.copyOfRange(last, last + 4))
    }

    @Test
    fun `the password is stored only when one was supplied`() {
        val pages = pageList(20)
        val without = MfuDumpWriter.toBin(capture(20, pages, password = null))
        val with = MfuDumpWriter.toBin(
            capture(20, pages, password = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())),
        )

        val pwdOffset = MFU_DUMP_HEADER_BYTES + 18 * 4
        assertArrayEquals(pages[18], without.copyOfRange(pwdOffset, pwdOffset + 4))
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            with.copyOfRange(pwdOffset, pwdOffset + 4),
        )
    }

    @Test
    fun `the produced file is read back by the app loader`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pm3-dump-${System.nanoTime()}")
        dir.mkdirs()

        listOf(20, 41).forEach { count ->
            val pages = pageList(count)
            val capture = capture(count, pages)
            val file = File(dir, "hf-mfu-${capture.uidHex}-dump.bin")
            file.writeBytes(MfuDumpWriter.toBin(capture))

            val image = DumpLoader.load(file)
            assertTrue(image is DumpImage.Ultralight)
            val dump = (image as DumpImage.Ultralight).dump

            assertEquals(count, dump.pages.size)
            assertEquals(capture.layout.variant, dump.layout.variant)
            assertArrayEquals(
                versionBytes(if (count == 20) 0x0B else 0x0E),
                dump.version,
            )
            assertArrayEquals(capture.signature, dump.signature)
            pages.dropLast(1).forEachIndexed { index, page ->
                assertArrayEquals(page, dump.page(index))
            }
            // The first two bytes of the PACK page are zeroed by the writer (no auth).
            val last = count - 1
            assertArrayEquals(
                byteArrayOf(0x00, 0x00, pages[last][2], pages[last][3]),
                dump.page(last),
            )
        }
    }

    @Test
    fun `default file name is the PM3 one`() {
        assertEquals("hf-mfu-04A1B29E6B8033-dump", capture(20).defaultName)
    }

    @Test
    fun `file names get one bin extension and reject separators`() {
        assertEquals("mio dump.bin", MfuDumpFiles.fileName("  mio dump  "))
        assertEquals("mio dump.bin", MfuDumpFiles.fileName("mio dump.bin"))
        assertEquals("mio.bin", MfuDumpFiles.fileName("mio.BIN"))
        assertNull(MfuDumpFiles.fileName(""))
        assertNull(MfuDumpFiles.fileName("   "))
        assertNull(MfuDumpFiles.fileName(".nascosto"))
        assertNull(MfuDumpFiles.fileName("cartella/file"))
        assertNull(MfuDumpFiles.fileName("cartella\\file"))
        assertNull(MfuDumpFiles.fileName("a".repeat(80) + ".bin"))
    }

    @Test
    fun `an existing name is never overwritten`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pm3-name-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "tag.bin").writeBytes(ByteArray(1))

        val first = MfuDumpFiles.unique(dir, "tag.bin")
        first.writeBytes(ByteArray(1))
        val second = MfuDumpFiles.unique(dir, "tag.bin")

        assertEquals("tag-1.bin", first.name)
        assertEquals("tag-2.bin", second.name)
    }

    @Test
    fun `the key file is named like the dump and found again on restore`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pm3-key-${System.nanoTime()}")
        dir.mkdirs()
        val dump = MfuDumpFiles.uniqueDump(dir, "hf-mf-04A1B29F-dump.bin")
        dump.writeBytes(ByteArray(16))
        val key = MfuDumpFiles.keyFileFor(dump)
        key.writeBytes(ByteArray(96))

        assertEquals("hf-mf-04A1B29F-key.bin", key.name)
        assertEquals(key, keyFileCandidate(dump))
    }

    @Test
    fun `the numbered suffix is shared by the dump and its key file`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pm3-pair-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "hf-mf-04A1B29F-dump.bin").writeBytes(ByteArray(16))

        val dump = MfuDumpFiles.uniqueDump(dir, "hf-mf-04A1B29F-dump.bin")

        assertEquals("hf-mf-04A1B29F-dump-1.bin", dump.name)
        val key = MfuDumpFiles.keyFileFor(dump)
        assertEquals("hf-mf-04A1B29F-dump-1-key.bin", key.name)
        key.writeBytes(ByteArray(96))
        assertEquals(key, keyFileCandidate(dump))
    }

    @Test
    fun `an existing key file also bumps the dump number`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pm3-both-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "hf-mf-04A1B29F-key.bin").writeBytes(ByteArray(96))

        val dump = MfuDumpFiles.uniqueDump(dir, "hf-mf-04A1B29F-dump.bin")

        assertEquals("hf-mf-04A1B29F-dump-1.bin", dump.name)
        assertEquals("hf-mf-04A1B29F-dump-1-key.bin", MfuDumpFiles.keyFileFor(dump).name)
    }

    // -----------------------------------------------------------------------

    private fun versionBytes(size: Int): ByteArray =
        byteArrayOf(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, size.toByte(), 0x03)

    private fun pageList(count: Int): MutableList<ByteArray> =
        MutableList(count) { index ->
            byteArrayOf(index.toByte(), 0x10, 0x20, 0x30)
        }.also {
            // UID page and page 1 feed the default file name.
            it[0] = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x2F)
            it[1] = byteArrayOf(0x9E.toByte(), 0x6B, 0x80.toByte(), 0x33)
        }

    private fun capture(
        count: Int,
        pages: MutableList<ByteArray> = pageList(count),
        pack: ByteArray? = null,
        password: ByteArray? = null,
        signature: (Int) -> ByteArray = { ByteArray(32) { value -> (value + 1).toByte() } },
    ): MfuCapture = MfuCapture(
        pages = pages,
        version = versionBytes(if (count == 20) 0x0B else 0x0E),
        signature = signature(0),
        counters = listOf(
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, 0, 1, 1),
            byteArrayOf(0, 0, 2, 2),
        ),
        pack = pack,
        password = password,
    )
}
