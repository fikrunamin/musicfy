// Theme.kt

package com.example.musicfy.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.example.musicfy.core.designsystem.MusicfyBaseTheme
import com.example.musicfy.core.designsystem.MusicfyDarkColorScheme
import com.example.musicfy.core.designsystem.MusicfyLightColorScheme
import com.materialkolor.score.Score

val DefaultThemeColor = Color(0xFF8E8E8E)

/**
 * musicfy renders dark-only.
 *
 * The light palette was never finished - large parts of the UI hardcode white text and dark
 * glass - so on a light-mode phone the app looked broken rather than light. Pinned here as one
 * switch instead of being decided independently at every call site.
 */
const val ForceDarkTheme = true

@Composable
fun MusicfyTheme(
    darkTheme: Boolean = ForceDarkTheme,
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    // Dynamic (Material You) colour is deliberately not used - the app ships its own palette
    // (core:designsystem). The branch is gone rather than dead-but-present: dynamic*ColorScheme is API 31.
    val baseColorScheme = if (darkTheme) MusicfyDarkColorScheme else MusicfyLightColorScheme

    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme) {
        if (darkTheme && pureBlack) {
            baseColorScheme.pureBlack(true)
        } else {
            baseColorScheme
        }
    }

    MusicfyBaseTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

fun Bitmap.extractThemeColor(): Color {
    val colorsToPopulation = Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .swatches
        .associate { it.rgb to it.population }
    val rankedColors = Score.score(colorsToPopulation)
    return Color(rankedColors.first())
}

fun Bitmap.extractGradientColors(): List<Color> {
    val extractedColors = Palette.from(this)
        .maximumColorCount(64)
        .generate()
        .swatches
        .associate { it.rgb to it.population }

    val orderedColors = Score.score(extractedColors, 2, 0xff4285f4.toInt(), true)
        .sortedByDescending { Color(it).luminance() }

    return if (orderedColors.size >= 2)
        listOf(Color(orderedColors[0]), Color(orderedColors[1]))
    else
        listOf(Color(0xFF595959), Color(0xFF0D0D0D))
}

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
