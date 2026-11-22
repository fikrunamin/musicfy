/** What a player plays. */
export type PlayableSource = { kind: 'youtube'; videoId: string } | { kind: 'video'; url: string; hls: boolean };

export type PlayerKind = PlayableSource['kind'];

/**
 * `gen` echoes the generation passed to load(), so the controller can ignore events (late errors,
 * 'playing') that belong to a previous load of the same player. Absent means "current".
 */
export type PlayerEvent = ({ type: 'playing' } | { type: 'paused' } | { type: 'unplayable' }) & { gen?: number };

/**
 * A media player adapter (YouTube IFrame or HTML5 <video>). Calls made before the underlying
 * player is ready are remembered and applied once it is.
 */
export interface MediaPlayer {
  /**
   * Prepares the source paused at startMs (null: the player's default, e.g. the live edge).
   * From this call until the new source reports its position, currentMs() must return null.
   * `gen` identifies this load; the player echoes it in the events it emits for it.
   */
  load(source: PlayableSource, startMs: number | null, gen?: number): void;
  play(): void;
  pause(): void;
  seek(ms: number): void;
  /** Current position, or null while unknown (not ready yet, or right after load()). */
  currentMs(): number | null;
  destroy(): void;
}

export type PlayerFactory = (kind: PlayerKind, onEvent: (event: PlayerEvent) => void) => MediaPlayer;
