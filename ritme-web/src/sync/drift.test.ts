import { describe, expect, it } from 'vitest';
import { shouldSeek } from './drift';

describe('shouldSeek', () => {
  const base = { expectedMs: 10_000, nowMs: 100_000, lastSeekAtMs: null };

  it('ignores drift up to 300 ms either way', () => {
    expect(shouldSeek({ ...base, actualMs: 10_300 })).toBe(false);
    expect(shouldSeek({ ...base, actualMs: 9_700 })).toBe(false);
  });

  it('seeks beyond 300 ms ahead or behind', () => {
    expect(shouldSeek({ ...base, actualMs: 10_301 })).toBe(true);
    expect(shouldSeek({ ...base, actualMs: 9_699 })).toBe(true);
  });

  it('waits at least 5 s between seeks', () => {
    expect(shouldSeek({ ...base, actualMs: 12_000, lastSeekAtMs: 96_000 })).toBe(false);
    expect(shouldSeek({ ...base, actualMs: 12_000, lastSeekAtMs: 95_000 })).toBe(true);
  });

  it('force skips the interval but not the threshold', () => {
    expect(shouldSeek({ ...base, actualMs: 12_000, lastSeekAtMs: 99_900, force: true })).toBe(true);
    expect(shouldSeek({ ...base, actualMs: 10_100, lastSeekAtMs: null, force: true })).toBe(false);
  });
});
