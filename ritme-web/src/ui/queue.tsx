import { useEffect, useState } from 'preact/hooks';
import { type QueueEntry, type RoomState, isLive } from '../protocol';
import { canControl, canRemove, moveDownTarget, moveUpTarget, splitQueue } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { entrySource, isVideoEntry, memberName, unplayableLabel } from './badges';
import { Badge, Button, EqBars, IconButton, cx } from './components';
import { formatMs } from './format';
import { Icon } from './icon';
import { MediaRow, Thumb } from './media';
import { t } from './strings';

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  unplayable: Readonly<Record<string, true>>;
  online: boolean;
  pageIsHttps: boolean;
  /** upcoming: "Berikutnya" and "Sudah diputar" (mobile, under the player); full: also "Sedang diputar" (desktop column). */
  variant?: 'upcoming' | 'full';
  /** Opens the add sheet (or focuses the add panel); omitted while the member may not add. */
  onAdd?: () => void;
}

interface RowProps extends Props {
  entry: QueueEntry;
  upcoming: boolean;
  current: boolean;
  /** The queue holds videos: every thumbnail is 78 × 44 (DECISIONS 4). */
  mixed: boolean;
  open: boolean;
  onToggle: () => void;
}

function Row({ entry, session, room, memberId, unplayable, online, pageIsHttps, upcoming, current, mixed, open, onToggle }: RowProps) {
  const control = online && canControl(room, memberId);
  const removable = online && canRemove(room, memberId, entry);
  const up = upcoming && control ? moveUpTarget(room, entry.entryId) : null;
  const down = upcoming && control ? moveDownTarget(room, entry.entryId) : null;
  const label = unplayableLabel(entry, unplayable, pageIsHttps);
  const video = isVideoEntry(entry);
  const live = isLive(entry);
  const by = t.addedBy(memberName(room.members, entry.addedBy, memberId));
  const hasActions = !current && (control || removable);
  const actionsId = `row-actions-${entry.entryId}`;
  // A video's badge says Video or LIVE and its length is on the thumbnail; a song's meta says LIVE.
  const meta = video ? (
    <>
      <Badge kind={live ? 'live' : 'video'} />
      <span>{[entrySource(entry), by].join(' · ')}</span>
    </>
  ) : (
    <span>{[entrySource(entry), live ? t.live : null, by].filter(Boolean).join(' · ')}</span>
  );

  return (
    <MediaRow
      current={current}
      thumb={
        <Thumb
          url={entry.thumbnailUrl}
          wide={mixed}
          kind={entry.kind === 'URL' ? 'direct' : video ? 'video' : 'song'}
          duration={video && !live ? formatMs(entry.durationMs) : null}
        />
      }
      title={entry.title}
      meta={meta}
      warning={label}
      trailing={
        current ? (
          <EqBars playing={room.playback.isPlaying} />
        ) : (
          hasActions && (
            <IconButton icon="more_vert" label={t.rowActions(entry.title)} aria-expanded={open} aria-controls={actionsId} onClick={onToggle} />
          )
        )
      }
      below={
        open &&
        hasActions && (
          <div id={actionsId} class="row-actions">
            {control && (
              <Button icon="play_arrow" class="row-play" aria-label={`${t.playNow}: ${entry.title}`} onClick={() => session.playEntry(entry.entryId)}>
                {t.playNow}
              </Button>
            )}
            {up && (
              <IconButton icon="arrow_upward" variant="filled" label={`${t.moveUp}: ${entry.title}`} onClick={() => session.move(entry.entryId, up.afterEntryId)} />
            )}
            {down && (
              <IconButton
                icon="arrow_downward"
                variant="filled"
                label={`${t.moveDown}: ${entry.title}`}
                onClick={() => session.move(entry.entryId, down.afterEntryId)}
              />
            )}
            {removable && (
              <IconButton icon="delete" variant="danger" label={`${t.remove}: ${entry.title}`} onClick={() => session.remove(entry.entryId)} />
            )}
          </div>
        )
      }
    />
  );
}

/** "Antrean kosong." with the vinyl mark and, for members who may add, the way to start. */
function EmptyQueue({ onAdd }: { onAdd?: () => void }) {
  return (
    <div class="empty-queue">
      <span class="empty-mark">
        <Icon name="album" size={40} />
      </span>
      <p class="empty-title">{t.queueEmpty}</p>
      <p class="empty-sub">{t.nothingPlaying}</p>
      {onAdd && (
        <Button variant="primary" icon="add" class="queue-add" onClick={onAdd}>
          {t.addSong}
        </Button>
      )}
    </div>
  );
}

export function QueueList(props: Props) {
  const { room, variant = 'upcoming', onAdd } = props;
  const { played, current, upcoming } = splitQueue(room);
  const [openId, setOpenId] = useState<string | null>(null);
  const [showPlayed, setShowPlayed] = useState(false);
  const full = variant === 'full';
  const mixed = room.queue.some(isVideoEntry);
  // A bar whose row is gone, or whose actions vanished (control revoked, offline), must not reopen by itself later.
  const openEntry = openId === null ? null : room.queue.find((e) => e.entryId === openId);
  const openStillValid =
    !!openEntry &&
    openEntry.entryId !== current?.entryId &&
    props.online &&
    (canControl(room, props.memberId) || canRemove(room, props.memberId, openEntry));
  useEffect(() => {
    if (openId !== null && !openStillValid) setOpenId(null);
  }, [openId, openStillValid]);
  const row = (entry: QueueEntry, kind: 'current' | 'upcoming' | 'played') => (
    <Row
      key={entry.entryId}
      {...props}
      entry={entry}
      current={kind === 'current'}
      upcoming={kind === 'upcoming'}
      mixed={mixed}
      open={openId === entry.entryId}
      onToggle={() => setOpenId(openId === entry.entryId ? null : entry.entryId)}
    />
  );

  if (room.queue.length === 0) {
    return (
      <section class={cx('queue-panel', `queue-${variant}`)} aria-labelledby="queue-title">
        <h2 id="queue-title" class="visually-hidden">
          {full ? t.queue : t.upNext}
        </h2>
        <EmptyQueue onAdd={onAdd} />
      </section>
    );
  }

  return (
    <section class={cx('queue-panel', `queue-${variant}`)} aria-labelledby="queue-title">
      <div class="queue-header">
        <h2 id="queue-title">{full ? t.queue : t.upNext}</h2>
        {onAdd && (
          <Button icon="add" variant={full ? 'primary' : 'secondary'} class="queue-add" onClick={onAdd}>
            {t.addSong}
          </Button>
        )}
      </div>
      {full && current && (
        <>
          <h3 class="queue-section">{t.nowPlaying}</h3>
          <ul class="rows">{row(current, 'current')}</ul>
        </>
      )}
      {full && <h3 class="queue-section">{t.upNext}</h3>}
      {upcoming.length === 0 ? <p class="queue-none">{t.queueEmpty}</p> : <ul class="rows">{upcoming.map((e) => row(e, 'upcoming'))}</ul>}
      {played.length > 0 && (
        <div class="played">
          <button type="button" class="played-toggle" aria-expanded={showPlayed} aria-controls="played-list" onClick={() => setShowPlayed(!showPlayed)}>
            {t.played(played.length)}
            <Icon name="keyboard_arrow_down" size={22} />
          </button>
          {showPlayed && (
            <ul id="played-list" class="rows">
              {played.map((e) => row(e, 'played'))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
