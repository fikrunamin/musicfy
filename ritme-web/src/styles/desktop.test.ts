import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./desktop.css', import.meta.url), 'utf8');
const imports = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');

/** The declarations of the first rule whose selector list is exactly `selector`. */
function rule(selector: string): string | null {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|[}\\n])\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css);
  return match ? match[1] : null;
}

describe('desktop.css', () => {
  it('is imported after the screens it rearranges', () => {
    expect(imports).toContain("@import './styles/desktop.css';");
    expect(imports.indexOf('desktop.css')).toBeGreaterThan(imports.indexOf('members.css'));
  });

  it('leaves the phone layout alone: the column wrappers do not box anything', () => {
    expect(rule('.room-col')).toMatch(/display:\s*contents/);
  });

  it('fills the window with the grid, which never scrolls itself', () => {
    const grid = rule('.room-desktop');
    expect(grid).toMatch(/display:\s*grid/);
    expect(grid).toMatch(/height:\s*100dvh/);
    expect(grid).toMatch(/overflow:\s*hidden/);
  });

  it('makes each column its own scroll container', () => {
    const col = rule('.room-desktop .room-col');
    expect(col).toMatch(/display:\s*flex/);
    expect(col).toMatch(/overflow-y:\s*auto/);
    expect(col).toMatch(/min-height:\s*0/);
  });

  it('caps the stage by the window height, so the transport under it stays on screen', () => {
    // Everything else in the left column has a fixed height; the stage takes what is left of the
    // window (never less than the 200 px the YouTube player needs) and never more than the column.
    const stage = rule('.room-desktop .stage');
    expect(stage).toMatch(/--stage-chrome:\s*calc\(var\(--stage-base\)\s*\+\s*var\(--stage-banner,\s*0px\)\s*\+\s*var\(--stage-extra,\s*0px\)\s*\+\s*var\(--stage-warning,\s*0px\)\)/);
    expect(stage).toMatch(/margin-inline:\s*auto/);
    expect(rule('.room-desktop .room-left')).toMatch(/--stage-base:\s*\d+px/);
    // Square: the width is the height. A vh line first for browsers without dvh.
    const square = rule('.room-desktop .stage-square');
    expect(square).toMatch(/width:\s*min\(100%,\s*max\(200px,\s*calc\(100vh - var\(--stage-chrome\)\)\)\);/);
    expect(square).toMatch(/width:\s*min\(100%,\s*max\(200px,\s*calc\(100dvh - var\(--stage-chrome\)\)\)\);/);
    expect(square!.indexOf('100vh')).toBeLessThan(square!.indexOf('100dvh'));
    // 16:9: the width is 16/9 of the height left, and the height is never below 200 px.
    const wide = rule('.room-desktop .stage-wide');
    expect(wide).toMatch(/width:\s*min\(100%,\s*max\(calc\(200px \* 16 \/ 9\),\s*calc\(\(100vh - var\(--stage-chrome\)\) \* 16 \/ 9\)\)\);/);
    expect(wide).toMatch(/width:\s*min\(100%,\s*max\(calc\(200px \* 16 \/ 9\),\s*calc\(\(100dvh - var\(--stage-chrome\)\) \* 16 \/ 9\)\)\);/);
  });

  it('counts what can appear around the stage: the reconnect banner, the mode or tap hint, the warning', () => {
    expect(rule('.reconnect-banner + .room-desktop .room-left')).toMatch(/--stage-banner:\s*\d+px/);
    // The banner is the room's sibling (so a sheet leaves it audible): the room gives up its height.
    const room = rule('.reconnect-banner + .room-desktop');
    expect(room).toMatch(/height:\s*calc\(100vh - 32px\);/);
    expect(room).toMatch(/height:\s*calc\(100dvh - 32px\);/);
    expect(rule('.reconnect-banner:has(+ .room-desktop)')).toMatch(/height:\s*32px/);
    expect(rule('.room-desktop .np-remote')).toMatch(/--stage-extra:\s*\d+px/);
    expect(rule('.room-desktop .now-playing:has(> .np-hint)')).toMatch(/--stage-extra:\s*\d+px/);
    expect(rule('.room-desktop .now-playing:has(.np-warning)')).toMatch(/--stage-warning:\s*\d+px/);
  });

  it('keeps the title and its source line to one line each, so they never push the transport down', () => {
    const meta = rule('.room-desktop .np-title,\n.room-desktop .np-sub');
    expect(meta).toMatch(/white-space:\s*nowrap/);
    expect(meta).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('leaves the share card to the header\'s "Bagikan" in the side panel', () => {
    expect(rule('.room-side .share-card')).toMatch(/display:\s*none/);
  });

  it('gives the field room next to a compact "Cari" in the inline panel, keeping the 44 px target', () => {
    const cari = rule('.add-panel-inline .add-search .btn');
    expect(cari).toMatch(/padding:\s*0 16px/);
    // Its height stays the .btn's 52 px (level with the field): nothing here makes it smaller.
    expect(cari).not.toMatch(/(?:^|[\s;])(?:min-)?height\s*:/);
  });

  it('never gives a column a property that would trap the fixed confirm dialog inside it', () => {
    expect(css).not.toMatch(/backdrop-filter|transform\s*:|contain\s*:|will-change/);
  });
});
