package com.example.musicfy.ritme.sync

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeSyncPlannerTest {

    private val e1 = entry("e1", durationMs = 200_000)

    private fun playingRoom(positionMs: Long = 0, anchor: Long = 10_000, version: Long = 1) = room(
        version = version,
        queue = listOf(e1, entry("e2")),
        currentEntryId = "e1",
        playback = Playback(isPlaying = true, positionMs = positionMs, anchorServerTimeMs = anchor),
    )

    private fun local(
        loaded: String? = "e1",
        pos: Long = 0,
        advancing: Boolean = false,
        pwr: Boolean = false,
        ended: Boolean = false,
        speed: Float = 1f,
    ) = LocalPlayerView(loaded, pos, advancing, pwr, ended, speed)

    private fun plan(
        room: RoomState?,
        now: Long,
        local: LocalPlayerView,
        lastKey: String? = null,
        force: Boolean = false,
        lastSeekAt: Long? = null,
    ) = RitmeSyncPlanner.plan(SyncInput(room, now, local, lastKey, localNowMs = now, lastSeekAtMs = lastSeekAt, force = force))

    private fun keyAt(room: RoomState, now: Long) = RitmeSyncPlanner.appliedKey(room, now)

    @Test
    fun aNewEntryIsLoadedPausedUntilTheAnchor() {
        val p = plan(playingRoom(), now = 9_000, local = local(loaded = null))
        assertEquals(listOf(PlayerOp.Load(e1, 0)), p.ops)
        assertEquals(10_000L, p.wakeAtServerMs)
    }

    @Test
    fun reachingTheAnchorStartsPlayback() {
        val room = playingRoom()
        val first = plan(room, 9_000, local(loaded = null))
        val p = plan(room, 10_020, local(pos = 0), lastKey = first.appliedKey)
        assertEquals(listOf(PlayerOp.Play), p.ops)
        assertNull(p.wakeAtServerMs)
    }

    @Test
    fun joiningMidSongLoadsAtTheRoomPositionAndPlays() {
        assertEquals(listOf(PlayerOp.Load(e1, 60_000), PlayerOp.Play), plan(playingRoom(), 70_000, local(loaded = null)).ops)
    }

    @Test
    fun runningAheadSlowsDown() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 10_300, advancing = true, pwr = true), lastKey = keyAt(room, 20_000))
        assertEquals(listOf(PlayerOp.SetSpeed(DriftCorrector.SLOWER)), p.ops)
    }

    @Test
    fun fallingFarBehindSeeks() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 8_500, advancing = true, pwr = true), lastKey = keyAt(room, 20_000))
        assertEquals(listOf(PlayerOp.SeekTo(10_000)), p.ops)
    }

    @Test
    fun aPausedRoomPausesAndAlignsThePlayer() {
        val room = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 42_000, 0))
        val p = plan(room, 50_000, local(pos = 40_000, pwr = true), lastKey = keyAt(room, 50_000))
        assertEquals(listOf(PlayerOp.Pause, PlayerOp.SeekTo(42_000)), p.ops)
    }

    @Test
    fun aSpeedCorrectionIsUndoneWhenTheRoomPauses() {
        val room = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 42_000, 0))
        val p = plan(room, 50_000, local(pos = 42_000, speed = DriftCorrector.SLOWER), lastKey = keyAt(room, 50_000))
        assertEquals(listOf(PlayerOp.SetSpeed(DriftCorrector.NORMAL)), p.ops)
    }

    @Test
    fun unsupportedEntriesHaltThePlayer() {
        val url = entry("u", kind = MediaKind.URL, videoId = "")
        val room = room(queue = listOf(url), currentEntryId = "u", playback = Playback(true, 0, 0))
        assertEquals(listOf(PlayerOp.Halt), plan(room, 1_000, local(pwr = true)).ops)
        assertEquals(emptyList<PlayerOp>(), plan(room, 1_000, local(pwr = false)).ops)
        assertNull(plan(room, 1_000, local()).appliedKey)
    }

    @Test
    fun aLocallyHeldPlayerIsLeftAloneUntilTheRoomChanges() {
        val room = playingRoom()
        assertEquals(emptyList<PlayerOp>(), plan(room, 20_000, local(pos = 5_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
    }

    @Test
    fun aLocalPauseResyncsAsSoonAsTheRoomChanges() {
        val room = playingRoom()
        // paused on this device only: every tick leaves it alone, drift and all
        assertEquals(emptyList<PlayerOp>(), plan(room, 20_000, local(pos = 4_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
        // someone with control seeks: the device follows the room again
        val seeked = playingRoom(positionMs = 50_000, anchor = 21_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(50_000), PlayerOp.Play), plan(seeked, 21_000, local(pos = 4_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
    }

    @Test
    fun aRoomSeekResyncsAndAResumeJustPlays() {
        val seeked = playingRoom(positionMs = 30_000, anchor = 20_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(30_100)), plan(seeked, 20_100, local(pos = 12_000, advancing = true, pwr = true)).ops)
        val resumed = playingRoom(positionMs = 12_000, anchor = 20_000, version = 3)
        assertEquals(listOf(PlayerOp.Play), plan(resumed, 20_050, local(pos = 12_000, pwr = false)).ops)
    }

    @Test
    fun anEndedTrackWaitsForTheRoom() {
        val room = playingRoom()
        assertEquals(emptyList<PlayerOp>(), plan(room, 215_000, local(pos = 200_000, ended = true), lastKey = keyAt(room, 215_000)).ops)
        val restarted = playingRoom(positionMs = 0, anchor = 215_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(0), PlayerOp.Play), plan(restarted, 215_000, local(pos = 200_000, ended = true)).ops)
    }

    @Test
    fun liveEntriesNeverSeek() {
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(listOf(PlayerOp.Load(live, null), PlayerOp.Play), plan(room, 5_000, local(loaded = null)).ops)
        val steady = plan(room, 50_000, local(loaded = "l", pos = 1_000, advancing = true, pwr = true), lastKey = keyAt(room, 50_000))
        assertEquals(emptyList<PlayerOp>(), steady.ops)
    }

    @Test
    fun aLiveEntryPlayedAgainAfterAPauseJumpsToTheLiveEdge() {
        // Bug 2 (spec 4.4): position 0 of a live stream is the start of its DVR window, not "now".
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(listOf(PlayerOp.SeekToLiveEdge, PlayerOp.Play), plan(room, 5_000, local(loaded = "l", pos = 30_000)).ops)
    }

    @Test
    fun aLiveEntryStillPlayingIsNotPulledToTheEdgeByARoomChange() {
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(emptyList<PlayerOp>(), plan(room, 5_000, local(loaded = "l", pos = 30_000, advancing = true, pwr = true)).ops)
    }

    @Test
    fun forceResyncsAnUnchangedRoom() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 10_600, advancing = true, pwr = true), lastKey = keyAt(room, 20_000), force = true)
        assertEquals(listOf(PlayerOp.SeekTo(10_000)), p.ops)
    }

    @Test
    fun noDriftCorrectionAtTheVeryEnd() {
        val room = playingRoom()
        val p = plan(room, 250_000, local(pos = 201_500, advancing = true, pwr = true), lastKey = keyAt(room, 250_000))
        assertEquals(emptyList<PlayerOp>(), p.ops)
    }

    @Test
    fun theUsersOwnSpeedIsNeutralisedInARoom() {
        val room = playingRoom()
        // on a room change
        assertEquals(listOf(PlayerOp.SetSpeed(DriftCorrector.NORMAL)), plan(room, 20_000, local(pos = 10_000, advancing = true, pwr = true, speed = 1.25f)).ops)
        // and on a steady tick, even in sync: it is not a running correction
        assertEquals(
            listOf(PlayerOp.SetSpeed(DriftCorrector.NORMAL)),
            plan(room, 20_000, local(pos = 10_010, advancing = true, pwr = true, speed = 1.25f), lastKey = keyAt(room, 20_000)).ops,
        )
    }

    @Test
    fun aQueueOnlyChangeKeepsALocalPause() {
        val room = playingRoom()
        val queueChanged = room.copy(version = 2, queue = room.queue + entry("e3"))
        assertEquals(emptyList<PlayerOp>(), plan(queueChanged, 20_000, local(pos = 4_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
    }

    @Test
    fun aRoomStoppedAtTheEndOfTheQueueDoesNotSeekEveryTick() {
        // the server stops at the end as Playback(false, durationMs, now); the real stream is a
        // little shorter than the rounded duration, so the player sits short of it
        val stopped = room(version = 3, queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 200_000, 216_000))
        assertEquals(emptyList<PlayerOp>(), plan(stopped, 216_000, local(pos = 199_400, ended = true)).ops)
        assertEquals(emptyList<PlayerOp>(), plan(stopped, 217_000, local(pos = 199_400, ended = true), lastKey = keyAt(stopped, 217_000)).ops)
        // paused just short of the end (not ended): ExoPlayer clamps a seek there, so do not ask again
        assertEquals(emptyList<PlayerOp>(), plan(stopped, 217_000, local(pos = 199_400), lastKey = keyAt(stopped, 217_000)).ops)
    }

    @Test
    fun aRoomPausedNearTheEndStillAlignsAPlayerThatIsFarAway() {
        val stopped = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 200_000, 216_000))
        assertEquals(listOf(PlayerOp.SeekTo(200_000)), plan(stopped, 217_000, local(pos = 150_000), lastKey = keyAt(stopped, 217_000)).ops)
        val nearEnd = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 199_000, 216_000))
        assertEquals(listOf(PlayerOp.SeekTo(199_000)), plan(nearEnd, 217_000, local(pos = 150_000), lastKey = keyAt(nearEnd, 217_000)).ops)
    }

    @Test
    fun aPausedSeekAwayFromTheEndRealignsAnEndedDevice() {
        // This device played e1 to its end; then the room was paused and seeked back to 1:00.
        val seekedBack = room(version = 4, queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 60_000, 220_000))
        assertEquals(
            listOf(PlayerOp.SeekTo(60_000)),
            plan(seekedBack, 221_000, local(pos = 200_000, ended = true), lastKey = keyAt(seekedBack, 221_000)).ops,
        )
    }
}
