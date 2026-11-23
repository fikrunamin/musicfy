import { useEffect, useRef, useState } from 'preact/hooks';
import { type KeyValueStore, NAME_KEY, readResume } from './net/storage';
import type { SessionApi } from './session/session';
import { codeFromPath, normalizeName } from './ui/format';
import { HomeScreen } from './ui/home';
import { useSessionState } from './ui/hooks';
import { RoomScreen } from './ui/room';
import { ConnectingScreen, EndScreen, endKind } from './ui/status-screens';
import { Toast } from './ui/toast';

interface Props {
  session: SessionApi;
  /** localStorage: name, mode. */
  prefs: KeyValueStore;
  /** sessionStorage: resume token. */
  tabStore: KeyValueStore;
  /** "Muat ulang" on the upgrade screen (a stub in tests). */
  reload?: () => void;
}

export function App({ session, prefs, tabStore, reload = () => location.reload() }: Props) {
  const state = useSessionState(session);
  /**
   * What "Coba lagi" starts again: the room code, null for a new room, undefined before any start.
   * Once in a room it is that room's code, so a lost connection rejoins it instead of creating another.
   */
  const lastStart = useRef<string | null | undefined>(undefined);
  const ending = state.phase === 'ended' && state.ended ? endKind(state.ended) : null;
  const [path, setPath] = useState(location.pathname);
  const navigate = (to: string) => {
    if (location.pathname === to) return;
    history.replaceState(null, '', to);
    setPath(to);
  };

  // The address bar follows the room, so a reload (or a shared tab) lands back in it.
  useEffect(() => {
    if (state.phase === 'in-room' && state.room) {
      lastStart.current = state.room.code;
      navigate(`/r/${state.room.code}`);
    }
    // Unreachable and upgrade keep the room link, so "Coba lagi" or a reload lands on it again.
    if (state.phase === 'ended' && state.ended && ending !== 'unreachable' && ending !== 'upgrade') navigate('/');
  }, [state.phase, state.room?.code]);

  // Reloaded inside a room: resume with the token from this tab.
  useEffect(() => {
    const code = codeFromPath(location.pathname);
    const name = normalizeName(prefs.get(NAME_KEY) ?? '');
    if (code && name && readResume(tabStore)?.code === code) {
      lastStart.current = code;
      session.join(code, name);
    }
  }, []);

  // Same as Home: the ended session is dismissed, then the last start goes out again.
  const retry = () => {
    const start = lastStart.current;
    const name = normalizeName(prefs.get(NAME_KEY) ?? '');
    session.dismiss();
    if (start === undefined || !name) return;
    if (start === null) session.create(name);
    else session.join(start, name);
  };

  // A refused join (wrong code, full room…) comes back to Home with the code still typed in.
  const refusedCode = state.phase === 'ended' && state.ended?.kind === 'rejected' ? (lastStart.current ?? null) : null;

  return (
    <>
      {state.phase === 'in-room' && state.room ? (
        <RoomScreen session={session} state={{ ...state, room: state.room }} prefs={prefs} />
      ) : state.phase === 'connecting' ? (
        <ConnectingScreen code={lastStart.current ?? null} />
      ) : ending ? (
        <EndScreen kind={ending} onRetry={retry} onBack={() => session.dismiss()} onReload={reload} />
      ) : (
        <HomeScreen
          session={session}
          state={state}
          routeCode={codeFromPath(path)}
          prefs={prefs}
          initialCode={refusedCode}
          onStart={(code) => {
            lastStart.current = code;
          }}
        />
      )}
      <Toast notice={state.notice} />
    </>
  );
}
