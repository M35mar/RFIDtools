// Console colouring and ANSI handling.
//
// The embedded client strips its own ANSI colours when the output is not a
// terminal, so the app classifies every line itself and maps the result to a
// theme colour. Escape sequences produced by Lua or cmd scripts are parsed as
// well, so they colour the text instead of showing as raw control bytes.
//
// This file is pure Kotlin (no Compose, no Android types) so the classifier and
// the SGR parser are covered by JVM unit tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

/** How a console line is rendered. */
enum class ConsoleStyle {
    NORMAL,
    DIM,
    PROMPT,
    ERROR,
    WARNING,
    SUCCESS,
    TAG,
    HEADER,
}

/** The sixteen ANSI foreground colours a script may ask for. */
enum class AnsiColor {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE,
    BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
    BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE,
}

/** One run of text with the style it must be drawn with. */
data class ConsoleSegment(
    val text: String,
    val style: ConsoleStyle = ConsoleStyle.NORMAL,
    val bold: Boolean = false,
    /** Set by an explicit ANSI code; overrides the classified colour. */
    val color: AnsiColor? = null,
)

object ConsoleAnsi {

    private const val ESC = '\u001B'

    private val ANSI_30 = listOf(
        AnsiColor.BLACK, AnsiColor.RED, AnsiColor.GREEN, AnsiColor.YELLOW,
        AnsiColor.BLUE, AnsiColor.MAGENTA, AnsiColor.CYAN, AnsiColor.WHITE,
    )

    private val ANSI_90 = listOf(
        AnsiColor.BRIGHT_BLACK, AnsiColor.BRIGHT_RED, AnsiColor.BRIGHT_GREEN,
        AnsiColor.BRIGHT_YELLOW, AnsiColor.BRIGHT_BLUE, AnsiColor.BRIGHT_MAGENTA,
        AnsiColor.BRIGHT_CYAN, AnsiColor.BRIGHT_WHITE,
    )

    // English and Italian, because the client speaks English and the app's own
    // messages are localised.
    private val ERROR_HINTS = listOf(
        "[!]", "error", "failed", "failure", "not found", "unable",
        "cannot", "can't", "invalid", "denied", "timeout", "timed out",
        "no tag", "not supported", "aborted",
        "errore", "non riuscit", "non valid", "impossibile", "negato",
        "non trovat", "non supportat",
    )

    private val WARNING_HINTS = listOf(
        "[-]", "warning", "warn", "deprecated", "caution", "attenzione",
    )

    private val SUCCESS_HINTS = listOf(
        "[+]", "success", "completed", "done", "saved",
        "completat", "salvat", "riuscit",
    )

    /** Lines that report a tag fact worth highlighting. */
    private val TAG_PATTERN = Regex(
        "^(UID|UID\\(hex\\)|ATQA|SAK|BCC|Manufacturer|Manufacturer ID|Product|Chip|ISO|Tag|Type|Prng|Signature|Block 0)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    private val HEADER_PATTERN = Regex("^[\\s=\\-_─═┌┐└┘│|+\\[\\]]{3,}$")

    private val HEX_PATTERN = Regex("^\\s*([0-9A-Fa-f]{2}\\s){4,}.*$")

    /** Parses SGR escape sequences and classifies the text between them. */
    fun parse(line: String): List<ConsoleSegment> {
        if (line.isEmpty()) return listOf(ConsoleSegment(""))
        val base = classify(line)
        val segments = mutableListOf<ConsoleSegment>()
        var current = StringBuilder()
        var color: AnsiColor? = null
        var bold = false

        fun flush() {
            if (current.isNotEmpty()) {
                segments += ConsoleSegment(current.toString(), base, bold, color)
                current = StringBuilder()
            }
        }

        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (ch == ESC && index + 1 < line.length && line[index + 1] == '[') {
                val end = line.indexOf('m', index + 2)
                if (end > index) {
                    flush()
                    for (code in sgrCodes(line.substring(index + 2, end))) {
                        when {
                            code == 0 -> { color = null; bold = false }
                            code == 1 -> bold = true
                            code == 22 -> bold = false
                            code == 39 -> color = null
                            code in 30..37 -> color = ANSI_30[code - 30]
                            code in 90..97 -> color = ANSI_90[code - 90]
                            else -> Unit // background and 256-colour codes: ignored
                        }
                    }
                    index = end + 1
                    continue
                }
            }
            current.append(ch)
            index++
        }
        flush()
        return segments.ifEmpty { listOf(ConsoleSegment("", base)) }
    }

    private fun sgrCodes(body: String): List<Int> = body.split(';').mapNotNull { it.toIntOrNull() }

    /** Which style the whole line belongs to, ignoring escape sequences. */
    fun classify(rawLine: String): ConsoleStyle {
        val text = stripAnsi(rawLine).trim()
        if (text.isEmpty()) return ConsoleStyle.NORMAL
        if (text.startsWith("> ")) return ConsoleStyle.PROMPT
        val lower = text.lowercase()
        if (ERROR_HINTS.any { lower.contains(it) }) return ConsoleStyle.ERROR
        if (WARNING_HINTS.any { lower.contains(it) }) return ConsoleStyle.WARNING
        if (SUCCESS_HINTS.any { lower.contains(it) }) return ConsoleStyle.SUCCESS
        if (TAG_PATTERN.matches(text)) return ConsoleStyle.TAG
        if (HEADER_PATTERN.matches(text)) return ConsoleStyle.HEADER
        if (HEX_PATTERN.matches(text)) return ConsoleStyle.DIM
        return ConsoleStyle.NORMAL
    }

    /** Removes every SGR escape sequence, for copying and sharing. */
    fun stripAnsi(text: String): String {
        if (text.indexOf(ESC) < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == ESC && index + 1 < text.length && text[index + 1] == '[') {
                val end = text.indexOf('m', index + 2)
                if (end > index) {
                    index = end + 1
                    continue
                }
            }
            out.append(ch)
            index++
        }
        return out.toString()
    }
}
