import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { classifyVideoLink, parseYouTubeLink } from './video-link';

const FIXTURE = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/youtube-links.json', import.meta.url));
const { cases } = JSON.parse(readFileSync(FIXTURE, 'utf8')) as {
  cases: { input: string; videoId: string | null; music?: boolean }[];
};

describe('parseYouTubeLink (shared fixture)', () => {
  it('has the shared cases', () => {
    expect(cases).toHaveLength(43);
  });

  for (const c of cases) {
    it(JSON.stringify(c.input), () => {
      expect(parseYouTubeLink(c.input)).toEqual(c.videoId === null ? null : { videoId: c.videoId, music: c.music ?? false });
    });
  }
});

describe('classifyVideoLink', () => {
  it('recognizes YouTube links first', () => {
    expect(classifyVideoLink('https://youtu.be/dQw4w9WgXcQ', true)).toEqual({ kind: 'youtube', videoId: 'dQw4w9WgXcQ', music: false });
  });

  it('accepts direct media links, normalized and without the fragment', () => {
    expect(classifyVideoLink(' https://www.Example.com/media/My%20Clip.MP4?token=1#t=5 ', true)).toEqual({
      kind: 'direct',
      url: 'https://www.example.com/media/My%20Clip.MP4?token=1',
      hls: false,
      fileName: 'My Clip.MP4',
      domain: 'example.com',
    });
    expect(classifyVideoLink('cdn.example.org/live/index.m3u8', true)).toEqual({
      kind: 'direct',
      url: 'https://cdn.example.org/live/index.m3u8',
      hls: true,
      fileName: 'index.m3u8',
      domain: 'cdn.example.org',
    });
    expect(classifyVideoLink('http://cdn.example.org/a.webm', false)).toMatchObject({ kind: 'direct', url: 'http://cdn.example.org/a.webm' });
  });

  it.each([
    ['', 'unrecognized'],
    ['bukan link', 'unrecognized'],
    ['ftp://cdn.example.org/a.mp4', 'unrecognized'],
    ['https://cdn.example.org/a.mpd', 'dash'],
    ['https://example.com/film/123', 'page'],
    ['https://www.youtube.com/playlist?list=PL1', 'page'],
    ['https://user:pw@cdn.example.org/a.mp4', 'unsafe'],
    ['http://192.168.1.10/a.mp4', 'unsafe'],
    ['http://2130706433/a.mp4', 'unsafe'],
    ['https://localhost/a.mp4', 'unsafe'],
    ['http://cdn.example.org/a.mp4', 'insecure'],
    ['https://localhost.localdomain/a.mp4', 'unsafe'],
    ['https://[::ffff:0:192.168.0.1]/a.mp4', 'unsafe'],
    ['https://[2002:c0a8:101::1]/a.mp4', 'unsafe'],
    ['https://198.18.0.1/a.mp4', 'unsafe'],
    [`https://cdn.example.org/${'a'.repeat(2048)}.mp4`, 'unsafe'],
  ])('rejects %j as %s', (input, reason) => {
    expect(classifyVideoLink(input, true)).toEqual({ kind: 'rejected', reason });
  });

  it('drops the trailing dot of a fully qualified host from the shown domain', () => {
    expect(classifyVideoLink('https://www.cdn.example.org./a.mp4', true)).toMatchObject({
      kind: 'direct',
      url: 'https://www.cdn.example.org./a.mp4',
      domain: 'cdn.example.org',
    });
  });

  it('keeps an IDN host as punycode in the url and the domain (decoded for display only)', () => {
    expect(classifyVideoLink('https://bücher.example/a.mp4', true)).toMatchObject({
      kind: 'direct',
      url: 'https://xn--bcher-kva.example/a.mp4',
      domain: 'xn--bcher-kva.example',
    });
  });

  it('lets public DNS names through even when they point at a private address (D6: no DNS on the web)', () => {
    expect(classifyVideoLink('https://127.0.0.1.nip.io/a.mp4', true)).toMatchObject({ kind: 'direct', domain: '127.0.0.1.nip.io' });
  });
});
