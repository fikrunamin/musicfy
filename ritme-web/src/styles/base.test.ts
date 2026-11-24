import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const base = read('./base.css');
const entry = read('../styles.css');
const rule = (css: string, selector: string) => new RegExp(`(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';

describe('base styles', () => {
  it('has one stylesheet per concern and no pre-redesign leftovers', () => {
    expect(entry).not.toContain('legacy');
    expect(existsSync(new URL('./legacy.css', import.meta.url))).toBe(false);
  });

  it('hides .visually-hidden text with clip-path, kept in the accessibility tree', () => {
    const hidden = rule(base, '.visually-hidden');
    expect(hidden).toContain('clip-path: inset(50%)');
    expect(hidden).toContain('position: absolute');
    expect(hidden).not.toContain('display: none');
  });

  it('draws a visible 3 px focus ring in the text colour', () => {
    expect(rule(base, ':focus-visible')).toContain('outline: 3px solid var(--text)');
  });
});
