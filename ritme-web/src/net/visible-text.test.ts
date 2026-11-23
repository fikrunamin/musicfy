import { describe, expect, it } from 'vitest';
import { cleanText, hasVisibleText } from './visible-text';

// Same cases as app RitmeEntriesTest.hasVisibleTextMirrorsTheServer (ritme-server RoomLogic).
describe('hasVisibleText (server hasVisible(cleanText()))', () => {
  it.each([
    ['Never Gonna', true],
    ['\u200B\u200Dx', true],
    ['\u{1F3B5}', true],
    ['', false],
    ['   \t\n', false],
    ['\u0301\u0302', false], // combining marks only
    ['\u20DD', false], // enclosing mark only
    ['\u3164\u115F\u1160\uFFA0', false], // Hangul fillers only
    ['\u2800', false], // blank braille
    ['\u200D\u200C\u00AD\u2060', false], // format characters only
    ['\u00A0\u2007\u202F\u3000', false], // non-breaking and ideographic spaces
    ['\u2028\u2029\uFEFF', false],
    ['\uD83C', false], // a lone surrogate is removed by cleanText
    ['\u202Eabc', true],
  ])('%j -> %s', (text, visible) => {
    expect(hasVisibleText(text)).toBe(visible);
  });
});

describe('cleanText', () => {
  it('normalizes to NFC, drops invisible characters and trims like the server', () => {
    expect(cleanText('  e\u0301\u200B\u202E\uFEFFx\n ')).toBe('\u00E9x');
    expect(cleanText('a\u200Db')).toBe('a\u200Db');
  });
});
