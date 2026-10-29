package com.example.ava.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

private val default = Typography()

private fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace)

/**
 * Monospace everywhere, like the text of an old terminal.
 */
val AppTypography = Typography(
    displayLarge = default.displayLarge.mono(),
    displayMedium = default.displayMedium.mono(),
    displaySmall = default.displaySmall.mono(),
    headlineLarge = default.headlineLarge.mono(),
    headlineMedium = default.headlineMedium.mono(),
    headlineSmall = default.headlineSmall.mono(),
    titleLarge = default.titleLarge.mono(),
    titleMedium = default.titleMedium.mono(),
    titleSmall = default.titleSmall.mono(),
    bodyLarge = default.bodyLarge.mono(),
    bodyMedium = default.bodyMedium.mono(),
    bodySmall = default.bodySmall.mono(),
    labelLarge = default.labelLarge.mono(),
    labelMedium = default.labelMedium.mono(),
    labelSmall = default.labelSmall.mono(),
)
