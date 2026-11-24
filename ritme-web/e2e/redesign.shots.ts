import { type Page, type WebSocketRoute, expect, test } from '@playwright/test';
import { VIDEO, addSong, closeSheet, endRooms, isolate, openRoom, showAdd, showMembers } from './fixtures';
import { SAMPLE_WEBM } from './sample-video';
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

/** An empty search field in the desktop add panel: the fixture's searches leave "x" there, and the panel
 * keeps its draft (query and results) across track changes, so clear the field by hand. */
async function clearInlineSearch(page: Page) {
  const field = page.getByRole('region', { name: 'Tambah ke antrean' }).getByLabel('Cari lagu');
  await field.fill('');
  await field.blur();
  await expect(field).toHaveValue('');
}

/** The seek bar, its times and previous/play/next sit wholly inside the window, without scrolling. */
async function expectTransportOnScreen(page: Page) {
  const now = page.locator('section.now-playing');
  await expect(now.getByRole('slider', { name: 'Posisi lagu' })).toBeInViewport({ ratio: 1 });
  for (const time of await now.locator('.np-times > span').all()) await expect(time).toBeInViewport({ ratio: 1 });
  await expect(now.getByRole('button', { name: 'Sebelumnya', exact: true })).toBeInViewport({ ratio: 1 });
  await expect(now.getByRole('button', { name: /^(Jeda|Putar)$/ })).toBeInViewport({ ratio: 1 });
  await expect(now.getByRole('button', { name: 'Berikutnya', exact: true })).toBeInViewport({ ratio: 1 });
  expect(await page.locator('.room-left').evaluate((el) => el.scrollTop)).toBe(0);
}

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

test('C+E desktop room: before start, listen, remote @desktop', async ({ context }, info) => {
  test.skip(!info.project.name.startsWith('desktop'), 'the three-column room exists from 1024 px only');
  const { a } = await openRoom(context);
  for (const song of SHOT_SONGS.slice(0, 5)) await addSong(a, 'x', song.title);
  await expect(a.locator('main.room-desktop')).toHaveCount(1);
  // The columns scroll on their own; the page itself fits the window.
  expect(await a.evaluate(() => document.documentElement.scrollHeight)).toBeLessThanOrEqual(900);
  await clearInlineSearch(a);
  await shot(a, info, 'C-desktop-before-start');
  await tap(a);
  await expect(a.locator('.player [data-testid="fake-yt"]')).toHaveCount(1);
  await shot(a, info, 'C-desktop-listen');

  // A phone-width window and back: the same player element and frame, still playing (YouTube rule).
  const frame = await a.locator('.player [data-testid="fake-yt"]').elementHandle();
  await a.setViewportSize({ width: 390, height: 844 });
  await expect(a.locator('main.room-desktop')).toHaveCount(0);
  expect(await frame!.evaluate((el) => el.isConnected && el === document.querySelector('.player [data-testid="fake-yt"]'))).toBe(true);
  await a.setViewportSize({ width: 1440, height: 900 });
  await expect(a.locator('main.room-desktop')).toHaveCount(1);
  expect(await frame!.evaluate((el) => el.isConnected && el === document.querySelector('.player [data-testid="fake-yt"]'))).toBe(true);
  await expect.poll(() => a.evaluate(() => (window as unknown as { __fakeYt?: { playing: boolean } }).__fakeYt?.playing)).toBe(true);

  await a.getByRole('button', { name: 'Mode remote' }).click();
  await shot(a, info, 'D-desktop-remote');

  // A 1366 x 768 laptop's browser window (about 650 px of page): the stage shrinks to what the
  // window leaves, so the whole transport stays on screen in both modes.
  await a.setViewportSize({ width: 1366, height: 650 });
  await expect(a.locator('main.room-desktop')).toHaveCount(1);
  await expectTransportOnScreen(a);
  await a.getByRole('button', { name: 'Mode dengar' }).click();
  await expectTransportOnScreen(a);
  const stage = await a.locator('.stage').boundingBox();
  expect(Math.min(stage!.width, stage!.height)).toBeGreaterThanOrEqual(200);
  await shot(a, info, 'C-desktop-short');
  // A 1440 x 900 MacBook's browser window (about 780 px of page).
  await a.setViewportSize({ width: 1440, height: 780 });
  await expectTransportOnScreen(a);
  await a.getByRole('button', { name: 'Mode remote' }).click();
  await expectTransportOnScreen(a);
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

test('L/P add video: empty, error, YouTube and direct previews @addvideo', async ({ context }, info) => {
  // The direct link is the sample WebM (12:00) served under an .mp4 name, so its format line reads MP4.
  await context.route('https://media.example.com/**', (route) => route.fulfill({ contentType: 'video/webm', body: SAMPLE_WEBM }));
  const { a } = await openRoom(context);
  await addSong(a, 'x', SHOT_SONGS[0].title); // something playing behind the sheet, as in LWN2X
  await showAdd(a);
  await a.getByRole('tab', { name: 'Video', exact: true }).click();
  const field = a.getByLabel('Tempel link video');
  const preview = a.getByRole('button', { name: 'Pratinjau' });
  await expect(field).toBeVisible();
  await shot(a, info, 'L-add-video-empty');

  // Not an http(s) link at all (Chromium parses "https://" + text with spaces, so spaces would read as a web page).
  await field.fill('ftp://contoh.com/halaman-ku');
  await preview.click();
  await expect(a.locator('.field-error')).toHaveText('Link tidak dikenali');
  await expect(field).toHaveAttribute('aria-invalid', 'true');
  await shot(a, info, 'L-add-video-error');

  await field.fill(`https://youtu.be/${VIDEO.videoId}`);
  await preview.click();
  await expect(a.locator('.avp-card .avp-title')).toHaveText(VIDEO.title);
  // The thumbnail is lazy: in the desktop column it may sit below the fold until scrolled to.
  await a.locator('.avp-art img').scrollIntoViewIfNeeded();
  await expect(a.locator('.avp-art img')).toHaveJSProperty('complete', true);
  await shot(a, info, 'L-add-video-preview');

  await field.fill('https://media.example.com/klip/sawah-pagi.mp4');
  await preview.click();
  // Enabled once the probe has read the length.
  await expect(a.getByRole('button', { name: 'Tambah: sawah-pagi.mp4' })).toBeEnabled({ timeout: 15_000 });
  await expect(a.getByText(/^MP4 · \d+:\d\d$/)).toBeVisible();
  await shot(a, info, 'P-add-direct-preview');
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
  // The phone sheet has the share card; the desktop panel leaves sharing to the header's "Bagikan".
  if (info.project.name.startsWith('desktop')) {
    await expect(guest.getByText('Bagikan kode')).toBeHidden();
    await expect(b.locator('.room-header').getByRole('button', { name: 'Bagikan' })).toBeVisible();
  } else {
    await expect(guest.getByText('Bagikan kode')).toBeVisible();
  }
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

/** Adds a link from the Video tab of "Tambah ke antrean" (the panel, the sheet, or the theater's dialog). */
async function addVideoLink(page: Page, url: string, title: string) {
  await showAdd(page);
  await page.getByRole('tab', { name: 'Video', exact: true }).click();
  await page.getByLabel('Tempel link video').fill(url);
  await page.getByRole('button', { name: 'Pratinjau' }).click();
  // A direct link takes its title from the field; a YouTube link from the video info.
  const field = page.getByLabel('Judul (opsional)');
  await expect(field.or(page.getByRole('button', { name: `Tambah: ${title}` })).first()).toBeVisible({ timeout: 15_000 });
  if ((await field.count()) > 0) await field.fill(title);
  await page.getByRole('button', { name: `Tambah: ${title}` }).click();
  await closeSheet(page);
}

/** The fake YouTube frame the player is showing, as a handle (to prove it is the same element later). */
async function youtubeFrame(page: Page) {
  await expect(page.locator('.player [data-testid="fake-yt"]')).toHaveCount(1);
  return (await page.locator('.player [data-testid="fake-yt"]').elementHandle())!;
}

const sameFrame = (frame: Awaited<ReturnType<typeof youtubeFrame>>) =>
  frame.evaluate((el) => el.isConnected && el === document.querySelector('main > .room-left > section.now-playing > .stage > .player [data-testid="fake-yt"]'));

const YOUTUBE_LINK = `https://youtu.be/${VIDEO.videoId}`;

test('I video: listen and remote, K desktop theater, J fullscreen @video', async ({ context }, info) => {
  const desktop = info.project.name.startsWith('desktop');
  const { a, b } = await openRoom(context);
  // A song first, listened to: the theater must then take over the same player.
  await addSong(a, 'x', SHOT_SONGS[0].title);
  await tap(a);
  const frame = await youtubeFrame(a);
  await addVideoLink(a, YOUTUBE_LINK, VIDEO.title);
  await addSong(a, 'x', SHOT_SONGS[1].title);
  await a.getByRole('button', { name: `Aksi: ${VIDEO.title}` }).click();
  await a.getByRole('button', { name: `Putar sekarang: ${VIDEO.title}` }).click();
  await expect(a.locator('section.now-playing .np-title')).toHaveText(VIDEO.title);
  await expect(a.locator('.stage.stage-wide')).toHaveCount(1);
  expect(await sameFrame(frame)).toBe(true);

  if (desktop) {
    // K: the theater, from the same tree; the transport fits under the 16:9 stage.
    await expect(a.locator('main.room-theater')).toHaveCount(1);
    await expect(a.getByRole('button', { name: 'Antrean dan anggota' })).toHaveAttribute('aria-expanded', 'true');
    await expectTransportOnScreen(a);
    await shot(a, info, 'K-desktop-theater');
    await a.getByRole('button', { name: 'Antrean dan anggota' }).click();
    await expect(a.locator('main.room-collapsed')).toHaveCount(1);
    expect(await sameFrame(frame)).toBe(true);
    await shot(a, info, 'K-desktop-theater-folded');
    await a.getByRole('button', { name: 'Antrean dan anggota' }).click();
    await a.setViewportSize({ width: 1366, height: 650 });
    await expectTransportOnScreen(a);
    const stage = await a.locator('.stage').boundingBox();
    expect(Math.min(stage!.width, stage!.height)).toBeGreaterThanOrEqual(200);
    await a.setViewportSize({ width: 1440, height: 900 });
  }

  await tap(b);
  const guestFrame = await youtubeFrame(b);
  await shot(b, info, 'I-video-listen');

  // J: fullscreen takes the now-playing section itself; the side bar sits beside the video.
  if (!desktop) await b.setViewportSize({ width: 844, height: 390 });
  const fullscreen = b.getByRole('button', { name: 'Layar penuh' });
  if ((await fullscreen.count()) === 0) {
    info.annotations.push({ type: 'J-fullscreen', description: 'skipped: document.fullscreenEnabled is false in this browser' });
  } else {
    await fullscreen.click();
    const entered = await b
      .waitForFunction(() => document.fullscreenElement?.matches('main > .room-left > section.now-playing') === true, null, { timeout: 3_000 })
      .then(
        () => true,
        () => false,
      );
    if (!entered) {
      info.annotations.push({ type: 'J-fullscreen', description: 'skipped: headless Chromium refused requestFullscreen' });
    } else {
      expect(await sameFrame(guestFrame)).toBe(true);
      await expect(b.locator('.np-fs-status')).toContainText('Tersinkron');
      // Beside the video, never over it.
      const video = (await b.locator('.np-full .stage').boundingBox())!;
      const bar = (await b.locator('.np-fs-status').boundingBox())!;
      expect(bar.x).toBeGreaterThanOrEqual(video.x + video.width - 1);
      await shot(b, info, 'J-fullscreen', { fullPage: false });
      await b.getByRole('button', { name: 'Keluar dari layar penuh' }).click();
      await expect.poll(() => b.evaluate(() => document.fullscreenElement === null)).toBe(true);
      expect(await sameFrame(guestFrame)).toBe(true);
    }
  }
  if (!desktop) await b.setViewportSize({ width: 390, height: 844 });

  await b.getByRole('button', { name: 'Mode remote' }).click();
  await expect(b.locator('.stage .vp-badge')).toContainText('Video');
  await shot(b, info, 'I-video-remote');

  if (desktop) {
    // Back to a song: three columns again, still the same player.
    await a.getByRole('button', { name: 'Berikutnya', exact: true }).click();
    await expect(a.locator('section.now-playing .np-title')).toHaveText(SHOT_SONGS[1].title);
    await expect(a.locator('main.room-theater')).toHaveCount(0);
    expect(await sameFrame(frame)).toBe(true);
  }
});

test('M direct link: listen, remote; O stage error @video', async ({ context }, info) => {
  // A media host that serves a real (tiny) WebM, and one whose file is gone.
  await context.route('https://media.example.com/**', (route) => route.fulfill({ contentType: 'video/webm', body: SAMPLE_WEBM }));
  await context.route('https://files.example.net/**', (route) => route.fulfill({ status: 404, body: '' }));
  const { a, b } = await openRoom(context);
  await addVideoLink(a, 'https://media.example.com/klip/sawah-terasering.webm', 'sawah-terasering-jatiluwih_drone-4k_final-v2_export.webm');
  await addVideoLink(a, 'https://files.example.net/klip-konser_final.webm', 'klip-konser_final.webm');
  await addSong(a, 'x', SHOT_SONGS[1].title);

  await tap(b);
  const video = b.locator('.player video');
  await expect(video).toBeVisible();
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState)).toBeGreaterThan(0);
  await expect(b.locator('.np-source')).toHaveText('dari media.example.com');
  await expect(b.locator('.np-badges .np-src')).toHaveText('WEBM');
  await shot(b, info, 'M-direct-listen');

  // ±10 s seeks the room (the host sees the position move).
  await b.getByRole('button', { name: 'Maju 10 detik' }).click();

  await b.getByRole('button', { name: 'Mode remote' }).click();
  await expect(b.locator('.stage .video-placeholder')).toContainText('media.example.com');
  await shot(b, info, 'M-direct-remote');
  await b.getByRole('button', { name: 'Mode dengar' }).click();
  await tap(b);

  // O: the next link cannot play here; the stage says so, with Lewati for members with control.
  await a.getByRole('button', { name: 'Berikutnya', exact: true }).click();
  await expect(b.locator('.stage-error')).toContainText('Video ini tidak bisa diputar di web', { timeout: 15_000 });
  await expect(b.locator('.stage-error').getByRole('button', { name: 'Lewati' })).toBeVisible();
  await shot(b, info, 'O-direct-error');
});
