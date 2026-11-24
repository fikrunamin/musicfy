// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import type { EndReason } from './net/connection';
import { MODE_KEY, NAME_KEY, RESUME_KEY, memoryStore } from './net/storage';
import { type SessionState, initialSession } from './session/reducer';
import { room } from './testing/builders';
import { fakeSession } from './testing/fake-session';

beforeEach(() => history.replaceState(null, '', '/'));
afterEach(cleanup);

function stores() {
  const prefs = memoryStore();
  prefs.set(MODE_KEY, 'remote');
  return { prefs, tabStore: memoryStore() };
}

/** A fake session whose state the test moves on, re-rendering the App like the real one. */
function liveSession(initial: SessionState = initialSession) {
  let state = initial;
  const listeners = new Set<() => void>();
  const session = fakeSession();
  return Object.assign(session, {
    getState: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    set(next: Partial<SessionState>) {
      state = { ...state, ...next };
      act(() => listeners.forEach((listener) => listener()));
    },
  });
}

const connecting = { phase: 'connecting' as const, connection: 'connecting' as const };
const ended = (reason: EndReason) => ({ phase: 'ended' as const, ended: reason, room: null, memberId: null });
const inRoom = (code = 'K7M2QX') => ({ phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room({ code }) });

function startAsSari(session: ReturnType<typeof liveSession>) {
  fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Sari' } });
  fireEvent.input(screen.getByLabelText('Kode room'), { target: { value: 'K7M2QX' } });
  fireEvent.click(screen.getByRole('button', { name: 'Gabung' }));
  expect(session.join).toHaveBeenLastCalledWith('K7M2QX', 'Sari');
}

describe('App', () => {
  it('shows the start screen at /', () => {
    render(<App session={fakeSession()} {...stores()} />);
    expect(screen.getByRole('button', { name: 'Mulai Ritme' })).toBeTruthy();
  });

  it('opens the join flow for /r/{code}', () => {
    history.replaceState(null, '', '/r/k7m2qx');
    render(<App session={fakeSession()} {...stores()} />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Kamu diajak dengar bareng');
    // The code comes from the link: shown in the room chip, not as an editable field (flow B).
    expect(screen.getByText('Room K7M2QX')).toBeTruthy();
    expect(screen.queryByLabelText('Kode room')).toBeNull();
  });

  it('resumes automatically after a reload inside a room', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const session = fakeSession();
    const { prefs, tabStore } = stores();
    prefs.set(NAME_KEY, 'Sari');
    tabStore.set(RESUME_KEY, JSON.stringify({ code: 'K7M2QX', token: 'tok' }));
    render(<App session={session} prefs={prefs} tabStore={tabStore} />);
    expect(session.join).toHaveBeenCalledWith('K7M2QX', 'Sari');
  });

  it('does not auto-join without a token for that room', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const session = fakeSession();
    const { prefs, tabStore } = stores();
    prefs.set(NAME_KEY, 'Sari');
    render(<App session={session} prefs={prefs} tabStore={tabStore} />);
    expect(session.join).not.toHaveBeenCalled();
  });

  it('shows the room and puts its link in the address bar', () => {
    const state = { ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room() };
    render(<App session={fakeSession(state)} {...stores()} />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Ritme · K7M2QX');
    expect(location.pathname).toBe('/r/K7M2QX');
  });

  it('goes back to / with the reason after being kicked', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const state = { ...initialSession, phase: 'ended' as const, ended: { kind: 'kicked' as const } };
    render(<App session={fakeSession(state)} {...stores()} />);
    expect(location.pathname).toBe('/');
    // The reason is a full screen now (handoff H2D5o), read out through the focused title.
    const title = screen.getByRole('heading', { level: 1 });
    expect(title.textContent).toBe('Kamu dikeluarkan dari Ritme');
    expect(document.activeElement).toBe(title);
    expect(screen.getByText('Host mengeluarkanmu dari room ini.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Mulai Ritme' })).toBeNull();
  });
});

describe('App connection states', () => {
  it('shows the connecting screen with the room code being joined', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    startAsSari(session);
    session.set(connecting);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Menghubungkan…');
    expect(screen.getByText('Room K7M2QX')).toBeTruthy();
    expect(screen.queryByLabelText('Nama tampilan')).toBeNull();
  });

  it('shows the connecting screen without a code while creating', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Budi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    session.set(connecting);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Menghubungkan…');
    expect(screen.queryByText(/^Room /)).toBeNull();
  });

  it('shows the code of a resumed room while connecting after a reload', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const session = liveSession();
    const { prefs, tabStore } = stores();
    prefs.set(NAME_KEY, 'Sari');
    tabStore.set(RESUME_KEY, JSON.stringify({ code: 'K7M2QX', token: 'tok' }));
    render(<App session={session} prefs={prefs} tabStore={tabStore} />);
    session.set(connecting);
    expect(screen.getByText('Room K7M2QX')).toBeTruthy();
  });

  it('"Coba lagi" joins the same room again after the server was unreachable', () => {
    history.replaceState(null, '', '/');
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    startAsSari(session);
    session.set(connecting);
    session.set(ended({ kind: 'unreachable' }));
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Tidak bisa terhubung ke server Ritme');
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    expect(session.join).toHaveBeenCalledTimes(2);
    expect(session.join).toHaveBeenLastCalledWith('K7M2QX', 'Sari');
    // Like Home: the ended session is dismissed first, then the start goes out.
    expect(session.dismiss.mock.invocationCallOrder.at(-1)!).toBeLessThan(session.join.mock.invocationCallOrder.at(-1)!);
    expect(session.create).not.toHaveBeenCalled();
  });

  it('"Coba lagi" creates again when the failed start was a new room', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Budi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    session.set(ended({ kind: 'unreachable' }));
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    expect(session.create).toHaveBeenCalledTimes(2);
    expect(session.create).toHaveBeenLastCalledWith('Budi');
    expect(session.join).not.toHaveBeenCalled();
  });

  it('"Coba lagi" after the connection was lost rejoins the room it was in', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Budi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    session.set(inRoom('Q2W3E4'));
    session.set(ended({ kind: 'lost' }));
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Terputus dari Ritme');
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    // Not a second, empty room: back into the one that was lost.
    expect(session.join).toHaveBeenCalledWith('Q2W3E4', 'Budi');
    expect(session.create).toHaveBeenCalledTimes(1);
  });

  it('"Coba lagi" resumes the reloaded room', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const session = liveSession();
    const { prefs, tabStore } = stores();
    prefs.set(NAME_KEY, 'Sari');
    tabStore.set(RESUME_KEY, JSON.stringify({ code: 'K7M2QX', token: 'tok' }));
    render(<App session={session} prefs={prefs} tabStore={tabStore} />);
    session.set(ended({ kind: 'unreachable' }));
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    expect(session.join).toHaveBeenCalledTimes(2);
    expect(session.join).toHaveBeenLastCalledWith('K7M2QX', 'Sari');
  });

  it('"Coba lagi" with nothing to retry goes back to the start screen', () => {
    const session = liveSession({ ...initialSession, ...ended({ kind: 'lost' }) });
    render(<App session={session} {...stores()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    expect(session.dismiss).toHaveBeenCalled();
    expect(session.join).not.toHaveBeenCalled();
    expect(session.create).not.toHaveBeenCalled();
    // The dismissed session is idle again: the start screen, with no banner.
    session.set({ ...initialSession });
    expect(screen.getByRole('button', { name: 'Mulai Ritme' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('"Kembali" after the server was unreachable keeps the typed code and name on Home', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    startAsSari(session);
    session.set(connecting);
    session.set(ended({ kind: 'unreachable' }));
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    session.set({ ...initialSession });
    expect((screen.getByLabelText('Kode room') as HTMLInputElement).value).toBe('K7M2QX');
    expect((screen.getByLabelText('Nama tampilan') as HTMLInputElement).value).toBe('Sari');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('does not bring that code back after a later start', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    startAsSari(session);
    session.set(ended({ kind: 'unreachable' }));
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    session.set({ ...initialSession });
    // A new room this time, refused: Home comes back without the old code.
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    session.set(connecting);
    session.set(ended({ kind: 'rejected', code: 'RATE_LIMITED' }));
    expect(screen.getByRole('alert').textContent).toBe('Terlalu banyak percobaan, coba lagi sebentar');
    expect((screen.getByLabelText('Kode room') as HTMLInputElement).value).toBe('');
  });

  it('moves focus to the title of the connecting screen, and of Home after "Kembali"', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    // At first load Home leaves focus where the browser put it.
    expect(document.activeElement).toBe(document.body);
    startAsSari(session);
    session.set(connecting);
    const connectingTitle = screen.getByRole('heading', { level: 1 });
    expect(connectingTitle.textContent).toBe('Menghubungkan…');
    expect(document.activeElement).toBe(connectingTitle);
    session.set(ended({ kind: 'lost' }));
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    session.set({ ...initialSession });
    const homeTitle = screen.getByRole('heading', { level: 1 });
    expect(homeTitle.textContent).toBe('Ritme');
    expect(document.activeElement).toBe(homeTitle);
  });

  it.each<[EndReason, string]>([
    [{ kind: 'lost' }, 'Terputus dari Ritme'],
    [{ kind: 'unreachable' }, 'Tidak bisa terhubung ke server Ritme'],
    [{ kind: 'closed' }, 'Ritme sudah diakhiri'],
    [{ kind: 'kicked' }, 'Kamu dikeluarkan dari Ritme'],
  ])('"Kembali" on %j dismisses back to Home', (reason, title) => {
    const session = liveSession({ ...initialSession, ...ended(reason) });
    render(<App session={session} {...stores()} />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(title);
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    expect(session.dismiss).toHaveBeenCalledTimes(1);
    expect(session.join).not.toHaveBeenCalled();
    session.set({ ...initialSession });
    expect(screen.getByRole('button', { name: 'Mulai Ritme' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('"Muat ulang" reloads the page, which stays on the room link', () => {
    history.replaceState(null, '', '/r/K7M2QX');
    const reload = vi.fn();
    const session = fakeSession({ ...initialSession, ...ended({ kind: 'rejected', code: 'UPGRADE_REQUIRED' }) });
    render(<App session={session} {...stores()} reload={reload} />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Perlu versi baru');
    // The reload lands on the invite for the same room, not on the bare start screen.
    expect(location.pathname).toBe('/r/K7M2QX');
    fireEvent.click(screen.getByRole('button', { name: 'Muat ulang' }));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(session.dismiss).not.toHaveBeenCalled();
  });

  it.each<[EndReason, string]>([
    [{ kind: 'rejected', code: 'ROOM_NOT_FOUND' }, 'Room tidak ditemukan'],
    [{ kind: 'rejected', code: 'ROOM_FULL' }, 'Room penuh'],
    [{ kind: 'rejected', code: 'RATE_LIMITED' }, 'Terlalu banyak percobaan, coba lagi sebentar'],
    [{ kind: 'rejected', code: 'INVALID' }, 'Nama atau kode tidak valid'],
  ])('other refusals (%j) stay a banner on Home', (reason, text) => {
    render(<App session={fakeSession({ ...initialSession, ...ended(reason) })} {...stores()} />);
    expect(screen.getByRole('alert').textContent).toBe(text);
    expect(screen.getByRole('button', { name: 'Mulai Ritme' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Coba lagi' })).toBeNull();
  });

  it('a refused join comes back to Home with the typed code kept, to fix it', () => {
    const session = liveSession();
    render(<App session={session} {...stores()} />);
    startAsSari(session);
    session.set(connecting);
    session.set(ended({ kind: 'rejected', code: 'ROOM_NOT_FOUND' }));
    expect(screen.getByRole('alert').textContent).toBe('Room tidak ditemukan');
    expect((screen.getByLabelText('Kode room') as HTMLInputElement).value).toBe('K7M2QX');
    expect((screen.getByLabelText('Nama tampilan') as HTMLInputElement).value).toBe('Sari');
  });

  it('leaving shows Home without a banner', () => {
    render(<App session={fakeSession({ ...initialSession, ...ended({ kind: 'left' }) })} {...stores()} />);
    expect(screen.getByRole('button', { name: 'Mulai Ritme' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keeps the room, with its reconnect banner, while reconnecting', () => {
    const session = liveSession({ ...initialSession, ...inRoom() });
    render(<App session={session} {...stores()} />);
    session.set({ connection: 'reconnecting' });
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Ritme · K7M2QX');
    expect(screen.getAllByText('Menyambung ulang…').length).toBeGreaterThan(0);
  });
});
