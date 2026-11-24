package com.example.musicfy.ritme.protocol

/** One instance of every message. Fixture file names are the map keys. */
object Samples {
    private val entry = QueueEntry(
        entryId = "e1",
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        durationMs = 213_000,
        addedBy = "m1",
    )
    private val newEntry = NewEntry("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, 213_000)
    private val state = RoomState(
        code = "K7M2QX",
        version = 3,
        hostId = "m1",
        settings = RoomSettings(guestsCanControl = true),
        members = listOf(Member("m1", "Budi", true, true, 1_000), Member("m2", "Sari", false, false, 2_000)),
        queue = listOf(entry),
        currentEntryId = "e1",
        playback = Playback(true, 5_000, 1_700_000_000_000),
    )

    private fun send(cmd: Command) = ClientMessage.Send("r1", cmd)

    val client: Map<String, ClientMessage> = linkedMapOf(
        "client-create" to ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(newEntry), startIndex = 0, startPositionMs = 0),
        "client-join" to ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Sari", resumeToken = "tok"),
        "client-leave" to ClientMessage.Leave,
        "client-ping" to ClientMessage.Ping(123),
        "client-track-ended" to ClientMessage.TrackEnded("e1"),
        "client-ack" to ClientMessage.Ack(3),
        "cmd-play" to send(Command.Play),
        "cmd-pause" to send(Command.Pause),
        "cmd-seek" to send(Command.Seek(42_000)),
        "cmd-next" to send(Command.Next),
        "cmd-previous" to send(Command.Previous),
        "cmd-play-entry" to send(Command.PlayEntry("e1")),
        "cmd-add" to send(Command.Add(listOf(newEntry), playNext = true)),
        "cmd-add-youtube-video" to send(
            Command.Add(listOf(NewEntry("dQw4w9WgXcQ", "Never Gonna Give You Up (Official Video)", "Rick Astley", null, 213_000, MediaKind.YOUTUBE_VIDEO))),
        ),
        "cmd-add-url-live" to send(
            Command.Add(listOf(NewEntry("", "Live stream", "", null, 0, MediaKind.URL, "https://example.com/live/index.m3u8"))),
        ),
        "cmd-add-url-hls-referrer" to send(
            Command.Add(
                listOf(
                    NewEntry(
                        "", "Film", "example.com", null, 5_400_000, MediaKind.URL,
                        "https://cdn.example.com/v/manifest?token=abc", StreamType.HLS, "https://example.com/",
                    ),
                ),
            ),
        ),
        "cmd-remove" to send(Command.Remove("e1")),
        "cmd-move" to send(Command.Move("e1", afterEntryId = null)),
        "cmd-set-settings" to send(Command.SetSettings(RoomSettings(guestsCanControl = false, guestsCanAdd = false))),
        "cmd-kick" to send(Command.Kick("m2")),
        "cmd-transfer-host" to send(Command.TransferHost("m2")),
        "cmd-end-room" to send(Command.EndRoom),
    )

    val server: Map<String, ServerMessage> = linkedMapOf(
        "server-welcome" to ServerMessage.Welcome("m1", "tok", state, 1_700_000_000_500),
        "server-state" to ServerMessage.State(state),
        "server-pong" to ServerMessage.Pong(123, 1_700_000_000_500),
        "server-error" to ServerMessage.Error("r1", ErrorCode.FORBIDDEN),
        "server-kicked" to ServerMessage.Kicked,
        "server-room-closed" to ServerMessage.RoomClosed,
    )
}
