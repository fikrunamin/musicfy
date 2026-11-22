package com.example.musicfy.ritme.server

object Limits {
    const val MAX_MEMBERS = 20
    const val MAX_QUEUE = 500
    const val COMMANDS_PER_SECOND = 10
    /**
     * Largest inbound frame. Safe because every field has its own cap; outbound frames are not
     * limited (a 500-entry snapshot is ~200 KB, ~3 MB with every field at its cap): clients must
     * accept >= 1 MB (4 MB recommended).
     */
    const val MAX_FRAME_BYTES = 256L * 1024
    const val MAX_NAME_LENGTH = 32
    const val MAX_VIDEO_ID_LENGTH = 64
    const val MAX_TEXT_LENGTH = 300
    const val MAX_URL_LENGTH = 2_048
    const val MAX_THUMBNAIL_URL_LENGTH = 512

    /** Longest accepted entry, and the furthest a live stream can be seeked or started. */
    const val MAX_DURATION_MS = 86_400_000L

    /** A disconnected member keeps their seat (and host role) this long. */
    const val GRACE_MS = 30_000L

    /** A room with nobody connected is deleted after this long. */
    const val EMPTY_ROOM_MS = 60_000L

    /** New tracks start this far in the future so every device can buffer and begin together. */
    const val START_DELAY_MS = 1_500L

    /** If no client reports TrackEnded, the server advances this long after the track's end. */
    const val FALLBACK_SLACK_MS = 5_000L

    /** Previous past this position restarts the current track instead. */
    const val PREVIOUS_RESTART_MS = 3_000L

    /** TrackEnded earlier than (duration - this) is treated as stale and ignored. */
    const val TRACK_ENDED_TOLERANCE_MS = 10_000L

    const val SWEEP_INTERVAL_MS = 1_000L

    const val ROOM_CODE_LENGTH = 6
    const val ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** A new socket must have joined (or created) a room within this long, or it is closed. */
    const val JOIN_DEADLINE_MS = 10_000L

    /** Create/Join attempts per client IP (IPv6: per /64), successful or not. */
    const val JOINS_PER_MINUTE = 10

    const val MAX_CONNECTIONS_PER_IP = 16
    const val MAX_CONNECTIONS = 5_000
    const val MAX_ROOMS = 2_000

    /** Every inbound frame (pings and garbage included) per connection; beyond this the socket is closed. */
    const val FRAMES_PER_SECOND = 20
    const val FRAME_BURST = 40

    /** Ktor's per-socket inbound and outgoing frame channels (see WebSocketPeer.SEND_BUFFER for ours). */
    const val WS_CHANNEL_CAPACITY = 8

    const val METRICS_INTERVAL_MS = 10 * 60_000L

    const val SEARCH_PER_MINUTE = 30
    const val SEARCH_MAX_QUERY_LENGTH = 200
}
