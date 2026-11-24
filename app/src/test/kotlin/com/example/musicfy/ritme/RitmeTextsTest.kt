package com.example.musicfy.ritme

import com.example.musicfy.R
import com.example.musicfy.ritme.session.NoticeKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeTextsTest {

    @Test
    fun addingLockedSaysSongsForSongsAndVideosForVideos() {
        assertEquals(R.string.ritme_toast_add_locked, RitmeTexts.notice(NoticeKey.ADD_FORBIDDEN))
        assertEquals(R.string.ritme_toast_add_video_locked, RitmeTexts.notice(NoticeKey.ADD_FORBIDDEN, videos = true))
        // other refusals read the same either way
        assertEquals(R.string.ritme_toast_queue_full, RitmeTexts.notice(NoticeKey.QUEUE_FULL, videos = true))
    }
}
