import { useEffect, useState } from 'preact/hooks';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';

/** Re-renders on every session change. */
export function useSessionState(session: SessionApi): SessionState {
  const [state, setState] = useState(session.getState());
  useEffect(() => {
    setState(session.getState());
    return session.subscribe(() => setState(session.getState()));
  }, [session]);
  return state;
}

/** Server time, refreshed every `intervalMs` (for progress bars). */
export function useServerNow(session: SessionApi, intervalMs = 500): number {
  const [now, setNow] = useState(session.serverNow());
  useEffect(() => {
    const id = setInterval(() => setNow(session.serverNow()), intervalMs);
    return () => clearInterval(id);
  }, [session, intervalMs]);
  return now;
}
