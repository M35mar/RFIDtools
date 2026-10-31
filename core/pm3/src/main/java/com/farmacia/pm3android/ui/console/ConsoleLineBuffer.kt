// Reassembles streamed console output into complete lines, so a chunk that
// ends mid-line does not split it in the visible log.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

class ConsoleLineBuffer {

    private val pending = StringBuilder()

    /**
     * Appends [text] and returns every line completed by it. With [flush] the
     * unterminated tail is returned as a line too (end of command).
     */
    @Synchronized
    fun append(text: String, flush: Boolean = false): List<String> {
        if (text.isNotEmpty()) {
            pending.append(text)
        }
        val lines = ArrayList<String>()
        var newline = pending.indexOf("\n")
        while (newline >= 0) {
            lines.add(pending.substring(0, newline).trimEnd('\r'))
            pending.delete(0, newline + 1)
            newline = pending.indexOf("\n")
        }
        if (flush && pending.isNotEmpty()) {
            lines.add(pending.toString().trimEnd('\r'))
            pending.setLength(0)
        }
        return lines
    }

    @Synchronized
    fun clear() {
        pending.setLength(0)
    }
}
