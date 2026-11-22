// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import type { PlayerEvent } from './types';
import { type YTNamespace, type YTPlayerOptions, IFRAME_API_URL, createYouTubeApiLoader, createYouTubePlayer } from './youtube';

class FakeYTPlayer {
  static last: FakeYTPlayer | null = null;
  calls: unknown[][] = [];
  time = 0;
  /** The video the (fake) player currently reports; tests move it to simulate the switch. */
  shownVideoId = '';
  constructor(
    readonly element: HTMLElement,
    readonly options: YTPlayerOptions,
  ) {
    FakeYTPlayer.last = this;
  }
  cueVideoById(o: unknown) {
    this.calls.push(['cue', o]);
  }
  playVideo() {
    this.calls.push(['play']);
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
  it('queues load and play until ready, then cues paused and plays', async () => {
    const player = createYouTubePlayer(document.body, () => {}, () => Promise.resolve(YT));
    player.load({ kind: 'youtube', videoId: 'abc' }, 15_000, 1);
    player.play();
    expect(player.currentMs()).toBeNull();
    await flush();
    const yt = FakeYTPlayer.last!;
    expect(yt.options.host).toBe('https://www.youtube-nocookie.com');
    yt.options.events.onReady();
    expect(yt.calls).toEqual([['cue', { videoId: 'abc', startSeconds: 15 }], ['play']]);
    yt.time = 16.5;
    expect(player.currentMs()).toBeNull(); // new video has not reported yet
    yt.shownVideoId = 'abc';
    yt.options.events.onStateChange({ data: CUED });
    expect(player.currentMs()).toBe(16_500);
    player.seek(20_000);
    expect(yt.calls.at(-1)).toEqual(['seek', 20, true]);
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
    yt.options.events.onStateChange({ data: CUED });
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
