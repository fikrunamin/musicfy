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

/** The desktop room (three columns) from this width up; below it the phone layout. */
export const DESKTOP_QUERY = '(min-width: 1024px)';

const mediaList = (query: string): MediaQueryList | null =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query) : null;

/**
 * Whether the window matches `query`, following it as the window is resized. A pure UI hook: it
 * only picks a layout. False where the browser has no matchMedia (the phone layout works everywhere).
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => mediaList(query)?.matches ?? false);
  useEffect(() => {
    const list = mediaList(query);
    if (!list) return;
    const onChange = () => setMatches(list.matches);
    onChange(); // the query changed, or the window was resized between render and this effect
    if (typeof list.addEventListener === 'function') {
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    }
    list.addListener(onChange); // Safari before 14
    return () => list.removeListener(onChange);
  }, [query]);
  return matches;
}

/**
 * The add flow's draft (tab, query, results, link, title, preview), held by the room so it outlives
 * the panel: the inline panel and the add dialog are different elements, and the theater switches
 * between them at a track boundary. A pure UI hook.
 */
export type Draft = Record<string, unknown>;

/** useState that also writes each value to `draft[key]`, and starts from it when there is one. */
export function useDraft<T>(draft: Draft | undefined, key: string, init: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(draft && key in draft ? (draft[key] as T) : init);
  return [
    value,
    (v: T) => {
      if (draft) draft[key] = v;
      setValue(v);
    },
  ];
}
