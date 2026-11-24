package com.example.musicfy.ritme.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFollowerTest {

    private fun eval(
        audio: Long,
        video: Long,
        audioSpeed: Float = 1f,
        videoSpeed: Float = 1f,
        advancing: Boolean = true,
        now: Long = 10_000,
        lastSeek: Long? = null,
    ) = VideoFollower.evaluate(audio, video, audioSpeed, videoSpeed, advancing, now, lastSeek)

    @Test
    fun inSyncIsLeftAlone() {
        assertEquals(FollowAction.Ignore, eval(5_000, 5_030))
        assertEquals(FollowAction.Ignore, eval(5_000, 4_970))
    }

    @Test
    fun inSyncTheVideoTakesTheAudiosOwnSpeed() {
        // the room's DriftCorrector runs the audio at 0.97: the picture goes along
        assertEquals(FollowAction.SetSpeed(0.97f), eval(5_000, 5_010, audioSpeed = 0.97f))
    }

    @Test
    fun aVideoBehindSpeedsUpAndOneAheadSlowsDown() {
        assertEquals(FollowAction.SetSpeed(1f * VideoFollower.FAST_FACTOR), eval(5_000, 4_900))
        assertEquals(FollowAction.SetSpeed(1f * VideoFollower.SLOW_FACTOR), eval(5_000, 5_100))
        assertEquals(FollowAction.Ignore, eval(5_000, 5_100, videoSpeed = 1f * VideoFollower.SLOW_FACTOR))
    }

    @Test
    fun theCorrectionRidesOnTheAudiosCorrection() {
        assertEquals(FollowAction.SetSpeed(1.03f * VideoFollower.FAST_FACTOR), eval(5_000, 4_900, audioSpeed = 1.03f))
    }

    @Test
    fun aRunningCorrectionContinuesUntilUnder20Ms() {
        assertEquals(FollowAction.Ignore, eval(5_000, 4_970, videoSpeed = VideoFollower.FAST_FACTOR))
        assertEquals(FollowAction.SetSpeed(1f), eval(5_000, 4_990, videoSpeed = VideoFollower.FAST_FACTOR))
    }

    @Test
    fun anOvershootStopsTheCorrection() {
        assertEquals(FollowAction.SetSpeed(1f), eval(5_000, 5_030, videoSpeed = VideoFollower.FAST_FACTOR))
    }

    @Test
    fun farOffSeeksALittleAheadOfTheAudio() {
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 3_000))
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 5_401))
    }

    @Test
    fun seeksAreAtMostOnceEveryTwoSeconds() {
        assertEquals(FollowAction.Ignore, eval(5_000, 3_000, now = 10_000, lastSeek = 8_500))
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 3_000, now = 10_000, lastSeek = 8_000))
    }

    @Test
    fun aPausedOrBufferingAudioGetsTheExactFrame() {
        assertEquals(FollowAction.Seek(5_000), eval(5_000, 5_200, advancing = false, lastSeek = 9_900))
        assertEquals(FollowAction.Ignore, eval(5_000, 5_020, advancing = false))
    }
}
