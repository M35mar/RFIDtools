// PC-grade terminal colour palette for the Proxmark3 console.
//
// Inspired by Tokyo Night / Catppuccin Mocha dark terminals so ANSI colours,
// hex dumps, tag summaries and status badges stand out with high contrast —
// matching the desktop Proxmark3 terminal experience regardless of whether the
// app itself is in Light or Dark mode.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.farmacia.pm3android.ui.console.AnsiColor
import com.farmacia.pm3android.ui.console.ConsoleStyle

/** Resolved colours for the terminal canvas and fallback line styles. */
data class TerminalPalette(
    val background: Color,
    val border: Color,
    val normal: Color,
    val dim: Color,
    val prompt: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val tag: Color,
    val header: Color,
) {
    fun colorFor(style: ConsoleStyle): Color = when (style) {
        ConsoleStyle.NORMAL -> normal
        ConsoleStyle.DIM -> dim
        ConsoleStyle.PROMPT -> prompt
        ConsoleStyle.ERROR -> error
        ConsoleStyle.WARNING -> warning
        ConsoleStyle.SUCCESS -> success
        ConsoleStyle.TAG -> tag
        ConsoleStyle.HEADER -> header
    }
}

/**
 * Always returns a crisp dark terminal palette (like a desktop terminal window)
 * so ANSI colours have maximum contrast and match the PC Proxmark3 client.
 * Also supports an optional light terminal palette when [forceDarkCanvas] is false.
 */
@Composable
@ReadOnlyComposable
fun terminalPalette(forceDarkCanvas: Boolean = true): TerminalPalette {
    val dark = forceDarkCanvas || LocalIsDarkTheme.current
    return if (dark) {
        TerminalPalette(
            background = Color(0xFF05070B),
            border = Color(0xFF243449),
            normal = Color(0xFFEDF2F7),
            dim = Color(0xFF8B9BB0),
            prompt = Color(0xFF22C55E),
            error = Color(0xFFFF5C77),
            warning = Color(0xFFF59E0B),
            success = Color(0xFF22C55E),
            tag = Color(0xFF29B6F6),
            header = Color(0xFFA855F7),
        )
    } else {
        TerminalPalette(
            background = Color(0xFFF8FAFC),
            border = Color(0xFFCBD5E1),
            normal = Color(0xFF0F172A),
            dim = Color(0xFF64748B),
            prompt = Color(0xFF15803D),
            error = Color(0xFFBE123C),
            warning = Color(0xFFB45309),
            success = Color(0xFF15803D),
            tag = Color(0xFF0369A1),
            header = Color(0xFF7C3AED),
        )
    }
}

/** Vivid PC-terminal ANSI colours with high contrast on dark or light canvas. */
fun ansiColor(color: AnsiColor, dark: Boolean): Color {
    return if (dark) {
        when (color) {
            AnsiColor.BLACK -> Color(0xFF475569)
            AnsiColor.RED -> Color(0xFFF43F5E)
            AnsiColor.GREEN -> Color(0xFF34D399)
            AnsiColor.YELLOW -> Color(0xFFF59E0B)
            AnsiColor.BLUE -> Color(0xFF60A5FA)
            AnsiColor.MAGENTA -> Color(0xFFC084FC)
            AnsiColor.CYAN -> Color(0xFF22D3EE)
            AnsiColor.WHITE -> Color(0xFFE2E8F0)
            AnsiColor.BRIGHT_BLACK -> Color(0xFF64748B)
            AnsiColor.BRIGHT_RED -> Color(0xFFFF6B81)
            AnsiColor.BRIGHT_GREEN -> Color(0xFF4ADE80)
            AnsiColor.BRIGHT_YELLOW -> Color(0xFFFDE047)
            AnsiColor.BRIGHT_BLUE -> Color(0xFF93C5FD)
            AnsiColor.BRIGHT_MAGENTA -> Color(0xFFE879F9)
            AnsiColor.BRIGHT_CYAN -> Color(0xFF38BDF8)
            AnsiColor.BRIGHT_WHITE -> Color(0xFFFFFFFF)
        }
    } else {
        when (color) {
            AnsiColor.BLACK -> Color(0xFF1E293B)
            AnsiColor.RED -> Color(0xFFBE123C)
            AnsiColor.GREEN -> Color(0xFF15803D)
            AnsiColor.YELLOW -> Color(0xFFB45309)
            AnsiColor.BLUE -> Color(0xFF1D4ED8)
            AnsiColor.MAGENTA -> Color(0xFF7E22CE)
            AnsiColor.CYAN -> Color(0xFF0E7490)
            AnsiColor.WHITE -> Color(0xFF334155)
            AnsiColor.BRIGHT_BLACK -> Color(0xFF64748B)
            AnsiColor.BRIGHT_RED -> Color(0xFFE11D48)
            AnsiColor.BRIGHT_GREEN -> Color(0xFF16A34A)
            AnsiColor.BRIGHT_YELLOW -> Color(0xFFD97706)
            AnsiColor.BRIGHT_BLUE -> Color(0xFF2563EB)
            AnsiColor.BRIGHT_MAGENTA -> Color(0xFF9333EA)
            AnsiColor.BRIGHT_CYAN -> Color(0xFF0284C7)
            AnsiColor.BRIGHT_WHITE -> Color(0xFF0F172A)
        }
    }
}
