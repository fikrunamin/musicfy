import type { SessionApi } from '../session/session';
import { t } from './strings';

/** The invite link for a room on this site: /r/{code}. */
export function roomLink(code: string): string {
  return `${location.origin}/r/${code}`;
}

/** The same link without its scheme, as the share card shows it ("ritme.dubeli.id/r/K7M2QX"). */
export function roomLinkLabel(code: string): string {
  return `${location.host}/r/${code}`;
}

/**
 * Copies the room link and says so with the "Link disalin" toast. Without a usable clipboard
 * (plain http, permission refused) the link is shown in a prompt to copy by hand.
 */
export async function copyLink(code: string, session: SessionApi): Promise<void> {
  const url = roomLink(code);
  try {
    await navigator.clipboard.writeText(url);
    session.notify('linkCopied');
  } catch {
    window.prompt(t.share, url);
  }
}

/** "Bagikan": the system share sheet where there is one, else the link is copied. */
export async function share(code: string, session: SessionApi): Promise<void> {
  if (navigator.share) {
    try {
      await navigator.share({ title: t.appName, text: t.shareText, url: roomLink(code) });
      return;
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
    }
  }
  await copyLink(code, session);
}
