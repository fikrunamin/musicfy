import { type BrowserContext, type Locator, type Page, expect } from '@playwright/test';
import { FAKE_IFRAME_API } from './fake-youtube';

export interface CannedSong {
  videoId: string;
  title: string;
  artist: string;
  durationMs: number;
  thumbnailUrl: string;
}

export const SONG: CannedSong = {
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  durationMs: 213_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
};

export const VIDEO = {
  videoId: 'aqz-KE-bpKQ',
  title: 'Big Buck Bunny',
  channel: 'Blender',
  durationMs: 635_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
  live: false,
};

/**
 * No real network beyond the local server: YouTube is faked, search and video info are canned,
 * the rest blocked. `songs` is what every search answers.
 */
export async function isolate(context: BrowserContext, songs: CannedSong[] = [SONG]) {
  // Routes registered later win, so the catch-all goes first.
  await context.route(/^https?:\/\/(?!localhost[:/])/, (route) => route.abort());
  await context.route('https://www.youtube.com/iframe_api', (route) =>
    route.fulfill({ contentType: 'text/javascript', body: FAKE_IFRAME_API }),
  );
  await context.route('**/api/search?**', (route) => route.fulfill({ json: songs }));
  await context.route('**/api/video?**', (route) => route.fulfill({ json: VIDEO }));
}

/** Closes the open sheet, if any, through its close button. */
export async function closeSheet(page: Page) {
  const dialog = page.getByRole('dialog');
  if ((await dialog.count()) === 0) return;
  await dialog.getByRole('button', { name: 'Tutup' }).click();
  await expect(dialog).toHaveCount(0);
}

/** Brings up "Tambah ke antrean": the inline panel, or the sheet behind "Tambah lagu" on a phone. */
export async function showAdd(page: Page) {
  if ((await page.getByLabel('Cari lagu').count()) === 0) await page.getByRole('button', { name: 'Tambah lagu' }).first().click();
  await expect(page.getByLabel('Cari lagu')).toBeVisible();
}

/** The members list: the inline panel, or the sheet behind the avatar stack on a phone. */
export async function showMembers(page: Page): Promise<Locator> {
  const opener = page.getByRole('button', { name: /^\d+ anggota$/ });
  if ((await opener.count()) > 0) {
    await opener.click();
    const sheet = page.getByRole('dialog', { name: 'Anggota' });
    await expect(sheet).toBeVisible();
    return sheet;
  }
  return page.locator('section[aria-labelledby="members-title"]');
}

/** Waits until `page` lists `name` as a room member. */
export async function expectMember(page: Page, name: string) {
  const members = await showMembers(page);
  await expect(members.locator('.members')).toContainText(name);
  await closeSheet(page);
}

/** Searches and adds one song to the end of the queue. */
export async function addSong(page: Page, query: string, title: string) {
  await showAdd(page);
  await page.getByLabel('Cari lagu').fill(query);
  await page.getByRole('button', { name: 'Cari', exact: true }).click();
  await page.getByRole('button', { name: `Tambah: ${title}` }).click();
  await closeSheet(page);
}

/** Tab A starts a room as Budi; tab B joins it through /r/{code} as Sari. */
export async function openRoom(context: BrowserContext): Promise<{ a: Page; b: Page; code: string }> {
  const a = await context.newPage();
  await a.goto('/');
  await a.getByLabel('Nama tampilan').fill('Budi');
  await a.getByRole('button', { name: 'Mulai Ritme' }).click();
  const heading = a.getByRole('heading', { level: 1 });
  await expect(heading).toHaveText(/^Ritme · [2-9A-HJ-NP-Z]{6}$/);
  const code = (await heading.textContent())!.slice(-6);
  await expect(a).toHaveURL(new RegExp(`/r/${code}$`));

  const b = await context.newPage();
  await b.goto(`/r/${code}`);
  await expect(b.getByRole('link', { name: 'Buka di aplikasi Musicfy' })).toHaveAttribute('href', `musicfy://ritme/${code}`);
  await b.getByLabel('Nama tampilan').fill('Sari');
  await b.getByRole('button', { name: 'Gabung' }).click();
  await expect(b.getByRole('heading', { level: 1 })).toHaveText(`Ritme · ${code}`);
  await expectMember(a, 'Sari');
  return { a, b, code };
}

/**
 * Ends the room from whichever page of `context` is its host ("Akhiri Ritme untuk semua"), so a
 * long run of scenarios stays under the server's live-rooms-per-IP limit (Limits.MAX_ROOMS_PER_IP).
 * Best effort: a page that is not in a room, or not the host, is skipped.
 */
export async function endRooms(context: BrowserContext) {
  for (const page of context.pages()) {
    try {
      await page.keyboard.press('Escape'); // an open confirm dialog makes the rest of the page inert
      await closeSheet(page);
      if ((await page.getByRole('heading', { level: 1, name: /^Ritme · / }).count()) === 0) continue;
      const members = await showMembers(page);
      const end = members.getByRole('button', { name: 'Akhiri Ritme untuk semua' });
      if ((await end.count()) === 0) {
        await closeSheet(page);
        continue;
      }
      await end.click({ timeout: 3000 });
      await page.getByRole('alertdialog').getByRole('button', { name: 'Akhiri', exact: true }).click({ timeout: 3000 });
      await expect(page.getByRole('heading', { level: 1, name: /^Ritme · / })).toHaveCount(0, { timeout: 3000 });
    } catch (error) {
      console.warn(`endRooms: could not end the room on ${page.url()}: ${error instanceof Error ? error.message : String(error)}`);
      // Leave it to the server's idle cleanup.
    }
  }
}
