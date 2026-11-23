package com.example.musicfy.ritme.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.example.musicfy.extensions.metadata
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeLocalAction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The media session's player while a Ritme room is active (MusicService swaps it in and out).
 * Notification buttons, headset keys, Bluetooth, Android Auto and the widget-less media controls
 * all reach the player through the session, so here they become intents for [gate] instead of
 * changing the local player. Whether a pause is a room Pause or "this device only" is decided by
 * RitmeController, not here.
 *
 * Built on ForwardingSimpleBasePlayer (Media3 1.7.1): the commands it advertises come from
 * [getState], so the events the session forwards to the notification carry next/previous too. (A
 * ForwardingPlayer that only overrides getAvailableCommands() still forwards the wrapped player's
 * commands in onAvailableCommandsChanged/onEvents, and the buttons can vanish.) SimpleBasePlayer
 * calls handleSeek for seekToNext/seekToPrevious even when the wrapped player has nothing next
 * (with mediaItemIndex = C.INDEX_UNSET), which is what a one-item player in a room needs.
 *
 * Never call release() on it: that releases the wrapped ExoPlayer. MusicService keeps one per player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RitmeSessionPlayer(
    player: Player,
    private val gate: RitmeGate,
    now: () -> Long = android.os.SystemClock::elapsedRealtime,
) : ForwardingSimpleBasePlayer(player) {

    private val addThenPlay = RitmeAddThenPlayFilter(now)

    /** The player behind it, so MusicService can reuse this wrapper for the same player. */
    val wrapped: Player get() = player

    /**
     * Recomputes the advertised state now. MusicService calls it before handing this wrapper to
     * the session again: a reused wrapper's last state may date from outside a room (no forced
     * next/previous) and the wrapped player need not change before the room's item loads.
     */
    fun refresh() = invalidateState()

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        if (!gate.isActive) return state
        val commands = state.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Android Auto's play-from-media-id is setMediaItems, prepare, play: in a room that is an Add,
        // and the play() belonging to it must not start (or resume) the room as well.
        if (gate.isActive && addThenPlay.swallow(playWhenReady)) return done()
        return if (gate.intercept(if (playWhenReady) RitmeLocalAction.Play else RitmeLocalAction.Pause)) done()
        else super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!gate.isActive) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        ritmeSeekIntent(seekCommand, positionMs)?.let(gate::intercept)
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.Shuffle)) done() else super.handleSetShuffleModeEnabled(shuffleModeEnabled)

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.Repeat)) done() else super.handleSetRepeatMode(repeatMode)

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.Speed)) done() else super.handleSetPlaybackParameters(playbackParameters)

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.PlayQueue(ritmeSongsFrom(mediaItems, startIndex)))) {
            addThenPlay.onAdded()
            done()
        } else {
            super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.AddItems(mediaItems.map { it.metadata }, playNext = false))) done()
        else super.handleAddMediaItems(index, mediaItems)

    // The player holds only the room's current item: a controller must not move, remove or replace it.
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.QueueEdit)) done() else super.handleMoveMediaItems(fromIndex, toIndex, newIndex)

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.QueueEdit)) done() else super.handleRemoveMediaItems(fromIndex, toIndex)

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        if (gate.intercept(RitmeLocalAction.QueueEdit)) done() else super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
}

/**
 * What a controller's seek means in a room, or null for nothing. Media3's play button on an ENDED
 * player seeks to the default position first; in a room that must not restart anything, so it is
 * a no-op (the play() that follows is routed). A seek without a position (skip to a queue item)
 * has nothing to offer the room either.
 */
internal fun ritmeSeekIntent(seekCommand: Int, positionMs: Long): RitmeLocalAction? = when (seekCommand) {
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> RitmeLocalAction.Next
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> RitmeLocalAction.Previous
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> null
    // seek bar, seek back/forward, seekTo(index, position)
    else -> if (positionMs != C.TIME_UNSET) RitmeLocalAction.Seek(positionMs.coerceAtLeast(0)) else null
}

/** The songs a controller's setMediaItems would start with: from [startIndex] (unset = the first). */
internal fun ritmeSongsFrom(mediaItems: List<MediaItem>, startIndex: Int): List<MediaMetadata?> {
    val from = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceAtLeast(0)
    return mediaItems.drop(from).map { it.metadata }
}

/**
 * In a room a controller's setMediaItems is only an Add. Media3 follows it with prepare() and
 * play() (Android Auto's play-from-media-id), and that play must not start a paused room: a play
 * request that arrives within [WINDOW_MS] of the add is swallowed, once. Any other play/pause
 * request in between ends the window.
 */
internal class RitmeAddThenPlayFilter(private val now: () -> Long) {
    private var addedAt: Long? = null

    fun onAdded() {
        addedAt = now()
    }

    /** True when this play/pause request is the play that belongs to the add just before it. */
    fun swallow(playWhenReady: Boolean): Boolean {
        val at = addedAt ?: return false
        addedAt = null
        return playWhenReady && now() - at <= WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 1_000L
    }
}
