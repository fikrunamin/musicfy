package com.example.musicfy.ui.screens.ritme

import android.content.ActivityNotFoundException
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.example.musicfy.ui.player.CardMorphState
import com.example.musicfy.ui.player.ImmersiveScrollPx
import com.example.musicfy.ui.player.LikeBurstState
import com.example.musicfy.ui.player.QueuePageHeader
import com.example.musicfy.ui.player.queueListBottomInset
import com.example.musicfy.ui.player.queueListEdgeFade
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.example.musicfy.R
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.Material3MenuGroup
import com.example.musicfy.ui.component.Material3MenuItemData
import com.example.musicfy.ui.component.PlayingIndicatorBox
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetDivider
import com.example.musicfy.ui.component.SheetHeaderButton
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.player.LyricsHeaderArtX
import com.example.musicfy.ui.screens.Screens
import com.example.musicfy.ui.utils.resize

// Queue rows mirror QueueScreen's QueueSongRow (private there, and bound to the local player's
// items): a 60 dp row, a 44 dp cover with 8 dp corners, a 14 dp press/highlight shape, the playing
// row lifted with a 7 % white wash, and 44 dp touch boxes holding 22 dp glyphs at the handle's 50 %.
private val RowMinHeight = 60.dp
private val RowArtSize = 44.dp
private val RowArtShape = RoundedCornerShape(8.dp)
private val RowHighlightShape = RoundedCornerShape(14.dp)
private const val RowArtPx = 144
private val ActionBoxSize = 44.dp
private val ActionIconSize = 22.dp

/** A video row's art: 16:9 at the same 44 dp height (SubpageVideoCell's shape, the row's corners). */
private val RowVideoArtWidth = RowArtSize * 16f / 9f

/** PopupSheet's content side padding (and RitmeQueuePage's), which the rows sit inside. */
private val SheetSidePadding = 20.dp
/** QueueSongRow puts the cover's left edge at LyricsHeaderArtX from the screen edge; so do we. */
private val RowArtStart = LyricsHeaderArtX - SheetSidePadding
/** QueueSectionHeader's space above and below a section title. */
private val SectionTitleTop = 16.dp
private val SectionTitleBottom = 8.dp
/** SheetHeader's gap before its round button. */
private val HeaderButtonGap = 10.dp

// One pushed Ritme sheet (QR, member actions, end confirmation) at a time over the room: a double
// tap must not stack two. Main thread only; reset when that sheet has left (its onClosed).
private var pushedSheetOpen = false

/**
 * The room (spec 3.3): code with share/QR, members, now playing, up next, then the room's actions
 * as menu rows. Controls the member is not allowed to use are hidden (spec 2.3,
 * [RitmeRoomActions]); the server checks again. Everything shown comes from the latest server
 * snapshot.
 */
@Composable
internal fun RitmeRoomContent(
    ritme: RitmeUi,
    onAddSongs: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
    topPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
) {
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val left by rememberUpdatedState(onLeft)
    LaunchedEffect(room == null) {
        if (room == null) left()
    }
    if (room == null) return
    val me = state.memberId
    val online = state.connection == ConnectionStatus.ONLINE
    val isHost = RitmePermissions.isHost(room, me)
    val canManage = RitmeRoomActions.canManageRoom(room, me, online)
    val queue = remember(room) { RitmePermissions.split(room) }
    val names = remember(room.members) { room.members.associate { it.id to it.name } }
    val popup = LocalZoomOutOverlayState.current
    val context = LocalContext.current
    val link = remember(room.code) { ritme.controller.shareLink(room.code) }
    val you = stringResource(R.string.ritme_you)
    // The member who added it may have left the room since.
    val someone = stringResource(R.string.ritme_someone)
    fun addedBy(entry: QueueEntry): String = if (entry.addedBy == me) you else names[entry.addedBy] ?: someone

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.ritme_room_title, room.code))
                    val connected = room.members.count { it.connected }
                    RitmeSecondaryText(
                        text = if (state.connection == ConnectionStatus.RECONNECTING) {
                            stringResource(R.string.ritme_reconnecting)
                        } else {
                            pluralStringResource(R.plurals.ritme_members, connected, connected)
                        },
                    )
                }
                // The song menu header's round buttons, with its gap before them.
                Spacer(Modifier.width(HeaderButtonGap))
                val share = stringResource(R.string.ritme_share)
                SheetHeaderButton(
                    icon = R.drawable.share,
                    onClick = { shareRoom(context, link) },
                    modifier = Modifier.semantics {
                        contentDescription = share
                        role = Role.Button
                    },
                )
                Spacer(Modifier.width(HeaderButtonGap))
                val qr = stringResource(R.string.ritme_qr)
                SheetHeaderButton(
                    icon = R.drawable.qr_code,
                    onClick = { popup.showRitmeQr(ritme, link, room.code) },
                    modifier = Modifier.semantics {
                        contentDescription = qr
                        role = Role.Button
                    },
                )
            }
        }
        item(key = "members") {
            MembersRow(
                room = room,
                me = me,
                online = online,
                onMember = { popup.showMemberActions(ritme, it.id) },
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        item(key = "divider1") { SheetDivider() }
        item(key = "nowTitle") {
            SheetLabel(stringResource(R.string.ritme_now_playing), modifier = Modifier.padding(bottom = SectionTitleBottom))
        }
        item(key = "now") {
            val current = queue.current
            if (current == null) {
                RitmeSecondaryText(stringResource(R.string.ritme_empty_queue))
            } else {
                EntryRow(
                    entry = current,
                    addedBy = addedBy(current),
                    isCurrent = true,
                    isPlaying = room.playback.isPlaying,
                    actions = RitmeRoomActions.forCurrent(room, me, current, online),
                    onPlay = {},
                    onRemove = { ritme.controller.roomCommand { remove(current.entryId) } },
                    onMoveUp = {},
                    onMoveDown = {},
                )
            }
        }
        if (queue.upcoming.isNotEmpty()) {
            item(key = "nextTitle") {
                SheetLabel(
                    stringResource(R.string.ritme_up_next),
                    modifier = Modifier.padding(top = SectionTitleTop, bottom = SectionTitleBottom),
                )
            }
        }
        items(queue.upcoming, key = { it.entryId }) { entry ->
            val actions = RitmeRoomActions.forUpcoming(room, me, entry, online)
            EntryRow(
                entry = entry,
                addedBy = addedBy(entry),
                isCurrent = false,
                isPlaying = false,
                actions = actions,
                onPlay = { ritme.controller.roomCommand { playEntry(entry.entryId) } },
                onRemove = { ritme.controller.roomCommand { remove(entry.entryId) } },
                onMoveUp = { actions.moveUp?.let { ritme.controller.roomCommand { move(entry.entryId, it.afterEntryId) } } },
                onMoveDown = { actions.moveDown?.let { ritme.controller.roomCommand { move(entry.entryId, it.afterEntryId) } } },
            )
        }
        item(key = "divider2") { SheetDivider() }
        item(key = "actions") {
            // The same menu rows as the song, queue and player menus (Material3MenuGroup, 8 dp
            // apart); a row is dimmed when its onClick is null. The switch rows are that row's
            // mirror with a real switch (RitmeMenuSwitchRow).
            val guestsCanControl = room.settings.guestsCanControl
            val guestsCanAdd = room.settings.guestsCanAdd
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Hidden, not dimmed, while the host keeps guests from adding (spec 8).
                if (RitmePermissions.canAdd(room, me)) {
                    Material3MenuGroup(
                        items = listOf(
                            Material3MenuItemData(
                                icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                                title = { Text(stringResource(R.string.ritme_add_songs)) },
                                onClick = onAddSongs,
                            ),
                        ),
                    )
                }
                if (isHost) {
                    RitmeMenuSwitchRow(
                        icon = {
                            Icon(
                                painterResource(if (guestsCanControl) R.drawable.lock_open else R.drawable.lock),
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.ritme_guests_can_control),
                        checked = guestsCanControl,
                        enabled = canManage,
                        onCheckedChange = { ritme.controller.roomCommand { setGuestsCanControl(it) } },
                    )
                    RitmeMenuSwitchRow(
                        icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                        title = stringResource(R.string.ritme_guests_can_add),
                        checked = guestsCanAdd,
                        enabled = canManage,
                        onCheckedChange = { ritme.controller.roomCommand { setGuestsCanAdd(it) } },
                    )
                }
                Material3MenuGroup(
                    items = buildList {
                        add(
                            Material3MenuItemData(
                                icon = { Icon(painterResource(R.drawable.logout), contentDescription = null) },
                                title = { Text(stringResource(R.string.ritme_leave)) },
                                onClick = { ritme.session.leave() },
                            ),
                        )
                        if (isHost) {
                            // Untinted, like the menus' Delete rows, which also lead to a confirmation.
                            add(
                                Material3MenuItemData(
                                    icon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                                    title = { Text(stringResource(R.string.ritme_end)) },
                                    onClick = if (canManage) ({ popup.showEndConfirm(ritme) }) else null,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * The room in place of QueueScreen while a Ritme room is active (spec 3.4), dressed as QueueScreen:
 * no page background of its own (the player's backdrop shows through), the same header the cover
 * and title morph into, the list ending at the seek bar with the same edge fades, and the same
 * scroll-to-hide controls ([onImmersiveChange]).
 */
@Composable
fun RitmeQueuePage(
    onClose: () -> Unit,
    navController: NavController,
    /** As QueueScreen's: distance from the bottom of the screen to the top of the seek bar. */
    contentBottomInset: Dp,
    /** As QueueScreen's: how far the player's button column reaches up from there. */
    controlsClearance: Dp,
    modifier: Modifier = Modifier,
    likeBurst: LikeBurstState? = null,
    morph: CardMorphState? = null,
    onImmersiveChange: (Boolean) -> Unit = {},
) {
    val ritme = rememberRitme()
    val playerSheet = LocalPlayerBottomSheetState.current
    val listState = rememberLazyListState()
    // No row here grows out of the bottom card: drop a target left by an earlier QueueScreen, so
    // the card's art lifts off and fades instead (PlayerBottomCardStack).
    if (morph != null) LaunchedEffect(Unit) { morph.pageArtAnchor = Offset.Unspecified }

    // QueueScreen's scroll-to-hide: scroll down and the controls slide away, scroll back up (or
    // reach the top) and they return. Only the user's own scrolling counts.
    var immersive by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onImmersiveChange(false) } }
    LaunchedEffect(immersive) { onImmersiveChange(immersive) }
    val immersion by animateFloatAsState(
        targetValue = if (immersive) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "ritmeQueueImmersion",
    )
    val scrollWatcher = remember {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = consumed.y
                if (dy == 0f) return Offset.Zero
                travel = if ((travel < 0f) == (dy < 0f)) travel + dy else dy
                if (travel < -ImmersiveScrollPx) immersive = true
                if (travel > ImmersiveScrollPx || !listState.canScrollBackward) immersive = false
                return Offset.Zero
            }
        }
    }

    // QueueScreen's rows are white on the player's backdrop.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(modifier.fillMaxSize()) {
            QueuePageHeader(likeBurst = likeBurst, onArtClick = { if (immersive) immersive = false else onClose() })
            RitmeRoomContent(
                ritme = ritme,
                onAddSongs = {
                    onClose()
                    playerSheet?.collapseSoft()
                    navController.navigate(Screens.Search.route)
                },
                onLeft = onClose,
                // QueueScreen's list padding: 2 dp under the header, and room at the end for the
                // last rows to clear the button column.
                topPadding = 2.dp,
                bottomPadding = controlsClearance + 8.dp,
                listState = listState,
                modifier = Modifier
                    .weight(1f)
                    .queueListBottomInset(contentBottomInset) { immersion }
                    .nestedScroll(scrollWatcher)
                    .queueListEdgeFade()
                    // The rows' own insets assume the sheet's side padding (RowArtStart).
                    .padding(horizontal = SheetSidePadding),
            )
        }
    }
}

@Composable
private fun MembersRow(room: RoomState, me: String?, online: Boolean, onMember: (Member) -> Unit, modifier: Modifier = Modifier) {
    val you = stringResource(R.string.ritme_you)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        room.members.forEach { member ->
            // Host only: tap someone else for "Make host" / "Remove from Ritme".
            val actionable = RitmeRoomActions.forMember(room, me, member, online).any
            val label = if (member.id == me) you else member.name
            val spoken = if (member.id == room.hostId) stringResource(R.string.ritme_member_host, label) else label
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(64.dp)
                    .alpha(if (member.connected) 1f else 0.4f)
                    .clip(RowHighlightShape)
                    .clickable(enabled = actionable, role = Role.Button) { onMember(member) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                    }
                    .padding(vertical = 6.dp),
            ) {
                RitmeAvatar(name = member.name, isHost = member.id == room.hostId)
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: QueueEntry,
    addedBy: String,
    isCurrent: Boolean,
    /** The room is playing [entry] (only for the current row): QueueSongRow's playing bars. */
    isPlaying: Boolean,
    actions: EntryActions,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val ink = LocalContentColor.current
    val playable = RitmeEntries.isPlayable(entry)
    val tag = entryTag(entry)
    val wide = tag != EntryTag.NONE
    // URL entries' pictures are never loaded (any host could track who looks): a placeholder instead.
    val artwork = remember(entry.thumbnailUrl, entry.kind) {
        when {
            entry.kind == MediaKind.URL -> null
            wide -> entry.thumbnailUrl
            else -> entry.thumbnailUrl?.resize(RowArtPx, RowArtPx)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(RowHighlightShape)
            .background(if (isCurrent) ink.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(
                enabled = actions.canPlay,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = ink),
                onClickLabel = stringResource(R.string.ritme_play_now),
                role = Role.Button,
                onClick = onPlay,
            )
            .padding(start = RowArtStart),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = if (wide) RowVideoArtWidth else RowArtSize, height = RowArtSize),
        ) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RowArtShape)
                    .background(ink.copy(alpha = 0.10f)),
            )
            if (entry.kind == MediaKind.URL) {
                Icon(
                    painter = painterResource(R.drawable.web_link),
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.5f),
                    modifier = Modifier.size(ActionIconSize),
                )
            }
            // QueueScreen's NowPlayingIndicator (private there), driven by the room's playback.
            if (isCurrent) {
                PlayingIndicatorBox(
                    isActive = true,
                    playWhenReady = isPlaying,
                    shape = RowArtShape,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = entry.title,
                fontSize = RitmeRowTitleSize,
                lineHeight = RitmeRowTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val what = when {
                !playable -> stringResource(R.string.ritme_unsupported) // UNKNOWN (or a link this app refuses)
                tag == EntryTag.VIDEO -> stringResource(R.string.ritme_tagged, stringResource(R.string.ritme_tag_video), entry.artist)
                tag == EntryTag.LIVE -> stringResource(R.string.ritme_tagged, stringResource(R.string.ritme_tag_live), entry.artist)
                else -> entry.artist
            }
            RitmeSecondaryText(stringResource(R.string.ritme_entry_subtitle, what, addedBy), maxLines = 1)
        }
        if (actions.moveUp != null) RowAction(R.drawable.arrow_upward, stringResource(R.string.ritme_move_up), onMoveUp)
        if (actions.moveDown != null) RowAction(R.drawable.arrow_downward, stringResource(R.string.ritme_move_down), onMoveDown)
        if (actions.canRemove) RowAction(R.drawable.close, stringResource(R.string.ritme_remove), onRemove)
    }
}

/** A queue row's trailing control, sized and tinted like QueueScreen's drag handle. */
@Composable
private fun RowAction(icon: Int, description: String, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ActionBoxSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = ink.copy(alpha = 0.5f),
            modifier = Modifier.size(ActionIconSize),
        )
    }
}

/**
 * A sheet pushed over the room sheet (the PopupSheet stack: the room recedes behind it) that closes
 * itself once [stillValid] turns false for the latest room (the room ended, the member left, this
 * device stopped being host), so it never acts on a snapshot that no longer holds.
 */
private fun PopupSheetState.showWhileValid(
    ritme: RitmeUi,
    stillValid: (room: RoomState, me: String?) -> Boolean,
    locked: Boolean = false,
    content: @Composable (room: RoomState, me: String?, online: Boolean, close: () -> Unit) -> Unit,
) {
    if (pushedSheetOpen) return
    pushedSheetOpen = true
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(locked = locked, onClosed = { pushedSheetOpen = false }) {
        val state by ritme.session.state.collectAsState()
        val room = state.room
        val valid = room != null && stillValid(room, state.memberId)
        LaunchedEffect(valid) {
            if (!valid) handle?.dismiss()
        }
        if (room != null) {
            content(room, state.memberId, state.connection == ConnectionStatus.ONLINE) { handle?.dismiss() }
        }
    }
}

private fun PopupSheetState.showRitmeQr(ritme: RitmeUi, link: String, code: String) {
    showWhileValid(ritme, stillValid = { room, _ -> room.code == code }) { _, _, _, _ ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 22.dp),
        ) {
            SheetLabel(stringResource(R.string.ritme_room_title, code))
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    // The kit's card corner (SheetOptionRow / SheetActionCard).
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                RitmeQrCode(text = link, modifier = Modifier.fillMaxWidth(), contentDescription = link)
            }
            Spacer(Modifier.height(12.dp))
            RitmeSecondaryText(link)
        }
    }
}

private fun PopupSheetState.showMemberActions(ritme: RitmeUi, memberId: String) {
    showWhileValid(
        ritme,
        stillValid = { room, me ->
            val member = room.members.firstOrNull { it.id == memberId }
            // Not tied to being online: a short reconnect only dims the rows.
            member != null && RitmeRoomActions.forMember(room, me, member, online = true).any
        },
    ) { room, me, online, close ->
        val member = room.members.firstOrNull { it.id == memberId } ?: return@showWhileValid
        val actions = RitmeRoomActions.forMember(room, me, member, online)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RitmeAvatar(name = member.name, isHost = false)
                Spacer(Modifier.width(14.dp))
                SheetLabel(member.name)
            }
            Spacer(Modifier.height(18.dp))
            Material3MenuGroup(
                items = listOf(
                    // Only to a connected member (the server answers INVALID otherwise).
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.crown), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_make_host)) },
                        onClick = if (actions.canTransferHost) ({
                            ritme.controller.roomCommand { transferHost(member.id) }
                            close()
                        }) else null,
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_kick)) },
                        onClick = if (actions.canKick) ({
                            ritme.controller.roomCommand { kick(member.id) }
                            close()
                        }) else null,
                    ),
                ),
            )
        }
    }
}

private fun PopupSheetState.showEndConfirm(ritme: RitmeUi) {
    // Locked, per docs/popup-sheet.skill.md for an irreversible choice: only End or Cancel close it.
    showWhileValid(ritme, stillValid = { room, me -> RitmePermissions.isHost(room, me) }, locked = true) { room, me, online, close ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            SheetLabel(stringResource(R.string.ritme_end_confirm_title))
            Spacer(Modifier.height(18.dp))
            Material3MenuGroup(
                items = listOf(
                    Material3MenuItemData(
                        // Tinted like the menus' red heart: the one destructive choice here.
                        icon = {
                            Icon(
                                painterResource(R.drawable.close),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        title = { Text(stringResource(R.string.ritme_end)) },
                        onClick = if (RitmeRoomActions.canManageRoom(room, me, online)) ({
                            ritme.controller.roomCommand { endRoom() }
                            close()
                        }) else null,
                    ),
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.ritme_cancel)) },
                        onClick = close,
                    ),
                ),
            )
        }
    }
}

private fun shareRoom(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.ritme_share_text, link))
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No share target at all (rare); the QR and the code on screen still work.
    }
}
