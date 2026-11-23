import type { BrowserContext, Page, TestInfo } from '@playwright/test';
import type { CannedSong } from './fixtures';

/** Where a screen's PNG goes: test-results/redesign/<SHOTS_SET or "after">/<project>/<name>.png. */
export function shotPath(info: TestInfo, name: string): string {
  return `test-results/redesign/${process.env.SHOTS_SET ?? 'after'}/${info.project.name}/${name}.png`;
}

/** Full-page screenshot once fonts and images settled. */
export async function shot(page: Page, info: TestInfo, name: string) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(700); // crossfades (600 ms) and the sheet (280 ms) finish
  await page.screenshot({ path: shotPath(info, name), fullPage: true });
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
