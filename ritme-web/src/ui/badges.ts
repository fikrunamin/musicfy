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

export function memberName(members: { id: string; name: string }[], id: string, me: string | null): string {
  if (id === me) return t.you;
  return members.find((m) => m.id === id)?.name ?? '—';
}

export const pageIsHttps = (): boolean => typeof location !== 'undefined' && location.protocol === 'https:';
