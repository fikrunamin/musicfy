import { vi } from 'vitest';
import { type SessionState, initialSession } from '../session/reducer';
import type { SessionApi } from '../session/session';

/** A SessionApi whose actions are spies, for component tests. */
export function fakeSession(state: SessionState = initialSession) {
  const resyncListeners = new Set<() => void>();
  const api = {
    getState: () => state,
    subscribe: () => () => {},
    serverNow: () => 0,
    onResync: (listener: () => void) => {
      resyncListeners.add(listener);
      return () => resyncListeners.delete(listener);
    },
    create: vi.fn(),
    join: vi.fn(),
    leave: vi.fn(),
    dismiss: vi.fn(),
    play: vi.fn(),
    pause: vi.fn(),
    seek: vi.fn(),
    next: vi.fn(),
    previous: vi.fn(),
    playEntry: vi.fn(),
    add: vi.fn(),
    remove: vi.fn(),
    move: vi.fn(),
    setGuestsCanControl: vi.fn(),
    kick: vi.fn(),
    transferHost: vi.fn(),
    endRoom: vi.fn(),
    markUnplayable: vi.fn(),
    notify: vi.fn(),
  } satisfies SessionApi;
  /** resyncListeners: test handle on the listeners registered through onResync. */
  return { ...api, resyncListeners };
}
