// Page-addressed memory read over raw NfcA frames. Android exposes no
// MifareUltralight for an Infineon my-d move lean, but the chip still answers
// the 4-block read command (0x30) that TagInfo uses, so the inspector can show
// its memory instead of declaring it unreadable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

import java.io.IOException

/** One row of the memory table: a page or block number and its bytes. */
class MemoryRow(val index: Int, val bytes: ByteArray) {
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    val ascii: String
        get() = bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) value.toChar().toString() else "."
        }
}

/** Result of a page read: four-byte pages, in the order the tag answered them. */
class PageMemory(
    val pages: List<ByteArray>,
    /** True when the last chunk was refused, which is how a tag says "end". */
    val endReached: Boolean,
) {
    init {
        require(pages.all { it.size == PageMemoryReader.BLOCK }) {
            "a page memory holds four-byte blocks"
        }
    }

    val blockCount: Int get() = pages.size

    val sizeBytes: Int get() = pages.sumOf { it.size }

    val rows: List<MemoryRow>
        get() = pages.mapIndexed { index, page -> MemoryRow(index, page) }

    companion object {
        val EMPTY = PageMemory(emptyList(), endReached = false)
    }
}

/**
 * Reads blocks with the 4-block command used by MIFARE Ultralight and by the
 * Infineon my-d move family. The command takes a block address and answers
 * sixteen bytes, so the loop advances four blocks at a time. A NAK, a timeout
 * or a short answer ends the read and keeps what was already captured; a tag
 * loss is re-thrown through [abort] so the screen can say the tag moved away.
 */
object PageMemoryReader {

    /** Read 4 blocks: `30 <block>`, sixteen bytes back. */
    const val CMD_READ_4_BLOCKS = 0x30

    const val BLOCK = 4

    /** Ceiling for a tag that never refuses: 256 bytes is plenty for Type 2. */
    const val DEFAULT_MAX_BLOCKS = 64

    fun read(
        transport: RawTagTransport,
        maxBlocks: Int = DEFAULT_MAX_BLOCKS,
        abort: (Throwable) -> Boolean = { false },
    ): PageMemory {
        require(maxBlocks >= BLOCK) { "a page read needs at least one chunk" }
        val pages = mutableListOf<ByteArray>()
        var start = 0
        while (start < maxBlocks) {
            val response = try {
                transport.transceive(
                    byteArrayOf(CMD_READ_4_BLOCKS.toByte(), start.toByte()),
                )
            } catch (e: IOException) {
                if (abort(e)) throw e
                return PageMemory(pages, endReached = true)
            }
            if (response.size != BLOCK * 4) {
                return PageMemory(pages, endReached = true)
            }
            for (offset in 0 until 4) {
                val block = start + offset
                if (block >= maxBlocks) break
                pages += response.copyOfRange(offset * BLOCK, offset * BLOCK + BLOCK)
            }
            start += 4
        }
        return PageMemory(pages, endReached = false)
    }
}
