package com.example.musicfy.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.useReaderConnection
import com.example.musicfy.core.platform.DesktopAppDirs
import com.example.musicfy.core.resolver.ResolverPlatform
import com.example.musicfy.core.resolver.UnsupportedBotGuardRuntime
import com.example.musicfy.db.desktop.DESKTOP_DB_NAME
import com.example.musicfy.db.desktop.DesktopDao
import com.example.musicfy.db.desktop.DesktopMusicDatabase
import com.example.musicfy.db.entities.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.dsl.koinApplication

class DesktopModuleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun graphResolvesAndTheDatabaseOpensInTheDataDir() = runBlocking {
        val root = tmp.newFolder("musicfy")
        val dirs = DesktopAppDirs.resolve(
            osName = "Linux",
            env = mapOf("XDG_DATA_HOME" to root.resolve("data").path, "XDG_CACHE_HOME" to root.resolve("cache").path),
            home = root,
        )
        val koin = koinApplication { modules(desktopModule(dirs)) }.koin

        val dao = koin.get<DesktopDao>()
        dao.insert(SearchHistory(query = "probe"))
        assertEquals(listOf("probe"), dao.searchHistory().first().map { it.query })
        assertTrue(dirs.data.resolve(DESKTOP_DB_NAME).exists())
        val journalMode = koin.get<DesktopMusicDatabase>().useReaderConnection { connection ->
            connection.usePrepared("PRAGMA journal_mode") { statement ->
                statement.step()
                statement.getText(0)
            }
        }
        assertEquals("wal", journalMode.lowercase())
        koin.get<DataStore<Preferences>>()
        koin.get<SearchController>()
        assertSame(UnsupportedBotGuardRuntime, koin.get<ResolverPlatform>().botGuardRuntime)

        koin.get<DesktopMusicDatabase>().close()
        koin.get<CoroutineScope>().cancel()
        koin.close()
    }
}
