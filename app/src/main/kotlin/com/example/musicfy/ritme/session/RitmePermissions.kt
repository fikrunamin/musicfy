package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState

data class QueueView(val played: List<QueueEntry>, val current: QueueEntry?, val upcoming: List<QueueEntry>)

/** A Move command target: `afterEntryId = null` means "right after the current entry". */
data class MoveTarget(val afterEntryId: String?)

/**
 * Client-side copy of the permission table (spec 2.3), used only to hide or refuse controls. The
 * server checks again and answers FORBIDDEN, so a stale view can never do harm.
 */
object RitmePermissions {
    fun isHost(room: RoomState, memberId: String?): Boolean = memberId != null && room.hostId == memberId

    /** Play, Pause, Seek, Next, Previous, PlayEntry, Move, and Remove of other people's entries. */
    fun canControl(room: RoomState, memberId: String?): Boolean = isHost(room, memberId) || room.settings.guestsCanControl

    fun canRemove(room: RoomState, memberId: String?, entry: QueueEntry): Boolean =
        entry.addedBy == memberId || canControl(room, memberId)

    fun split(room: RoomState): QueueView {
        val index = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        if (index < 0) return QueueView(emptyList(), null, room.queue)
        return QueueView(room.queue.subList(0, index), room.queue[index], room.queue.subList(index + 1, room.queue.size))
    }

    /** Swaps an upcoming entry with the one before it; null when it is already first. */
    fun moveUpTarget(room: RoomState, entryId: String): MoveTarget? {
        val currentIndex = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        val index = room.queue.indexOfFirst { it.entryId == entryId }
        if (index <= currentIndex + 1) return null
        val anchorIndex = index - 2
        if (anchorIndex <= currentIndex) return MoveTarget(null)
        return MoveTarget(room.queue[anchorIndex].entryId)
    }

    /** Swaps an upcoming entry with the one after it; null when it is last (or current, or played). */
    fun moveDownTarget(room: RoomState, entryId: String): MoveTarget? {
        val currentIndex = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        val index = room.queue.indexOfFirst { it.entryId == entryId }
        if (index <= currentIndex || index + 1 >= room.queue.size) return null
        return MoveTarget(room.queue[index + 1].entryId)
    }
}
