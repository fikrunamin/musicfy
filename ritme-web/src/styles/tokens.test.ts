import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');
const [darkBlock, lightBlock] = [css.slice(0, css.indexOf('@media (prefers-color-scheme: light)')), css.slice(css.indexOf('@media (prefers-color-scheme: light)'))];
const value = (block: string, name: string) => new RegExp(`--${name}:\\s*([^;]+);`).exec(block)?.[1].trim().toLowerCase();

/** The handoff token table ("Token warna"): name, dark, light. */
const TABLE: [string, string, string][] = [
  ['bg', '#000000', '#ffffff'],
  ['surface', '#1a1a1a', '#f0f0f0'],
  ['surface2', '#2c2c2c', '#e0e0e0'],
  ['surface3', '#333333', '#cccccc'],
  ['text', '#ffffff', '#000000'],
  ['muted', '#ffffff99', '#00000099'],
  ['faint', '#ffffff1f', '#0000001a'],
  ['accent', '#e0e0e0', '#000000'],
  ['on-accent', '#000000', '#ffffff'],
  ['danger', '#ff5a4e', '#d93025'],
  ['overlay', '#000000b3', '#ffffffc7'],
];

describe('design tokens', () => {
  it.each(TABLE)('--%s is %s dark and %s light', (name, dark, light) => {
    expect(value(darkBlock, name)).toBe(dark);
    expect(value(lightBlock, name)).toBe(light);
  });

  it('has the handoff radii, fixed placeholder colours and touch sizes', () => {
    expect(value(darkBlock, 'r-pill')).toBe('999px');
    expect(value(darkBlock, 'r-card')).toBe('24px');
    expect(value(darkBlock, 'r-stage')).toBe('20px');
    expect(value(darkBlock, 'r-input')).toBe('16px');
    expect(value(darkBlock, 'r-thumb')).toBe('8px');
    expect(value(darkBlock, 'r-sheet')).toBe('28px');
    expect(value(darkBlock, 'placeholder')).toBe('#161616');
    expect(value(darkBlock, 'stage-error')).toBe('#0d0d0d');
    expect(value(darkBlock, 'btn-h')).toBe('52px');
    expect(value(darkBlock, 'row-h')).toBe('60px');
    expect(value(darkBlock, 'target')).toBe('44px');
    expect(value(darkBlock, 'gutter')).toBe('16px');
  });

  it('has the handoff type scale, with the 96 px page title on desktop only', () => {
    const scale = { display: '44px', title: '28px', section: '20px', header: '17px', body: '15px', meta: '13px', caption: '12px' };
    for (const [name, size] of Object.entries(scale)) expect(value(darkBlock, `fs-${name}`)).toBe(size);
    const desktop = css.slice(css.indexOf('@media (min-width: 1024px)'));
    expect(css.indexOf('@media (min-width: 1024px)')).toBeGreaterThan(0);
    expect(value(desktop, 'fs-display')).toBe('96px');
    expect(value(lightBlock.replace(desktop, ''), 'fs-display')).toBeUndefined();
  });
});
