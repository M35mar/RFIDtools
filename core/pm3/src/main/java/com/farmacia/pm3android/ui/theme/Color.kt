// Brand, chrome & semantic hardware-domain palette for RFIDtools.
//
// Designed for crisp, deep, high-contrast legibility in daily RFID work:
// deep obsidian surfaces, sharp structural dividers, clearly separated top/bottom
// bars and saturated (non-pastel) semantic accents per hardware domain.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.theme

import androidx.compose.ui.graphics.Color

// Core brand hues — deep, crisp, high-contrast (no pastel washout).
val Pm3Blue = Color(0xFF0369A1)
val Pm3BlueDark = Color(0xFF29B6F6)
val Pm3Teal = Color(0xFF047857)
val Pm3TealDark = Color(0xFF22C55E)
val Pm3Amber = Color(0xFFB45309)
val Pm3AmberDark = Color(0xFFF59E0B)
val Pm3Ink = Color(0xFF092234)
val Pm3SurfaceLight = Color(0xFFDCEBF2)
val Pm3SurfaceDark = Color(0xFF070A0F)
val Pm3Error = Color(0xFFBE123C)
val Pm3ErrorDark = Color(0xFFF43F5E)

/** Dedicated structural chrome colours so TopBar, Body, Cards & BottomBar never blend. */
data class Pm3ChromeColors(
    val topBarBg: Color,
    val bottomBarBg: Color,
    val bottomBarUnselected: Color,
    val chromeDivider: Color,
    val cardBg: Color,
    val cardBorder: Color,
)

/**
 * Nordic Ocean Petrol light theme: rich marine petrol TopBar/BottomBar,
 * frosted aqua-mist canvas and ocean-pearl cards — neither stark white, nor
 * dull grey, nor black.
 */
val LightChrome = Pm3ChromeColors(
    topBarBg = Color(0xFF0C4A6E),
    bottomBarBg = Color(0xFF0E3A54),
    bottomBarUnselected = Color(0xFFA7CBE0),
    chromeDivider = Color(0xFF1E6488),
    cardBg = Color(0xFFEFF7FB),
    cardBorder = Color(0xFFA8C9DC),
)

val DarkChrome = Pm3ChromeColors(
    topBarBg = Color(0xFF0B1018),
    bottomBarBg = Color(0xFF04060A),
    bottomBarUnselected = Color(0xFF7D8FA3),
    chromeDivider = Color(0xFF263549),
    cardBg = Color(0xFF101722),
    cardBorder = Color(0xFF223044),
)

/** Dedicated semantic accents for hardware domains & tool categories. */
data class Pm3Accents(
    val usb: Color,
    val bluetooth: Color,
    val hf: Color,
    val lf: Color,
    val script: Color,
    val danger: Color,
    val success: Color,
    val info: Color,
)

val LightAccents = Pm3Accents(
    usb = Color(0xFF0369A1),       // Deep Sky Cyan
    bluetooth = Color(0xFF4338CA), // Deep Royal Indigo
    hf = Color(0xFF047857),        // Deep Emerald
    lf = Color(0xFFB45309),        // Deep Amber
    script = Color(0xFF6D28D9),    // Deep Violet
    danger = Color(0xFFBE123C),    // Sharp Crimson
    success = Color(0xFF15803D),   // Deep Forest Green
    info = Color(0xFF0E7490),      // Deep Teal Cyan
)

val DarkAccents = Pm3Accents(
    usb = Color(0xFF29B6F6),       // Crisp Electric Cyan
    bluetooth = Color(0xFF7C83FF), // Crisp Royal Indigo
    hf = Color(0xFF22C55E),        // Crisp Emerald Green
    lf = Color(0xFFF59E0B),        // Crisp Rich Amber
    script = Color(0xFFA855F7),    // Crisp Vivid Purple
    danger = Color(0xFFF43F5E),    // Sharp Crimson Rose
    success = Color(0xFF22C55E),   // Crisp Green
    info = Color(0xFF06B6D4),      // Crisp Cyan
)
