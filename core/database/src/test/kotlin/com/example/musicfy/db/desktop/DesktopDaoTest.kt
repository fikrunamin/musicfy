package com.example.musicfy.db.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.musicfy.db.entities.ArtistEntity
import com.example.musicfy.db.entities.Event
import com.example.musicfy.db.entities.PlaylistEntity
import com.example.musicfy.db.entities.SearchHistory
import com.example.musicfy.db.entities.Song
import com.example.musicfy.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class DesktopDaoTest {
    private lateinit var db: DesktopMusicDatabase
    private val dao get() = db.dao
    private val t = LocalDateTime.of(2026, 11, 22, 9, 15, 30)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder<DesktopMusicDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun insertedSongIsReadBackById() = runBlocking {
        dao.insert(SongEntity(id = "s1", title = "One", duration = 200))
        val song = dao.getSongById("s1")
        assertEquals("One", song?.song?.title)
        assertEquals(emptyList<ArtistEntity>(), song?.artists)
    }

    @Test
    fun likingASongPutsItInLikedSongs() = runBlocking {
        val entity = SongEntity(id = "s1", title = "One")
        dao.insert(entity)
        assertEquals(emptyList<Song>(), dao.likedSongsByCreateDateAsc().first())
        dao.update(entity.copy(liked = true, likedDate = t))
        val liked = dao.likedSongsByCreateDateAsc().first()
        assertEquals(listOf("s1"), liked.map { it.song.id })
        assertEquals(t, liked.single().song.likedDate)
    }

    @Test
    fun bookmarkedPlaylistIsListedWithSongCount() = runBlocking {
        dao.insert(PlaylistEntity(id = "LPtest0001", name = "Mix", createdAt = t, lastUpdateTime = t, bookmarkedAt = t))
        val playlists = dao.playlistsByCreateDateAsc().first()
        assertEquals(listOf("Mix"), playlists.map { it.playlist.name })
        assertEquals(0, playlists.single().songCount)
    }

    @Test
    fun playEventIsListedWithItsSong() = runBlocking {
        dao.insert(SongEntity(id = "s1", title = "One"))
        dao.insert(Event(songId = "s1", timestamp = t, playTime = 30_000))
        val event = dao.events().first().single()
        assertEquals("s1", event.song.song.id)
        assertEquals(t, event.event.timestamp)
        assertEquals(30_000L, event.event.playTime)
    }

    @Test
    fun repeatedSearchMovesToTheTop() = runBlocking {
        dao.insert(SearchHistory(query = "daft"))
        dao.insert(SearchHistory(query = "punk"))
        dao.insert(SearchHistory(query = "daft"))
        assertEquals(listOf("daft", "punk"), dao.searchHistory().first().map { it.query })
        assertEquals(listOf("punk"), dao.searchHistory("pu").first().map { it.query })
    }
}
