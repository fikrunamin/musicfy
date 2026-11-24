import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { displayDomain } from '../net/idn';
import { type ProbeOutcome, probeMedia } from '../net/media-probe';
import { MAX_MEDIA_URL_LENGTH } from '../net/media-url-rules';
import { MAX_TEXT_LENGTH } from '../net/search';
import { type VideoInfo, fetchVideoInfo, toUrlEntry, toVideoEntry, urlEntryTitle } from '../net/video';
import { type DirectVideoLink, classifyVideoLink } from '../net/video-link';
import type { NewEntry } from '../protocol';
import type { SessionApi } from '../session/session';
import { mediaFormat } from './badges';
import { Badge, Button, DurationChip, FieldError, IconButton, TextField } from './components';
import { formatMs } from './format';
import { type Draft, useDraft } from './hooks';
import { Icon } from './icon';
import { Thumb, VideoPlaceholder } from './media';
import { t, videoInfoFailureText, videoLinkProblemText } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** http:// media cannot play on an https page (mixed content), so such links are refused. */
  pageIsHttps: boolean;
  /** Where the link, title and preview are kept across the move between inline panel and dialog. */
  draft?: Draft;
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

const ERROR_ID = 'video-link-error';

function directDuration(probe: ProbeOutcome | null): string {
  if (probe === null) return t.loadingPreview;
  if (probe.kind !== 'ready') return t.unknownDuration;
  if (probe.live) return t.live;
  return probe.durationMs > 0 ? formatMs(probe.durationMs) : t.unknownDuration;
}

function canReadClipboard(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.clipboard?.readText === 'function';
}

/** What the youtube preview shows after "YouTube ·" (and announces after the title). */
function youtubeMeta(preview: { info: VideoInfo; entry: NewEntry }): string[] {
  return [preview.entry.artist, preview.info.live ? t.live : formatMs(preview.entry.durationMs)].filter(Boolean);
}

/** What the direct preview announces after its title: the source, then the length. */
function directMeta(link: DirectVideoLink, probe: ProbeOutcome | null): string[] {
  return [t.fromDomain(displayDomain(link.domain)), directDuration(probe)];
}

/** The name a direct link is shown and added under: the typed title, else the file name. */
const directTitle = (link: DirectVideoLink, title: string) => urlEntryTitle(link, title) ?? link.fileName;

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
      return [preview.entry.title, ...youtubeMeta(preview)].join(' · ');
    case 'direct': {
      if (preview.probe === null) return t.loadingPreview;
      const row = [directTitle(preview.link, ''), ...directMeta(preview.link, preview.probe)].filter(Boolean).join(' · ');
      return preview.probe.kind === 'unplayable' ? `${row}. ${t.videoUnplayableWeb}` : row;
    }
  }
}

/** The surface2 r24 card (handoff L/P): the 16:9 art (decorative), the title, then its lines. */
function PreviewCard({ art, title, children }: { art: ComponentChildren; title: string; children: ComponentChildren }) {
  return (
    <div class="avp-card">
      <div class="avp-art" aria-hidden="true">
        {art}
      </div>
      <p class="avp-title">{title}</p>
      {children}
    </div>
  );
}

function YouTubeCard({ preview }: { preview: { info: VideoInfo; entry: NewEntry } }) {
  const { entry, info } = preview;
  // A song (music.youtube.com) keeps the square thumbnail it gets in the queue, and no Video badge.
  const wide = entry.kind !== 'YOUTUBE_MUSIC';
  return (
    <PreviewCard
      title={entry.title}
      art={
        <>
          <Thumb url={entry.thumbnailUrl} wide={wide} />
          {wide && (
            <span class="vp-badge">
              <Badge kind={info.live ? 'live' : 'video'} />
            </span>
          )}
          {!info.live && (
            <span class="vp-duration">
              <DurationChip>{formatMs(entry.durationMs)}</DurationChip>
            </span>
          )}
        </>
      }
    >
      <p class="avp-meta avp-one">
        <span>{t.youtube} · </span>
        <span>{youtubeMeta(preview).join(' · ')}</span>
      </p>
    </PreviewCard>
  );
}

function DirectCard({ link, probe, title }: { link: DirectVideoLink; probe: ProbeOutcome | null; title: string }) {
  const domain = displayDomain(link.domain);
  const live = probe?.kind === 'ready' && probe.live;
  const badge = () => <Badge kind={live ? 'live' : 'video'} />;
  const length = probe?.kind === 'ready' && !probe.live && probe.durationMs > 0 ? formatMs(probe.durationMs) : null;
  return (
    <PreviewCard title={directTitle(link, title)} art={<VideoPlaceholder domain={domain} badge={badge()} duration={length} />}>
      <p class="avp-meta">
        <Icon name="link" size={16} />
        <span>{t.fromDomain(domain)}</span>
      </p>
      <p class="avp-meta">
        {badge()}
        {/* A live stream says so in its badge; anything else gives its length (or why not). */}
        <span>{[mediaFormat(link.url), live ? null : directDuration(probe)].filter(Boolean).join(' · ')}</span>
      </p>
      {probe?.kind === 'unplayable' && (
        <p class="avp-warn">
          <Icon name="error" size={16} />
          <span>{t.videoUnplayableWeb}</span>
        </p>
      )}
    </PreviewCard>
  );
}

/**
 * The "Video" tab of the add card (spec 6.1, handoff L and P): paste a link, preview it, add it.
 * Nothing is added automatically, and the preview starts only on the "Pratinjau" button or Enter
 * (the server allows 30 lookups per minute per IP); typing or pasting never looks anything up.
 * YouTube links need /api/video to answer (no made-up titles); direct links are probed by a hidden
 * <video> and may be added even when this browser cannot play them, because an Android member
 * might. What is sent is exactly what toVideoEntry/toUrlEntry return; only the shown domain is
 * IDN-decoded.
 */
export function AddVideoPanel({ session, online, pageIsHttps, draft, fetchImpl, probe }: Props) {
  const [text, setText] = useDraft(draft, 'text', '');
  const [title, setTitle] = useDraft(draft, 'title', '');
  const [preview, setPreview] = useDraft<Preview>(draft, 'preview', { kind: 'idle' });
  const [pasteFailed, setPasteFailed] = useState(false);
  const inflight = useRef<AbortController | null>(null);
  const input = useRef<HTMLInputElement>(null);
  useEffect(
    () => () => {
      inflight.current?.abort();
      inflight.current = null;
      // A lookup cut short here is not waited for when the panel comes back: "Pratinjau" again.
      const p = draft?.preview as Preview | undefined;
      if (p?.kind === 'loading' || (p?.kind === 'direct' && !p.probe)) draft!.preview = { kind: 'idle' };
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
    setPasteFailed(false);
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

  /** Fills the field only; the lookup still waits for "Pratinjau". A refusal is said, never thrown. */
  const paste = async () => {
    try {
      const pasted = (await navigator.clipboard.readText()).trim();
      if (pasted !== '') changeLink(pasted.slice(0, MAX_MEDIA_URL_LENGTH));
    } catch {
      setPasteFailed(true);
    }
    input.current?.focus();
  };

  const directEntry = (p: Preview): NewEntry | null =>
    p.kind === 'direct' && p.probe ? toUrlEntry(p.link, title, p.probe.kind === 'ready' ? p.probe.durationMs : 0) : null;

  const entry = preview.kind === 'youtube' ? preview.entry : directEntry(preview);
  const name = preview.kind === 'youtube' ? preview.entry.title : preview.kind === 'direct' ? directTitle(preview.link, title) : null;

  const add = (playNext: boolean) => {
    if (!entry) return;
    session.add(entry, playNext);
    setText('');
    setTitle('');
    setPreview({ kind: 'idle' });
  };

  const busy = preview.kind === 'loading';
  const error = preview.kind === 'error' ? preview.message : null;
  return (
    <>
      <form class="add-search avp-form" onSubmit={onSubmit}>
        <TextField
          id="video-link-input"
          label={t.videoLinkLabel}
          inputRef={input}
          inputMode="url"
          autoComplete="off"
          spellcheck={false}
          value={text}
          maxLength={MAX_MEDIA_URL_LENGTH}
          placeholder={t.videoLinkPlaceholder}
          errorId={error && ERROR_ID}
          onInput={(e) => changeLink(e.currentTarget.value)}
        />
        {canReadClipboard() && <IconButton icon="content_paste" variant="filled" label={t.pasteLink} title={t.pasteLink} onClick={() => void paste()} />}
        <IconButton type="submit" icon="search" variant="accent" label={busy ? t.loadingPreview : t.preview} disabled={busy} />
      </form>
      {error && <FieldError id={ERROR_ID}>{error}</FieldError>}
      {error === videoLinkProblemText.unrecognized && <p class="add-hint">{t.videoLinkHint}</p>}
      {pasteFailed && <p class="add-hint">{t.pasteFailed}</p>}
      {preview.kind === 'youtube' && <YouTubeCard preview={preview} />}
      {preview.kind === 'direct' && (
        <>
          <DirectCard link={preview.link} probe={preview.probe} title={title} />
          <TextField
            id="video-title-input"
            label={t.titleOptional}
            value={title}
            maxLength={MAX_TEXT_LENGTH}
            placeholder={t.titlePlaceholder}
            onInput={(e) => setTitle(e.currentTarget.value)}
          />
        </>
      )}
      <div class="avp-actions">
        <Button icon="playlist_play" disabled={!online || !entry} aria-label={name ? `${t.playNext}: ${name}` : undefined} onClick={() => add(true)}>
          {t.playNext}
        </Button>
        <Button variant="primary" icon="add" disabled={!online || !entry} aria-label={name ? `${t.addToQueue}: ${name}` : undefined} onClick={() => add(false)}>
          {t.addToQueue}
        </Button>
      </div>
      <p class="visually-hidden" role="status" aria-live="polite">
        {pasteFailed ? t.pasteFailed : announcement(preview)}
      </p>
      <p class="add-tip">
        <Icon name="info" size={16} />
        <span>{t.videoTip}</span>
      </p>
    </>
  );
}
