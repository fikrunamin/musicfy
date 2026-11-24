import type { RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { probeMedia } from '../net/media-probe';
import { type SearchResult, searchSongs, toNewEntry, validThumbnail } from '../net/search';
import type { SessionApi } from '../session/session';
import { AddVideoPanel } from './add-video';
import { Button, IconButton, TextField } from './components';
import { formatMs } from './format';
import { type Draft, useDraft } from './hooks';
import { Icon, type IconName } from './icon';
import { MediaRow, Thumb } from './media';
import { searchFailureText, t } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** Links to http:// media are refused on an https page (mixed content). */
  pageIsHttps?: boolean;
  /** Shows the close button (the panel is in a sheet). */
  onClose?: () => void;
  /** sheet: inside the phone's "Tambah lagu" sheet. panel: inline in the desktop room's right column. */
  variant?: 'sheet' | 'panel';
  /** Each new value selects the Lagu tab and focuses the song search (desktop "Tambah lagu"). */
  focusRequest?: number;
  /** Where the typed state is kept, so it survives the move between the inline panel and the dialog. */
  draft?: Draft;
  /** Injected in tests. */
  fetchImpl?: typeof fetch;
  probe?: typeof probeMedia;
}

type Tab = 'songs' | 'video';

const TABS: readonly { id: Tab; label: string; icon: IconName }[] = [
  { id: 'songs', label: t.tabSongs, icon: 'music_note' },
  { id: 'video', label: t.tabVideo, icon: 'smart_display' },
];

const tabId = (tab: Tab) => `add-tab-${tab}`;
const panelId = (tab: Tab) => `add-panel-${tab}`;

/** The tab a key moves to from index `i` (horizontal tablist, wrapping), or null for other keys. */
function tabForKey(key: string, i: number): Tab | null {
  const n = TABS.length;
  switch (key) {
    case 'ArrowRight':
      return TABS[(i + 1) % n].id;
    case 'ArrowLeft':
      return TABS[(i - 1 + n) % n].id;
    case 'Home':
      return TABS[0].id;
    case 'End':
      return TABS[n - 1].id;
    default:
      return null;
  }
}

/**
 * "Tambah ke antrean" (handoff F and L): a header with the close button, then WAI-ARIA tabs Lagu
 * (song search) and Video (pasting a link). Tabs follow the APG pattern with automatic activation:
 * one tab stop (roving tabindex), arrows wrap, Home/End jump. Only the selected panel's content
 * is mounted, so leaving a tab cancels its lookups as before.
 */
export function SearchPanel({ session, online, pageIsHttps = true, onClose, variant = 'sheet', focusRequest = 0, draft, fetchImpl, probe }: Props) {
  const inline = variant === 'panel';
  const [tab, setTab] = useDraft<Tab>(draft, 'tab', 'songs');
  const tabs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({});
  const search = useRef<HTMLInputElement>(null);

  // A focus request selects Lagu first: the song search is mounted only while its tab is selected,
  // so the focus waits for the render that mounts it. Only a new request: a panel mounted again (the
  // theater gave way to a song) does not take focus.
  const focusWanted = useRef(false);
  const handled = useRef(focusRequest);
  useEffect(() => {
    if (focusRequest === handled.current) return;
    handled.current = focusRequest;
    focusWanted.current = true;
    setTab('songs');
  }, [focusRequest]);
  useEffect(() => {
    if (!focusWanted.current || tab !== 'songs') return;
    focusWanted.current = false;
    search.current?.focus();
  });

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.altKey || e.ctrlKey || e.metaKey) return; // browser and screen-reader shortcuts
    const focused = TABS.findIndex((x) => tabs.current[x.id] === e.target);
    const next = tabForKey(e.key, focused >= 0 ? focused : TABS.findIndex((x) => x.id === tab));
    if (next === null) return;
    e.preventDefault();
    setTab(next);
    tabs.current[next]?.focus();
  };

  const content = (
    <>
      <div class="sheet-header">
        <h2 id="add-title">{t.addTitle}</h2>
        {onClose && <IconButton icon="close" label={t.close} variant="filled" onClick={onClose} />}
      </div>
      <div class="segmented add-tabs" role="tablist" aria-label={t.addKindGroup} onKeyDown={onKeyDown}>
        {TABS.map((x) => (
          <button
            key={x.id}
            ref={(el) => {
              tabs.current[x.id] = el;
            }}
            type="button"
            role="tab"
            id={tabId(x.id)}
            aria-selected={tab === x.id}
            aria-controls={panelId(x.id)}
            tabIndex={tab === x.id ? 0 : -1}
            onClick={() => setTab(x.id)}
          >
            <Icon name={x.icon} size={20} />
            {x.label}
          </button>
        ))}
      </div>
      {TABS.map((x) => (
        <div key={x.id} id={panelId(x.id)} class="add-tabpanel" role="tabpanel" aria-labelledby={tabId(x.id)} hidden={tab !== x.id}>
          {tab === x.id &&
            (x.id === 'songs' ? (
              <SongSearch session={session} online={online} inline={inline} inputRef={search} draft={draft} fetchImpl={fetchImpl} />
            ) : (
              <AddVideoPanel session={session} online={online} pageIsHttps={pageIsHttps} draft={draft} fetchImpl={fetchImpl} probe={probe} />
            ))}
        </div>
      ))}
    </>
  );
  // In a sheet the dialog is named after the heading; inline, the section is.
  return inline ? (
    <section class="add-panel add-panel-inline" aria-labelledby="add-title">
      {content}
    </section>
  ) : (
    <div class="add-panel">{content}</div>
  );
}

/** Search submits on Enter/button only (the server allows 30 searches per minute per IP). */
/** inline (desktop panel): the field shows only its search icon, and each result adds with a round "+". */
function SongSearch({
  session,
  online,
  inline,
  inputRef,
  draft,
  fetchImpl,
}: {
  session: SessionApi;
  online: boolean;
  inline: boolean;
  inputRef?: RefObject<HTMLInputElement>;
  draft?: Draft;
  fetchImpl?: typeof fetch;
}) {
  const [query, setQuery] = useDraft(draft, 'query', '');
  const [results, setResults] = useDraft<SearchResult[] | null>(draft, 'results', null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inflight = useRef<AbortController | null>(null);

  const onSubmit = async (e: Event) => {
    e.preventDefault();
    inflight.current?.abort();
    const controller = new AbortController();
    inflight.current = controller;
    setBusy(true);
    setError(null);
    const outcome = await searchSongs(query, fetchImpl ?? fetch, controller.signal);
    if (inflight.current !== controller) return;
    setBusy(false);
    if (outcome.ok) setResults(outcome.results);
    else setError(searchFailureText[outcome.reason]);
  };

  const add = (result: SearchResult, playNext: boolean) => {
    const entry = toNewEntry(result);
    if (entry) session.add(entry, playNext);
  };

  return (
    <>
      <form class="add-search" role="search" onSubmit={onSubmit}>
        <TextField
          id="search-input"
          label={t.searchLabel}
          hideLabel={inline}
          inputRef={inputRef}
          leadingIcon="search"
          type="search"
          value={query}
          maxLength={200}
          placeholder={t.searchPlaceholder}
          onInput={(e) => setQuery(e.currentTarget.value)}
        />
        <Button type="submit" variant="primary" disabled={busy}>
          {t.search}
        </Button>
      </form>
      {/* Always present, so "Mencari…" and "Tidak ada hasil." are announced when they appear. */}
      <div class="add-status" role="status">
        {busy && <p class="add-chip">{t.searching}</p>}
        {!busy && !error && results?.length === 0 && <p class="add-chip">{t.noResults}</p>}
      </div>
      {error && (
        <p class="add-chip" role="alert">
          {error}
        </p>
      )}
      {results && results.length > 0 && (
        <ul class="rows add-results">
          {results.map((r) => {
            const live = !(r.durationMs > 0);
            const meta = `${r.artist} · ${live ? t.live : formatMs(r.durationMs)}`;
            return (
              <MediaRow
                key={r.videoId}
                thumb={<Thumb url={validThumbnail(r.thumbnailUrl)} />}
                title={r.title}
                meta={live ? <span class="meta-live">{meta}</span> : meta}
                trailing={
                  <>
                    <IconButton icon="playlist_play" class="add-next" label={`${t.playNext}: ${r.title}`} disabled={!online} onClick={() => add(r, true)} />
                    {inline ? (
                      <IconButton icon="add" variant="accent" label={`${t.addToQueue}: ${r.title}`} disabled={!online} onClick={() => add(r, false)} />
                    ) : (
                      <Button class="add-one" disabled={!online} aria-label={`${t.addToQueue}: ${r.title}`} onClick={() => add(r, false)}>
                        {t.addToQueue}
                      </Button>
                    )}
                  </>
                }
              />
            );
          })}
        </ul>
      )}
    </>
  );
}
