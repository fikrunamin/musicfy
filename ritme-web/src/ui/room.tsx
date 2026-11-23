import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { KeyValueStore } from '../net/storage';
import { MODE_KEY } from '../net/storage';
import { type RoomState, currentEntry } from '../protocol';
import { canAdd } from '../session/permissions';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { Backdrop } from './backdrop';
import { isVideoEntry, pageIsHttps } from './badges';
import { AvatarStack, Button, IconButton, SyncDot, cx } from './components';
import { DESKTOP_QUERY, useMediaQuery } from './hooks';
import { Icon } from './icon';
import { type Mode, ModeSwitch } from './listen';
import { MembersPanel } from './members';
import { NowPlaying } from './now-playing';
import { QueueList } from './queue';
import { SearchPanel } from './search';
import { share } from './share';
import { Sheet } from './sheet';
import { t } from './strings';

interface Props {
  session: SessionApi;
  state: SessionState & { room: RoomState };
  prefs: KeyValueStore;
}

const showQueue = () => document.getElementById('queue-title')?.scrollIntoView({ block: 'start' });

/**
 * The room. Phone: one column, with add and members behind sheets. Desktop (1024 px and up,
 * handoff C+E): the same tree laid out by desktop.css as three columns that scroll on their own:
 * header and stage, the full queue, then add and members as inline panels.
 *
 * YouTube: the stage keeps its place in the tree across the breakpoint. Both layouts render the
 * same elements in the same order (main > .room-left > section.now-playing > .stage), and only
 * classes and the content around the stage differ, so Preact never re-creates the player's
 * container: the iframe and its controller survive a resize. The column wrappers are
 * `display: contents` on a phone, so the phone layout is the plain column it was.
 */
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
  const [showingMembers, setShowingMembers] = useState(false);
  const desktop = useMediaQuery(DESKTOP_QUERY);
  // Crossing the breakpoint closes any sheet: on desktop its content is inline, and a phone that
  // was a desktop a moment ago should not find a sheet it never opened.
  useEffect(() => {
    setAdding(false);
    setShowingMembers(false);
  }, [desktop]);
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

  /** Desktop "Tambah lagu": the add panel is already there; go to its field. */
  const focusAdd = () => main.current?.querySelector<HTMLElement>('.add-panel-inline input')?.focus();

  return (
    <>
      <Backdrop url={entry?.thumbnailUrl ?? null} video={entry !== null && isVideoEntry(entry)} />
      <main ref={main} class={cx('room', desktop && 'room-desktop')}>
        {!online && (
          <p class="reconnect-banner" role="status">
            <Icon name="sync" size={18} />
            {t.reconnecting}
          </p>
        )}
        <div class="room-col room-left">
          <header class="room-header">
            {desktop && <Icon name="radio_button_checked" size={28} class="room-logo" />}
            <div class="room-heading">
              <h1 ref={title} tabIndex={-1}>
                {t.roomTitle(room.code)}
              </h1>
              <p class="room-status">
                <SyncDot state={online ? 'online' : 'reconnecting'} />
                {/* Desktop: "Tersinkron · 3 anggota", the count standing in for the avatar stack. */}
                <span>{[online ? t.synced : t.reconnecting, desktop && t.members(room.members.length)].filter(Boolean).join(' · ')}</span>
              </p>
            </div>
            {!desktop && (
              <button type="button" class="room-members" onClick={() => setShowingMembers(true)}>
                <AvatarStack names={room.members.map((m) => m.name)} />
                <span class="visually-hidden">{t.members(room.members.length)}</span>
              </button>
            )}
            {desktop ? (
              <Button icon="ios_share" class="room-share" onClick={() => void share(room.code, session)}>
                {t.share}
              </Button>
            ) : (
              <IconButton icon="ios_share" label={t.share} variant="filled" onClick={() => void share(room.code, session)} />
            )}
          </header>

          <ModeSwitch mode={mode} onModeChange={changeMode} />
          <NowPlaying {...shared} mode={mode} onShowQueue={desktop ? undefined : showQueue} />
        </div>
        <div class="room-col room-queue">
          {/* "Tambah lagu" is hidden, not disabled, while the host keeps guests from adding (spec 8). */}
          <QueueList {...shared} variant={desktop ? 'full' : 'upcoming'} onAdd={mayAdd ? (desktop ? focusAdd : () => setAdding(true)) : undefined} />
        </div>
        {desktop && (
          <div class="room-col room-side">
            {mayAdd && <SearchPanel session={session} online={online} pageIsHttps={https} variant="panel" />}
            <MembersPanel session={session} room={room} memberId={memberId} online={online} variant="panel" />
          </div>
        )}
      </main>
      {!desktop && adding && mayAdd && (
        <Sheet labelledBy="add-title" class="add-sheet" onClose={() => setAdding(false)} returnFocus={addReturn}>
          <SearchPanel session={session} online={online} pageIsHttps={https} onClose={() => setAdding(false)} />
        </Sheet>
      )}
      {/* Leaving and ending the room live in the members sheet, under the share card (handoff G). */}
      {!desktop && showingMembers && (
        <Sheet labelledBy="members-title" class="members-sheet" onClose={() => setShowingMembers(false)} returnFocus={title}>
          <MembersPanel session={session} room={room} memberId={memberId} online={online} variant="sheet" onClose={() => setShowingMembers(false)} />
        </Sheet>
      )}
    </>
  );
}
