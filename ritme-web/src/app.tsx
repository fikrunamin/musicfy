import { useEffect, useState } from 'preact/hooks';
import { type KeyValueStore, NAME_KEY, readResume } from './net/storage';
import type { SessionApi } from './session/session';
import { codeFromPath, normalizeName } from './ui/format';
import { HomeScreen } from './ui/home';
import { useSessionState } from './ui/hooks';
import { RoomScreen } from './ui/room';
import { Toast } from './ui/toast';

interface Props {
  session: SessionApi;
  /** localStorage: name, mode. */
  prefs: KeyValueStore;
  /** sessionStorage: resume token. */
  tabStore: KeyValueStore;
}

export function App({ session, prefs, tabStore }: Props) {
  const state = useSessionState(session);
  const [path, setPath] = useState(location.pathname);
  const navigate = (to: string) => {
    if (location.pathname === to) return;
    history.replaceState(null, '', to);
    setPath(to);
  };

  // The address bar follows the room, so a reload (or a shared tab) lands back in it.
  useEffect(() => {
    if (state.phase === 'in-room' && state.room) navigate(`/r/${state.room.code}`);
    if (state.phase === 'ended' && state.ended && state.ended.kind !== 'unreachable') navigate('/');
  }, [state.phase, state.room?.code]);

  // Reloaded inside a room: resume with the token from this tab.
  useEffect(() => {
    const code = codeFromPath(location.pathname);
    const name = normalizeName(prefs.get(NAME_KEY) ?? '');
    if (code && name && readResume(tabStore)?.code === code) session.join(code, name);
  }, []);

  return (
    <>
      {state.phase === 'in-room' && state.room ? (
        <RoomScreen session={session} state={{ ...state, room: state.room }} prefs={prefs} />
      ) : (
        <HomeScreen session={session} state={state} routeCode={codeFromPath(path)} prefs={prefs} />
      )}
      <Toast notice={state.notice} />
    </>
  );
}
