// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import type { PlayerEvent } from './types';
import { type YTNamespace, type YTPlayerOptions, IFRAME_API_URL, createYouTubeApiLoader, createYouTubePlayer } from './youtube';

class FakeYTPlayer {
  static last: FakeYTPlayer | null = null;
  calls: unknown[][] = [];
  time = 0;
  /** The video the (fake) player currently reports; tests move it to simulate the switch. */
  shownVideoId = '';
  /** What getPlayerState() reports (YT.PlayerState: 1 playing, 5 cued, ...). */
  playerState = -1;
  /** Like the real API: a cue is in flight until CUED/PLAYING is reported, and playVideo() then fails with error 2. */
  cueInFlight = false;
  constructor(
    readonly element: HTMLElement,
    readonly options: YTPlayerOptions,
  ) {
    FakeYTPlayer.last = this;
    const onStateChange = options.events.onStateChange;
    options.events.onStateChange = (e) => {
      if (e.data === CUED || e.data === 1) this.cueInFlight = false;
      onStateChange(e);
    };
  }
  cueVideoById(o: unknown) {
    this.calls.push(['cue', o]);
    this.cueInFlight = true;
  }
  playVideo() {
    this.calls.push(['play']);
    if (this.cueInFlight) this.options.events.onError({ data: 2 });
  }
  pauseVideo() {
    this.calls.push(['pause']);
  }
  seekTo(s: number, a: boolean) {
    this.calls.push(['seek', s, a]);
  }
  getCurrentTime() {
    return this.time;
  }
  getPlayerState() {
    return this.playerState;
  }
  getVideoData() {
    return { video_id: this.shownVideoId };
  }
  destroy() {
    this.calls.push(['destroy']);
  }
}

const YT = { Player: FakeYTPlayer } as unknown as YTNamespace;
const flush = () => new Promise((r) => setTimeout(r, 0));
const BUFFERING = 3;
const CUED = 5;

async function readyPlayer(events: PlayerEvent[] = []) {
  const player = createYouTubePlayer(document.body, (e) => events.push(e), () => Promise.resolve(YT));
  await flush();
  const yt = FakeYTPlayer.last!;
  yt.options.events.onReady();
  return { player, yt, events };
}

describe('createYouTubeApiLoader', () => {
  it('adds the official script once and resolves when the API calls back', async () => {
    const win = {} as Window & { YT?: YTNamespace; onYouTubeIframeAPIReady?: () => void };
    const load = createYouTubeApiLoader(document, win);
    const a = load();
    const b = load();
    expect(a).toBe(b);
    const scripts = document.head.querySelectorAll(`script[src="${IFRAME_API_URL}"]`);
    expect(scripts).toHaveLength(1);
    win.YT = YT;
    win.onYouTubeIframeAPIReady!();
    await expect(a).resolves.toBe(YT);
    await expect(load()).resolves.toBe(YT);
  });
});

describe('createYouTubePlayer', () => {
  it('queues load and play until ready, then cues paused and plays once CUED', async () => {
    const events: PlayerEvent[] = [];
    const player = createYouTubePlayer(document.body, (e) => events.push(e), () => Promise.resolve(YT));
    player.load({ kind: 'youtube', videoId: 'abc' }, 15_000, 1);
    player.play();
    expect(player.currentMs()).toBeNull();
    await flush();
    const yt = FakeYTPlayer.last!;
    expect(yt.options.host).toBe('https://www.youtube-nocookie.com');
    yt.options.events.onReady();
    // Not play in the same tick: YouTube rejects that with error 2 (mid-join "Tidak bisa diputar di web").
    expect(yt.calls).toEqual([['cue', { videoId: 'abc', startSeconds: 15 }]]);
    yt.time = 16.5;
    expect(player.currentMs()).toBeNull(); // new video has not reported yet
    yt.shownVideoId = 'abc';
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls).toEqual([['cue', { videoId: 'abc', startSeconds: 15 }], ['play']]);
    expect(player.currentMs()).toBe(15_000); // cued: the cued start, not getCurrentTime()
    yt.options.events.onStateChange({ data: 1 });
    expect(player.currentMs()).toBe(16_500);
    player.seek(20_000);
    expect(yt.calls.at(-1)).toEqual(['seek', 20, true]);
    expect(events).toEqual([{ type: 'playing', gen: 1 }]); // no spurious unplayable
  });

  it('on a ready player, holds play() for a new track until its cue reports CUED', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: 1 });
    yt.calls.length = 0;
    events.length = 0;

    player.load({ kind: 'youtube', videoId: 'b' }, 30_000, 2); // anchor already past
    player.play();
    expect(yt.calls).toEqual([['cue', { videoId: 'b', startSeconds: 30 }]]);
    player.play(); // a controller retry while the cue is in flight: still held
    expect(yt.calls).toHaveLength(1);
    yt.shownVideoId = 'b';
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls.at(-1)).toEqual(['play']);
    yt.options.events.onStateChange({ data: 1 });
    expect(events).toEqual([{ type: 'playing', gen: 2 }]);
    player.play(); // cue settled: plays right away again
    expect(yt.calls.at(-1)).toEqual(['play']);
    expect(yt.calls.filter((c) => c[0] === 'play')).toHaveLength(2);
  });

  it('a pause while the cue is in flight cancels the held play', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 10_000, 1);
    player.play();
    player.pause();
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls.some((c) => c[0] === 'play')).toBe(false);
    expect(player.currentMs()).toBe(10_000);
    expect(events).toEqual([]);
  });

  it('a new load while a cue is in flight releases the play only on the new video\'s CUED', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    yt.calls.length = 0;

    player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
    player.play();
    player.load({ kind: 'youtube', videoId: 'c' }, 5_000, 3); // gen changes before B is cued
    expect(yt.calls).toEqual([
      ['cue', { videoId: 'b', startSeconds: 0 }],
      ['cue', { videoId: 'c', startSeconds: 5 }],
    ]);
    yt.shownVideoId = 'b';
    yt.options.events.onStateChange({ data: CUED }); // B's late CUED: known stale, dropped
    expect(yt.calls.some((c) => c[0] === 'play')).toBe(false);
    yt.shownVideoId = 'c';
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls.at(-1)).toEqual(['play']);
    yt.options.events.onStateChange({ data: 1 });
    expect(events).toEqual([{ type: 'playing', gen: 3 }]);
  });

  it('a load and play queued before ready, then replaced, cues and plays only the latest', async () => {
    const events: PlayerEvent[] = [];
    const player = createYouTubePlayer(document.body, (e) => events.push(e), () => Promise.resolve(YT));
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    player.play();
    player.load({ kind: 'youtube', videoId: 'b' }, 2_000, 2);
    await flush();
    const yt = FakeYTPlayer.last!;
    yt.options.events.onReady();
    expect(yt.calls).toEqual([['cue', { videoId: 'b', startSeconds: 2 }]]);
    yt.shownVideoId = 'b';
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls.at(-1)).toEqual(['play']);
    yt.options.events.onStateChange({ data: 1 });
    expect(events).toEqual([{ type: 'playing', gen: 2 }]);
  });

  it('a play retry long after a cue whose CUED event never arrived plays if the player reports CUED', async () => {
    const { player, yt } = await readyPlayer();
    const realNow = Date.now;
    let now = 1_000_000;
    Date.now = () => now;
    try {
      player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
      player.play();
      expect(yt.calls.some((c) => c[0] === 'play')).toBe(false);
      yt.shownVideoId = 'a';
      yt.playerState = CUED;
      yt.cueInFlight = false; // the cue finished, but its event was lost
      player.play(); // too early to second-guess the event
      expect(yt.calls.some((c) => c[0] === 'play')).toBe(false);
      now += 3_500;
      player.play();
      expect(yt.calls.at(-1)).toEqual(['play']);
      expect(player.currentMs()).toBe(0);
    } finally {
      Date.now = realNow;
    }
  });

  it('a re-cue from seek while paused, then play, waits for CUED', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    player.seek(40_000);
    player.play();
    expect(yt.calls.at(-1)).toEqual(['cue', { videoId: 'a', startSeconds: 40 }]);
    yt.options.events.onStateChange({ data: CUED });
    expect(yt.calls.at(-1)).toEqual(['play']);
    expect(events).toEqual([]);
  });

  it('maps states and embed errors to events tagged with the load generation', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'abc' }, 0, 7);
    yt.shownVideoId = 'abc';
    yt.options.events.onStateChange({ data: 1 });
    yt.options.events.onStateChange({ data: 2 });
    yt.options.events.onStateChange({ data: BUFFERING }); // buffering: no event
    yt.options.events.onError({ data: 150 });
    yt.options.events.onError({ data: 101 });
    yt.options.events.onError({ data: 100 });
    yt.options.events.onError({ data: 2 });
    yt.options.events.onError({ data: 5 }); // HTML5 player error: transient, ignored
    expect(events).toEqual([
      { type: 'playing', gen: 7 },
      { type: 'paused', gen: 7 },
      { type: 'unplayable', gen: 7 },
      { type: 'unplayable', gen: 7 },
      { type: 'unplayable', gen: 7 },
      { type: 'unplayable', gen: 7 },
    ]);
  });

  it('maps ended to ended, never to paused', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'abc' }, 0, 3);
    yt.shownVideoId = 'abc';
    yt.options.events.onStateChange({ data: 0 });
    expect(events).toEqual([{ type: 'ended', gen: 3 }]);
  });

  it('tags an error with the latest load, and queued loads with their own generation', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.options.events.onError({ data: 150 });
    player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
    yt.options.events.onError({ data: 150 });
    expect(events).toEqual([
      { type: 'unplayable', gen: 1 },
      { type: 'unplayable', gen: 2 },
    ]);
  });

  it('currentMs() is null after load() on a reused player until the new video is cued or playing', async () => {
    const { player, yt } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'old' }, 0, 1);
    yt.shownVideoId = 'old';
    yt.options.events.onStateChange({ data: 1 });
    yt.time = 42;
    expect(player.currentMs()).toBe(42_000);

    player.load({ kind: 'youtube', videoId: 'new' }, 5_000, 2);
    expect(player.currentMs()).toBeNull(); // still the old video's 42 s
    yt.options.events.onStateChange({ data: CUED }); // late state of the old video
    expect(player.currentMs()).toBeNull();
    yt.shownVideoId = 'new';
    yt.time = 5;
    yt.options.events.onStateChange({ data: BUFFERING });
    expect(player.currentMs()).toBeNull();
    yt.options.events.onStateChange({ data: 1 });
    expect(player.currentMs()).toBe(5_000);
  });

  it('does not report a late state of the previous video under the new generation', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'old' }, 0, 1);
    yt.shownVideoId = 'old';
    player.load({ kind: 'youtube', videoId: 'new' }, 0, 2);
    yt.options.events.onStateChange({ data: 0 }); // old video ended after the new load
    expect(events).toEqual([]);
  });

  it('seek on a cued video re-cues instead of seekTo (seekTo would start playback)', async () => {
    const { player, yt } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 60_000, 1);
    player.pause();
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    expect(player.currentMs()).toBe(60_000); // not getCurrentTime() (0)
    player.seek(90_000);
    expect(yt.calls.some((c) => c[0] === 'seek')).toBe(false);
    expect(yt.calls.at(-1)).toEqual(['cue', { videoId: 'a', startSeconds: 90 }]);
    expect(player.currentMs()).toBeNull();
    yt.options.events.onStateChange({ data: CUED });
    expect(player.currentMs()).toBe(90_000);
  });

  it('seek uses seekTo when playing or when play is wanted', async () => {
    const { player, yt } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    player.play();
    player.seek(5_000);
    expect(yt.calls.at(-1)).toEqual(['seek', 5, true]);
  });

  it('does not starve on an unrelated video id', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'req' }, 0, 1);
    yt.shownVideoId = 'other';
    yt.options.events.onStateChange({ data: CUED });
    yt.options.events.onStateChange({ data: 1 });
    expect(events).toEqual([{ type: 'playing', gen: 1 }]);
    expect(player.currentMs()).not.toBeNull();
  });

  it('drops a known-stale previous id right after load, accepts it later', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    const realNow = Date.now;
    let now = 1_000_000;
    Date.now = () => now;
    try {
      player.load({ kind: 'youtube', videoId: 'b' }, 0, 2); // cache still shows 'a'
      yt.options.events.onStateChange({ data: 1 });
      yt.options.events.onError({ data: 150 });
      expect(events).toEqual([]);
      expect(player.currentMs()).toBeNull();
      now += 3_500; // cache never updated: stop filtering
      yt.options.events.onStateChange({ data: 1 });
      expect(events).toEqual([{ type: 'playing', gen: 2 }]);
      expect(player.currentMs()).not.toBeNull();
    } finally {
      Date.now = realNow;
    }
  });

  it('seek on an ended video re-cues instead of seekTo (seekTo would replay the tail)', async () => {
    const { player, yt } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: 1 });
    yt.options.events.onStateChange({ data: 0 });
    player.pause();
    player.seek(225_000);
    expect(yt.calls.some((c) => c[0] === 'seek')).toBe(false);
    expect(yt.calls.at(-1)).toEqual(['cue', { videoId: 'a', startSeconds: 225 }]);
  });

  it('recovers a CUED/PLAYING dropped as stale once the video data catches up', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    vi.useFakeTimers();
    try {
      player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
      yt.options.events.onStateChange({ data: CUED }); // cache still 'a': dropped
      yt.options.events.onStateChange({ data: 1 });
      expect(events).toEqual([]);
      expect(player.currentMs()).toBeNull();
      yt.shownVideoId = 'b'; // no further state events
      yt.playerState = 1;
      vi.advanceTimersByTime(250);
      expect(events).toEqual([{ type: 'playing', gen: 2 }]);
      expect(player.currentMs()).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a stale ENDED of the previous video is not replayed under the new gen', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: 1 });
    events.length = 0;
    vi.useFakeTimers();
    try {
      player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
      yt.options.events.onStateChange({ data: 0 }); // A's ENDED, dropped
      yt.shownVideoId = 'b';
      yt.playerState = CUED; // B is actually cued
      vi.advanceTimersByTime(250);
      expect(events).toEqual([]);
      expect(player.currentMs()).not.toBeNull(); // awaiting cleared
    } finally {
      vi.useRealTimers();
    }
  });

  it('a stale PAUSED after a dropped PLAYING recovers as playing', async () => {
    const { player, yt, events } = await readyPlayer();
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: CUED });
    vi.useFakeTimers();
    try {
      player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
      yt.options.events.onStateChange({ data: 1 }); // B's PLAYING, dropped (cache lags)
      yt.options.events.onStateChange({ data: 2 }); // A's stale PAUSED, dropped
      yt.shownVideoId = 'b';
      yt.playerState = 1;
      vi.advanceTimersByTime(250);
      expect(events).toEqual([{ type: 'playing', gen: 2 }]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('callbacks after destroy do nothing', async () => {
    const events: PlayerEvent[] = [];
    const player = createYouTubePlayer(document.body, (e) => events.push(e), () => Promise.resolve(YT));
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    await flush();
    const yt = FakeYTPlayer.last!;
    player.destroy();
    const n = yt.calls.length;
    yt.options.events.onReady();
    yt.shownVideoId = 'a';
    yt.options.events.onStateChange({ data: 1 });
    yt.options.events.onError({ data: 150 });
    expect(yt.calls).toHaveLength(n);
    expect(events).toEqual([]);
  });

  it('retries the API on the next load after a failed script load', async () => {
    const events: PlayerEvent[] = [];
    let attempts = 0;
    const loadApi = () => (++attempts === 1 ? Promise.reject(new Error('blocked')) : Promise.resolve(YT));
    FakeYTPlayer.last = null;
    const player = createYouTubePlayer(document.body, (e) => events.push(e), loadApi);
    player.load({ kind: 'youtube', videoId: 'a' }, 0, 1);
    await flush();
    expect(events).toEqual([{ type: 'unplayable', gen: 1 }]);
    player.load({ kind: 'youtube', videoId: 'b' }, 0, 2);
    await flush();
    expect(attempts).toBe(2);
    FakeYTPlayer.last!.options.events.onReady();
    expect(FakeYTPlayer.last!.calls).toEqual([['cue', { videoId: 'b', startSeconds: 0 }]]);
    expect(events).toHaveLength(1);
  });

  it('reports unplayable when the API cannot load (blocked script)', async () => {
    const events: PlayerEvent[] = [];
    const player = createYouTubePlayer(document.body, (e) => events.push(e), () => Promise.reject(new Error('blocked')));
    player.load({ kind: 'youtube', videoId: 'abc' }, 0, 4);
    await flush();
    expect(events).toEqual([{ type: 'unplayable', gen: 4 }]);
    player.load({ kind: 'youtube', videoId: 'def' }, 0, 5);
    await flush();
    expect(events).toEqual([
      { type: 'unplayable', gen: 4 },
      { type: 'unplayable', gen: 5 },
    ]);
  });

  it('destroy removes the player and its mount', async () => {
    const container = document.createElement('div');
    const player = createYouTubePlayer(container, () => {}, () => Promise.resolve(YT));
    await flush();
    player.destroy();
    expect(FakeYTPlayer.last!.calls.at(-1)).toEqual(['destroy']);
    expect(container.children).toHaveLength(0);
  });

  it('never creates a player after destroy', async () => {
    FakeYTPlayer.last = null;
    const player = createYouTubePlayer(document.body, () => {}, () => Promise.resolve(YT));
    player.destroy();
    await flush();
    expect(FakeYTPlayer.last).toBeNull();
  });
});
