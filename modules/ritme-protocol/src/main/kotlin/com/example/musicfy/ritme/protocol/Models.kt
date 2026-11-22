package com.example.musicfy.ritme.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Bump on any incompatible message change. Create/Join carry it; the server accepts
 * [MIN_SUPPORTED_PROTOCOL_VERSION]..[PROTOCOL_VERSION] and answers others with UPGRADE_REQUIRED.
 * Welcome tells the client which version the server speaks.
 */
const val PROTOCOL_VERSION = 1

/** Oldest client protocol the server still accepts. Raise only when dropping old clients. */
const val MIN_SUPPORTED_PROTOCOL_VERSION = 1

@Serializable
data class RoomSettings(val guestsCanControl: Boolean = true)

@Serializable
data class Member(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val connected: Boolean,
    val joinedAt: Long,
)

/**
 * What an entry is. The server treats all kinds alike (same queue, same sync); clients decide how
 * to render: audio for [YOUTUBE_MUSIC], a video player for [YOUTUBE_VIDEO], and the URL itself
 * (a direct video file or HLS/DASH stream found by the in-app browser) for [URL].
 */
@Serializable(with = MediaKindSerializer::class)
enum class MediaKind {
    YOUTUBE_MUSIC,
    YOUTUBE_VIDEO,
    URL,

    /** A kind added by a newer protocol. Clients skip such entries; the server never accepts them. */
    UNKNOWN,
}

internal object MediaKindSerializer : LenientEnumSerializer<MediaKind>("MediaKind", MediaKind.entries, MediaKind.UNKNOWN)

/**
 * An entry as a client proposes it. The server assigns [QueueEntry.entryId] and [QueueEntry.addedBy].
 * YouTube kinds need [videoId]; [MediaKind.URL] needs an http(s) [url] and sends `videoId = ""`.
 * `durationMs = 0` means unknown (live stream): no automatic advance, position is not clamped.
 */
@Serializable
data class NewEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long,
    val kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    val url: String? = null,
)

@Serializable
data class QueueEntry(
    val entryId: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long,
    val addedBy: String,
    val kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    val url: String? = null,
)

/** True when the length is unknown (live stream). */
val QueueEntry.isLive: Boolean get() = durationMs <= 0

/**
 * Where playback is, as of [anchorServerTimeMs] on the server clock. While playing, the position
 * moves forward with server time; an anchor in the future means "start at that moment".
 */
@Serializable
data class Playback(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorServerTimeMs: Long = 0,
)

@Serializable
data class RoomState(
    val code: String,
    val version: Long,
    val hostId: String,
    val settings: RoomSettings = RoomSettings(),
    val members: List<Member> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val playback: Playback = Playback(),
)

fun RoomState.currentEntry(): QueueEntry? =
    currentEntryId?.let { id -> queue.firstOrNull { it.entryId == id } }

/**
 * Playback position at [serverNow], clamped to the current entry (only at 0 for live entries).
 * 0 when nothing is current.
 */
fun RoomState.positionAt(serverNow: Long): Long {
    val entry = currentEntry() ?: return 0
    val raw = if (playback.isPlaying) {
        playback.positionMs + (serverNow - playback.anchorServerTimeMs)
    } else {
        playback.positionMs
    }
    return if (entry.isLive) raw.coerceAtLeast(0) else raw.coerceIn(0, entry.durationMs)
}

/**
 * Encodes an enum by name, exactly like the default serializer, but decodes names it does not
 * know (added by a newer protocol) to [fallback] instead of failing the whole message.
 */
internal open class LenientEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val values: List<T>,
    private val fallback: T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): T {
        val name = decoder.decodeString()
        return values.firstOrNull { it.name == name } ?: fallback
    }
}
