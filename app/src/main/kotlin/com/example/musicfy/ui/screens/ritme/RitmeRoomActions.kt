package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.isLive
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.MoveTarget
import com.example.musicfy.ritme.session.RitmePermissions

/** What a queue row offers. A null move target hides that arrow. */
internal data class EntryActions(
    val canPlay: Boolean,
    val canRemove: Boolean,
    val moveUp: MoveTarget?,
    val moveDown: MoveTarget?,
) {
    companion object {
        val NONE = EntryActions(canPlay = false, canRemove = false, moveUp = null, moveDown = null)
    }
}

/** What the host may do to another member. */
internal data class MemberActions(val canTransferHost: Boolean, val canKick: Boolean) {
    val any: Boolean get() = canTransferHost || canKick
}

/**
 * Which room controls the UI shows, mirroring RitmePermissions and the server's RoomLogic: Remove
 * for your own entries or with control; PlayEntry and Move with control; Kick, TransferHost,
 * SetSettings and EndRoom for the host only, TransferHost only to a connected member and never to
 * yourself. Nothing is offered while [online] is false, because RitmeSession refuses to send then
 * (the web client hides them the same way). Pure, for unit tests.
 */
internal object RitmeRoomActions {
    /** The entry being played: it can only be removed (play/move make no sense for it). */
    fun forCurrent(room: RoomState, me: String?, entry: QueueEntry, online: Boolean): EntryActions =
        if (!online) EntryActions.NONE else EntryActions.NONE.copy(canRemove = RitmePermissions.canRemove(room, me, entry))

    fun forUpcoming(room: RoomState, me: String?, entry: QueueEntry, online: Boolean): EntryActions {
        if (!online) return EntryActions.NONE
        val control = RitmePermissions.canControl(room, me)
        return EntryActions(
            canPlay = control && RitmeEntries.isPlayable(entry),
            canRemove = RitmePermissions.canRemove(room, me, entry),
            moveUp = if (control) RitmePermissions.moveUpTarget(room, entry.entryId) else null,
            moveDown = if (control) RitmePermissions.moveDownTarget(room, entry.entryId) else null,
        )
    }

    fun forMember(room: RoomState, me: String?, member: Member, online: Boolean): MemberActions {
        val allowed = online && RitmePermissions.isHost(room, me) && member.id != me &&
            room.members.any { it.id == member.id }
        return MemberActions(
            canTransferHost = allowed && member.connected && member.id != room.hostId,
            canKick = allowed,
        )
    }

    /** "Members can control", "Members can add" and "End Ritme". */
    fun canManageRoom(room: RoomState, me: String?, online: Boolean): Boolean = online && RitmePermissions.isHost(room, me)
}

/** The avatar letter: the name's first code point (an emoji stays whole), upper-cased; "?" for a blank name. */
internal fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
}

/** The short word in front of a row's subtitle (spec 4.2): videos say "Video", live videos "LIVE". */
internal enum class EntryTag { NONE, VIDEO, LIVE }

internal fun entryTag(entry: QueueEntry): EntryTag = when {
    !RitmeEntries.isVideo(entry) -> EntryTag.NONE
    entry.isLive -> EntryTag.LIVE
    else -> EntryTag.VIDEO
}
