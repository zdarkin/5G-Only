package com.nsaanchor.gatekeeper.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Semantic Tactical Accent Colors
val EmeraldGreen = Color(0xFF10B981)
val EmeraldGreenDim = Color(0xFF064E3B)
val CrimsonRed = Color(0xFFEF4444)
val CrimsonRedDim = Color(0xFF7F1D1D)
val AmberWarning = Color(0xFFF59E0B)
val AmberWarningDim = Color(0xFF78350F)
val CyanAccent = Color(0xFF38BDF8)
val CyanAccentDim = Color(0xFF0C4A6E)

// Deep Slate Surfaces & Text
val DeepSlateBackground = Color(0xFF0B0F19)
val SlateCardSurface = Color(0xFF111827)
val SlateCardElevated = Color(0xFF1F2937)
val SlateBorder = Color(0xFF334155)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)
val TerminalBg = Color(0xFF060911)

private val TacticalDarkColorScheme = darkColorScheme(
    primary = EmeraldGreen,
    onPrimary = Color(0xFF022C22),
    primaryContainer = EmeraldGreenDim,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = CyanAccent,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = CyanAccentDim,
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = AmberWarning,
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = AmberWarningDim,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = CrimsonRed,
    onError = Color(0xFF450A0A),
    errorContainer = CrimsonRedDim,
    onErrorContainer = Color(0xFFFECACA),
    background = DeepSlateBackground,
    onBackground = TextPrimary,
    surface = SlateCardSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder
)

val TacticalTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

@Composable
fun NSA5GAnchorTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = TacticalDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = DeepSlateBackground.toArgb()
                window.navigationBarColor = DeepSlateBackground.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TacticalTypography,
        content = content
    )
}
