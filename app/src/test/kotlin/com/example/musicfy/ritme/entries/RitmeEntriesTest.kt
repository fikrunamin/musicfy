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

    // ---- boundaries ----

    @Test
    fun createCarriesExactly200AndBatchesAtTheBoundary() {
        fun q(n: Int) = RitmeEntries.buildInitialQueue((1..n).map { song("s$it") }, 0, 0)
        assertEquals(200, q(200).first.size)
        assertTrue(q(200).overflow.isEmpty())
        assertEquals(200, q(201).first.size)
        assertEquals(listOf(1), q(201).overflow.map { it.size })
        assertEquals(listOf(200, 100), q(500).overflow.map { it.size })
        assertEquals(500, q(500).total)
        assertEquals(500, q(501).total)
        assertEquals(listOf(200, 100), q(501).overflow.map { it.size })
    }

    @Test
    fun addBatchesBoundaries() {
        fun b(n: Int) = RitmeEntries.toAddBatches((1..n).map { song("s$it") })
        assertEquals(listOf(200), b(200).batches.map { it.size })
        assertEquals(listOf(200, 1), b(201).batches.map { it.size })
        assertEquals(500, b(500).count)
        assertEquals(listOf(200, 200, 100), b(501).batches.map { it.size })
        assertEquals(500, b(501).count)
    }

    @Test
    fun theCapStillReachesFiveHundredWithLocalSongsInterleaved() {
        val songs = (1..900).map { if (it % 3 == 0) song(localUri = "content://$it") else song("s$it") }
        val q = RitmeEntries.buildInitialQueue(songs, 0, 0)
        assertEquals(500, q.total)
        assertEquals(300, q.skipped)
        assertTrue((q.first + q.overflow.flatten()).none { it.videoId.isBlank() })
        val b = RitmeEntries.toAddBatches(songs)
        assertEquals(500, b.count)
        assertEquals(300, b.skipped)
    }

    @Test
    fun titleAndArtistLengthsAreBoundedAfterTrim() {
        assertEquals(300, RitmeEntries.toNewEntry(song(title = "t".repeat(300)))!!.title.length)
        assertEquals(300, RitmeEntries.toNewEntry(song(title = "t".repeat(301)))!!.title.length)
        assertEquals(299, RitmeEntries.toNewEntry(song(title = "t".repeat(299)))!!.title.length)
        assertEquals(300, RitmeEntries.toNewEntry(song(title = "  " + "t".repeat(300) + "  "))!!.title.length)
        assertEquals(300, RitmeEntries.toNewEntry(song(artists = listOf("a".repeat(300))))!!.artist.length)
        assertEquals(300, RitmeEntries.toNewEntry(song(artists = listOf("a".repeat(301))))!!.artist.length)
        assertEquals(299, RitmeEntries.toNewEntry(song(artists = listOf("a".repeat(299))))!!.artist.length)
        assertEquals(300, RitmeEntries.toNewEntry(song(artists = listOf("a".repeat(150), "b".repeat(150))))!!.artist.length.coerceAtMost(300))
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val emoji = "\uD83C\uDFB5"
        val title = RitmeEntries.toNewEntry(song(title = "t".repeat(299) + emoji + "x"))!!.title
        assertEquals("t".repeat(299), title)
        assertFalse(Character.isHighSurrogate(title.last()))
        val keep = RitmeEntries.toNewEntry(song(title = "t".repeat(298) + emoji + "x"))!!.title
        assertEquals("t".repeat(298) + emoji, keep)
        assertEquals(300, keep.length)
        val artist = RitmeEntries.toNewEntry(song(artists = listOf("a".repeat(299) + emoji)))!!.artist
        assertEquals("a".repeat(299), artist)
    }

    @Test
    fun thumbnailLengthBoundary() {
        val base = "https://i.ytimg.com/"
        assertEquals(512, (base + "a".repeat(512 - base.length)).length)
        assertEquals(base + "a".repeat(512 - base.length), RitmeEntries.safeThumbnail(base + "a".repeat(512 - base.length)))
        assertNull(RitmeEntries.safeThumbnail(base + "a".repeat(513 - base.length)))
    }

    @Test
    fun videoIdLengthBoundary() {
        assertEquals("a".repeat(64), RitmeEntries.toNewEntry(song(id = "a".repeat(64)))?.videoId)
        assertNull(RitmeEntries.toNewEntry(song(id = "a".repeat(65))))
        assertNull(RitmeEntries.toNewEntry(song(id = "")))
        assertEquals("a-_Z9", RitmeEntries.toNewEntry(song(id = "a-_Z9"))?.videoId)
        assertNull(RitmeEntries.toNewEntry(song(id = "a b")))
        assertTrue(RitmeEntries.isPlayable(entry("a", videoId = "a".repeat(64))))
        assertFalse(RitmeEntries.isPlayable(entry("a", videoId = "a".repeat(65))))
        assertFalse(RitmeEntries.isPlayable(entry("a", videoId = "")))
    }

    @Test
    fun durationClampBoundary() {
        assertEquals(86_400_000L, RitmeEntries.toNewEntry(song(duration = 86_400))!!.durationMs)
        assertEquals(86_400_000L, RitmeEntries.toNewEntry(song(duration = 86_401))!!.durationMs)
        assertEquals(86_399_000L, RitmeEntries.toNewEntry(song(duration = 86_399))!!.durationMs)
        assertEquals(86_400_000L, RitmeEntries.toNewEntry(song(duration = Int.MAX_VALUE))!!.durationMs)
        assertEquals(0L, RitmeEntries.toNewEntry(song(duration = 0))!!.durationMs)
        assertEquals(1000L, RitmeEntries.toNewEntry(song(duration = 1))!!.durationMs)
    }

    @Test
    fun hasVisibleTextMirrorsTheServer() {
        assertTrue(RitmeEntries.hasVisibleText("Never Gonna"))
        assertTrue(RitmeEntries.hasVisibleText("\u200B\u200Dx"))
        assertTrue(RitmeEntries.hasVisibleText("\uD83C\uDFB5"))
        assertFalse(RitmeEntries.hasVisibleText(""))
        assertFalse(RitmeEntries.hasVisibleText("   \t\n"))
        assertFalse(RitmeEntries.hasVisibleText("\u0301\u0302")) // combining marks only
        assertFalse(RitmeEntries.hasVisibleText("\u20DD")) // enclosing mark only
        assertFalse(RitmeEntries.hasVisibleText("\u3164\u115F\u1160\uFFA0")) // Hangul fillers only
        assertFalse(RitmeEntries.hasVisibleText("\u2800")) // blank braille
        assertFalse(RitmeEntries.hasVisibleText("\u200D\u200C\u00AD\u2060")) // format characters only
    }
}
