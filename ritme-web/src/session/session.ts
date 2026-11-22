import type { ConnectionEvent, JoinIntent } from '../net/connection';
import type { Command, NewEntry } from '../protocol';
import { ClockSync } from '../sync/clock';
import { type NoticeKey, type SessionState, initialSession, sessionReducer } from './reducer';

/** What Session needs from RitmeConnection (a fake in tests). */
export interface ConnectionLike {
  start(intent: JoinIntent): void;
  command(cmd: Command): string | null;
  leave(): void;
}

export interface SessionDeps {
  connect(onEvent: (event: ConnectionEvent) => void): ConnectionLike;
  clock?: ClockSync;
  /** Same monotonic clock the connection uses (performance.now()). */
  now: () => number;
}

/** What the UI uses; Session implements it and UI tests pass a fake. */
export interface SessionApi {
  getState(): SessionState;
  subscribe(listener: () => void): () => void;
  serverNow(): number;
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
  private conn: ConnectionLike | null = null;
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
    this.send({ type: 'Add', entries: [entry], playNext });
  }
  remove(entryId: string): void {
    this.send({ type: 'Remove', entryId });
  }
  move(entryId: string, afterEntryId: string | null): void {
    this.send({ type: 'Move', entryId, afterEntryId });
  }
  setGuestsCanControl(value: boolean): void {
    this.send({ type: 'SetSettings', settings: { guestsCanControl: value } });
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
    if (event.type === 'pong') this.clock.addSample(event.clientTime, event.receivedAt, event.serverTime);
    if (event.type === 'welcome') this.clock.seed(event.welcome.serverTime, event.receivedAt);
    if (event.type === 'ended') this.conn = null;
    this.dispatch(event);
  }

  private send(cmd: Command): void {
    if (this.state.phase !== 'in-room' || this.state.connection !== 'online') return;
    this.conn?.command(cmd);
  }

  private dispatch(action: Parameters<typeof sessionReducer>[1]): void {
    const next = sessionReducer(this.state, action);
    if (next === this.state) return;
    this.state = next;
    for (const listener of this.listeners) listener();
  }
}
