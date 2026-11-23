import { type Page, expect, test } from '@playwright/test';
import { SONG, VIDEO, addSong, isolate, openRoom, showAdd } from './fixtures';

test('two tabs in one room: the song tab A adds shows up in tab B', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);

  await b.getByRole('button', { name: 'Ketuk untuk ikut dengar' }).click();

  await addSong(a, 'rick', SONG.title);

  const nowPlaying = b.locator('section.now-playing');
  await expect(nowPlaying).toContainText(SONG.title);
  await expect(nowPlaying).toContainText('oleh Budi');
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { cued: string } }).__fakeYt?.cued)).toBe(SONG.videoId);
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { playing: boolean } }).__fakeYt?.playing)).toBe(true);
});

test('joining a room that is already playing plays the song mid-way, not "Tidak bisa diputar di web"', async ({ context }) => {
  type FakeYt = { cued: string | null; startSeconds: number | null; playing: boolean; errors: number[] };
  const fakeYt = (p: Page) => p.evaluate(() => (window as unknown as { __fakeYt?: FakeYt }).__fakeYt ?? null);
  await isolate(context);
  const { a, b } = await openRoom(context);

  await addSong(a, 'rick', SONG.title);
  await expect(b.locator('section.now-playing')).toContainText(SONG.title);
  // Let the start anchor pass, so the room is playing when B starts listening.
  await b.waitForTimeout(3_000);

  // The player is created only now: load and play are requested before it is ready.
  await b.getByRole('button', { name: 'Ketuk untuk ikut dengar' }).click();
  await expect.poll(async () => (await fakeYt(b))?.playing).toBe(true);
  const yt = (await fakeYt(b))!;
  expect(yt.cued).toBe(SONG.videoId);
  expect(yt.startSeconds).toBeGreaterThan(1); // cued mid-song: a real mid-join
  expect(yt.errors).toEqual([]);
  await expect(b.getByText('Tidak bisa diputar di web', { exact: true })).toHaveCount(0);
});

test('a YouTube link and a direct mp4 link from the Video tab reach the other tab', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);

  await showAdd(a);
  await a.getByRole('tab', { name: 'Video', exact: true }).click();
  await expect(a.getByRole('tab', { name: 'Video', exact: true })).toHaveAttribute('aria-selected', 'true');
  await a.getByLabel('Tempel link video').fill(`https://youtu.be/${VIDEO.videoId}?t=42`);
  await a.getByRole('button', { name: 'Pratinjau' }).click();
  await expect(a.getByText(`${VIDEO.channel} · 10:35`, { exact: true })).toBeVisible();
  await a.getByRole('button', { name: `Tambah: ${VIDEO.title}` }).click();
  await expect(b.locator('section.now-playing')).toContainText(VIDEO.title);

  // The media host answers 404, so the probe fails fast: the preview says so and still allows adding.
  await context.route('https://media.example/**', (route) => route.fulfill({ status: 404, body: '' }));
  await a.getByLabel('Tempel link video').fill('https://media.example/clips/sample.mp4');
  await a.getByRole('button', { name: 'Pratinjau' }).click();
  await expect(a.getByText('Video ini tidak bisa diputar di web', { exact: true })).toBeVisible({ timeout: 15_000 });
  await a.getByLabel('Judul (opsional)').fill('Klip contoh');
  await a.getByRole('button', { name: 'Tambah: Klip contoh' }).click();

  const queue = b.locator('section[aria-labelledby="queue-title"]');
  await expect(queue).toContainText('Klip contoh');
  await expect(queue).toContainText('dari media.example');
  await expect(queue.locator('.thumb-wide')).toHaveCount(1);
});

test('the host can stop guests from adding', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);
  await expect(b.getByRole('button', { name: 'Tambah lagu' })).toBeVisible();
  await a.getByRole('switch', { name: 'Anggota boleh menambah' }).click();
  await expect(b.getByRole('button', { name: 'Tambah lagu' })).toHaveCount(0);
  await expect(a.getByRole('button', { name: 'Tambah lagu' })).toBeVisible();
  await a.getByRole('switch', { name: 'Anggota boleh menambah' }).click();
  await expect(b.getByRole('button', { name: 'Tambah lagu' })).toBeVisible();
});
