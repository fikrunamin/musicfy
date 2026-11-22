import type { QueueEntry } from '../protocol';
import type { PlayableSource } from './types';

export type Playability =
  | { playable: true; source: PlayableSource; large: boolean }
  | { playable: false; reason: 'unsupported' | 'insecure' };

const HLS_PATH = /\.m3u8$/i;

/**
 * How (and whether) this browser can play an entry. YouTube kinds use the IFrame player
 * (video kinds shown large); URL entries use <video>, with hls.js for .m3u8. UNKNOWN kinds from a
 * newer protocol are unsupported, and http:// media cannot play on an https page (mixed content).
 */
export function playability(entry: QueueEntry, pageIsHttps: boolean): Playability {
  switch (entry.kind) {
    case 'YOUTUBE_MUSIC':
    case 'YOUTUBE_VIDEO':
      if (!entry.videoId) return { playable: false, reason: 'unsupported' };
      return { playable: true, source: { kind: 'youtube', videoId: entry.videoId }, large: entry.kind === 'YOUTUBE_VIDEO' };
    case 'URL': {
      let url: URL;
      try {
        url = new URL(entry.url ?? '');
      } catch {
        return { playable: false, reason: 'unsupported' };
      }
      if (url.protocol !== 'https:' && url.protocol !== 'http:') return { playable: false, reason: 'unsupported' };
      if (pageIsHttps && url.protocol === 'http:') return { playable: false, reason: 'insecure' };
      return { playable: true, source: { kind: 'video', url: url.href, hls: HLS_PATH.test(url.pathname) }, large: true };
    }
    default:
      return { playable: false, reason: 'unsupported' };
  }
}
