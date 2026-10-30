// Dump of a page-addressed Type 2 chip that Android exposes only through raw
// NfcA (the Infineon my-d move on transport tickets). The header matches the
// 56-byte `hf mfu dump` layout so the Proxmark3 client can open the file, but
// PACK and password are left exactly as read: unlike the Ultralight path this
// writer must not patch the last page.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc

object PageDumpWriter {

    /** Offset of the "highest page" byte in `mfu_dump_t`. */
    private const val PAGES_OFFSET = 11

    fun toBin(pages: List<ByteArray>): ByteArray {
        require(pages.isNotEmpty()) { "a page dump needs at least one page" }
        require(pages.all { it.size == 4 }) { "every page must hold four bytes" }
        val header = ByteArray(MFU_DUMP_HEADER_BYTES)
        header[PAGES_OFFSET] = (pages.size - 1).toByte()
        val body = ByteArray(pages.size * 4)
        pages.forEachIndexed { index, page -> page.copyInto(body, index * 4) }
        return header + body
    }
}
