package com.example.musicfy.desktop.player.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlcOptionsTest {
    @Test
    fun silentAddsTheDummyOutputAndNeverAddsHttpHeaders() {
        assertTrue("--aout=dummy" in vlcArgs(silent = true))
        assertFalse("--aout=dummy" in vlcArgs(silent = false))
        assertTrue(vlcArgs(silent = false).containsAll(listOf("--no-video", "--network-caching=1500", "--file-caching=300")))
        assertTrue(vlcArgs(silent = false).none { it.startsWith(":http") })
    }

    @Test
    fun bundledDirPrefersTheTestPropertyThenComposeResources() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        val resources = mapOf("compose.application.resources.dir" to dir.parent, VlcNatives.DIR_PROPERTY to dir.path)
        assertEquals(dir, VlcNatives.bundledDir(resources::get))
        assertEquals(null, VlcNatives.bundledDir(mapOf(VlcNatives.DIR_PROPERTY to "/definitely/missing")::get))
        assertEquals(null, VlcNatives.bundledDir(emptyMap<String, String>()::get))
    }
}
