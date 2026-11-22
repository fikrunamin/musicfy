import { type QueueEntry, type RoomState, currentEntry, isLive, positionAt } from '../protocol';
import { shouldSeek } from '../sync/drift';
import { playability } from './playable';
import type { MediaPlayer, PlayerEvent, PlayerFactory, PlayerKind } from './types';

export const DRIFT_CHECK_MS = 1_000;
/** No 'playing' event this long after play(): the browser probably wants a tap on the player. */
export const NEEDS_TAP_AFTER_MS = 4_000;

export interface ControllerOptions {
  createPlayer: PlayerFactory;
  /** Estimated server time (Session.serverNow). */
  serverNow: () => number;
  pageIsHttps: boolean;
  onUnplayable: (entryId: string) => void;
  onNeedsTap: (needsTap: boolean) => void;
}

/**
 * Makes the local player follow the room (spec 5.4). It only reads room state and never sends
 * anything: no commands, and never TrackEnded. New entries are prepared paused and started at
 * the anchor; drift is corrected by seeking only (sync/drift.ts); live entries follow play/pause
 * but are never seeked.
 */
export class PlaybackController {
  private player: MediaPlayer | null = null;
  private playerKind: PlayerKind | null = null;
  private loadedEntryId: string | null = null;
  private room: RoomState | null = null;
  private entry: QueueEntry | null = null;
  private wantPlaying = false;
  private playerPlaying = false;
  private lastSeekAt: number | null = null;
  /** Entry + playback fields last applied; a forced re-sync happens only when they change. */
  private appliedKey: string | null = null;
  private startTimer: ReturnType<typeof setTimeout> | null = null;
  private tapTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly driftTimer: ReturnType<typeof setInterval>;

  constructor(private readonly opts: ControllerOptions) {
    this.driftTimer = setInterval(() => this.correct(false), DRIFT_CHECK_MS);
  }

  /** Call with every new room snapshot (and when the unplayable set changes). */
  update(room: RoomState | null, unplayable: Readonly<Record<string, true>>): void {
    this.room = room;
    const entry = room ? currentEntry(room) : null;
    const verdict = entry ? playability(entry, this.opts.pageIsHttps) : null;
    if (!entry || !verdict || !verdict.playable || unplayable[entry.entryId]) {
      this.entry = null;
      this.appliedKey = null;
      this.halt();
      return;
    }
    this.entry = entry;
    if (this.playerKind !== verdict.source.kind) {
      this.player?.destroy();
      this.player = this.opts.createPlayer(verdict.source.kind, (e) => this.onPlayerEvent(e));
      this.playerKind = verdict.source.kind;
      this.loadedEntryId = null;
      this.playerPlaying = false;
    }
    if (this.loadedEntryId !== entry.entryId) {
      this.loadedEntryId = entry.entryId;
      this.lastSeekAt = null;
      const start = isLive(entry) ? null : positionAt(room!, this.opts.serverNow());
      this.player!.load(verdict.source, start);
    }
    // Snapshots that only change members or the queue must not bypass the 5 s seek interval.
    const { isPlaying, positionMs, anchorServerTimeMs } = room!.playback;
    const key = `${entry.entryId}|${isPlaying}|${positionMs}|${anchorServerTimeMs}`;
    const changed = key !== this.appliedKey;
    this.appliedKey = key;
    this.apply(changed);
  }

  /** Tab visible again, or anything else that calls for an immediate re-sync. */
  resync(): void {
    this.apply(true);
  }

  destroy(): void {
    clearInterval(this.driftTimer);
    this.clearStart();
    this.clearTap();
    this.player?.destroy();
    this.player = null;
    this.playerKind = null;
    this.loadedEntryId = null;
  }

  /** force: playback changed, the anchor was reached, or the tab came back (skips the 5 s rule). */
  private apply(force: boolean): void {
    this.clearStart();
    const room = this.room;
    const player = this.player;
    if (!room || !player || !this.entry) return;
    const now = this.opts.serverNow();
    const { isPlaying, anchorServerTimeMs } = room.playback;
    if (isPlaying && now >= anchorServerTimeMs) {
      this.wantPlaying = true;
      player.play();
      this.armTap();
      this.correct(force);
      return;
    }
    this.wantPlaying = false;
    this.clearTap();
    player.pause();
    this.correct(force);
    if (isPlaying) this.startTimer = setTimeout(() => this.apply(true), anchorServerTimeMs - now);
  }

  /** Paused, or waiting for the anchor: correct toward the room position, playing or not. */
  private correct(force: boolean): void {
    const room = this.room;
    const player = this.player;
    const entry = this.entry;
    if (!room || !player || !entry || isLive(entry)) return;
    if (!force && !this.wantPlaying) return;
    const actual = player.currentMs();
    if (actual === null) return;
    const now = this.opts.serverNow();
    const expected = positionAt(room, now);
    if (shouldSeek({ expectedMs: expected, actualMs: actual, nowMs: now, lastSeekAtMs: this.lastSeekAt, force })) {
      player.seek(expected);
      this.lastSeekAt = now;
    }
  }

  private onPlayerEvent(event: PlayerEvent): void {
    switch (event.type) {
      case 'playing':
        this.playerPlaying = true;
        this.clearTap();
        this.opts.onNeedsTap(false);
        // Back from an ad or buffering: check right away instead of at the next tick. Not forced,
        // so a slow network cannot loop seek -> buffer -> playing -> seek faster than every 5 s.
        if (this.wantPlaying) this.correct(false);
        else this.player?.pause();
        return;
      case 'paused':
        this.playerPlaying = false;
        return;
      case 'unplayable':
        if (this.loadedEntryId) this.opts.onUnplayable(this.loadedEntryId);
        return;
    }
  }

  /** Stops output for "nothing playable now"; keeps the player for the next entry. */
  private halt(): void {
    this.clearStart();
    this.clearTap();
    this.wantPlaying = false;
    this.player?.pause();
  }

  private armTap(): void {
    if (this.playerPlaying || this.tapTimer !== null) return;
    this.tapTimer = setTimeout(() => {
      this.tapTimer = null;
      if (this.wantPlaying && !this.playerPlaying) this.opts.onNeedsTap(true);
    }, NEEDS_TAP_AFTER_MS);
  }

  private clearTap(): void {
    if (this.tapTimer !== null) clearTimeout(this.tapTimer);
    this.tapTimer = null;
  }

  private clearStart(): void {
    if (this.startTimer !== null) clearTimeout(this.startTimer);
    this.startTimer = null;
  }
}
