package com.example.musicfy.ui.screens.ritme.video

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.musicfy.LocalPlayerConnection
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.video.RitmeStageSlot
import com.example.musicfy.ritme.video.RitmeStageTarget
import com.example.musicfy.ui.player.styles.RotatingFrame
import com.example.musicfy.ui.player.styles.StyleCircle
import com.example.musicfy.ui.player.styles.TransportCircles
import com.example.musicfy.ui.player.styles.rememberStyleTones
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeChipFill
import com.example.musicfy.ui.screens.ritme.RitmePill
import com.example.musicfy.ui.theme.InterFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether the room's video is shown full screen (spec 4.2, flow F). Main thread; one per app. */
object RitmeFullscreen {
    var open by mutableStateOf(false)
}

/** The quarter turn's length (RotatingFrame's spring settles in about this). */
private const val TURN_MS = 450L
/** The side bar: wide enough for the "Ritme · N" chip. */
private val BarWidth = 132.dp
private val BarButtonSize = 36.dp

/**
 * Mounted once in MainActivity, above the player and under the popup sheets. Shows the room's video
 * full screen while [RitmeFullscreen.open] and a stage target exists; closes itself when the room
 * moves on to a song or this device leaves.
 */
@Composable
fun RitmeVideoFullscreenHost(window: Window, playerBarsBehavior: () -> Int) {
    if (!RitmeConfig.ENABLED) return
    val connection = LocalPlayerConnection.current ?: return
    val track by connection.uiState.trackInfo.collectAsState()
    val target = rememberRitmeStageTarget(track.mediaId)
    LaunchedEffect(target == null) { if (target == null) RitmeFullscreen.open = false }
    // While open, the cover's stage steps aside (MorphingCover asks the same question).
    val slot = RitmeStageSlot.of(hasTarget = target != null, fullscreenOpen = RitmeFullscreen.open, coverShown = false)
    if (slot != RitmeStageSlot.FULLSCREEN || target == null) return
    RitmeVideoFullscreen(target, track.title, window, playerBarsBehavior)
}

/**
 * The video turned a quarter (RotatingFrame, the activity stays portrait: spec D4/U2), system bars
 * hidden (transient by swipe), the room's controls as a thin bar beside the video, never over it.
 * A tap anywhere but a button hides or shows the bar. Back or the exit button turns it upright.
 */
@Composable
private fun RitmeVideoFullscreen(target: RitmeStageTarget, title: String, window: Window, playerBarsBehavior: () -> Int) {
    val scope = rememberCoroutineScope()
    // Starts upright and turns, so opening plays RotatingFrame's quarter turn.
    var rotated by remember { mutableStateOf(false) }
    var barShown by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { rotated = true }
    val close: () -> Unit = {
        if (rotated) {
            rotated = false
            scope.launch {
                delay(TURN_MS)
                RitmeFullscreen.open = false
            }
        }
    }
    BackHandler(onBack = close)
    val barsBehavior by rememberUpdatedState(playerBarsBehavior)
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // The open player keeps the navigation bar hidden itself (MainActivity): only the status bar returns.
            controller.systemBarsBehavior = barsBehavior()
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    val tones = rememberStyleTones()
    RotatingFrame(
        rotated = rotated,
        tones = tones,
        background = { Box(Modifier.fillMaxSize().background(Color.Black)) },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { barShown = !barShown }) },
    ) { landscape ->
        // RotatingFrame draws both orientations while it turns: only the sideways one holds the
        // stage, so one stage (one follower, one texture) lives from the opening turn to the closing
        // one. The upright layer is the black background turning away or in.
        if (landscape) Row(modifier = Modifier.fillMaxSize()) {
            RitmeVideoStage(target = target, modifier = Modifier.weight(1f).fillMaxHeight())
            AnimatedVisibility(
                visible = barShown,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(BarWidth)
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    val exitLabel = stringResource(R.string.ritme_video_exit_fullscreen)
                    StyleCircle(
                        icon = R.drawable.fullscreen_exit,
                        tones = tones,
                        size = BarButtonSize,
                        onClick = close,
                        modifier = Modifier.semantics {
                            contentDescription = exitLabel
                            role = Role.Button
                        },
                    )
                    Text(
                        text = title,
                        color = tones.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    TransportCircles(tones = tones, size = BarButtonSize, vertical = true)
                    RitmePill(alphaProvider = { 1f })
                }
            }
        }
    }
}

/**
 * "Full screen" on the open flagship player, in the top-right slot RitmeCornerButton uses outside a
 * room: the same chip, with the existing fullscreen glyph. Only while a room video is current.
 */
@Composable
fun RitmeFullscreenButton(loadedMediaId: String, alphaProvider: () -> Float, modifier: Modifier = Modifier) {
    if (rememberRitmeStageTarget(loadedMediaId) == null) return
    val alpha by rememberUpdatedState(alphaProvider)
    val visible by remember { derivedStateOf { alpha() > 0.05f } }
    // Not there while invisible, so an invisible chip never takes a tap.
    if (!visible || RitmeFullscreen.open) return
    RitmeChip(
        text = stringResource(R.string.ritme_video_fullscreen),
        containerColor = RitmeChipFill,
        icon = R.drawable.fullscreen,
        onClick = { RitmeFullscreen.open = true },
        modifier = modifier.graphicsLayer { this.alpha = alpha() },
    )
}
