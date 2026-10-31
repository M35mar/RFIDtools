// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleLineBufferTest {

    @Test
    fun `complete lines are emitted immediately`() {
        val buffer = ConsoleLineBuffer()
        assertEquals(listOf("one", "two"), buffer.append("one\ntwo\n"))
    }

    @Test
    fun `partial line waits for the next chunk`() {
        val buffer = ConsoleLineBuffer()
        assertEquals(emptyList<String>(), buffer.append("partial"))
        assertEquals(listOf("partial tail", "next"), buffer.append(" tail\nnext\n"))
    }

    @Test
    fun `flush emits the unterminated tail`() {
        val buffer = ConsoleLineBuffer()
        buffer.append("done")
        assertEquals(listOf("done"), buffer.append("", flush = true))
        assertEquals(emptyList<String>(), buffer.append("", flush = true))
    }

    @Test
    fun `carriage returns from the native output are removed`() {
        val buffer = ConsoleLineBuffer()
        assertEquals(listOf("line"), buffer.append("line\r\n"))
    }

    @Test
    fun `blank lines are preserved`() {
        val buffer = ConsoleLineBuffer()
        val lines = buffer.append("a\n\nb\n")
        assertEquals(3, lines.size)
        assertEquals("a", lines[0])
        assertTrue(lines[1].isEmpty())
        assertEquals("b", lines[2])
    }

    @Test
    fun `clear drops the pending tail`() {
        val buffer = ConsoleLineBuffer()
        buffer.append("stale")
        buffer.clear()
        assertEquals(listOf("fresh"), buffer.append("fresh\n"))
    }
}
