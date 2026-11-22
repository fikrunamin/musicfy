/** A string key/value store; sessionStorage and localStorage can throw (private mode, blocked). */
export interface KeyValueStore {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

export function safeStore(getStorage: () => Storage): KeyValueStore {
  return {
    get(key) {
      try {
        return getStorage().getItem(key);
      } catch {
        return null;
      }
    },
    set(key, value) {
      try {
        getStorage().setItem(key, value);
      } catch {
        // Not persisted; the app still works for this page load.
      }
    },
    remove(key) {
      try {
        getStorage().removeItem(key);
      } catch {
        // Nothing to clean up.
      }
    },
  };
}

export function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return {
    get: (key) => data.get(key) ?? null,
    set: (key, value) => void data.set(key, value),
    remove: (key) => void data.delete(key),
  };
}

/** Resume token for the room this tab is in (sessionStorage: per tab, survives reloads). */
export const RESUME_KEY = 'ritme.resume';
/** Display name (localStorage: remembered across visits). */
export const NAME_KEY = 'ritme.name';
/** 'listen' or 'remote' (localStorage). */
export const MODE_KEY = 'ritme.mode';

export interface ResumeInfo {
  code: string;
  token: string;
}

export function readResume(store: KeyValueStore): ResumeInfo | null {
  const raw = store.get(RESUME_KEY);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<ResumeInfo>;
    return typeof v.code === 'string' && typeof v.token === 'string' ? { code: v.code, token: v.token } : null;
  } catch {
    return null;
  }
}

export function writeResume(store: KeyValueStore, info: ResumeInfo): void {
  store.set(RESUME_KEY, JSON.stringify(info));
}
