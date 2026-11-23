import { useState } from 'preact/hooks';
import { displayHostOf } from '../net/idn';
import { httpsThumbnail } from '../net/search';
import { type QueueEntry, type RoomState, currentEntry, isLive, positionAt } from '../protocol';
import { canControl } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { entrySource, isVideoEntry, memberName, unplayableLabel } from './badges';
import { Badge, EqBars, IconButton, cx } from './components';
import { formatMs } from './format';
import { useServerNow } from './hooks';
import { Icon } from './icon';
import { ListenStage, type Mode } from './listen';
import { Crossfade } from './motion';
import { VideoPlaceholder } from './media';
import { t } from './strings';

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  unplayable: Readonly<Record<string, true>>;
  online: boolean;
  pageIsHttps: boolean;
  mode: Mode;
  /** Mobile: the queue button under the controls scrolls to the queue. */
  onShowQueue?: () => void;
}

const LOCKED_HINT_ID = 'controls-locked-hint';

/** A CSS url() for an https thumbnail: the parsed href has no raw quote or newline; backslashes are encoded too. */
const cssUrl = (src: string) => `url("${new URL(src).href.replace(/\\/g, '%5C')}")`;

/** What the stage shows when there is no player to show (remote mode, before the tap, nothing playing). */
function CoverLayer({ entry, gated, entering }: { entry: QueueEntry | null; gated: boolean; entering: boolean }) {
  const fade = entering && 'xfade-in';
  if (!entry) {
    // Under the tap gate the icon would only show as an arc around the button, so leave it out.
    return <div class={cx('stage-empty', fade)}>{!gated && <Icon name="album" size={56} />}</div>;
  }
  const src = httpsThumbnail(entry.thumbnailUrl);
  if (src) return <img class={cx('stage-cover', fade)} src={src} alt="" referrerpolicy="no-referrer" />;
  if (entry.kind === 'URL') return <VideoPlaceholder domain={displayHostOf(entry.url) ?? entry.artist} class={fade || undefined} />;
  return (
    <div class={cx('stage-empty', fade)}>
      <Icon name={isVideoEntry(entry) ? 'smart_display' : 'music_note'} size={56} />
    </div>
  );
}

/** The cover, crossfading (600 ms) to the next one when the song changes. */
function Cover({ entry, gated }: { entry: QueueEntry | null; gated: boolean }) {
  return (
    <Crossfade value={entry} keyOf={(e) => e?.entryId ?? ''}>
      {(e, entering) => <CoverLayer entry={e} gated={gated} entering={entering} />}
    </Crossfade>
  );
}

/** "Video ini tidak bisa diputar di web" in the stage, with Lewati for members with control. */
function StageError({ entry, label, canSkip, skipDisabled, onSkip }: { entry: QueueEntry; label: string | null; canSkip: boolean; skipDisabled: boolean; onSkip: () => void }) {
  return (
    <div class="stage-error">
      <Icon name="warning" size={28} />
      <p class="stage-error-title">{isVideoEntry(entry) ? t.videoUnplayableWeb : (label ?? t.unplayableWeb)}</p>
      <p class="stage-error-reason">{t.unplayableReason}</p>
      {canSkip ? (
        <button type="button" class="btn btn-secondary stage-skip" disabled={skipDisabled} onClick={onSkip}>
          <Icon name="skip_next" size={20} />
          <span class="btn-label">{t.skip}</span>
        </button>
      ) : (
        <p class="stage-error-wait">{t.waitingForHost}</p>
      )}
    </div>
  );
}

export function NowPlaying({ session, room, memberId, unplayable, online, pageIsHttps, mode, onShowQueue }: Props) {
  const now = useServerNow(session);
  const [dragMs, setDragMs] = useState<number | null>(null);
  const [needsTap, setNeedsTap] = useState(false);
  const entry = currentEntry(room);
  const allowed = canControl(room, memberId);
  const control = online && allowed;
  const locked = !allowed;
  /** Locked controls stay tappable (35 % opacity) and explain themselves with a toast. */
  const guarded = (action: () => void) => () => {
    if (locked) session.notify('forbidden');
    else action();
  };
  const lockedProps = locked ? { 'aria-disabled': 'true' as const, 'aria-describedby': LOCKED_HINT_ID } : {};

  const live = entry !== null && isLive(entry);
  const wide = entry !== null && (isVideoEntry(entry) || live);
  const label = entry ? unplayableLabel(entry, unplayable, pageIsHttps) : null;
  const playing = room.playback.isPlaying;
  const cover = <Cover entry={entry} gated={mode === 'listen'} />;
  const stageArt = entry ? httpsThumbnail(entry.thumbnailUrl) : null;

  return (
    <section class={cx('now-playing', `np-${mode}`)} aria-labelledby="now-title">
      <div class="np-label-row">
        <h2 id="now-title" class="np-label">
          <EqBars playing={playing && entry !== null} />
          {t.nowPlaying}
        </h2>
        {mode === 'listen' && entry && (
          <p class="np-sound">
            <Icon name={entry.kind === 'URL' ? 'link' : 'smart_display'} size={18} />
            {entry.kind === 'URL' ? t.directLinkSource : t.soundFromYouTube}
          </p>
        )}
      </div>

      {/* The artwork is also the stage's own background, under everything: when the tap gate opens,
          the player fades in (400 ms, its own opacity) over it, so the cover crossfades to the
          player with nothing laid on top of the player. */}
      <div class={cx('stage', wide ? 'stage-wide' : 'stage-square')} style={stageArt ? { backgroundImage: cssUrl(stageArt) } : undefined}>
        {mode === 'listen' ? (
          <ListenStage
            session={session}
            room={room}
            unplayable={unplayable}
            pageIsHttps={pageIsHttps}
            cover={cover}
            unplayableSlot={entry && <StageError entry={entry} label={label} canSkip={allowed} skipDisabled={!online} onSkip={() => session.next()} />}
            onNeedsTap={setNeedsTap}
          />
        ) : (
          cover
        )}
      </div>
      {mode === 'listen' && needsTap && <p class="np-hint">{t.tapPlayer}</p>}

      {entry ? (
        <>
          <div class="np-meta">
            <p class="np-title">{entry.title}</p>
            <p class="np-sub">
              {[entrySource(entry), t.addedBy(memberName(room.members, entry.addedBy, memberId))].filter(Boolean).join(' · ')}
            </p>
            {label && <p class="np-warning">{label}</p>}
          </div>

          {live ? (
            <Badge kind="live" class="np-live" />
          ) : (
            <Progress session={session} room={room} entry={entry} now={now} dragMs={dragMs} setDragMs={setDragMs} control={control} />
          )}

          <div class="np-controls">
            <span class="np-slot" />
            <IconButton icon="skip_previous" label={t.previous} disabled={!online} {...lockedProps} onClick={guarded(() => session.previous())} />
            <IconButton
              icon={playing ? 'pause' : 'play_arrow'}
              label={playing ? t.pause : t.play}
              variant="accent"
              size="lg"
              disabled={!online}
              {...lockedProps}
              onClick={guarded(() => (playing ? session.pause() : session.play()))}
            />
            <IconButton icon="skip_next" label={t.next} disabled={!online} {...lockedProps} onClick={guarded(() => session.next())} />
            <span class="np-slot">{onShowQueue && <IconButton icon="queue_music" label={t.queue} onClick={onShowQueue} />}</span>
          </div>
        </>
      ) : (
        room.queue.length > 0 && <p class="np-empty">{t.nothingPlaying}</p>
      )}

      {mode === 'listen' && (
        <p class="np-tip">
          <Icon name="light_mode" size={18} />
          {t.keepScreenOn}
        </p>
      )}
      {locked && (
        <p id={LOCKED_HINT_ID} class="visually-hidden">
          {t.controlsLocked}
        </p>
      )}
    </section>
  );
}

interface ProgressProps {
  session: SessionApi;
  room: RoomState;
  entry: QueueEntry;
  now: number;
  dragMs: number | null;
  setDragMs: (ms: number | null) => void;
  control: boolean;
}

/** The seek bar (a native range input, styled) and the two times under it. */
function Progress({ session, room, entry, now, dragMs, setDragMs, control }: ProgressProps) {
  const position = dragMs ?? positionAt(room, now);
  const pct = entry.durationMs > 0 ? Math.min(100, (position / entry.durationMs) * 100) : 0;
  return (
    <div class="np-progress">
      <input
        type="range"
        class="seek"
        style={{ '--p': `${pct}%` }}
        aria-label={t.seek}
        aria-valuetext={formatMs(position)}
        min={0}
        max={entry.durationMs}
        step={1000}
        value={position}
        disabled={!control}
        onInput={(e) => setDragMs(Number(e.currentTarget.value))}
        onChange={(e) => {
          session.seek(Number(e.currentTarget.value));
          setDragMs(null);
        }}
      />
      <div class="np-times">
        <span>{formatMs(position)}</span>
        <span>{formatMs(entry.durationMs)}</span>
      </div>
    </div>
  );
}
