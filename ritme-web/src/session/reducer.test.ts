import { describe, expect, it } from 'vitest';
import type { Welcome } from '../protocol';
import { type SessionState, initialSession, sessionReducer } from './reducer';
import { room } from '../testing/builders';

const welcome = (version = 3, code = 'K7M2QX'): Welcome => ({
  type: 'Welcome',
  memberId: 'm2',
  resumeToken: 't',
  serverTime: 1,
  protocolVersion: 1,
  state: room({ version, code }),
});

function inRoom(version = 3): SessionState {
  return sessionReducer(sessionReducer(initialSession, { type: 'start' }), {
    type: 'welcome',
    welcome: welcome(version),
    receivedAt: 0,
  });
}

describe('sessionReducer', () => {
  it('start → connecting, Welcome → in the room with member and state', () => {
    const connecting = sessionReducer(initialSession, { type: 'start' });
    expect(connecting.phase).toBe('connecting');
    const s = inRoom();
    expect(s.phase).toBe('in-room');
    expect(s.memberId).toBe('m2');
    expect(s.room?.version).toBe(3);
    expect(s.serverProtocolVersion).toBe(1);
  });

  it('applies newer State and ignores same or older versions', () => {
    const s = inRoom(3);
    const newer = sessionReducer(s, { type: 'state', state: room({ version: 4 }) });
    expect(newer.room?.version).toBe(4);
    expect(sessionReducer(newer, { type: 'state', state: room({ version: 4 }) })).toBe(newer);
    expect(sessionReducer(newer, { type: 'state', state: room({ version: 2 }) })).toBe(newer);
  });

  it('ignores State for another room or before Welcome', () => {
    expect(sessionReducer(inRoom(), { type: 'state', state: room({ version: 9, code: 'ZZZZZZ' }) }).room?.code).toBe('K7M2QX');
    expect(sessionReducer(initialSession, { type: 'state', state: room({ version: 9 }) }).room).toBeNull();
  });

  it('a Welcome after a resume replaces the room even with an equal version', () => {
    const s = sessionReducer(inRoom(3), { type: 'welcome', welcome: welcome(3), receivedAt: 0 });
    expect(s.room?.version).toBe(3);
  });

  it('turns command errors into notices with fresh ids, QUEUE_FULL included', () => {
    const a = sessionReducer(inRoom(), { type: 'error', reqId: 'r1', code: 'QUEUE_FULL' });
    expect(a.notice).toEqual({ id: 1, key: 'queueFull' });
    const b = sessionReducer(a, { type: 'error', reqId: 'r2', code: 'FORBIDDEN' });
    expect(b.notice).toEqual({ id: 2, key: 'forbidden' });
    expect(sessionReducer(b, { type: 'error', reqId: null, code: 'UNKNOWN' }).notice?.key).toBe('failed');
    expect(b.room).toBe(a.room);
  });

  it('tracks connection status and the end reason', () => {
    const r = sessionReducer(inRoom(), { type: 'status', status: 'reconnecting' });
    expect(r.connection).toBe('reconnecting');
    const ended = sessionReducer(r, { type: 'ended', reason: { kind: 'kicked' } });
    expect(ended.phase).toBe('ended');
    expect(ended.ended).toEqual({ kind: 'kicked' });
    expect(ended.room).toBeNull();
  });

  it('marks entries unplayable locally, and forgets them in a different room', () => {
    const s = sessionReducer(inRoom(), { type: 'mark-unplayable', entryId: 'e1' });
    expect(s.unplayable).toEqual({ e1: true });
    expect(sessionReducer(s, { type: 'mark-unplayable', entryId: 'e1' })).toBe(s);
    const other = sessionReducer(s, { type: 'welcome', welcome: welcome(1, 'ZZZZZZ'), receivedAt: 0 });
    expect(other.unplayable).toEqual({});
  });
});
