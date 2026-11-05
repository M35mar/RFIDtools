// Unit tests for the Proxmark3 output parser.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParsersTest {

    @Test
    fun `parses a MIFARE Classic result`() {
        val output = """
            [+] UID: 01 23 45 67
            [+] ATQA: 00 04
            [+] SAK: 08
            [+] TYPE: MIFARE Classic 1K
        """.trimIndent()

        val info = TagParsers.parse("hf 14a info", output)

        assertEquals("MIFARE Classic", info?.title)
        assertEquals("01 23 45 67", info?.fields?.first { it.label == "UID" }?.value)
        assertEquals("08", info?.fields?.first { it.label == "SAK" }?.value)
        assertTrue(info?.raw?.contains("ATQA") == true)
    }

    @Test
    fun `strips ANSI escapes`() {
        val output = "\u001B[32m[+] UID:\u001B[0m AA BB CC DD\n[+] SAK: 00"

        val info = TagParsers.parse("hf 14a info", output)

        assertEquals("AA BB CC DD", info?.fields?.first { it.label == "UID" }?.value)
        assertEquals("00", info?.fields?.first { it.label == "SAK" }?.value)
    }

    @Test
    fun `detects ISO15693 and T55xx titles`() {
        assertEquals(
            "ISO 15693",
            TagParsers.parse("hf 15 info", "ISO15693 tag found\nUID: E0 11")?.title,
        )
        assertEquals(
            "T55xx",
            TagParsers.parse("lf t55xx info", "T55x7 configuration\nUID: 01")?.title,
        )
    }

    @Test
    fun `returns null for empty or unstructured output`() {
        assertNull(TagParsers.parse("hf search", ""))
        assertNull(TagParsers.parse("hf search", "No tag found"))
    }
}
