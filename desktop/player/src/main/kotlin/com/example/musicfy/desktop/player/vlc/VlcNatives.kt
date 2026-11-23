package com.example.musicfy.desktop.player.vlc

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import java.io.File

/** Finds libVLC: the copy bundled in the app's resources on macOS/Windows, the system one on Linux. */
object VlcNatives {
    const val DIR_PROPERTY = "musicfy.vlc.dir"
    private const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"

    /** `-Dmusicfy.vlc.dir` (tests), else `<compose resources dir>/vlc` (`gradle run` and the packaged app). */
    fun bundledDir(properties: (String) -> String? = System::getProperty): File? {
        properties(DIR_PROPERTY)?.let { return File(it).takeIf(File::isDirectory) }
        return properties(COMPOSE_RESOURCES_PROPERTY)?.let { File(it, "vlc") }?.takeIf(File::isDirectory)
    }

    fun discovery(bundled: File? = bundledDir()): NativeDiscovery {
        val strategies = buildList<NativeDiscoveryStrategy> {
            bundled?.let { add(BundledVlcDiscoveryStrategy(it)) }
            if (System.getProperty("os.name").orEmpty().lowercase().startsWith("linux")) add(LinuxNativeDiscoveryStrategy())
        }
        return NativeDiscovery(*strategies.toTypedArray())
    }
}
