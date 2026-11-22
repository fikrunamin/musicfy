package com.example.musicfy.core.platform

import java.io.File

class DesktopAppDirs private constructor(
    override val data: File,
    override val cache: File,
) : AppDirs {
    override val downloads: File
        get() = data.resolve("download")

    companion object {
        private const val APP_NAME = "Musicfy"
        private const val LINUX_DIR = "musicfy"

        fun forCurrentOs(): DesktopAppDirs = resolve(
            osName = System.getProperty("os.name").orEmpty(),
            env = System.getenv(),
            home = File(System.getProperty("user.home")),
        )

        fun resolve(osName: String, env: Map<String, String>, home: File): DesktopAppDirs {
            fun envDir(name: String): File? = env[name]?.takeIf { it.isNotBlank() }?.let(::File)
            val os = osName.lowercase()
            return when {
                os.startsWith("windows") -> DesktopAppDirs(
                    data = (envDir("APPDATA") ?: home.resolve("AppData/Roaming")).resolve(APP_NAME),
                    cache = (envDir("LOCALAPPDATA") ?: home.resolve("AppData/Local")).resolve(APP_NAME).resolve("Cache"),
                )
                os.startsWith("mac") -> DesktopAppDirs(
                    data = home.resolve("Library/Application Support").resolve(APP_NAME),
                    cache = home.resolve("Library/Caches").resolve(APP_NAME),
                )
                else -> DesktopAppDirs(
                    data = (envDir("XDG_DATA_HOME") ?: home.resolve(".local/share")).resolve(LINUX_DIR),
                    cache = (envDir("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve(LINUX_DIR),
                )
            }
        }
    }
}
