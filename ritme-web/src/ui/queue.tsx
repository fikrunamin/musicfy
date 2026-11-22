import { httpsThumbnail } from '../net/search';
import { type QueueEntry, type RoomState, isLive } from '../protocol';
import { canControl, canRemove, moveDownTarget, moveUpTarget, splitQueue } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { memberName, unplayableLabel } from './badges';
import { formatMs } from './format';
import { t } from './strings';

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  unplayable: Readonly<Record<string, true>>;
  online: boolean;
  pageIsHttps: boolean;
}

/** A 48 px thumbnail, or the placeholder unless the URL is https (entries come from any client). */
export function Thumb({ url }: { url: string | null }) {
  const src = httpsThumbnail(url);
  return src ? (
    <img class="thumb" src={src} alt="" loading="lazy" referrerpolicy="no-referrer" width={48} height={48} />
  ) : (
    <div class="thumb thumb-empty" aria-hidden="true">
      ♪
    </div>
  );
}

function Row({ entry, session, room, memberId, unplayable, online, pageIsHttps, upcoming }: Props & { entry: QueueEntry; upcoming: boolean }) {
  const control = online && canControl(room, memberId);
  const removable = online && canRemove(room, memberId, entry);
  const up = upcoming ? moveUpTarget(room, entry.entryId) : null;
  const down = upcoming ? moveDownTarget(room, entry.entryId) : null;
  const label = unplayableLabel(entry, unplayable, pageIsHttps);
  return (
    <li class="queue-row">
      <Thumb url={entry.thumbnailUrl} />
      <div class="queue-text">
        <span class="title">{entry.title}</span>
        <span class="muted small">
          {[entry.artist, isLive(entry) ? t.live : formatMs(entry.durationMs), t.addedBy(memberName(room.members, entry.addedBy, memberId))]
            .filter(Boolean)
            .join(' · ')}
        </span>
        {label && <span class="badge">{label}</span>}
      </div>
      <div class="queue-actions">
        {control && (
          <button type="button" class="icon-btn" aria-label={`${t.playNow}: ${entry.title}`} onClick={() => session.playEntry(entry.entryId)}>
            ▶
          </button>
        )}
        {control && up && (
          <button type="button" class="icon-btn" aria-label={`${t.moveUp}: ${entry.title}`} onClick={() => session.move(entry.entryId, up.afterEntryId)}>
            ▲
          </button>
        )}
        {control && down && (
          <button type="button" class="icon-btn" aria-label={`${t.moveDown}: ${entry.title}`} onClick={() => session.move(entry.entryId, down.afterEntryId)}>
            ▼
          </button>
        )}
        {removable && (
          <button type="button" class="icon-btn" aria-label={`${t.remove}: ${entry.title}`} onClick={() => session.remove(entry.entryId)}>
            ✕
          </button>
        )}
      </div>
    </li>
  );
}

export function QueueList(props: Props) {
  const { played, upcoming } = splitQueue(props.room);
  return (
    <section class="card" aria-labelledby="queue-title">
      <h2 id="queue-title">{t.upNext}</h2>
      {upcoming.length === 0 ? (
        <p class="muted">{t.queueEmpty}</p>
      ) : (
        <ul class="queue">
          {upcoming.map((entry) => (
            <Row key={entry.entryId} {...props} entry={entry} upcoming />
          ))}
        </ul>
      )}
      {played.length > 0 && (
        <details class="played">
          <summary>{t.played(played.length)}</summary>
          <ul class="queue">
            {played.map((entry) => (
              <Row key={entry.entryId} {...props} entry={entry} upcoming={false} />
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
