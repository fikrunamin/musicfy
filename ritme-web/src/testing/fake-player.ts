import type { MediaPlayer, PlayableSource, PlayerEvent, PlayerKind } from '../player/types';

/** Test double: records calls; `position` is what currentMs() reports. */
export class FakePlayer implements MediaPlayer {
  calls: string[] = [];
  loaded: { source: PlayableSource; startMs: number | null } | null = null;
  position: number | null = 0;
  destroyed = false;

  constructor(
    readonly kind: PlayerKind,
    readonly emit: (event: PlayerEvent) => void,
  ) {}

  load(source: PlayableSource, startMs: number | null): void {
    this.loaded = { source, startMs };
    this.calls.push(`load:${startMs}`);
  }
  play(): void {
    this.calls.push('play');
  }
  pause(): void {
    this.calls.push('pause');
  }
  seek(ms: number): void {
    this.calls.push(`seek:${ms}`);
    this.position = ms;
  }
  currentMs(): number | null {
    return this.position;
  }
  destroy(): void {
    this.destroyed = true;
  }
}
