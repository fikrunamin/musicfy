import type { ConnectionEvent, JoinIntent } from '../net/connection';
import type { Command, NewEntry, RoomSettings } from '../protocol';
import { ClockSync } from '../sync/clock';
import { type NoticeKey, type SessionState, initialSession, sessionReducer } from './reducer';

/** What Session needs from RitmeConnection (a fake in tests). */
export interface ConnectionLike {
  start(intent: JoinIntent): void;
  command(cmd: Command): string | null;
  pingBurst(): void;
  leave(): void;
}

/**
 * A wall clock that keeps running while the device sleeps, compared with performance.now()
 * (which does not) on wake: a gap above this means the clock samples are stale.
 */
export const SLEEP_DETECT_MS = 1_000;

export interface SessionDeps {
  connect(onEvent: (event: ConnectionEvent) => void): ConnectionLike;
  clock?: ClockSync;
  /** Same monotonic clock the connection uses (performance.now()). */
  now: () => number;
  /** Wall clock (Date.now()), only to notice that the device slept. */
  wallNow?: () => number;
}

/** What the UI uses; Session implements it and UI tests pass a fake. */
export interface SessionApi {
  getState(): SessionState;
  subscribe(listener: () => void): () => void;
  serverNow(): number;
  /**
   * Called when the player should re-sync with force: when the page becomes visible, but after a
   * sleep or a (re)connect only once the clock has a fresh sample. Returns an unsubscribe function.
   */
  onResync(listener: () => void): () => void;
  create(name: string): void;
  join(code: string, name: string): void;
  leave(): void;
  dismiss(): void;
  play(): void;
  pause(): void;
  seek(positionMs: number): void;
  next(): void;
  previous(): void;
  playEntry(entryId: string): void;
  add(entry: NewEntry, playNext: boolean): void;
  remove(entryId: string): void;
  move(entryId: string, afterEntryId: string | null): void;
  setGuestsCanControl(value: boolean): void;
  setGuestsCanAdd(value: boolean): void;
  kick(memberId: string): void;
  transferHost(memberId: string): void;
  endRoom(): void;
  markUnplayable(entryId: string): void;
  notify(key: NoticeKey): void;
}

/**
 * The one place that owns the connection, the clock and the session state. There is deliberately
 * no way to send TrackEnded from here: the web client never reports track ends (spec 2.6).
 */
export class Session implements SessionApi {
  private state: SessionState = initialSession;
  private readonly listeners = new Set<() => void>();
  private readonly resyncListeners = new Set<() => void>();
  private conn: ConnectionLike | null = null;
  /** The clock was reset; the next kept Pong sample triggers a resync. */
  private awaitingSample = false;
  private hiddenAt: { local: number; wall: number } | null = null;
  /** reqIds of recent Adds, so a FORBIDDEN answer to one gets its own message (spec U6). */
  private readonly addReqIds = new Set<string>();
  /** The settings last sent, with the room and version they were built on (see sendSettings). */
  private sentSettings: { code: string; afterVersion: number; settings: RoomSettings } | null = null;
  readonly clock: ClockSync;

  constructor(private readonly deps: SessionDeps) {
    this.clock = deps.clock ?? new ClockSync();
  }

  getState = (): SessionState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  serverNow = (): number => this.clock.serverNow(this.deps.now());

  onResync = (listener: () => void): (() => void) => {
    this.resyncListeners.add(listener);
    return () => this.resyncListeners.delete(listener);
  };

  /**
   * Page visibility (main.tsx wires document.visibilitychange). On becoming visible, a wall-clock
   * gap that performance.now() did not see means the device slept: the clock window is reset, a
   * ping burst sent, and the resync waits for the first fresh Pong. Otherwise it resyncs at once.
   */
  visibilityChanged(visible: boolean): void {
    const local = this.deps.now();
    const wall = (this.deps.wallNow ?? Date.now)();
    if (!visible) {
      this.hiddenAt = { local, wall };
      return;
    }
    const hidden = this.hiddenAt;
    this.hiddenAt = null;
    const missed = hidden ? wall - hidden.wall - (local - hidden.local) : 0;
    if (Math.abs(missed) > SLEEP_DETECT_MS) {
      this.resetClock(local, missed);
      this.conn?.pingBurst();
      return;
    }
    this.emitResync();
  }

  create(name: string): void {
    this.begin({ kind: 'create', name });
  }

  join(code: string, name: string): void {
    this.begin({ kind: 'join', code, name });
  }

  leave(): void {
    this.conn?.leave();
  }

  /** Back to the start screen after the session ended. */
  dismiss(): void {
    if (this.state.phase === 'ended') this.dispatch({ type: 'reset' });
  }

  play(): void {
    this.send({ type: 'Play' });
  }
  pause(): void {
    this.send({ type: 'Pause' });
  }
  seek(positionMs: number): void {
    this.send({ type: 'Seek', positionMs: Math.max(0, Math.round(positionMs)) });
  }
  next(): void {
    this.send({ type: 'Next' });
  }
  previous(): void {
    this.send({ type: 'Previous' });
  }
  playEntry(entryId: string): void {
    this.send({ type: 'PlayEntry', entryId });
  }
  add(entry: NewEntry, playNext: boolean): void {
    const reqId = this.send({ type: 'Add', entries: [entry], playNext });
    if (reqId === null) return;
    this.addReqIds.add(reqId);
    // Only answers to recent commands matter: keep the set small.
    if (this.addReqIds.size > 64) this.addReqIds.delete(this.addReqIds.values().next().value!);
  }
  remove(entryId: string): void {
    this.send({ type: 'Remove', entryId });
  }
  move(entryId: string, afterEntryId: string | null): void {
    this.send({ type: 'Move', entryId, afterEntryId });
  }
  setGuestsCanControl(value: boolean): void {
    this.sendSettings({ guestsCanControl: value });
  }
  setGuestsCanAdd(value: boolean): void {
    this.sendSettings({ guestsCanAdd: value });
  }
  kick(memberId: string): void {
    this.send({ type: 'Kick', memberId });
  }
  transferHost(memberId: string): void {
    this.send({ type: 'TransferHost', memberId });
  }
  endRoom(): void {
    this.send({ type: 'EndRoom' });
  }

  markUnplayable(entryId: string): void {
    this.dispatch({ type: 'mark-unplayable', entryId });
  }

  notify(key: NoticeKey): void {
    this.dispatch({ type: 'notice', key });
  }

  private begin(intent: JoinIntent): void {
    if (this.state.phase === 'connecting' || this.state.phase === 'in-room') return;
    this.dispatch({ type: 'start' });
    const conn = this.deps.connect((event) => {
      if (this.conn === conn) this.onEvent(event);
    });
    this.conn = conn;
    conn.start(intent);
  }

  private onEvent(event: ConnectionEvent): void {
    if (event.type === 'welcome') {
      // A new connection may follow a sleep: start a fresh window (the connection's ping burst
      // follows this event) and use the Welcome's server time until the first Pong.
      this.resetClock(event.receivedAt, 0);
      this.clock.seed(event.welcome.serverTime, event.receivedAt);
      // Command answers and the room version start over with a new connection.
      this.addReqIds.clear();
      this.sentSettings = null;
    }
    let fresh = false;
    if (event.type === 'pong') {
      const kept = this.clock.addSample(event.clientTime, event.receivedAt, event.serverTime);
      fresh = kept && this.awaitingSample;
      if (fresh) this.awaitingSample = false;
    }
    if (event.type === 'ended') this.conn = null;
    // "The host locked the controls" would be wrong for a refused Add (spec U6).
    if (event.type === 'error' && event.reqId !== null && this.addReqIds.delete(event.reqId) && event.code === 'FORBIDDEN') {
      this.dispatch({ type: 'notice', key: 'addForbidden' });
      return;
    }
    this.dispatch(event);
    if (fresh) this.emitResync();
  }

  private resetClock(localNow: number, shiftMs: number): void {
    this.clock.reset(localNow, shiftMs);
    this.awaitingSample = true;
  }

  private emitResync(): void {
    for (const listener of this.resyncListeners) listener();
  }

  /** The reqId the command went out with, or null when it was not sent (not online in a room). */
  private send(cmd: Command): string | null {
    if (this.state.phase !== 'in-room' || this.state.connection !== 'online') return null;
    return this.conn?.command(cmd) ?? null;
  }

  /**
   * SetSettings replaces every setting. The others come from the settings this client last sent
   * until a snapshot newer than the one those were built on arrives (two quick toggles must not
   * undo each other), and from the latest snapshot after that.
   */
  private sendSettings(change: Partial<RoomSettings>): void {
    const room = this.state.room;
    if (!room) return;
    const sent = this.sentSettings;
    const base = sent && sent.code === room.code && room.version <= sent.afterVersion ? sent.settings : room.settings;
    const settings = { ...base, ...change };
    if (this.send({ type: 'SetSettings', settings }) !== null) {
      this.sentSettings = { code: room.code, afterVersion: room.version, settings };
    }
  }

  private dispatch(action: Parameters<typeof sessionReducer>[1]): void {
    const next = sessionReducer(this.state, action);
    if (next === this.state) return;
    this.state = next;
    for (const listener of this.listeners) listener();
  }
}
