package com.example.musicfy.ritme.entries

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.testing.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeEntriesTest {

    private fun song(
        id: String = "dQw4w9WgXcQ",
        title: String = "Never Gonna",
        artists: List<String> = listOf("Rick", "Astley"),
        duration: Int = 213,
        thumb: String? = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
        localUri: String? = null,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = duration,
        thumbnailUrl = thumb,
        localUri = localUri,
    )

    @Test
    fun aSongBecomesANewEntry() {
        assertEquals(
            NewEntry(
                videoId = "dQw4w9WgXcQ",
                title = "Never Gonna",
                artist = "Rick, Astley",
                thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
                durationMs = 213_000,
            ),
            RitmeEntries.toNewEntry(song()),
        )
    }

    @Test
    fun localAndMalformedSongsAreNotShared() {
        assertNull(RitmeEntries.toNewEntry(song(localUri = "content://media/1")))
        assertNull(RitmeEntries.toNewEntry(song(id = "local:42")))
        assertNull(RitmeEntries.toNewEntry(song(title = "   ")))
        // only zero-width/filler characters: the server's hasVisible(cleanText(title)) would refuse the whole batch
        assertNull(RitmeEntries.toNewEntry(song(title = "\u200B\u3164 \u2800")))
        assertEquals("Lagu", RitmeEntries.toNewEntry(song(title = "\u200BLagu"))?.title?.replace("\u200B", ""))
        assertNull(RitmeEntries.toNewEntry(null))
    }

    @Test
    fun onlyYouTubeThumbnailsAreKept() {
        assertEquals("https://lh3.googleusercontent.com/a=w120", RitmeEntries.safeThumbnail("https://lh3.googleusercontent.com/a=w120"))
        assertEquals("https://yt3.ggpht.com/x", RitmeEntries.safeThumbnail("https://yt3.ggpht.com/x"))
        assertNull(RitmeEntries.safeThumbnail("http://i.ytimg.com/vi/x/hq.jpg"))
        assertNull(RitmeEntries.safeThumbnail("https://example.com/x.jpg"))
        assertNull(RitmeEntries.safeThumbnail("https://i.ytimg.com/" + "a".repeat(600)))
        assertNull(RitmeEntries.toNewEntry(song(thumb = "https://example.com/x.jpg"))?.thumbnailUrl)
    }

    @Test
    fun textAndDurationAreBounded() {
        val e = RitmeEntries.toNewEntry(song(title = "t".repeat(400), duration = -1))!!
        assertEquals(300, e.title.length)
        assertEquals(0L, e.durationMs)
    }

    @Test
    fun theInitialQueueStartsAtTheCurrentSongAndKeepsItsPosition() {
        val q = RitmeEntries.buildInitialQueue(listOf(song("aaaa"), song("bbbb"), song("cccc")), currentIndex = 1, positionMs = 42_000)
        assertEquals(listOf("bbbb", "cccc"), q.first.map { it.videoId })
        assertEquals(0, q.startIndex)
        assertEquals(42_000L, q.startPositionMs)
        assertEquals(0, q.skipped)
    }

    @Test
    fun aLocalCurrentSongStartsTheRoomFromZero() {
        val q = RitmeEntries.buildInitialQueue(listOf(song(localUri = "content://1"), song("bbbb")), currentIndex = 0, positionMs = 42_000)
        assertEquals(listOf("bbbb"), q.first.map { it.videoId })
        assertEquals(0L, q.startPositionMs)
        assertEquals(1, q.skipped)
    }

    @Test
    fun bigQueuesAreBatchedAndCappedAtFiveHundred() {
        val q = RitmeEntries.buildInitialQueue((1..600).map { song("s$it") }, currentIndex = 0, positionMs = 0)
        assertEquals(200, q.first.size)
        assertEquals(listOf(200, 100), q.overflow.map { it.size })
        assertEquals(500, q.total)
        assertEquals(InitialQueue.EMPTY, RitmeEntries.buildInitialQueue(emptyList(), 0, 0))
    }

    @Test
    fun addBatchesSkipLocalSongs() {
        val b = RitmeEntries.toAddBatches((1..250).map { song("s$it") } + song(localUri = "content://1"))
        assertEquals(listOf(200, 50), b.batches.map { it.size })
        assertEquals(250, b.count)
        assertEquals(1, b.skipped)
    }

    @Test
    fun onlyYouTubeKindsArePlayable() {
        assertTrue(RitmeEntries.isPlayable(entry("a")))
        assertTrue(RitmeEntries.isPlayable(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertFalse(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "")))
        assertFalse(RitmeEntries.isPlayable(entry("x", kind = MediaKind.UNKNOWN)))
    }

    @Test
    fun aRoomEntryBecomesPlayerMetadata() {
        val m = RitmeEntries.toMediaMetadata(entry("a", durationMs = 213_500))
        assertEquals("vida", m.id)
        assertEquals("Song a", m.title)
        assertEquals(listOf("Artist a"), m.artists.map { it.name })
        assertEquals(213, m.duration)
        assertNull(m.localUri)
    }
}
