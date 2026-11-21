// Console colouring, full ANSI SGR handling and PC-style PM3 token highlighting.
//
// When the embedded client emits ANSI SGR escapes (16-colour, bright,
// background, 256-colour or 24-bit RGB), parse() renders them faithfully.
// When a line carries no explicit ANSI codes, highlight() applies intra-line
// semantic token colouring modelled after the desktop Proxmark3 terminal:
// status badges ([+]/[-]/[!]/[=]), tag fields (UID/ATQA/SAK), hex dumps with
// dimmed 00 bytes, ASCII table borders, flags, file names and status keywords.
//
// Pure Kotlin (no Compose, no Android types) so all parsing and highlighting
// logic is covered by JVM unit tests.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.console

/** How a console line is rendered as a fallback when no token colour applies. */
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

/** The sixteen standard + bright ANSI colours. */
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
    /** Set by an explicit 16-colour ANSI code or semantic token; overrides [style]. */
    val color: AnsiColor? = null,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** Explicit 256-colour or 24-bit truecolour foreground (0x00RRGGBB). */
    val rgbColor: Int? = null,
    /** Explicit 16-colour ANSI background. */
    val background: AnsiColor? = null,
    /** Explicit 256-colour or 24-bit truecolour background (0x00RRGGBB). */
    val rgbBackground: Int? = null,
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

    /** Phrases like "0 failed" or "0 errors" must not trigger ERROR styling. */
    private val ZERO_ERROR_PATTERN = Regex(
        "\\b0\\s+(error|errors|failed|failures|errori|fallit[io])\\b",
        RegexOption.IGNORE_CASE,
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
        "^(UID|UID\\(hex\\)|ATQA|SAK|BCC|Manufacturer|Manufacturer ID|Product|Chip|ISO|Tag|Type|Prng|Signature|Block 0|ATS|HIST|Card|UID size)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    private val HEADER_PATTERN = Regex("^[\\s=\\-_─═┌┐└┘┬┴┼├┤│|+\\[\\]]{3,}$")

    private val HEX_PATTERN = Regex("^\\s*([0-9A-Fa-f]{2}\\s){4,}.*$")

    private val PREFIX_BADGE = Regex("""^(\s*)(\[[+!\-=#?]|\[usb]|\[bt]|\[ble]|\[pm3]|\[ok]|\[err]|\[warn])(]?)\s*""", RegexOption.IGNORE_CASE)
    private val STATUS_BADGE_EXACT = Regex("""^(\s*)(\[\+]|\[!]|\[-]|\[=]|\[#]|\[\?]|\[usb]|\[bt]|\[ble]|\[pm3])(\s*)""", RegexOption.IGNORE_CASE)
    private val LABEL_VALUE = Regex("""^([A-Za-z0-9_ ()/.\-#]{2,28})(\s*:\s*)(.+)$""")
    private val TOKEN_REGEX = Regex(
        """(?<![A-Za-z0-9])([0-9A-Fa-f]{2}(?:[\s:][0-9A-Fa-f]{2}){3,})(?![A-Za-z0-9])""" +
            """|(?<![A-Za-z0-9])(0x[0-9A-Fa-f]{2,16}|[0-9A-Fa-f]{8,16})(?![A-Za-z0-9])""" +
            """|(\b[\w.\-]+\.(?:bin|json|eml|mct|trace|log|lua|py|cmd|dic)\b)""" +
            """|((?:^|(?<=\s))--?[A-Za-z][A-Za-z0-9\-]*)""" +
            """|([│┌┐└┘├┤┬┴┼─═]+)""" +
            """|(\b(?:OK|VALID|FOUND|SUCCESS|YES|TRUE|OPEN|UNLOCKED|WRITTEN|VERIFIED)\b)""" +
            """|(\b(?:FAIL|FAILED|ERROR|INVALID|DENIED|LOCKED|NO TAG|TIMEOUT|ABORTED)\b)""" +
            """|(\b(?:WARN|WARNING|WEAK|HARD|STATIC|DARKSIDE|NESTED|PROTECTED)\b)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses explicit ANSI SGR escape sequences and classifies text between
     * them. Non-SGR CSI control sequences (e.g. cursor/erase line) are stripped.
     */
    fun parse(line: String): List<ConsoleSegment> {
        if (line.isEmpty()) return listOf(ConsoleSegment(""))
        val base = classify(line)
        val segments = mutableListOf<ConsoleSegment>()
        var current = StringBuilder()
        var color: AnsiColor? = null
        var rgbColor: Int? = null
        var background: AnsiColor? = null
        var rgbBackground: Int? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        var inverse = false

        fun flush() {
            if (current.isNotEmpty()) {
                val fg = if (inverse) background ?: AnsiColor.BLACK else color
                val fgRgb = if (inverse) rgbBackground else rgbColor
                val bg = if (inverse) color ?: AnsiColor.WHITE else background
                val bgRgb = if (inverse) rgbColor else rgbBackground
                segments += ConsoleSegment(
                    text = current.toString(),
                    style = base,
                    bold = bold,
                    color = fg,
                    dim = dim,
                    italic = italic,
                    underline = underline,
                    rgbColor = fgRgb,
                    background = bg,
                    rgbBackground = bgRgb,
                )
                current = StringBuilder()
            }
        }

        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (ch == ESC && index + 1 < line.length && line[index + 1] == '[') {
                val csiEnd = findCsiEnd(line, index + 2)
                if (csiEnd > index) {
                    val finalByte = line[csiEnd]
                    if (finalByte == 'm') {
                        flush()
                        val codes = sgrCodes(line.substring(index + 2, csiEnd))
                        var i = 0
                        while (i < codes.size) {
                            when (val code = codes[i]) {
                                0 -> {
                                    color = null; rgbColor = null
                                    background = null; rgbBackground = null
                                    bold = false; dim = false; italic = false
                                    underline = false; inverse = false
                                }
                                1 -> bold = true
                                2 -> dim = true
                                3 -> italic = true
                                4 -> underline = true
                                7 -> inverse = true
                                22 -> { bold = false; dim = false }
                                23 -> italic = false
                                24 -> underline = false
                                27 -> inverse = false
                                39 -> { color = null; rgbColor = null }
                                49 -> { background = null; rgbBackground = null }
                                in 30..37 -> { color = ANSI_30[code - 30]; rgbColor = null }
                                in 90..97 -> { color = ANSI_90[code - 90]; rgbColor = null }
                                in 40..47 -> { background = ANSI_30[code - 40]; rgbBackground = null }
                                in 100..107 -> { background = ANSI_90[code - 100]; rgbBackground = null }
                                38, 48 -> {
                                    val isFg = code == 38
                                    val mode = codes.getOrNull(i + 1)
                                    if (mode == 5 && i + 2 < codes.size) {
                                        val rgb = xterm256ToRgb(codes[i + 2])
                                        if (isFg) { color = null; rgbColor = rgb }
                                        else { background = null; rgbBackground = rgb }
                                        i += 2
                                    } else if (mode == 2 && i + 4 < codes.size) {
                                        val r = codes[i + 2].coerceIn(0, 255)
                                        val g = codes[i + 3].coerceIn(0, 255)
                                        val b = codes[i + 4].coerceIn(0, 255)
                                        val rgb = (r shl 16) or (g shl 8) or b
                                        if (isFg) { color = null; rgbColor = rgb }
                                        else { background = null; rgbBackground = rgb }
                                        i += 4
                                    }
                                }
                            }
                            i++
                        }
                    }
                    index = csiEnd + 1
                    continue
                }
            }
            current.append(ch)
            index++
        }
        flush()
        return segments.ifEmpty { listOf(ConsoleSegment("", base)) }
    }

    /**
     * Full PC-fidelity highlighting for UI rendering:
     * - If the line contains ANSI escape codes, renders them verbatim via [parse].
     * - Otherwise applies intra-line PM3 syntax highlighting (badges, prompts,
     *   key:value pairs, hex bytes, table borders, file paths, status tokens).
     */
    fun highlight(line: String): List<ConsoleSegment> {
        if (line.isEmpty()) return listOf(ConsoleSegment(""))
        if (line.indexOf(ESC) >= 0) return parse(line)

        val base = classify(line)
        if (base == ConsoleStyle.HEADER) {
            return listOf(ConsoleSegment(line, base, color = AnsiColor.BRIGHT_BLACK))
        }
        if (base == ConsoleStyle.PROMPT) {
            return highlightPrompt(line)
        }

        val out = mutableListOf<ConsoleSegment>()
        var rest = line

        // 1. Leading PM3 status badge: [+], [!], [-], [=], [#], [?], [usb], [bt]...
        val badgeMatch = STATUS_BADGE_EXACT.find(rest)
        if (badgeMatch != null) {
            val lead = badgeMatch.groupValues[1]
            val badge = badgeMatch.groupValues[2]
            val space = badgeMatch.groupValues[3]
            if (lead.isNotEmpty()) out += ConsoleSegment(lead, base)
            val badgeColor = when (badge.lowercase()) {
                "[+]" -> AnsiColor.BRIGHT_GREEN
                "[!]" -> AnsiColor.BRIGHT_RED
                "[-]" -> AnsiColor.BRIGHT_YELLOW
                "[=]", "[#]", "[?]" -> AnsiColor.BRIGHT_CYAN
                else -> AnsiColor.BRIGHT_MAGENTA
            }
            out += ConsoleSegment(badge, base, bold = true, color = badgeColor)
            if (space.isNotEmpty()) out += ConsoleSegment(space, base)
            rest = rest.substring(badgeMatch.range.last + 1)
        }

        if (rest.isEmpty()) return out.ifEmpty { listOf(ConsoleSegment(line, base)) }

        // 2. Key: Value pair (e.g. "UID: 04 A1 B2 C3", "SAK: 08", "Manufacturer: NXP")
        val kvMatch = LABEL_VALUE.find(rest)
        if (kvMatch != null && !rest.trimStart().startsWith("|")) {
            val label = kvMatch.groupValues[1]
            val sep = kvMatch.groupValues[2]
            val value = kvMatch.groupValues[3]
            val labelColor = when (base) {
                ConsoleStyle.ERROR -> AnsiColor.BRIGHT_RED
                ConsoleStyle.WARNING -> AnsiColor.BRIGHT_YELLOW
                ConsoleStyle.TAG -> AnsiColor.BRIGHT_CYAN
                else -> AnsiColor.CYAN
            }
            out += ConsoleSegment(label, base, bold = base == ConsoleStyle.TAG, color = labelColor)
            out += ConsoleSegment(sep, base, color = AnsiColor.BRIGHT_BLACK)
            val valueBase = if (base == ConsoleStyle.TAG) ConsoleStyle.NORMAL else base
            highlightInlineTokens(value, valueBase, out, preferBrightValue = base == ConsoleStyle.TAG)
            return out
        }

        // 3. General inline tokenization (hex rows, ASCII tables, keywords, files, flags)
        highlightInlineTokens(rest, base, out, preferBrightValue = false)
        return out.ifEmpty { listOf(ConsoleSegment(line, base)) }
    }

    private fun highlightPrompt(line: String): List<ConsoleSegment> {
        val out = mutableListOf<ConsoleSegment>()
        val trimmedStart = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (trimmedStart > 0) out += ConsoleSegment(line.substring(0, trimmedStart), ConsoleStyle.PROMPT)
        val after = line.substring(trimmedStart)
        if (!after.startsWith("> ")) {
            return listOf(ConsoleSegment(line, ConsoleStyle.PROMPT, bold = true, color = AnsiColor.BRIGHT_GREEN))
        }
        out += ConsoleSegment("> ", ConsoleStyle.PROMPT, bold = true, color = AnsiColor.BRIGHT_GREEN)
        val cmdLine = after.substring(2)
        val tokens = cmdLine.split(' ')
        tokens.forEachIndexed { idx, tok ->
            if (idx > 0) out += ConsoleSegment(" ", ConsoleStyle.PROMPT)
            val seg = when {
                idx <= 1 && !tok.startsWith("-") ->
                    ConsoleSegment(tok, ConsoleStyle.PROMPT, bold = true, color = AnsiColor.BRIGHT_CYAN)
                tok.startsWith("-") ->
                    ConsoleSegment(tok, ConsoleStyle.PROMPT, bold = true, color = AnsiColor.BRIGHT_YELLOW)
                tok.length >= 4 && tok.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
                    ConsoleSegment(tok, ConsoleStyle.PROMPT, bold = true, color = AnsiColor.BRIGHT_MAGENTA)
                else ->
                    ConsoleSegment(tok, ConsoleStyle.PROMPT, bold = false, color = AnsiColor.BRIGHT_WHITE)
            }
            out += seg
        }
        return out
    }

    private fun highlightInlineTokens(
        text: String,
        base: ConsoleStyle,
        out: MutableList<ConsoleSegment>,
        preferBrightValue: Boolean,
    ) {
        var cursor = 0
        for (match in TOKEN_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                val plain = text.substring(cursor, match.range.first)
                out += ConsoleSegment(
                    text = plain,
                    style = base,
                    color = if (preferBrightValue) AnsiColor.BRIGHT_WHITE else null,
                )
            }
            val token = match.value
            when {
                match.groupValues[1].isNotEmpty() -> {
                    // Multi-byte hex sequence (e.g. "04 A1 00 FF 12 34"):
                    // highlight 00 dimmed, FF amber, other bytes bright cyan/green.
                    appendHexRun(token, base, out, highlightValue = preferBrightValue || base == ConsoleStyle.TAG)
                }
                match.groupValues[2].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, bold = true, color = AnsiColor.BRIGHT_YELLOW)
                }
                match.groupValues[3].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, underline = true, color = AnsiColor.BRIGHT_MAGENTA)
                }
                match.groupValues[4].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, color = AnsiColor.YELLOW)
                }
                match.groupValues[5].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, color = AnsiColor.BRIGHT_BLACK)
                }
                match.groupValues[6].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, bold = true, color = AnsiColor.BRIGHT_GREEN)
                }
                match.groupValues[7].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, bold = true, color = AnsiColor.BRIGHT_RED)
                }
                match.groupValues[8].isNotEmpty() -> {
                    out += ConsoleSegment(token, base, bold = true, color = AnsiColor.BRIGHT_YELLOW)
                }
                else -> out += ConsoleSegment(token, base)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            out += ConsoleSegment(
                text = text.substring(cursor),
                style = base,
                color = if (preferBrightValue && cursor == 0) AnsiColor.BRIGHT_WHITE else null,
            )
        }
    }

    private fun appendHexRun(
        run: String,
        base: ConsoleStyle,
        out: MutableList<ConsoleSegment>,
        highlightValue: Boolean,
    ) {
        var i = 0
        while (i < run.length) {
            if (i + 1 < run.length && run[i].isHex() && run[i + 1].isHex()) {
                val pair = run.substring(i, i + 2)
                val pairColor = when {
                    highlightValue -> AnsiColor.BRIGHT_GREEN
                    pair == "00" -> AnsiColor.BRIGHT_BLACK
                    pair.equals("FF", ignoreCase = true) -> AnsiColor.BRIGHT_YELLOW
                    else -> AnsiColor.BRIGHT_CYAN
                }
                out += ConsoleSegment(
                    text = pair,
                    style = base,
                    bold = highlightValue,
                    dim = !highlightValue && pair == "00",
                    color = pairColor,
                )
                i += 2
            } else {
                out += ConsoleSegment(run[i].toString(), base, color = AnsiColor.BRIGHT_BLACK)
                i++
            }
        }
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Finds the final byte (0x40..0x7E) of a CSI escape sequence starting at [from]. */
    private fun findCsiEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val code = text[i].code
            if (code in 0x40..0x7E) return i
            if (code !in 0x20..0x3F) return -1
            i++
        }
        return -1
    }

    private fun sgrCodes(body: String): List<Int> {
        if (body.isEmpty()) return listOf(0)
        return body.split(';').map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }.filter { it >= 0 }
    }

    /** Converts an xterm 256-colour index (0..255) to 0x00RRGGBB. */
    fun xterm256ToRgb(index: Int): Int {
        val n = index.coerceIn(0, 255)
        if (n < 16) return XTERM_16[n]
        if (n < 232) {
            val c = n - 16
            val r = (c / 36).let { if (it == 0) 0 else 55 + it * 40 }
            val g = ((c / 6) % 6).let { if (it == 0) 0 else 55 + it * 40 }
            val b = (c % 6).let { if (it == 0) 0 else 55 + it * 40 }
            return (r shl 16) or (g shl 8) or b
        }
        val gray = 8 + (n - 232) * 10
        return (gray shl 16) or (gray shl 8) or gray
    }

    private val XTERM_16 = intArrayOf(
        0x1E293B, 0xF43F5E, 0x22C55E, 0xF59E0B,
        0x3B82F6, 0xC084FC, 0x06B6D4, 0xE2E8F0,
        0x64748B, 0xFB7185, 0x4ADE80, 0xFDE047,
        0x60A5FA, 0xE879F9, 0x22D3EE, 0xFFFFFF,
    )

    /** Which style the whole line belongs to, ignoring escape sequences. */
    fun classify(rawLine: String): ConsoleStyle {
        val text = stripAnsi(rawLine).trim()
        if (text.isEmpty()) return ConsoleStyle.NORMAL
        if (text.startsWith("> ")) return ConsoleStyle.PROMPT
        val lower = text.lowercase()
        val lowerWithoutZeroErrors = ZERO_ERROR_PATTERN.replace(lower, "")
        if (ERROR_HINTS.any { lowerWithoutZeroErrors.contains(it) }) return ConsoleStyle.ERROR
        if (WARNING_HINTS.any { lower.contains(it) }) return ConsoleStyle.WARNING
        if (SUCCESS_HINTS.any { lower.contains(it) }) return ConsoleStyle.SUCCESS
        if (TAG_PATTERN.matches(text)) return ConsoleStyle.TAG
        if (HEADER_PATTERN.matches(text)) return ConsoleStyle.HEADER
        if (HEX_PATTERN.matches(text)) return ConsoleStyle.DIM
        return ConsoleStyle.NORMAL
    }

    /** Removes every CSI escape sequence, for copying, sharing and tag parsing. */
    fun stripAnsi(text: String): String {
        if (text.indexOf(ESC) < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == ESC && index + 1 < text.length && text[index + 1] == '[') {
                val end = findCsiEnd(text, index + 2)
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
