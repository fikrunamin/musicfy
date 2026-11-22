import { describe, expect, it } from 'vitest';
import type { ConnectionEvent, JoinIntent } from '../net/connection';
import type { Command } from '../protocol';
import { Session } from './session';
import { room } from '../testing/builders';

class FakeConnection {
  intent: JoinIntent | null = null;
  commands: Command[] = [];
  left = false;
  bursts = 0;
  constructor(readonly emit: (e: ConnectionEvent) => void) {}
  start(intent: JoinIntent) {
    this.intent = intent;
  }
  command(cmd: Command) {
    this.commands.push(cmd);
    return `r${this.commands.length}`;
  }
  pingBurst() {
    this.bursts++;
  }
  leave() {
    this.left = true;
    this.emit({ type: 'ended', reason: { kind: 'left' } });
  }
}

function setup() {
  const conns: FakeConnection[] = [];
  let now = 1_000;
  let wall = 1_700_000_000_000;
  const session = new Session({
    wallNow: () => wall,
    connect: (onEvent) => {
      const c = new FakeConnection(onEvent);
      conns.push(c);
      return c;
    },
    now: () => now,
  });
  const welcome = (c: FakeConnection) => {
    c.emit({
      type: 'welcome',
      welcome: { type: 'Welcome', memberId: 'm1', resumeToken: 't', serverTime: 50_000, protocolVersion: 1, state: room() },
      receivedAt: now,
    });
    c.emit({ type: 'status', status: 'online' });
  };
  const pong = (c: FakeConnection, clientTime: number, serverTime: number) =>
    c.emit({ type: 'pong', clientTime, serverTime, receivedAt: now });
  return {
    session,
    conns,
    welcome,
    pong,
    setNow: (v: number) => (now = v),
    advance: (perfMs: number, wallMs: number = perfMs) => {
      now += perfMs;
      wall += wallMs;
    },
  };
}

describe('Session', () => {
  it('create starts a connection with the intent and enters the room on Welcome', () => {
    const t = setup();
    let notified = 0;
    t.session.subscribe(() => notified++);
    t.session.create('Budi');
    expect(t.conns[0].intent).toEqual({ kind: 'create', name: 'Budi' });
    expect(t.session.getState().phase).toBe('connecting');
    t.welcome(t.conns[0]);
    expect(t.session.getState().phase).toBe('in-room');
    expect(notified).toBeGreaterThanOrEqual(2);
  });

  it('ignores a second create/join while connecting or in a room', () => {
    const t = setup();
    t.session.join('K7M2QX', 'Sari');
    t.session.join('ZZZZZZ', 'Sari');
    expect(t.conns).toHaveLength(1);
  });

  it('sends commands only while online in a room', () => {
    const t = setup();
    t.session.create('Budi');
    t.session.play();
    expect(t.conns[0].commands).toEqual([]);
    t.welcome(t.conns[0]);
    t.session.play();
    t.session.seek(1234.6);
    t.session.add(
      { videoId: 'v', title: 'T', artist: 'A', thumbnailUrl: null, durationMs: 1, kind: 'YOUTUBE_MUSIC', url: null },
      true,
    );
    t.session.setGuestsCanControl(false);
    t.conns[0].emit({ type: 'status', status: 'reconnecting' });
    t.session.pause();
    expect(t.conns[0].commands.map((c) => c.type)).toEqual(['Play', 'Seek', 'Add', 'SetSettings']);
    expect(t.conns[0].commands[1]).toEqual({ type: 'Seek', positionMs: 1235 });
  });

  it('feeds the clock from Welcome and Pong', () => {
    const t = setup();
    t.session.create('Budi');
    t.welcome(t.conns[0]); // seed: 50_000 - 1_000
    expect(t.session.serverNow()).toBe(50_000);
    t.conns[0].emit({ type: 'pong', clientTime: 1_000, serverTime: 60_010, receivedAt: 1_020 });
    t.setNow(2_000);
    expect(t.session.serverNow()).toBe(61_000);
  });

  it('leave ends the session; dismiss goes back to idle; a new create opens a new connection', () => {
    const t = setup();
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.session.leave();
    expect(t.conns[0].left).toBe(true);
    expect(t.session.getState().phase).toBe('ended');
    t.session.dismiss();
    expect(t.session.getState().phase).toBe('idle');
    t.session.create('Budi');
    expect(t.conns).toHaveLength(2);
  });

  it('ignores events from an old connection', () => {
    const t = setup();
    t.session.create('Budi');
    const old = t.conns[0];
    old.emit({ type: 'ended', reason: { kind: 'unreachable' } });
    t.session.dismiss();
    t.session.create('Budi');
    old.emit({ type: 'ended', reason: { kind: 'lost' } });
    expect(t.session.getState().phase).toBe('connecting');
  });

  it('starts a fresh clock window on every Welcome, so old low-rtt samples cannot win', () => {
    const t = setup();
    t.session.create('Budi');
    t.welcome(t.conns[0]); // t=1_000
    t.pong(t.conns[0], 1_000, 50_000); // rtt 0, offset 49_000
    expect(t.session.serverNow()).toBe(50_000);
    // Reconnect after the device slept: performance.now() barely moved, the server clock did.
    t.conns[0].emit({ type: 'status', status: 'reconnecting' });
    t.setNow(1_500);
    t.conns[0].emit({
      type: 'welcome',
      welcome: { type: 'Welcome', memberId: 'm1', resumeToken: 't', serverTime: 900_000, protocolVersion: 1, state: room({ version: 2 }) },
      receivedAt: 1_500,
    });
    expect(t.session.serverNow()).toBe(900_000); // the new seed, not the old sample
    t.setNow(1_600);
    t.pong(t.conns[0], 1_500, 900_150); // rtt 100, offset 898_600
    expect(t.session.serverNow()).toBe(900_200);
  });

  it('asks for a forced resync when the first Pong after a Welcome lands', () => {
    const t = setup();
    let resyncs = 0;
    t.session.onResync(() => resyncs++);
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    expect(resyncs).toBe(0);
    t.pong(t.conns[0], 1_000, 50_000);
    expect(resyncs).toBe(1);
    t.pong(t.conns[0], 1_000, 50_000);
    expect(resyncs).toBe(1);
  });

  it('becoming visible without a sleep resyncs at once and keeps the clock', () => {
    const t = setup();
    let resyncs = 0;
    t.session.onResync(() => resyncs++);
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.pong(t.conns[0], 1_000, 50_000);
    resyncs = 0;
    t.session.visibilityChanged(false);
    t.advance(60_000, 60_400); // hidden for a minute, clocks agree within 1 s
    t.session.visibilityChanged(true);
    expect(resyncs).toBe(1);
    expect(t.conns[0].bursts).toBe(0);
    expect(t.session.serverNow()).toBe(110_000);
  });

  it('after a sleep, resets the clock, sends a ping burst, and resyncs only once a new Pong lands', () => {
    const t = setup();
    let resyncs = 0;
    const off = t.session.onResync(() => resyncs++);
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.pong(t.conns[0], 1_000, 50_000); // offset 49_000
    resyncs = 0;
    t.session.visibilityChanged(false); // perf 1_000
    t.advance(2_000, 302_000); // asleep for 300 s: performance.now() moved only 2 s
    t.session.visibilityChanged(true); // perf 3_000
    expect(t.conns[0].bursts).toBe(1);
    expect(resyncs).toBe(0);
    // Until the burst answers, the old offset moved by the missed 300 s is used.
    expect(t.session.serverNow()).toBe(3_000 + 49_000 + 300_000);
    t.pong(t.conns[0], 2_000, 1); // a Ping from before the sleep: ignored
    expect(resyncs).toBe(0);
    t.advance(40);
    t.pong(t.conns[0], 3_000, 352_100); // rtt 40, offset 349_080
    expect(resyncs).toBe(1);
    expect(t.session.serverNow()).toBe(3_040 + 349_080);
    off();
    t.session.visibilityChanged(false);
    t.session.visibilityChanged(true);
    expect(resyncs).toBe(1);
  });

  it('a visible event with no hidden one before it just resyncs', () => {
    const t = setup();
    let resyncs = 0;
    t.session.onResync(() => resyncs++);
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.session.visibilityChanged(true);
    expect(resyncs).toBe(1);
    expect(t.conns[0].bursts).toBe(0);
  });
});
