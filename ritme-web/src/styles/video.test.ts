import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./video.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const imports = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');

/** The declarations of the first rule whose selector list is exactly `selector` (whitespace-insensitive). */
function rule(selector: string): string | null {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+');
  const match = new RegExp(`(?:^|[}\\n])\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css);
  return match ? match[1] : null;
}

describe('video.css', () => {
  it('is imported after desktop.css, whose stage rules it refines', () => {
    expect(imports).toContain("@import './styles/video.css';");
    expect(imports.indexOf('video.css')).toBeGreaterThan(imports.indexOf('desktop.css'));
  });

  it('takes a listening phone video full-bleed: the gutter given back, no radius (390 x 220)', () => {
    const bleed = rule('.room:not(.room-desktop) .np-listen.np-video:not(.np-full) .stage');
    expect(bleed).toMatch(/margin-inline:\s*calc\(-1 \* var\(--gutter\)\)/);
    expect(bleed).toMatch(/border-radius:\s*0/);
  });

  it('cuts video titles to 2 lines at 20 px on a phone', () => {
    const title = rule('.np-video .np-title');
    expect(title).toMatch(/font-size:\s*var\(--fs-section\)/);
    expect(title).toMatch(/-webkit-line-clamp:\s*2/);
  });

  it('lays the fullscreen section out as the video and a 151-280 px side bar beside it', () => {
    const full = rule('.room .now-playing.np-full');
    expect(full).toMatch(/--side:\s*clamp\(151px,\s*calc\(100vw - 100vh \* 16 \/ 9\),\s*280px\)/);
    expect(full).toMatch(/display:\s*grid/);
    // The stage has a column of its own; nothing else shares its cell.
    expect(full).toMatch(/grid-template-areas:[^;]*"stage \. status status status \."/);
    expect(rule('.room .now-playing.np-full .stage')).toMatch(/grid-area:\s*stage/);
  });

  it('gives the theater a 340 px right column that the toggle can fold away', () => {
    expect(rule('.room-theater')).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)\s+340px/);
    expect(rule('.room-collapsed')).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)/);
    expect(rule('.room-desktop .room-col[hidden]')).toMatch(/display:\s*none/);
    expect(rule('.room-theater .stage')).toMatch(/border-radius:\s*var\(--r-stage\)/);
  });

  it('puts the tap hint and the room notice in the fullscreen side bar, and keeps the app toast out of the way there', () => {
    const full = rule('.room .now-playing.np-full');
    expect(full).toMatch(/grid-template-areas:[^;]*"stage \. hint hint hint \."/);
    expect(rule('.room .now-playing.np-full .np-hint')).toMatch(/grid-area:\s*hint/);
    expect(css).not.toMatch(/np-full \.np-hint,/);
    expect(rule('.np-fs-status .toast-region')).toMatch(/position:\s*static/);
    expect(rule('main:has(:fullscreen) ~ .toast-region')).toMatch(/display:\s*none/);
  });

  it('gives a live song the progress row in fullscreen', () => {
    expect(rule('.room .now-playing.np-full .np-progress,\n.room .now-playing.np-full .np-live')).toMatch(/grid-area:\s*prog/);
  });

  it('separates the source from "oleh" with one even gap', () => {
    expect(rule('.np-src::after')).toMatch(/content:\s*' · '/);
    expect(rule('.np-badges')).toMatch(/column-gap:\s*0/);
  });

  it('keeps the theater meta clear of the stage when there is no progress row (LIVE)', () => {
    expect(rule('.room-theater .now-playing:not(:has(.np-progress)) .np-meta')).toMatch(/margin-top:\s*16px/);
  });

  it('shows the folded state on the column toggle', () => {
    expect(rule(".room-side-toggle[aria-expanded='false']")).toBeTruthy();
  });

  it('lets focus follow the visual order of the theater header and the fullscreen bar', () => {
    expect(rule('.room-theater .room-left')).toMatch(/reading-flow:\s*grid-rows/);
    expect(rule('.room .now-playing.np-full')).toMatch(/reading-flow:\s*grid-rows/);
  });

  it('puts the one fullscreen button in the empty fifth slot on YouTube, and on a row of its own after +10 s', () => {
    expect(rule('.np-controls > .np-slot:nth-child(5):empty')).toMatch(/display:\s*none/);
    expect(rule('.np-slot:not(:empty) + .np-fs')).toMatch(/margin:\s*8px 0 0 calc\(100% - var\(--target\)\)/);
  });

  it('moves things with grid placement only: no transform, filter or perspective anywhere (sheets live in the room)', () => {
    expect(css).not.toMatch(/(^|[\s;{])(transform|translate|scale|rotate|perspective|filter|backdrop-filter|contain|will-change)\s*:/);
  });
});
