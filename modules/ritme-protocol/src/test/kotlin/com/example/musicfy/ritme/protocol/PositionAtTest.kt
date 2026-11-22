package com.example.musicfy.ritme.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionAtTest {
    private val entry = QueueEntry("e1", "vid", "Title", "Artist", null, durationMs = 200_000, addedBy = "m1")
    private fun state(playback: Playback, current: String? = "e1") =
        RoomState(code = "ABCDEF", version = 1, hostId = "m1", queue = listOf(entry), currentEntryId = current, playback = playback)

    @Test
    fun playingAdvancesWithServerTime() {
        assertEquals(15_000, state(Playback(true, 10_000, 1_000)).positionAt(6_000))
    }

    @Test
    fun pausedStaysPut() {
        assertEquals(10_000, state(Playback(false, 10_000, 1_000)).positionAt(99_000))
    }

    @Test
    fun anchorInTheFutureMeansNotStartedYet() {
        assertEquals(0, state(Playback(true, 0, 10_000)).positionAt(8_500))
    }

    @Test
    fun clampedToTrackLength() {
        assertEquals(200_000, state(Playback(true, 190_000, 0)).positionAt(60_000))
    }

    @Test
    fun nothingCurrentIsZero() {
        val idle = state(Playback(true, 5_000, 0), current = null)
        assertNull(idle.currentEntry())
        assertEquals(0, idle.positionAt(10_000))
    }

    @Test
    fun unknownDurationLiveStreamsAreNotClamped() {
        val live = QueueEntry("e1", "", "Live", "", null, durationMs = 0, addedBy = "m1", kind = MediaKind.URL, url = "https://example.com/live.m3u8")
        val state = RoomState(code = "ABCDEF", version = 1, hostId = "m1", queue = listOf(live), currentEntryId = "e1", playback = Playback(true, 0, 0))
        assertEquals(3_600_000, state.positionAt(3_600_000))
    }
}
