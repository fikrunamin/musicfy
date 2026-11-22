import type { SocketLike } from '../net/connection';

/** In-memory WebSocket for tests: records what the client sends, lets the test drive events. */
export class FakeSocket implements SocketLike {
  readyState = 0;
  sent: Record<string, unknown>[] = [];
  closed = false;
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: unknown) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;

  constructor(readonly url: string) {}

  send(data: string): void {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }

  close(): void {
    this.closed = true;
    this.readyState = 3;
  }

  // Test controls
  open(): void {
    this.readyState = 1;
    this.onopen?.({});
  }

  receive(message: object | string): void {
    this.onmessage?.({ data: typeof message === 'string' ? message : JSON.stringify(message) });
  }

  serverClose(): void {
    this.readyState = 3;
    this.onclose?.({});
  }

  types(): unknown[] {
    return this.sent.map((m) => m.type);
  }
}
