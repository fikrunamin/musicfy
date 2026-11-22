import { useEffect, useRef, useState } from 'preact/hooks';
import { PlaybackController } from '../player/controller';
import { createVideoPlayer } from '../player/html5';
import { playability } from '../player/playable';
import { createYouTubePlayer, loadYouTubeApi } from '../player/youtube';
import { type RoomState, currentEntry } from '../protocol';
import type { SessionApi } from '../session/session';
import { t } from './strings';
import { keepScreenOn } from './wake-lock';

export type Mode = 'listen' | 'remote';

interface Props {
  session: SessionApi;
  room: RoomState;
  unplayable: Readonly<Record<string, true>>;
  mode: Mode;
  onModeChange: (mode: Mode) => void;
  pageIsHttps: boolean;
}

/** The visible player (never hidden, per YouTube's terms); exists only after the tap gate. */
function PlayerHost({ session, room, unplayable, pageIsHttps }: Omit<Props, 'mode' | 'onModeChange'>) {
  const container = useRef<HTMLDivElement>(null);
  const controller = useRef<PlaybackController | null>(null);
  const [needsTap, setNeedsTap] = useState(false);

  useEffect(() => {
    const el = container.current!;
    const c = new PlaybackController({
      createPlayer: (kind, onEvent) => (kind === 'youtube' ? createYouTubePlayer(el, onEvent) : createVideoPlayer(el, onEvent)),
      serverNow: () => session.serverNow(),
      pageIsHttps,
      onUnplayable: (entryId) => session.markUnplayable(entryId),
      onNeedsTap: setNeedsTap,
    });
    controller.current = c;
    c.update(room, unplayable);
    const onVisible = () => {
      if (document.visibilityState === 'visible') c.resync();
    };
    document.addEventListener('visibilitychange', onVisible);
    const release = keepScreenOn();
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      release();
      c.destroy();
      controller.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- one controller per mount; snapshots go through update() below
  }, [session, pageIsHttps]);

  useEffect(() => {
    controller.current?.update(room, unplayable);
  }, [room, unplayable]);

  const entry = currentEntry(room);
  const verdict = entry ? playability(entry, pageIsHttps) : null;
  const large = verdict?.playable === true && verdict.large;
  const hidden = !verdict?.playable || (entry !== null && unplayable[entry.entryId] === true);

  return (
    <>
      <div ref={container} class={`player ${large ? 'player-large' : 'player-small'}`} hidden={hidden} />
      {needsTap && <p class="hint">{t.tapPlayer}</p>}
      <p class="muted small">{t.keepScreenOn}</p>
    </>
  );
}

export function ListenPanel({ session, room, unplayable, mode, onModeChange, pageIsHttps }: Props) {
  const [gateOpen, setGateOpen] = useState(false);

  // Fetch the IFrame API early so the player appears quickly after the tap.
  useEffect(() => {
    if (mode === 'listen') loadYouTubeApi().catch(() => {});
  }, [mode]);

  return (
    <section class="card listen" aria-label={t.listenMode}>
      <div class="segmented" role="radiogroup" aria-label={t.listenMode}>
        <button type="button" role="radio" aria-checked={mode === 'listen'} onClick={() => onModeChange('listen')}>
          {t.listenMode}
        </button>
        <button type="button" role="radio" aria-checked={mode === 'remote'} onClick={() => onModeChange('remote')}>
          {t.remoteMode}
        </button>
      </div>
      {mode === 'remote' ? (
        <p class="muted small">{t.modeHint}</p>
      ) : gateOpen ? (
        <PlayerHost session={session} room={room} unplayable={unplayable} pageIsHttps={pageIsHttps} />
      ) : (
        <button type="button" class="btn btn-primary btn-block" onClick={() => setGateOpen(true)}>
          {t.tapToListen}
        </button>
      )}
    </section>
  );
}
