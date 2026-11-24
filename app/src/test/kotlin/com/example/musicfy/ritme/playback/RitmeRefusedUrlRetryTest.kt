package com.example.musicfy.ritme.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeRefusedUrlRetryTest {

    private val retry = RitmeRefusedUrlRetry()
    private fun step(toast: Boolean, seconds: Long) = RitmeRefusedUrlRetry.Step(toast, seconds * 1000)

    @Test
    fun aRefusedEntryToastsOnceAndBacksOffTo60Seconds() {
        assertEquals(
            listOf(step(true, 5), step(false, 15), step(false, 60), step(false, 60), step(false, 60)),
            List(5) { retry.next("ritme-url:a") },
        )
    }

    @Test
    fun anotherEntryStartsOverWithItsOwnToast() {
        retry.next("ritme-url:a")
        retry.next("ritme-url:a")
        assertEquals(step(true, 5), retry.next("ritme-url:b"))
        assertEquals(step(false, 15), retry.next("ritme-url:b"))
    }

    @Test
    fun anEntryThatPlayedStartsOverIfItIsRefusedAgain() {
        retry.next("ritme-url:a")
        retry.next("ritme-url:a")
        retry.reset()
        assertEquals(step(true, 5), retry.next("ritme-url:a"))
    }
}
