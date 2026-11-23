package com.example.musicfy.ritme

import androidx.media3.common.MediaItem
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.playback.queues.EmptyQueue
import com.example.musicfy.playback.queues.ListQueue
import com.example.musicfy.playback.queues.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeQueueSongsTest {

    private fun song(id: String) = MediaMetadata(
        id = id,
        title = "Song $id",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
        duration = 200,
    )

    /** A radio-like queue: only its tapped song is known before the network load. */
    private class RadioQueue(override val preloadItem: MediaMetadata?) : Queue {
        override suspend fun getInitialStatus() = throw UnsupportedOperationException()
        override fun hasNextPage() = false
        override suspend fun nextPage() = throw UnsupportedOperationException()
    }

    // MediaItem.metadata reads the tag of localConfiguration, which needs a Uri (Android-only in a
    // JVM test), so these items carry no metadata; the test checks which items are taken.
    private fun items(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).build() }

    @Test
    fun aRadioQueueStartsWithItsTappedSong() {
        val tapped = song("a")
        assertEquals(listOf(tapped), RadioQueue(tapped).ritmeSongs())
    }

    @Test
    fun aListQueueStartsAtItsStartIndex() {
        assertEquals(2, ListQueue(items = items("a", "b", "c"), startIndex = 1).ritmeSongs().size)
        assertEquals(3, ListQueue(items = items("a", "b", "c"), startIndex = 0).ritmeSongs().size)
        assertEquals(3, ListQueue(items = items("a", "b", "c"), startIndex = -1).ritmeSongs().size)
    }

    @Test
    fun aQueueThatNeedsLoadingOffersNothing() {
        assertTrue(RadioQueue(null).ritmeSongs().isEmpty())
        assertTrue(EmptyQueue.ritmeSongs().isEmpty())
        assertTrue(ListQueue(items = emptyList()).ritmeSongs().isEmpty())
    }
}
