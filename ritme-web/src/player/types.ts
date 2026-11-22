/** What a player plays. */
export type PlayableSource = { kind: 'youtube'; videoId: string } | { kind: 'video'; url: string; hls: boolean };

export type PlayerKind = PlayableSource['kind'];

export type PlayerEvent = { type: 'playing' } | { type: 'paused' } | { type: 'unplayable' };

/**
 * A media player adapter (YouTube IFrame or HTML5 <video>). Calls made before the underlying
 * player is ready are remembered and applied once it is.
 */
export interface MediaPlayer {
  /** Prepares the source paused at startMs (null: the player's default, e.g. the live edge). */
  load(source: PlayableSource, startMs: number | null): void;
  play(): void;
  pause(): void;
  seek(ms: number): void;
  /** Current position, or null while unknown (not ready yet). */
  currentMs(): number | null;
  destroy(): void;
}

export type PlayerFactory = (kind: PlayerKind, onEvent: (event: PlayerEvent) => void) => MediaPlayer;
