package com.example.musicfy.db.desktop

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.musicfy.db.entities.Event
import com.example.musicfy.db.entities.EventWithSong
import com.example.musicfy.db.entities.Playlist
import com.example.musicfy.db.entities.PlaylistEntity
import com.example.musicfy.db.entities.SearchHistory
import com.example.musicfy.db.entities.Song
import com.example.musicfy.db.entities.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * Desktop queries. Room only allows suspend/Flow functions off Android, so this cannot be the
 * Android DatabaseDao. SQL is copied verbatim from DatabaseDao so both platforms read the same way.
 */
@Dao
interface DesktopDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: SongEntity): Long

    @Update
    suspend fun update(song: SongEntity)

    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): Song?

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate")
    fun likedSongsByCreateDateAsc(): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: PlaylistEntity)

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY rowId")
    fun playlistsByCreateDateAsc(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: Event)

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(searchHistory: SearchHistory)

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>
}
