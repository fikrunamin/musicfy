package com.example.musicfy.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/** MaterialTheme with musicfy's tokens and bodyLarge as the default text style. */
@Composable
fun MusicfyBaseTheme(
    colorScheme: ColorScheme,
    typography: Typography,
    shapes: Shapes = MusicfyShapes,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = typography,
    ) {
        ProvideTextStyle(
            value = typography.bodyLarge,
            content = content
        )
    }
}
