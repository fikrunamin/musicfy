import { describe, expect, it } from 'vitest';
import type { ConnectionEvent, JoinIntent } from '../net/connection';
import type { Command } from '../protocol';
import { Session } from './session';
import { room } from '../testing/builders';

class FakeConnection {
  intent: JoinIntent | null = null;
  commands: Command[] = [];
  left = false;
  constructor(readonly emit: (e: ConnectionEvent) => void) {}
  start(intent: JoinIntent) {
    this.intent = intent;
  }
  command(cmd: Command) {
    this.commands.push(cmd);
    return `r${this.commands.length}`;
  }
  leave() {
    this.left = true;
    this.emit({ type: 'ended', reason: { kind: 'left' } });
  }
}

function setup() {
  const conns: FakeConnection[] = [];
  let now = 1_000;
  const session = new Session({
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
  return { session, conns, welcome, setNow: (v: number) => (now = v) };
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
});
