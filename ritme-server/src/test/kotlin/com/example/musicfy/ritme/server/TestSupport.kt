package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState

const val HOST = "host"
const val GUEST = "guest"

class SeqIds : IdGen {
    private var n = 0

    override fun memberId() = "m${++n}"

    override fun entryId() = "e${++n}"

    override fun resumeToken() = "t${++n}"

    override fun roomCode() = "RM%04d".format(++n)
}

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun now(): Long = now
}

fun entry(id: String, durationMs: Long = 200_000, addedBy: String = HOST) =
    QueueEntry(id, "vid-$id", "Title $id", "Artist", null, durationMs, addedBy)

fun newEntry(videoId: String = "dQw4w9WgXcQ", durationMs: Long = 200_000) =
    NewEntry(videoId, "Song $videoId", "Artist", null, durationMs)

fun roomState(
    queue: List<QueueEntry> = emptyList(),
    currentEntryId: String? = null,
    playback: Playback = Playback(),
    guestsCanControl: Boolean = true,
    guestConnected: Boolean = true,
) = RoomState(
    code = "ABCDEF",
    version = 1,
    hostId = HOST,
    settings = RoomSettings(guestsCanControl),
    members = listOf(Member(HOST, "Host", true, true, 1), Member(GUEST, "Guest", false, guestConnected, 2)),
    queue = queue,
    currentEntryId = currentEntryId,
    playback = playback,
)
