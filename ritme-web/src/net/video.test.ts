import { describe, expect, it, vi } from 'vitest';
import { type VideoInfo, fetchVideoInfo, toUrlEntry, toVideoEntry } from './video';
import type { DirectVideoLink } from './video-link';

const info: VideoInfo = {
  videoId: 'aqz-KE-bpKQ',
  title: 'Big Buck Bunny',
  channel: 'Blender',
  durationMs: 635_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
  live: false,
};

function respond(status: number, body: unknown = info) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
}

describe('fetchVideoInfo', () => {
  it('requests /api/video with the id and returns the info', async () => {
    const fetchImpl = respond(200);
    expect(await fetchVideoInfo('aqz-KE-bpKQ', fetchImpl)).toEqual({ ok: true, info });
    expect(fetchImpl).toHaveBeenCalledWith('/api/video?id=aqz-KE-bpKQ', expect.anything());
  });

  it('never calls the server for a malformed id', async () => {
    const fetchImpl = respond(200);
    expect(await fetchVideoInfo('abc', fetchImpl)).toEqual({ ok: false, reason: 'invalid' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it.each<[number, string]>([
    [400, 'invalid'],
    [404, 'not-found'],
    [429, 'rate-limited'],
    [503, 'busy'],
    [502, 'failed'],
    [500, 'failed'],
  ])('maps HTTP %i to %s', async (status, reason) => {
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(status, ''))).toEqual({ ok: false, reason });
  });

  it('treats a network error, a malformed body or another video as failed', async () => {
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    }) as unknown as typeof fetch;
    expect(await fetchVideoInfo('aqz-KE-bpKQ', offline)).toEqual({ ok: false, reason: 'failed' });
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(200, { videoId: 1 }))).toEqual({ ok: false, reason: 'failed' });
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(200, { ...info, videoId: 'dQw4w9WgXcQ' }))).toEqual({ ok: false, reason: 'failed' });
  });
});

describe('toVideoEntry', () => {
  it('makes a YOUTUBE_VIDEO entry the server accepts', () => {
    expect(toVideoEntry(info, false)).toEqual({
      videoId: 'aqz-KE-bpKQ',
      title: 'Big Buck Bunny',
      artist: 'Blender',
      thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
      durationMs: 635_000,
      kind: 'YOUTUBE_VIDEO',
      url: null,
    });
  });

  it('uses YOUTUBE_MUSIC for music.youtube.com links, 0 for live, and drops foreign thumbnails', () => {
    expect(toVideoEntry(info, true)?.kind).toBe('YOUTUBE_MUSIC');
    expect(toVideoEntry({ ...info, live: true, durationMs: 0 }, false)?.durationMs).toBe(0);
    expect(toVideoEntry({ ...info, thumbnailUrl: 'https://evil.example/t.jpg' }, false)?.thumbnailUrl).toBeNull();
  });

  it('trims long text and refuses a blank title', () => {
    expect(toVideoEntry({ ...info, title: 'x'.repeat(400) }, false)?.title).toHaveLength(300);
    expect(toVideoEntry({ ...info, title: '   ' }, false)).toBeNull();
  });
});

describe('toUrlEntry', () => {
  const link: DirectVideoLink = {
    kind: 'direct',
    url: 'https://cdn.example.com/media/clip.mp4',
    hls: false,
    fileName: 'clip.mp4',
    domain: 'cdn.example.com',
  };

  it('makes a URL entry with the typed title, the domain as artist and no thumbnail', () => {
    expect(toUrlEntry(link, '  Klip saya ', 61_000)).toEqual({
      videoId: '',
      title: 'Klip saya',
      artist: 'cdn.example.com',
      thumbnailUrl: null,
      durationMs: 61_000,
      kind: 'URL',
      url: 'https://cdn.example.com/media/clip.mp4',
    });
  });

  it('falls back to the file name, then the domain, and clamps the duration', () => {
    expect(toUrlEntry(link, '', 0)?.title).toBe('clip.mp4');
    expect(toUrlEntry({ ...link, fileName: '' }, '', 0)?.title).toBe('cdn.example.com');
    expect(toUrlEntry(link, '', 90_000_000)?.durationMs).toBe(86_400_000);
    expect(toUrlEntry(link, '', Number.NaN)?.durationMs).toBe(0);
  });

  it('refuses a link the server would refuse', () => {
    expect(toUrlEntry({ ...link, url: 'http://192.168.1.1/clip.mp4' }, '', 0)).toBeNull();
  });
});
