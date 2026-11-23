/**
 * TypeScript port of ritme-server RoomLogic.cleanText/hasVisible (also copied in the app's
 * RitmeEntries.hasVisibleText). The server refuses a whole Add whose title has nothing visible
 * after cleaning, so clients check titles the same way before sending.
 */

/** C0/C1 controls, lone surrogates, bidi embeddings/overrides/isolates, U+200B, U+FEFF, U+2028/2029. */
const INVISIBLE = /[\p{Cc}\p{Cs}\u200B\u202A-\u202E\u2066-\u2069\uFEFF\u2028\u2029]/gu;

/**
 * A code point the server counts as visible: not whitespace (Java Character.isWhitespace) and not
 * of type Cf, Mn, Me, Zs or Cc, and not a blank letter (Hangul fillers U+115F, U+1160, U+3164,
 * U+FFA0, blank braille U+2800).
 */
const VISIBLE = /[^\p{Cf}\p{Mn}\p{Me}\p{Zs}\p{Zl}\p{Zp}\p{Cc}\u115F\u1160\u3164\uFFA0\u2800]/u;

/** NFC, without the invisible characters above, trimmed (what the server stores). */
export function cleanText(raw: string): string {
  return raw.normalize('NFC').replace(INVISIBLE, '').trim();
}

/** Same verdict as the server's `hasVisible(cleanText(text))`. */
export function hasVisibleText(text: string): boolean {
  return VISIBLE.test(cleanText(text));
}
