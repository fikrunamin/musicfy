package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.NoticeKey
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.FakeLocalPlayback
import com.example.musicfy.ritme.testing.FakeNotifier
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RitmeControllerTest {

    private val connector = FakeConnector()
    private val timers = FakeTimers()
    private val session = RitmeSession(connector, timers)
    private val gate = RitmeGate()
    private val toasts = FakeNotifier()
    private val local = FakeLocalPlayback()
    private val settings = object : RitmeSettings {
        override val serverUrl = "https://ritme.dubeli.id"
        override val displayName = "Budi"
    }
    private val controller = RitmeController(session, gate, toasts, settings).also { it.localPlayback = local }

    private val playing = room(
        queue = listOf(entry("e1")),
        currentEntryId = "e1",
        playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = 0),
    )
    private val paused = playing.copy(playback = Playback(isPlaying = false, positionMs = 5_000, anchorServerTimeMs = 0))

    private fun inRoom(state: RoomState, memberId: String = "m1") {
        session.join(settings.serverUrl, state.code, "Budi")
        connector.last.welcome(state, memberId)
    }

    private val commands get() = connector.last.commands

    private fun song(id: String, local: Boolean = false) = MediaMetadata(
        id = id,
        title = "Song $id",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
        duration = 200,
        localUri = if (local) "content://media/$id" else null,
    )

    @Test
    fun aLockedGuestGetsAToastInsteadOfACommand() {
        inRoom(paused.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Next)
        controller.handle(RitmeLocalAction.Previous)
        controller.handle(RitmeLocalAction.Seek(1_000))
        assertTrue(commands.isEmpty())
        assertEquals(List<RitmeToast>(3) { RitmeToast.ControlsLocked }, toasts.shown)
    }

    @Test
    fun aLockedGuestPausesAndResumesOnlyTheirOwnDevice() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.Pause)
        assertEquals(1, local.pausedLocally)
        assertEquals(listOf<RitmeToast>(RitmeToast.PausedLocally), toasts.shown)
        controller.handle(RitmeLocalAction.TogglePlayPause) // paused locally, room playing: resume this device
        assertEquals(1, local.resumed)
        controller.handle(RitmeLocalAction.TogglePlayPause) // playing again: pause this device again
        assertEquals(2, local.pausedLocally)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aLockedGuestCannotStartAPausedRoom() {
        inRoom(paused.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Play)
        assertEquals(0, local.resumed)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.ControlsLocked), toasts.shown)
    }

    @Test
    fun aMemberWithControlPausesTheWholeRoom() {
        inRoom(playing, memberId = "m2")
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.Pause)
        assertEquals(listOf<Command>(Command.Pause), commands)
        assertEquals(0, local.pausedLocally)
    }

    @Test
    fun aGuestWithControlSendsCommands() {
        inRoom(paused, memberId = "m2")
        controller.handle(RitmeLocalAction.Next)
        controller.handle(RitmeLocalAction.Seek(42_000))
        controller.handle(RitmeLocalAction.Play)
        assertEquals(listOf(Command.Next, Command.Seek(42_000), Command.Play), commands)
    }

    @Test
    fun playWhileTheRoomPlaysResumesLocallyEvenWhenLocked() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Play)
        assertEquals(1, local.resumed)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun toggleFollowsTheLocalPlayer() {
        inRoom(playing)
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(listOf<Command>(Command.Pause), commands)
        local.isPlayingLocally = false
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(1, local.resumed)
    }

    @Test
    fun commandsDroppedWhileReconnectingSaySo() {
        inRoom(paused)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        controller.handle(RitmeLocalAction.Next)
        assertEquals(listOf<RitmeToast>(RitmeToast.Reconnecting), toasts.shown)
    }

    @Test
    fun addingSkipsLocalSongsAndBatchesByTwoHundred() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems((1..250).map { song("s$it") } + song("x", local = true), playNext = false))
        assertEquals(listOf(200, 50), commands.map { (it as Command.Add).entries.size })
        assertTrue(commands.all { !(it as Command.Add).playNext })
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(250)), toasts.shown)
    }

    @Test
    fun playNextSendsTheLastBatchFirstSoTheOrderSurvives() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems((1..250).map { song("s$it") }, playNext = true))
        assertEquals(listOf(50, 200), commands.map { (it as Command.Add).entries.size })
        assertTrue(commands.all { (it as Command.Add).playNext })
    }

    @Test
    fun playNextIntoAnEmptyRoomKeepsTheBatchOrder() {
        // Nothing is current, so there is no "right after the current entry" to stack batches on.
        inRoom(room())
        controller.handle(RitmeLocalAction.AddItems((1..250).map { song("s$it") }, playNext = true))
        assertEquals(listOf(200, 50), commands.map { (it as Command.Add).entries.size })
        assertEquals("s1", (commands.first() as Command.Add).entries.first().videoId)
    }

    @Test
    fun roomSheetCommandsDroppedWhileReconnectingSaySo() {
        inRoom(paused)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(controller.roomCommand { kick("m2") })
        assertFalse(controller.roomCommand { endRoom() })
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.Reconnecting, RitmeToast.Reconnecting), toasts.shown)
    }

    @Test
    fun roomSheetCommandsGoOutSilentlyWhenOnline() {
        inRoom(paused)
        assertTrue(controller.roomCommand { remove("e1") })
        assertEquals(listOf<Command>(Command.Remove("e1")), commands)
        assertTrue(toasts.shown.isEmpty())
    }

    @Test
    fun onlyLocalSongsAddNothing() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems(listOf(song("x", local = true)), playNext = false))
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.SkippedLocal(1)), toasts.shown)
    }

    @Test
    fun tappingASongInARoomAddsIt() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.PlayQueue(listOf(song("a"))))
        assertEquals(listOf<Command>(Command.Add(listOf(RitmeEntries.toNewEntry(song("a"))!!), playNext = false)), commands)
        controller.handle(RitmeLocalAction.PlayQueue(emptyList()))
        assertEquals(RitmeToast.NotAvailable, toasts.shown.last())
    }

    @Test
    fun localOnlyFeaturesAreNotAvailable() {
        inRoom(playing)
        listOf(RitmeLocalAction.Shuffle, RitmeLocalAction.Repeat, RitmeLocalAction.Speed, RitmeLocalAction.QueueEdit).forEach(controller::handle)
        assertTrue(commands.isEmpty())
        assertEquals(List<RitmeToast>(4) { RitmeToast.NotAvailable }, toasts.shown)
    }

    @Test
    fun startRoomBuildsTheInitialQueueFromThePlayOrder() {
        local.order = LocalPlayOrder(listOf(song("old"), song("a"), song("x", local = true), song("b")), currentIndex = 1, positionMs = 5_000)
        controller.startRoom("Budi")
        val expected = listOf(song("a"), song("b")).map { RitmeEntries.toNewEntry(it)!! }
        assertEquals(JoinIntent.Create("Budi", expected, 0, 5_000), connector.last.intent)
        assertEquals("wss://ritme.dubeli.id/ritme", connector.last.url)
        assertEquals(listOf<RitmeToast>(RitmeToast.SkippedLocal(1)), toasts.shown)
    }

    @Test
    fun startWithUsesTheGivenSongs() {
        controller.startRoom("Budi", startWith = listOf(song("z")))
        assertEquals(JoinIntent.Create("Budi", listOf(RitmeEntries.toNewEntry(song("z"))!!), 0, 0), connector.last.intent)
    }

    @Test
    fun castingBlocksStartingAndJoining() {
        local.isCasting = true
        controller.startRoom("Budi")
        controller.joinRoom("K7M2QX", "Budi")
        assertTrue(connector.connections.isEmpty())
        assertEquals(List<RitmeToast>(2) { RitmeToast.CastingBlocked }, toasts.shown)
    }

    @Test
    fun theGateRoutesToTheControllerOnlyWhileActive() {
        inRoom(paused)
        assertFalse(gate.intercept(RitmeLocalAction.Next))
        gate.activate()
        assertTrue(gate.intercept(RitmeLocalAction.Next))
        assertEquals(listOf<Command>(Command.Next), commands)
        gate.deactivate()
        assertFalse(gate.isActive)
    }

    @Test
    fun sheetRequestsPickEntryOrRoom() {
        controller.openJoin("K7M2QX")
        val first = controller.sheetRequest.value!!
        assertEquals(RitmeSheetRequest.Kind.Entry(code = "K7M2QX"), first.kind)
        controller.consumeSheetRequest(first.id)
        assertNull(controller.sheetRequest.value)
        inRoom(paused)
        controller.openSheet()
        assertEquals(RitmeSheetRequest.Kind.Room, controller.sheetRequest.value?.kind)
        assertEquals("https://ritme.dubeli.id/r/K7M2QX", controller.shareLink("K7M2QX"))
    }

    @Test
    fun aRoomLinkOpensTheJoinFormAndNeverJoinsByItself() {
        assertTrue(controller.openJoinLink("Listen with me on Ritme: https://ritme.dubeli.id/r/k7m2qx"))
        assertEquals(RitmeSheetRequest.Kind.Entry(code = "K7M2QX"), controller.sheetRequest.value?.kind)
        assertTrue(connector.connections.isEmpty())
        controller.consumeSheetRequest(controller.sheetRequest.value!!.id)
        assertFalse(controller.openJoinLink("https://www.reddit.com/r/travel"))
        assertFalse(controller.openJoinLink("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(controller.sheetRequest.value)
    }

    @Test
    fun roomLinksOfASelfHostedServerOpenTheJoinForm() {
        val selfHosted = object : RitmeSettings {
            override val serverUrl = "http://192.168.1.5:8080"
            override val displayName = "Budi"
        }
        val other = RitmeController(session, RitmeGate(), toasts, selfHosted)
        assertTrue(other.openJoinLink("http://192.168.1.5:8080/r/K7M2QX"))
        assertEquals(RitmeSheetRequest.Kind.Entry(code = "K7M2QX"), other.sheetRequest.value?.kind)
    }

    @Test
    fun theShareLinkKeepsTheServerTheRoomWasJoinedOn() {
        val changing = object : RitmeSettings {
            override var serverUrl = "http://192.168.1.5:8080"
            override val displayName = "Budi"
        }
        val other = RitmeController(session, RitmeGate(), toasts, changing)
        other.joinRoom("K7M2QX", "Budi")
        connector.last.welcome(paused, memberId = "m2")
        // Settings changed mid-room: only the next Ritme uses the new server.
        changing.serverUrl = "https://ritme.example.com"
        assertEquals("http://192.168.1.5:8080/r/K7M2QX", other.shareLink("K7M2QX"))
    }

    @Test
    fun theHostOfANewRoomGetsTheRoomSheetOnceAndJoinersDoNot() {
        controller.startRoom("Budi")
        assertNull(controller.sheetRequest.value)
        connector.last.welcome(paused, memberId = "m1")
        val opened = controller.sheetRequest.value!!
        assertEquals(RitmeSheetRequest.Kind.Room, opened.kind)
        controller.consumeSheetRequest(opened.id)
        // a resume (another Welcome on the same session) does not open it again
        connector.last.welcome(paused.copy(version = 2), memberId = "m1")
        assertNull(controller.sheetRequest.value)
    }

    @Test
    fun joiningSomeoneElsesRoomDoesNotOpenTheRoomSheet() {
        controller.joinRoom("K7M2QX", "Budi")
        connector.last.welcome(paused, memberId = "m2")
        assertNull(controller.sheetRequest.value)
    }

    // Carry-forward: no optimistic local play. A control-holder's Play is the room command only;
    // the device starts when the next snapshot says the room plays.
    @Test
    fun aControlHoldersPlayIsOnlyARoomCommand() {
        inRoom(paused)
        controller.handle(RitmeLocalAction.Play)
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(listOf<Command>(Command.Play, Command.Play), commands)
        assertEquals(0, local.resumed)
        assertTrue(toasts.shown.isEmpty())
    }

    // Carry-forward: a locked member's toggle in a paused room is refused, not resumed locally.
    @Test
    fun aLockedGuestsToggleInAPausedRoomIsRefused() {
        inRoom(paused.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(0, local.resumed)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.ControlsLocked), toasts.shown)
    }

    @Test
    fun theHostOfALockedRoomStillControlsIt() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m1")
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.Pause)
        controller.handle(RitmeLocalAction.Next)
        assertEquals(listOf(Command.Pause, Command.Next), commands)
        assertEquals(0, local.pausedLocally)
        assertTrue(toasts.shown.isEmpty())
    }

    @Test
    fun nothingHappensOutsideARoom() {
        controller.handle(RitmeLocalAction.Next)
        controller.handle(RitmeLocalAction.Play)
        assertTrue(connector.connections.isEmpty())
        assertEquals(0, local.resumed)
        assertTrue(toasts.shown.isEmpty())
    }

    private val addedIds get() = commands.flatMap { (it as Command.Add).entries.map { e -> e.videoId } }

    @Test
    fun addAllPutsTheListAtTheEndInTheGivenOrderPacedInBatchesOfTwoHundred() {
        inRoom(playing)
        val songs = (1..450).map { song("s$it") }
        controller.handle(RitmeLocalAction.AddAll(songs))
        // the first batch right away, the rest paced by the session
        assertEquals(listOf(200), commands.map { (it as Command.Add).entries.size })
        timers.advanceBy(10 * RitmeSession.OVERFLOW_GAP_MS)
        assertEquals(listOf(200, 200, 50), commands.map { (it as Command.Add).entries.size })
        assertTrue(commands.all { !(it as Command.Add).playNext })
        assertEquals(songs.map { it.id }, addedIds)
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(450)), toasts.shown)
    }

    @Test
    fun addAllKeepsAShuffledOrder() {
        inRoom(playing)
        val shuffled = (1..30).map { song("s$it") }.shuffled(Random(7))
        controller.handle(RitmeLocalAction.AddAll(shuffled))
        assertEquals(shuffled.map { it.id }, addedIds)
    }

    @Test
    fun addAllSkipsLocalSongs() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddAll(listOf(song("a"), song("x", local = true), song("b"))))
        assertEquals(listOf("a", "b"), addedIds)
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(2)), toasts.shown)
        controller.handle(RitmeLocalAction.AddAll(listOf(song("x", local = true), song("y", local = true))))
        assertEquals(2, addedIds.size)
        assertEquals(RitmeToast.SkippedLocal(2), toasts.shown.last())
        controller.handle(RitmeLocalAction.AddAll(emptyList()))
        assertEquals(RitmeToast.NotAvailable, toasts.shown.last())
    }

    @Test
    fun addAllIsCappedAtWhatTheRoomStillHolds() {
        // 1 entry in the room: 499 of 600 fit under the server's 500-entry cap
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddAll((1..600).map { song("s$it") }))
        timers.advanceBy(10 * RitmeSession.OVERFLOW_GAP_MS)
        assertEquals(listOf(200, 200, 99), commands.map { (it as Command.Add).entries.size })
        assertEquals(listOf<RitmeToast>(RitmeToast.AddedRestFull(499)), toasts.shown)
    }

    @Test
    fun addAllToAFullRoomSaysSoAndSendsNothing() {
        val full = playing.copy(queue = (1..RitmeEntries.MAX_QUEUE).map { entry("e$it") })
        inRoom(full)
        controller.handle(RitmeLocalAction.AddAll(listOf(song("a"))))
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.QUEUE_FULL)), toasts.shown)
    }

    @Test
    fun addAllWhileReconnectingSaysSo() {
        inRoom(playing)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        controller.handle(RitmeLocalAction.AddAll(listOf(song("a"))))
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.Reconnecting), toasts.shown)
    }

    @Test
    fun addAllGoesThroughTheGateOnlyInARoom() {
        inRoom(playing)
        assertFalse(gate.intercept(RitmeLocalAction.AddAll(listOf(song("a")))))
        assertTrue(commands.isEmpty())
        gate.activate()
        assertTrue(gate.intercept(RitmeLocalAction.AddAll(listOf(song("a")))))
        assertEquals(listOf("a"), addedIds)
    }

    @Test
    fun startWithAWholeListUsesItAsTheInitialQueue() {
        // "Start Ritme with this playlist/album/selected songs": the list, local files skipped
        val list = listOf(song("a"), song("x", local = true), song("b"))
        controller.openStartWith(list)
        assertEquals(RitmeSheetRequest.Kind.Entry(startWith = list), controller.sheetRequest.value?.kind)
        controller.startRoom("Budi", startWith = list)
        val expected = listOf(song("a"), song("b")).map { RitmeEntries.toNewEntry(it)!! }
        assertEquals(JoinIntent.Create("Budi", expected, 0, 0), connector.last.intent)
        assertEquals(listOf<RitmeToast>(RitmeToast.SkippedLocal(1)), toasts.shown)
    }

    @Test
    fun aListAddRightAfterABigStartCountsTheOverflowStillOwed() {
        // Start Ritme with 450 songs: the Create holds 200, 250 are still owed when another album is played.
        controller.startRoom("Budi", startWith = (1..450).map { song("s$it") })
        val created = (1..200).map { entry("e$it", videoId = "s$it") }
        connector.last.welcome(room(queue = created, currentEntryId = "e1"), memberId = "m1")
        assertEquals(250, session.owedEntries)
        controller.handle(RitmeLocalAction.AddAll((1..100).map { song("a$it") }))
        // 500 - 200 in the room - 250 owed: only 50 of the album fit, and the toast says so
        assertEquals(RitmeToast.AddedRestFull(50), toasts.shown.last())
        timers.advanceBy(RitmeSession.OVERFLOW_DELAY_MS + 10 * RitmeSession.OVERFLOW_GAP_MS)
        assertEquals(listOf(200, 50, 50), commands.map { (it as Command.Add).entries.size })
        assertEquals((201..450).map { "s$it" } + (1..50).map { "a$it" }, addedIds)
        assertEquals(500, created.size + addedIds.size)
    }

    @Test
    fun aListAddCountsAddsSentButNotYetInASnapshot() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddAll((1..300).map { song("s$it") }))
        timers.advanceBy(10 * RitmeSession.OVERFLOW_GAP_MS) // both batches out, neither in a snapshot yet
        assertEquals(300, session.owedEntries)
        controller.handle(RitmeLocalAction.AddAll((1..300).map { song("b$it") }))
        assertEquals(RitmeToast.AddedRestFull(199), toasts.shown.last())
        timers.advanceBy(10 * RitmeSession.OVERFLOW_GAP_MS)
        assertEquals(499, addedIds.size)
        // the snapshot with everything lands: nothing is owed any more, and the room is full
        val queue = playing.queue + addedIds.mapIndexed { i, id -> entry("n$i", videoId = id) }
        connector.last.emit(ConnectionEvent.State(playing.copy(version = 2, queue = queue)))
        assertEquals(0, session.owedEntries)
        controller.handle(RitmeLocalAction.AddAll(listOf(song("c"))))
        assertEquals(RitmeToast.CommandFailed(NoticeKey.QUEUE_FULL), toasts.shown.last())
    }

    @Test
    fun startWithNothingShareableSaysNotAvailableAndOpensNothing() {
        controller.openStartWith(emptyList())
        controller.openStartWith(listOf(song("x", local = true)))
        assertNull(controller.sheetRequest.value)
        assertEquals(List<RitmeToast>(2) { RitmeToast.NotAvailable }, toasts.shown)
    }

    @Test
    fun guestsCannotAddWhileTheHostTurnedAddingOff() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.AddItems(listOf(song("a")), playNext = false))
        controller.handle(RitmeLocalAction.PlayQueue(listOf(song("b"))))
        assertTrue(commands.isEmpty())
        assertEquals(
            listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN), RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)),
            toasts.shown,
        )
    }

    @Test
    fun theHostAddsEvenWhileGuestsMayNot() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m1")
        controller.handle(RitmeLocalAction.AddItems(listOf(song("a")), playNext = false))
        assertEquals(1, commands.size)
    }

    @Test
    fun theAddSwitchGoesThroughRoomCommand() {
        inRoom(playing, memberId = "m1")
        assertTrue(controller.roomCommand { setGuestsCanAdd(false) })
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(controller.roomCommand { setGuestsCanAdd(true) })
        assertEquals(RitmeToast.Reconnecting, toasts.shown.last())
    }

    @Test
    fun aListAddWhileGuestsMayNotAddSaysSoAndSendsNothing() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.AddAll((1..450).map { song("s$it") }))
        timers.advanceBy(RitmeSession.OVERFLOW_DELAY_MS + 10 * RitmeSession.OVERFLOW_GAP_MS)
        assertTrue(commands.isEmpty())
        assertEquals(0, session.owedEntries)
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)), toasts.shown)
    }

    private val clip = NewEntry(videoId = "dQw4w9WgXcQ", title = "Clip", artist = "Channel", durationMs = 60_000, kind = MediaKind.YOUTUBE_VIDEO)

    @Test
    fun addEntriesSendsOneAddAndSaysSo() {
        inRoom(playing)
        controller.addEntries(listOf(clip), playNext = true)
        assertEquals(listOf<Command>(Command.Add(listOf(clip), playNext = true)), commands)
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(1)), toasts.shown)
    }

    @Test
    fun addEntriesIsRefusedUpFrontWhenGuestsCannotAdd() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.addEntries(listOf(clip), playNext = false)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)), toasts.shown)
    }

    @Test
    fun startingWithEntriesPutsThemInTheCreate() {
        controller.startRoom("Budi", startWithEntries = listOf(clip))
        assertEquals(JoinIntent.Create("Budi", listOf(clip), 0, 0), connector.last.intent)
    }

    @Test
    fun openStartWithEntriesAsksForTheStartSheetOutsideARoomAndTheRoomSheetInside() {
        controller.openStartWithEntries(listOf(clip))
        assertEquals(RitmeSheetRequest.Kind.Entry(startWithEntries = listOf(clip)), controller.sheetRequest.value?.kind)
        inRoom(playing)
        controller.openStartWithEntries(listOf(clip))
        assertEquals(RitmeSheetRequest.Kind.Room, controller.sheetRequest.value?.kind)
    }

    @Test
    fun startWithVideoOutsideARoomOpensTheStartSheet() {
        controller.startOrAddEntries(listOf(clip))
        assertEquals(RitmeSheetRequest.Kind.Entry(startWithEntries = listOf(clip)), controller.sheetRequest.value?.kind)
        assertTrue(toasts.shown.isEmpty())
    }

    @Test
    fun startWithVideoAfterJoiningARoomAddsItThereInsteadOfDroppingIt() {
        // The "Add video" sheet opened in start mode, then this device joined a room.
        inRoom(playing)
        val before = controller.sheetRequest.value
        controller.startOrAddEntries(listOf(clip))
        assertEquals(listOf<Command>(Command.Add(listOf(clip), playNext = false)), commands)
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(1)), toasts.shown)
        // no room sheet in its place
        assertEquals(before, controller.sheetRequest.value)
    }

    @Test
    fun startWithVideoInARoomWhereGuestsCannotAddIsRefused() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        val before = controller.sheetRequest.value
        controller.startOrAddEntries(listOf(clip))
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)), toasts.shown)
        assertEquals(before, controller.sheetRequest.value)
    }

    @Test
    fun addVideoIsRefusedUpFrontWhenGuestsCannotAdd() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.openAddVideo("https://youtu.be/dQw4w9WgXcQ")
        assertNull(controller.sheetRequest.value)
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)), toasts.shown)
    }

    @Test
    fun theHostGetsTheAddVideoSheetWithTheLink() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m1")
        controller.openAddVideo("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(RitmeSheetRequest.Kind.AddVideo("https://youtu.be/dQw4w9WgXcQ"), controller.sheetRequest.value?.kind)
        assertTrue(toasts.shown.isEmpty())
    }

    @Test
    fun outsideARoomAddVideoOpensInStartMode() {
        controller.openAddVideo("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(RitmeSheetRequest.Kind.AddVideo("https://youtu.be/dQw4w9WgXcQ"), controller.sheetRequest.value?.kind)
        controller.openAddVideo()
        assertEquals(RitmeSheetRequest.Kind.AddVideo(), controller.sheetRequest.value?.kind)
    }
}
