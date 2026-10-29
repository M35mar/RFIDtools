// Command/response log shared by the inspector and the APDU console. It is
// written from the IO thread and read from the UI thread, so every access is
// synchronized. Only the last [MAX_ENTRIES] exchanges are kept: a long read
// must not grow the state that Compose observes without bound.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.nfc.inspect

class CommandLog {

    class Entry(
        val command: ByteArray,
        val response: ByteArray?,
        val error: String? = null,
        val label: String? = null,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(
        command: ByteArray,
        response: ByteArray?,
        error: String? = null,
        label: String? = null,
    ) {
        entries.addLast(Entry(command.copyOf(), response?.copyOf(), error, label))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private companion object {
        const val MAX_ENTRIES = 128
    }
}
