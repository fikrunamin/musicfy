package com.example.musicfy.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.musicfy.core.designsystem.MusicfyBaseTheme
import com.example.musicfy.core.designsystem.MusicfyDarkColorScheme
import com.example.musicfy.core.designsystem.musicfyTypography
import com.example.musicfy.core.platform.DesktopAppDirs
import com.example.musicfy.core.platform.StdoutLogger
import com.example.musicfy.core.resolver.ResolverRuntime
import com.example.musicfy.db.desktop.DesktopMusicDatabase
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    val dirs = DesktopAppDirs.forCurrentOs()
    dirs.data.mkdirs()
    val koin = startKoin { modules(desktopModule(dirs)) }.koin
    ResolverRuntime.install(koin.get())
    val contentLocale = runBlocking { applyInnerTubeLocale(koin.get()) }
    val controller = koin.get<SearchController>()
    controller.start()
    StdoutLogger.i("MusicfyDesktop", "started, data dir = ${dirs.data}, content locale = $contentLocale")

    application {
        Window(
            onCloseRequest = {
                koin.get<DesktopMusicDatabase>().close()
                exitApplication()
            },
            title = "Musicfy",
        ) {
            MusicfyBaseTheme(
                colorScheme = MusicfyDarkColorScheme,
                typography = musicfyTypography(FontFamily.Default),
            ) {
                SearchScreen(controller)
            }
        }
    }
}
