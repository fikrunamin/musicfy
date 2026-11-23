import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { MAX_MEDIA_URL_LENGTH, checkMediaUrl } from './media-url-rules';

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

  it('refuses exactly the characters JS /[\\s\\p{Cc}\\\\]/u matches (the Kotlin list)', () => {
    const forbidden = /[\s\p{Cc}\\]/u;
    for (let c = 0; c <= 0xffff; c++) {
      if (c >= 0xd800 && c <= 0xdfff) continue;
      const ch = String.fromCharCode(c);
      const verdict = checkMediaUrl(`https://cdn.example.com/a${ch}b`);
      if (forbidden.test(ch)) expect(verdict, `U+${c.toString(16)}`).toBe('FORBIDDEN_CHARACTER');
      else expect(verdict, `U+${c.toString(16)}`).not.toBe('FORBIDDEN_CHARACTER');
    }
  });
});
