package com.example.musicfy.core.platform

import java.io.File

class DesktopAppDirs private constructor(
    override val data: File,
    override val cache: File,
    /**
     * Large, machine-local files: downloads, the browser component, the persisted queue. Windows keeps
     * them in %LOCALAPPDATA%\Musicfy so a roaming profile does not sync them; elsewhere this is [data].
     */
    val localData: File,
) : AppDirs {
    override val downloads: File
        get() = localData.resolve("download")

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
                os.startsWith("windows") -> {
                    val local = (envDir("LOCALAPPDATA") ?: home.resolve("AppData/Local")).resolve(APP_NAME)
                    DesktopAppDirs(
                        data = (envDir("APPDATA") ?: home.resolve("AppData/Roaming")).resolve(APP_NAME),
                        cache = local.resolve("Cache"),
                        localData = local,
                    )
                }
                os.startsWith("mac") -> {
                    val data = home.resolve("Library/Application Support").resolve(APP_NAME)
                    DesktopAppDirs(data = data, cache = home.resolve("Library/Caches").resolve(APP_NAME), localData = data)
                }
                else -> {
                    val data = (envDir("XDG_DATA_HOME") ?: home.resolve(".local/share")).resolve(LINUX_DIR)
                    DesktopAppDirs(
                        data = data,
                        cache = (envDir("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve(LINUX_DIR),
                        localData = data,
                    )
                }
            }
        }
    }
}
