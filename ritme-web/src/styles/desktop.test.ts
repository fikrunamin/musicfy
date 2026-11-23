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

  it('never gives a column a property that would trap the fixed confirm dialog inside it', () => {
    expect(css).not.toMatch(/backdrop-filter|transform\s*:|contain\s*:|will-change/);
  });
});
