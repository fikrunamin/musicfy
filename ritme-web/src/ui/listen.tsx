import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { PlaybackController } from '../player/controller';
import { createVideoPlayer } from '../player/html5';
import { playability } from '../player/playable';
import { createYouTubePlayer, loadYouTubeApi } from '../player/youtube';
import { type RoomState, currentEntry } from '../protocol';
import type { SessionApi } from '../session/session';
import { Icon } from './icon';
import { t } from './strings';
import { keepScreenOn } from './wake-lock';

export type Mode = 'listen' | 'remote';

/** "Mode dengar" / "Mode remote" (aria-pressed toggle buttons) and the remote hint. */
export function ModeSwitch({ mode, onModeChange }: { mode: Mode; onModeChange: (mode: Mode) => void }) {
  return (
    <div class="mode-switch">
      <div class="segmented" role="group" aria-label={t.modeGroup}>
        <button type="button" aria-pressed={mode === 'listen'} onClick={() => onModeChange('listen')}>
          <Icon name="headphones" size={20} />
          {t.listenMode}
        </button>
        <button type="button" aria-pressed={mode === 'remote'} onClick={() => onModeChange('remote')}>
          <Icon name="settings_remote" size={20} />
          {t.remoteMode}
        </button>
      </div>
      {mode === 'remote' && <p class="mode-hint">{t.modeHint}</p>}
    </div>
  );
}

interface StageProps {
  session: SessionApi;
  room: RoomState;
  unplayable: Readonly<Record<string, true>>;
  pageIsHttps: boolean;
  /** What the slot shows while there is no player to show: the cover, or the empty placeholder. */
  cover: ComponentChildren;
  /** Shown in the slot when the current entry cannot play in this browser. */
  unplayableSlot: ComponentChildren;
  /** The controller asks for (or no longer needs) a tap on the player itself. */
  onNeedsTap: (needsTap: boolean) => void;
}

/**
 * The visible player (never hidden behind anything, per YouTube's terms); exists only after the
 * tap gate. Its container stays mounted while the stage changes shape, so the iframe is never
 * re-created by a layout change.
 */
function PlayerHost({ session, room, unplayable, pageIsHttps, cover, unplayableSlot, onNeedsTap }: StageProps) {
  const container = useRef<HTMLDivElement>(null);
  const controller = useRef<PlaybackController | null>(null);

  useEffect(() => {
    const el = container.current!;
    const c = new PlaybackController({
      createPlayer: (kind, onEvent) => (kind === 'youtube' ? createYouTubePlayer(el, onEvent) : createVideoPlayer(el, onEvent)),
      serverNow: () => session.serverNow(),
      pageIsHttps,
      onUnplayable: (entryId) => session.markUnplayable(entryId),
      onNeedsTap,
    });
    controller.current = c;
    c.update(room, unplayable);
    // The session decides when: on becoming visible, or once the clock is fresh after a sleep or reconnect.
    const offResync = session.onResync(() => c.resync());
    const release = keepScreenOn();
    return () => {
      offResync();
      release();
      c.destroy();
      controller.current = null;
      onNeedsTap(false);
    };
  }, [session, pageIsHttps]);

  useEffect(() => {
    controller.current?.update(room, unplayable);
  }, [room, unplayable]);

  const entry = currentEntry(room);
  const verdict = entry ? playability(entry, pageIsHttps) : null;
  const hidden = !verdict?.playable || (entry !== null && unplayable[entry.entryId] === true);

  return (
    <>
      <div ref={container} class="player" hidden={hidden} />
      {hidden && (entry ? unplayableSlot : cover)}
    </>
  );
}

/**
 * The listen-mode content of the stage (brief §11): before the first tap the cover with
 * "Ketuk untuk ikut dengar" (browsers need one gesture before sound); after it, the player in the
 * same slot with nothing on top. Leaving listen mode unmounts this, so coming back asks again.
 */
export function ListenStage(props: StageProps) {
  const [gateOpen, setGateOpen] = useState(false);

  // Fetch the IFrame API early so the player appears quickly after the tap.
  useEffect(() => {
    loadYouTubeApi().catch(() => {});
  }, []);

  if (gateOpen) return <PlayerHost {...props} />;
  return (
    <>
      {props.cover}
      <div class="stage-gate">
        <button type="button" class="btn btn-primary gate-btn" onClick={() => setGateOpen(true)}>
          <Icon name="headphones" size={22} />
          <span class="btn-label">{t.tapToListen}</span>
        </button>
        <p class="gate-reason">{t.tapReason}</p>
      </div>
    </>
  );
}
