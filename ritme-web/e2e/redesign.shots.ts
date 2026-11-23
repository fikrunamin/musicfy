import { type Page, type WebSocketRoute, expect, test } from '@playwright/test';
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

test('G members sheet: host and guest @members', async ({ context }, info) => {
  const { a, b } = await openRoom(context);
  await addSong(a, 'x', SHOT_SONGS[0].title); // something playing behind the sheet, as in J5QAxI
  // A third member who then drops out, for the "terputus" row.
  const c = await context.newPage();
  await c.goto(a.url());
  await c.getByLabel('Nama tampilan').fill('Rani');
  await c.getByRole('button', { name: 'Gabung' }).click();
  await expect(c.getByRole('heading', { level: 1 })).toHaveText(/^Ritme · /);
  await c.close();
  const host = await showMembers(a);
  await expect(host.locator('.members')).toContainText('terputus');
  await expect(host.getByRole('switch', { name: 'Anggota boleh kontrol' })).toBeChecked();
  await expect(host.getByRole('button', { name: 'Keluarkan: Sari' })).toBeVisible();
  await shot(a, info, 'G-members-host');
  const guest = await showMembers(b);
  await expect(guest.getByText('Bagikan kode')).toBeVisible();
  await expect(guest.getByRole('switch')).toHaveCount(0);
  await shot(b, info, 'G-members-guest');
});

/** The Ritme socket (ws://host/ritme), for routeWebSocket mocks. */
const RITME_SOCKET = /\/ritme$/;

test('H connecting, unreachable, upgrade (mocked socket) @status', async ({ page }, info) => {
  // hang: the socket opens and never answers, so the page waits for its Welcome (10 s at most).
  let mode: 'hang' | 'upgrade' = 'hang';
  const hung: WebSocketRoute[] = [];
  await page.routeWebSocket(RITME_SOCKET, (ws) => {
    if (mode === 'hang') hung.push(ws);
    else ws.onMessage(() => ws.send(JSON.stringify({ type: 'Error', reqId: null, code: 'UPGRADE_REQUIRED' })));
  });
  await page.goto('/r/K7M2QX');
  await page.getByLabel('Nama tampilan').fill('Sari');
  await page.getByRole('button', { name: 'Gabung' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Menghubungkan…');
  await expect(page.getByText('Room K7M2QX', { exact: true })).toBeVisible();
  // The ring turns, and stands still under reduced motion (as in the screenshot).
  const ring = page.locator('.status-ring');
  await expect(ring).toHaveCSS('animation-name', 'status-spin');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(ring).toHaveCSS('animation-name', 'none');
  await shot(page, info, 'H-connecting');

  // The socket closes before any Welcome: the server is unreachable. The room link stays.
  await expect.poll(() => hung.length).toBe(1);
  await hung[0].close();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tidak bisa terhubung ke server Ritme');
  await expect(page).toHaveURL(/\/r\/K7M2QX$/);
  await shot(page, info, 'H-unreachable');

  // "Coba lagi" joins the same room again; this time the server wants a newer client.
  mode = 'upgrade';
  await page.getByRole('button', { name: 'Coba lagi' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Perlu versi baru');
  await expect(page.getByRole('button', { name: 'Muat ulang' })).toBeVisible();
  await expect(page).toHaveURL(/\/r\/K7M2QX$/);
  await shot(page, info, 'H-upgrade');
});

test('H kicked, ended @status', async ({ context }, info) => {
  const { a, b, code } = await openRoom(context);
  const host = await showMembers(a);
  await host.getByRole('button', { name: 'Keluarkan: Sari' }).click();
  await a.getByRole('alertdialog').getByRole('button', { name: 'Keluarkan', exact: true }).click();
  await expect(b.getByRole('heading', { level: 1 })).toHaveText('Kamu dikeluarkan dari Ritme');
  await shot(b, info, 'H-kicked');
  await closeSheet(a);

  // A second guest is there when the host ends Ritme for everyone (this also ends the room).
  const c = await context.newPage();
  await c.goto(`/r/${code}`);
  await c.getByLabel('Nama tampilan').fill('Rani');
  await c.getByRole('button', { name: 'Gabung' }).click();
  await expect(c.getByRole('heading', { level: 1 })).toHaveText(`Ritme · ${code}`);
  const members = await showMembers(a);
  await members.getByRole('button', { name: 'Akhiri Ritme untuk semua' }).click();
  await a.getByRole('alertdialog').getByRole('button', { name: 'Akhiri', exact: true }).click();
  await expect(c.getByRole('heading', { level: 1 })).toHaveText('Ritme sudah diakhiri');
  await shot(c, info, 'H-ended');
});

test('H reconnecting, lost @status', async ({ context }, info) => {
  // The host talks to the server directly and stays online, so afterEach can end the room.
  const a = await context.newPage();
  await a.goto('/');
  await a.getByLabel('Nama tampilan').fill('Budi');
  await a.getByRole('button', { name: 'Mulai Ritme' }).click();
  await expect(a.getByRole('heading', { level: 1 })).toHaveText(/^Ritme · [2-9A-HJ-NP-Z]{6}$/);
  const code = (await a.getByRole('heading', { level: 1 }).textContent())!.slice(-6);
  await addSong(a, 'x', SHOT_SONGS[0].title);

  // The guest's socket goes through Playwright: passed to the server, then cut.
  let mode: 'proxy' | 'drop' | 'refuse' = 'proxy';
  const proxied: WebSocketRoute[] = [];
  const b = await context.newPage();
  await b.routeWebSocket(RITME_SOCKET, (ws) => {
    if (mode === 'proxy') {
      ws.connectToServer();
      proxied.push(ws);
    } else if (mode === 'drop') {
      void ws.close();
    } else {
      // A resume the server no longer knows: the session is lost at once (no 30 s wait).
      ws.onMessage(() => ws.send(JSON.stringify({ type: 'Error', reqId: null, code: 'ROOM_NOT_FOUND' })));
    }
  });
  await b.goto(`/r/${code}`);
  await b.getByLabel('Nama tampilan').fill('Sari');
  await b.getByRole('button', { name: 'Gabung' }).click();
  await expect(b.getByRole('heading', { level: 1 })).toHaveText(`Ritme · ${code}`);

  mode = 'drop';
  for (const ws of proxied) await ws.close();
  await expect(b.getByRole('status').filter({ hasText: 'Menyambung ulang…' }).first()).toBeVisible();
  await expect(b.getByRole('heading', { level: 1 })).toHaveText(`Ritme · ${code}`);
  await shot(b, info, 'H-reconnecting');

  mode = 'refuse';
  // The next attempt comes within the backoff (at most 15 s); the give-up at 30 s also ends here.
  await expect(b.getByRole('heading', { level: 1 })).toHaveText('Terputus dari Ritme', { timeout: 40_000 });
  await expect(b.getByRole('button', { name: 'Coba lagi' })).toBeVisible();
  await shot(b, info, 'H-lost');
});
