package com.example.musicfy.desktop.player.vlc

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import java.io.File

/**
 * libVLC as fetch-libvlc.sh lays it out: macOS `vlc/lib/libvlc.dylib` + `vlc/plugins/`, Windows
 * `vlc/libvlc.dll` + `vlc/plugins/<category>/`. Once found it does what vlcj's own OS strategies do:
 * preload libvlccore on macOS and point VLC_PLUGIN_PATH at the bundled plugins.
 */
class BundledVlcDiscoveryStrategy(private val root: File) : NativeDiscoveryStrategy {
    private val libDir: File = if (RuntimeUtil.isMac()) root.resolve("lib") else root
    private val libFile: File = libDir.resolve(if (RuntimeUtil.isWindows()) "libvlc.dll" else "libvlc.dylib")

    override fun supported(): Boolean = (RuntimeUtil.isMac() || RuntimeUtil.isWindows()) && libFile.isFile

    override fun discover(): String? = libDir.absolutePath.takeIf { libFile.isFile }

    override fun onFound(path: String): Boolean {
        if (RuntimeUtil.isMac()) {
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), path)
            NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName())
        }
        return true
    }

    override fun onSetPluginPath(path: String): Boolean {
        val plugins = root.resolve("plugins").absolutePath
        return if (RuntimeUtil.isWindows()) {
            LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=$plugins") == 0
        } else {
            LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", plugins, 1) == 0
        }
    }
}
