// Colours for the console, in the spirit of a terminal theme: a green prompt,
// red errors, amber warnings, cyan tag facts. Fixed hues are used instead of
// the Material roles because console text must stay readable on both themes
// and keep the meaning of the colours stable.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.farmacia.pm3android.ui.console.AnsiColor
import com.farmacia.pm3android.ui.console.ConsoleStyle

/** Resolved colours for one console line. */
data class TerminalPalette(
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

@Composable
@ReadOnlyComposable
fun terminalPalette(): TerminalPalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        TerminalPalette(
            normal = MaterialTheme.colorScheme.onSurface,
            dim = Color(0xFF9AA4AE),
            prompt = Color(0xFF7BE38B),
            error = Color(0xFFFF6B6B),
            warning = Color(0xFFFFC857),
            success = Color(0xFF7BE38B),
            tag = Color(0xFF7FD4F5),
            header = Color(0xFFC9A7FF),
        )
    } else {
        TerminalPalette(
            normal = MaterialTheme.colorScheme.onSurface,
            dim = Color(0xFF6B7280),
            prompt = Color(0xFF1B7F3B),
            error = Color(0xFFC62828),
            warning = Color(0xFF8A6D00),
            success = Color(0xFF1B7F3B),
            tag = Color(0xFF0B6E8A),
            header = Color(0xFF6A3FA0),
        )
    }
}

/** Colour asked for by an explicit ANSI code in script output. */
fun ansiColor(color: AnsiColor, dark: Boolean): Color {
    return if (dark) {
        when (color) {
            AnsiColor.BLACK -> Color(0xFF6B7280)
            AnsiColor.RED -> Color(0xFFFF6B6B)
            AnsiColor.GREEN -> Color(0xFF7BE38B)
            AnsiColor.YELLOW -> Color(0xFFFFD866)
            AnsiColor.BLUE -> Color(0xFF74A8FF)
            AnsiColor.MAGENTA -> Color(0xFFE58BFF)
            AnsiColor.CYAN -> Color(0xFF7FD4F5)
            AnsiColor.WHITE -> Color(0xFFE6E6E6)
            AnsiColor.BRIGHT_BLACK -> Color(0xFF9AA4AE)
            AnsiColor.BRIGHT_RED -> Color(0xFFFF9A9A)
            AnsiColor.BRIGHT_GREEN -> Color(0xFFA6F0B1)
            AnsiColor.BRIGHT_YELLOW -> Color(0xFFFFE9A3)
            AnsiColor.BRIGHT_BLUE -> Color(0xFFA9C7FF)
            AnsiColor.BRIGHT_MAGENTA -> Color(0xFFF0B6FF)
            AnsiColor.BRIGHT_CYAN -> Color(0xFFB3E8FB)
            AnsiColor.BRIGHT_WHITE -> Color(0xFFFFFFFF)
        }
    } else {
        when (color) {
            AnsiColor.BLACK -> Color(0xFF263238)
            AnsiColor.RED -> Color(0xFFC62828)
            AnsiColor.GREEN -> Color(0xFF1B7F3B)
            AnsiColor.YELLOW -> Color(0xFF8A6D00)
            AnsiColor.BLUE -> Color(0xFF1565C0)
            AnsiColor.MAGENTA -> Color(0xFF7B1FA2)
            AnsiColor.CYAN -> Color(0xFF00838F)
            AnsiColor.WHITE -> Color(0xFF37474F)
            AnsiColor.BRIGHT_BLACK -> Color(0xFF546E7A)
            AnsiColor.BRIGHT_RED -> Color(0xFFD32F2F)
            AnsiColor.BRIGHT_GREEN -> Color(0xFF2E7D32)
            AnsiColor.BRIGHT_YELLOW -> Color(0xFF9A7B00)
            AnsiColor.BRIGHT_BLUE -> Color(0xFF1976D2)
            AnsiColor.BRIGHT_MAGENTA -> Color(0xFF8E24AA)
            AnsiColor.BRIGHT_CYAN -> Color(0xFF0097A7)
            AnsiColor.BRIGHT_WHITE -> Color(0xFF111827)
        }
    }
}
