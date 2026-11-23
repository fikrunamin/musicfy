// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { memoryStore } from '../net/storage';
import { initialSession } from '../session/reducer';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { stubMatchMedia } from '../testing/media-query';
import { DESKTOP_QUERY } from './hooks';
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

afterEach(() => {
  mocks.log.length = 0;
  mocks.controllers.length = 0;
  cleanup();
  vi.unstubAllGlobals();
});

const playingRoom = room({
  queue: [entry('a', { title: 'Senja di Kota' }), entry('b', { title: 'Kembali Pulang' })],
  currentEntryId: 'a',
  playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 },
});
const inRoom = (r = playingRoom) => ({ ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: r });

function renderRoom(desktop: boolean, r = playingRoom) {
  const media = stubMatchMedia({ [DESKTOP_QUERY]: desktop });
  const state = inRoom(r);
  const session = fakeSession(state);
  const view = render(<RoomScreen session={session} state={state} prefs={memoryStore()} />);
  const cross = (toDesktop: boolean) => act(() => media.set(DESKTOP_QUERY, toDesktop));
  return { ...view, session, cross };
}

const tapGate = async () => {
  fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
  await Promise.resolve();
};

describe('RoomScreen across the desktop breakpoint', () => {
  it('keeps the same .player element and controller when the window crosses 1024 px, both ways', async () => {
    const { cross } = renderRoom(false);
    await tapGate();
    const player = document.querySelector('.player');
    expect(player).toBeTruthy();
    expect(mocks.controllers).toHaveLength(1);
    const controller = mocks.controllers[0];

    cross(true);
    expect(document.querySelector('main.room-desktop')).toBeTruthy();
    expect(document.querySelector('.player')).toBe(player);
    expect(player!.isConnected).toBe(true);

    cross(false);
    expect(document.querySelector('main.room-desktop')).toBeNull();
    expect(document.querySelector('.player')).toBe(player);

    cross(true);
    expect(document.querySelector('.player')).toBe(player);
    expect(mocks.controllers).toEqual([controller]);
    expect(mocks.log).toEqual(['new']);
    // The tap gate does not come back: the player keeps playing through the resize.
    expect(screen.queryByRole('button', { name: 'Ketuk untuk ikut dengar' })).toBeNull();
  });

  it('closes the members sheet when the window becomes desktop, and does not reopen it on the way back', () => {
    const { cross } = renderRoom(false);
    fireEvent.click(screen.getByRole('button', { name: '2 anggota' }));
    expect(screen.getByRole('dialog', { name: 'Anggota' })).toBeTruthy();
    cross(true);
    expect(screen.queryByRole('dialog')).toBeNull();
    // Nothing is left inert behind the closed sheet.
    expect(document.querySelector('main')!.hasAttribute('inert')).toBe(false);
    expect(document.querySelector('main')!.hasAttribute('aria-hidden')).toBe(false);
    cross(false);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('closes the add sheet when the window becomes desktop, and does not reopen it on the way back', () => {
    const { cross } = renderRoom(false);
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(screen.getByRole('dialog', { name: 'Tambah ke antrean' })).toBeTruthy();
    cross(true);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.querySelector('main')!.hasAttribute('inert')).toBe(false);
    cross(false);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('moves focus to the room title when the focused right column goes away on a phone', () => {
    const { cross } = renderRoom(true);
    // Mounting does not move focus anywhere.
    expect(document.activeElement).toBe(document.body);
    const leave = screen.getByRole('button', { name: 'Keluar Ritme' });
    leave.focus();
    expect(document.activeElement).toBe(leave);
    cross(false);
    expect(leave.isConnected).toBe(false);
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 }));
  });

  it('leaves focus where it is when it survives the crossing', () => {
    const { cross } = renderRoom(true);
    const pause = screen.getByRole('button', { name: 'Jeda' });
    pause.focus();
    cross(false);
    expect(document.activeElement).toBe(pause);
    cross(true);
    expect(document.activeElement).toBe(pause);
  });

  it('closes a kick confirmation open in the desktop members panel when the window becomes a phone', () => {
    const { cross } = renderRoom(true);
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan: Sari' }));
    expect(screen.getByRole('alertdialog')).toBeTruthy();
    cross(false);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.querySelector('main')!.hasAttribute('inert')).toBe(false);
  });
});

describe('RoomScreen on desktop', () => {
  it('shows add and members as inline panels beside the full queue, with no sheet openers', () => {
    renderRoom(true);
    const add = screen.getByRole('region', { name: 'Tambah ke antrean' });
    expect(within(add).getByLabelText('Cari lagu')).toBeTruthy();
    expect(within(add).queryByRole('button', { name: 'Tutup' })).toBeNull();
    const members = screen.getByRole('region', { name: 'Anggota' });
    expect(within(members).getByRole('button', { name: 'Keluar Ritme' })).toBeTruthy();
    expect(within(members).getByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeTruthy();
    expect(within(members).queryByRole('button', { name: 'Tutup' })).toBeNull();
    // The full queue: "Antrean" with its "Sedang diputar" and "Berikutnya" sections.
    const queue = screen.getByRole('region', { name: 'Antrean' });
    expect(within(queue).getByRole('heading', { level: 3, name: 'Sedang diputar' })).toBeTruthy();
    expect(within(queue).getByRole('heading', { level: 3, name: 'Berikutnya' })).toBeTruthy();
    expect(within(queue).getByText('Senja di Kota')).toBeTruthy();
    // No avatar-stack "N anggota" button and no queue jump button: both are on screen already.
    expect(screen.queryByRole('button', { name: /^\d+ anggota$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Antrean' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('has the desktop header: logo, "Tersinkron · 2 anggota" and a "Bagikan" pill with its label', () => {
    renderRoom(true);
    const header = document.querySelector('.room-header')!;
    expect(header.querySelector('.room-logo')).toBeTruthy();
    expect(within(header as HTMLElement).getByText('Tersinkron · 2 anggota')).toBeTruthy();
    const share = within(header as HTMLElement).getByRole('button', { name: 'Bagikan' });
    expect(share.textContent).toBe('Bagikan');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Ritme · K7M2QX');
  });

  it('"Tambah lagu" moves focus to the search field in the panel instead of opening a sheet', () => {
    renderRoom(true);
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByLabelText('Cari lagu'));
  });

  it('"Tambah lagu" goes back to the Lagu tab before focusing the song search', () => {
    renderRoom(true);
    const add = screen.getByRole('region', { name: 'Tambah ke antrean' });
    fireEvent.click(within(add).getByRole('tab', { name: 'Video' }));
    expect(within(add).getByRole('tab', { name: 'Video' }).getAttribute('aria-selected')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(within(add).getByRole('tab', { name: 'Lagu' }).getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(within(add).getByLabelText('Cari lagu'));
    // Again, with Lagu already selected: still the song search.
    (document.activeElement as HTMLElement).blur();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(document.activeElement).toBe(within(add).getByLabelText('Cari lagu'));
  });

  it('hides the add panel while the host keeps guests from adding', () => {
    const locked = { ...playingRoom, settings: { guestsCanControl: true, guestsCanAdd: false } };
    const media = stubMatchMedia({ [DESKTOP_QUERY]: true });
    const state = { ...inRoom(locked), memberId: 'm2' };
    render(<RoomScreen session={fakeSession(state)} state={state} prefs={memoryStore()} />);
    expect(screen.queryByRole('region', { name: 'Tambah ke antrean' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Tambah lagu' })).toBeNull();
    expect(screen.getByRole('region', { name: 'Anggota' })).toBeTruthy();
    expect(media.listeners(DESKTOP_QUERY)).toBe(1);
  });

  it('lays the room out as three columns, each its own scroll container', () => {
    renderRoom(true);
    const main = document.querySelector('main.room')!;
    expect(main.classList.contains('room-desktop')).toBe(true);
    const cols = [...main.children].filter((c) => c.classList.contains('room-col'));
    expect(cols).toHaveLength(3);
    expect(cols[0].classList.contains('room-left')).toBe(true);
    expect(cols[1].classList.contains('room-queue')).toBe(true);
    expect(cols[2].classList.contains('room-side')).toBe(true);
    expect(cols[0].querySelector('.now-playing .stage')).toBeTruthy();
    expect(cols[0].querySelector('.room-header')).toBeTruthy();
    expect(cols[1].querySelector('section[aria-labelledby="queue-title"]')).toBeTruthy();
    expect(cols[2].querySelector('.add-panel')).toBeTruthy();
    expect(cols[2].querySelector('section[aria-labelledby="members-title"]')).toBeTruthy();
  });
});

describe('RoomScreen on a phone', () => {
  it('keeps the single column: upcoming queue, no inline panels, members and add behind sheets', () => {
    renderRoom(false);
    expect(document.querySelector('main.room-desktop')).toBeNull();
    expect(document.querySelector('.room-side')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Anggota' })).toBeNull();
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
    expect(screen.getByRole('region', { name: 'Berikutnya' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '2 anggota' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Antrean' })).toBeTruthy();
  });
});
