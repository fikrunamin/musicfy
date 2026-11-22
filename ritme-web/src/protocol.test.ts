import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  type ClientMessage,
  type Command,
  type NewEntry,
  type QueueEntry,
  type RoomState,
  type ServerMessage,
  PROTOCOL_VERSION,
  encodeClientMessage,
  parseServerMessage,
  positionAt,
} from './protocol';

const FIXTURES = fileURLToPath(new URL('../../modules/ritme-protocol/fixtures/', import.meta.url));
const fixture = (name: string): string => readFileSync(`${FIXTURES}${name}.json`, 'utf8');

// The same instances as modules/ritme-protocol/src/test/kotlin/.../Samples.kt.
const newEntry: NewEntry = {
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  thumbnailUrl: null,
  durationMs: 213_000,
  kind: 'YOUTUBE_MUSIC',
  url: null,
};
const entry: QueueEntry = {
  entryId: 'e1',
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  thumbnailUrl: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
  durationMs: 213_000,
  addedBy: 'm1',
  kind: 'YOUTUBE_MUSIC',
  url: null,
};
const state: RoomState = {
  code: 'K7M2QX',
  version: 3,
  hostId: 'm1',
  settings: { guestsCanControl: true },
  members: [
    { id: 'm1', name: 'Budi', isHost: true, connected: true, joinedAt: 1_000 },
    { id: 'm2', name: 'Sari', isHost: false, connected: false, joinedAt: 2_000 },
  ],
  queue: [entry],
  currentEntryId: 'e1',
  playback: { isPlaying: true, positionMs: 5_000, anchorServerTimeMs: 1_700_000_000_000 },
};
const send = (cmd: Command): ClientMessage => ({ type: 'Command', reqId: 'r1', cmd });

const clientSamples: Record<string, ClientMessage> = {
  'client-create': {
    type: 'Create',
    protocolVersion: PROTOCOL_VERSION,
    name: 'Budi',
    initialQueue: [newEntry],
    startIndex: 0,
    startPositionMs: 0,
  },
  'client-join': { type: 'Join', protocolVersion: PROTOCOL_VERSION, code: 'K7M2QX', name: 'Sari', resumeToken: 'tok' },
  'client-leave': { type: 'Leave' },
  'client-ping': { type: 'Ping', clientTime: 123 },
  'client-track-ended': { type: 'TrackEnded', entryId: 'e1' },
  'cmd-play': send({ type: 'Play' }),
  'cmd-pause': send({ type: 'Pause' }),
  'cmd-seek': send({ type: 'Seek', positionMs: 42_000 }),
  'cmd-next': send({ type: 'Next' }),
  'cmd-previous': send({ type: 'Previous' }),
  'cmd-play-entry': send({ type: 'PlayEntry', entryId: 'e1' }),
  'cmd-add': send({ type: 'Add', entries: [newEntry], playNext: true }),
  'cmd-add-youtube-video': send({
    type: 'Add',
    entries: [{ ...newEntry, title: 'Never Gonna Give You Up (Official Video)', kind: 'YOUTUBE_VIDEO' }],
    playNext: false,
  }),
  'cmd-add-url-live': send({
    type: 'Add',
    entries: [
      {
        videoId: '',
        title: 'Live stream',
        artist: '',
        thumbnailUrl: null,
        durationMs: 0,
        kind: 'URL',
        url: 'https://example.com/live/index.m3u8',
      },
    ],
    playNext: false,
  }),
  'cmd-remove': send({ type: 'Remove', entryId: 'e1' }),
  'cmd-move': send({ type: 'Move', entryId: 'e1', afterEntryId: null }),
  'cmd-set-settings': send({ type: 'SetSettings', settings: { guestsCanControl: false } }),
  'cmd-kick': send({ type: 'Kick', memberId: 'm2' }),
  'cmd-transfer-host': send({ type: 'TransferHost', memberId: 'm2' }),
  'cmd-end-room': send({ type: 'EndRoom' }),
};

const serverSamples: Record<string, ServerMessage> = {
  'server-welcome': {
    type: 'Welcome',
    memberId: 'm1',
    resumeToken: 'tok',
    state,
    serverTime: 1_700_000_000_500,
    protocolVersion: 1,
  },
  'server-state': { type: 'State', state },
  'server-pong': { type: 'Pong', clientTime: 123, serverTime: 1_700_000_000_500 },
  'server-error': { type: 'Error', reqId: 'r1', code: 'FORBIDDEN' },
  'server-kicked': { type: 'Kicked' },
  'server-room-closed': { type: 'RoomClosed' },
};

describe('golden fixtures', () => {
  it('every fixture file has a TypeScript sample, and every sample a fixture', () => {
    const files = readdirSync(FIXTURES)
      .filter((f) => f.endsWith('.json'))
      .map((f) => f.slice(0, -'.json'.length))
      .sort();
    expect(files).toEqual([...Object.keys(clientSamples), ...Object.keys(serverSamples)].sort());
  });

  for (const [name, message] of Object.entries(clientSamples)) {
    it(`encodes ${name} exactly like Kotlin (same keys, same order)`, () => {
      expect(encodeClientMessage(message)).toBe(JSON.stringify(JSON.parse(fixture(name))));
    });
  }

  for (const [name, message] of Object.entries(serverSamples)) {
    it(`parses ${name}`, () => {
      expect(parseServerMessage(fixture(name))).toEqual(message);
    });
  }
});

describe('forward compatibility (mirrors ForwardCompatibilityTest.kt)', () => {
  const entryJson =
    '{"entryId":"e1","videoId":"","title":"T","artist":"A","durationMs":1,"addedBy":"m1","kind":"PODCAST","url":null}';

  it('decodes an unknown media kind to UNKNOWN', () => {
    const msg = parseServerMessage(
      `{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","queue":[${entryJson}]}}`,
    );
    expect(msg?.type === 'State' && msg.state.queue[0].kind).toBe('UNKNOWN');
  });

  it('decodes an unknown error code to UNKNOWN', () => {
    expect(parseServerMessage('{"type":"Error","reqId":"r1","code":"SERVER_ON_FIRE"}')).toEqual({
      type: 'Error',
      reqId: 'r1',
      code: 'UNKNOWN',
    });
  });

  it('returns null for unknown message types and malformed JSON', () => {
    expect(parseServerMessage('{"type":"Reaction","emoji":"x"}')).toBeNull();
    expect(parseServerMessage('{"type":')).toBeNull();
    expect(parseServerMessage('not json')).toBeNull();
    expect(parseServerMessage('[1,2]')).toBeNull();
    expect(parseServerMessage('{"type":"Pong","clientTime":"soon","serverTime":2}')).toBeNull();
    expect(parseServerMessage('{"type":"Pong","clientTime":1,"serverTime":2}')).toEqual({
      type: 'Pong',
      clientTime: 1,
      serverTime: 2,
    });
  });

  it('fills Kotlin defaults: Welcome without protocolVersion, minimal state, minimal entry', () => {
    const msg = parseServerMessage(
      '{"type":"Welcome","memberId":"m1","resumeToken":"t","serverTime":5,"state":{"code":"K7M2QX","version":1,"hostId":"m1","queue":[{"entryId":"e1","videoId":"v","title":"T","artist":"A","durationMs":1,"addedBy":"m1"}]}}',
    );
    expect(msg).toEqual({
      type: 'Welcome',
      memberId: 'm1',
      resumeToken: 't',
      serverTime: 5,
      protocolVersion: PROTOCOL_VERSION,
      state: {
        code: 'K7M2QX',
        version: 1,
        hostId: 'm1',
        settings: { guestsCanControl: true },
        members: [],
        queue: [
          {
            entryId: 'e1',
            videoId: 'v',
            title: 'T',
            artist: 'A',
            thumbnailUrl: null,
            durationMs: 1,
            addedBy: 'm1',
            kind: 'YOUTUBE_MUSIC',
            url: null,
          },
        ],
        currentEntryId: null,
        playback: { isPlaying: false, positionMs: 0, anchorServerTimeMs: 0 },
      },
    });
  });

  it('ignores unknown fields', () => {
    expect(parseServerMessage('{"type":"Pong","clientTime":1,"serverTime":2,"future":"x"}')).toEqual({
      type: 'Pong',
      clientTime: 1,
      serverTime: 2,
    });
  });
});

describe('positionAt (mirrors PositionAtTest.kt)', () => {
  const at = (playback: RoomState['playback'], durationMs = 213_000): RoomState => ({
    ...state,
    queue: [{ ...entry, durationMs }],
    playback,
  });

  it('moves with server time while playing', () => {
    expect(positionAt(at({ isPlaying: true, positionMs: 5_000, anchorServerTimeMs: 1_000 }), 3_000)).toBe(7_000);
  });

  it('stays put while paused', () => {
    expect(positionAt(at({ isPlaying: false, positionMs: 5_000, anchorServerTimeMs: 1_000 }), 9_000)).toBe(5_000);
  });

  it('is clamped to 0 before a future anchor and to the duration at the end', () => {
    expect(positionAt(at({ isPlaying: true, positionMs: 0, anchorServerTimeMs: 10_000 }), 8_500)).toBe(0);
    expect(positionAt(at({ isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 }), 999_999)).toBe(213_000);
  });

  it('is not clamped to an end for live entries', () => {
    expect(positionAt(at({ isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 }, 0), 999_999)).toBe(999_999);
  });

  it('is 0 with nothing current', () => {
    expect(positionAt({ ...state, currentEntryId: null }, 5_000)).toBe(0);
  });
});
