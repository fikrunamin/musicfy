// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { type HlsConstructor, type HlsLike, createVideoPlayer } from './html5';
import type { PlayerEvent } from './types';

const flush = () => new Promise((r) => setTimeout(r, 0));
type Handler = (e: string, d: { fatal?: boolean }) => void;
const MP4 = { kind: 'video', url: 'https://cdn.example/v.mp4', hls: false } as const;
const M3U8 = { kind: 'video', url: 'https://cdn.example/live.m3u8', hls: true } as const;

let playSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // jsdom does not implement media playback.
  playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(() => Promise.resolve());
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('');
});

function fakeHls(supported = true) {
  const instances: (HlsLike & { url?: string; destroyed: boolean; handlers: Record<string, Handler> })[] = [];
  class Hls {
    static Events = { ERROR: 'hlsError' };
    static isSupported = () => supported;
    url?: string;
    destroyed = false;
    handlers: Record<string, Handler> = {};
    constructor() {
      instances.push(this as never);
    }
    loadSource(url: string) {
      this.url = url;
    }
    attachMedia() {}
    on(event: string, handler: Handler) {
      this.handlers[event] = handler;
    }
    destroy() {
      this.destroyed = true;
    }
  }
  return { Hls: Hls as unknown as HlsConstructor, instances };
}

function setup(loadHls?: () => Promise<HlsConstructor>) {
  const events: PlayerEvent[] = [];
  const container = document.createElement('div');
  const player = createVideoPlayer(container, (e) => events.push(e), loadHls);
  const video = container.querySelector('video')!;
  return { player, video, events, container };
}

function setReady(video: HTMLVideoElement, readyState = 1) {
  Object.defineProperty(video, 'readyState', { value: readyState, configurable: true });
}

function setError(video: HTMLVideoElement, error: unknown) {
  Object.defineProperty(video, 'error', { value: error, configurable: true });
}

/** Loads and positions (no start seek), as a real source reaching HAVE_METADATA does. */
function position(video: HTMLVideoElement) {
  setReady(video);
  video.dispatchEvent(new Event('loadedmetadata'));
}

function setTime(video: HTMLVideoElement, seconds: number) {
  Object.defineProperty(video, 'currentTime', { value: seconds, writable: true, configurable: true });
}

describe('createVideoPlayer', () => {
  it('plays mp4 natively via src and reports unknown position until positioned', () => {
    const { player, video } = setup();
    player.load(MP4, 5_000, 1);
    expect(video.getAttribute('src')).toBe(MP4.url);
    expect(video.playsInline).toBe(true);
    expect(player.currentMs()).toBeNull();
    setTime(video, 0);
    position(video);
    expect(video.currentTime).toBe(5); // initial seek applied
    expect(player.currentMs()).toBeNull(); // still waiting for 'seeked'
    video.dispatchEvent(new Event('seeked'));
    expect(player.currentMs()).toBe(5_000);
  });

  it('is positioned right after loadedmetadata when no start seek is needed', () => {
    const { player, video } = setup();
    player.load(MP4, null, 1);
    position(video);
    setTime(video, 12);
    expect(player.currentMs()).toBe(12_000);
  });

  it('currentMs is null again after a new load, never the previous source position', () => {
    const { player, video } = setup();
    player.load(MP4, null, 1);
    position(video);
    setTime(video, 42);
    expect(player.currentMs()).toBe(42_000);
    player.load({ ...MP4, url: 'https://cdn.example/other.mp4' }, null, 2);
    expect(player.currentMs()).toBeNull();
  });

  it('echoes the gen of its own load and does not double-report through old listeners', () => {
    const { player, video, events } = setup();
    player.load(MP4, null, 7);
    position(video);
    video.dispatchEvent(new Event('playing'));
    expect(events).toEqual([{ type: 'playing', gen: 7 }]);
    player.load({ ...MP4, url: 'https://cdn.example/other.mp4' }, null, 8);
    position(video);
    video.dispatchEvent(new Event('playing'));
    expect(events).toEqual([
      { type: 'playing', gen: 7 },
      { type: 'playing', gen: 8 },
    ]);
  });

  it('maps media element events, with the gen', () => {
    const { player, video, events } = setup();
    player.load(MP4, null, 3);
    position(video);
    video.dispatchEvent(new Event('playing'));
    video.dispatchEvent(new Event('pause'));
    setError(video, { code: 4 });
    video.dispatchEvent(new Event('error'));
    expect(events).toEqual([
      { type: 'playing', gen: 3 },
      { type: 'paused', gen: 3 },
      { type: 'unplayable', gen: 3 },
    ]);
  });

  it('natural end: the pause before ended is not reported as paused; ended is', () => {
    const { player, video, events } = setup();
    player.load(MP4, null, 4);
    position(video);
    Object.defineProperty(video, 'duration', { value: 100, configurable: true });
    setTime(video, 100);
    video.dispatchEvent(new Event('pause'));
    video.dispatchEvent(new Event('ended'));
    expect(events).toEqual([{ type: 'ended', gen: 4 }]);
    // within the epsilon of the end counts as the end too
    setTime(video, 99.97);
    video.dispatchEvent(new Event('pause'));
    expect(events).toHaveLength(1);
    // a mid-media pause is reported
    setTime(video, 50);
    video.dispatchEvent(new Event('pause'));
    expect(events.at(-1)).toEqual({ type: 'paused', gen: 4 });
  });

  it('remembers play() until positioned, then plays', () => {
    const { player, video } = setup();
    player.load(MP4, 2_000, 1);
    player.play();
    expect(playSpy).not.toHaveBeenCalled();
    position(video);
    expect(playSpy).not.toHaveBeenCalled(); // wait for the start seek
    video.dispatchEvent(new Event('seeked'));
    expect(playSpy).toHaveBeenCalledTimes(1);
  });

  it('a seek before positioned updates the pending start instead of being lost', () => {
    const { player, video } = setup();
    player.load(MP4, 1_000, 1);
    player.seek(9_000);
    position(video);
    expect(video.currentTime).toBe(9);
    video.dispatchEvent(new Event('seeked'));
    expect(player.currentMs()).toBe(9_000);
    // after positioned, seek goes straight to the element
    player.seek(3_000);
    expect(video.currentTime).toBe(3);
  });

  it('a seek between loadedmetadata and seeked re-applies the start', () => {
    const { player, video } = setup();
    player.load(MP4, 1_000, 1);
    position(video);
    player.seek(6_000);
    expect(video.currentTime).toBe(6);
    expect(player.currentMs()).toBeNull();
  });

  it('uses hls.js (lazy) for .m3u8 without native HLS; fatal error is unplayable with the gen', async () => {
    const { Hls, instances } = fakeHls();
    const loadHls = vi.fn(() => Promise.resolve(Hls));
    const { player, events } = setup(loadHls);
    player.load(MP4, null, 1);
    expect(loadHls).not.toHaveBeenCalled(); // not for mp4
    player.load(M3U8, null, 2);
    await flush();
    expect(loadHls).toHaveBeenCalledTimes(1);
    expect(instances).toHaveLength(1);
    expect(instances[0].url).toBe(M3U8.url);
    instances[0].handlers.hlsError('hlsError', { fatal: false });
    expect(events).toEqual([]);
    instances[0].handlers.hlsError('hlsError', { fatal: true }); // e.g. blocked by CORS
    expect(events).toEqual([{ type: 'unplayable', gen: 2 }]);
  });

  it('native HLS plays through src without loading hls.js', () => {
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const loadHls = vi.fn();
    const { player, video } = setup(loadHls);
    player.load(M3U8, null, 1);
    expect(video.getAttribute('src')).toBe(M3U8.url);
    expect(loadHls).not.toHaveBeenCalled();
  });

  it('marks HLS unplayable when hls.js is unsupported or fails to load', async () => {
    const unsupported = fakeHls(false);
    const a = setup(() => Promise.resolve(unsupported.Hls));
    a.player.load(M3U8, null, 1);
    await flush();
    expect(a.events).toEqual([{ type: 'unplayable', gen: 1 }]);
    const b = setup(() => Promise.reject(new Error('chunk failed')));
    b.player.load(M3U8, null, 2);
    await flush();
    expect(b.events).toEqual([{ type: 'unplayable', gen: 2 }]);
  });

  it('destroys the hls instance on the next load and ignores its late errors', async () => {
    const { Hls, instances } = fakeHls();
    const { player, events } = setup(() => Promise.resolve(Hls));
    player.load(M3U8, null, 1);
    await flush();
    player.load(MP4, null, 2);
    expect(instances[0].destroyed).toBe(true);
    instances[0].handlers.hlsError('hlsError', { fatal: true });
    expect(events).toEqual([]);
  });

  it('does not create hls.js for a load superseded while it was loading', async () => {
    const { Hls, instances } = fakeHls();
    const { player } = setup(() => Promise.resolve(Hls));
    player.load(M3U8, null, 1);
    player.load(MP4, null, 2);
    await flush();
    expect(instances).toHaveLength(0);
  });

  it('destroy removes the element, destroys hls and silences later callbacks', async () => {
    const { Hls, instances } = fakeHls();
    const { player, video, events, container } = setup(() => Promise.resolve(Hls));
    player.load(M3U8, null, 1);
    await flush();
    player.destroy();
    expect(container.querySelector('video')).toBeNull();
    expect(instances[0].destroyed).toBe(true);
    instances[0].handlers.hlsError('hlsError', { fatal: true });
    video.dispatchEvent(new Event('playing'));
    video.dispatchEvent(new Event('error'));
    expect(events).toEqual([]);
  });

  it('destroy while hls.js is still loading creates nothing', async () => {
    const { Hls, instances } = fakeHls();
    const { player } = setup(() => Promise.resolve(Hls));
    player.load(M3U8, null, 1);
    player.destroy();
    await flush();
    expect(instances).toHaveLength(0);
  });

  it('V1: a pause queued by the previous source does not report paused under the new gen', () => {
    const { player, video, events } = setup();
    player.load(MP4, null, 1);
    position(video);
    player.play();
    video.dispatchEvent(new Event('playing'));
    events.length = 0;
    player.load({ ...MP4, url: 'https://cdn.example/b.mp4' }, 0, 2);
    video.dispatchEvent(new Event('pause'));
    expect(events).toEqual([]);
  });

  it('V2: stale ended / error (video.error reset by load) after a new load emit nothing', () => {
    const { player, video, events } = setup();
    player.load(MP4, null, 1);
    position(video);
    events.length = 0;
    player.load({ ...MP4, url: 'https://cdn.example/b.mp4' }, 0, 2);
    setError(video, null);
    video.dispatchEvent(new Event('ended'));
    video.dispatchEvent(new Event('error'));
    video.dispatchEvent(new Event('pause'));
    expect(events).toEqual([]);
    // real ones after positioning still work
    setReady(video);
    video.dispatchEvent(new Event('loadedmetadata'));
    video.dispatchEvent(new Event('seeked'));
    video.dispatchEvent(new Event('ended'));
    setError(video, { code: 3 });
    video.dispatchEvent(new Event('error'));
    expect(events).toEqual([
      { type: 'ended', gen: 2 },
      { type: 'unplayable', gen: 2 },
    ]);
  });

  it('V3: a stale loadedmetadata (readyState 0) does not position the new load', () => {
    const { player, video } = setup();
    player.load(MP4, null, 1);
    player.load({ ...MP4, url: 'https://cdn.example/live.m3u8' }, null, 2);
    setReady(video, 0);
    video.dispatchEvent(new Event('loadedmetadata'));
    expect(player.currentMs()).toBeNull();
    setReady(video, 1);
    video.dispatchEvent(new Event('loadedmetadata'));
    expect(player.currentMs()).not.toBeNull();
  });
});
