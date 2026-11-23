package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLogicTest {
    private val ids = SeqIds()
    private val now = 1_000_000L

    /** Playing "a" since 5 s ago from 10 s in, so 15 s in at [now]. */
    private val playingA = roomState(
        queue = listOf(entry("a"), entry("b"), entry("c")),
        currentEntryId = "a",
        playback = Playback(true, 10_000, now - 5_000),
    )

    private fun apply(state: RoomState, by: String, cmd: Command) = RoomLogic.apply(state, by, cmd, now, ids)

    private fun ok(outcome: Outcome): RoomState {
        assertTrue("expected Ok but was $outcome", outcome is Outcome.Ok)
        return (outcome as Outcome.Ok).state
    }

    private fun rejected(outcome: Outcome): ErrorCode {
        assertTrue("expected Rejected but was $outcome", outcome is Outcome.Rejected)
        return (outcome as Outcome.Rejected).code
    }

    @Test
    fun guestCannotControlPlaybackOrOthersEntriesWhenLocked() {
        val locked = playingA.copy(settings = RoomSettings(guestsCanControl = false))
        listOf(
            Command.Play, Command.Pause, Command.Seek(0), Command.Next, Command.Previous,
            Command.PlayEntry("b"), Command.Move("c", "a"), Command.Remove("b"),
        ).forEach { assertEquals("$it", ErrorCode.FORBIDDEN, rejected(apply(locked, GUEST, it))) }
    }

    @Test
    fun guestMayAddAndRemoveOwnEntryWhenLocked() {
        val locked = playingA.copy(settings = RoomSettings(guestsCanControl = false))
        val added = ok(apply(locked, GUEST, Command.Add(listOf(newEntry()))))
        val own = added.queue.last()
        assertEquals(GUEST, own.addedBy)
        assertFalse(ok(apply(added, GUEST, Command.Remove(own.entryId))).queue.any { it.entryId == own.entryId })
    }

    @Test
    fun hostOnlyCommandsRejectGuestsEvenWhenControlIsOpen() {
        listOf(Command.SetSettings(RoomSettings(false)), Command.Kick(HOST), Command.TransferHost(GUEST), Command.EndRoom)
            .forEach { assertEquals("$it", ErrorCode.FORBIDDEN, rejected(apply(playingA, GUEST, it))) }
    }

    @Test
    fun pauseFreezesCurrentPositionAndBumpsVersion() {
        val paused = ok(apply(playingA, HOST, Command.Pause))
        assertEquals(Playback(false, 15_000, now), paused.playback)
        assertEquals(playingA.version + 1, paused.version)
    }

    @Test
    fun playResumesFromFrozenPositionAnchoredNow() {
        val paused = playingA.copy(playback = Playback(false, 15_000, now - 60_000))
        assertEquals(Playback(true, 15_000, now), ok(apply(paused, HOST, Command.Play)).playback)
    }

    @Test
    fun playWhilePlayingIsANoOp() {
        assertSame(playingA, (apply(playingA, HOST, Command.Play) as Outcome.Ok).state)
    }

    @Test
    fun nextStartsTheFollowingEntryAfterTheStartDelay() {
        val next = ok(apply(playingA, HOST, Command.Next))
        assertEquals("b", next.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), next.playback)
    }

    @Test
    fun nextOnTheLastEntryStopsAtItsEnd() {
        val ended = ok(apply(playingA.copy(currentEntryId = "c"), HOST, Command.Next))
        assertEquals("c", ended.currentEntryId)
        assertEquals(Playback(false, 200_000, now), ended.playback)
    }

    @Test
    fun previousRestartsTheCurrentEntryWhenPastTheThreshold() {
        val restarted = ok(apply(playingA.copy(currentEntryId = "b"), HOST, Command.Previous))
        assertEquals("b", restarted.currentEntryId)
        assertEquals(Playback(true, 0, now), restarted.playback)
    }

    @Test
    fun previousGoesBackWhenNearTheStart() {
        val earlyB = playingA.copy(currentEntryId = "b", playback = Playback(true, 0, now - 1_000))
        val back = ok(apply(earlyB, HOST, Command.Previous))
        assertEquals("a", back.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), back.playback)
    }

    @Test
    fun seekClampsIntoTheTrackAndKeepsPlayState() {
        assertEquals(Playback(true, 200_000, now), ok(apply(playingA, HOST, Command.Seek(999_999))).playback)
        assertEquals(Playback(true, 0, now), ok(apply(playingA, HOST, Command.Seek(-5))).playback)
    }

    @Test
    fun addToAnIdleRoomStartsTheFirstAddedEntry() {
        val started = ok(apply(roomState(), GUEST, Command.Add(listOf(newEntry("x"), newEntry("y")))))
        assertEquals(started.queue.first().entryId, started.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), started.playback)
    }

    @Test
    fun addPlayNextInsertsRightAfterTheCurrentEntry() {
        val added = ok(apply(playingA, HOST, Command.Add(listOf(newEntry("n")), playNext = true)))
        assertEquals(listOf("a", "n", "b", "c"), added.queue.map { it.videoId.removePrefix("vid-") })
    }

    @Test
    fun addRejectsInvalidAndOversizedBatches() {
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(emptyList()))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry(" "))))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry(durationMs = -1))))))
        val tooMany = List(Limits.MAX_QUEUE - 2) { newEntry("v$it") }
        assertEquals(ErrorCode.QUEUE_FULL, rejected(apply(playingA, HOST, Command.Add(tooMany))))
    }

    @Test
    fun removingTheCurrentEntryAdvancesAndRemovingTheLastOneClearsPlayback() {
        val removedA = ok(apply(playingA, HOST, Command.Remove("a")))
        assertEquals("b", removedA.currentEntryId)
        assertEquals(listOf("b", "c"), removedA.queue.map { it.entryId })

        val onlyC = roomState(queue = listOf(entry("c")), currentEntryId = "c", playback = Playback(true, 0, now))
        val cleared = ok(apply(onlyC, HOST, Command.Remove("c")))
        assertNull(cleared.currentEntryId)
        assertEquals(Playback(), cleared.playback)
        assertTrue(cleared.queue.isEmpty())
    }

    @Test
    fun moveWithoutAnAnchorPlacesTheEntryRightAfterCurrent() {
        assertEquals(listOf("a", "c", "b"), ok(apply(playingA, HOST, Command.Move("c", null))).queue.map { it.entryId })
    }

    @Test
    fun moveRejectsTheCurrentEntryUnknownIdsAndSelfAnchors() {
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("a", "c"))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("zzz", null))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("b", "b"))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("b", "zzz"))))
    }

    @Test
    fun kickRemovesTheMemberAndReportsTheEffect() {
        val outcome = apply(playingA, HOST, Command.Kick(GUEST)) as Outcome.Ok
        assertEquals(Effect.Kicked(GUEST), outcome.effect)
        assertEquals(listOf(HOST), outcome.state.members.map { it.id })
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Kick(HOST))))
    }

    @Test
    fun transferHostMovesTheFlagAndRefusesDisconnectedTargets() {
        val moved = ok(apply(playingA, HOST, Command.TransferHost(GUEST)))
        assertEquals(GUEST, moved.hostId)
        assertEquals(mapOf(HOST to false, GUEST to true), moved.members.associate { it.id to it.isHost })
        assertEquals(ErrorCode.INVALID, rejected(apply(roomState(guestConnected = false), HOST, Command.TransferHost(GUEST))))
    }

    @Test
    fun endRoomReportsTheClosedEffect() {
        assertEquals(Effect.Closed, (apply(playingA, HOST, Command.EndRoom) as Outcome.Ok).effect)
    }

    @Test
    fun trackEndedAdvancesOnlyForTheCurrentEntryNearItsEnd() {
        val nearEnd = playingA.copy(playback = Playback(true, 195_000, now))
        val advanced = RoomLogic.onTrackEnded(nearEnd, "a", now)
        assertEquals("b", advanced?.currentEntryId)
        assertNull(RoomLogic.onTrackEnded(nearEnd, "b", now)) // not the current entry
        assertNull(RoomLogic.onTrackEnded(playingA, "a", now)) // 15 s in: stale report
        assertNull(RoomLogic.onTrackEnded(advanced!!, "a", now)) // duplicate after advancing
    }

    @Test
    fun tickAdvancesOnlyAfterTheFallbackSlack() {
        val atEnd = playingA.copy(playback = Playback(true, 200_000, now))
        assertNull(RoomLogic.tick(atEnd, now + Limits.FALLBACK_SLACK_MS - 1))
        assertEquals("b", RoomLogic.tick(atEnd, now + Limits.FALLBACK_SLACK_MS)?.currentEntryId)
    }

    @Test
    fun newRoomStartsAtTheRequestedIndexAfterTheDelay() {
        val create = ClientMessage.Create(PROTOCOL_VERSION, "Host", listOf(newEntry("x"), newEntry("y")), startIndex = 1, startPositionMs = 30_000)
        val state = RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids)
        assertEquals(state.queue[1].entryId, state.currentEntryId)
        assertEquals(Playback(true, 30_000, now + Limits.START_DELAY_MS), state.playback)
        assertEquals(listOf(Member(HOST, "Host", true, true, now)), state.members)
        assertNull(RoomLogic.newRoom("ABCDEF", HOST, "Host", create.copy(initialQueue = emptyList()), now, ids).currentEntryId)
    }

    @Test
    fun cleanNameTrimsAndBoundsLength() {
        assertEquals("Budi", RoomLogic.cleanName("  Budi "))
        assertNull(RoomLogic.cleanName("   "))
        assertNull(RoomLogic.cleanName("x".repeat(Limits.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun nextHostIsTheEarliestConnectedMemberOtherThanTheOneLeaving() {
        val three = playingA.copy(members = playingA.members + Member("late", "Late", false, true, 99))
        assertEquals(GUEST, RoomLogic.nextHost(three, excluding = HOST))
        val guestOffline = three.copy(members = three.members.map { if (it.id == GUEST) it.copy(connected = false) else it })
        assertEquals("late", RoomLogic.nextHost(guestOffline, excluding = HOST))
        assertNull(RoomLogic.nextHost(roomState(guestConnected = false), excluding = HOST))
    }

    @Test
    fun removingTheHostHandsTheRoleToTheNextHost() {
        val after = RoomLogic.removeMembers(playingA, setOf(HOST))
        assertEquals(GUEST, after.hostId)
        assertTrue(after.members.single().isHost)
    }

    @Test
    fun urlEntriesNeedAnHttpUrlAndKeepTheirKind() {
        fun url(u: String?) = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, u)
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url(null))))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url("ftp://x/v.mp4"))))))
        val added = ok(apply(playingA, HOST, Command.Add(listOf(url("https://cdn.example/v.mp4"))))).queue.last()
        assertEquals(MediaKind.URL, added.kind)
        assertEquals("https://cdn.example/v.mp4", added.url)
        val video = ok(apply(playingA, HOST, Command.Add(listOf(newEntry().copy(kind = MediaKind.YOUTUBE_VIDEO))))).queue.last()
        assertEquals(MediaKind.YOUTUBE_VIDEO, video.kind)
    }

    @Test
    fun liveEntriesNeverAutoAdvanceSeekFreelyAndAcceptAnyEndReport() {
        val live = QueueEntry("live", "", "Live", "", null, 0, HOST, MediaKind.URL, "https://example.com/live.m3u8")
        val state = roomState(queue = listOf(live, entry("b")), currentEntryId = "live", playback = Playback(true, 0, now))
        assertNull(RoomLogic.tick(state, now + 24 * 3_600_000L))
        assertEquals(Playback(true, 7_200_000, now), ok(apply(state, HOST, Command.Seek(7_200_000))).playback)
        assertEquals("b", RoomLogic.onTrackEnded(state, "live", now + 1_000)?.currentEntryId)
        val ended = ok(apply(state.copy(queue = listOf(live)), HOST, Command.Next))
        assertEquals(false, ended.playback.isPlaying)
    }

    @Test
    fun entriesCarryOnlyTheFieldsTheirKindNeeds() {
        fun add(e: NewEntry) = apply(playingA, HOST, Command.Add(listOf(e)))
        val yt = newEntry()
        val url = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, "https://cdn.example/v.mp4")
        assertEquals(ErrorCode.INVALID, rejected(add(yt.copy(url = "https://x/" + "a".repeat(1_000)))))
        assertEquals(ErrorCode.INVALID, rejected(add(yt.copy(kind = MediaKind.YOUTUBE_VIDEO, url = "https://x/v"))))
        assertEquals(ErrorCode.INVALID, rejected(add(url.copy(videoId = "dQw4w9WgXcQ"))))
        listOf("", "a b", "a/b", "<script>", "é", "a".repeat(Limits.MAX_VIDEO_ID_LENGTH + 1)).forEach {
            assertEquals("videoId '$it'", ErrorCode.INVALID, rejected(add(yt.copy(videoId = it))))
        }
        ok(add(yt.copy(videoId = "Az09_-")))
        ok(add(yt.copy(videoId = "a".repeat(Limits.MAX_VIDEO_ID_LENGTH))))
        ok(add(url))
    }

    @Test
    fun thumbnailsAreShortHttpsLinksAndYoutubeOnesComeFromYoutubeHosts() {
        fun add(e: NewEntry) = apply(playingA, HOST, Command.Add(listOf(e)))
        val yt = newEntry()
        val url = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, "https://cdn.example/v.mp4")
        listOf(
            "https://i.ytimg.com/vi/x/hqdefault.jpg",
            "https://i9.ytimg.com/vi/x/hq.jpg",
            "https://lh3.googleusercontent.com/abc=w544-h544",
            "https://yt3.ggpht.com/abc",
        ).forEach { ok(add(yt.copy(thumbnailUrl = it))) }
        listOf(
            "http://i.ytimg.com/vi/x/hqdefault.jpg",
            "https://evil.example/t.jpg",
            "https://i.ytimg.com.evil.example/t.jpg",
            "https://ytimg.com.evil.example/t.jpg",
            "https://notytimg.com/t.jpg",
            "https://i.ytimg.com/" + "a".repeat(Limits.MAX_THUMBNAIL_URL_LENGTH),
            "https://i.ytimg.com/a b^",
            "javascript:alert(1)",
        ).forEach { assertEquals("thumbnail $it", ErrorCode.INVALID, rejected(add(yt.copy(thumbnailUrl = it)))) }
        ok(add(url.copy(thumbnailUrl = "https://cdn.example/poster.jpg")))
        assertEquals(ErrorCode.INVALID, rejected(add(url.copy(thumbnailUrl = "http://cdn.example/poster.jpg"))))
        assertEquals(
            ErrorCode.INVALID,
            rejected(add(url.copy(thumbnailUrl = "https://cdn.example/" + "a".repeat(Limits.MAX_THUMBNAIL_URL_LENGTH)))),
        )
    }

    @Test
    fun durationsAndLivePositionsAreBoundedToADay() {
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry(durationMs = Limits.MAX_DURATION_MS + 1))))))
        ok(apply(playingA, HOST, Command.Add(listOf(newEntry(durationMs = Limits.MAX_DURATION_MS)))))
        val live = QueueEntry("live", "", "Live", "", null, 0, HOST, MediaKind.URL, "https://example.com/live.m3u8")
        val state = roomState(queue = listOf(live), currentEntryId = "live", playback = Playback(true, 0, now))
        assertEquals(Limits.MAX_DURATION_MS, ok(apply(state, HOST, Command.Seek(Long.MAX_VALUE))).playback.positionMs)
        val create = ClientMessage.Create(
            PROTOCOL_VERSION,
            "Host",
            listOf(NewEntry("", "Live", "", null, 0, MediaKind.URL, "https://example.com/live.m3u8")),
            startPositionMs = Long.MAX_VALUE,
        )
        assertEquals(Limits.MAX_DURATION_MS, RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids).playback.positionMs)
    }

    @Test
    fun newRoomDropsInvalidInitialEntries() {
        val create = ClientMessage.Create(
            PROTOCOL_VERSION,
            "Host",
            listOf(newEntry().copy(url = "javascript:" + "a".repeat(60_000)), newEntry("ok")),
        )
        assertEquals(listOf("ok"), RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids).queue.map { it.videoId })
    }

    @Test
    fun entriesOfAnUnknownKindAreRejected() {
        val unknown = newEntry().copy(kind = MediaKind.UNKNOWN)
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(unknown)))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(unknown.copy(videoId = "", url = "https://x/v.mp4"))))))
    }

    @Test
    fun cleanNameNormalizesAndStripsControlAndFormatCharacters() {
        assertEquals("Budi", RoomLogic.cleanName("  Budi \n"))
        assertEquals("Budi", RoomLogic.cleanName("\u202EBu\u200Bdi\u2066"))
        assertEquals("Sari Ayu", RoomLogic.cleanName("Sari\r\n Ayu"))
        assertEquals("\u00e9", RoomLogic.cleanName("e\u0301")) // NFC: e + combining acute -> é
        assertEquals("\uD83C\uDFB5 Ritme", RoomLogic.cleanName("\uD83C\uDFB5 Ritme")) // emoji kept
        assertNull(RoomLogic.cleanName("\u200B\u200C\uFEFF\u0000\u2029"))
        assertNull(RoomLogic.cleanName("a".repeat(Limits.MAX_NAME_LENGTH + 1)))
        // Invisible padding does not count towards the length.
        assertEquals("a".repeat(Limits.MAX_NAME_LENGTH), RoomLogic.cleanName("a".repeat(Limits.MAX_NAME_LENGTH) + "\u200B".repeat(10)))
    }

    private fun maximal(i: Int) = NewEntry(
        "", "\u0001".repeat(150) + "T".repeat(150), "A".repeat(300), "https://cdn.example/" + "t".repeat(480), 600_000,
        MediaKind.URL, "https://cdn.example/$i/" + "u".repeat(2_000),
    )

    @Test
    fun titlesAndArtistsAreCleanedOfInvisibleCharacters() {
        val e = newEntry().copy(title = " \u202ESo\u0001ng\n", artist = "Ar\u200Btist\u0000")
        val added = ok(apply(playingA, HOST, Command.Add(listOf(e)))).queue.last()
        assertEquals("Song", added.title)
        assertEquals("Artist", added.artist)
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(e.copy(title = "\u0001\u200B \u0000"))))))
    }

    @Test
    fun addIsRefusedOnceTheSnapshotWouldPassTheStateCap() {
        var state = roomState()
        var added = 0
        while (true) {
            when (val outcome = apply(state, HOST, Command.Add(List(20) { maximal(added + it) }))) {
                is Outcome.Ok -> {
                    state = outcome.state
                    added += 20
                }
                is Outcome.Rejected -> {
                    assertEquals(ErrorCode.QUEUE_FULL, outcome.code)
                    break
                }
            }
        }
        assertTrue("only $added entries", added in 40 until Limits.MAX_QUEUE)
        assertTrue(RoomLogic.stateBytes(state) <= Limits.MAX_STATE_BYTES)
        // Smaller entries still fit until the cap.
        ok(apply(state, HOST, Command.Add(listOf(newEntry("small")))))
    }

    @Test
    fun anOversizedCreateKeepsTheEntriesThatFit() {
        val create = ClientMessage.Create(PROTOCOL_VERSION, "Host", List(Limits.MAX_QUEUE) { maximal(it) })
        val room = RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids)
        assertTrue(room.queue.size in 40 until Limits.MAX_QUEUE)
        assertTrue(RoomLogic.stateBytes(room) <= Limits.MAX_STATE_BYTES)
        assertEquals(room.queue.first().entryId, room.currentEntryId)
        // A normal full queue is far below the cap.
        val normal = ClientMessage.Create(PROTOCOL_VERSION, "Host", List(Limits.MAX_QUEUE) { newEntry("v$it") })
        assertEquals(Limits.MAX_QUEUE, RoomLogic.newRoom("ABCDEF", HOST, "Host", normal, now, ids).queue.size)
    }

    @Test
    fun addChecksTheCachedSnapshotSizeAndTheServerWideBudget() {
        val add = Command.Add(listOf(newEntry("n")))
        ok(RoomLogic.apply(playingA, HOST, add, now, ids, SizeContext(currentBytes = 1_000, budgetLeft = 10_000)))
        // The cached size is trusted (no re-encode of the whole state): near the cap means full.
        assertEquals(
            ErrorCode.QUEUE_FULL,
            rejected(RoomLogic.apply(playingA, HOST, add, now, ids, SizeContext(currentBytes = Limits.MAX_STATE_BYTES - 10))),
        )
        // The server-wide budget left is smaller than the new entry.
        assertEquals(ErrorCode.QUEUE_FULL, rejected(RoomLogic.apply(playingA, HOST, add, now, ids, SizeContext(budgetLeft = 10))))
        // Other commands are not affected by either.
        ok(RoomLogic.apply(playingA, HOST, Command.Pause, now, ids, SizeContext(currentBytes = Limits.MAX_STATE_BYTES, budgetLeft = 0)))
    }

    @Test
    fun newRoomRespectsASmallerByteLimit() {
        val create = ClientMessage.Create(PROTOCOL_VERSION, "Host", List(100) { maximal(it) })
        val room = RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids, maxBytes = 20_000)
        assertTrue(room.queue.size in 1..6)
        assertTrue(RoomLogic.stateBytes(room) <= 20_000)
    }

    @Test
    fun cleaningKeepsEmojiSequencesNewEmojiAndJoinersInText() {
        fun cp(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)
        val family = cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) // ZWJ sequence
        val scotland = cp(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F) // tag sequence
        val unicode16 = cp(0x1FAE9) // unassigned in Java 21's tables
        val persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0645" // ZWNJ
        val heart = "\u2764\uFE0F" // VS16
        listOf(family, scotland, unicode16, persian, heart, "$heart $family $scotland").forEach {
            assertEquals(it, RoomLogic.cleanText(it))
            assertEquals(it, RoomLogic.cleanName(it))
        }
        val song = newEntry().copy(title = "Lagu $family $unicode16", artist = persian)
        val added = ok(apply(playingA, HOST, Command.Add(listOf(song)))).queue.last()
        assertEquals("Lagu $family $unicode16", added.title)
        assertEquals(persian, added.artist)
    }

    @Test
    fun cleaningRemovesSpoofingAndLineBreakingCharacters() {
        assertEquals("abcdefgh", RoomLogic.cleanText("a\u202Eb\u202Ac\u2066d\u2069e\u200Bf\uFEFFg\u2028h"))
        assertEquals("Budi", RoomLogic.cleanText("\nBu\r\ndi\t\u0000\u009F\u2029"))
        assertEquals("ab", RoomLogic.cleanText("a\uD800b")) // lone surrogate
        // Only joiners / selectors left: nothing to show, so not a valid name or title.
        assertNull(RoomLogic.cleanName("\u200D\u200C\uFE0F"))
        // Letters that render blank (Hangul fillers, blank braille) do not count as visible either.
        listOf("\u3164", "\u2800\u2800", "\u115F\u1160", "\uFFA0").forEach { assertNull(it, RoomLogic.cleanName(it)) }
        assertEquals("\u3164a", RoomLogic.cleanName("\u3164a"))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry().copy(title = "\u200D\u200B"))))))
    }

    @Test
    fun guestsMayNotAddWhileTheHostTurnedAddingOff() {
        val noAdds = playingA.copy(settings = RoomSettings(guestsCanControl = true, guestsCanAdd = false))
        assertEquals(ErrorCode.FORBIDDEN, rejected(apply(noAdds, GUEST, Command.Add(listOf(newEntry())))))
        assertEquals(4, ok(apply(noAdds, HOST, Command.Add(listOf(newEntry())))).queue.size)
        // Adding and controlling are separate switches.
        assertFalse(ok(apply(noAdds, GUEST, Command.Pause)).playback.isPlaying)
        val addsOnly = playingA.copy(settings = RoomSettings(guestsCanControl = false, guestsCanAdd = true))
        assertEquals(4, ok(apply(addsOnly, GUEST, Command.Add(listOf(newEntry())))).queue.size)
    }

    @Test
    fun setSettingsReplacesBothSwitches() {
        val changed = ok(apply(playingA, HOST, Command.SetSettings(RoomSettings(guestsCanControl = false, guestsCanAdd = false))))
        assertEquals(RoomSettings(guestsCanControl = false, guestsCanAdd = false), changed.settings)
    }

    @Test
    fun urlEntriesFollowTheSharedMediaUrlRules() {
        fun add(u: String) = apply(playingA, HOST, Command.Add(listOf(NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, u))))
        listOf(
            "https://user:pass@cdn.example/v.mp4",
            "http://192.168.1.1/v.mp4",
            "http://127.0.0.1:8080/v.mp4",
            "https://localhost/v.mp4",
            "http://[::1]/v.mp4",
            "http://2130706433/v.mp4",
            "https://cdn.example/v.mp4#t=5",
            "https://cdn.example/a b.mp4",
            "https://router/v.mp4",
        ).forEach { assertEquals(it, ErrorCode.INVALID, rejected(add(it))) }
        val good = "https://cdn.example.com:8443/live/index.m3u8?token=abc"
        assertEquals(good, ok(add(good)).queue.last().url)
    }

    @Test
    fun newRoomDropsInitialUrlEntriesThatBreakTheMediaUrlRules() {
        val clip = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, "http://10.0.0.1/v.mp4")
        val create = ClientMessage.Create(PROTOCOL_VERSION, "Host", listOf(clip, newEntry("ok"), clip.copy(url = "https://cdn.example/v.mp4")))
        val queue = RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids).queue
        assertEquals(listOf(null, "https://cdn.example/v.mp4"), queue.map { it.url })
    }

    @Test
    fun urlEntryThumbnailsFollowTheSameHostRules() {
        val url = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, "https://cdn.example/v.mp4")
        listOf("https://192.168.1.1/poster.jpg", "https://localhost/poster.jpg", "https://user@cdn.example/poster.jpg").forEach {
            assertEquals(it, ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url.copy(thumbnailUrl = it))))))
        }
    }
}
