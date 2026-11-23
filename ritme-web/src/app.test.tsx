// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { App } from './app';
import { MODE_KEY, NAME_KEY, RESUME_KEY, memoryStore } from './net/storage';
import { initialSession } from './session/reducer';
import { room } from './testing/builders';
import { fakeSession } from './testing/fake-session';

beforeEach(() => history.replaceState(null, '', '/'));
afterEach(cleanup);

function stores() {
  const prefs = memoryStore();
  prefs.set(MODE_KEY, 'remote');
  return { prefs, tabStore: memoryStore() };
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
    expect(screen.getByRole('alert').textContent).toBe('Kamu dikeluarkan dari Ritme');
  });
});
