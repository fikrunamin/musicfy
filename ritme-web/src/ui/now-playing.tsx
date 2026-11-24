import { useEffect, useRef, useState } from 'preact/hooks';
import { displayHostOf } from '../net/idn';
import { httpsThumbnail } from '../net/search';
import { type QueueEntry, type RoomState, currentEntry, isLive, positionAt } from '../protocol';
import { canControl } from '../session/permissions';
import type { Notice } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { entrySource, isVideoEntry, mediaFormat, memberName, unplayableLabel } from './badges';
import { Avatar, AvatarStack, Badge, DurationChip, EqBars, IconButton, SyncDot, cx } from './components';
import { formatMs } from './format';
import { canFullscreen, useFullscreen } from './fullscreen';
import { useServerNow } from './hooks';
import { Icon } from './icon';
import { ListenStage, type Mode } from './listen';
import { Crossfade } from './motion';
import { VideoPlaceholder } from './media';
import { t } from './strings';
import { Toast } from './toast';

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
  /** The room's latest notice: the app toast is outside a fullscreen section, so it shows here then. */
  notice?: Notice | null;
}

const LOCKED_HINT_ID = 'controls-locked-hint';

/** What the stage shows when there is no player to show (remote mode, before the tap, nothing playing). */
function CoverLayer({ entry, gated, entering }: { entry: QueueEntry | null; gated: boolean; entering: boolean }) {
  const fade = entering && 'xfade-in';
  if (!entry) {
    // Under the tap gate the icon would only show as an arc around the button, so leave it out.
    return <div class={cx('stage-empty', fade)}>{!gated && <Icon name="album" size={56} />}</div>;
  }
  const src = httpsThumbnail(entry.thumbnailUrl);
  // Remote mode (no gate, never a player): a video's thumbnail carries its badge and length (I/M remote).
  const live = isLive(entry);
  const decor = !gated && isVideoEntry(entry);
  const badge = decor && <Badge kind={live ? 'live' : 'video'} />;
  const length = decor && !live ? formatMs(entry.durationMs) : null;
  if (src) {
    const cover = <img class={cx('stage-cover', !decor && fade)} src={src} alt="" referrerpolicy="no-referrer" />;
    if (!decor) return cover;
    return (
      <div class={cx('stage-thumb', fade)}>
        {cover}
        <span class="vp-badge">{badge}</span>
        {length && (
          <span class="vp-duration">
            <DurationChip>{length}</DurationChip>
          </span>
        )}
      </div>
    );
  }
  if (entry.kind === 'URL') return <VideoPlaceholder domain={displayHostOf(entry.url) ?? entry.artist} badge={badge} duration={length} class={fade || undefined} />;
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

export function NowPlaying({ session, room, memberId, unplayable, online, pageIsHttps, mode, onShowQueue, notice }: Props) {
  const now = useServerNow(session);
  const section = useRef<HTMLElement>(null);
  const full = useFullscreen(section, () => session.notify('failed'));
  /** The notice shown when fullscreen began: only newer ones go to the side bar. */
  const seen = useRef(notice?.id);
  if (!full.active) seen.current = notice?.id;
  // Leaving fullscreen with the button gone (a song took the video's place): focus goes to the title,
  // not to <body>. The button keeps its slot, so while it is there it keeps focus by itself.
  const wasFull = useRef(false);
  useEffect(() => {
    if (wasFull.current && !full.active && (!document.activeElement || document.activeElement === document.body))
      (section.current?.querySelector<HTMLElement>('.np-fs') ?? document.getElementById('now-title'))?.focus();
    wasFull.current = full.active;
  }, [full.active]);
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
  const video = entry !== null && isVideoEntry(entry);
  const direct = entry?.kind === 'URL';
  const wide = video || live;
  const label = entry ? unplayableLabel(entry, unplayable, pageIsHttps) : null;
  const playing = room.playback.isPlaying;
  const cover = <Cover entry={entry} gated={mode === 'listen'} />;
  const stageArt = entry ? httpsThumbnail(entry.thumbnailUrl) : null;
  /** ±10 s (direct links, handoff M): from where the room is now, within the video. */
  const seekBy = (deltaMs: number) =>
    guarded(() => !live && session.seek(Math.min(Math.max(0, positionAt(room, session.serverNow()) + deltaMs), entry!.durationMs)));
  const skip = { disabled: !online || live, ...lockedProps };
  // Outside the video, never on it (J/N). Kept while fullscreen, so a song that follows can leave it.
  const fsButton = (full.active || (video && mode === 'listen' && canFullscreen())) && (
    <IconButton
      icon={full.active ? 'fullscreen_exit' : 'fullscreen'}
      label={full.active ? t.exitFullscreen : t.fullscreen}
      variant="filled"
      class="np-fs"
      disabled={!full.active && label !== null}
      onClick={full.toggle}
    />
  );
  const adder = memberName(room.members, entry?.addedBy ?? '', null);
  const source = direct ? mediaFormat(entry.url) : entry && [entry.artist, t.youtube].filter(Boolean).join(' · ');

  return (
    <section ref={section} class={cx('now-playing', `np-${mode}`, video && 'np-video', full.active && 'np-full')} aria-labelledby="now-title">
      <div class="np-label-row">
        <h2 id="now-title" class="np-label" tabIndex={-1}>
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

      {/* The artwork is also the stage's first layer, under everything: when the tap gate opens,
          the player fades in (400 ms, its own opacity) over it, so the cover crossfades to the
          player with nothing laid on top of the player. An <img> so it loads without a Referer
          (a CSS background cannot); always rendered, empty and hidden without artwork, so the
          player after it keeps its place in the tree. */}
      <div class={cx('stage', wide ? 'stage-wide' : 'stage-square')}>
        <img class="stage-art" src={stageArt ?? undefined} hidden={!stageArt} alt="" aria-hidden="true" referrerpolicy="no-referrer" />
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
            {video ? (
              <>
                {direct && (
                  <p class="np-source">
                    <Icon name="link" size={18} />
                    {entrySource(entry)}
                  </p>
                )}
                {/* Handoff I/M: the badge, the source (channel on YouTube, or the format), who added it. */}
                <p class={cx('np-badges', !direct && 'np-yt')}>
                  <Badge kind={live ? 'live' : 'video'} />
                  {source && <span class="np-src">{source}</span>}
                  <span class="np-by">
                    <Avatar name={adder} size="xs" />
                    {t.addedBy(memberName(room.members, entry.addedBy, memberId))}
                  </span>
                </p>
              </>
            ) : (
              <p class="np-sub">
                {[entrySource(entry), t.addedBy(memberName(room.members, entry.addedBy, memberId))].filter(Boolean).join(' · ')}
              </p>
            )}
            {label && <p class="np-warning">{label}</p>}
          </div>

          {/* A live song says LIVE where the seek bar was; a live video says it in its badge row. */}
          {live ? (
            !video && <Badge kind="live" class="np-live" />
          ) : (
            <Progress session={session} room={room} entry={entry} now={now} dragMs={dragMs} setDragMs={setDragMs} control={control} />
          )}

          <div class="np-controls">
            <span class="np-slot">{direct && <IconButton icon="replay_10" label={t.back10} {...skip} onClick={seekBy(-10_000)} />}</span>
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
            {/* +10 s, or the phone's queue button where there is no fullscreen button to take its place. */}
            <span class="np-slot">
              {direct ? (
                <IconButton icon="forward_10" label={t.forward10} {...skip} onClick={seekBy(10_000)} />
              ) : (
                !fsButton && onShowQueue && <IconButton icon="queue_music" label={t.queue} onClick={onShowQueue} />
              )}
            </span>
            {/* One slot, the last, for every kind of entry: the button is never re-created, so it keeps focus. */}
            {fsButton}
          </div>
        </>
      ) : (
        room.queue.length > 0 && <p class="np-empty">{t.nothingPlaying}</p>
      )}

      {mode === 'listen' ? (
        <p class="np-tip">
          <Icon name="light_mode" size={18} />
          {t.keepScreenOn}
        </p>
      ) : (
        // The theater puts the remote hint here, under the controls (Z14e90); hidden elsewhere.
        video && (
          <p class="np-tip np-remote-tip">
            <Icon name="settings_remote" size={18} />
            {t.modeHint}
          </p>
        )
      )}
      {locked && (
        <p id={LOCKED_HINT_ID} class="visually-hidden">
          {t.controlsLocked}
        </p>
      )}
      {/* Fullscreen side bar (J/N): sync status and members, beside the video, after it in the tree. */}
      {full.active && (
        <div class="np-fs-status">
          <p class="room-status">
            <SyncDot state={online ? 'online' : 'reconnecting'} />
            {online ? t.synced : t.reconnecting}
          </p>
          <p class="room-status">
            <AvatarStack names={room.members.map((m) => m.name)} />
            {t.members(room.members.length)}
          </p>
          <Toast notice={(notice?.id !== seen.current && notice) || null} />
        </div>
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
