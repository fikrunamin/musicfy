package com.example.musicfy.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val GlobalFontWeight = FontWeight.SemiBold
private val GlobalLetterSpacing = (-0.05).em

/** The app's type scale. Android passes Inter (R.font); desktop passes its own family. */
fun musicfyTypography(fontFamily: FontFamily): Typography {
    fun style(size: Int, lineHeight: Int) = TextStyle(
        fontFamily = fontFamily,
        fontWeight = GlobalFontWeight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = GlobalLetterSpacing
    )
    return Typography(
        displayLarge = style(57, 64),
        displayMedium = style(45, 52),
        displaySmall = style(36, 44),
        headlineLarge = style(32, 40),
        headlineMedium = style(28, 36),
        headlineSmall = style(24, 32),
        titleLarge = style(22, 28),
        titleMedium = style(16, 24),
        titleSmall = style(14, 20),
        bodyLarge = style(16, 24),
        bodyMedium = style(14, 20),
        bodySmall = style(12, 16),
        labelLarge = style(14, 20),
        labelMedium = style(12, 16),
        labelSmall = style(11, 16)
    )
}
