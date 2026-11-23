/**
 * Display-only decoding of IDN hosts (`xn--bcher-kva.example` -> `bücher.example`). Links and
 * entries always carry the punycode form; only text shown to people is decoded. Like browsers'
 * address bars, a label stays punycode when decoding fails, when it decodes to anything but
 * letters, marks, digits and hyphens (emoji, symbols, invisible characters), or when it mixes
 * Latin with Cyrillic or Greek (the common homograph trick).
 */

const BASE = 36;
const T_MIN = 1;
const T_MAX = 26;
const SKEW = 38;
const DAMP = 700;
const INITIAL_BIAS = 72;
const INITIAL_N = 128;
const MAX_CODE_POINT = 0x10ffff;

function adapt(delta: number, numPoints: number, firstTime: boolean): number {
  let d = firstTime ? Math.floor(delta / DAMP) : delta >> 1;
  d += Math.floor(d / numPoints);
  let k = 0;
  while (d > ((BASE - T_MIN) * T_MAX) >> 1) {
    d = Math.floor(d / (BASE - T_MIN));
    k += BASE;
  }
  return k + Math.floor(((BASE - T_MIN + 1) * d) / (d + SKEW));
}

function digitOf(c: number): number {
  if (c >= 0x30 && c <= 0x39) return c - 0x30 + 26; // 0-9
  if (c >= 0x41 && c <= 0x5a) return c - 0x41; // A-Z
  if (c >= 0x61 && c <= 0x7a) return c - 0x61; // a-z
  return BASE;
}

/** RFC 3492 decoding of the part after "xn--"; null when malformed. */
function decodePunycode(input: string): string | null {
  const output: number[] = [];
  const basicEnd = input.lastIndexOf('-');
  for (let j = 0; j < Math.max(basicEnd, 0); j++) {
    const c = input.charCodeAt(j);
    if (c >= 0x80) return null;
    output.push(c);
  }
  let n = INITIAL_N;
  let bias = INITIAL_BIAS;
  let i = 0;
  for (let index = basicEnd > 0 ? basicEnd + 1 : 0; index < input.length; ) {
    const oldI = i;
    let w = 1;
    for (let k = BASE; ; k += BASE) {
      if (index >= input.length) return null;
      const digit = digitOf(input.charCodeAt(index++));
      if (digit >= BASE) return null;
      i += digit * w;
      if (i > MAX_CODE_POINT * 64) return null;
      const t = k <= bias ? T_MIN : k >= bias + T_MAX ? T_MAX : k - bias;
      if (digit < t) break;
      w *= BASE - t;
    }
    const length = output.length + 1;
    bias = adapt(i - oldI, length, oldI === 0);
    n += Math.floor(i / length);
    i %= length;
    if (n > MAX_CODE_POINT || (n >= 0xd800 && n <= 0xdfff)) return null;
    output.splice(i, 0, n);
    i++;
  }
  return String.fromCodePoint(...output);
}

const LABEL_CHARACTERS = /^[\p{L}\p{M}\p{N}-]+$/u;
const LATIN = /\p{Script=Latin}/u;
const CONFUSABLE_WITH_LATIN = /[\p{Script=Cyrillic}\p{Script=Greek}]/u;

function displayLabel(label: string): string {
  if (!/^xn--/i.test(label)) return label;
  const decoded = decodePunycode(label.slice(4));
  if (decoded === null || decoded === '' || !LABEL_CHARACTERS.test(decoded)) return label;
  if (LATIN.test(decoded) && CONFUSABLE_WITH_LATIN.test(decoded)) return label;
  return decoded.normalize('NFC');
}

/** `host` with its punycode labels decoded for display (see the file comment). */
export function displayDomain(host: string): string {
  if (host.startsWith('[')) return host;
  return host.split('.').map(displayLabel).join('.');
}

/** The host of `url` without "www." and a trailing dot, decoded for display; null when unparsable. */
export function displayHostOf(url: string | null): string | null {
  if (!url) return null;
  try {
    const host = new URL(url).hostname;
    return host === '' ? null : displayDomain(host.replace(/\.$/, '').replace(/^www\./, ''));
  } catch {
    return null;
  }
}
