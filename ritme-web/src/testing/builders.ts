import type { QueueEntry, RoomState } from '../protocol';

/** Builders shared by unit tests. */
export function entry(entryId: string, overrides: Partial<QueueEntry> = {}): QueueEntry {
  return {
    entryId,
    videoId: `vid-${entryId}`,
    title: `Lagu ${entryId}`,
    artist: 'Artis',
    thumbnailUrl: null,
    durationMs: 200_000,
    addedBy: 'm1',
    kind: 'YOUTUBE_MUSIC',
    url: null,
    ...overrides,
  };
}

export function room(overrides: Partial<RoomState> = {}): RoomState {
  return {
    code: 'K7M2QX',
    version: 1,
    hostId: 'm1',
    settings: { guestsCanControl: true, guestsCanAdd: true },
    members: [
      { id: 'm1', name: 'Budi', isHost: true, connected: true, joinedAt: 1 },
      { id: 'm2', name: 'Sari', isHost: false, connected: true, joinedAt: 2 },
    ],
    queue: [],
    currentEntryId: null,
    playback: { isPlaying: false, positionMs: 0, anchorServerTimeMs: 0 },
    ...overrides,
  };
}
