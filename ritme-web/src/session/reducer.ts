import type { ConnectionEvent, ConnectionStatus, EndReason } from '../net/connection';
import type { ErrorCode, RoomState } from '../protocol';

export type NoticeKey = 'forbidden' | 'addForbidden' | 'rateLimited' | 'queueFull' | 'invalid' | 'failed' | 'linkCopied';

export interface Notice {
  id: number;
  key: NoticeKey;
}

export interface SessionState {
  phase: 'idle' | 'connecting' | 'in-room' | 'ended';
  connection: ConnectionStatus;
  memberId: string | null;
  room: RoomState | null;
  serverProtocolVersion: number | null;
  ended: EndReason | null;
  /** Entries this browser cannot play (embed blocked, media error); local only, never sent. */
  unplayable: Readonly<Record<string, true>>;
  notice: Notice | null;
}

export type SessionAction =
  | ConnectionEvent
  | { type: 'start' }
  | { type: 'reset' }
  | { type: 'mark-unplayable'; entryId: string }
  | { type: 'notice'; key: NoticeKey };

export const initialSession: SessionState = {
  phase: 'idle',
  connection: 'connecting',
  memberId: null,
  room: null,
  serverProtocolVersion: null,
  ended: null,
  unplayable: {},
  notice: null,
};

export function noticeFor(code: ErrorCode): NoticeKey {
  switch (code) {
    case 'FORBIDDEN':
      return 'forbidden';
    case 'RATE_LIMITED':
      return 'rateLimited';
    case 'QUEUE_FULL':
      return 'queueFull';
    case 'INVALID':
      return 'invalid';
    default:
      return 'failed';
  }
}

function withNotice(state: SessionState, key: NoticeKey): SessionState {
  return { ...state, notice: { id: (state.notice?.id ?? 0) + 1, key } };
}

/**
 * Pure session state machine. The server's snapshot is the only truth: no optimistic updates, and
 * a State whose version is not newer than the applied one is ignored (spec 2.2).
 */
export function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case 'start':
      return { ...initialSession, phase: 'connecting', notice: state.notice };
    case 'reset':
      return { ...initialSession, notice: state.notice };
    case 'status':
      return { ...state, connection: action.status };
    case 'welcome': {
      const sameRoom = state.room?.code === action.welcome.state.code;
      return {
        ...state,
        phase: 'in-room',
        memberId: action.welcome.memberId,
        room: action.welcome.state,
        serverProtocolVersion: action.welcome.protocolVersion,
        unplayable: sameRoom ? state.unplayable : {},
      };
    }
    case 'state':
      if (!state.room || action.state.code !== state.room.code) return state;
      if (action.state.version <= state.room.version) return state;
      return { ...state, room: action.state };
    case 'pong':
      return state;
    case 'error':
      return withNotice(state, noticeFor(action.code));
    case 'ended':
      return { ...state, phase: 'ended', ended: action.reason, room: null, memberId: null };
    case 'mark-unplayable':
      if (state.unplayable[action.entryId]) return state;
      return { ...state, unplayable: { ...state.unplayable, [action.entryId]: true } };
    case 'notice':
      return withNotice(state, action.key);
  }
}
