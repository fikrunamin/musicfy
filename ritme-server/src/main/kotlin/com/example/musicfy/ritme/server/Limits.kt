package com.example.musicfy.ritme.server

/**
 * Heap budget. The container has `mem_limit: 768m` and the JVM `-XX:MaxRAMPercentage=75`, so the
 * heap is ~576 MB. The limits below are chosen so that their worst cases add up:
 *
 * | Part | Bound | Worst case in heap |
 * |---|---|---|
 * | Baseline (JVM, Ktor, Netty, caches) | measured | ~80 MB |
 * | Idle sockets | [MAX_CONNECTIONS] x ~60 KB | ~90 MB |
 * | Unsent outbound bytes | [MAX_PENDING_BYTES_TOTAL] | 96 MB |
 * | Rooms at rest | [MAX_ROOM_STATE_BYTES_TOTAL] (120 MB encoded) x 1.3 | ~156 MB |
 * | Headroom (GC, in-flight encodes) | rest | >= 150 MB |
 *
 * Room objects take 0.9-1.3x their encoded size in heap (measured: ASCII-heavy maximal entries
 * 1.10, 3-byte characters 0.88, typical YouTube entries 1.27), hence the 1.3 factor.
 * Rooms: at most [MAX_ROOMS]; at most [MAX_ROOM_STATE_BYTES_TOTAL] / [MAX_STATE_BYTES] = 480 of
 * them can be full, the rest stay small (~1-2 KB each).
 */
object Limits {
    const val MAX_MEMBERS = 20
    const val MAX_QUEUE = 500
    const val COMMANDS_PER_SECOND = 10
    /** Largest inbound frame. Safe because every field has its own cap. */
    const val MAX_FRAME_BYTES = 256L * 1024

    /**
     * Largest room snapshot (UTF-8 JSON): Add beyond it gets QUEUE_FULL, an over-large Create keeps
     * the entries that fit. A typical 500-entry queue is ~200 KB. Server frames (Welcome/State)
     * therefore stay below ~260 KB; clients must accept inbound frames of at least 1 MB.
     */
    const val MAX_STATE_BYTES = 256 * 1024

    /** All rooms' snapshots together (see the heap budget above and StateBudget). */
    const val MAX_ROOM_STATE_BYTES_TOTAL = 120L * 1024 * 1024
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

    /**
     * Join limiter per client IP (IPv6: per /64): [JOIN_BURST] tokens refilled at [JOINS_PER_MINUTE].
     * Charged for every Create and for failed Joins (ROOM_NOT_FOUND, ROOM_FULL, UPGRADE_REQUIRED);
     * successful joins and resumes are free, but need a non-empty bucket.
     */
    const val JOINS_PER_MINUTE = 10
    const val JOIN_BURST = 30

    /** Generous for shared addresses (a household, a campus, carrier NAT). */
    const val MAX_CONNECTIONS_PER_IP = 48
    /**
     * Sized to memory (512 MB container, ~384 MB heap): an idle socket costs ~60 KB (Netty
     * buffers, Ktor session, coroutines) -> ~90 MB at the cap. Buffered outbound bytes are bounded
     * separately by [MAX_PENDING_BYTES_TOTAL], room state by [MAX_ROOM_STATE_BYTES_TOTAL].
     */
    const val MAX_CONNECTIONS = 1_500

    /** A room lives only while someone is (or was, < 60 s ago) connected: one room per connection. */
    const val MAX_ROOMS = MAX_CONNECTIONS

    /** Every inbound frame (pings and garbage included) per connection; beyond this the socket is closed. */
    const val FRAMES_PER_SECOND = 20
    const val FRAME_BURST = 40

    /** Ktor's per-socket inbound frame channel. */
    const val WS_INCOMING_CAPACITY = 8

    /** Ktor's per-socket outgoing frame channel: one frame; everything else waits in our [PeerOutbox]. */
    const val WS_OUTGOING_CAPACITY = 1

    /** A client whose unsent messages exceed this is dropped (it can resume and get fresh state). */
    const val MAX_PENDING_BYTES_PER_PEER = 2L * 1024 * 1024
    const val MAX_PENDING_MESSAGES_PER_PEER = 64

    /** All clients together (see PeerStats); bounds outbound buffering whatever the connection count. */
    const val MAX_PENDING_BYTES_TOTAL = 96L * 1024 * 1024

    /** A frame the client has not accepted within this long means it stopped reading: dropped. */
    const val SEND_STALL_MS = 20_000L

    const val METRICS_INTERVAL_MS = 10 * 60_000L

    const val SEARCH_PER_MINUTE = 30

    /** All upstream (uncached) searches together; beyond this /api/search answers 503. */
    const val SEARCH_GLOBAL_PER_MINUTE = 300
    const val SEARCH_MAX_QUERY_LENGTH = 200
}
