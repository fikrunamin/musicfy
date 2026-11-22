/** 0:07, 3:33, 1:02:03 */
export function formatMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

export const ROOM_CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const CODE = new RegExp(`^[${ROOM_CODE_ALPHABET}]{6}$`);

/** Uppercased, spaces and dashes removed; null unless it is a valid 6-character room code. */
export function normalizeCode(raw: string): string | null {
  const code = raw.replace(/[\s-]/g, '').toUpperCase();
  return CODE.test(code) ? code : null;
}

/** Trimmed display name of 1–32 characters, or null. */
export function normalizeName(raw: string): string | null {
  const name = raw.trim();
  return name.length >= 1 && name.length <= 32 ? name : null;
}

/** The room code in /r/{code}, or null. */
export function codeFromPath(pathname: string): string | null {
  const match = /^\/r\/([^/]+)\/?$/.exec(pathname);
  return match ? normalizeCode(decodeURIComponent(match[1])) : null;
}
