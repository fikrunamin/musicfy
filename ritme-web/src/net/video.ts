import type { NewEntry } from '../protocol';
import { checkMediaUrl } from './media-url-rules';
import { MAX_DURATION_MS, MAX_TEXT_LENGTH, validThumbnail } from './search';
import type { DirectVideoLink } from './video-link';
import { hasVisibleText } from './visible-text';

/** GET /api/video?id= (ritme-server Video.kt VideoInfo). */
export interface VideoInfo {
  videoId: string;
  title: string;
  channel: string;
  /** 0 for live videos. */
  durationMs: number;
  thumbnailUrl: string | null;
  live: boolean;
}

export type VideoInfoFailure = 'invalid' | 'not-found' | 'rate-limited' | 'busy' | 'failed';

export type VideoInfoOutcome = { ok: true; info: VideoInfo } | { ok: false; reason: VideoInfoFailure };

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

function parseVideoInfo(v: unknown): VideoInfo | null {
  if (typeof v !== 'object' || v === null) return null;
  const o = v as Record<string, unknown>;
  if (typeof o.videoId !== 'string' || typeof o.title !== 'string' || typeof o.channel !== 'string') return null;
  if (typeof o.durationMs !== 'number' || typeof o.live !== 'boolean') return null;
  return {
    videoId: o.videoId,
    title: o.title,
    channel: o.channel,
    durationMs: o.durationMs,
    thumbnailUrl: typeof o.thumbnailUrl === 'string' ? o.thumbnailUrl : null,
    live: o.live,
  };
}

/** Same-origin metadata lookup; nothing is added unless this succeeds (no made-up titles). */
export async function fetchVideoInfo(
  videoId: string,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<VideoInfoOutcome> {
  if (!VIDEO_ID.test(videoId)) return { ok: false, reason: 'invalid' };
  let response: Response;
  try {
    response = await fetchImpl(`/api/video?id=${encodeURIComponent(videoId)}`, {
      signal,
      headers: { Accept: 'application/json' },
    });
  } catch {
    return { ok: false, reason: 'failed' };
  }
  if (response.status === 400) return { ok: false, reason: 'invalid' };
  if (response.status === 404) return { ok: false, reason: 'not-found' };
  if (response.status === 429) return { ok: false, reason: 'rate-limited' };
  if (response.status === 503) return { ok: false, reason: 'busy' };
  if (!response.ok) return { ok: false, reason: 'failed' };
  try {
    const info = parseVideoInfo(await response.json());
    return info && info.videoId === videoId ? { ok: true, info } : { ok: false, reason: 'failed' };
  } catch {
    return { ok: false, reason: 'failed' };
  }
}

/** Trimmed and cut to the server's 300 UTF-16 units, never ending on half a surrogate pair. */
function boundedText(text: string): string {
  const trimmed = text.trim();
  if (trimmed.length <= MAX_TEXT_LENGTH) return trimmed;
  const last = trimmed.charCodeAt(MAX_TEXT_LENGTH - 1);
  return trimmed.slice(0, last >= 0xd800 && last <= 0xdbff ? MAX_TEXT_LENGTH - 1 : MAX_TEXT_LENGTH);
}

function clampDuration(ms: number): number {
  return Number.isFinite(ms) ? Math.min(Math.max(0, Math.round(ms)), MAX_DURATION_MS) : 0;
}

/**
 * A YouTube video as an Add entry the server accepts; null when the id is unusable or the title
 * has nothing the server counts as visible (it would refuse the whole Add).
 */
export function toVideoEntry(info: VideoInfo, music: boolean): NewEntry | null {
  if (!VIDEO_ID.test(info.videoId)) return null;
  const title = boundedText(info.title);
  if (!hasVisibleText(title)) return null;
  return {
    videoId: info.videoId,
    title,
    artist: boundedText(info.channel),
    thumbnailUrl: validThumbnail(info.thumbnailUrl),
    durationMs: info.live ? 0 : clampDuration(info.durationMs),
    kind: music ? 'YOUTUBE_MUSIC' : 'YOUTUBE_VIDEO',
    url: null,
  };
}

/**
 * The title a direct link is added under: the typed title, else the file name, else the domain,
 * whichever first has something the server counts as visible; null when none has.
 */
export function urlEntryTitle(link: DirectVideoLink, title: string): string | null {
  for (const candidate of [title, link.fileName, link.domain]) {
    const text = boundedText(candidate);
    if (hasVisibleText(text)) return text;
  }
  return null;
}

/**
 * A direct link as a URL entry: the title from urlEntryTitle, the domain as artist, never a
 * thumbnail (no image from an arbitrary host for every member). Null when the server would refuse
 * the url or no title is usable.
 */
export function toUrlEntry(link: DirectVideoLink, title: string, durationMs: number): NewEntry | null {
  if (checkMediaUrl(link.url) !== null) return null;
  const name = urlEntryTitle(link, title);
  if (name === null) return null;
  return {
    videoId: '',
    title: name,
    artist: boundedText(link.domain),
    thumbnailUrl: null,
    durationMs: clampDuration(durationMs),
    kind: 'URL',
    url: link.url,
  };
}
