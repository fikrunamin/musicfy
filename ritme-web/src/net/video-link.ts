import { checkMediaUrl } from './media-url-rules';
import { MAX_TEXT_LENGTH } from './search';

/** A YouTube video named by a pasted link. `music`: a music.youtube.com link (added as a song). */
export interface YouTubeLink {
  videoId: string;
  music: boolean;
}

/** A direct media link the web can try: mp4/webm/m4v/mov, or HLS (.m3u8). */
export interface DirectVideoLink {
  kind: 'direct';
  /** WHATWG-normalized (punycode host, percent-encoded path), without fragment. */
  url: string;
  hls: boolean;
  /** Last path segment, decoded: the default title. */
  fileName: string;
  /**
   * Host without "www." and a trailing dot, punycode for IDNs: sent as the entry's artist and
   * shown (decoded, see idn.ts) as where the video comes from.
   */
  domain: string;
}

export type VideoLinkProblem = 'unrecognized' | 'page' | 'dash' | 'unsafe' | 'insecure';

export type VideoLink = ({ kind: 'youtube' } & YouTubeLink) | DirectVideoLink | { kind: 'rejected'; reason: VideoLinkProblem };

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const HAS_SCHEME = /^[a-z][a-z0-9+.-]*:\/\//i;
const VND = /^vnd\.youtube:(?:\/\/)?([A-Za-z0-9_-]{11})(?:[?&#].*)?$/i;
const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'youtube-nocookie.com',
  'www.youtube-nocookie.com',
  'youtu.be',
]);
const PATH_ID_PREFIXES = new Set(['shorts', 'live', 'embed']);
const DIRECT_PATH = /\.(mp4|webm|m4v|mov|m3u8)$/i;
const HLS_PATH = /\.m3u8$/i;
const DASH_PATH = /\.mpd$/i;

/** An http(s) URL from pasted text (no scheme means https), or null. */
function parseWebUrl(text: string): URL | null {
  if (text === '') return null;
  try {
    const url = new URL(HAS_SCHEME.test(text) ? text : `https://${text}`);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url : null;
  } catch {
    return null;
  }
}

/** `/embed/videoseries?list=` embeds a playlist; "videoseries" has the shape of a video id. */
const PLAYLIST_EMBED = 'videoseries';

/**
 * The video in a YouTube link: watch?v=, youtu.be/, /shorts/, /live/, /embed/ on youtube.com
 * (www., m., music.) and youtube-nocookie.com, and vnd.youtube:. Hosts may end in a dot
 * (`youtube.com.`) and the path keywords are matched case-insensitively (`/SHORTS/`); the id
 * itself is case-sensitive. Timestamps (t=) and playlists (list=, /embed/videoseries) are ignored.
 * Cases: modules/ritme-protocol/fixtures/rules/youtube-links.json.
 */
export function parseYouTubeLink(raw: string): YouTubeLink | null {
  const text = raw.trim();
  const vnd = VND.exec(text);
  if (vnd) return { videoId: vnd[1], music: false };
  const url = parseWebUrl(text);
  if (!url) return null;
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  if (!YOUTUBE_HOSTS.has(host)) return null;
  const segments = url.pathname.split('/').filter((s) => s !== '');
  const keyword = segments[0]?.toLowerCase();
  let id: string | null = null;
  if (host === 'youtu.be') {
    if (segments.length === 1) id = segments[0];
  } else if (segments.length === 1 && keyword === 'watch') {
    id = url.searchParams.get('v');
  } else if (segments.length === 2 && keyword !== undefined && PATH_ID_PREFIXES.has(keyword)) {
    if (keyword !== 'embed' || segments[1].toLowerCase() !== PLAYLIST_EMBED) id = segments[1];
  }
  return id !== null && VIDEO_ID.test(id) ? { videoId: id, music: host === 'music.youtube.com' } : null;
}

function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/**
 * What the Video tab does with pasted text (spec 6.1): a YouTube video, a direct media link, or
 * why not. Direct links are normalized and lose their fragment; `.mpd` is refused (no DASH on the
 * web), other paths are web pages, links the server would refuse are `unsafe`, and http media
 * cannot play on an https page (`insecure`).
 */
export function classifyVideoLink(raw: string, pageIsHttps: boolean): VideoLink {
  const text = raw.trim();
  const youtube = parseYouTubeLink(text);
  if (youtube) return { kind: 'youtube', ...youtube };
  const url = parseWebUrl(text);
  if (!url) return { kind: 'rejected', reason: 'unrecognized' };
  url.hash = '';
  if (DASH_PATH.test(url.pathname)) return { kind: 'rejected', reason: 'dash' };
  if (!DIRECT_PATH.test(url.pathname)) return { kind: 'rejected', reason: 'page' };
  if (checkMediaUrl(url.href) !== null) return { kind: 'rejected', reason: 'unsafe' };
  if (pageIsHttps && url.protocol === 'http:') return { kind: 'rejected', reason: 'insecure' };
  const last = url.pathname.slice(url.pathname.lastIndexOf('/') + 1);
  return {
    kind: 'direct',
    url: url.href,
    hls: HLS_PATH.test(url.pathname),
    fileName: decodeSegment(last).trim().slice(0, MAX_TEXT_LENGTH),
    domain: url.hostname.replace(/\.$/, '').replace(/^www\./, ''),
  };
}
