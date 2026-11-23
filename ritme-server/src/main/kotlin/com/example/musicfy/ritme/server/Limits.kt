package com.example.musicfy.ritme.server

/**
 * Heap budget. The container has `mem_limit: 1408m` and the JVM `-XX:MaxRAMPercentage=75`, which
 * gives a max heap of ~1020 MB as reported by the JVM (and `-XX:+ExitOnOutOfMemoryError` restarts it
 * should it ever run out). The limits below are chosen so that their worst cases add up:
 *
 * | Part | Bound | Worst case in heap |
 * |---|---|---|
 * | Baseline (JVM, Ktor, Netty, caches) | measured | ~80 MB |
 * | Connections | [MAX_CONNECTIONS] (380) x ~1.5 MB | ~570 MB |
 * | Unsent outbound bytes (our outboxes) | [MAX_PENDING_BYTES_TOTAL] | 96 MB |
 * | Rooms at rest | [MAX_ROOM_STATE_BYTES_TOTAL] (120 MB encoded) x 1.3 | ~156 MB |
 * | Headroom (GC, in-flight encodes) | rest | ~118 MB |
 *
 * Per connection: an honest client holds ~60 KB of socket/session overhead plus at most one
 * unacknowledged snapshot (<= [MAX_STATE_BYTES]) in Ktor's per-connection output buffer (up to
 * 1 MiB, outside our accounting). Acks are predictable when one connection drives a room, so
 * colluding clients that ack without reading can fill that buffer. Worst case ~1.5 MB per
 * connection: Ktor's 1 MiB buffer plus the frame in our outgoing slot plus the frame being
 * written; measured up to ~1.35 MB (probe12: 570 colluding peers, 883 MB live). Ktor's ping
 * timeout and the stall/ack timers drop such clients within ~20 s. 380 rather than 400
 * connections: at 400 the theoretical worst case leaves only ~88 MB (91 % heap occupancy), and
 * probe12 already showed back-to-back full GCs at 87 %. A per-snapshot unpredictable ack token
 * (planned) brings the bound back to one snapshot per connection and allows far more connections.
 * Room objects take 0.9-1.3x their encoded size in heap (measured: ASCII-heavy maximal entries
 * 1.10, 3-byte characters 0.88, typical YouTube entries 1.27), hence the 1.3 factor.
 * Rooms: at most [MAX_ROOMS] (380); the room-state budget alone would allow 480 full ones.
 */
object Limits {
    const val MAX_MEMBERS = 20
    const val MAX_QUEUE = 500
    const val COMMANDS_PER_SECOND = 10

    /** All members of a room together (commands and TrackEnded), on top of the per-member limit. */
    const val ROOM_COMMANDS_PER_SECOND = 20
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
    /** Sized to the heap budget above: ~1.5 MB worst case per connection (colluding Acks). */
    const val MAX_CONNECTIONS = 380

    /** Live rooms per creator IP (IPv6: per /64), held until the room is deleted; beyond it RATE_LIMITED. */
    const val MAX_ROOMS_PER_IP = 10

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

    /**
     * A snapshot not acknowledged within this long drops the client. Longer than [SEND_STALL_MS]:
     * a 256 KB snapshot over a slow mobile link (2G) can take well over 20 s to arrive.
     */
    const val ACK_DEADLINE_MS = 45_000L

    const val METRICS_INTERVAL_MS = 10 * 60_000L

    const val SEARCH_PER_MINUTE = 30

    /** All uncached InnerTube calls together, search and video info (UpstreamBudget); beyond this both endpoints answer 503. */
    const val SEARCH_GLOBAL_PER_MINUTE = 300
    const val SEARCH_MAX_QUERY_LENGTH = 200

    /** /api/video per client IP: a bucket of its own, so adding videos does not use up searches. */
    const val VIDEO_PER_MINUTE = 30
}
