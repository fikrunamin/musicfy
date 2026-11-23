package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
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

class RitmeControllerTest {

    private val connector = FakeConnector()
    private val session = RitmeSession(connector, FakeTimers())
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
}
