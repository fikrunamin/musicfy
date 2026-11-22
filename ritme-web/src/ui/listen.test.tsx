// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ControllerOptions } from '../player/controller';
import { memoryStore, MODE_KEY } from '../net/storage';
import { initialSession } from '../session/reducer';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { ListenPanel } from './listen';
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
vi.mock('../player/html5', () => ({ createVideoPlayer: () => ({}) }));

afterEach(() => {
  mocks.log.length = 0;
  mocks.opts.length = 0;
  cleanup();
  vi.unstubAllGlobals();
});

describe('ListenPanel', () => {
  it('shows the tap gate in listen mode and creates no player before the tap', () => {
    render(<ListenPanel session={fakeSession()} room={room()} unplayable={{}} mode="listen" onModeChange={() => {}} pageIsHttps />);
    expect(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' })).toBeTruthy();
    expect(document.querySelector('.player')).toBeNull();
  });

  it('shows only the hint in remote mode', () => {
    render(<ListenPanel session={fakeSession()} room={room()} unplayable={{}} mode="remote" onModeChange={() => {}} pageIsHttps />);
    expect(screen.queryByRole('button', { name: 'Ketuk untuk ikut dengar' })).toBeNull();
    expect(screen.getByText('Mode remote: kontrol saja, tanpa suara di perangkat ini.')).toBeTruthy();
  });

  it('reports mode changes', () => {
    const onModeChange = vi.fn();
    render(<ListenPanel session={fakeSession()} room={room()} unplayable={{}} mode="listen" onModeChange={onModeChange} pageIsHttps />);
    fireEvent.click(screen.getByRole('radio', { name: 'Mode remote' }));
    expect(onModeChange).toHaveBeenCalledWith('remote');
  });
});

describe('RoomScreen', () => {
  const state = { ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room() };

  it('stores the chosen mode in ritme.mode', () => {
    const prefs = memoryStore();
    render(<RoomScreen session={fakeSession(state)} state={state} prefs={prefs} />);
    fireEvent.click(screen.getByRole('radio', { name: 'Mode remote' }));
    expect(prefs.get(MODE_KEY)).toBe('remote');
  });

  it('shows the reconnecting banner while offline', () => {
    const off = { ...state, connection: 'reconnecting' as const };
    render(<RoomScreen session={fakeSession(off)} state={off} prefs={memoryStore()} />);
    expect(screen.getByText('Menyambung ulang…')).toBeTruthy();
  });
});

describe('ListenPanel after the tap gate', () => {
  const playing = () => room({ queue: [entry('a')], currentEntryId: 'a', playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 } });
  const panel = (session: ReturnType<typeof fakeSession>, r: ReturnType<typeof room>, mode: 'listen' | 'remote', unplayable: Record<string, true> = {}) => (
    <ListenPanel session={session} room={r} unplayable={unplayable} mode={mode} onModeChange={() => {}} pageIsHttps />
  );
  const opts = () => mocks.opts[0] as ControllerOptions;

  it('creates the controller only after the tap and destroys it on remote and on unmount', async () => {
    const s = fakeSession();
    const r = playing();
    const view = render(panel(s, r, 'listen'));
    expect(mocks.log).not.toContain('new');
    fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
    await Promise.resolve();
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(1);
    view.rerender(panel(s, r, 'remote'));
    expect(mocks.log.at(-1)).toBe('destroy');
    view.rerender(panel(s, r, 'listen'));
    expect(mocks.log.filter((l) => l === 'new')).toHaveLength(2);
    cleanup();
    expect(mocks.log.at(-1)).toBe('destroy');
  });

  it('adds and removes the same visibilitychange listener and resyncs only when visible', async () => {
    const add = vi.spyOn(document, 'addEventListener');
    const rem = vi.spyOn(document, 'removeEventListener');
    const s = fakeSession();
    render(panel(s, playing(), 'listen'));
    fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
    await Promise.resolve();
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(mocks.log).not.toContain('resync');
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(mocks.log.filter((l) => l === 'resync')).toHaveLength(1);
    cleanup();
    const added = add.mock.calls.filter((c) => c[0] === 'visibilitychange').map((c) => c[1]);
    const removed = rem.mock.calls.filter((c) => c[0] === 'visibilitychange').map((c) => c[1]);
    expect(added.length).toBeGreaterThan(0);
    expect(added.every((f) => removed.includes(f))).toBe(true);
    add.mockRestore();
    rem.mockRestore();
  });

  it('marks the entry unplayable on the session and hides the player', async () => {
    const s = fakeSession();
    const r = playing();
    const view = render(panel(s, r, 'listen'));
    fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
    await Promise.resolve();
    expect((document.querySelector('.player') as HTMLElement).hidden).toBe(false);
    opts().onUnplayable('a');
    expect(s.markUnplayable).toHaveBeenCalledWith('a');
    view.rerender(panel(s, { ...r, version: 2 }, 'listen', { a: true }));
    expect((document.querySelector('.player') as HTMLElement).hidden).toBe(true);
  });

  it('shows the tap hint when the controller asks for a tap', async () => {
    render(panel(fakeSession(), playing(), 'listen'));
    fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
    await Promise.resolve();
    expect(screen.queryByText('Ketuk pemutar untuk mulai')).toBeNull();
    await act(async () => opts().onNeedsTap(true));
    expect(screen.getByText('Ketuk pemutar untuk mulai')).toBeTruthy();
  });

  it('takes server time from the session', async () => {
    const s = fakeSession();
    s.serverNow = () => 12345;
    render(panel(s, playing(), 'listen'));
    fireEvent.click(screen.getByRole('button', { name: 'Ketuk untuk ikut dengar' }));
    await Promise.resolve();
    expect(opts().serverNow()).toBe(12345);
  });
});
