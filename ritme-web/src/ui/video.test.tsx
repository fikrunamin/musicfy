// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { memoryStore } from '../net/storage';
import type { QueueEntry, RoomState } from '../protocol';
import { initialSession } from '../session/reducer';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { stubMatchMedia } from '../testing/media-query';
import { DESKTOP_QUERY } from './hooks';
import { NowPlaying } from './now-playing';
import { RoomScreen } from './room';

const mocks = vi.hoisted(() => ({ log: [] as string[], controllers: [] as unknown[] }));
vi.mock('../player/controller', () => ({
  PlaybackController: class {
    constructor() {
      mocks.controllers.push(this);
      mocks.log.push('new');
    }
    update() {}
    resync() {}
    destroy() {
      mocks.log.push('destroy');
    }
  },
}));
vi.mock('../player/youtube', () => ({ loadYouTubeApi: () => Promise.resolve({}), createYouTubePlayer: () => ({}) }));
vi.mock('../player/html5', () => ({ createVideoPlayer: () => ({}), loadHlsJs: () => Promise.reject(new Error('not used here')) }));

/** The Fullscreen API, as a desktop browser has it (jsdom has none). */
function stubFullscreen(enabled = true) {
  let element: Element | null = null;
  const change = () => document.dispatchEvent(new Event('fullscreenchange'));
  const request = vi.fn(function (this: Element) {
    element = this;
    change();
    return Promise.resolve();
  });
  const exit = vi.fn(() => {
    element = null;
    change();
    return Promise.resolve();
  });
  Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, get: () => enabled });
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => element });
  Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exit });
  Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, value: request });
  return { request, exit };
}

afterEach(() => {
  cleanup(); // first: unmounting destroys this test's controller, which must not reach the next log
  mocks.log.length = 0;
  mocks.controllers.length = 0;
  vi.unstubAllGlobals();
  for (const key of ['fullscreenEnabled', 'fullscreenElement', 'exitFullscreen']) delete (document as unknown as Record<string, unknown>)[key];
  delete (HTMLElement.prototype as unknown as Record<string, unknown>).requestFullscreen;
});

const THUMB = 'https://i.ytimg.com/vi/rooftop/hqdefault.jpg';
const ytVideo = (o: Partial<QueueEntry> = {}) =>
  entry('v', { kind: 'YOUTUBE_VIDEO', title: 'Rooftop Session: Kota Senja (Full Set)', artist: 'Sore Sessions', durationMs: 1_458_000, thumbnailUrl: THUMB, ...o });
const direct = (o: Partial<QueueEntry> = {}) =>
  entry('d', { kind: 'URL', videoId: '', url: 'https://media.example.com/sawah.mp4', title: 'sawah-terasering.mp4', artist: '', durationMs: 724_000, ...o });
const playing = (e: QueueEntry, o: Partial<RoomState> = {}) =>
  room({ queue: [e], currentEntryId: e.entryId, playback: { isPlaying: false, positionMs: 192_000, anchorServerTimeMs: 0 }, ...o });
const locked = { settings: { guestsCanControl: false, guestsCanAdd: true } };

function np(r: RoomState, mode: 'listen' | 'remote' = 'listen', o: { memberId?: string; unplayable?: Record<string, true>; session?: ReturnType<typeof fakeSession> } = {}) {
  const session = o.session ?? fakeSession();
  const view = render(<NowPlaying session={session} room={r} memberId={o.memberId ?? 'm2'} unplayable={o.unplayable ?? {}} online pageIsHttps mode={mode} />);
  return { ...view, session };
}

const tapGate = async () => {
  fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
  await Promise.resolve();
};

describe('the video meta under the stage', () => {
  it('gives a YouTube video the Video badge, its channel on YouTube, and who added it with an avatar', () => {
    np(playing(ytVideo()));
    const section = document.querySelector('section.now-playing')!;
    expect(section.classList.contains('np-video')).toBe(true);
    const badges = section.querySelector('.np-badges')!;
    expect(within(badges as HTMLElement).getByText('Video').classList.contains('badge-video')).toBe(true);
    expect(badges.textContent).toContain('Sore Sessions · YouTube');
    expect(badges.querySelector('.np-by')?.textContent).toBe('Boleh Budi');
    expect(badges.querySelector('.np-by .avatar')?.getAttribute('aria-hidden')).toBe('true');
    // The song line ("artis · oleh") is not repeated.
    expect(section.querySelector('.np-sub')).toBeNull();
  });

  it('gives a live video the LIVE badge in that row and no seek bar', () => {
    np(playing(ytVideo({ durationMs: 0 })));
    const badges = document.querySelector('.np-badges')!;
    expect(badges.querySelector('.badge-live')?.textContent).toBe('LIVE');
    expect(screen.queryByRole('slider')).toBeNull();
    // Only the row's badge: no second, standalone LIVE pill where the seek bar was.
    expect(screen.getAllByText('LIVE')).toHaveLength(1);
  });

  it('gives a direct link a 🔗 "dari domain" line and its format from the url in the badge row', () => {
    np(playing(direct()));
    const source = document.querySelector('.np-source')!;
    expect(source.textContent).toBe('dari media.example.com');
    expect(source.querySelector('svg')).toBeTruthy();
    const badges = document.querySelector('.np-badges')!;
    expect(badges.querySelector('.badge-video')).toBeTruthy();
    expect(badges.querySelector('.np-src')?.textContent).toBe('MP4');
    expect(badges.textContent).toContain('oleh Budi');
  });

  it('calls an HLS stream "HLS · m3u8" and decodes an IDN domain', () => {
    np(playing(direct({ url: 'https://www.xn--bcher-kva.example/live/index.m3u8', durationMs: 0 })));
    expect(document.querySelector('.np-source')?.textContent).toBe('dari bücher.example');
    expect(document.querySelector('.np-badges .np-src')?.textContent).toBe('HLS · m3u8');
    expect(document.querySelector('.np-badges .badge-live')).toBeTruthy();
  });

  it('leaves songs as they were: one "artis · oleh" line, no badge row', () => {
    np(playing(entry('s', { title: 'Senja', artist: 'Nadin' })));
    expect(document.querySelector('.np-badges')).toBeNull();
    expect(screen.getByText('Nadin · oleh Budi')).toBeTruthy();
    expect(document.querySelector('section.now-playing')!.classList.contains('np-video')).toBe(false);
  });
});

describe('±10 s on a direct link', () => {
  it('seeks the room 10 s back and forward from where it is, within the video', () => {
    const { session } = np(playing(direct()));
    fireEvent.click(screen.getByRole('button', { name: 'Mundur 10 detik' }));
    expect(session.seek).toHaveBeenLastCalledWith(182_000);
    fireEvent.click(screen.getByRole('button', { name: 'Maju 10 detik' }));
    expect(session.seek).toHaveBeenLastCalledWith(202_000);
  });

  it('never seeks before the start or past the end', () => {
    const near = (positionMs: number) => playing(direct(), { playback: { isPlaying: false, positionMs, anchorServerTimeMs: 0 } });
    const { session, rerender } = np(near(4_000));
    fireEvent.click(screen.getByRole('button', { name: 'Mundur 10 detik' }));
    expect(session.seek).toHaveBeenLastCalledWith(0);
    rerender(<NowPlaying session={session} room={near(720_000)} memberId="m2" unplayable={{}} online pageIsHttps mode="listen" />);
    fireEvent.click(screen.getByRole('button', { name: 'Maju 10 detik' }));
    expect(session.seek).toHaveBeenLastCalledWith(724_000);
  });

  it('is disabled while LIVE', () => {
    const { session } = np(playing(direct({ url: 'https://live.example.org/index.m3u8', durationMs: 0 })));
    for (const name of ['Mundur 10 detik', 'Maju 10 detik']) {
      const button = screen.getByRole('button', { name }) as HTMLButtonElement;
      expect(button.disabled).toBe(true);
      fireEvent.click(button);
    }
    expect(session.seek).not.toHaveBeenCalled();
  });

  it('answers a tap with the locked toast while the host keeps control', () => {
    const { session } = np(playing(direct(), locked));
    const back = screen.getByRole('button', { name: 'Mundur 10 detik' });
    expect(back.getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(back);
    fireEvent.click(screen.getByRole('button', { name: 'Maju 10 detik' }));
    expect(session.seek).not.toHaveBeenCalled();
    expect(session.notify).toHaveBeenCalledTimes(2);
    expect(session.notify).toHaveBeenCalledWith('forbidden');
  });

  it('is there in remote mode too, and not for YouTube entries or songs', () => {
    const view = np(playing(direct()), 'remote');
    expect(screen.getByRole('button', { name: 'Maju 10 detik' })).toBeTruthy();
    view.rerender(<NowPlaying session={view.session} room={playing(ytVideo())} memberId="m2" unplayable={{}} online pageIsHttps mode="listen" />);
    expect(screen.queryByRole('button', { name: /10 detik$/ })).toBeNull();
    view.rerender(<NowPlaying session={view.session} room={playing(entry('s'))} memberId="m2" unplayable={{}} online pageIsHttps mode="listen" />);
    expect(screen.queryByRole('button', { name: /10 detik$/ })).toBeNull();
  });
});

describe('the remote-mode stage of a video', () => {
  it('shows the thumbnail with the Video badge and the length chip, and no player', () => {
    np(playing(ytVideo()), 'remote');
    const stage = document.querySelector('.stage.stage-wide')!;
    expect(stage.querySelector('img.stage-cover')?.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(stage.querySelector('.vp-badge .badge-video')?.textContent).toBe('Video');
    expect(stage.querySelector('.vp-duration')?.textContent).toBe('24:18');
    expect(stage.querySelector('.player')).toBeNull();
  });

  it('shows a live video with LIVE and no length', () => {
    np(playing(ytVideo({ durationMs: 0 })), 'remote');
    expect(document.querySelector('.stage .vp-badge .badge-live')).toBeTruthy();
    expect(document.querySelector('.stage .vp-duration')).toBeNull();
  });

  it('shows the direct-link placeholder with its domain, badge and length', () => {
    np(playing(direct()), 'remote');
    const placeholder = document.querySelector('.stage .video-placeholder')!;
    expect(placeholder.querySelector('.vp-domain')?.textContent).toBe('media.example.com');
    expect(placeholder.querySelector('.vp-badge .badge-video')).toBeTruthy();
    expect(placeholder.querySelector('.vp-duration')?.textContent).toBe('12:04');
  });

  it('leaves the badge and chip off the listen-mode cover (the player takes that slot)', () => {
    np(playing(ytVideo()), 'listen');
    expect(document.querySelector('.stage .vp-badge')).toBeNull();
    expect(document.querySelector('.stage .vp-duration')).toBeNull();
  });

  it('leaves songs without a badge on the cover', () => {
    np(playing(entry('s', { thumbnailUrl: THUMB })), 'remote');
    expect(document.querySelector('.stage .vp-badge')).toBeNull();
  });
});

describe('the stage error of a direct link', () => {
  it('says the video cannot play here and offers Lewati to members with control', async () => {
    const { session } = np(playing(direct({ url: 'https://files.example.net/klip.webm' })), 'listen', { unplayable: { d: true } });
    await tapGate();
    expect(document.querySelector('.stage-error-title')?.textContent).toBe('Video ini tidak bisa diputar di web');
    fireEvent.click(screen.getByRole('button', { name: 'Lewati' }));
    expect(session.next).toHaveBeenCalled();
  });

  it('tells members without control "Menunggu host melewati video ini" instead of Lewati (DECISIONS 2)', async () => {
    np(playing(direct(), locked), 'listen', { unplayable: { d: true } });
    await tapGate();
    expect(screen.queryByRole('button', { name: 'Lewati' })).toBeNull();
    const wait = screen.getByText('Menunggu host melewati video ini');
    expect(wait.closest('.stage-error')).toBeTruthy();
  });
});

describe('the YouTube source line', () => {
  it('says only "YouTube" when the channel is empty', () => {
    np(playing(ytVideo({ artist: '' })));
    expect(document.querySelector('.np-src')?.textContent).toBe('YouTube');
  });
});

describe('the fullscreen button', () => {
  it('is there for a video in listen mode when the browser can go fullscreen, outside the stage', () => {
    stubFullscreen(true);
    np(playing(ytVideo()));
    const button = screen.getByRole('button', { name: 'Layar penuh' });
    expect(button.closest('.stage')).toBeNull();
    expect(button.closest('section.now-playing')).toBeTruthy();
  });

  it('is not there where fullscreen is not available (iPhone Safari)', () => {
    stubFullscreen(false);
    np(playing(ytVideo()));
    expect(screen.queryByRole('button', { name: 'Layar penuh' })).toBeNull();
  });

  it('is not there without the API at all, in remote mode, or for a song', () => {
    const view = np(playing(direct()));
    expect(screen.queryByRole('button', { name: 'Layar penuh' })).toBeNull();
    stubFullscreen(true);
    view.rerender(<NowPlaying session={view.session} room={playing(ytVideo())} memberId="m2" unplayable={{}} online pageIsHttps mode="remote" />);
    expect(screen.queryByRole('button', { name: 'Layar penuh' })).toBeNull();
    view.rerender(<NowPlaying session={view.session} room={playing(entry('s'))} memberId="m2" unplayable={{}} online pageIsHttps mode="listen" />);
    expect(screen.queryByRole('button', { name: 'Layar penuh' })).toBeNull();
  });

  it('fullscreens the whole now-playing section, with a side bar of sync status and members, and leaves again', async () => {
    const { request, exit } = stubFullscreen(true);
    np(playing(direct()));
    const section = document.querySelector('section.now-playing')!;
    expect(document.querySelector('.np-fs-status')).toBeNull();
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.contexts[0]).toBe(section);
    expect(section.classList.contains('np-full')).toBe(true);
    const status = section.querySelector('.np-fs-status')!;
    expect(status.textContent).toContain('Tersinkron');
    expect(status.textContent).toContain('2 anggota');
    // The side bar is beside the video, not in it.
    expect(status.closest('.stage')).toBeNull();
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Keluar dari layar penuh' })));
    expect(exit).toHaveBeenCalledTimes(1);
    expect(section.classList.contains('np-full')).toBe(false);
    expect(document.querySelector('.np-fs-status')).toBeNull();
  });

  it('follows the browser leaving fullscreen by itself (Esc)', async () => {
    stubFullscreen(true);
    np(playing(ytVideo()));
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    expect(document.querySelector('.np-full')).toBeTruthy();
    await act(async () => {
      Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => null });
      document.dispatchEvent(new Event('fullscreenchange'));
    });
    expect(document.querySelector('.np-full')).toBeNull();
    expect(screen.getByRole('button', { name: 'Layar penuh' })).toBeTruthy();
  });
});

describe('fullscreen feedback and focus', () => {
  const props = (r: RoomState, session: ReturnType<typeof fakeSession>) =>
    ({ session, room: r, memberId: 'm2', unplayable: {}, online: true, pageIsHttps: true, mode: 'listen' as const });

  it('shows the room notice inside the fullscreen side bar, never over the stage: a locked tap says "Host mengunci kontrol"', async () => {
    stubFullscreen(true);
    const session = fakeSession();
    const r = playing(ytVideo(), locked);
    // A notice from before fullscreen is not shown again on the way in.
    const view = render(<NowPlaying {...props(r, session)} notice={{ id: 1, key: 'rateLimited' }} />);
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    const section = document.querySelector('section.now-playing')!;
    const bar = section.querySelector('.np-fs-status')!;
    const live = within(bar as HTMLElement).getByRole('status');
    expect(live.getAttribute('aria-live')).toBe('polite');
    expect(live.textContent).toBe('');
    fireEvent.click(screen.getByRole('button', { name: 'Putar' }));
    expect(session.notify).toHaveBeenCalledWith('forbidden');
    view.rerender(<NowPlaying {...props(r, session)} notice={{ id: 2, key: 'forbidden' }} />);
    expect(live.textContent).toBe('Host mengunci kontrol');
    expect(live.closest('.stage')).toBeNull();
    expect(live.closest('section.np-full')).toBe(section);
  });

  it('shows no notice there outside fullscreen (the app toast has it)', () => {
    np(playing(ytVideo()));
    expect(document.querySelector('section.now-playing .toast-region')).toBeNull();
  });

  it('keeps one button in one slot from a YouTube video to a direct link', async () => {
    stubFullscreen(true);
    const session = fakeSession();
    const view = render(<NowPlaying {...props(playing(ytVideo()), session)} />);
    const button = screen.getByRole('button', { name: 'Layar penuh' });
    view.rerender(<NowPlaying {...props(playing(direct()), session)} />);
    expect(screen.getByRole('button', { name: 'Layar penuh' })).toBe(button);
    await act(async () => void fireEvent.click(button));
    view.rerender(<NowPlaying {...props(playing(ytVideo()), session)} />);
    expect(screen.getByRole('button', { name: 'Keluar dari layar penuh' })).toBe(button);
  });

  it('gives focus to the now-playing title on leaving fullscreen when a song took the video\'s place (the button is gone)', async () => {
    stubFullscreen(true);
    const { update } = renderRoom(mixed('v', 1));
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    update(mixed('s', 2));
    const exit = screen.getByRole('button', { name: 'Keluar dari layar penuh' });
    exit.focus();
    await act(async () => void fireEvent.click(exit));
    expect(screen.queryByRole('button', { name: /layar penuh/i })).toBeNull();
    expect(document.activeElement).toBe(document.getElementById('now-title'));
  });

  it('keeps focus on the button when it stays (still a video)', async () => {
    stubFullscreen(true);
    renderRoom(mixed('v', 1));
    const button = screen.getByRole('button', { name: 'Layar penuh' });
    button.focus();
    await act(async () => void fireEvent.click(button));
    await act(async () => void fireEvent.click(button));
    expect(document.activeElement).toBe(button);
  });

  it('says so with a toast when the browser refuses fullscreen', async () => {
    const { request } = stubFullscreen(true);
    request.mockImplementationOnce(() => Promise.reject(new Error('denied')));
    const { session } = np(playing(ytVideo()));
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    expect(session.notify).toHaveBeenCalledWith('failed');
    expect(document.querySelector('.np-full')).toBeNull();
  });
});

const inRoom = (r: RoomState) => ({ ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: r });

function renderRoom(r: RoomState, desktop = true) {
  const media = stubMatchMedia({ [DESKTOP_QUERY]: desktop });
  const session = fakeSession(inRoom(r));
  const view = render(<RoomScreen session={session} state={inRoom(r)} prefs={memoryStore()} />);
  const update = (next: RoomState) => view.rerender(<RoomScreen session={session} state={inRoom(next)} prefs={memoryStore()} />);
  const cross = (toDesktop: boolean) => act(() => media.set(DESKTOP_QUERY, toDesktop));
  return { ...view, session, update, cross };
}

const song = entry('s', { title: 'Senja di Kota' });
const video = ytVideo();
const mixed = (current: string, version: number) =>
  room({ queue: [song, video], currentEntryId: current, version, playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 } });
const toggle = () => screen.getByRole('button', { name: 'Antrean dan anggota' });

describe('the desktop theater', () => {
  it('turns on for a video entry and off for a song', () => {
    const { update } = renderRoom(mixed('s', 1));
    expect(document.querySelector('main.room-theater')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Antrean dan anggota' })).toBeNull();
    update(mixed('v', 2));
    expect(document.querySelector('main.room-desktop.room-theater')).toBeTruthy();
    update(mixed('s', 3));
    expect(document.querySelector('main.room-theater')).toBeNull();
  });

  it('is desktop only: a phone keeps its one column for a video', () => {
    renderRoom(mixed('v', 1), false);
    expect(document.querySelector('main.room-theater')).toBeNull();
  });

  it('puts the full queue and the compact members list in the right column, and add behind "Tambah lagu"', () => {
    renderRoom(mixed('v', 1));
    const queue = document.getElementById('room-queue')!;
    const side = document.getElementById('room-side')!;
    expect(queue.querySelector('.queue-full')).toBeTruthy();
    expect(side.querySelector('.members-compact')).toBeTruthy();
    expect(within(side).queryByRole('button', { name: 'Keluar Ritme' })).toBeNull();
    // No inline add panel: "Tambah lagu" opens the add dialog.
    expect(screen.queryByRole('region', { name: 'Tambah ke antrean' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(screen.getByRole('dialog', { name: 'Tambah ke antrean' })).toBeTruthy();
  });

  it('keeps leaving and the host settings one tap away: the avatar stack opens the members dialog', () => {
    renderRoom(mixed('v', 1));
    fireEvent.click(screen.getByRole('button', { name: '2 anggota' }));
    const sheet = screen.getByRole('dialog', { name: 'Anggota' });
    expect(within(sheet).getByRole('button', { name: 'Keluar Ritme' })).toBeTruthy();
    expect(within(sheet).getByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeTruthy();
    // One "members-title" in the document: the compact list has its own heading id.
    expect(document.querySelectorAll('#members-title')).toHaveLength(1);
  });

  it('collapses and expands the right column from the header toggle, with aria-expanded', () => {
    renderRoom(mixed('v', 1));
    const button = toggle();
    expect(button.closest('header')).toBeTruthy();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(button.getAttribute('aria-controls')).toBe('room-queue room-side');
    fireEvent.click(button);
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('main.room-collapsed')).toBeTruthy();
    expect(document.getElementById('room-queue')!.hidden).toBe(true);
    expect(document.getElementById('room-side')!.hidden).toBe(true);
    fireEvent.click(toggle());
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(document.getElementById('room-queue')!.hidden).toBe(false);
    expect(document.querySelector('main.room-collapsed')).toBeNull();
  });

  /** The add flow, typed into: the song search with "senja", or the Video tab with a link. */
  const addRegion = () => screen.getByRole('region', { name: 'Tambah ke antrean' });
  const addDialog = () => screen.getByRole('dialog', { name: 'Tambah ke antrean' });
  const LINK = 'https://media.example.com/sawah.mp4';

  it('carries a song search typed in the inline panel into the add dialog when a video turns the theater on, focus and all', () => {
    const { update } = renderRoom(mixed('s', 1));
    const input = within(addRegion()).getByRole('searchbox', { name: 'Cari lagu' }) as HTMLInputElement;
    input.focus();
    fireEvent.input(input, { target: { value: 'senja' } });
    update(mixed('v', 2));
    expect(document.querySelector('main.room-theater')).toBeTruthy();
    const field = within(addDialog()).getByRole('searchbox', { name: 'Cari lagu' }) as HTMLInputElement;
    expect(field.value).toBe('senja');
    expect(document.activeElement).toBe(field);
  });

  it('carries the Video tab and its link into the dialog even when focus is elsewhere', () => {
    const { update } = renderRoom(mixed('s', 1));
    fireEvent.click(within(addRegion()).getByRole('tab', { name: 'Video' }));
    fireEvent.input(within(addRegion()).getByLabelText('Tempel link video'), { target: { value: LINK } });
    (document.activeElement as HTMLElement | null)?.blur();
    update(mixed('v', 2));
    const dialog = addDialog();
    expect(within(dialog).getByRole('tab', { name: 'Video' }).getAttribute('aria-selected')).toBe('true');
    expect((within(dialog).getByLabelText('Tempel link video') as HTMLInputElement).value).toBe(LINK);
  });

  it('opens no dialog for a finished search left in the panel, which is still there when the song comes back', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(JSON.stringify([]))));
    vi.stubGlobal('fetch', fetchImpl);
    const { update } = renderRoom(mixed('s', 1));
    const input = within(addRegion()).getByRole('searchbox', { name: 'Cari lagu' });
    fireEvent.input(input, { target: { value: 'senja' } });
    await act(async () => void fireEvent.submit(input.closest('form')!));
    expect(fetchImpl).toHaveBeenCalled();
    await screen.findByText('Tidak ada hasil.');
    (document.activeElement as HTMLElement | null)?.blur();
    update(mixed('v', 2));
    expect(screen.queryByRole('dialog')).toBeNull();
    update(mixed('s', 3));
    expect((within(addRegion()).getByRole('searchbox', { name: 'Cari lagu' }) as HTMLInputElement).value).toBe('senja');
  });

  it('opens no dialog when the inline panel was untouched', () => {
    const { update } = renderRoom(mixed('s', 1));
    update(mixed('v', 2));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps the add dialog, its tab and its link when the video gives way to a song, until it is closed', () => {
    const { update } = renderRoom(mixed('v', 1));
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    fireEvent.click(within(addDialog()).getByRole('tab', { name: 'Video' }));
    const link = within(addDialog()).getByLabelText('Tempel link video') as HTMLInputElement;
    link.focus();
    fireEvent.input(link, { target: { value: LINK } });
    update(mixed('s', 2));
    expect(document.querySelector('main.room-theater')).toBeNull();
    const dialog = addDialog();
    expect(within(dialog).getByLabelText('Tempel link video')).toBe(link);
    expect(link.value).toBe(LINK);
    expect(document.activeElement).toBe(link);
    // One add flow at a time: the inline panel waits behind the dialog.
    expect(screen.queryByRole('region', { name: 'Tambah ke antrean' })).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tutup' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(addRegion()).toBeTruthy();
    // The inline panel that comes back does not pull focus to its search.
    expect(document.activeElement).not.toBe(within(addRegion()).getByRole('searchbox', { name: 'Cari lagu' }));
  });

  it('still closes the add dialog when the window crosses to a phone (Task 10)', () => {
    const { cross } = renderRoom(mixed('v', 1));
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    cross(false);
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('the player keeps its place in the tree (YouTube rule)', () => {
  it('keeps the same .player element and controller from song to video (theater on) and back (theater off)', async () => {
    const { update } = renderRoom(mixed('s', 1));
    await tapGate();
    const player = document.querySelector('.player')!;
    const path = () => {
      const p = document.querySelector('.player')!;
      return [p.parentElement!.className, p.parentElement!.parentElement!.tagName, p.parentElement!.parentElement!.parentElement!.className];
    };
    const before = path();
    expect(mocks.controllers).toHaveLength(1);
    update(mixed('v', 2));
    expect(document.querySelector('main.room-theater')).toBeTruthy();
    expect(document.querySelector('.player')).toBe(player);
    expect(document.querySelector('main > .room-left > section.now-playing > .stage > .player')).toBe(player);
    expect(path()[1]).toBe(before[1]);
    update(mixed('s', 3));
    expect(document.querySelector('main.room-theater')).toBeNull();
    expect(document.querySelector('.player')).toBe(player);
    expect(path()).toEqual(before);
    expect(mocks.log).toEqual(['new']);
  });

  it('keeps it through collapsing the theater column and crossing to a phone and back', async () => {
    const { cross } = renderRoom(mixed('v', 1));
    await tapGate();
    const player = document.querySelector('.player')!;
    fireEvent.click(toggle());
    expect(document.querySelector('.player')).toBe(player);
    cross(false);
    expect(document.querySelector('main.room-theater')).toBeNull();
    expect(document.querySelector('.player')).toBe(player);
    cross(true);
    expect(document.querySelector('main.room-theater')).toBeTruthy();
    expect(document.querySelector('.player')).toBe(player);
    expect(mocks.log).toEqual(['new']);
  });

  it('keeps it entering and leaving fullscreen, which takes the existing section, never a new wrapper', async () => {
    const { request } = stubFullscreen(true);
    renderRoom(mixed('v', 1));
    await tapGate();
    const player = document.querySelector('.player')!;
    const section = player.closest('section.now-playing')!;
    const controller = mocks.controllers[0];
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Layar penuh' })));
    expect(request.mock.contexts[0]).toBe(section);
    expect(section.classList.contains('np-full')).toBe(true);
    expect(document.querySelector('.player')).toBe(player);
    expect(player.parentElement!.parentElement).toBe(section);
    expect([...player.parentElement!.children].indexOf(player)).toBe(1);
    await act(async () => void fireEvent.click(screen.getByRole('button', { name: 'Keluar dari layar penuh' })));
    expect(section.classList.contains('np-full')).toBe(false);
    expect(document.querySelector('.player')).toBe(player);
    expect(mocks.controllers).toEqual([controller]);
    expect(mocks.log).toEqual(['new']);
  });
});
