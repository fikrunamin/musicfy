package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeReducer
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeStageSlotTest {

    @Test
    fun noTargetAndFullScreenClosedMeansNoStage() {
        for (cover in listOf(false, true)) {
            assertEquals(RitmeStageSlot.NONE, RitmeStageSlot.of(hasTarget = false, fullscreenOpen = false, coverShown = cover))
        }
    }

    @Test
    fun theCoverHoldsTheStageWhileFullScreenIsClosed() {
        assertEquals(RitmeStageSlot.COVER, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = false, coverShown = true))
        assertEquals(RitmeStageSlot.NONE, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = false, coverShown = false))
    }

    @Test
    fun fullScreenTakesTheStageFromTheCover() {
        // one stage at a time: one follower, one texture, one screen-on hold
        assertEquals(RitmeStageSlot.FULLSCREEN, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = true, coverShown = true))
        assertEquals(RitmeStageSlot.FULLSCREEN, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = true, coverShown = false))
    }

    @Test
    fun fullScreenKeepsTheSlotWhileThePlayerCatchesUp() {
        // video -> video: the target is null for a moment; the cover must not take the slot meanwhile
        for (cover in listOf(false, true)) {
            assertEquals(RitmeStageSlot.FULLSCREEN, RitmeStageSlot.of(hasTarget = false, fullscreenOpen = true, coverShown = cover))
        }
    }

    private val video1 = entry("v1", kind = MediaKind.YOUTUBE_VIDEO, videoId = "dQw4w9WgXcQ")
    private val video2 = entry("v2", kind = MediaKind.YOUTUBE_VIDEO, videoId = "9bZkp7q19f0")
    private val link = entry("u1", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/film.mp4")
    private val song = entry("s")

    private fun current(e: QueueEntry) =
        room(queue = listOf(e) + listOf(video1, song).filter { it.entryId != e.entryId }, currentEntryId = e.entryId)

    @Test
    fun fullScreenStaysAcrossAVideoToVideoAdvance() {
        val room = current(video2)
        // the player still holds video 1: no stage target yet ...
        assertNull(RitmeStageTarget.of(room, loadedMediaId = video1.videoId))
        // ... but full screen stays open over black until it does
        assertTrue(RitmeStageSlot.fullscreenStays(room, inRoom = true))
        assertTrue(RitmeStageSlot.fullscreenStays(current(link), inRoom = true))
    }

    @Test
    fun fullScreenClosesWhenTheRoomMovesOnToASong() {
        assertFalse(RitmeStageSlot.fullscreenStays(current(song), inRoom = true))
        assertFalse(RitmeStageSlot.fullscreenStays(room(queue = listOf(video1), currentEntryId = null), inRoom = true))
    }

    @Test
    fun fullScreenClosesWhenThisDeviceLeavesTheRoom() {
        assertFalse(RitmeStageSlot.fullscreenStays(current(video1), inRoom = false))
        assertFalse(RitmeStageSlot.fullscreenStays(null, inRoom = false))
        val inRoom = RitmeUiState(phase = RitmePhase.IN_ROOM, room = current(video1))
        val left = RitmeReducer.reduce(inRoom, ConnectionEvent.Ended(EndReason.Left))
        assertFalse(RitmeStageSlot.fullscreenStays(left.room, left.inRoom))
        val lost = RitmeReducer.reduce(inRoom, ConnectionEvent.Ended(EndReason.Lost))
        assertFalse(RitmeStageSlot.fullscreenStays(lost.room, lost.inRoom))
    }

    @Test
    fun fullScreenClosesForAnUnplayableEntry() {
        assertFalse(RitmeStageSlot.fullscreenStays(current(link.copy(url = "http://10.0.0.2/x.mp4")), inRoom = true))
        assertFalse(RitmeStageSlot.fullscreenStays(current(link.copy(url = "ftp://cdn.example.com/x.mp4")), inRoom = true))
        assertFalse(RitmeStageSlot.fullscreenStays(current(video1.copy(videoId = "not a video id!")), inRoom = true))
    }

    @Test
    fun fullScreenStaysWhileTheConnectionReconnects() {
        // the session keeps the room (and IN_ROOM) while reconnecting; only Ended drops it
        val inRoom = RitmeUiState(phase = RitmePhase.IN_ROOM, connection = ConnectionStatus.ONLINE, room = current(video1))
        val reconnecting = RitmeReducer.reduce(inRoom, ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertTrue(RitmeStageSlot.fullscreenStays(reconnecting.room, reconnecting.inRoom))
    }
}
