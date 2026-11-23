import type { NewEntry } from '../protocol';
import { hasVisibleText } from './visible-text';

/** One item of GET /api/search?q= (ritme-server Search.kt SearchResult). */
export interface SearchResult {
  videoId: string;
  title: string;
  artist: string;
  durationMs: number;
  thumbnailUrl: string | null;
}

export type SearchFailure = 'invalid' | 'rate-limited' | 'busy' | 'failed';

export type SearchOutcome = { ok: true; results: SearchResult[] } | { ok: false; reason: SearchFailure };

export const MAX_QUERY_LENGTH = 200;

/** Server-side entry limits (ritme-server README "Entries"); exceeding them makes Add INVALID. */
export const MAX_TEXT_LENGTH = 300;
const MAX_THUMBNAIL_URL_LENGTH = 512;
export const MAX_DURATION_MS = 86_400_000;
const VIDEO_ID = /^[A-Za-z0-9_-]{1,64}$/;
/** RoomLogic.isValidThumbnail: YouTube entries only accept thumbnails from these hosts. */
const THUMBNAIL_HOST_SUFFIXES = ['.ytimg.com', '.googleusercontent.com', '.ggpht.com'];

function parseResult(v: unknown): SearchResult | null {
  if (typeof v !== 'object' || v === null) return null;
  const o = v as Record<string, unknown>;
  if (typeof o.videoId !== 'string' || typeof o.title !== 'string' || typeof o.artist !== 'string') return null;
  if (typeof o.durationMs !== 'number') return null;
  return {
    videoId: o.videoId,
    title: o.title,
    artist: o.artist,
    durationMs: o.durationMs,
    thumbnailUrl: typeof o.thumbnailUrl === 'string' ? o.thumbnailUrl : null,
  };
}

export async function searchSongs(
  query: string,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<SearchOutcome> {
  const q = query.trim();
  if (q.length === 0 || q.length > MAX_QUERY_LENGTH) return { ok: false, reason: 'invalid' };
  let response: Response;
  try {
    response = await fetchImpl(`/api/search?q=${encodeURIComponent(q)}`, {
      signal,
      headers: { Accept: 'application/json' },
    });
  } catch {
    return { ok: false, reason: 'failed' };
  }
  if (response.status === 400) return { ok: false, reason: 'invalid' };
  if (response.status === 429) return { ok: false, reason: 'rate-limited' };
  if (response.status === 503) return { ok: false, reason: 'busy' };
  if (!response.ok) return { ok: false, reason: 'failed' };
  try {
    const body: unknown = await response.json();
    if (!Array.isArray(body)) return { ok: false, reason: 'failed' };
    return { ok: true, results: body.map(parseResult).filter((r): r is SearchResult => r !== null) };
  } catch {
    return { ok: false, reason: 'failed' };
  }
}

/** The URL when it is an https URL of at most 512 chars (what the server accepts), else null. */
export function httpsThumbnail(thumb: string | null): string | null {
  if (!thumb || !thumb.startsWith('https://') || thumb.length > MAX_THUMBNAIL_URL_LENGTH) return null;
  try {
    return new URL(thumb).protocol === 'https:' ? thumb : null;
  } catch {
    return null;
  }
}

/** An https thumbnail on a YouTube image host (search results), else null. */
export function validThumbnail(thumb: string | null): string | null {
  if (!httpsThumbnail(thumb)) return null;
  const host = new URL(thumb!).hostname.toLowerCase();
  return host === 'i.ytimg.com' || THUMBNAIL_HOST_SUFFIXES.some((s) => host.endsWith(s)) ? thumb : null;
}

/**
 * A search result as an Add entry that passes the server's validation: text trimmed to 300
 * chars, thumbnails dropped unless https, at most 512 chars and on a YouTube image host,
 * duration within 0..24 h. Null when the videoId is unusable or the title has nothing visible.
 */
export function toNewEntry(result: SearchResult): NewEntry | null {
  if (!VIDEO_ID.test(result.videoId)) return null;
  const title = result.title.trim().slice(0, MAX_TEXT_LENGTH);
  // Same verdict as the server's hasVisible(cleanText()): a title of invisible characters is refused.
  if (!hasVisibleText(title)) return null;
  const rounded = Number.isFinite(result.durationMs) ? Math.round(result.durationMs) : 0;
  return {
    videoId: result.videoId,
    title,
    artist: result.artist.trim().slice(0, MAX_TEXT_LENGTH),
    thumbnailUrl: validThumbnail(result.thumbnailUrl),
    durationMs: Math.min(Math.max(0, rounded), MAX_DURATION_MS),
    kind: 'YOUTUBE_MUSIC',
    url: null,
  };
}
