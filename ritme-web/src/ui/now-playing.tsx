import { useState } from 'preact/hooks';
import { type RoomState, currentEntry, isLive, positionAt } from '../protocol';
import { canControl } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { memberName, unplayableLabel } from './badges';
import { formatMs } from './format';
import { useServerNow } from './hooks';
import { Thumb } from './queue';
import { t } from './strings';

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  unplayable: Readonly<Record<string, true>>;
  online: boolean;
  pageIsHttps: boolean;
}

export function NowPlaying({ session, room, memberId, unplayable, online, pageIsHttps }: Props) {
  const now = useServerNow(session);
  const [dragMs, setDragMs] = useState<number | null>(null);
  const entry = currentEntry(room);
  const control = online && canControl(room, memberId);
  const lockedHint = canControl(room, memberId) ? undefined : t.controlsLocked;

  if (!entry) {
    return (
      <section class="card now-playing" aria-labelledby="now-title">
        <h2 id="now-title">{t.nowPlaying}</h2>
        <p class="muted">{t.nothingPlaying}</p>
      </section>
    );
  }

  const live = isLive(entry);
  const position = dragMs ?? positionAt(room, now);
  const label = unplayableLabel(entry, unplayable, pageIsHttps);
  const playing = room.playback.isPlaying;

  return (
    <section class="card now-playing" aria-labelledby="now-title">
      <h2 id="now-title">{t.nowPlaying}</h2>
      <div class="now-row">
        <Thumb url={entry.thumbnailUrl} />
        <div class="queue-text">
          <span class="title">{entry.title}</span>
          <span class="muted small">
            {[entry.artist, t.addedBy(memberName(room.members, entry.addedBy, memberId))].filter(Boolean).join(' · ')}
          </span>
          {label && <span class="badge">{label}</span>}
        </div>
      </div>

      {live ? (
        <p class="live-pill">{t.live}</p>
      ) : (
        <div class="progress">
          <input
            type="range"
            aria-label={t.seek}
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
          <div class="progress-times muted small">
            <span>{formatMs(position)}</span>
            <span>{formatMs(entry.durationMs)}</span>
          </div>
        </div>
      )}

      <div class="controls" title={lockedHint}>
        <button type="button" class="icon-btn" aria-label={t.previous} disabled={!control} onClick={() => session.previous()}>
          ⏮
        </button>
        <button
          type="button"
          class="icon-btn icon-btn-big"
          aria-label={playing ? t.pause : t.play}
          disabled={!control}
          onClick={() => (playing ? session.pause() : session.play())}
        >
          {playing ? '⏸' : '▶'}
        </button>
        <button type="button" class="icon-btn" aria-label={t.next} disabled={!control} onClick={() => session.next()}>
          ⏭
        </button>
      </div>
      {lockedHint && <p class="muted small center">{lockedHint}</p>}
    </section>
  );
}
