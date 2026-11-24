import { displayHostOf } from '../net/idn';
import type { QueueEntry } from '../protocol';
import { playability } from '../player/playable';
import { t } from './strings';

/** Why this browser will not play an entry, or null when it can. */
export function unplayableLabel(entry: QueueEntry, unplayable: Readonly<Record<string, true>>, pageIsHttps: boolean): string | null {
  const verdict = playability(entry, pageIsHttps);
  if (!verdict.playable) return verdict.reason === 'insecure' ? t.insecure : t.unsupported;
  return unplayable[entry.entryId] ? t.unplayableWeb : null;
}

/** YouTube videos and direct links: shown with a 16:9 thumbnail and a Video (or LIVE) badge. */
export function isVideoEntry(entry: QueueEntry): boolean {
  return entry.kind === 'YOUTUBE_VIDEO' || entry.kind === 'URL';
}

/**
 * Where an entry comes from, for its meta line: the artist/channel, or for a direct link
 * "dari <domain>" taken from the url itself (not the sender's artist text), IDN decoded.
 */
export function entrySource(entry: QueueEntry): string {
  if (entry.kind !== 'URL') return entry.artist;
  const host = displayHostOf(entry.url);
  return host === null ? entry.artist : t.fromDomain(host);
}

/**
 * The format of a direct link, read from its path (handoff M: "MP4", "HLS · m3u8"); null when the
 * path does not say. The query string never counts: `?file=a.mp4` is not an mp4.
 */
export function mediaFormat(url: string | null): string | null {
  let ext: string | undefined;
  try {
    ext = /\.(mp4|webm|m4v|mov|m3u8)$/i.exec(new URL(url ?? '').pathname)?.[1].toLowerCase();
  } catch {
    return null;
  }
  return !ext ? null : ext === 'm3u8' ? t.hlsFormat : ext.toUpperCase();
}

export function memberName(members: { id: string; name: string }[], id: string, me: string | null): string {
  if (id === me) return t.you;
  return members.find((m) => m.id === id)?.name ?? '—';
}

export const pageIsHttps = (): boolean => typeof location !== 'undefined' && location.protocol === 'https:';
