import { useEffect, useRef, useState } from 'preact/hooks';
import { displayDomain } from '../net/idn';
import { type ProbeOutcome, probeMedia } from '../net/media-probe';
import { MAX_MEDIA_URL_LENGTH } from '../net/media-url-rules';
import { MAX_TEXT_LENGTH } from '../net/search';
import { type VideoInfo, fetchVideoInfo, toUrlEntry, toVideoEntry, urlEntryTitle } from '../net/video';
import { type DirectVideoLink, classifyVideoLink } from '../net/video-link';
import type { NewEntry } from '../protocol';
import type { SessionApi } from '../session/session';
import { formatMs } from './format';
import { Thumb } from './media';
import { t, videoInfoFailureText, videoLinkProblemText } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** http:// media cannot play on an https page (mixed content), so such links are refused. */
  pageIsHttps: boolean;
  /** Injected in tests. */
  fetchImpl?: typeof fetch;
  probe?: typeof probeMedia;
}

type Preview =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'youtube'; info: VideoInfo; entry: NewEntry }
  /** probe is null while the hidden <video> is still loading the metadata. */
  | { kind: 'direct'; link: DirectVideoLink; probe: ProbeOutcome | null };

function directDuration(probe: ProbeOutcome | null): string {
  if (probe === null) return t.loadingPreview;
  if (probe.kind !== 'ready') return t.unknownDuration;
  if (probe.live) return t.live;
  return probe.durationMs > 0 ? formatMs(probe.durationMs) : t.unknownDuration;
}

function canReadClipboard(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.clipboard?.readText === 'function';
}

/** A clipboard outline in the text colour (the handoff's paste button is an icon only). */
function PasteIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <rect x="8" y="2" width="8" height="4" rx="1" />
      <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
    </svg>
  );
}

interface PreviewRowProps {
  thumbnailUrl: string | null;
  /** 16:9 for videos; a song (music.youtube.com) keeps the square thumbnail it gets in the queue. */
  wide: boolean;
  title: string;
  meta: string[];
  enabled: boolean;
  onAdd: (playNext: boolean) => void;
}

/** One preview, laid out exactly like a search result row, with the thumbnail its queue row will have. */
function PreviewRow({ thumbnailUrl, wide, title, meta, enabled, onAdd }: PreviewRowProps) {
  return (
    <ul class="queue">
      <li class="queue-row">
        <Thumb url={thumbnailUrl} wide={wide} />
        <div class="queue-text">
          <span class="title">{title}</span>
          <span class="muted small">{meta.filter(Boolean).join(' · ')}</span>
        </div>
        <div class="queue-actions">
          <button type="button" class="btn btn-small" disabled={!enabled} aria-label={`${t.playNext}: ${title}`} onClick={() => onAdd(true)}>
            {t.playNext}
          </button>
          <button
            type="button"
            class="btn btn-small btn-primary"
            disabled={!enabled}
            aria-label={`${t.addToQueue}: ${title}`}
            onClick={() => onAdd(false)}
          >
            {t.addToQueue}
          </button>
        </div>
      </li>
    </ul>
  );
}

/** What the youtube preview row shows under its title. */
function youtubeMeta(preview: { info: VideoInfo; entry: NewEntry }): string[] {
  return [preview.entry.artist, preview.info.live ? t.live : formatMs(preview.entry.durationMs)];
}

/** What the direct preview row shows under its title. */
function directMeta(link: DirectVideoLink, probe: ProbeOutcome | null): string[] {
  return [t.fromDomain(displayDomain(link.domain)), directDuration(probe)];
}

/**
 * The text of the one polite live region: "loading", then the preview (and, for a link this
 * browser cannot play, that warning). Errors are announced by their own role="alert". The typed
 * title is left out so editing it does not announce on every keystroke.
 */
function announcement(preview: Preview): string {
  switch (preview.kind) {
    case 'idle':
    case 'error':
      return '';
    case 'loading':
      return t.loadingPreview;
    case 'youtube':
      return [preview.entry.title, ...youtubeMeta(preview)].filter(Boolean).join(' · ');
    case 'direct': {
      if (preview.probe === null) return t.loadingPreview;
      const title = urlEntryTitle(preview.link, '') ?? preview.link.fileName;
      const row = [title, ...directMeta(preview.link, preview.probe)].filter(Boolean).join(' · ');
      return preview.probe.kind === 'unplayable' ? `${row}. ${t.videoUnplayableWeb}` : row;
    }
  }
}

/**
 * The "Video" tab of the add card (spec 6.1, brief §12 L and §13): paste a link, preview it, add
 * it. Nothing is added automatically, and the preview starts only on "Pratinjau"/Enter (the
 * server allows 30 lookups per minute per IP). YouTube links need /api/video to answer (no
 * made-up titles); direct links are probed by a hidden <video> and may be added even when this
 * browser cannot play them, because an Android member might. What is sent is exactly what
 * toVideoEntry/toUrlEntry return; only the shown domain is IDN-decoded.
 */
export function AddVideoPanel({ session, online, pageIsHttps, fetchImpl, probe }: Props) {
  const [text, setText] = useState('');
  const [title, setTitle] = useState('');
  const [preview, setPreview] = useState<Preview>({ kind: 'idle' });
  const inflight = useRef<AbortController | null>(null);
  const input = useRef<HTMLInputElement>(null);
  useEffect(
    () => () => {
      inflight.current?.abort();
      inflight.current = null;
    },
    [],
  );

  /** False once a newer submit, an edit of the link or leaving the tab cancelled this lookup: its outcome is dropped. */
  const current = (controller: AbortController) => inflight.current === controller && !controller.signal.aborted;

  /**
   * A typed or pasted link replaces the old one: the lookup in flight is cancelled and the preview
   * cleared, so "Tambah" can never add a link other than the one in the field.
   */
  const changeLink = (value: string) => {
    setText(value);
    inflight.current?.abort();
    inflight.current = null;
    setTitle('');
    setPreview({ kind: 'idle' });
  };

  const onSubmit = async (e: Event) => {
    e.preventDefault();
    inflight.current?.abort();
    const controller = new AbortController();
    inflight.current = controller;
    const link = classifyVideoLink(text, pageIsHttps);
    if (link.kind === 'rejected') {
      setPreview({ kind: 'error', message: videoLinkProblemText[link.reason] });
      return;
    }
    if (link.kind === 'youtube') {
      setPreview({ kind: 'loading' });
      const outcome = await fetchVideoInfo(link.videoId, fetchImpl ?? fetch, controller.signal);
      if (!current(controller)) return;
      if (!outcome.ok) {
        setPreview({ kind: 'error', message: videoInfoFailureText[outcome.reason] });
        return;
      }
      const entry = toVideoEntry(outcome.info, link.music);
      setPreview(entry ? { kind: 'youtube', info: outcome.info, entry } : { kind: 'error', message: videoInfoFailureText.failed });
      return;
    }
    setTitle('');
    setPreview({ kind: 'direct', link, probe: null });
    const outcome = await (probe ?? probeMedia)(link.url, { hls: link.hls, signal: controller.signal });
    if (!current(controller)) return;
    setPreview({ kind: 'direct', link, probe: outcome });
  };

  const paste = async () => {
    try {
      const pasted = (await navigator.clipboard.readText()).trim();
      if (pasted !== '') changeLink(pasted.slice(0, MAX_MEDIA_URL_LENGTH));
    } catch {
      // permission refused or nothing readable: the field stays as it was
    }
    input.current?.focus();
  };

  const directEntry = (p: Preview): NewEntry | null =>
    p.kind === 'direct' && p.probe ? toUrlEntry(p.link, title, p.probe.kind === 'ready' ? p.probe.durationMs : 0) : null;

  const add = (playNext: boolean) => {
    const entry = preview.kind === 'youtube' ? preview.entry : directEntry(preview);
    if (!entry) return;
    session.add(entry, playNext);
    setText('');
    setTitle('');
    setPreview({ kind: 'idle' });
  };

  const busy = preview.kind === 'loading';
  return (
    <>
      <form class="search-form" onSubmit={onSubmit}>
        <label class="visually-hidden" for="video-link-input">
          {t.videoLinkLabel}
        </label>
        <input
          ref={input}
          id="video-link-input"
          type="text"
          inputMode="url"
          autoComplete="off"
          spellcheck={false}
          value={text}
          maxLength={MAX_MEDIA_URL_LENGTH}
          placeholder={t.videoLinkPlaceholder}
          onInput={(e) => changeLink(e.currentTarget.value)}
        />
        {canReadClipboard() && (
          <button type="button" class="icon-btn" aria-label={t.pasteLink} title={t.pasteLink} onClick={() => void paste()}>
            <PasteIcon />
          </button>
        )}
        <button type="submit" class="btn" disabled={busy}>
          {busy ? t.loadingPreview : t.preview}
        </button>
      </form>
      {preview.kind === 'error' && (
        <p class="error" role="alert">
          {preview.message}
        </p>
      )}
      {preview.kind === 'youtube' && (
        <PreviewRow
          thumbnailUrl={preview.entry.thumbnailUrl}
          wide={preview.entry.kind !== 'YOUTUBE_MUSIC'}
          title={preview.entry.title}
          meta={youtubeMeta(preview)}
          enabled={online}
          onAdd={add}
        />
      )}
      {preview.kind === 'direct' && (
        <div class="stack">
          <label class="field">
            {t.titleOptional}
            <input
              type="text"
              value={title}
              maxLength={MAX_TEXT_LENGTH}
              placeholder={preview.link.fileName}
              onInput={(e) => setTitle(e.currentTarget.value)}
            />
          </label>
          <PreviewRow
            thumbnailUrl={null}
            wide
            title={urlEntryTitle(preview.link, title) ?? preview.link.fileName}
            meta={directMeta(preview.link, preview.probe)}
            enabled={online && directEntry(preview) !== null}
            onAdd={add}
          />
          {preview.probe?.kind === 'unplayable' && <p class="muted small">{t.videoUnplayableWeb}</p>}
        </div>
      )}
      <p class="visually-hidden" role="status" aria-live="polite">
        {announcement(preview)}
      </p>
      <p class="muted small">{t.videoTip}</p>
    </>
  );
}
