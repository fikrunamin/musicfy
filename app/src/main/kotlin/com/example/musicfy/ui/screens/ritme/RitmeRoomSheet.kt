package com.example.musicfy.ui.screens.ritme

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ui.component.AppSwitch
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.Material3MenuGroup
import com.example.musicfy.ui.component.Material3MenuItemData
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetDivider
import com.example.musicfy.ui.component.SheetHeaderButton
import com.example.musicfy.ui.component.SheetLabel
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
    val secondary = LocalContentColor.current.copy(alpha = 0.6f)
    fun addedBy(entry: QueueEntry): String = if (entry.addedBy == me) you else names[entry.addedBy] ?: "?"

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.ritme_room_title, room.code))
                    val connected = room.members.count { it.connected }
                    Text(
                        text = if (state.connection == ConnectionStatus.RECONNECTING) {
                            stringResource(R.string.ritme_reconnecting)
                        } else {
                            pluralStringResource(R.plurals.ritme_members, connected, connected)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                    )
                }
                // The song menu header's round buttons.
                val share = stringResource(R.string.ritme_share)
                SheetHeaderButton(
                    icon = R.drawable.share,
                    onClick = { shareRoom(context, link) },
                    modifier = Modifier.semantics {
                        contentDescription = share
                        role = Role.Button
                    },
                )
                Spacer(Modifier.width(8.dp))
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
            SheetLabel(stringResource(R.string.ritme_now_playing), modifier = Modifier.padding(bottom = 8.dp))
        }
        item(key = "now") {
            val current = queue.current
            if (current == null) {
                Text(
                    text = stringResource(R.string.ritme_empty_queue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
            } else {
                EntryRow(
                    entry = current,
                    addedBy = addedBy(current),
                    isCurrent = true,
                    actions = RitmeRoomActions.forCurrent(room, me, current, online),
                    onPlay = {},
                    onRemove = { ritme.session.remove(current.entryId) },
                    onMoveUp = {},
                    onMoveDown = {},
                )
            }
        }
        if (queue.upcoming.isNotEmpty()) {
            item(key = "nextTitle") {
                SheetLabel(stringResource(R.string.ritme_up_next), modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            }
        }
        items(queue.upcoming, key = { it.entryId }) { entry ->
            val actions = RitmeRoomActions.forUpcoming(room, me, entry, online)
            EntryRow(
                entry = entry,
                addedBy = addedBy(entry),
                isCurrent = false,
                actions = actions,
                onPlay = { ritme.session.playEntry(entry.entryId) },
                onRemove = { ritme.session.remove(entry.entryId) },
                onMoveUp = { actions.moveUp?.let { ritme.session.move(entry.entryId, it.afterEntryId) } },
                onMoveDown = { actions.moveDown?.let { ritme.session.move(entry.entryId, it.afterEntryId) } },
            )
        }
        item(key = "divider2") { SheetDivider() }
        item(key = "actions") {
            // The same menu rows as the song, queue and player menus; a row is dimmed when its
            // onClick is null.
            val guestsCanControl = room.settings.guestsCanControl
            Material3MenuGroup(
                items = buildList {
                    add(
                        Material3MenuItemData(
                            icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                            title = { Text(stringResource(R.string.ritme_add_songs)) },
                            onClick = onAddSongs,
                        ),
                    )
                    if (isHost) {
                        add(
                            Material3MenuItemData(
                                icon = {
                                    Icon(
                                        painterResource(if (guestsCanControl) R.drawable.lock_open else R.drawable.lock),
                                        contentDescription = null,
                                    )
                                },
                                title = { Text(stringResource(R.string.ritme_guests_can_control)) },
                                onClick = if (canManage) ({ ritme.session.setGuestsCanControl(!guestsCanControl) }) else null,
                                // Shows the room's setting; the whole row toggles it.
                                trailingContent = { AppSwitch(checked = guestsCanControl, onCheckedChange = null, enabled = canManage) },
                            ),
                        )
                    }
                    add(
                        Material3MenuItemData(
                            icon = { Icon(painterResource(R.drawable.logout), contentDescription = null) },
                            title = { Text(stringResource(R.string.ritme_leave)) },
                            onClick = { ritme.session.leave() },
                        ),
                    )
                    if (isHost) {
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

/** The room in place of QueueScreen while a Ritme room is active (spec 3.4). */
@Composable
fun RitmeQueuePage(onClose: () -> Unit, navController: NavController, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val ritme = rememberRitme()
    val playerSheet = LocalPlayerBottomSheetState.current
    Box(
        modifier
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        RitmeRoomContent(
            ritme = ritme,
            onAddSongs = {
                onClose()
                playerSheet?.collapseSoft()
                navController.navigate(Screens.Search.route)
            },
            onLeft = onClose,
            bottomPadding = bottomPadding,
        )
    }
}

@Composable
private fun MembersRow(room: RoomState, me: String?, online: Boolean, onMember: (Member) -> Unit, modifier: Modifier = Modifier) {
    val you = stringResource(R.string.ritme_you)
    val host = stringResource(R.string.ritme_host)
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(64.dp)
                    .alpha(if (member.connected) 1f else 0.4f)
                    .clip(RowHighlightShape)
                    .clickable(enabled = actionable, role = Role.Button) { onMember(member) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (member.id == room.hostId) "$label, $host" else label
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
    actions: EntryActions,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val ink = LocalContentColor.current
    val playable = RitmeEntries.isPlayable(entry)
    val artwork = remember(entry.thumbnailUrl) { entry.thumbnailUrl?.resize(RowArtPx, RowArtPx) }
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
            .padding(start = 10.dp),
    ) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(RowArtSize)
                .clip(RowArtShape)
                .background(ink.copy(alpha = 0.10f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val by = stringResource(R.string.ritme_added_by, addedBy)
            Text(
                text = if (playable) "${entry.artist} · $by" else "${stringResource(R.string.ritme_unsupported)} · $by",
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    content: @Composable (room: RoomState, me: String?, online: Boolean, close: () -> Unit) -> Unit,
) {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle {
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
            Text(link, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
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
                            ritme.session.transferHost(member.id)
                            close()
                        }) else null,
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_kick)) },
                        onClick = if (actions.canKick) ({
                            ritme.session.kick(member.id)
                            close()
                        }) else null,
                    ),
                ),
            )
        }
    }
}

private fun PopupSheetState.showEndConfirm(ritme: RitmeUi) {
    showWhileValid(ritme, stillValid = { room, me -> RitmePermissions.isHost(room, me) }) { room, me, online, close ->
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
                            ritme.session.endRoom()
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
