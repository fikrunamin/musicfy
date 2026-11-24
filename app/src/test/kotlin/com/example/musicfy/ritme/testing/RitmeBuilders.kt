package com.example.musicfy.ritme.testing

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.StreamType

fun entry(
    id: String,
    addedBy: String = "m1",
    durationMs: Long = 200_000,
    kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    videoId: String = "vid$id",
    url: String? = null,
    streamType: StreamType? = null,
    referrer: String? = null,
) = QueueEntry(
    entryId = id,
    videoId = videoId,
    title = "Song $id",
    artist = "Artist $id",
    thumbnailUrl = null,
    durationMs = durationMs,
    addedBy = addedBy,
    kind = kind,
    url = url,
    streamType = streamType,
    referrer = referrer,
)

fun member(id: String, isHost: Boolean = false, connected: Boolean = true) =
    Member(id = id, name = "Name $id", isHost = isHost, connected = connected, joinedAt = 0)

fun room(
    code: String = "K7M2QX",
    version: Long = 1,
    hostId: String = "m1",
    queue: List<QueueEntry> = emptyList(),
    currentEntryId: String? = null,
    playback: Playback = Playback(),
    settings: RoomSettings = RoomSettings(),
    members: List<Member> = listOf(member("m1", isHost = true), member("m2")),
) = RoomState(
    code = code,
    version = version,
    hostId = hostId,
    settings = settings,
    members = members,
    queue = queue,
    currentEntryId = currentEntryId,
    playback = playback,
)

fun welcome(state: RoomState, memberId: String = "m1", resumeToken: String = "tok", serverTime: Long = 0) =
    ServerMessage.Welcome(memberId = memberId, resumeToken = resumeToken, state = state, serverTime = serverTime)
