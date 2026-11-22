import { describe, expect, it } from 'vitest';
import { entry } from '../testing/builders';
import { playability } from './playable';

describe('playability', () => {
  it('plays YouTube kinds in the IFrame player, video kinds large', () => {
    expect(playability(entry('a'), true)).toEqual({ playable: true, source: { kind: 'youtube', videoId: 'vid-a' }, large: false });
    expect(playability(entry('a', { kind: 'YOUTUBE_VIDEO' }), true)).toMatchObject({ playable: true, large: true });
  });

  it('plays URL entries in <video>, flagging HLS', () => {
    const hls = entry('a', { kind: 'URL', videoId: '', url: 'https://cdn.example/live/index.m3u8?t=1' });
    expect(playability(hls, true)).toEqual({
      playable: true,
      source: { kind: 'video', url: 'https://cdn.example/live/index.m3u8?t=1', hls: true },
      large: true,
    });
    const mp4 = entry('a', { kind: 'URL', videoId: '', url: 'https://cdn.example/v.mp4' });
    expect(playability(mp4, true)).toMatchObject({ source: { kind: 'video', hls: false } });
  });

  it('refuses http media on an https page, but allows it on http (local development)', () => {
    const http = entry('a', { kind: 'URL', videoId: '', url: 'http://cdn.example/v.mp4' });
    expect(playability(http, true)).toEqual({ playable: false, reason: 'insecure' });
    expect(playability(http, false)).toMatchObject({ playable: true });
  });

  it('marks UNKNOWN kinds and broken URL entries unsupported', () => {
    expect(playability(entry('a', { kind: 'UNKNOWN' }), true)).toEqual({ playable: false, reason: 'unsupported' });
    expect(playability(entry('a', { kind: 'URL', videoId: '', url: null }), true)).toEqual({ playable: false, reason: 'unsupported' });
    expect(playability(entry('a', { kind: 'URL', videoId: '', url: 'ftp://x/y' }), true)).toEqual({ playable: false, reason: 'unsupported' });
  });
});
