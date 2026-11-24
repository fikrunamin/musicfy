import type { QueueEntry } from '../protocol';
import type { PlayableSource } from './types';

export type Playability =
  | { playable: true; source: PlayableSource; large: boolean }
  | { playable: false; reason: 'unsupported' | 'insecure' };

const HLS_PATH = /\.m3u8$/i;
const DASH_PATH = /\.mpd$/i;

/**
 * How (and whether) this browser can play an entry. YouTube kinds use the IFrame player (video
 * kinds shown large); URL entries use <video>, with hls.js for HLS. A URL entry's streamType (#3)
 * says what its path may not (`/manifest?token=`); without one, or UNKNOWN, the extension decides.
 * DASH is never playable here (D11). UNKNOWN kinds from a newer protocol are unsupported, and
 * http:// media cannot play on an https page (mixed content).
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
      const type = entry.streamType === 'UNKNOWN' ? null : entry.streamType;
      if (type === 'DASH' || (type === null && DASH_PATH.test(url.pathname))) return { playable: false, reason: 'unsupported' };
      const hls = type === 'HLS' || (type === null && HLS_PATH.test(url.pathname));
      return { playable: true, source: { kind: 'video', url: url.href, hls }, large: true };
    }
    default:
      return { playable: false, reason: 'unsupported' };
  }
}
