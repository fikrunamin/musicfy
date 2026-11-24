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
import { DESKTOP_QUERY, type Draft, useMediaQuery } from './hooks';
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
 *
 * Theater (desktop with a video entry, handoff K/O): still that tree, laid out by video.css as a
 * wide 16:9 stage under a one-row header, and a 340 px right column (full queue, compact members)
 * that the header toggle folds away. Adding and the full members list open as dialogs there.
 *
 * The add flow survives the theater coming and going at a track boundary: what was typed lives in
 * `draft`, the inline panel's work moves into the dialog, and an open dialog stays until closed.
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
  const theater = desktop && entry !== null && isVideoEntry(entry);
  /** Phone, or the theater: add and members open as sheets (dialogs on desktop). */
  const sheets = !desktop || theater;
  const [sideOpen, setSideOpen] = useState(true);
  const folded = theater && !sideOpen;
  const main = useRef<HTMLElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const crossed = useRef(false);
  const draft = useRef<Draft>({});
  /** Opening "Tambah lagu" or closing the dialog starts the next add flow afresh. */
  const openAdd = (open: boolean) => {
    draft.current = {};
    setAdding(open);
  };
  // The theater turning on takes the inline add panel away. Work in progress there (focus in it, a
  // link not yet added, a search not yet run) goes on in the dialog; a finished search left in the
  // field does not open it. This runs while the panel is still in the page, before it is gone.
  const wasTheater = useRef(theater);
  const carryFocus = useRef<string | null>(null);
  if (wasTheater.current !== theater) {
    wasTheater.current = theater;
    const panel = theater && main.current?.querySelector('.add-panel-inline');
    const focused = document.activeElement;
    const d = draft.current;
    if (panel && (panel.contains(focused) || d.text || (d.query && !d.results))) {
      carryFocus.current = panel.contains(focused) ? focused!.id : null;
      setAdding(true);
    }
  }
  useEffect(() => {
    if (carryFocus.current) document.getElementById(carryFocus.current)?.focus();
    carryFocus.current = null;
  }, [adding]);
  // Crossing the breakpoint closes any sheet: on desktop its content is inline, and a phone that
  // was a desktop a moment ago should not find a sheet it never opened.
  useEffect(() => setAdding(false), [desktop]);
  // That, or the theater coming or going, closes the members sheet. Focus that was in a part that
  // is gone now (the right column on the way to a phone, the add panel on the way into the theater)
  // goes to the title, not to <body>.
  useEffect(() => {
    setShowingMembers(false);
    if (!crossed.current) {
      crossed.current = true;
      return;
    }
    const focused = document.activeElement;
    if (!focused || focused === document.body || !focused.isConnected) title.current?.focus();
  }, [desktop, theater]);
  /**
   * Where focus goes when the sheet closes and the "Tambah lagu" that opened it is gone: the one
   * shown now (the empty state's and the queue header's are different buttons), else the title.
   * One in the folded theater column is hidden, and focus would fall to <body>: the title then.
   */
  const addReturn = useMemo(
    () => ({
      get current(): HTMLElement | null {
        const add = main.current?.querySelector<HTMLElement>('.queue-add');
        return add && !add.closest('[hidden]') ? add : title.current;
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

  /** Desktop "Tambah lagu": the add panel is already there; it goes to its Lagu tab and song search. */
  const [addFocus, setAddFocus] = useState(0);
  const focusAdd = () => setAddFocus((n) => n + 1);
  const onShare = () => void share(room.code, session);

  return (
    <>
      <Backdrop url={entry?.thumbnailUrl ?? null} video={entry !== null && isVideoEntry(entry)} />
      {/* Above the room, not in it: a sheet makes <main> inert and aria-hidden, and a live region
          outside it is left alone, so "Menyambung ulang…" is still heard. Sticky only in remote
          mode, where there is no player for it to cover. */}
      {!online && (
        <p class={cx('reconnect-banner', mode === 'remote' && 'reconnect-sticky')} role="status">
          <Icon name="sync" size={18} />
          {t.reconnecting}
        </p>
      )}
      <main ref={main} class={cx('room', desktop && 'room-desktop', theater && 'room-theater', folded && 'room-collapsed')}>
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
                <span>{desktop ? t.statusWithMembers(online ? t.synced : t.reconnecting, room.members.length) : online ? t.synced : t.reconnecting}</span>
              </p>
            </div>
            {sheets && (
              <button type="button" class="room-members" onClick={() => setShowingMembers(true)}>
                <AvatarStack names={room.members.map((m) => m.name)} />
                <span class="visually-hidden">{t.members(room.members.length)}</span>
              </button>
            )}
            {desktop ? (
              <Button icon="ios_share" class="room-share" onClick={onShare}>
                {t.share}
              </Button>
            ) : (
              <IconButton icon="ios_share" label={t.share} variant="filled" onClick={onShare} />
            )}
            {theater && (
              <IconButton
                icon="dock_to_left"
                label={t.sidePanel}
                variant="filled"
                class="room-side-toggle"
                aria-expanded={sideOpen}
                aria-controls="room-queue room-side"
                onClick={() => setSideOpen(!sideOpen)}
              />
            )}
          </header>

          <ModeSwitch mode={mode} onModeChange={changeMode} />
          <NowPlaying {...shared} mode={mode} onShowQueue={desktop ? undefined : showQueue} notice={state.notice} />
        </div>
        <div id="room-queue" class="room-col room-queue" hidden={folded}>
          {/* "Tambah lagu" is hidden, not disabled, while the host keeps guests from adding (spec 8). */}
          <QueueList {...shared} variant={desktop ? 'full' : 'upcoming'} onAdd={mayAdd ? (sheets ? () => openAdd(true) : focusAdd) : undefined} />
        </div>
        {desktop && (
          <div id="room-side" class="room-col room-side" hidden={folded}>
            {mayAdd && !theater && !adding && <SearchPanel session={session} online={online} pageIsHttps={https} variant="panel" focusRequest={addFocus} draft={draft.current} />}
            <MembersPanel session={session} room={room} memberId={memberId} online={online} variant={theater ? 'compact' : 'panel'} />
          </div>
        )}
      </main>
      {/* Not only with `sheets`: a dialog open when the theater gives way to a song stays until closed. */}
      {adding && mayAdd && (
        <Sheet labelledBy="add-title" class="add-sheet" onClose={() => openAdd(false)} returnFocus={addReturn}>
          <SearchPanel session={session} online={online} pageIsHttps={https} onClose={() => openAdd(false)} draft={draft.current} />
        </Sheet>
      )}
      {/* Leaving and ending the room live in the members sheet, under the share card (handoff G). */}
      {sheets && showingMembers && (
        <Sheet labelledBy="members-title" class="members-sheet" onClose={() => setShowingMembers(false)} returnFocus={title}>
          <MembersPanel session={session} room={room} memberId={memberId} online={online} variant="sheet" onClose={() => setShowingMembers(false)} />
        </Sheet>
      )}
    </>
  );
}
