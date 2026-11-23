// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ControllerOptions } from '../player/controller';
import { memoryStore, MODE_KEY } from '../net/storage';
import { initialSession } from '../session/reducer';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { ModeSwitch } from './listen';
import { NowPlaying } from './now-playing';
import { RoomScreen } from './room';

const mocks = vi.hoisted(() => ({ log: [] as string[], opts: [] as unknown[] }));
vi.mock('../player/controller', () => ({
  PlaybackController: class {
    constructor(o: unknown) {
      mocks.opts.push(o);
      mocks.log.push('new');
    }
    update(r: { version: number }) {
      mocks.log.push(`update:${r.version}`);
    }
    resync() {
      mocks.log.push('resync');
    }
    destroy() {
      mocks.log.push('destroy');
    }
  },
}));
vi.mock('../player/youtube', () => ({ loadYouTubeApi: () => Promise.resolve({}), createYouTubePlayer: () => ({}) }));
vi.mock('../player/html5', () => ({ createVideoPlayer: () => ({}), loadHlsJs: () => Promise.reject(new Error('not used here')) }));

afterEach(() => {
  mocks.log.length = 0;
  mocks.opts.length = 0;
  cleanup();
  vi.unstubAllGlobals();
});

type Room = ReturnType<typeof room>;
const stage = (session: ReturnType<typeof fakeSession>, r: Room, mode: 'listen' | 'remote', unplayable: Record<string, true> = {}, memberId = 'm1') => (
  <NowPlaying session={session} room={r} memberId={memberId} unplayable={unplayable} online pageIsHttps mode={mode} />
);
const gate = () => screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' });

describe('ModeSwitch', () => {
  it('reports mode changes from aria-pressed toggle buttons', () => {
    const onModeChange = vi.fn();
    render(<ModeSwitch mode="listen" onModeChange={onModeChange} />);
    expect(screen.queryByRole('radio')).toBeNull();
    expect(screen.getByRole('button', { name: 'Mode dengar', pressed: true })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Mode remote', pressed: false }));
    expect(onModeChange).toHaveBeenCalledWith('remote');
  });

  it('explains remote mode only in remote mode', () => {
    const view = render(<ModeSwitch mode="listen" onModeChange={() => {}} />);
    expect(screen.queryByText('Mode remote: kontrol saja, tanpa suara di perangkat ini.')).toBeNull();
    view.rerender(<ModeSwitch mode="remote" onModeChange={() => {}} />);
    expect(screen.getByText('Mode remote: kontrol saja, tanpa suara di perangkat ini.')).toBeTruthy();
  });
});

describe('the stage before the tap', () => {
  it('shows the cover with the tap gate in listen mode and creates no player before the tap', () => {
    render(stage(fakeSession(), room(), 'listen'));
    expect(gate()).toBeTruthy();
    expect(screen.getByText('Browser butuh satu ketukan sebelum memutar suara')).toBeTruthy();
    expect(document.querySelector('.player')).toBeNull();
    expect(document.querySelector('.stage .stage-empty')).toBeTruthy();
  });

  it('shows only the cover in remote mode: no gate, no player', () => {
    const r = room({ queue: [entry('a', { thumbnailUrl: 'https://i.ytimg.com/vi/a/hqdefault.jpg' })], currentEntryId: 'a' });
    render(stage(fakeSession(), r, 'remote'));
    expect(screen.queryByRole('button', { name: 'Ketuk untuk ikut dengar' })).toBeNull();
    expect(document.querySelector('.player')).toBeNull();
    expect(document.querySelector('.stage img.stage-cover')?.getAttribute('src')).toBe('https://i.ytimg.com/vi/a/hqdefault.jpg');
    expect(screen.queryByText('Biarkan layar menyala supaya musik tidak berhenti.')).toBeNull();
  });
});

describe('RoomScreen', () => {
  const state = { ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room() };

  it('stores the chosen mode in ritme.mode', () => {
    const prefs = memoryStore();
    render(<RoomScreen session={fakeSession(state)} state={state} prefs={prefs} />);
    fireEvent.click(screen.getByRole('button', { name: 'Mode remote' }));
    expect(prefs.get(MODE_KEY)).toBe('remote');
    expect(screen.getByRole('button', { name: 'Mode remote', pressed: true })).toBeTruthy();
  });

  it('has a single End room button for the host (in the members panel) and Leave in the footer', () => {
    render(<RoomScreen session={fakeSession(state)} state={state} prefs={memoryStore()} />);
    expect(screen.getAllByRole('button', { name: /^Akhiri/ }).map((b) => b.textContent)).toEqual(['Akhiri Ritme untuk semua']);
    expect(screen.getByRole('button', { name: 'Keluar Ritme' })).toBeTruthy();
  });

  it('shows the reconnecting banner and status while offline', () => {
    const off = { ...state, connection: 'reconnecting' as const };
    render(<RoomScreen session={fakeSession(off)} state={off} prefs={memoryStore()} />);
    expect(screen.getByRole('status').textContent).toBe('Menyambung ulang…');
    // The banner, and the header status in place of "Tersinkron".
    expect(screen.getAllByText('Menyambung ulang…')).toHaveLength(2);
    expect(screen.queryByText('Tersinkron')).toBeNull();
  });

  it('says Tersinkron while online', () => {
    render(<RoomScreen session={fakeSession(state)} state={state} prefs={memoryStore()} />);
    expect(screen.getByText('Tersinkron')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('the stage after the tap gate', () => {
  const playing = (overrides: Parameters<typeof entry>[1] = {}) =>
    room({ queue: [entry('a', overrides)], currentEntryId: 'a', playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 } });
  const opts = () => mocks.opts[0] as ControllerOptions;

  it('creates the controller only after the tap and destroys it on remote and on unmount', async () => {
    const s = fakeSession();
    const r = playing();
    const view = render(stage(s, r, 'listen'));
    expect(mocks.log).not.toContain('new');
    fireEvent.click(gate());
    await Promise.resolve();
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(1);
    view.rerender(stage(s, r, 'remote'));
    expect(mocks.log.at(-1)).toBe('destroy');
    view.rerender(stage(s, r, 'listen'));
    // Back in listen mode the gate is shown again; the player needs a new tap.
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(1);
    fireEvent.click(gate());
    await Promise.resolve();
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(2);
    cleanup();
    expect(mocks.log.at(-1)).toBe('destroy');
  });

  it('shows the tap gate again after listen -> remote -> listen', async () => {
    const s = fakeSession();
    const r = playing();
    const view = render(stage(s, r, 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    expect(document.querySelector('.player')).toBeTruthy();
    view.rerender(stage(s, r, 'remote'));
    await Promise.resolve();
    view.rerender(stage(s, r, 'listen'));
    expect(gate()).toBeTruthy();
    expect(document.querySelector('.player')).toBeNull();
  });

  it('puts the player alone in the stage: no cover, gate or label over it', async () => {
    render(stage(fakeSession(), playing({ thumbnailUrl: 'https://i.ytimg.com/vi/a/hqdefault.jpg' }), 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    const slot = document.querySelector('.stage')!;
    expect([...slot.children].map((c) => c.className)).toEqual(['player']);
    expect((slot.querySelector('.player') as HTMLElement).hidden).toBe(false);
  });

  it('keeps the same player element when the entry changes shape (song -> video)', async () => {
    const s = fakeSession();
    const view = render(stage(s, playing(), 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    const player = document.querySelector('.player');
    view.rerender(stage(s, { ...playing({ kind: 'YOUTUBE_VIDEO' }), version: 2 }, 'listen'));
    expect(document.querySelector('.stage')?.classList.contains('stage-wide')).toBe(true);
    expect(document.querySelector('.player')).toBe(player);
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(1);
  });

  it('resyncs the controller when the session asks for it, and unsubscribes on unmount', async () => {
    const s = fakeSession();
    render(stage(s, playing(), 'listen'));
    expect(s.resyncListeners.size).toBe(0);
    fireEvent.click(gate());
    await Promise.resolve();
    expect(s.resyncListeners.size).toBe(1);
    for (const l of s.resyncListeners) l();
    expect(mocks.log.filter((l) => l === 'resync')).toHaveLength(1);
    cleanup();
    expect(s.resyncListeners.size).toBe(0);
  });

  it('marks the entry unplayable on the session, hides the player and offers Lewati to members with control', async () => {
    const s = fakeSession();
    const r = playing({ kind: 'YOUTUBE_VIDEO' });
    const view = render(stage(s, r, 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    expect((document.querySelector('.player') as HTMLElement).hidden).toBe(false);
    opts().onUnplayable('a');
    expect(s.markUnplayable).toHaveBeenCalledWith('a');
    view.rerender(stage(s, { ...r, version: 2 }, 'listen', { a: true }));
    expect((document.querySelector('.player') as HTMLElement).hidden).toBe(true);
    expect(document.querySelector('.stage-error')?.textContent).toContain('Video ini tidak bisa diputar di web');
    fireEvent.click(screen.getByRole('button', { name: 'Lewati' }));
    expect(s.next).toHaveBeenCalled();
  });

  it('tells members without control that the host has to skip', async () => {
    const s = fakeSession();
    const r = { ...playing({ kind: 'YOUTUBE_VIDEO' }), settings: { guestsCanControl: false, guestsCanAdd: true } };
    render(stage(s, r, 'listen', { a: true }, 'm2'));
    fireEvent.click(gate());
    await Promise.resolve();
    expect(screen.queryByRole('button', { name: 'Lewati' })).toBeNull();
    expect(screen.getByText('Menunggu host melewati video ini')).toBeTruthy();
  });

  it('shows the tap hint under the stage when the controller asks for a tap', async () => {
    render(stage(fakeSession(), playing(), 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    expect(screen.queryByText('Ketuk pemutar untuk mulai')).toBeNull();
    await act(async () => opts().onNeedsTap(true));
    const hint = screen.getByText('Ketuk pemutar untuk mulai');
    expect(hint.closest('.stage')).toBeNull();
  });

  it('takes server time from the session', async () => {
    const s = fakeSession();
    s.serverNow = () => 12345;
    render(stage(s, playing(), 'listen'));
    fireEvent.click(gate());
    await Promise.resolve();
    expect(opts().serverNow()).toBe(12345);
  });
});
