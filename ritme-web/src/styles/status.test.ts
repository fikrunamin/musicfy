import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./status.css', import.meta.url), 'utf8');
const base = readFileSync(new URL('./base.css', import.meta.url), 'utf8');
const motion = readFileSync(new URL('./motion.css', import.meta.url), 'utf8');
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
    // The spin lives with the rest of the motion (motion.css), behind the same media query.
    const allowed = mediaBlock(motion, '(prefers-reduced-motion: no-preference)');
    expect(allowed).not.toBeNull();
    expect(allowed).toMatch(/\.status-ring\s*\{[^}]*animation:\s*status-spin\b/);
    expect(motion).toMatch(/@keyframes status-spin\s*\{[^@]*rotate\(45deg\)[^@]*rotate\(405deg\)/);
    // status.css itself never animates: reduced motion leaves a still ring.
    expect(css).not.toMatch(/animation(-name)?\s*:|@keyframes/);
    // And the global reset still switches every animation off.
    expect(mediaBlock(base, '(prefers-reduced-motion: reduce)')).toMatch(/animation:\s*none\s*!important/);
  });
});

describe('the reconnecting banner (room.css)', () => {
  const room = readFileSync(new URL('./room.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const decls = (selector: string) => new RegExp(`(?:^|[}\\n])\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(room)?.[1] ?? null;

  it('never sits over the stage: it scrolls away in listen mode and sticks to the top only in remote mode', () => {
    expect(decls('.reconnect-banner')).not.toMatch(/position:\s*(sticky|fixed|absolute)/);
    expect(decls('.reconnect-sticky')).toMatch(/position:\s*sticky/);
  });
});
