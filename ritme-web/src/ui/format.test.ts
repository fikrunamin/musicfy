import { describe, expect, it } from 'vitest';
import { codeFromPath, formatMs, normalizeCode, normalizeName } from './format';

describe('format helpers', () => {
  it('formats durations', () => {
    expect(formatMs(7_400)).toBe('0:07');
    expect(formatMs(213_000)).toBe('3:33');
    expect(formatMs(3_723_000)).toBe('1:02:03');
    expect(formatMs(-5)).toBe('0:00');
  });

  it('normalizes room codes', () => {
    expect(normalizeCode(' k7m-2qx ')).toBe('K7M2QX');
    expect(normalizeCode('K7M2Q')).toBeNull();
    expect(normalizeCode('K7M2Q1')).toBeNull(); // 1 is not in the alphabet
  });

  it('validates names', () => {
    expect(normalizeName('  Budi ')).toBe('Budi');
    expect(normalizeName('   ')).toBeNull();
    expect(normalizeName('x'.repeat(33))).toBeNull();
  });

  it('reads the code from /r/{code}', () => {
    expect(codeFromPath('/r/k7m2qx')).toBe('K7M2QX');
    expect(codeFromPath('/r/K7M2QX/')).toBe('K7M2QX');
    expect(codeFromPath('/')).toBeNull();
    expect(codeFromPath('/r/<script>')).toBeNull();
  });
});
