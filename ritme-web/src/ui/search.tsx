import { useRef, useState } from 'preact/hooks';
import { type SearchResult, searchSongs, toNewEntry, validThumbnail } from '../net/search';
import type { SessionApi } from '../session/session';
import { formatMs } from './format';
import { Thumb } from './queue';
import { searchFailureText, t } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** Injected in tests. */
  fetchImpl?: typeof fetch;
}

/** Search submits on Enter/button only (the server allows 30 searches per minute per IP). */
export function SearchPanel({ session, online, fetchImpl }: Props) {
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
    <section class="card" aria-labelledby="search-title">
      <h2 id="search-title">{t.addSong}</h2>
      <form class="search-form" role="search" onSubmit={onSubmit}>
        <label class="visually-hidden" for="search-input">
          {t.searchLabel}
        </label>
        <input
          id="search-input"
          type="search"
          value={query}
          maxLength={200}
          placeholder={t.searchPlaceholder}
          onInput={(e) => setQuery(e.currentTarget.value)}
        />
        <button type="submit" class="btn" disabled={busy}>
          {busy ? t.searching : t.search}
        </button>
      </form>
      {error && (
        <p class="error" role="alert">
          {error}
        </p>
      )}
      {results && results.length === 0 && <p class="muted">{t.noResults}</p>}
      {results && results.length > 0 && (
        <ul class="queue">
          {results.map((r) => (
            <li key={r.videoId} class="queue-row">
              <Thumb url={validThumbnail(r.thumbnailUrl)} />
              <div class="queue-text">
                <span class="title">{r.title}</span>
                <span class="muted small">
                  {r.artist} · {formatMs(r.durationMs)}
                </span>
              </div>
              <div class="queue-actions">
                <button type="button" class="btn btn-small" disabled={!online} aria-label={`${t.playNext}: ${r.title}`} onClick={() => add(r, true)}>
                  {t.playNext}
                </button>
                <button
                  type="button"
                  class="btn btn-small btn-primary"
                  disabled={!online}
                  aria-label={`${t.addToQueue}: ${r.title}`}
                  onClick={() => add(r, false)}
                >
                  {t.addToQueue}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
