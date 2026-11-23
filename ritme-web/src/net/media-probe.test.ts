import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HlsConstructor } from '../player/html5';
import { PROBE_TIMEOUT_MS, type ProbeDeps, probeMedia } from './media-probe';

class FakeVideo extends EventTarget {
  muted = false;
  preload = '';
  src = '';
  duration = Number.NaN;
  removed = false;
  nativeHls = false;
  canPlayType(type: string): string {
    return this.nativeHls && type === 'application/vnd.apple.mpegurl' ? 'maybe' : '';
  }
  removeAttribute(name: string): void {
    if (name === 'src') this.src = '';
  }
  load(): void {}
  remove(): void {
    this.removed = true;
  }
  metadata(seconds: number): void {
    this.duration = seconds;
    this.dispatchEvent(new Event('loadedmetadata'));
  }
}

function deps(video: FakeVideo, loadHls?: () => Promise<HlsConstructor>): ProbeDeps {
  return {
    createVideo: () => video,
    loadHls: loadHls ?? (() => Promise.reject(new Error('hls.js is not expected here'))),
    timeoutMs: PROBE_TIMEOUT_MS,
  };
}

function fakeHls(calls: string[], handlers: Array<(event: string, data: { fatal?: boolean }) => void>): HlsConstructor {
  class FakeHls {
    static Events = { ERROR: 'hlsError' };
    static isSupported() {
      return true;
    }
    loadSource(url: string) {
      calls.push(`load ${url}`);
    }
    attachMedia() {
      calls.push('attach');
    }
    on(_event: string, handler: (event: string, data: { fatal?: boolean }) => void) {
      handlers.push(handler);
    }
    destroy() {
      calls.push('destroy');
    }
  }
  return FakeHls as unknown as HlsConstructor;
}

afterEach(() => {
  vi.useRealTimers();
});

describe('probeMedia', () => {
  it('loads only the metadata, muted, reads the duration and cleans up', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    expect(video.src).toBe('https://cdn.example.com/a.mp4');
    expect(video.muted).toBe(true);
    expect(video.preload).toBe('metadata');
    video.metadata(61.4);
    expect(await result).toEqual({ kind: 'ready', durationMs: 61_400, live: false });
    expect(video.src).toBe('');
    expect(video.removed).toBe(true);
  });

  it('reports Infinity as live and an unknown duration as 0 but not live', async () => {
    const live = new FakeVideo();
    const liveResult = probeMedia('https://cdn.example.com/live.m3u8', { hls: false }, deps(live));
    live.metadata(Number.POSITIVE_INFINITY);
    expect(await liveResult).toEqual({ kind: 'ready', durationMs: 0, live: true });
    const unknown = new FakeVideo();
    const unknownResult = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(unknown));
    unknown.metadata(Number.NaN);
    expect(await unknownResult).toEqual({ kind: 'ready', durationMs: 0, live: false });
  });

  it('caps a duration beyond a day at 24 h', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    video.metadata(100_000);
    expect(await result).toEqual({ kind: 'ready', durationMs: 86_400_000, live: false });
  });

  it('reports a media error as unplayable', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    video.dispatchEvent(new Event('error'));
    expect(await result).toEqual({ kind: 'unplayable' });
    expect(video.removed).toBe(true);
  });

  it('gives up after 10 s', async () => {
    vi.useFakeTimers();
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    vi.advanceTimersByTime(PROBE_TIMEOUT_MS);
    expect(await result).toEqual({ kind: 'timeout' });
    expect(video.removed).toBe(true);
  });

  it('stops when aborted', async () => {
    const video = new FakeVideo();
    const controller = new AbortController();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false, signal: controller.signal }, deps(video));
    controller.abort();
    expect(await result).toEqual({ kind: 'timeout' });
    expect(video.removed).toBe(true);
  });

  it('uses hls.js for HLS without native support and destroys it afterwards', async () => {
    const calls: string[] = [];
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video, async () => fakeHls(calls, [])));
    await vi.waitFor(() => expect(calls).toEqual(['load https://cdn.example.com/live.m3u8', 'attach']));
    expect(video.src).toBe('');
    video.metadata(Number.POSITIVE_INFINITY);
    expect(await result).toEqual({ kind: 'ready', durationMs: 0, live: true });
    expect(calls).toContain('destroy');
  });

  it('reports a fatal hls.js error as unplayable', async () => {
    const handlers: Array<(event: string, data: { fatal?: boolean }) => void> = [];
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video, async () => fakeHls([], handlers)));
    await vi.waitFor(() => expect(handlers).toHaveLength(1));
    handlers[0]('hlsError', { fatal: false });
    handlers[0]('hlsError', { fatal: true });
    expect(await result).toEqual({ kind: 'unplayable' });
  });

  it('plays HLS natively where the browser can (Safari)', async () => {
    const video = new FakeVideo();
    video.nativeHls = true;
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video));
    expect(video.src).toBe('https://cdn.example.com/live.m3u8');
    video.metadata(30);
    expect(await result).toEqual({ kind: 'ready', durationMs: 30_000, live: false });
  });
});
