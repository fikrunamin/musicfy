import { type BrowserContext, expect, test } from '@playwright/test';
import { FAKE_IFRAME_API } from './fake-youtube';

const SONG = {
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  durationMs: 213_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
};

/** No real network beyond the local server: YouTube is faked, search is canned, the rest blocked. */
async function isolate(context: BrowserContext) {
  // Routes registered later win, so the catch-all goes first.
  await context.route(/^https?:\/\/(?!localhost[:/])/, (route) => route.abort());
  await context.route('https://www.youtube.com/iframe_api', (route) =>
    route.fulfill({ contentType: 'text/javascript', body: FAKE_IFRAME_API }),
  );
  await context.route('**/api/search?**', (route) => route.fulfill({ json: [SONG] }));
}

test('two tabs in one room: the song tab A adds shows up in tab B', async ({ context }) => {
  await isolate(context);

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
  await expect(a.locator('.members')).toContainText('Sari');

  await b.getByRole('button', { name: 'Ketuk untuk ikut dengar' }).click();

  await a.getByLabel('Cari lagu').fill('rick');
  await a.getByRole('button', { name: 'Cari', exact: true }).click();
  await a.getByRole('button', { name: `Tambah: ${SONG.title}` }).click();

  const nowPlaying = b.locator('section.now-playing');
  await expect(nowPlaying).toContainText(SONG.title);
  await expect(nowPlaying).toContainText('oleh Budi');
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { cued: string } }).__fakeYt?.cued)).toBe(SONG.videoId);
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { playing: boolean } }).__fakeYt?.playing)).toBe(true);
});
