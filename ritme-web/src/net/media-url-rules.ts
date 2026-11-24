/**
 * TypeScript twin of modules/ritme-protocol MediaUrlRules.kt (spec 3.2): the shape a URL entry's
 * url must have for the server to accept it. Pure string checks; nothing is resolved, so a public
 * DNS name that points at a private address passes (D6; see README "URL entries"). Both sides are
 * tested against modules/ritme-protocol/fixtures/rules/media-urls.json, so change them together.
 */

export const MAX_MEDIA_URL_LENGTH = 2048;
/** Kotlin MediaUrlRules.MAX_REFERRER_LENGTH. */
export const MAX_REFERRER_LENGTH = 200;

export type MediaUrlRejection =
  | 'TOO_LONG'
  /** A C0/C1 control character, a backslash, Unicode whitespace or U+FEFF anywhere. */
  | 'FORBIDDEN_CHARACTER'
  /** A `#fragment`: clients strip it before sending. */
  | 'FRAGMENT'
  | 'MALFORMED'
  /** Anything but http/https. */
  | 'SCHEME'
  /** `user:pass@`: credentials would be broadcast to the room. */
  | 'USERINFO'
  | 'NO_HOST'
  /** A numeric host that is not a dotted quad (`2130706433`, `0x7f.0.0.1`, `127.1`, `010.0.0.1`). */
  | 'NON_CANONICAL_IP'
  /** Loopback, private, link-local, CGNAT, unspecified, multicast or reserved addresses, and local names. */
  | 'LOCAL_HOST'
  /** A referrer with a path, a query or no trailing slash: only `scheme://host[:port]/`. */
  | 'NOT_ORIGIN';

/** Exactly the Kotlin isForbiddenCharacter list (C0, space, DEL, C1, Unicode White_Space, U+FEFF, backslash). */
const FORBIDDEN_CHARACTER = /[\s\p{Cc}\\]/u;
const SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):/;
const PORT = /^:([0-9]{1,5})?$/;
const HOST_NAME = /^[a-z0-9_-]+(\.[a-z0-9_-]+)*\.?$/;
const NUMERIC_LABEL = /^(0x[0-9a-f]*|[0-9]+)$/;
const IPV4_PART = /^(0|[1-9][0-9]{0,2})$/;
const IPV6_CHARS = /^[0-9a-f:.]+$/;
const LOCAL_NAMES = new Set(['home.arpa', 'localdomain', 'localdomain4', 'localdomain6']);
/** `*.localdomain` too: Linux resolves `localhost.localdomain` (and RHEL `localhost4.localdomain4`) to loopback. */
const LOCAL_SUFFIXES = [
  '.localhost',
  '.local',
  '.internal',
  '.lan',
  '.home.arpa',
  '.localdomain',
  '.localdomain4',
  '.localdomain6',
];

/** Why `url` is refused, or null when it is acceptable. */
export function checkMediaUrl(url: string): MediaUrlRejection | null {
  if (url.length > MAX_MEDIA_URL_LENGTH) return 'TOO_LONG';
  if (FORBIDDEN_CHARACTER.test(url)) return 'FORBIDDEN_CHARACTER';
  if (url.includes('#')) return 'FRAGMENT';
  const match = SCHEME.exec(url);
  if (!match) return 'MALFORMED';
  const scheme = match[1].toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return 'SCHEME';
  const rest = url.slice(match[1].length + 1);
  if (!rest.startsWith('//')) return 'MALFORMED';
  const authority = /^[^/?]*/.exec(rest.slice(2))![0];
  if (authority.includes('@')) return 'USERINFO';
  const host = hostOf(authority);
  if (host === null) return 'MALFORMED';
  if (host === '') return 'NO_HOST';
  return hostRejection(host);
}

/** The host part of `authority` (an IPv6 literal keeps its brackets), lowercased; null when malformed. */
function hostOf(authority: string): string | null {
  let hostEnd: number;
  if (authority.startsWith('[')) {
    const close = authority.indexOf(']');
    if (close < 0) return null;
    hostEnd = close + 1;
  } else {
    const colon = authority.lastIndexOf(':');
    hostEnd = colon < 0 ? authority.length : colon;
  }
  if (hostEnd < authority.length) {
    const port = PORT.exec(authority.slice(hostEnd));
    if (!port) return null;
    if (port[1] !== undefined && Number(port[1]) > 65_535) return null;
  }
  return authority.slice(0, hostEnd).toLowerCase();
}

function hostRejection(host: string): MediaUrlRejection | null {
  if (host.startsWith('[')) {
    const bytes = parseIpv6(host.slice(1, -1));
    if (!bytes) return 'MALFORMED';
    return isLocalIpv6(bytes) ? 'LOCAL_HOST' : null;
  }
  if (!HOST_NAME.test(host)) return 'MALFORMED';
  const name = host.endsWith('.') ? host.slice(0, -1) : host;
  if (NUMERIC_LABEL.test(name.slice(name.lastIndexOf('.') + 1))) {
    const v4 = parseIpv4(name);
    if (!v4) return 'NON_CANONICAL_IP';
    return isLocalIpv4(v4) ? 'LOCAL_HOST' : null;
  }
  // Single-label names resolve through the LAN's search domain (router, nas, printer).
  if (!name.includes('.') || LOCAL_NAMES.has(name) || LOCAL_SUFFIXES.some((s) => name.endsWith(s))) return 'LOCAL_HOST';
  return null;
}

/** Four decimal parts 0-255 without leading zeros, or null. */
function parseIpv4(text: string): number[] | null {
  const parts = text.split('.');
  if (parts.length !== 4 || parts.some((p) => !IPV4_PART.test(p))) return null;
  const bytes = parts.map(Number);
  return bytes.every((b) => b <= 255) ? bytes : null;
}

/**
 * 0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12, 192.0.0/24 (IETF protocol assignments),
 * 192.168/16, 198.18/15 (benchmarking, also the fake-IP range of Clash/Surge proxies), and
 * 224/4 + 240/4 (multicast, reserved, broadcast).
 */
function isLocalIpv4(a: number[]): boolean {
  return (
    a[0] === 0 ||
    a[0] === 10 ||
    a[0] === 127 ||
    a[0] >= 224 ||
    (a[0] === 100 && a[1] >= 64 && a[1] <= 127) ||
    (a[0] === 169 && a[1] === 254) ||
    (a[0] === 172 && a[1] >= 16 && a[1] <= 31) ||
    (a[0] === 192 && a[1] === 168) ||
    (a[0] === 192 && a[1] === 0 && a[2] === 0) ||
    (a[0] === 198 && a[1] >= 18 && a[1] <= 19)
  );
}

/** 16 bytes, or null. Accepts `::` and a dotted IPv4 tail; no zone ids. */
function parseIpv6(text: string): number[] | null {
  if (text === '' || !IPV6_CHARS.test(text)) return null;
  const halves = text.split('::');
  if (halves.length > 2 || (halves.length === 2 && halves[0].includes('.'))) return null;
  const head = ipv6Words(halves[0]);
  if (!head) return null;
  const tail = halves.length === 2 ? ipv6Words(halves[1]) : [];
  if (!tail) return null;
  const missing = 8 - head.length - tail.length;
  const fits = halves.length === 2 ? missing >= 1 : missing === 0;
  if (!fits) return null;
  const words = [...head, ...new Array<number>(missing).fill(0), ...tail];
  const bytes: number[] = [];
  for (const w of words) bytes.push(w >> 8, w & 0xff);
  return bytes;
}

function ipv6Words(part: string): number[] | null {
  if (part === '') return [];
  const pieces = part.split(':');
  const words: number[] = [];
  for (let i = 0; i < pieces.length; i++) {
    const piece = pieces[i];
    if (i === pieces.length - 1 && piece.includes('.')) {
      const v4 = parseIpv4(piece);
      if (!v4) return null;
      words.push((v4[0] << 8) | v4[1], (v4[2] << 8) | v4[3]);
    } else {
      if (piece === '' || piece.length > 4 || piece.includes('.')) return null;
      words.push(parseInt(piece, 16));
    }
  }
  return words;
}

function isLocalIpv6(b: number[]): boolean {
  const zero = (from: number, to: number) => b.slice(from, to).every((x) => x === 0);
  const embeddedV4 = b.slice(12, 16);
  const nat64Prefix = b[0] === 0x00 && b[1] === 0x64 && b[2] === 0xff && b[3] === 0x9b;
  // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
  if (zero(0, 12)) return true;
  // IPv4-mapped ::ffff:a.b.c.d
  if (zero(0, 10) && b[10] === 0xff && b[11] === 0xff) return isLocalIpv4(embeddedV4);
  // SIIT IPv4-translated ::ffff:0:a.b.c.d
  if (zero(0, 8) && b[8] === 0xff && b[9] === 0xff && b[10] === 0 && b[11] === 0) return isLocalIpv4(embeddedV4);
  // NAT64 64:ff9b::/96
  if (nat64Prefix && zero(4, 12)) return isLocalIpv4(embeddedV4);
  // Local-use NAT64 64:ff9b:1::/48 (RFC 8215): a local translator by definition
  if (nat64Prefix && b[4] === 0x00 && b[5] === 0x01) return true;
  // 6to4 2002:a.b.c.d::/48
  if (b[0] === 0x20 && b[1] === 0x02) return isLocalIpv4(b.slice(2, 6));
  // fc00::/7 unique local
  if ((b[0] & 0xfe) === 0xfc) return true;
  // fe80::/10 link-local and fec0::/10 site-local
  if (b[0] === 0xfe && b[1] >= 0x80) return true;
  // ff00::/8 multicast
  return b[0] === 0xff;
}

/**
 * Kotlin MediaUrlRules.checkReferrer: why a URL entry's referrer is refused, or null for a bare
 * http(s) origin with a trailing slash (`https://host[:port]/`), at most 200 chars, under the same
 * host rules as checkMediaUrl. The web client never sends one; this keeps the twins in step.
 */
export function checkReferrer(referrer: string): MediaUrlRejection | null {
  if (referrer.length > MAX_REFERRER_LENGTH) return 'TOO_LONG';
  const rejection = checkMediaUrl(referrer);
  if (rejection !== null) return rejection;
  const afterScheme = referrer.slice(referrer.indexOf('://') + 3);
  const end = afterScheme.search(/[/?]/);
  const authority = end < 0 ? afterScheme : afterScheme.slice(0, end);
  return afterScheme.slice(authority.length) === '/' ? null : 'NOT_ORIGIN';
}
