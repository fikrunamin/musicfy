package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeStageTargetTest {

    private val video = entry("v1", kind = MediaKind.YOUTUBE_VIDEO, videoId = "dQw4w9WgXcQ")
    private val link = entry("u1", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/film.mp4")

    private fun current(e: QueueEntry) = room(queue = listOf(e, entry("s")), currentEntryId = e.entryId)

    @Test
    fun aVideoEntryThePlayerHoldsGetsAStage() {
        assertEquals(
            RitmeStageTarget.YouTube("v1", "dQw4w9WgXcQ", null),
            RitmeStageTarget.of(current(video), loadedMediaId = "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun notWhileThePlayerStillHoldsThePreviousItem() {
        assertNull(RitmeStageTarget.of(current(video), loadedMediaId = "vids"))
        assertNull(RitmeStageTarget.of(current(link), loadedMediaId = "vids"))
    }

    @Test
    fun aUrlEntryThePlayerHoldsGetsAStage() {
        assertEquals(RitmeStageTarget.Url("u1"), RitmeStageTarget.of(current(link), loadedMediaId = "RITME_URL:u1"))
    }

    @Test
    fun songsUnsafeLinksAndNoRoomGetNone() {
        assertNull(RitmeStageTarget.of(current(entry("s")), loadedMediaId = "vids"))
        assertNull(RitmeStageTarget.of(current(link.copy(url = "http://10.0.0.2/x.mp4")), loadedMediaId = "RITME_URL:u1"))
        assertNull(RitmeStageTarget.of(null, loadedMediaId = "dQw4w9WgXcQ"))
    }
}
