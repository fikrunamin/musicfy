package com.example.musicfy.desktop

import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.musicfy.constants.ContentCountryKey
import com.example.musicfy.core.settings.createSettingsDataStore
import com.example.musicfy.db.desktop.DesktopMusicDatabase
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val song = SongItem(
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artists = listOf(Artist(name = "Rick Astley", id = "UCuAXFkgsw1L7xaCfnd5JJOw")),
        thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    )

    @Test
    fun submitStoresTheQueryShowsResultsAndReadsSettings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder<DesktopMusicDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val settings = createSettingsDataStore(tmp.newFolder("settings"), scope)
        settings.edit { it[ContentCountryKey] = "ID" }
        val queries = mutableListOf<String>()
        val controller = SearchController(
            search = { q -> queries += q; Result.success(listOf(song)) },
            dao = db.dao,
            settings = settings,
            scope = scope,
        )

        controller.start()
        controller.onQueryChange("  daft punk ")
        controller.submit().join()

        val state = withTimeout(5_000) {
            controller.state.first { it.recentQueries == listOf("daft punk") && it.contentCountry == "ID" }
        }
        assertEquals(listOf("daft punk"), queries)
        assertEquals(listOf(song), state.results)
        assertEquals(null, state.error)
        scope.cancel()
        db.close()
    }

    @Test
    fun failedSearchShowsTheError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder<DesktopMusicDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val controller = SearchController(
            search = { Result.failure(IllegalStateException("offline")) },
            dao = db.dao,
            settings = createSettingsDataStore(tmp.newFolder("settings"), scope),
            scope = scope,
        )
        controller.onQueryChange("x")
        controller.submit().join()
        assertEquals("offline", controller.state.value.error)
        assertEquals(false, controller.state.value.loading)
        scope.cancel()
        db.close()
    }
}
