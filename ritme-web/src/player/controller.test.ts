import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RoomState } from '../protocol';
import { entry, room } from '../testing/builders';
import { PlaybackController } from './controller';
import { FakePlayer } from '../testing/fake-player';

let serverNow = 0;

function setup() {
  const players: FakePlayer[] = [];
  const unplayable: string[] = [];
  const needsTap: boolean[] = [];
  const controller = new PlaybackController({
    createPlayer: (kind, onEvent) => {
      const p = new FakePlayer(kind, onEvent);
      players.push(p);
      return p;
    },
    serverNow: () => serverNow,
    pageIsHttps: true,
    onUnplayable: (id) => unplayable.push(id),
    onNeedsTap: (v) => needsTap.push(v),
  });
  return { controller, players, unplayable, needsTap, player: () => players[players.length - 1] };
}

function playing(positionMs: number, anchorServerTimeMs: number, extra: Partial<RoomState> = {}): RoomState {
  return room({
    queue: [entry('e1'), entry('e2'), entry('live', { durationMs: 0, kind: 'YOUTUBE_VIDEO' })],
    currentEntryId: 'e1',
    playback: { isPlaying: true, positionMs, anchorServerTimeMs },
    ...extra,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  serverNow = 100_000;
});
afterEach(() => vi.useRealTimers());

describe('PlaybackController', () => {
  it('loads the current entry at the room position and plays once the anchor has passed', () => {
    const t = setup();
    t.controller.update(playing(5_000, 90_000), {}); // position 15_000
    expect(t.players).toHaveLength(1);
    expect(t.player().kind).toBe('youtube');
    expect(t.player().loaded).toEqual({ source: { kind: 'youtube', videoId: 'vid-e1' }, startMs: 15_000 });
    expect(t.player().calls).toContain('play');
  });

  it('waits paused for a future anchor, then starts exactly at it', () => {
    const t = setup();
    t.controller.update(playing(0, 101_500), {});
    expect(t.player().calls).toEqual(['load:0', 'pause']);
    serverNow = 101_499;
    vi.advanceTimersByTime(1_499);
    expect(t.player().calls).not.toContain('play');
    serverNow = 101_500;
    vi.advanceTimersByTime(1);
    expect(t.player().calls).toContain('play');
  });

  it('pauses when the room pauses and moves to the paused position', () => {
    const t = setup();
    t.controller.update(playing(5_000, 100_000), {});
    t.player().position = 9_000;
    t.controller.update(room({ ...playing(0, 0), version: 2, playback: { isPlaying: false, positionMs: 7_000, anchorServerTimeMs: 0 } }), {});
    expect(t.player().calls.slice(-2)).toEqual(['pause', 'seek:7000']);
  });

  it('corrects drift over 300 ms by seeking, at most every 5 s', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    t.player().position = 0;
    serverNow = 101_000;
    t.player().position = 500; // expected 1_000, off by 500
    vi.advanceTimersByTime(1_000);
    expect(t.player().calls.at(-1)).toBe('seek:1000');
    serverNow = 103_000;
    t.player().position = 2_000; // off by 1_000, but only 2 s since the last seek
    vi.advanceTimersByTime(1_000);
    expect(t.player().calls.filter((c) => c.startsWith('seek'))).toEqual(['seek:1000']);
    serverNow = 106_000;
    t.player().position = 5_000; // off by 1_000, 5 s later
    vi.advanceTimersByTime(1_000);
    expect(t.player().calls.at(-1)).toBe('seek:6000');
  });

  it('re-syncs as soon as the player plays again after an ad, without waiting for a tick', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    serverNow = 101_000;
    t.player().position = 500;
    vi.advanceTimersByTime(1_000); // seek:1000 at 101_000
    serverNow = 121_000; // a 20 s ad; the content stayed at 1_000
    t.player().position = 1_000;
    t.player().emit({ type: 'playing' });
    expect(t.player().calls.at(-1)).toBe('seek:21000');
  });

  it('does not loop seeks when playing resumes within 5 s of the last seek (slow buffering)', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    serverNow = 101_000;
    t.player().position = 500;
    vi.advanceTimersByTime(1_000); // seek:1000 at 101_000
    serverNow = 103_000;
    t.player().position = 1_000; // buffered for 2 s after the seek
    t.player().emit({ type: 'playing' });
    expect(t.player().calls.filter((c) => c.startsWith('seek'))).toEqual(['seek:1000']);
  });

  it('snapshots that do not change playback (e.g. a member joined) do not bypass the 5 s rule', () => {
    const t = setup();
    const state = playing(0, 100_000);
    t.controller.update(state, {});
    serverNow = 101_000;
    t.player().position = 500;
    vi.advanceTimersByTime(1_000); // seek:1000 at 101_000
    serverNow = 102_000;
    t.player().position = 1_200; // off by 800, 1 s after the seek
    t.controller.update({ ...state, version: 2 }, {});
    expect(t.player().calls.filter((c) => c.startsWith('seek'))).toEqual(['seek:1000']);
    t.controller.update({ ...state, version: 3, playback: { ...state.playback, positionMs: 30_000, anchorServerTimeMs: 102_000 } }, {});
    expect(t.player().calls.at(-1)).toBe('seek:30000'); // a real Seek in the room applies at once
  });

  it('resync() re-applies at once (tab visible again)', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    serverNow = 160_000;
    t.player().position = 3_000;
    t.controller.resync();
    expect(t.player().calls.at(-1)).toBe('seek:60000');
  });

  it('reports unplayable entries and stops playing them', () => {
    const t = setup();
    const state = playing(0, 100_000);
    t.controller.update(state, {});
    t.player().emit({ type: 'unplayable' });
    expect(t.unplayable).toEqual(['e1']);
    const calls = t.player().calls.length;
    t.controller.update(state, { e1: true });
    expect(t.player().calls.slice(calls)).toEqual(['pause']);
  });

  it('does not create a player for UNKNOWN kinds', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000, { queue: [entry('e1', { kind: 'UNKNOWN' })] }), {});
    expect(t.players).toHaveLength(0);
  });

  it('follows play/pause for live entries but never seeks them', () => {
    const t = setup();
    t.controller.update(playing(0, 0, { currentEntryId: 'live' }), {});
    expect(t.player().loaded?.startMs).toBeNull();
    t.player().position = 1;
    vi.advanceTimersByTime(10_000);
    expect(t.player().calls.some((c) => c.startsWith('seek'))).toBe(false);
    expect(t.player().calls).toContain('play');
  });

  it('switches player kind when the entry needs a different player', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    const yt = t.player();
    const url = entry('u', { kind: 'URL', videoId: '', url: 'https://cdn.example/v.mp4' });
    t.controller.update(playing(0, 100_000, { version: 2, queue: [url], currentEntryId: 'u' }), {});
    expect(yt.destroyed).toBe(true);
    expect(t.player().kind).toBe('video');
  });

  it('asks for a tap when playback has not started 4 s after play()', () => {
    const t = setup();
    t.controller.update(playing(0, 100_000), {});
    vi.advanceTimersByTime(4_000);
    expect(t.needsTap).toEqual([true]);
    t.player().emit({ type: 'playing' });
    expect(t.needsTap).toEqual([true, false]);
  });

  it('pauses a player that starts on its own while the room is paused', () => {
    const t = setup();
    t.controller.update(room({ ...playing(0, 0), playback: { isPlaying: false, positionMs: 0, anchorServerTimeMs: 0 } }), {});
    t.player().emit({ type: 'playing' });
    expect(t.player().calls.at(-1)).toBe('pause');
  });
});
