import type { QueueEntry, RoomState } from '../protocol';

/**
 * Client-side copy of the permission table (spec 2.3), used only to hide buttons. The server
 * checks again and answers FORBIDDEN, so a stale view can never do harm.
 */
export function isHost(room: RoomState, memberId: string | null): boolean {
  return memberId !== null && room.hostId === memberId;
}

/** Play, Pause, Seek, Next, Previous, PlayEntry, Move, and Remove of other people's entries. */
export function canControl(room: RoomState, memberId: string | null): boolean {
  return isHost(room, memberId) || room.settings.guestsCanControl;
}

export function canRemove(room: RoomState, memberId: string | null, entry: QueueEntry): boolean {
  return entry.addedBy === memberId || canControl(room, memberId);
}

export interface QueueView {
  played: QueueEntry[];
  current: QueueEntry | null;
  upcoming: QueueEntry[];
}

/** Splits the queue around the current entry; with nothing current everything is upcoming. */
export function splitQueue(room: RoomState): QueueView {
  const index = room.queue.findIndex((e) => e.entryId === room.currentEntryId);
  if (index < 0) return { played: [], current: null, upcoming: room.queue };
  return {
    played: room.queue.slice(0, index),
    current: room.queue[index],
    upcoming: room.queue.slice(index + 1),
  };
}

/**
 * The Move target that swaps an upcoming entry with the one before it, or null when it is already
 * first. `afterEntryId: null` means "right after the current entry" (or the very top with nothing
 * current), exactly as the server reads it.
 */
export function moveUpTarget(room: RoomState, entryId: string): { afterEntryId: string | null } | null {
  const currentIndex = room.queue.findIndex((e) => e.entryId === room.currentEntryId);
  const index = room.queue.findIndex((e) => e.entryId === entryId);
  if (index <= currentIndex + 1) return null;
  const anchorIndex = index - 2;
  if (anchorIndex <= currentIndex) return { afterEntryId: null };
  return { afterEntryId: room.queue[anchorIndex].entryId };
}

/** The Move target that swaps an upcoming entry with the one after it, or null when it is last. */
export function moveDownTarget(room: RoomState, entryId: string): { afterEntryId: string | null } | null {
  const currentIndex = room.queue.findIndex((e) => e.entryId === room.currentEntryId);
  const index = room.queue.findIndex((e) => e.entryId === entryId);
  if (index <= currentIndex || index + 1 >= room.queue.length) return null;
  return { afterEntryId: room.queue[index + 1].entryId };
}
