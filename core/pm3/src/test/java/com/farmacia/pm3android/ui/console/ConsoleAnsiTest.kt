// Console colouring: line classification, SGR parsing and ANSI stripping.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleAnsiTest {

    @Test
    fun `a command echo is a prompt`() {
        assertEquals(ConsoleStyle.PROMPT, ConsoleAnsi.classify("> hf search"))
    }

    @Test
    fun `errors are recognised in the client wording`() {
        assertEquals(ConsoleStyle.ERROR, ConsoleAnsi.classify("Error: no tag found"))
        assertEquals(ConsoleStyle.ERROR, ConsoleAnsi.classify("[!] command failed"))
        assertEquals(ConsoleStyle.ERROR, ConsoleAnsi.classify("Autenticazione non riuscita"))
        assertEquals(ConsoleStyle.SUCCESS, ConsoleAnsi.classify("Ripristino completato"))
    }

    @Test
    fun `warnings and successes are distinguished`() {
        assertEquals(ConsoleStyle.WARNING, ConsoleAnsi.classify("[-] warning: weak field"))
        assertEquals(ConsoleStyle.SUCCESS, ConsoleAnsi.classify("[+] done"))
    }

    @Test
    fun `tag facts are highlighted`() {
        assertEquals(ConsoleStyle.TAG, ConsoleAnsi.classify("UID: 04 A1 B2 C3"))
        assertEquals(ConsoleStyle.TAG, ConsoleAnsi.classify("SAK: 08"))
        assertEquals(ConsoleStyle.TAG, ConsoleAnsi.classify("ATQA: 00 44"))
    }

    @Test
    fun `plain output stays normal`() {
        assertEquals(ConsoleStyle.NORMAL, ConsoleAnsi.classify("Searching for NFC tag..."))
        assertEquals(ConsoleStyle.NORMAL, ConsoleAnsi.classify(""))
    }

    @Test
    fun `hex dumps are dim and separators are headers`() {
        assertEquals(ConsoleStyle.DIM, ConsoleAnsi.classify("04 A1 B2 C3 11 22 33 44"))
        assertEquals(ConsoleStyle.HEADER, ConsoleAnsi.classify("----------"))
    }

    @Test
    fun `ansi colours are parsed instead of shown`() {
        val red = ConsoleAnsi.parse("\u001B[31mred\u001B[0m plain")

        assertEquals(2, red.size)
        assertEquals("red", red[0].text)
        assertEquals(AnsiColor.RED, red[0].color)
        assertEquals(" plain", red[1].text)
        assertNull(red[1].color)
    }

    @Test
    fun `bold and bright colours combine`() {
        val segments = ConsoleAnsi.parse("\u001B[1;93mboom\u001B[22m")

        assertEquals("boom", segments[0].text)
        assertTrue(segments[0].bold)
        assertEquals(AnsiColor.BRIGHT_YELLOW, segments[0].color)
        assertEquals("boom", ConsoleAnsi.stripAnsi("\u001B[1;93mboom\u001B[22m"))
    }

    @Test
    fun `unknown and background codes keep the text`() {
        val segments = ConsoleAnsi.parse("\u001B[40;38;5;200mtext")

        assertEquals("text", segments.single().text)
    }

    @Test
    fun `strip removes every sequence and keeps normal text`() {
        assertEquals("plain text", ConsoleAnsi.stripAnsi("\u001B[32mplain\u001B[0m text"))
        assertEquals("no escapes", ConsoleAnsi.stripAnsi("no escapes"))
    }
}
