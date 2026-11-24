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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areSystemBarsVisible
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import com.example.musicfy.LocalPlayerConnection
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.protocol.currentEntry
import com.example.musicfy.ritme.video.RitmeStageSlot
import com.example.musicfy.ritme.video.RitmeStageTarget
import com.example.musicfy.ui.player.styles.RotatingFrame
import com.example.musicfy.ui.player.styles.StyleCircle
import com.example.musicfy.ui.player.styles.TransportCircles
import com.example.musicfy.ui.player.styles.rememberStyleTones
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeChipFill
import com.example.musicfy.ui.screens.ritme.RitmePill
import com.example.musicfy.ui.screens.ritme.rememberRitme
import com.example.musicfy.ui.theme.InterFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether the room's video is shown full screen (spec 4.2, flow F). Main thread; one per app. */
object RitmeFullscreen {
    var open by mutableStateOf(false)
}

/** The quarter turn's length (RotatingFrame's spring settles in about this). */
private const val TURN_MS = 450L
/** How long system bars a swipe brought back stay (MainActivity's navigation bar: the same). */
private const val BARS_RETURN_MS = 2_500L
/** The side bar: wide enough for the "Ritme · N" chip. */
private val BarWidth = 132.dp
private val BarButtonSize = 36.dp

/**
 * Mounted once in MainActivity, above the player and under the popup sheets. Shows the room's video
 * full screen while [RitmeFullscreen.open]; closes itself when the room moves on to a song or an
 * unplayable entry, or this device leaves. A video-to-video advance (no stage target until the
 * player holds the next item) and a reconnect keep it open: black, with the bar, until the stage is back.
 */
@Composable
fun RitmeVideoFullscreenHost(window: Window, playerBarsBehavior: () -> Int) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val stays = RitmeStageSlot.fullscreenStays(state.room, state.inRoom)
    val connection = LocalPlayerConnection.current
    // Before anything can return early: `open` never outlives the room's video, nor the player
    // connection (without one nothing is shown, so it would stay open unseen, with the player's
    // stage and system bars standing aside for it).
    val closes = !stays || connection == null
    LaunchedEffect(closes, RitmeFullscreen.open) { if (closes && RitmeFullscreen.open) RitmeFullscreen.open = false }
    if (connection == null) return
    val track by connection.uiState.trackInfo.collectAsState()
    val target = rememberRitmeStageTarget(track.mediaId)
    // While open, the cover's stage steps aside (MorphingCover asks the same question), target or not.
    val slot = RitmeStageSlot.of(hasTarget = target != null, fullscreenOpen = RitmeFullscreen.open && stays, coverShown = false)
    if (slot != RitmeStageSlot.FULLSCREEN) return
    // The room's entry names what is (about to be) on stage; the player's title lags at an advance.
    val title = state.room?.currentEntry()?.title ?: track.title
    RitmeVideoFullscreen(target, title, window, playerBarsBehavior)
}

/**
 * The video turned a quarter (RotatingFrame, the activity stays portrait: spec D4/U2), system bars
 * hidden, the room's controls as a thin bar beside the video, never over it. A tap anywhere but a
 * button hides or shows the bar. Back or the exit button turns it upright. With no [target] (the
 * player is catching up with the next video) the video's place is black and no stage is mounted.
 *
 * The bars come back the way the open player's navigation bar does ([playerBarsBehavior]): the
 * default behaviour under gesture navigation, so the first back swipe goes back instead of only
 * revealing the bars (spec 4.2's Back requirement over its "transient by swipe"); transient with buttons.
 */
@Composable
private fun RitmeVideoFullscreen(target: RitmeStageTarget?, title: String, window: Window, playerBarsBehavior: () -> Int) {
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
    // Full screen owns the system bars while open (MainActivity's player effect stands aside).
    // Hidden again on every return to the app (from Home, the recents, a dialog).
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(window, windowFocused) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = barsBehavior()
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    // Bars a swipe brought back (the default behaviour keeps them) go away again after a moment,
    // as the open player's navigation bar does.
    @OptIn(ExperimentalLayoutApi::class)
    val barsShown = WindowInsets.areSystemBarsVisible
    LaunchedEffect(window, barsShown) {
        if (!barsShown) return@LaunchedEffect
        delay(BARS_RETURN_MS)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
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
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                if (target != null) RitmeVideoStage(target = target, modifier = Modifier.fillMaxSize())
            }
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
