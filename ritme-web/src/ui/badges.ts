import type { QueueEntry } from '../protocol';
import { playability } from '../player/playable';
import { t } from './strings';

/** Why this browser will not play an entry, or null when it can. */
export function unplayableLabel(entry: QueueEntry, unplayable: Readonly<Record<string, true>>, pageIsHttps: boolean): string | null {
  const verdict = playability(entry, pageIsHttps);
  if (!verdict.playable) return verdict.reason === 'insecure' ? t.insecure : t.unsupported;
  return unplayable[entry.entryId] ? t.unplayableWeb : null;
}

export function memberName(members: { id: string; name: string }[], id: string, me: string | null): string {
  if (id === me) return t.you;
  return members.find((m) => m.id === id)?.name ?? '—';
}

export const pageIsHttps = (): boolean => typeof location !== 'undefined' && location.protocol === 'https:';
