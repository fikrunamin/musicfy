package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.musicfy.di.RitmeEntryPoint
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.RitmeSheetRequest
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.screens.Screens
import dagger.hilt.android.EntryPointAccessors

/** The singletons Ritme's UI talks to. [bridge] is null only in tests. */
@Stable
class RitmeUi(
    val session: RitmeSession,
    val controller: RitmeController,
    val bridge: com.example.musicfy.ritme.playback.RitmePlaybackBridge? = null,
)

@Composable
fun rememberRitme(): RitmeUi {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        val entry = EntryPointAccessors.fromApplication(app, RitmeEntryPoint::class.java)
        RitmeUi(entry.ritmeSession(), entry.ritmeController(), entry.ritmeBridge())
    }
}

/** Shows whatever sheet RitmeController asks for (player menu, song menus, deep links). Mounted once in MainActivity. */
@Composable
fun RitmeSheetOpener(navController: NavController) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val popup = LocalZoomOutOverlayState.current
    val request by ritme.controller.sheetRequest.collectAsState()
    LaunchedEffect(request?.id) {
        val current = request ?: return@LaunchedEffect
        ritme.controller.consumeSheetRequest(current.id)
        when (val kind = current.kind) {
            RitmeSheetRequest.Kind.Room -> popup.showRitmeRoomSheet(ritme, navController)
            is RitmeSheetRequest.Kind.Entry -> popup.showRitmeEntrySheet(ritme, kind.code, kind.startWith, kind.startWithEntries)
            is RitmeSheetRequest.Kind.AddVideo -> popup.showRitmeAddVideoSheet(ritme, kind.text)
        }
    }
}

// One of each at a time: a second tap on the pill (or a repeated deep link) must not stack a copy
// of the sheet already showing. Main thread only; cleared once that sheet has left. The popup state
// these sheets go on (LocalZoomOutOverlayState's default) outlives an activity, so these do too.
/**
 * The entry sheet that is showing: its form, so a deep link can fill in its code, and its handle, so
 * the room sheet can take its place instead of being pushed over it.
 */
private class OpenEntrySheet(val form: RitmeEntryForm) {
    var handle: PopupSheetHandle? = null
}
private var openEntry: OpenEntrySheet? = null
private var roomSheetOpen = false

fun PopupSheetState.showRitmeEntrySheet(ritme: RitmeUi, code: String?, startWith: List<MediaMetadata?>?, startWithEntries: List<NewEntry>? = null) {
    openEntry?.form?.let { open ->
        // Already showing: a link's code goes into it (not while an attempt is under way), and an
        // error from the previous attempt no longer applies.
        if (code != null && ritme.session.state.value.phase != RitmePhase.CONNECTING) {
            ritme.session.dismiss()
            open.joining = true
            open.code = code
        }
        return
    }
    // A failure left over from an earlier attempt is not this sheet's error.
    ritme.session.dismiss()
    val form = RitmeEntryForm(code, startWith, startWithEntries)
    val open = OpenEntrySheet(form)
    openEntry = open
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = { RitmeEntryButton(ritme, form) },
        onClosed = {
            if (openEntry === open) openEntry = null
            // Closed while still connecting: give that attempt up. After a failure: back to idle.
            when (ritme.session.state.value.phase) {
                RitmePhase.CONNECTING -> ritme.session.leave()
                RitmePhase.ENDED -> ritme.session.dismiss()
                else -> Unit
            }
        },
    ) {
        RitmeEntryContent(
            ritme = ritme,
            form = form,
            // In the room: this sheet's job is done. The host of a new room gets the room sheet
            // from RitmeController (once per created room); a joiner just sees the pill.
            onJoined = { handle?.dismiss() },
        )
    }
    open.handle = handle
}

/** The "Add video" sheet that is showing: a second share fills its field instead of stacking a copy. */
private var openAddVideo: RitmeAddVideoForm? = null

fun PopupSheetState.showRitmeAddVideoSheet(ritme: RitmeUi, text: String?) {
    openAddVideo?.let { open ->
        if (text != null) open.fill(text)
        return
    }
    val form = RitmeAddVideoForm(text, roomMode = ritme.session.state.value.inRoom)
    openAddVideo = form
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = { RitmeAddVideoButtons(ritme, form, onDone = { handle?.dismiss() }) },
        onClosed = { if (openAddVideo === form) openAddVideo = null },
    ) {
        RitmeAddVideoContent(ritme = ritme, form = form, onRoomGone = { handle?.dismiss() })
    }
}

fun PopupSheetState.showRitmeRoomSheet(ritme: RitmeUi, navController: NavController) =
    showRitmeRoomSheet(ritme) { navController.navigate(Screens.Search.route) }

internal fun PopupSheetState.showRitmeRoomSheet(ritme: RitmeUi, openSearch: () -> Unit) {
    // The host of a new room gets this sheet while the entry sheet may still be up (it closes
    // itself a frame later): close it now, so the room sheet takes its place rather than being
    // pushed over it. The room sheet then goes in underneath the leaving entry sheet.
    openEntry?.handle?.dismiss()
    if (roomSheetOpen) return
    roomSheetOpen = true
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(onClosed = { roomSheetOpen = false }) {
        val playerSheet = LocalPlayerBottomSheetState.current
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        RitmeRoomContent(
            ritme = ritme,
            onAddSongs = {
                handle?.dismiss()
                // compositionLocalOf<BottomSheetState?>: null outside the main layout
                playerSheet?.let { if (it.isExpanded) it.collapseSoft() }
                // In a room, tapping a search result adds it to the room (RitmeController, PlayQueue).
                openSearch()
            },
            onAddVideo = {
                // The "Add video" sheet takes this one's place (it goes in underneath while this leaves).
                handle?.dismiss()
                ritme.controller.openAddVideo()
            },
            onLeft = { handle?.dismiss() },
            bottomPadding = 16.dp + navigationBar,
        )
    }
}
