import { type Page, expect, test } from '@playwright/test';
import { addSong, closeSheet, endRooms, isolate, openRoom, showAdd, showMembers } from './fixtures';
import { SHOT_SONGS, shot, withArtwork } from './shots';

/**
 * One test per screen group; the tag after @ is what a task greps for. Each test writes the
 * screens it names, in all four projects (mobile/desktop x dark/light).
 */
test.beforeEach(async ({ context }) => {
  await isolate(context, SHOT_SONGS);
  await withArtwork(context);
});

// Every scenario opens a room; end it so the run stays under the server's rooms-per-IP limit.
test.afterEach(async ({ context }) => endRooms(context));

const tap = (page: Page) => page.getByRole('button', { name: 'Ketuk untuk ikut dengar' }).click();

test('A home, B invite @home', async ({ page }, info) => {
  await page.goto('/');
  await shot(page, info, 'A-home');
  await page.getByRole('button', { name: 'Mulai Ritme' }).click();
  await shot(page, info, 'A-home-error');
  await page.goto('/r/K7M2QX');
  await shot(page, info, 'B-invite');
});

test('C listen, D remote, E queue @room', async ({ context }, info) => {
  const { a, b } = await openRoom(context);
  await shot(b, info, 'C-listen-empty');
  for (const song of SHOT_SONGS.slice(0, 4)) await addSong(a, 'x', song.title);
  await shot(b, info, 'C-listen-before-start');
  await tap(b);
  await shot(b, info, 'C-listen-playing');
  await b.getByRole('button', { name: 'Mode remote' }).click();
  await shot(b, info, 'D-remote');
  await shot(a, info, 'E-queue-host');
});

test('toast, confirm dialog @overlays', async ({ context }, info) => {
  const { a } = await openRoom(context);
  await a.getByRole('button', { name: 'Bagikan' }).click();
  await expect(a.getByRole('status').filter({ hasText: 'Link disalin' })).toBeVisible();
  await shot(a, info, 'H-toast');
  const members = await showMembers(a);
  await members.getByRole('button', { name: /^Keluarkan/ }).first().click();
  await expect(a.getByRole('alertdialog')).toBeVisible();
  await shot(a, info, 'G-confirm-kick');
});

test('LIVE and locked controls @locked', async ({ context }, info) => {
  const { a, b } = await openRoom(context);
  await addSong(a, 'x', 'Lofi Radio 24/7');
  await addSong(a, 'x', 'Kembali Pulang');
  await (await showMembers(a)).getByRole('switch', { name: 'Anggota boleh kontrol' }).click();
  await closeSheet(a);
  await tap(b);
  // Locked controls are aria-disabled but still take taps (they explain the lock); Playwright
  // treats aria-disabled as not enabled and would wait forever without force.
  await b.getByRole('button', { name: 'Jeda' }).click({ force: true });
  await expect(b.getByRole('status').filter({ hasText: 'Host mengunci kontrol' })).toBeVisible();
  await shot(b, info, 'C-live-locked');
});

test('E queue: row actions, played, empty @queue', async ({ context }, info) => {
  const { a, b } = await openRoom(context);
  await shot(b, info, 'E-queue-empty');
  for (const song of SHOT_SONGS.slice(0, 5)) await addSong(a, 'x', song.title);
  await a.getByRole('button', { name: 'Berikutnya', exact: true }).click();
  await a.getByRole('button', { name: `Aksi: ${SHOT_SONGS[2].title}` }).click();
  await shot(a, info, 'E-queue-actions');
  // A middle row shows Putar sekarang, up, down and delete together: the widest bar.
  await a.getByRole('button', { name: `Aksi: ${SHOT_SONGS[3].title}` }).click(); // closes the first bar, opens this one
  await shot(a, info, 'E-queue-actions-middle');
});

test('F add sheet: results, no results @add', async ({ context }, info) => {
  const { a } = await openRoom(context);
  await addSong(a, 'x', SHOT_SONGS[0].title); // something playing behind the sheet, as in eBVC3
  await showAdd(a);
  await a.getByLabel('Cari lagu').fill('senja');
  await a.getByRole('button', { name: 'Cari', exact: true }).click();
  await expect(a.getByRole('button', { name: `Tambah: ${SHOT_SONGS[5].title}` })).toBeVisible();
  await shot(a, info, 'F-add-sheet');
  // Routes registered later win: this search answers nothing.
  await context.route('**/api/search?**', (route) => route.fulfill({ json: [] }));
  await a.getByLabel('Cari lagu').fill('tidak ada');
  await a.getByRole('button', { name: 'Cari', exact: true }).click();
  await expect(a.getByText('Tidak ada hasil.', { exact: true })).toBeVisible();
  await shot(a, info, 'F-add-empty');
});
