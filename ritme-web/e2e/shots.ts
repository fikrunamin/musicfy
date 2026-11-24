import type { BrowserContext, Page, TestInfo } from '@playwright/test';
import type { CannedSong } from './fixtures';

/** Where a screen's PNG goes: test-results/redesign/<SHOTS_SET or "after">/<project>/<name>.png. */
export function shotPath(info: TestInfo, name: string): string {
  return `test-results/redesign/${process.env.SHOTS_SET ?? 'after'}/${info.project.name}/${name}.png`;
}

/** Controls the touch-target audit measures (role-carrying elements included). */
const CONTROLS =
  'button, a[href], input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="tab"], [role="switch"], [role="slider"], [role="checkbox"], [role="radio"], [role="menuitem"]';

/**
 * Soft touch-target audit (spec: every target ≥ 44 × 44): lists each visible control smaller than
 * that. Inline text links (an `<a>` laid out inline in a sentence) are exempt, and so are controls
 * behind an open modal (inert) and visually hidden ones. It never fails the shot: what it finds is
 * a console warning and a `touch-target` annotation on the test, for the reviewer to judge.
 */
export async function auditTouchTargets(page: Page, info: TestInfo, name: string): Promise<string[]> {
  let small: string[];
  try {
    small = await page.evaluate((selector) => {
      const found: string[] = [];
      for (const el of document.querySelectorAll<HTMLElement>(selector)) {
        if (el.closest('[hidden], [inert], .visually-hidden')) continue;
        const style = getComputedStyle(el);
        if (style.visibility === 'hidden' || style.display === 'none') continue;
        if (el.tagName === 'A' && style.display === 'inline') continue; // a text link inside a sentence
        const { width, height } = el.getBoundingClientRect();
        if (width === 0 || height === 0 || (width >= 44 && height >= 44)) continue;
        const label = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 40);
        const cls = typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/).join('.')}` : '';
        found.push(`${el.tagName.toLowerCase()}${cls} "${label}" ${Math.round(width)}×${Math.round(height)}`);
      }
      return found;
    }, CONTROLS);
  } catch (error) {
    console.warn(`[touch-target] ${info.project.name}/${name}: audit skipped (${String(error)})`);
    return [];
  }
  if (small.length > 0) {
    const description = `${info.project.name}/${name}: ${small.length} control(s) under 44×44: ${small.join('; ')}`;
    console.warn(`[touch-target] ${description}`);
    info.annotations.push({ type: 'touch-target', description });
  }
  return small;
}

/**
 * Full-page screenshot once fonts and images settled. The flow runs with motion; for the capture
 * itself the page switches to reduced motion, so endless motion (marquee, equalizer, sync pulse,
 * spinner) rests in its still state and every PNG is deterministic. Motion comes back afterwards.
 * `fullPage: false` for a fullscreen element, which covers the window, not the page. Each shot
 * also runs the soft touch-target audit above.
 */
export async function shot(page: Page, info: TestInfo, name: string, { fullPage = true }: { fullPage?: boolean } = {}) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(700); // crossfades (600 ms) and the sheet (280 ms) finish
  await auditTouchTargets(page, info, name);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
  await page.screenshot({ path: shotPath(info, name), fullPage });
  await page.emulateMedia({ reducedMotion: null });
}

/** Six songs with distinct artwork, served below as SVG gradients (no network). */
export const SHOT_SONGS: CannedSong[] = [
  ['shotSenja01', 'Senja di Kota', 'Nadin Amizah', 221_000],
  ['shotPulang2', 'Kembali Pulang', 'Feast', 252_000],
  ['shotJalan03', 'Hati-Hati di Jalan', 'Tulus', 242_000],
  ['shotMonokr4', 'Monokrom', 'Tulus', 213_000],
  ['shotDebu005', 'Sampai Jadi Debu', 'Banda Neira', 238_000],
  ['shotRadio06', 'Lofi Radio 24/7', 'Lofi Girl', 0],
].map(([videoId, title, artist, durationMs]) => ({
  videoId: videoId as string,
  title: title as string,
  artist: artist as string,
  durationMs: durationMs as number,
  thumbnailUrl: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
}));

const HUES = [12, 200, 265, 45, 150, 330];

/**
 * Artwork for the screenshots: every i.ytimg.com thumbnail becomes a gradient SVG (registered
 * after isolate(), so it wins over the catch-all abort), and the fake YouTube frame gets a picture.
 */
export async function withArtwork(context: BrowserContext) {
  await context.route('https://i.ytimg.com/**', (route) => {
    const id = new URL(route.request().url()).pathname.split('/')[2] ?? '';
    const hue = HUES[[...id].reduce((h, c) => h + c.charCodeAt(0), 0) % HUES.length];
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="360"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${hue},70%,62%)"/><stop offset="1" stop-color="hsl(${(hue + 60) % 360},65%,28%)"/></linearGradient></defs><rect width="480" height="360" fill="url(#g)"/><circle cx="330" cy="120" r="70" fill="hsl(${(hue + 30) % 360},90%,80%)" opacity=".7"/></svg>`;
    return route.fulfill({ contentType: 'image/svg+xml', body: svg });
  });
  // No share sheet in the screenshots: "Bagikan" copies the link and shows the toast.
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await context.addInitScript(() => {
    Object.defineProperty(Navigator.prototype, 'share', { configurable: true, value: undefined });
    document.addEventListener('DOMContentLoaded', () => {
      const style = document.createElement('style');
      style.textContent =
        '[data-testid="fake-yt"]{width:100%;height:100%;background:linear-gradient(160deg,#2b4162,#fa9f42 70%,#0b6e4f)}';
      document.head.appendChild(style);
    });
  });
}
