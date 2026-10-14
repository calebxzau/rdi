package calebxzau.rdi.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private fun TextStyle.withUiFontFamily() = copy(fontFamily = UIFontFamily)

val AppTypography: Typography
    get() = Typography().run {
        copy(
            displayLarge = displayLarge.withUiFontFamily(),
            displayMedium = displayMedium.withUiFontFamily(),
            displaySmall = displaySmall.withUiFontFamily(),
            headlineLarge = headlineLarge.withUiFontFamily(),
            headlineMedium = headlineMedium.withUiFontFamily(),
            headlineSmall = headlineSmall.withUiFontFamily(),
            titleLarge = titleLarge.withUiFontFamily(),
            titleMedium = titleMedium.withUiFontFamily(),
            titleSmall = titleSmall.withUiFontFamily(),
            bodyLarge = bodyLarge.withUiFontFamily().copy(letterSpacing = 0.sp),
            bodyMedium = bodyMedium.withUiFontFamily().copy(letterSpacing = 0.sp),
            bodySmall = bodySmall.withUiFontFamily().copy(letterSpacing = 0.sp),
            labelLarge = labelLarge.withUiFontFamily().copy(letterSpacing = 0.sp),
            labelMedium = labelMedium.withUiFontFamily().copy(letterSpacing = 0.sp),
            // M3's default 0.5sp tracking spreads CJK text apart at this size.
            labelSmall = labelSmall.withUiFontFamily().copy(letterSpacing = 0.sp)
        )
    }

private val RdiColorScheme = lightColorScheme(
    primary = Color(0xFF0D6681),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBE9FF),
    onPrimaryContainer = Color(0xFF004D63),
    inversePrimary = Color(0xFF8AD0EE),
    secondary = Color(0xFF4C616B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE6F2),
    onSecondaryContainer = Color(0xFF354A53),
    tertiary = Color(0xFF5C5B7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2DFFF),
    onTertiaryContainer = Color(0xFF444465),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF5FAFD),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF5FAFD),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE4E8),
    onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF0D6681),
    inverseSurface = Color(0xFF2C3134),
    inverseOnSurface = Color(0xFFEDF1F5),
    surfaceDim = Color(0xFFD6DBDE),
    surfaceBright = Color(0xFFF5FAFD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEEF2),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDEE3E6),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CC),
    scrim = Color(0xFF000000),
    primaryFixed = Color(0xFFBBE9FF),
    primaryFixedDim = Color(0xFF8AD0EE),
    onPrimaryFixed = Color(0xFF001F29),
    onPrimaryFixedVariant = Color(0xFF004D63),
    secondaryFixed = Color(0xFFCFE6F2),
    secondaryFixedDim = Color(0xFFB4CAD5),
    onSecondaryFixed = Color(0xFF071E26),
    onSecondaryFixedVariant = Color(0xFF354A53),
    tertiaryFixed = Color(0xFFE2DFFF),
    tertiaryFixedDim = Color(0xFFC5C3EA),
    onTertiaryFixed = Color(0xFF181837),
    onTertiaryFixedVariant = Color(0xFF444465)
)

private val RdiShapes = Shapes(
    baseRoundCornerShape,
    baseRoundCornerShape,
    baseRoundCornerShape,
    baseRoundCornerShape,
    baseRoundCornerShape,
    baseRoundCornerShape,
    baseRoundCornerShape,
)

@Composable
fun RTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RdiColorScheme,
        typography = AppTypography,
        shapes = RdiShapes,
        content = content
    )
}

val themeNow @Composable
get() = MaterialTheme.colorScheme
