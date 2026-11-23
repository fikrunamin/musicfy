import { describe, expect, it, vi } from 'vitest';
import { type SearchResult, searchSongs, toNewEntry } from './search';

const song: SearchResult = {
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  durationMs: 213_000,
  thumbnailUrl: 'https://lh3.googleusercontent.com/abc=w120-h120',
};

function respond(status: number, body: unknown = []) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
}

describe('searchSongs', () => {
  it('requests /api/search with the encoded, trimmed query and returns the results', async () => {
    const fetchImpl = respond(200, [song, { videoId: 1 }]);
    const outcome = await searchSongs('  rick & roll ', fetchImpl);
    expect(fetchImpl).toHaveBeenCalledWith('/api/search?q=rick%20%26%20roll', expect.anything());
    expect(outcome).toEqual({ ok: true, results: [song] });
  });

  it('never calls the server for an empty or too long query', async () => {
    const fetchImpl = respond(200);
    expect(await searchSongs('   ', fetchImpl)).toEqual({ ok: false, reason: 'invalid' });
    expect(await searchSongs('x'.repeat(201), fetchImpl)).toEqual({ ok: false, reason: 'invalid' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('maps 400, 429, 503, other errors and network failures', async () => {
    expect(await searchSongs('a', respond(400))).toEqual({ ok: false, reason: 'invalid' });
    expect(await searchSongs('a', respond(429))).toEqual({ ok: false, reason: 'rate-limited' });
    expect(await searchSongs('a', respond(503))).toEqual({ ok: false, reason: 'busy' });
    expect(await searchSongs('a', respond(502))).toEqual({ ok: false, reason: 'failed' });
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    }) as unknown as typeof fetch;
    expect(await searchSongs('a', offline)).toEqual({ ok: false, reason: 'failed' });
  });
});

describe('toNewEntry', () => {
  it('makes a YOUTUBE_MUSIC entry', () => {
    expect(toNewEntry(song)).toEqual({
      videoId: 'dQw4w9WgXcQ',
      title: 'Never Gonna Give You Up',
      artist: 'Rick Astley',
      thumbnailUrl: 'https://lh3.googleusercontent.com/abc=w120-h120',
      durationMs: 213_000,
      kind: 'YOUTUBE_MUSIC',
      url: null,
    });
  });

  it('keeps entries within the server limits', () => {
    const long = toNewEntry({ ...song, title: 'x'.repeat(400), thumbnailUrl: `https://a.ytimg.com/${'y'.repeat(600)}` })!;
    expect(long.title).toHaveLength(300);
    expect(long.thumbnailUrl).toBeNull();
    expect(toNewEntry({ ...song, thumbnailUrl: 'http://i.ytimg.com/x.jpg' })!.thumbnailUrl).toBeNull();
    expect(toNewEntry({ ...song, videoId: 'bad id!' })).toBeNull();
  });

  it('only keeps thumbnails from the hosts the server allows', () => {
    const thumb = (u: string) => toNewEntry({ ...song, thumbnailUrl: u })!.thumbnailUrl;
    expect(thumb('https://i.ytimg.com/vi/x/hq.jpg')).not.toBeNull();
    expect(thumb('https://yt3.ggpht.com/a')).not.toBeNull();
    expect(thumb('https://example.com/a.jpg')).toBeNull();
    expect(thumb('https://ytimg.com/a.jpg')).toBeNull();
    expect(thumb('https://evilytimg.com/a.jpg')).toBeNull();
    expect(thumb('https://i.ytimg.com.evil.com/a.jpg')).toBeNull();
    expect(thumb('https://evil.com@i.ytimg.com/a.jpg')).not.toBeNull();
    expect(thumb('https://evil.com/@i.ytimg.com')).toBeNull();
    expect(thumb('not a url')).toBeNull();
  });

  it('clamps the duration and rejects blank titles', () => {
    expect(toNewEntry({ ...song, durationMs: 9e9 })!.durationMs).toBe(86_400_000);
    expect(toNewEntry({ ...song, durationMs: -5 })!.durationMs).toBe(0);
    expect(toNewEntry({ ...song, durationMs: Number.NaN })!.durationMs).toBe(0);
    expect(toNewEntry({ ...song, title: '   ' })).toBeNull();
    // Nothing visible once cleaned like the server does: zero-width space, blank braille, Hangul filler.
    expect(toNewEntry({ ...song, title: '\u200B\u2800\u3164' })).toBeNull();
  });
});
