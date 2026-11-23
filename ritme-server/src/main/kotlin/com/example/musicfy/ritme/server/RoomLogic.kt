package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RitmeJson
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry
import com.example.musicfy.ritme.protocol.isLive
import com.example.musicfy.ritme.protocol.positionAt
import java.text.Normalizer

/** Side effects a command asks the stateful [Room] to carry out. */
sealed interface Effect {
    data class Kicked(val memberId: String) : Effect

    data object Closed : Effect
}

/**
 * Size information for Add, so it can check the caps by encoding only the new entries:
 * [currentBytes] is the current snapshot's encoded size (-1: compute it), [budgetLeft] the
 * server-wide room-state budget still free.
 */
data class SizeContext(val currentBytes: Int = -1, val budgetLeft: Long = Long.MAX_VALUE)

sealed interface Outcome {
    /** [state] is the same instance when nothing changed (no broadcast needed). */
    data class Ok(val state: RoomState, val effect: Effect? = null) : Outcome

    data class Rejected(val code: ErrorCode) : Outcome
}

/** Pure room rules: (state, who, what, when) -> outcome. No I/O, no locks, no clock. */
object RoomLogic {
    private val FORBIDDEN = Outcome.Rejected(ErrorCode.FORBIDDEN)
    private val INVALID = Outcome.Rejected(ErrorCode.INVALID)

    fun apply(state: RoomState, by: String, cmd: Command, now: Long, ids: IdGen, size: SizeContext = SizeContext()): Outcome {
        val isHost = by == state.hostId
        val canControl = isHost || state.settings.guestsCanControl
        val canAdd = isHost || state.settings.guestsCanAdd
        fun control(block: () -> Outcome): Outcome = if (canControl) block() else FORBIDDEN
        fun hostOnly(block: () -> Outcome): Outcome = if (isHost) block() else FORBIDDEN

        return when (cmd) {
            Command.Play -> control { play(state, now) }
            Command.Pause -> control { pause(state, now) }
            is Command.Seek -> control { seek(state, cmd.positionMs, now) }
            Command.Next -> control { changed(advance(state, now)) }
            Command.Previous -> control { previous(state, now) }
            is Command.PlayEntry -> control {
                if (state.queue.any { it.entryId == cmd.entryId }) changed(startEntry(state, cmd.entryId, now)) else INVALID
            }
            is Command.Add -> if (canAdd) add(state, by, cmd, now, ids, size) else FORBIDDEN
            is Command.Remove -> remove(state, by, canControl, cmd.entryId, now)
            is Command.Move -> control { move(state, cmd.entryId, cmd.afterEntryId) }
            is Command.SetSettings -> hostOnly { changed(state.copy(settings = cmd.settings)) }
            is Command.Kick -> hostOnly { kick(state, by, cmd.memberId) }
            is Command.TransferHost -> hostOnly { transferHost(state, cmd.memberId) }
            Command.EndRoom -> hostOnly { Outcome.Ok(state, Effect.Closed) }
        }
    }

    fun newRoom(
        code: String,
        hostId: String,
        hostName: String,
        create: ClientMessage.Create,
        now: Long,
        ids: IdGen,
        maxBytes: Int = Limits.MAX_STATE_BYTES,
    ): RoomState {
        val empty = RoomState(
            code = code,
            version = 1,
            hostId = hostId,
            members = listOf(Member(hostId, hostName, isHost = true, connected = true, joinedAt = now)),
        )
        // Keep initial entries while the snapshot fits [maxBytes] (the rest is dropped, like
        // invalid ones); STATE_SLACK covers currentEntryId and playback.
        var budget = maxBytes - jsonBytes(empty) - STATE_SLACK
        val queue = create.initialQueue
            .asSequence()
            .filter { it.isValid() }
            .take(Limits.MAX_QUEUE)
            .map { it.toQueueEntry(ids.entryId(), hostId) }
            .takeWhile { entry ->
                budget -= jsonBytes(entry) + 1
                budget >= 0
            }
            .toList()
        val base = empty.copy(queue = queue)
        val start = queue.getOrNull(create.startIndex) ?: queue.firstOrNull() ?: return base
        val startAt = clampPosition(start, create.startPositionMs)
        return base.copy(
            currentEntryId = start.entryId,
            playback = Playback(true, startAt, now + Limits.START_DELAY_MS),
        )
    }

    /**
     * Advances on the first plausible end report for the current entry; null means ignore.
     * Live entries have no known end, so any report for them counts (the stream ended).
     */
    fun onTrackEnded(state: RoomState, entryId: String, now: Long): RoomState? {
        val current = state.currentEntry() ?: return null
        if (current.entryId != entryId || !state.playback.isPlaying) return null
        if (!current.isLive && state.positionAt(now) < current.durationMs - Limits.TRACK_ENDED_TOLERANCE_MS) return null
        return bump(advance(state, now))
    }

    /** Fallback when no client reports the end; null means nothing to do. Never for live entries. */
    fun tick(state: RoomState, now: Long): RoomState? {
        val current = state.currentEntry() ?: return null
        if (!state.playback.isPlaying || current.isLive) return null
        val raw = state.playback.positionMs + (now - state.playback.anchorServerTimeMs)
        return if (raw >= current.durationMs + Limits.FALLBACK_SLACK_MS) bump(advance(state, now)) else null
    }

    /** [cleanText], with at least one visible character and at most 32 chars. */
    fun cleanName(raw: String): String? =
        cleanText(raw).takeIf { hasVisible(it) && it.length <= Limits.MAX_NAME_LENGTH }

    /**
     * NFC, trimmed, without the characters that break or spoof a line of text: C0/C1 controls
     * (newlines, tabs), lone surrogates, bidi embeddings/overrides/isolates (U+202A-U+202E,
     * U+2066-U+2069), zero-width space U+200B, BOM U+FEFF, and line/paragraph separators.
     * Everything else stays, notably ZWJ/ZWNJ (emoji sequences, Persian and Indic text),
     * variation selectors, tag characters (subdivision flags), private-use and unassigned code
     * points (emoji newer than the JDK's Unicode tables). Used for names, titles and artists.
     */
    fun cleanText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(INVISIBLE, "").trim()

    private val INVISIBLE = Regex("[\\p{Cc}\\p{Cs}\\u200B\\u202A-\\u202E\\u2066-\\u2069\\uFEFF\\u2028\\u2029]")

    private val NOT_VISIBLE_TYPES = setOf(
        Character.FORMAT.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        Character.CONTROL.toInt(),
    )

    /** Letters that render as blank: Hangul fillers and the blank braille pattern. */
    private val BLANK_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

    /** True when [text] has something to see (not only joiners, selectors, marks, fillers or spaces). */
    private fun hasVisible(text: String): Boolean = text.codePoints().anyMatch {
        !Character.isWhitespace(it) && Character.getType(it) !in NOT_VISIBLE_TYPES && it !in BLANK_LETTERS
    }

    /** Bytes of the snapshot as sent (UTF-8 JSON). */
    fun stateBytes(state: RoomState): Int = jsonBytes(state)

    private const val STATE_SLACK = 256

    private fun jsonBytes(state: RoomState): Int = utf8Length(RitmeJson.encodeToString(RoomState.serializer(), state))

    private fun jsonBytes(entry: QueueEntry): Int = utf8Length(RitmeJson.encodeToString(QueueEntry.serializer(), entry))

    private fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    fun nextHost(state: RoomState, excluding: String): String? =
        state.members.filter { it.connected && it.id != excluding }.minByOrNull { it.joinedAt }?.id

    fun addMember(state: RoomState, member: Member): RoomState = bump(state.copy(members = state.members + member))

    fun setConnected(state: RoomState, memberId: String, connected: Boolean): RoomState =
        bump(state.copy(members = state.members.map { if (it.id == memberId) it.copy(connected = connected) else it }))

    /** Removes members; if the host is among them the role goes to [nextHost]. */
    fun removeMembers(state: RoomState, memberIds: Set<String>): RoomState {
        val remaining = state.copy(members = state.members.filterNot { it.id in memberIds })
        val newHost = if (state.hostId in memberIds) nextHost(remaining, excluding = state.hostId) else null
        return bump(if (newHost != null) withHost(remaining, newHost) else remaining)
    }

    fun withHost(state: RoomState, hostId: String): RoomState =
        state.copy(hostId = hostId, members = state.members.map { it.copy(isHost = it.id == hostId) })

    fun startEntry(state: RoomState, entryId: String, now: Long): RoomState =
        state.copy(currentEntryId = entryId, playback = Playback(true, 0, now + Limits.START_DELAY_MS))

    /** Next entry, or stop at the end of the last one. With nothing current, starts the first. */
    fun advance(state: RoomState, now: Long): RoomState {
        val index = state.queue.indexOfFirst { it.entryId == state.currentEntryId }
        val next = state.queue.getOrNull(index + 1)
        if (next != null) return startEntry(state, next.entryId, now)
        val current = state.currentEntry() ?: return state.copy(playback = Playback())
        val stopAt = if (current.isLive) state.positionAt(now) else current.durationMs
        return state.copy(playback = Playback(false, stopAt, now))
    }

    private fun play(state: RoomState, now: Long): Outcome {
        val current = state.currentEntry()
            ?: return state.queue.firstOrNull()?.let { changed(startEntry(state, it.entryId, now)) } ?: Outcome.Ok(state)
        if (state.playback.isPlaying) return Outcome.Ok(state)
        val position = state.positionAt(now)
        val from = if (!current.isLive && position >= current.durationMs) 0 else position
        return changed(state.copy(playback = Playback(true, from, now)))
    }

    private fun pause(state: RoomState, now: Long): Outcome {
        if (state.currentEntry() == null || !state.playback.isPlaying) return Outcome.Ok(state)
        return changed(state.copy(playback = Playback(false, state.positionAt(now), now)))
    }

    private fun seek(state: RoomState, positionMs: Long, now: Long): Outcome {
        val current = state.currentEntry() ?: return INVALID
        val clamped = clampPosition(current, positionMs)
        return changed(state.copy(playback = Playback(state.playback.isPlaying, clamped, now)))
    }

    private fun previous(state: RoomState, now: Long): Outcome {
        val current = state.currentEntry() ?: return INVALID
        val previous = state.queue.getOrNull(state.queue.indexOf(current) - 1)
        return if (previous == null || state.positionAt(now) > Limits.PREVIOUS_RESTART_MS) {
            seek(state, 0, now)
        } else {
            changed(startEntry(state, previous.entryId, now))
        }
    }

    private fun add(state: RoomState, by: String, cmd: Command.Add, now: Long, ids: IdGen, size: SizeContext): Outcome {
        if (cmd.entries.isEmpty() || cmd.entries.any { !it.isValid() }) return INVALID
        if (state.queue.size + cmd.entries.size > Limits.MAX_QUEUE) return Outcome.Rejected(ErrorCode.QUEUE_FULL)
        val added = cmd.entries.map { it.toQueueEntry(ids.entryId(), by) }
        // Only the new entries are encoded: current size + entries + separators (+ slack when a
        // track starts) must fit the room cap and the server-wide budget.
        val current = if (size.currentBytes >= 0) size.currentBytes else jsonBytes(state)
        val extra = added.sumOf { jsonBytes(it) + 1 } + (if (state.currentEntryId == null) STATE_SLACK else 0)
        if (current + extra > Limits.MAX_STATE_BYTES || extra > size.budgetLeft) return Outcome.Rejected(ErrorCode.QUEUE_FULL)
        val currentIndex = state.queue.indexOfFirst { it.entryId == state.currentEntryId }
        val queue = if (cmd.playNext && currentIndex >= 0) {
            state.queue.toMutableList().apply { addAll(currentIndex + 1, added) }
        } else {
            state.queue + added
        }
        val withQueue = state.copy(queue = queue)
        return changed(if (state.currentEntryId == null) startEntry(withQueue, added.first().entryId, now) else withQueue)
    }

    private fun remove(state: RoomState, by: String, canControl: Boolean, entryId: String, now: Long): Outcome {
        val entry = state.queue.firstOrNull { it.entryId == entryId } ?: return INVALID
        if (entry.addedBy != by && !canControl) return FORBIDDEN
        val base = if (entryId == state.currentEntryId) {
            val advanced = advance(state, now)
            if (advanced.currentEntryId == entryId) advanced.copy(currentEntryId = null, playback = Playback()) else advanced
        } else {
            state
        }
        return changed(base.copy(queue = base.queue.filterNot { it.entryId == entryId }))
    }

    private fun move(state: RoomState, entryId: String, afterEntryId: String?): Outcome {
        if (entryId == state.currentEntryId || entryId == afterEntryId) return INVALID
        val entry = state.queue.firstOrNull { it.entryId == entryId } ?: return INVALID
        val rest = state.queue.filterNot { it.entryId == entryId }
        val anchorId = afterEntryId ?: state.currentEntryId
        val insertAt = if (anchorId == null) {
            0
        } else {
            val anchorIndex = rest.indexOfFirst { it.entryId == anchorId }
            if (anchorIndex < 0) return INVALID
            anchorIndex + 1
        }
        return changed(state.copy(queue = rest.toMutableList().apply { add(insertAt, entry) }))
    }

    private fun kick(state: RoomState, by: String, target: String): Outcome {
        if (target == by || state.members.none { it.id == target }) return INVALID
        return Outcome.Ok(bump(state.copy(members = state.members.filterNot { it.id == target })), Effect.Kicked(target))
    }

    private fun transferHost(state: RoomState, target: String): Outcome {
        val member = state.members.firstOrNull { it.id == target } ?: return INVALID
        if (!member.connected || target == state.hostId) return INVALID
        return changed(withHost(state, target))
    }

    private fun bump(state: RoomState): RoomState = state.copy(version = state.version + 1)

    private fun changed(state: RoomState): Outcome = Outcome.Ok(bump(state))

    /** Live entries have no end, but positions stay within a day so time arithmetic cannot overflow. */
    private fun clampPosition(entry: QueueEntry, positionMs: Long): Long =
        positionMs.coerceIn(0, if (entry.isLive) Limits.MAX_DURATION_MS else entry.durationMs)

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,${Limits.MAX_VIDEO_ID_LENGTH}}$")

    /**
     * Every field is bounded so a full queue stays small when it is broadcast: YouTube kinds carry
     * only a videoId, URL entries only a url that passes MediaUrlRules, thumbnails are short https
     * links (for YouTube kinds only from YouTube's image hosts).
     */
    internal fun NewEntry.isValid(): Boolean {
        val source = when (kind) {
            MediaKind.YOUTUBE_MUSIC, MediaKind.YOUTUBE_VIDEO -> url == null && VIDEO_ID.matches(videoId)
            MediaKind.URL -> videoId.isEmpty() && url?.let(MediaUrlRules::isAcceptableMediaUrl) == true
            MediaKind.UNKNOWN -> false
        }
        return source &&
            title.length <= Limits.MAX_TEXT_LENGTH && hasVisible(cleanText(title)) &&
            artist.length <= Limits.MAX_TEXT_LENGTH &&
            isValidThumbnail(thumbnailUrl, youtube = kind == MediaKind.YOUTUBE_MUSIC || kind == MediaKind.YOUTUBE_VIDEO) &&
            durationMs in 0..Limits.MAX_DURATION_MS
    }

    private fun isValidThumbnail(thumbnailUrl: String?, youtube: Boolean): Boolean {
        if (thumbnailUrl == null) return true
        if (thumbnailUrl.length > Limits.MAX_THUMBNAIL_URL_LENGTH || !thumbnailUrl.startsWith("https://")) return false
        // Other members' devices load it directly: the same host rules as a URL entry's url.
        if (!youtube) return MediaUrlRules.isAcceptableMediaUrl(thumbnailUrl)
        return isYouTubeImageUrl(thumbnailUrl)
    }

    private fun NewEntry.toQueueEntry(entryId: String, addedBy: String) =
        QueueEntry(entryId, videoId, cleanText(title), cleanText(artist), thumbnailUrl, durationMs, addedBy, kind, url?.trim())
}
