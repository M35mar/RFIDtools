// Material 3 theme: light/dark/system mode, optional dynamic colour, plus
// CompositionLocals for the resolved dark flag, chrome separation and accents.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.data.AppSettings
import com.farmacia.pm3android.data.ThemeMode

/** True when the active app theme is dark (respects [AppSettings.themeMode]). */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Semantic hardware-domain colours (USB, BT, HF, LF, Scripts, Danger). */
val LocalPm3Accents = staticCompositionLocalOf { DarkAccents }

/** Structural chrome colours for distinct TopBar, BottomBar and Card borders. */
val LocalPm3Chrome = staticCompositionLocalOf { DarkChrome }

val MaterialTheme.pm3Accents: Pm3Accents
    @Composable
    @ReadOnlyComposable
    get() = LocalPm3Accents.current

val MaterialTheme.pm3Chrome: Pm3ChromeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalPm3Chrome.current

private val LightScheme = lightColorScheme(
    primary = Pm3Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBE6FB),
    onPrimaryContainer = Color(0xFF062C43),
    secondary = Pm3Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F3D0),
    onSecondaryContainer = Color(0xFF022C22),
    tertiary = Pm3Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFDE68A),
    onTertiaryContainer = Color(0xFF451A03),
    error = Pm3Error,
    onError = Color.White,
    errorContainer = Color(0xFFFECDD3),
    onErrorContainer = Color(0xFF4C0519),
    background = Pm3SurfaceLight,
    surface = Pm3SurfaceLight,
    surfaceContainerLowest = Color(0xFFF5FAFD),
    surfaceContainerLow = Color(0xFFEFF7FB),
    surfaceContainer = Color(0xFFE4F0F6),
    surfaceContainerHigh = Color(0xFFD6E8F1),
    surfaceVariant = Color(0xFFC8E0EC),
    onSurface = Pm3Ink,
    onSurfaceVariant = Color(0xFF284C63),
    outline = Color(0xFF537B94),
    outlineVariant = Color(0xFFA8C9DC),
)

private val DarkScheme = darkColorScheme(
    primary = Pm3BlueDark,
    onPrimary = Color(0xFF041C2C),
    primaryContainer = Color(0xFF0C3B5E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Pm3TealDark,
    onSecondary = Color(0xFF032310),
    secondaryContainer = Color(0xFF094225),
    onSecondaryContainer = Color(0xFFDCFCE7),
    tertiary = Pm3AmberDark,
    onTertiary = Color(0xFF2B1300),
    tertiaryContainer = Color(0xFF572C04),
    onTertiaryContainer = Color(0xFFFEF3C7),
    error = Pm3ErrorDark,
    onError = Color(0xFF2F040E),
    errorContainer = Color(0xFF6B0F25),
    onErrorContainer = Color(0xFFFFE4E6),
    background = Pm3SurfaceDark,
    surface = Pm3SurfaceDark,
    surfaceContainerLowest = Color(0xFF04060A),
    surfaceContainerLow = Color(0xFF101722),
    surfaceContainer = Color(0xFF151E2C),
    surfaceContainerHigh = Color(0xFF1C2738),
    surfaceVariant = Color(0xFF243247),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFA3B3C6),
    outline = Color(0xFF5D728A),
    outlineVariant = Color(0xFF223044),
)

private val Pm3Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val Pm3Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun Pm3Theme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val accents = if (dark) DarkAccents else LightAccents
    val chrome = if (dark) DarkChrome else LightChrome

    CompositionLocalProvider(
        LocalIsDarkTheme provides dark,
        LocalPm3Accents provides accents,
        LocalPm3Chrome provides chrome,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = Pm3Typography,
            shapes = Pm3Shapes,
            content = content,
        )
    }
}
