package com.example.musicfy.ui.screens.ritme.video

import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import com.example.musicfy.LocalPlayerConnection
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeLocalAction
import com.example.musicfy.ritme.playback.RitmeAddressGuard
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ritme.video.FollowAction
import com.example.musicfy.ritme.video.RitmeStageTarget
import com.example.musicfy.ritme.video.RitmeVideoStreams
import com.example.musicfy.ritme.video.VideoFollower
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeChipFill
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryAlpha
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.rememberRitme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The room's current video entry once the player holds it (see [RitmeStageTarget.of]), else null. */
@Composable
fun rememberRitmeStageTarget(loadedMediaId: String): RitmeStageTarget? {
    if (!RitmeConfig.ENABLED) return null
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val inRoom = state.inRoom
    return remember(room, inRoom, loadedMediaId) { if (inRoom) RitmeStageTarget.of(room, loadedMediaId) else null }
}

/**
 * The video stage (spec 4.2–4.4): the whole video, fit and never cropped, on black. Nothing is drawn
 * over the picture; only when there is no picture does a short note say why. It runs only while
 * the app is on screen (started): with the screen off or the app in the background, the follower is
 * released and a URL entry stops decoding video, while the sound plays on (watch-together turns into
 * listen-together); coming back prepares the follower again at the audio's position.
 */
@Composable
internal fun RitmeVideoStage(target: RitmeStageTarget, modifier: Modifier = Modifier) {
    val connection = LocalPlayerConnection.current ?: return
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val transport by connection.uiState.transportState.collectAsState()
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            if (transport.isPlaying) KeepScreenOn()
            when (target) {
                is RitmeStageTarget.Url -> UrlVideo(connection.player)
                is RitmeStageTarget.YouTube -> FollowedVideo(target, connection.player)
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A URL entry: MusicService's player renders it here, decoding video only while this is shown. */
@Composable
private fun BoxScope.UrlVideo(player: ExoPlayer) {
    val ritme = rememberRitme()
    DisposableEffect(ritme) {
        ritme.bridge?.videoShown()
        onDispose { ritme.bridge?.videoHidden() }
    }
    VideoSurface(player)
    val error = rememberPlayerError(player) ?: return
    if (RitmeAddressGuard.isRefusal(error)) {
        // MusicService has paused it and said so (spec 4.5); the link fails the same way every time,
        // so this is not a playback hiccup to skip past: the note only says why there is no picture.
        StageNote(stringResource(R.string.ritme_url_local_address_blocked))
        return
    }
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val canSkip = room != null && RitmePermissions.canControl(room, state.memberId)
    StageNote(stringResource(R.string.ritme_video_cannot_play)) {
        // The room does not wait for anyone (spec 8): whoever may control it can move it on.
        if (canSkip) {
            RitmeChip(
                text = stringResource(R.string.ritme_video_skip),
                containerColor = RitmeChipFill,
                icon = R.drawable.skip_next,
                onClick = { ritme.controller.handle(RitmeLocalAction.Next) },
            )
        }
    }
}

/** The player's error from the moment it fails until the player plays or renders again, else null. */
@Composable
private fun rememberPlayerError(player: Player): PlaybackException? {
    var failure by remember(player) { mutableStateOf(player.playerError) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = error
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) failure = null
            }

            override fun onRenderedFirstFrame() {
                failure = null
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return failure
}

/** A YouTube video: a muted follower player for the picture, kept on the audio by [VideoFollower]. */
@Composable
private fun BoxScope.FollowedVideo(target: RitmeStageTarget.YouTube, audio: ExoPlayer) {
    val context = LocalContext.current
    var streamUrl by remember(target.videoId) { mutableStateOf<String?>(null) }
    var unavailable by remember(target.videoId) { mutableStateOf(false) }
    LaunchedEffect(target.videoId) {
        val url = RitmeVideoStreams.resolve(context, target.videoId)
        if (url == null) unavailable = true else streamUrl = url
    }
    StageThumbnail(target.thumbnailUrl)
    val url = streamUrl
    if (url == null) {
        if (unavailable) StageNote(stringResource(R.string.ritme_video_unavailable))
        return
    }
    val follower = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setSeekParameters(SeekParameters.EXACT)
            prepare()
            seekTo(audio.currentPosition)
        }
    }
    var shown by remember(follower) { mutableStateOf(false) }
    var broken by remember(follower) { mutableStateOf(false) }
    DisposableEffect(follower) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                shown = true
            }

            override fun onPlayerError(error: PlaybackException) {
                broken = true
            }
        }
        follower.addListener(listener)
        onDispose {
            follower.removeListener(listener)
            follower.release()
        }
    }
    LaunchedEffect(follower) {
        var lastSeekAt: Long? = null
        while (isActive) {
            // Buffering or paused audio pauses the picture; buffering video never holds the audio.
            val advancing = audio.isPlaying
            if (follower.playWhenReady != advancing) follower.playWhenReady = advancing
            val now = SystemClock.elapsedRealtime()
            val action = VideoFollower.evaluate(
                audioMs = audio.currentPosition,
                videoMs = follower.currentPosition,
                audioSpeed = audio.playbackParameters.speed,
                videoSpeed = follower.playbackParameters.speed,
                audioAdvancing = advancing,
                nowMs = now,
                lastSeekAtMs = lastSeekAt,
            )
            when (action) {
                is FollowAction.Seek -> {
                    follower.seekTo(action.positionMs)
                    lastSeekAt = now
                }
                is FollowAction.SetSpeed -> follower.playbackParameters = PlaybackParameters(action.speed)
                FollowAction.Ignore -> Unit
            }
            delay(VideoFollower.TICK_MS)
        }
    }
    // Fades in on its first frame, as the cover's video background does (isVideoReady).
    val alpha by animateFloatAsState(if (shown && !broken) 1f else 0f, tween(400), label = "ritmeVideoAlpha")
    VideoSurface(follower, Modifier.graphicsLayer { this.alpha = alpha })
    if (broken) StageNote(stringResource(R.string.ritme_video_unavailable))
}

/** A TextureView (it turns with RotatingFrame, a SurfaceView would not) letterboxed to the video's own shape. */
@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val textureView = remember(player) { TextureView(context) }
    val aspect = remember(player) {
        AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(textureView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
    val frame = remember(player) {
        FrameLayout(context).apply {
            // Display only: taps belong to the player around it (double tap to like, swipes).
            isEnabled = false
            isClickable = false
            isFocusable = false
            addView(aspect, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }
    }
    DisposableEffect(player, textureView) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = aspect.fit(videoSize)
        }
        aspect.fit(player.videoSize)
        player.addListener(listener)
        player.setVideoTextureView(textureView)
        onDispose {
            player.removeListener(listener)
            // only clears it if this view is still the player's
            player.clearVideoTextureView(textureView)
        }
    }
    AndroidView(factory = { frame }, modifier = modifier.fillMaxSize())
}

private fun AspectRatioFrameLayout.fit(size: VideoSize) {
    if (size.width > 0 && size.height > 0) setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
}

@Composable
private fun StageThumbnail(url: String?) {
    if (url == null) return
    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/** Why there is no picture: the sheets' secondary line, white at 60 %, with an optional chip under it. */
@Composable
private fun BoxScope.StageNote(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RitmeSecondaryText(
            text = text,
            color = Color.White.copy(alpha = RitmeSecondaryAlpha),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        action?.invoke()
    }
}
