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
import com.example.musicfy.ritme.RitmeSheetRequest
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.screens.Screens
import dagger.hilt.android.EntryPointAccessors

/** The two singletons Ritme's UI talks to. */
@Stable
class RitmeUi(val session: RitmeSession, val controller: RitmeController)

@Composable
fun rememberRitme(): RitmeUi {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        val entry = EntryPointAccessors.fromApplication(app, RitmeEntryPoint::class.java)
        RitmeUi(entry.ritmeSession(), entry.ritmeController())
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
            is RitmeSheetRequest.Kind.Entry -> popup.showRitmeEntrySheet(ritme, kind.code, kind.startWith)
        }
    }
}

// One of each at a time: a second tap on the pill (or a repeated deep link) must not stack a copy
// of the sheet already showing. Main thread only; reset once that sheet has left. The popup state
// these sheets go on (LocalZoomOutOverlayState's default) outlives an activity, so these do too.
private var entrySheetOpen = false
private var roomSheetOpen = false

fun PopupSheetState.showRitmeEntrySheet(ritme: RitmeUi, code: String?, startWith: List<MediaMetadata?>?) {
    if (entrySheetOpen) return
    entrySheetOpen = true
    // A failure left over from an earlier attempt is not this sheet's error.
    ritme.session.dismiss()
    val form = RitmeEntryForm(code, startWith)
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = { RitmeEntryButton(ritme, form) },
        onClosed = {
            entrySheetOpen = false
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
            onJoined = {
                handle?.dismiss()
                ritme.controller.openSheet()
            },
        )
    }
}

fun PopupSheetState.showRitmeRoomSheet(ritme: RitmeUi, navController: NavController) {
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
                navController.navigate(Screens.Search.route)
            },
            onLeft = { handle?.dismiss() },
            bottomPadding = 16.dp + navigationBar,
        )
    }
}
