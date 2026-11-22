import {
  type ClientMessage,
  type Command,
  type ErrorCode,
  type RoomState,
  type ServerMessage,
  type Welcome,
  PROTOCOL_VERSION,
  encodeClientMessage,
  parseServerMessage,
} from '../protocol';
import { type KeyValueStore, type ResumeInfo, RESUME_KEY, readResume, writeResume } from './storage';

/** The subset of the browser WebSocket this client uses, so tests can pass a fake. */
export interface SocketLike {
  readonly readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: unknown) => void) | null;
  onerror: ((ev: unknown) => void) | null;
}

export type SocketFactory = (url: string) => SocketLike;

export type ConnectionStatus = 'connecting' | 'online' | 'reconnecting';

export type EndReason =
  | { kind: 'left' }
  | { kind: 'kicked' }
  | { kind: 'closed' }
  | { kind: 'lost' }
  | { kind: 'unreachable' }
  | { kind: 'rejected'; code: ErrorCode };

export type ConnectionEvent =
  | { type: 'status'; status: ConnectionStatus }
  | { type: 'welcome'; welcome: Welcome; receivedAt: number }
  | { type: 'state'; state: RoomState }
  | { type: 'pong'; clientTime: number; serverTime: number; receivedAt: number }
  | { type: 'error'; reqId: string | null; code: ErrorCode }
  | { type: 'ended'; reason: EndReason };

export type JoinIntent = { kind: 'create'; name: string } | { kind: 'join'; code: string; name: string };

export interface ConnectionOptions {
  url: string;
  socketFactory: SocketFactory;
  /** sessionStorage in the browser: holds the resume token. */
  storage: KeyValueStore;
  /** Monotonic local clock in ms (performance.now()). */
  now: () => number;
  onEvent: (event: ConnectionEvent) => void;
}

/** Reconnect delays (spec 4.1): 1 → 2 → 4 → 8 → 15 s, giving up 30 s after the drop. */
export const BACKOFF_MS = [1_000, 2_000, 4_000, 8_000, 15_000];
export const RECONNECT_GIVE_UP_MS = 30_000;
/** A socket that has not opened by then counts as a failed attempt. */
export const CONNECT_TIMEOUT_MS = 8_000;
/** An opened socket that has not been welcomed by then counts as a failed attempt (server join deadline). */
export const JOIN_TIMEOUT_MS = 10_000;
export const PING_BURST = 5;
export const PING_BURST_GAP_MS = 200;
export const PING_INTERVAL_MS = 30_000;

const WS_OPEN = 1;

/**
 * One Ritme session over WebSocket: Create/Join on every (re)connect with the resume token,
 * clock pings, exponential reconnect, and a single terminal `ended` event. Unknown or malformed
 * frames are skipped. Browser WebSockets have no inbound frame limit, so multi-MB snapshots are fine.
 */
export class RitmeConnection {
  private socket: SocketLike | null = null;
  private intent: JoinIntent | null = null;
  private welcomed = false;
  private joined = false;
  private ended = false;
  private attempt = 0;
  private droppedAt: number | null = null;
  /** Latest token in memory; sessionStorage is only the reload fallback (it can be blocked). */
  private resume: ResumeInfo | null = null;
  private nextReqId = 1;
  private readonly socketTimers = new Set<ReturnType<typeof setTimeout>>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly opts: ConnectionOptions) {}

  start(intent: JoinIntent): void {
    if (this.intent) throw new Error('RitmeConnection.start called twice');
    this.intent = intent;
    this.emit({ type: 'status', status: 'connecting' });
    this.open();
  }

  /** Sends a command and returns its reqId; dropped (returns null) while not joined. */
  command(cmd: Command): string | null {
    if (!this.joined) return null;
    const reqId = `r${this.nextReqId++}`;
    this.send({ type: 'Command', reqId, cmd });
    return reqId;
  }

  leave(): void {
    if (this.ended) return;
    // Leaving before the first Welcome just closes the socket; the server has no member to remove yet.
    if (this.joined) this.send({ type: 'Leave' });
    this.finish({ kind: 'left' });
  }

  private open(): void {
    const socket = this.opts.socketFactory(this.opts.url);
    this.socket = socket;
    this.joined = false;
    let opened = false;
    this.later(CONNECT_TIMEOUT_MS, () => {
      if (!opened) this.drop(socket);
    });
    socket.onopen = () => {
      if (this.socket !== socket) return;
      opened = true;
      this.send(this.joinMessage());
      this.later(JOIN_TIMEOUT_MS, () => {
        if (!this.joined) this.drop(socket);
      });
      for (let i = 0; i < PING_BURST; i++) this.later(i * PING_BURST_GAP_MS, () => this.ping());
      this.later(PING_INTERVAL_MS, () => this.pingLoop());
    };
    socket.onmessage = (ev) => {
      if (this.socket !== socket || typeof ev.data !== 'string') return;
      const message = parseServerMessage(ev.data);
      if (message) this.handle(message);
    };
    socket.onclose = () => this.drop(socket);
    socket.onerror = () => {
      // A close event always follows.
    };
  }

  private joinMessage(): ClientMessage {
    const intent = this.intent!;
    if (intent.kind === 'create') {
      return {
        type: 'Create',
        protocolVersion: PROTOCOL_VERSION,
        name: intent.name,
        initialQueue: [],
        startIndex: 0,
        startPositionMs: 0,
      };
    }
    // A reload resume that hits RATE_LIMITED before any Welcome ends as rejected (not retried): intentional.
    const resume = this.resume ?? readResume(this.opts.storage);
    return {
      type: 'Join',
      protocolVersion: PROTOCOL_VERSION,
      code: intent.code,
      name: intent.name,
      resumeToken: resume && resume.code === intent.code ? resume.token : null,
    };
  }

  private handle(message: ServerMessage): void {
    switch (message.type) {
      case 'Welcome': {
        this.joined = true;
        this.welcomed = true;
        this.attempt = 0;
        this.droppedAt = null;
        // Reconnects always Join the room this Welcome is for, with its token.
        this.intent = { kind: 'join', code: message.state.code, name: this.intent!.name };
        this.resume = { code: message.state.code, token: message.resumeToken };
        writeResume(this.opts.storage, { code: message.state.code, token: message.resumeToken });
        this.emit({ type: 'welcome', welcome: message, receivedAt: this.opts.now() });
        this.emit({ type: 'status', status: 'online' });
        return;
      }
      case 'State':
        if (this.joined) this.emit({ type: 'state', state: message.state });
        return;
      case 'Pong':
        this.emit({
          type: 'pong',
          clientTime: message.clientTime,
          serverTime: message.serverTime,
          receivedAt: this.opts.now(),
        });
        return;
      case 'Error':
        if (this.joined) {
          this.emit({ type: 'error', reqId: message.reqId, code: message.code });
        } else if (this.welcomed && message.code === 'RATE_LIMITED') {
          // A reconnect hit a connection cap: the server closes, and the backoff tries again.
        } else {
          this.finish(this.welcomed ? { kind: 'lost' } : { kind: 'rejected', code: message.code });
        }
        return;
      case 'Kicked':
        this.finish({ kind: 'kicked' });
        return;
      case 'RoomClosed':
        this.finish({ kind: 'closed' });
        return;
    }
  }

  private drop(socket: SocketLike): void {
    if (this.socket !== socket) return;
    this.detach(socket);
    if (this.ended) return;
    if (!this.welcomed) {
      this.finish({ kind: 'unreachable' });
      return;
    }
    if (this.droppedAt === null) {
      this.droppedAt = this.opts.now();
      this.emit({ type: 'status', status: 'reconnecting' });
    }
    // Give up 30 s after the first drop: delays are clamped so a final attempt lands at the 30 s mark.
    const remaining = this.droppedAt + RECONNECT_GIVE_UP_MS - this.opts.now();
    if (remaining <= 0) {
      this.finish({ kind: 'lost' });
      return;
    }
    const delay = Math.min(BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)], remaining);
    this.attempt++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.ended) this.open();
    }, delay);
  }

  private finish(reason: EndReason): void {
    if (this.ended) return;
    this.ended = true;
    if (reason.kind !== 'unreachable') {
      this.resume = null;
      this.opts.storage.remove(RESUME_KEY);
    }
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    if (this.socket) this.detach(this.socket);
    this.emit({ type: 'ended', reason });
  }

  /** Forgets a socket: no more callbacks, its timers cleared, closed if still open. */
  private detach(socket: SocketLike): void {
    socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
    for (const t of this.socketTimers) clearTimeout(t);
    this.socketTimers.clear();
    if (this.socket === socket) this.socket = null;
    this.joined = false;
    try {
      socket.close(1000, 'bye');
    } catch {
      // Already closed.
    }
  }

  private ping(): void {
    this.send({ type: 'Ping', clientTime: Math.floor(this.opts.now()) });
  }

  private pingLoop(): void {
    this.ping();
    this.later(PING_INTERVAL_MS, () => this.pingLoop());
  }

  private later(ms: number, fn: () => void): void {
    const timer = setTimeout(() => {
      this.socketTimers.delete(timer);
      fn();
    }, ms);
    this.socketTimers.add(timer);
  }

  private send(message: ClientMessage): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== WS_OPEN) return;
    socket.send(encodeClientMessage(message));
  }

  private emit(event: ConnectionEvent): void {
    this.opts.onEvent(event);
  }
}

/** ws(s)://<this origin>/ritme */
export function ritmeSocketUrl(location: { protocol: string; host: string }): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ritme`;
}
