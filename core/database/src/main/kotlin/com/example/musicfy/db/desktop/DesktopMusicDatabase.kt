package com.example.musicfy.db.desktop

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.musicfy.db.Converters
import com.example.musicfy.db.entities.AlbumArtistMap
import com.example.musicfy.db.entities.AlbumEntity
import com.example.musicfy.db.entities.ArtistEntity
import com.example.musicfy.db.entities.Event
import com.example.musicfy.db.entities.FormatEntity
import com.example.musicfy.db.entities.ImportedSong
import com.example.musicfy.db.entities.LyricsEntity
import com.example.musicfy.db.entities.PlayCountEntity
import com.example.musicfy.db.entities.PlaylistEntity
import com.example.musicfy.db.entities.PlaylistEvent
import com.example.musicfy.db.entities.PlaylistSongMap
import com.example.musicfy.db.entities.PlaylistSongMapPreview
import com.example.musicfy.db.entities.RecognitionHistory
import com.example.musicfy.db.entities.RelatedSongMap
import com.example.musicfy.db.entities.SearchHistory
import com.example.musicfy.db.entities.SetVideoIdEntity
import com.example.musicfy.db.entities.SongAlbumMap
import com.example.musicfy.db.entities.SongArtistMap
import com.example.musicfy.db.entities.SongEntity
import com.example.musicfy.db.entities.SortedSongAlbumMap
import com.example.musicfy.db.entities.SortedSongArtistMap
import com.example.musicfy.db.entities.SpeedDialItem

/**
 * The desktop copy of app/'s InternalDatabase: same entities and views in the same order, same
 * version. SchemaParityTest fails if the two ever drift. Keep both lists identical.
 */
@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SongArtistMap::class,
        SongAlbumMap::class,
        AlbumArtistMap::class,
        PlaylistSongMap::class,
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
        RelatedSongMap::class,
        SetVideoIdEntity::class,
        PlayCountEntity::class,
        RecognitionHistory::class,
        SpeedDialItem::class,
        PlaylistEvent::class,
        ImportedSong::class,
    ],
    views = [
        SortedSongArtistMap::class,
        SortedSongAlbumMap::class,
        PlaylistSongMapPreview::class,
    ],
    version = 39,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DesktopMusicDatabase : RoomDatabase() {
    abstract val dao: DesktopDao
}
