import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { room } from '../testing/builders';
import { type ConnectionEvent, type JoinIntent, RitmeConnection, ritmeSocketUrl } from './connection';
import { FakeSocket } from '../testing/fake-socket';
import { type KeyValueStore, RESUME_KEY, memoryStore } from './storage';

function welcome(code = 'K7M2QX', token = 'tok-1') {
  return { type: 'Welcome', memberId: 'm2', resumeToken: token, serverTime: 5_000, protocolVersion: 1, state: room({ code }) };
}

function setup(storage: KeyValueStore = memoryStore()) {
  const sockets: FakeSocket[] = [];
  const events: ConnectionEvent[] = [];
  const conn = new RitmeConnection({
    url: 'ws://test/ritme',
    socketFactory: (url) => {
      const s = new FakeSocket(url);
      sockets.push(s);
      return s;
    },
    storage,
    now: () => Date.now(),
    onEvent: (e) => events.push(e),
  });
  const last = () => sockets[sockets.length - 1];
  const ended = () => events.filter((e) => e.type === 'ended');
  return { conn, sockets, events, last, ended, storage };
}

function joinIn(t: ReturnType<typeof setup>, intent: JoinIntent = { kind: 'join', code: 'K7M2QX', name: 'Sari' }) {
  t.conn.start(intent);
  t.last().open();
  t.last().receive(welcome());
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
});
afterEach(() => {
  vi.useRealTimers();
});

describe('RitmeConnection: joining', () => {
  it('sends Create with an empty queue, then stores the resume token on Welcome', () => {
    const t = setup();
    t.conn.start({ kind: 'create', name: 'Budi' });
    t.last().open();
    expect(t.last().sent[0]).toEqual({
      type: 'Create',
      protocolVersion: 1,
      name: 'Budi',
      initialQueue: [],
      startIndex: 0,
      startPositionMs: 0,
    });
    t.last().receive(welcome('ABCDEF', 'tok-9'));
    expect(JSON.parse(t.storage.get(RESUME_KEY)!)).toEqual({ code: 'ABCDEF', token: 'tok-9' });
    expect(t.events.map((e) => e.type)).toEqual(['status', 'welcome', 'status']);
    expect(t.events[2]).toEqual({ type: 'status', status: 'online' });
  });

  it('sends Join with the stored token only for the same room', () => {
    const storage = memoryStore();
    storage.set(RESUME_KEY, JSON.stringify({ code: 'K7M2QX', token: 'old' }));
    const same = setup(storage);
    same.conn.start({ kind: 'join', code: 'K7M2QX', name: 'Sari' });
    same.last().open();
    expect(same.last().sent[0]).toMatchObject({ type: 'Join', code: 'K7M2QX', name: 'Sari', resumeToken: 'old' });

    const other = setup(storage);
    other.conn.start({ kind: 'join', code: 'ZZZZZZ', name: 'Sari' });
    other.last().open();
    expect(other.last().sent[0]).toMatchObject({ type: 'Join', code: 'ZZZZZZ', resumeToken: null });
  });

  it('a rejected first join ends the session with the code and clears the token', () => {
    const storage = memoryStore();
    storage.set(RESUME_KEY, JSON.stringify({ code: 'K7M2QX', token: 'old' }));
    const t = setup(storage);
    t.conn.start({ kind: 'join', code: 'K7M2QX', name: 'Sari' });
    t.last().open();
    t.last().receive({ type: 'Error', reqId: null, code: 'ROOM_NOT_FOUND' });
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'rejected', code: 'ROOM_NOT_FOUND' } }]);
    expect(storage.get(RESUME_KEY)).toBeNull();
    expect(t.last().closed).toBe(true);
  });

  it('a server that cannot be reached ends as unreachable (close or connect timeout)', () => {
    const closed = setup();
    closed.conn.start({ kind: 'create', name: 'Budi' });
    closed.last().serverClose();
    expect(closed.ended()).toEqual([{ type: 'ended', reason: { kind: 'unreachable' } }]);

    const silent = setup();
    silent.conn.start({ kind: 'create', name: 'Budi' });
    vi.advanceTimersByTime(8_000);
    expect(silent.ended()).toEqual([{ type: 'ended', reason: { kind: 'unreachable' } }]);
    expect(silent.last().closed).toBe(true);
  });

  it('skips unknown and malformed frames', () => {
    const t = setup();
    joinIn(t);
    const before = t.events.length;
    t.last().receive({ type: 'Reaction', emoji: 'x' });
    t.last().receive('not json');
    expect(t.events.length).toBe(before);
  });
});

describe('RitmeConnection: in the room', () => {
  it('pings 5 times 200 ms apart, then every 30 s', () => {
    const t = setup();
    joinIn(t);
    vi.advanceTimersByTime(1_000);
    expect(t.last().types().filter((x) => x === 'Ping')).toHaveLength(5);
    expect(t.last().sent.filter((m) => m.type === 'Ping').map((m) => m.clientTime)).toEqual([0, 200, 400, 600, 800]);
    vi.advanceTimersByTime(29_000);
    expect(t.last().types().filter((x) => x === 'Ping')).toHaveLength(6);
  });

  it('reports pongs with the receive time', () => {
    const t = setup();
    joinIn(t);
    vi.advanceTimersByTime(40);
    t.last().receive({ type: 'Pong', clientTime: 0, serverTime: 9_000 });
    expect(t.events.at(-1)).toEqual({ type: 'pong', clientTime: 0, serverTime: 9_000, receivedAt: 40 });
  });

  it('sends commands with increasing reqIds and reports errors for them', () => {
    const t = setup();
    joinIn(t);
    expect(t.conn.command({ type: 'Play' })).toBe('r1');
    expect(t.conn.command({ type: 'Seek', positionMs: 1_000 })).toBe('r2');
    expect(t.last().sent.filter((m) => m.type === 'Command')).toEqual([
      { type: 'Command', reqId: 'r1', cmd: { type: 'Play' } },
      { type: 'Command', reqId: 'r2', cmd: { type: 'Seek', positionMs: 1_000 } },
    ]);
    t.last().receive({ type: 'Error', reqId: 'r2', code: 'QUEUE_FULL' });
    expect(t.events.at(-1)).toEqual({ type: 'error', reqId: 'r2', code: 'QUEUE_FULL' });
  });

  it('forwards State snapshots', () => {
    const t = setup();
    joinIn(t);
    t.last().receive({ type: 'State', state: room({ version: 7 }) });
    expect(t.events.at(-1)).toMatchObject({ type: 'state', state: { version: 7 } });
  });

  it('drops commands while not joined', () => {
    const t = setup();
    t.conn.start({ kind: 'create', name: 'Budi' });
    expect(t.conn.command({ type: 'Play' })).toBeNull();
  });

  it('Kicked and RoomClosed end the session and clear the token', () => {
    const kicked = setup();
    joinIn(kicked);
    kicked.last().receive({ type: 'Kicked' });
    expect(kicked.ended()).toEqual([{ type: 'ended', reason: { kind: 'kicked' } }]);
    expect(kicked.storage.get(RESUME_KEY)).toBeNull();

    const closed = setup();
    joinIn(closed);
    closed.last().receive({ type: 'RoomClosed' });
    expect(closed.ended()).toEqual([{ type: 'ended', reason: { kind: 'closed' } }]);
  });

  it('leave sends Leave, closes the socket and ends as left', () => {
    const t = setup();
    joinIn(t);
    t.conn.leave();
    expect(t.last().types().at(-1)).toBe('Leave');
    expect(t.last().closed).toBe(true);
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'left' } }]);
    expect(t.storage.get(RESUME_KEY)).toBeNull();
  });
});

describe('RitmeConnection: reconnecting', () => {
  it('reconnects after 1 s, then 2 s, and resumes with the token', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose();
    expect(t.events.at(-1)).toEqual({ type: 'status', status: 'reconnecting' });
    vi.advanceTimersByTime(999);
    expect(t.sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(t.sockets).toHaveLength(2);
    t.last().serverClose(); // attempt 1 fails
    vi.advanceTimersByTime(1_999);
    expect(t.sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(t.sockets).toHaveLength(3);
    t.last().open();
    expect(t.last().sent[0]).toMatchObject({ type: 'Join', code: 'K7M2QX', name: 'Sari', resumeToken: 'tok-1' });
    t.last().receive(welcome('K7M2QX', 'tok-1'));
    expect(t.events.at(-1)).toEqual({ type: 'status', status: 'online' });
  });

  it('a Create session reconnects with Join to the created room', () => {
    const t = setup();
    joinIn(t, { kind: 'create', name: 'Budi' });
    t.last().serverClose();
    vi.advanceTimersByTime(1_000);
    t.last().open();
    expect(t.last().sent[0]).toMatchObject({ type: 'Join', code: 'K7M2QX', name: 'Budi', resumeToken: 'tok-1' });
  });

  it('gives up 30 s after the drop', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose(); // t=0, retries at 1 s, 3 s, 7 s, 15 s, final at 30 s
    for (const wait of [1_000, 2_000, 4_000, 8_000]) {
      vi.advanceTimersByTime(wait);
      t.last().serverClose();
    }
    expect(t.sockets).toHaveLength(5);
    expect(t.ended()).toEqual([]);
    vi.advanceTimersByTime(14_999);
    expect(t.sockets).toHaveLength(5);
    vi.advanceTimersByTime(1);
    expect(t.sockets).toHaveLength(6);
    expect(t.ended()).toEqual([]);
    t.last().serverClose(); // the final attempt at 30 s fails
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'lost' } }]);
    vi.advanceTimersByTime(60_000);
    expect(t.sockets).toHaveLength(6);
  });

  it('keeps retrying when a reconnect is RATE_LIMITED, but a resume into a gone room ends as lost', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose();
    vi.advanceTimersByTime(1_000);
    t.last().open();
    t.last().receive({ type: 'Error', reqId: null, code: 'RATE_LIMITED' });
    t.last().serverClose();
    expect(t.ended()).toEqual([]);
    vi.advanceTimersByTime(2_000);
    t.last().open();
    t.last().receive({ type: 'Error', reqId: null, code: 'ROOM_NOT_FOUND' });
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'lost' } }]);
  });
});

describe('RitmeConnection: deadlines and edge cases', () => {
  it('an opened socket with no Welcome within 10 s fails the attempt (initial connect)', () => {
    const t = setup();
    t.conn.start({ kind: 'create', name: 'Budi' });
    t.last().open();
    vi.advanceTimersByTime(9_999);
    expect(t.ended()).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'unreachable' } }]);
    expect(t.last().closed).toBe(true);
  });

  it('an opened socket with no Welcome feeds the reconnect backoff and give-up', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose(); // t=0
    vi.advanceTimersByTime(1_000);
    t.last().open(); // stalls: no Welcome
    expect(t.sockets).toHaveLength(2);
    vi.advanceTimersByTime(10_000); // dropped at 11 s, retry in 2 s
    expect(t.last().closed).toBe(true);
    vi.advanceTimersByTime(1_999);
    expect(t.sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(t.sockets).toHaveLength(3);
    t.last().open(); // stalls again, dropped at 23 s, retry in 4 s clamped to 7 s left -> 4 s
    vi.advanceTimersByTime(10_000 + 4_000);
    expect(t.sockets).toHaveLength(4);
    t.last().serverClose(); // t=27 s: next delay 8 s clamped to 3 s
    vi.advanceTimersByTime(3_000);
    expect(t.sockets).toHaveLength(5);
    t.last().serverClose(); // t=30 s
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'lost' } }]);
  });

  it('keeps the resume token in memory when storage throws', () => {
    const broken: KeyValueStore = {
      get: () => {
        throw new Error('blocked');
      },
      set: () => {
        throw new Error('blocked');
      },
      remove: () => {
        throw new Error('blocked');
      },
    };
    const t = setup({
      get: (k) => {
        try {
          return broken.get(k);
        } catch {
          return null;
        }
      },
      set: () => {},
      remove: () => {},
    });
    joinIn(t);
    t.last().serverClose();
    vi.advanceTimersByTime(1_000);
    t.last().open();
    expect(t.last().sent[0]).toMatchObject({ type: 'Join', resumeToken: 'tok-1' });
  });

  it('leave while a reconnect is pending opens no new socket', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose();
    t.conn.leave();
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'left' } }]);
    vi.advanceTimersByTime(60_000);
    expect(t.sockets).toHaveLength(1);
  });

  it('ignores close and messages from a stale socket', () => {
    const t = setup();
    joinIn(t);
    const old = t.last();
    old.serverClose();
    vi.advanceTimersByTime(1_000);
    const fresh = t.last();
    fresh.open();
    fresh.receive(welcome());
    const before = t.events.length;
    old.onclose?.({});
    old.onmessage?.({ data: JSON.stringify({ type: 'Kicked' }) });
    expect(t.events.length).toBe(before);
    expect(t.ended()).toEqual([]);
    expect(t.sockets).toHaveLength(2);
  });

  it('does not double the ping schedule across a reconnect', () => {
    const t = setup();
    joinIn(t);
    t.last().serverClose();
    vi.advanceTimersByTime(1_000);
    t.last().open();
    t.last().receive(welcome());
    vi.advanceTimersByTime(1_000);
    expect(t.last().types().filter((x) => x === 'Ping')).toHaveLength(5);
    vi.advanceTimersByTime(29_000);
    expect(t.last().types().filter((x) => x === 'Ping')).toHaveLength(6);
    expect(t.sockets[0].types().filter((x) => x === 'Ping')).toHaveLength(0);
  });

  it('UPGRADE_REQUIRED before Welcome ends without retry', () => {
    const t = setup();
    t.conn.start({ kind: 'create', name: 'Budi' });
    t.last().open();
    t.last().receive({ type: 'Error', reqId: null, code: 'UPGRADE_REQUIRED' });
    expect(t.ended()).toEqual([{ type: 'ended', reason: { kind: 'rejected', code: 'UPGRADE_REQUIRED' } }]);
    vi.advanceTimersByTime(60_000);
    expect(t.sockets).toHaveLength(1);
  });
});

describe('ritmeSocketUrl', () => {
  it('uses wss on https and the same host', () => {
    expect(ritmeSocketUrl({ protocol: 'https:', host: 'ritme.example' })).toBe('wss://ritme.example/ritme');
    expect(ritmeSocketUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ritme');
  });
});
