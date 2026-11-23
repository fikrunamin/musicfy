import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./status.css', import.meta.url), 'utf8');
const base = readFileSync(new URL('./base.css', import.meta.url), 'utf8');
const imports = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');

/** The body of the first `@media <query> { … }` block (balanced braces). */
function mediaBlock(source: string, query: string): string | null {
  const start = source.indexOf(`@media ${query}`);
  if (start < 0) return null;
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(open + 1, i);
  }
  return null;
}

describe('status.css', () => {
  it('is imported', () => {
    expect(imports).toContain("@import './styles/status.css';");
  });

  it('turns the connecting ring only when motion is allowed', () => {
    const allowed = mediaBlock(css, '(prefers-reduced-motion: no-preference)');
    expect(allowed).not.toBeNull();
    expect(allowed).toMatch(/\.status-ring\s*\{[^}]*animation:\s*status-spin\b/);
    // Outside that block nothing animates: reduced motion leaves a still ring.
    const outside = css.replace(`@media (prefers-reduced-motion: no-preference) {${allowed}}`, '');
    expect(outside).not.toMatch(/animation(-name)?\s*:/);
    // And the global reset still switches every animation off.
    expect(mediaBlock(base, '(prefers-reduced-motion: reduce)')).toMatch(/animation:\s*none\s*!important/);
  });
});
