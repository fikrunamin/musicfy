import { useRef, useState } from 'preact/hooks';
import type { probeMedia } from '../net/media-probe';
import { type SearchResult, searchSongs, toNewEntry, validThumbnail } from '../net/search';
import type { SessionApi } from '../session/session';
import { AddVideoPanel } from './add-video';
import { Button, IconButton, TextField } from './components';
import { formatMs } from './format';
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
export function SearchPanel({ session, online, pageIsHttps = true, onClose, fetchImpl, probe }: Props) {
  const [tab, setTab] = useState<Tab>('songs');
  const tabs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({});

  const onKeyDown = (e: KeyboardEvent) => {
    const focused = TABS.findIndex((x) => tabs.current[x.id] === e.target);
    const next = tabForKey(e.key, focused >= 0 ? focused : TABS.findIndex((x) => x.id === tab));
    if (next === null) return;
    e.preventDefault();
    setTab(next);
    tabs.current[next]?.focus();
  };

  return (
    <div class="add-panel">
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
              <SongSearch session={session} online={online} fetchImpl={fetchImpl} />
            ) : (
              <AddVideoPanel session={session} online={online} pageIsHttps={pageIsHttps} fetchImpl={fetchImpl} probe={probe} />
            ))}
        </div>
      ))}
    </div>
  );
}

/** Search submits on Enter/button only (the server allows 30 searches per minute per IP). */
function SongSearch({ session, online, fetchImpl }: { session: SessionApi; online: boolean; fetchImpl?: typeof fetch }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[] | null>(null);
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
                    <Button class="add-one" disabled={!online} aria-label={`${t.addToQueue}: ${r.title}`} onClick={() => add(r, false)}>
                      {t.addToQueue}
                    </Button>
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
