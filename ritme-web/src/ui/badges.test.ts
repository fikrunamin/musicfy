import { describe, expect, it } from 'vitest';
import { mediaFormat } from './badges';

describe('mediaFormat', () => {
  it('names the container of a direct file from its extension, upper case', () => {
    expect(mediaFormat('https://media.example.com/a/sawah.mp4')).toBe('MP4');
    expect(mediaFormat('https://files.example.net/klip-konser_final.WebM')).toBe('WEBM');
    expect(mediaFormat('http://x.example/v.mov')).toBe('MOV');
    expect(mediaFormat('https://x.example/v.m4v?token=1#t=3')).toBe('M4V');
  });

  it('calls an .m3u8 stream HLS', () => {
    expect(mediaFormat('https://live.example.org/kamera/index.m3u8')).toBe('HLS · m3u8');
    expect(mediaFormat('https://live.example.org/a.M3U8?x=y')).toBe('HLS · m3u8');
  });

  it('says nothing when the path does not tell (no extension, an unknown one, the query, or no url)', () => {
    expect(mediaFormat('https://media.example.com/stream')).toBeNull();
    expect(mediaFormat('https://media.example.com/page.html')).toBeNull();
    expect(mediaFormat('https://media.example.com/watch?file=a.mp4')).toBeNull();
    expect(mediaFormat('not a url')).toBeNull();
    expect(mediaFormat(null)).toBeNull();
  });
});
