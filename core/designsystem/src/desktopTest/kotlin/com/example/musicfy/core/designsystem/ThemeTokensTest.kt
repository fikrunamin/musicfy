package com.example.musicfy.core.designsystem

import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the values the Android app shipped with before the tokens moved here. */
class ThemeTokensTest {
    @Test
    fun darkSchemeKeepsTheShippedPalette() = with(MusicfyDarkColorScheme) {
        assertEquals(Color(0xFFE0E0E0), primary)
        assertEquals(Color.Black, onPrimary)
        assertEquals(Color(0xFF333333), primaryContainer)
        assertEquals(Color.White, onPrimaryContainer)
        assertEquals(Color(0xFFB0B0B0), secondary)
        assertEquals(Color.Black, onSecondary)
        assertEquals(Color(0xFF2C2C2C), secondaryContainer)
        assertEquals(Color.White, onSecondaryContainer)
        assertEquals(Color.Black, background)
        assertEquals(Color.White, onBackground)
        assertEquals(Color.Black, surface)
        assertEquals(Color.White, onSurface)
        assertEquals(Color(0xFF1A1A1A), surfaceVariant)
        assertEquals(Color.White, onSurfaceVariant)
        assertEquals(Color.Black, surfaceTint)
    }

    @Test
    fun lightSchemeKeepsTheShippedPalette() = with(MusicfyLightColorScheme) {
        assertEquals(Color.Black, primary)
        assertEquals(Color.White, onPrimary)
        assertEquals(Color(0xFFE0E0E0), primaryContainer)
        assertEquals(Color.Black, onPrimaryContainer)
        assertEquals(Color(0xFF333333), secondary)
        assertEquals(Color.White, onSecondary)
        assertEquals(Color(0xFFCCCCCC), secondaryContainer)
        assertEquals(Color.Black, onSecondaryContainer)
        assertEquals(Color.White, background)
        assertEquals(Color.Black, onBackground)
        assertEquals(Color.White, surface)
        assertEquals(Color.Black, onSurface)
        assertEquals(Color(0xFFF0F0F0), surfaceVariant)
        assertEquals(Color.Black, onSurfaceVariant)
        assertEquals(Color.White, surfaceTint)
    }

    private fun styles(family: FontFamily) = with(musicfyTypography(family)) {
        listOf(
            displayLarge, displayMedium, displaySmall,
            headlineLarge, headlineMedium, headlineSmall,
            titleLarge, titleMedium, titleSmall,
            bodyLarge, bodyMedium, bodySmall,
            labelLarge, labelMedium, labelSmall,
        )
    }

    @Test
    fun everyStyleUsesTheGivenFamilySemiBoldAndTightTracking() {
        styles(FontFamily.Serif).forEach {
            assertEquals(FontFamily.Serif, it.fontFamily)
            assertEquals(FontWeight.SemiBold, it.fontWeight)
            assertEquals((-0.05).em, it.letterSpacing)
        }
    }

    @Test
    fun typeScaleMatchesTheAndroidApp() = assertEquals(
        listOf(
            57.sp to 64.sp, 45.sp to 52.sp, 36.sp to 44.sp,
            32.sp to 40.sp, 28.sp to 36.sp, 24.sp to 32.sp,
            22.sp to 28.sp, 16.sp to 24.sp, 14.sp to 20.sp,
            16.sp to 24.sp, 14.sp to 20.sp, 12.sp to 16.sp,
            14.sp to 20.sp, 12.sp to 16.sp, 11.sp to 16.sp,
        ),
        styles(FontFamily.Default).map { it.fontSize to it.lineHeight },
    )

    @Test
    fun shapesAreMaterialDefaults() = assertEquals(Shapes(), MusicfyShapes)
}
