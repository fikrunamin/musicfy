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
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

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
                // Stop collectors before the DB closes, then tear down Koin.
                koin.get<CoroutineScope>().cancel()
                koin.get<DesktopMusicDatabase>().close()
                stopKoin()
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
    // OkHttp's non-daemon dispatcher threads would keep the JVM alive for up to a minute.
    exitProcess(0)
}
