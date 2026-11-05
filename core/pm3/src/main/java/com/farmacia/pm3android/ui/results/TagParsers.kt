// Turns raw Proxmark3 client output into a compact, structured summary.
// Pure Kotlin, so the parsing rules are unit-tested and independent of
// Compose/Android. The full output stays available as `raw`.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.results

private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

private val KEYED_LINE = Regex(
    """(?im)^\s*(?:\[[^\]]*\]\s*)?""" +
        """(UID|ATQA|SAK|TYPE|Chip Type|Modulation|Bit Rate|Manufacturer|""" +
        """Version|Client|Firmware|Card type|Tag type|Memory|Capacity)\s*[:=]\s*(.+?)\s*$""",
)

data class TagField(val label: String, val value: String)

data class TagInfo(
    val command: String,
    val title: String,
    val fields: List<TagField>,
    val raw: String,
)

object TagParsers {

    fun parse(command: String, output: String): TagInfo? {
        val clean = ANSI.replace(output, "")
        if (clean.isBlank()) return null

        val seen = LinkedHashMap<String, String>()
        for (match in KEYED_LINE.findAll(clean)) {
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (value.isNotEmpty() && !seen.containsKey(key)) {
                seen[key] = value
            }
        }

        val title = detectTitle(command, clean)
        // A command with neither structured fields nor a recognisable tag is
        // not a result card: keep the raw console as the only output.
        if (seen.isEmpty() && title == command) return null

        return TagInfo(
            command = command,
            title = title,
            fields = seen.map { TagField(it.key, it.value) },
            raw = clean.trim(),
        )
    }

    private fun detectTitle(command: String, output: String): String = when {
        output.contains("MIFARE Classic", ignoreCase = true) -> "MIFARE Classic"
        output.contains("MIFARE Ultralight", ignoreCase = true) -> "MIFARE Ultralight"
        output.contains("DESFire", ignoreCase = true) -> "MIFARE DESFire"
        output.contains("ISO15693", ignoreCase = true) ||
            output.contains("ISO 15693", ignoreCase = true) -> "ISO 15693"
        output.contains("iCLASS", ignoreCase = true) ||
            output.contains("Picopass", ignoreCase = true) -> "iCLASS"
        output.contains("T55", ignoreCase = true) -> "T55xx"
        output.contains("EM410x", ignoreCase = true) -> "EM410x"
        output.contains("ISO14443", ignoreCase = true) ||
            command.contains("14a", ignoreCase = true) -> "ISO 14443-A"
        command.startsWith("hw ") -> "Proxmark3"
        else -> command
    }
}
