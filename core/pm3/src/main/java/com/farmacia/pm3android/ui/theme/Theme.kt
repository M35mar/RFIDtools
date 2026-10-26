// Material 3 theme: light/dark/system mode, optional dynamic colour.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmacia.pm3android.data.AppSettings
import com.farmacia.pm3android.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Pm3Blue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFBDE9F8),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001F29),
    secondary = Pm3Teal,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF9EF2E3),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF00201C),
    tertiary = Pm3Amber,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDEA0),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF271900),
    error = Pm3Error,
    background = Pm3SurfaceLight,
    surface = Pm3SurfaceLight,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFDCE4E8),
    onSurface = Pm3Ink,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF40484C),
    outline = androidx.compose.ui.graphics.Color(0xFF70787C),
)

private val DarkScheme = darkColorScheme(
    primary = Pm3BlueDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003544),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004E63),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFBDE9F8),
    secondary = Pm3TealDark,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003731),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF005048),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF9EF2E3),
    tertiary = Pm3AmberDark,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF412D00),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF5E4200),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDEA0),
    error = Pm3ErrorDark,
    background = Pm3SurfaceDark,
    surface = Pm3SurfaceDark,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF40484C),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE1E3E5),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC0C8CC),
    outline = androidx.compose.ui.graphics.Color(0xFF8A9296),
)

private val Pm3Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val Pm3Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
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

    MaterialTheme(
        colorScheme = colors,
        typography = Pm3Typography,
        shapes = Pm3Shapes,
        content = content,
    )
}
