package com.example.musicfy.ritme.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.musicfy.ritme.RitmeLocalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What RitmeSessionPlayer turns a controller's seek into while a room is active. (The player itself
 * needs a Looper, so the mapping is tested on its own.)
 */
class RitmeSessionRoutingTest {

    @Test
    fun nextAndPreviousBecomeRoomSkips() {
        assertEquals(RitmeLocalAction.Next, ritmeSeekIntent(Player.COMMAND_SEEK_TO_NEXT, C.TIME_UNSET))
        assertEquals(RitmeLocalAction.Next, ritmeSeekIntent(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, C.TIME_UNSET))
        assertEquals(RitmeLocalAction.Previous, ritmeSeekIntent(Player.COMMAND_SEEK_TO_PREVIOUS, C.TIME_UNSET))
        assertEquals(RitmeLocalAction.Previous, ritmeSeekIntent(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, 0))
    }

    @Test
    fun thePlayButtonsSeekToTheDefaultPositionOnAnEndedPlayerIsANoOp() {
        assertNull(ritmeSeekIntent(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, C.TIME_UNSET))
        assertNull(ritmeSeekIntent(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, 0))
    }

    @Test
    fun aSeekToAPositionBecomesARoomSeek() {
        assertEquals(RitmeLocalAction.Seek(42_000), ritmeSeekIntent(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 42_000))
        assertEquals(RitmeLocalAction.Seek(15_000), ritmeSeekIntent(Player.COMMAND_SEEK_FORWARD, 15_000))
        assertEquals(RitmeLocalAction.Seek(0), ritmeSeekIntent(Player.COMMAND_SEEK_BACK, -5_000))
        assertEquals(RitmeLocalAction.Seek(3_000), ritmeSeekIntent(Player.COMMAND_SEEK_TO_MEDIA_ITEM, 3_000))
    }

    @Test
    fun aSeekWithoutAPositionDoesNothing() {
        // e.g. "skip to queue item" from Android Auto: seekToDefaultPosition(index)
        assertNull(ritmeSeekIntent(Player.COMMAND_SEEK_TO_MEDIA_ITEM, C.TIME_UNSET))
    }

    @Test
    fun setMediaItemsOffersTheSongsFromTheStartIndex() {
        val items = listOf("a", "b", "c").map { MediaItem.Builder().setMediaId(it).build() }
        assertEquals(2, ritmeSongsFrom(items, 1).size)
        assertEquals(3, ritmeSongsFrom(items, C.INDEX_UNSET).size)
        assertEquals(0, ritmeSongsFrom(items, 5).size)
    }
}
