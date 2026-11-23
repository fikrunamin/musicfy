package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.cipher.PlayerJsFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerJsFetcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        TestResolver.install(tmp.root)
        TestResolver.writePlayerJsCache(tmp.root)
    }

    @Test
    fun freshCacheInDataDirIsServedWithoutNetwork() = runBlocking {
        assertEquals(TestResolver.PLAYER_JS to "testhash", PlayerJsFetcher.getPlayerJs())
    }

    @Test
    fun cacheStatusReadsDataDirCipherCache() {
        val status = PlayerJsFetcher.readCacheStatus()
        assertEquals("testhash", status.playerHash)
        assertEquals(TestResolver.PLAYER_JS.length.toLong(), status.sizeBytes)
    }

    @Test
    fun invalidateDeletesTheCache() {
        PlayerJsFetcher.invalidateCache()
        assertFalse(PlayerJsFetcher.readCacheStatus().hasCache)
    }
}
