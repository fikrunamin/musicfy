package com.example.musicfy.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DesktopAppDirsTest {
    private val home = File("/home/u")

    @Test
    fun windowsUsesAppDataAndKeepsLargeFilesInLocalAppData() {
        val dirs = DesktopAppDirs.resolve(
            osName = "Windows 11",
            env = mapOf("APPDATA" to "C:/Users/u/AppData/Roaming", "LOCALAPPDATA" to "C:/Users/u/AppData/Local"),
            home = home,
        )
        assertEquals(File("C:/Users/u/AppData/Roaming/Musicfy"), dirs.data)
        assertEquals(File("C:/Users/u/AppData/Local/Musicfy/Cache"), dirs.cache)
        assertEquals(File("C:/Users/u/AppData/Local/Musicfy"), dirs.localData)
        assertEquals(File("C:/Users/u/AppData/Local/Musicfy/download"), dirs.downloads)
    }

    @Test
    fun windowsFallsBackToHomeWhenEnvMissing() {
        val dirs = DesktopAppDirs.resolve("Windows 10", emptyMap(), home)
        assertEquals(File("/home/u/AppData/Roaming/Musicfy"), dirs.data)
        assertEquals(File("/home/u/AppData/Local/Musicfy/Cache"), dirs.cache)
        assertEquals(File("/home/u/AppData/Local/Musicfy"), dirs.localData)
    }

    @Test
    fun macUsesLibraryFoldersAndLocalDataIsData() {
        val dirs = DesktopAppDirs.resolve("Mac OS X", emptyMap(), File("/Users/u"))
        assertEquals(File("/Users/u/Library/Application Support/Musicfy"), dirs.data)
        assertEquals(File("/Users/u/Library/Caches/Musicfy"), dirs.cache)
        assertEquals(dirs.data, dirs.localData)
        assertEquals(File("/Users/u/Library/Application Support/Musicfy/download"), dirs.downloads)
    }

    @Test
    fun linuxDefaultsToXdgFallbacks() {
        val dirs = DesktopAppDirs.resolve("Linux", emptyMap(), home)
        assertEquals(File("/home/u/.local/share/musicfy"), dirs.data)
        assertEquals(File("/home/u/.cache/musicfy"), dirs.cache)
        assertEquals(dirs.data, dirs.localData)
    }

    @Test
    fun linuxHonoursXdgVariablesButIgnoresBlankOnes() {
        val dirs = DesktopAppDirs.resolve(
            osName = "Linux",
            env = mapOf("XDG_DATA_HOME" to "/data", "XDG_CACHE_HOME" to "  "),
            home = home,
        )
        assertEquals(File("/data/musicfy"), dirs.data)
        assertEquals(File("/home/u/.cache/musicfy"), dirs.cache)
    }
}
