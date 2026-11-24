import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { MAX_MEDIA_URL_LENGTH, checkMediaUrl, checkReferrer } from './media-url-rules';

const FIXTURE = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/media-urls.json', import.meta.url));
const { cases } = JSON.parse(readFileSync(FIXTURE, 'utf8')) as {
  cases: { url: string; reject: string | null; padTo?: number }[];
};

/** `padTo`: append "a" until the url is that many UTF-16 code units long (Kotlin padEnd). */
function urlOf(c: { url: string; padTo?: number }): string {
  return c.padTo === undefined ? c.url : c.url.padEnd(c.padTo, 'a');
}

describe('checkMediaUrl (same fixture as MediaUrlRulesTest.kt)', () => {
  it('has the shared cases', () => {
    expect(cases).toHaveLength(120);
  });

  for (const c of cases) {
    const label = c.padTo === undefined ? JSON.stringify(c.url) : `${JSON.stringify(c.url)} padded to ${c.padTo}`;
    it(`${label} -> ${c.reject ?? 'accepted'}`, () => {
      expect(checkMediaUrl(urlOf(c))).toBe(c.reject);
    });
  }

  it('caps the length at 2048 characters', () => {
    const base = 'https://cdn.example.com/';
    expect(checkMediaUrl(base + 'a'.repeat(MAX_MEDIA_URL_LENGTH - base.length))).toBeNull();
    expect(checkMediaUrl(base + 'a'.repeat(MAX_MEDIA_URL_LENGTH - base.length + 1))).toBe('TOO_LONG');
  });

  it('refuses exactly the characters of Kotlin MediaUrlRules.isForbiddenCharacter (whole BMP)', () => {
    // Mirrors the Kotlin list literally, so a drift of the TS regex shows up here.
    const kotlinForbidden = (c: number): boolean =>
      c <= 0x20 ||
      (c >= 0x7f && c <= 0xa0) ||
      c === 0x5c ||
      c === 0x1680 ||
      (c >= 0x2000 && c <= 0x200a) ||
      c === 0x2028 ||
      c === 0x2029 ||
      c === 0x202f ||
      c === 0x205f ||
      c === 0x3000 ||
      c === 0xfeff;
    for (let c = 0; c <= 0xffff; c++) {
      if (c >= 0xd800 && c <= 0xdfff) continue;
      const verdict = checkMediaUrl(`https://cdn.example.com/a${String.fromCharCode(c)}b`);
      if (kotlinForbidden(c)) expect(verdict, `U+${c.toString(16)}`).toBe('FORBIDDEN_CHARACTER');
      else expect(verdict, `U+${c.toString(16)}`).not.toBe('FORBIDDEN_CHARACTER');
    }
  });
});

const REFERRERS = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/referrers.json', import.meta.url));
const referrerCases = (
  JSON.parse(readFileSync(REFERRERS, 'utf8')) as { cases: { referrer: string; reject: string | null; padTo?: number }[] }
).cases;

describe('checkReferrer (same fixture as ReferrerRulesTest.kt)', () => {
  it('has the shared cases', () => {
    expect(referrerCases).toHaveLength(20);
  });

  for (const c of referrerCases) {
    const value = c.padTo === undefined ? c.referrer : c.referrer.padEnd(c.padTo, 'a');
    const label = c.padTo === undefined ? JSON.stringify(c.referrer) : `${JSON.stringify(c.referrer)} padded to ${c.padTo}`;
    it(`${label} -> ${c.reject ?? 'accepted'}`, () => {
      expect(checkReferrer(value)).toBe(c.reject);
    });
  }
});
