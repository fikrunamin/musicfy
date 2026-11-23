import { useState } from 'preact/hooks';
import type { KeyValueStore } from '../net/storage';
import { MODE_KEY } from '../net/storage';
import type { RoomState } from '../protocol';
import { canAdd } from '../session/permissions';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { pageIsHttps } from './badges';
import { type Mode, ListenPanel } from './listen';
import { MembersPanel } from './members';
import { NowPlaying } from './now-playing';
import { QueueList } from './queue';
import { SearchPanel } from './search';
import { t } from './strings';

interface Props {
  session: SessionApi;
  state: SessionState & { room: RoomState };
  prefs: KeyValueStore;
}

async function share(code: string, session: SessionApi): Promise<void> {
  const url = `${location.origin}/r/${code}`;
  if (navigator.share) {
    try {
      await navigator.share({ title: t.appName, text: t.shareText, url });
      return;
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
    }
  }
  try {
    await navigator.clipboard.writeText(url);
    session.notify('linkCopied');
  } catch {
    window.prompt(t.share, url);
  }
}

export function RoomScreen({ session, state, prefs }: Props) {
  const { room, memberId, unplayable } = state;
  const online = state.connection === 'online';
  const https = pageIsHttps();
  const [mode, setMode] = useState<Mode>(prefs.get(MODE_KEY) === 'remote' ? 'remote' : 'listen');
  const changeMode = (m: Mode) => {
    prefs.set(MODE_KEY, m);
    setMode(m);
  };
  const shared = { session, room, memberId, unplayable, online, pageIsHttps: https };

  return (
    <main class="screen room">
      <header class="room-header">
        <div>
          <h1>{t.roomTitle(room.code)}</h1>
          <p class="muted small">{t.members(room.members.length)}</p>
        </div>
        <button type="button" class="btn" onClick={() => void share(room.code, session)}>
          {t.share}
        </button>
      </header>

      {!online && (
        <p class="banner" role="status">
          {t.reconnecting}
        </p>
      )}

      <ListenPanel session={session} room={room} unplayable={unplayable} mode={mode} onModeChange={changeMode} pageIsHttps={https} />
      <NowPlaying {...shared} />
      <QueueList {...shared} />
      {/* Hidden, not disabled, while the host keeps guests from adding (spec 8). */}
      {canAdd(room, memberId) && <SearchPanel session={session} online={online} pageIsHttps={https} />}
      <MembersPanel session={session} room={room} memberId={memberId} online={online} />

      {/* Ending the room lives in the members panel's host section, right above. */}
      <footer class="room-footer">
        <button type="button" class="btn" onClick={() => session.leave()}>
          {t.leave}
        </button>
      </footer>
    </main>
  );
}
