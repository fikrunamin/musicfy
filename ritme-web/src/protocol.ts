/**
 * TypeScript mirror of modules/ritme-protocol (Models.kt, Messages.kt, RitmeJson.kt). The golden
 * fixtures in modules/ritme-protocol/fixtures keep both sides in step (see protocol.test.ts).
 *
 * Decoding is tolerant exactly like decodeServerMessageOrNull: unknown message types and malformed
 * JSON give null, unknown fields are ignored, unknown MediaKind / ErrorCode values become UNKNOWN,
 * and missing fields that have a Kotlin default get that default.
 */

export const PROTOCOL_VERSION = 1;
export const MIN_SUPPORTED_PROTOCOL_VERSION = 1;

export const MEDIA_KINDS = ['YOUTUBE_MUSIC', 'YOUTUBE_VIDEO', 'URL', 'UNKNOWN'] as const;
export type MediaKind = (typeof MEDIA_KINDS)[number];

export const ERROR_CODES = [
  'ROOM_NOT_FOUND',
  'ROOM_FULL',
  'FORBIDDEN',
  'RATE_LIMITED',
  'UPGRADE_REQUIRED',
  'INVALID',
  'QUEUE_FULL',
  'UNKNOWN',
] as const;
export type ErrorCode = (typeof ERROR_CODES)[number];

export interface RoomSettings {
  guestsCanControl: boolean;
}

export interface Member {
  id: string;
  name: string;
  isHost: boolean;
  connected: boolean;
  joinedAt: number;
}

/** An entry as the client proposes it; the server assigns entryId and addedBy. */
export interface NewEntry {
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl: string | null;
  durationMs: number;
  kind: MediaKind;
  url: string | null;
}

export interface QueueEntry {
  entryId: string;
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl: string | null;
  durationMs: number;
  addedBy: string;
  kind: MediaKind;
  url: string | null;
}

export interface Playback {
  isPlaying: boolean;
  positionMs: number;
  anchorServerTimeMs: number;
}

export interface RoomState {
  code: string;
  version: number;
  hostId: string;
  settings: RoomSettings;
  members: Member[];
  queue: QueueEntry[];
  currentEntryId: string | null;
  playback: Playback;
}

export type Command =
  | { type: 'Play' }
  | { type: 'Pause' }
  | { type: 'Seek'; positionMs: number }
  | { type: 'Next' }
  | { type: 'Previous' }
  | { type: 'PlayEntry'; entryId: string }
  | { type: 'Add'; entries: NewEntry[]; playNext: boolean }
  | { type: 'Remove'; entryId: string }
  | { type: 'Move'; entryId: string; afterEntryId: string | null }
  | { type: 'SetSettings'; settings: RoomSettings }
  | { type: 'Kick'; memberId: string }
  | { type: 'TransferHost'; memberId: string }
  | { type: 'EndRoom' };

/** TrackEnded exists for protocol parity only: the web client never sends it. */
export type ClientMessage =
  | {
      type: 'Create';
      protocolVersion: number;
      name: string;
      initialQueue: NewEntry[];
      startIndex: number;
      startPositionMs: number;
    }
  | { type: 'Join'; protocolVersion: number; code: string; name: string; resumeToken: string | null }
  | { type: 'Leave' }
  | { type: 'Ping'; clientTime: number }
  | { type: 'Command'; reqId: string; cmd: Command }
  | { type: 'TrackEnded'; entryId: string };

export interface Welcome {
  type: 'Welcome';
  memberId: string;
  resumeToken: string;
  state: RoomState;
  serverTime: number;
  protocolVersion: number;
}

export type ServerMessage =
  | Welcome
  | { type: 'State'; state: RoomState }
  | { type: 'Pong'; clientTime: number; serverTime: number }
  | { type: 'Error'; reqId: string | null; code: ErrorCode }
  | { type: 'Kicked' }
  | { type: 'RoomClosed' };

// ---------------------------------------------------------------------------------------------
// Encoding: keys in Kotlin declaration order with every default written out (encodeDefaults).

function encodeNewEntry(e: NewEntry): NewEntry {
  return {
    videoId: e.videoId,
    title: e.title,
    artist: e.artist,
    thumbnailUrl: e.thumbnailUrl,
    durationMs: e.durationMs,
    kind: e.kind,
    url: e.url,
  };
}

function encodeCommand(cmd: Command): Command {
  switch (cmd.type) {
    case 'Seek':
      return { type: 'Seek', positionMs: cmd.positionMs };
    case 'PlayEntry':
      return { type: 'PlayEntry', entryId: cmd.entryId };
    case 'Add':
      return { type: 'Add', entries: cmd.entries.map(encodeNewEntry), playNext: cmd.playNext };
    case 'Remove':
      return { type: 'Remove', entryId: cmd.entryId };
    case 'Move':
      return { type: 'Move', entryId: cmd.entryId, afterEntryId: cmd.afterEntryId };
    case 'SetSettings':
      return { type: 'SetSettings', settings: { guestsCanControl: cmd.settings.guestsCanControl } };
    case 'Kick':
      return { type: 'Kick', memberId: cmd.memberId };
    case 'TransferHost':
      return { type: 'TransferHost', memberId: cmd.memberId };
    default:
      return { type: cmd.type };
  }
}

function orderClientMessage(msg: ClientMessage): ClientMessage {
  switch (msg.type) {
    case 'Create':
      return {
        type: 'Create',
        protocolVersion: msg.protocolVersion,
        name: msg.name,
        initialQueue: msg.initialQueue.map(encodeNewEntry),
        startIndex: msg.startIndex,
        startPositionMs: msg.startPositionMs,
      };
    case 'Join':
      return {
        type: 'Join',
        protocolVersion: msg.protocolVersion,
        code: msg.code,
        name: msg.name,
        resumeToken: msg.resumeToken,
      };
    case 'Leave':
      return { type: 'Leave' };
    case 'Ping':
      return { type: 'Ping', clientTime: msg.clientTime };
    case 'Command':
      return { type: 'Command', reqId: msg.reqId, cmd: encodeCommand(msg.cmd) };
    case 'TrackEnded':
      return { type: 'TrackEnded', entryId: msg.entryId };
  }
}

export function encodeClientMessage(msg: ClientMessage): string {
  return JSON.stringify(orderClientMessage(msg));
}

// ---------------------------------------------------------------------------------------------
// Decoding

type Json = Record<string, unknown>;

/** Thrown for a malformed known message; parseServerMessage turns it into null. */
class Malformed extends Error {}

function obj(v: unknown): Json {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new Malformed();
  return v as Json;
}

function str(o: Json, key: string): string {
  const v = o[key];
  if (typeof v !== 'string') throw new Malformed();
  return v;
}

function optStr(o: Json, key: string): string | null {
  const v = o[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') throw new Malformed();
  return v;
}

function num(o: Json, key: string, fallback?: number): number {
  const v = o[key];
  if (v === undefined && fallback !== undefined) return fallback;
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Malformed();
  return v;
}

function bool(o: Json, key: string, fallback?: boolean): boolean {
  const v = o[key];
  if (v === undefined && fallback !== undefined) return fallback;
  if (typeof v !== 'boolean') throw new Malformed();
  return v;
}

function list<T>(o: Json, key: string, item: (v: unknown) => T): T[] {
  const v = o[key];
  if (v === undefined) return [];
  if (!Array.isArray(v)) throw new Malformed();
  return v.map(item);
}

function lenientEnum<T extends string>(values: readonly T[], raw: unknown, fallback: T): T {
  if (typeof raw !== 'string') throw new Malformed();
  return (values as readonly string[]).includes(raw) ? (raw as T) : fallback;
}

function parseSettings(v: unknown): RoomSettings {
  if (v === undefined) return { guestsCanControl: true };
  const o = obj(v);
  return { guestsCanControl: bool(o, 'guestsCanControl', true) };
}

function parseMember(v: unknown): Member {
  const o = obj(v);
  return {
    id: str(o, 'id'),
    name: str(o, 'name'),
    isHost: bool(o, 'isHost'),
    connected: bool(o, 'connected'),
    joinedAt: num(o, 'joinedAt'),
  };
}

export function parseQueueEntry(v: unknown): QueueEntry {
  const o = obj(v);
  return {
    entryId: str(o, 'entryId'),
    videoId: str(o, 'videoId'),
    title: str(o, 'title'),
    artist: str(o, 'artist'),
    thumbnailUrl: optStr(o, 'thumbnailUrl'),
    durationMs: num(o, 'durationMs'),
    addedBy: str(o, 'addedBy'),
    kind: o.kind === undefined ? 'YOUTUBE_MUSIC' : lenientEnum(MEDIA_KINDS, o.kind, 'UNKNOWN'),
    url: optStr(o, 'url'),
  };
}

function parsePlayback(v: unknown): Playback {
  if (v === undefined) return { isPlaying: false, positionMs: 0, anchorServerTimeMs: 0 };
  const o = obj(v);
  return {
    isPlaying: bool(o, 'isPlaying', false),
    positionMs: num(o, 'positionMs', 0),
    anchorServerTimeMs: num(o, 'anchorServerTimeMs', 0),
  };
}

function parseRoomState(v: unknown): RoomState {
  const o = obj(v);
  return {
    code: str(o, 'code'),
    version: num(o, 'version'),
    hostId: str(o, 'hostId'),
    settings: parseSettings(o.settings),
    members: list(o, 'members', parseMember),
    queue: list(o, 'queue', parseQueueEntry),
    currentEntryId: optStr(o, 'currentEntryId'),
    playback: parsePlayback(o.playback),
  };
}

function decode(o: Json): ServerMessage | null {
  switch (o.type) {
    case 'Welcome':
      return {
        type: 'Welcome',
        memberId: str(o, 'memberId'),
        resumeToken: str(o, 'resumeToken'),
        state: parseRoomState(o.state),
        serverTime: num(o, 'serverTime'),
        protocolVersion: num(o, 'protocolVersion', PROTOCOL_VERSION),
      };
    case 'State':
      return { type: 'State', state: parseRoomState(o.state) };
    case 'Pong':
      return { type: 'Pong', clientTime: num(o, 'clientTime'), serverTime: num(o, 'serverTime') };
    case 'Error':
      return { type: 'Error', reqId: optStr(o, 'reqId'), code: lenientEnum(ERROR_CODES, o.code, 'UNKNOWN') };
    case 'Kicked':
      return { type: 'Kicked' };
    case 'RoomClosed':
      return { type: 'RoomClosed' };
    default:
      return null; // a message type from a newer server: skip it
  }
}

/** Null for unknown message types and malformed JSON, so the caller just skips the frame. */
export function parseServerMessage(text: string): ServerMessage | null {
  try {
    return decode(obj(JSON.parse(text)));
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// Room helpers (Models.kt)

export function currentEntry(state: RoomState): QueueEntry | null {
  if (state.currentEntryId === null) return null;
  return state.queue.find((e) => e.entryId === state.currentEntryId) ?? null;
}

/** True when the length is unknown (live stream). */
export function isLive(entry: { durationMs: number }): boolean {
  return entry.durationMs <= 0;
}

/** Playback position at serverNow, clamped to the current entry (only at 0 for live entries). */
export function positionAt(state: RoomState, serverNow: number): number {
  const entry = currentEntry(state);
  if (!entry) return 0;
  const { isPlaying, positionMs, anchorServerTimeMs } = state.playback;
  const raw = isPlaying ? positionMs + (serverNow - anchorServerTimeMs) : positionMs;
  if (isLive(entry)) return Math.max(0, raw);
  return Math.min(Math.max(0, raw), entry.durationMs);
}
