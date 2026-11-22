// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { memoryStore, MODE_KEY } from '../net/storage';
import { initialSession } from '../session/reducer';
import { room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { ListenPanel } from './listen';
import { RoomScreen } from './room';

afterEach(() => {
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
