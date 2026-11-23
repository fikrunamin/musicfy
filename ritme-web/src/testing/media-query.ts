import { vi } from 'vitest';

type Listener = (event: MediaQueryListEvent) => void;

/**
 * Stubs `window.matchMedia` (jsdom has none) for desktop and reduced-motion tests. `initial` says
 * which queries match; `set` changes one and fires its `change` listeners, as a resize would.
 * Undo with `vi.unstubAllGlobals()`.
 */
export function stubMatchMedia(initial: Record<string, boolean> = {}) {
  const matches = new Map(Object.entries(initial));
  const listeners = new Map<string, Set<Listener>>();
  const of = (query: string) => {
    let set = listeners.get(query);
    if (!set) listeners.set(query, (set = new Set()));
    return set;
  };
  const matchMedia = (query: string): MediaQueryList => {
    const own = of(query);
    const list = {
      media: query,
      get matches() {
        return matches.get(query) ?? false;
      },
      onchange: null,
      addEventListener: (_type: string, l: Listener) => own.add(l),
      removeEventListener: (_type: string, l: Listener) => own.delete(l),
      addListener: (l: Listener) => own.add(l),
      removeListener: (l: Listener) => own.delete(l),
      dispatchEvent: () => false,
    };
    return list as unknown as MediaQueryList;
  };
  vi.stubGlobal('matchMedia', matchMedia);
  return {
    /** The window now matches (or no longer matches) `query`. */
    set(query: string, value: boolean) {
      matches.set(query, value);
      for (const l of [...of(query)]) l({ matches: value, media: query } as MediaQueryListEvent);
    },
    /** How many change listeners are subscribed to `query`. */
    listeners: (query: string) => of(query).size,
  };
}
