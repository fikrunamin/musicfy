import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { KeyValueStore } from '../net/storage';
import { MODE_KEY } from '../net/storage';
import { type RoomState, currentEntry } from '../protocol';
import { canAdd } from '../session/permissions';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { Backdrop } from './backdrop';
import { isVideoEntry, pageIsHttps } from './badges';
import { AvatarStack, IconButton, SyncDot } from './components';
import { Icon } from './icon';
import { type Mode, ModeSwitch } from './listen';
import { MembersPanel } from './members';
import { NowPlaying } from './now-playing';
import { QueueList } from './queue';
import { SearchPanel } from './search';
import { Sheet } from './sheet';
import { t } from './strings';

interface Props {
  session: SessionApi;
  state: SessionState & { room: RoomState };
  prefs: KeyValueStore;
}

export async function share(code: string, session: SessionApi): Promise<void> {
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

const showQueue = () => document.getElementById('queue-title')?.scrollIntoView({ block: 'start' });

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
  const entry = currentEntry(room);
  const mayAdd = canAdd(room, memberId);
  const [adding, setAdding] = useState(false);
  const main = useRef<HTMLElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  /**
   * Where focus goes when the sheet closes and the "Tambah lagu" that opened it is gone: the one
   * shown now (the empty state's and the queue header's are different buttons), else the title.
   */
  const addReturn = useMemo(
    () => ({
      get current(): HTMLElement | null {
        return main.current?.querySelector<HTMLElement>('.queue-add') ?? title.current;
      },
    }),
    [],
  );
  // The host locking adding closes the sheet and hides "Tambah lagu"; unlocking later must not
  // reopen the sheet by itself. The sheet gave focus back to its opener before that was removed.
  useEffect(() => {
    if (!adding || mayAdd) return;
    setAdding(false);
    if (!document.activeElement || document.activeElement === document.body) title.current?.focus();
  }, [adding, mayAdd]);

  return (
    <>
      <Backdrop url={entry?.thumbnailUrl ?? null} video={entry !== null && isVideoEntry(entry)} />
      <main ref={main} class="room">
        {!online && (
          <p class="reconnect-banner" role="status">
            <Icon name="sync" size={18} />
            {t.reconnecting}
          </p>
        )}
        <header class="room-header">
          <div class="room-heading">
            <h1 ref={title} tabIndex={-1}>
              {t.roomTitle(room.code)}
            </h1>
            <p class="room-status">
              <SyncDot state={online ? 'online' : 'reconnecting'} />
              {online ? t.synced : t.reconnecting}
            </p>
          </div>
          <span class="room-members" title={t.members(room.members.length)}>
            <AvatarStack names={room.members.map((m) => m.name)} />
            <span class="visually-hidden">{t.members(room.members.length)}</span>
          </span>
          <IconButton icon="ios_share" label={t.share} variant="filled" onClick={() => void share(room.code, session)} />
        </header>

        <ModeSwitch mode={mode} onModeChange={changeMode} />
        <NowPlaying {...shared} mode={mode} onShowQueue={showQueue} />
        {/* "Tambah lagu" is hidden, not disabled, while the host keeps guests from adding (spec 8). */}
        <QueueList {...shared} onAdd={mayAdd ? () => setAdding(true) : undefined} />
        <MembersPanel session={session} room={room} memberId={memberId} online={online} />

        {/* Ending the room lives in the members panel's host section, right above. */}
        <footer class="room-footer">
          <button type="button" class="btn" onClick={() => session.leave()}>
            {t.leave}
          </button>
        </footer>
      </main>
      {adding && mayAdd && (
        <Sheet labelledBy="add-title" class="add-sheet" onClose={() => setAdding(false)} returnFocus={addReturn}>
          <SearchPanel session={session} online={online} pageIsHttps={https} onClose={() => setAdding(false)} />
        </Sheet>
      )}
    </>
  );
}
