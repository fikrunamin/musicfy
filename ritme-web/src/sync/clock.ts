/**
 * Server clock estimate (spec 2.4). Each Ping/Pong gives rtt = t1 - t0 and
 * offset = serverTime - (t0 + t1) / 2; the offset in use comes from the sample with the smallest
 * rtt among the last WINDOW samples. Local times come from a monotonic clock (performance.now()).
 */
export const CLOCK_WINDOW = 8;

interface Sample {
  rtt: number;
  offset: number;
}

export class ClockSync {
  private samples: Sample[] = [];
  private seeded: number | null = null;
  /** Pings sent before this local time belong to the previous clock epoch (see reset). */
  private since = Number.NEGATIVE_INFINITY;

  constructor(private readonly window: number = CLOCK_WINDOW) {}

  /**
   * t0 = local time the Ping was sent (echoed back as clientTime), t1 = local time the Pong arrived.
   * Returns whether the sample was kept (false for a negative rtt or a Ping sent before reset()).
   */
  addSample(t0: number, t1: number, serverTime: number): boolean {
    const rtt = t1 - t0;
    if (rtt < 0 || t0 < this.since) return false;
    this.samples.push({ rtt, offset: serverTime - (t0 + t1) / 2 });
    if (this.samples.length > this.window) this.samples.shift();
    return true;
  }

  /**
   * Starts a new sample window, for when the old samples may be wrong: a new connection, or the
   * device slept (performance.now() does not advance during OS suspend, so pre-sleep samples
   * would keep their low rtt and win forever). Until a new sample arrives the offset in use
   * stays available as the fallback, moved by `shiftMs` (the time the local clock missed), so
   * serverNow keeps working. Pongs for Pings sent before `localNow` are ignored.
   */
  reset(localNow: number, shiftMs = 0): void {
    const current = this.offset;
    this.samples = [];
    this.seeded = current === null ? null : current + shiftMs;
    this.since = Math.floor(localNow);
  }

  /** Rough offset from Welcome.serverTime, used only until the first Pong (after a reset) arrives. */
  seed(serverTime: number, receivedAt: number): void {
    this.seeded = serverTime - receivedAt;
  }

  /** Offset of the best sample, the seed, or null when nothing is known yet. */
  get offset(): number | null {
    if (this.samples.length === 0) return this.seeded;
    let best = this.samples[0];
    for (const s of this.samples) if (s.rtt < best.rtt) best = s;
    return best.offset;
  }

  /** Smallest rtt in the window, or null. */
  get rtt(): number | null {
    if (this.samples.length === 0) return null;
    return Math.min(...this.samples.map((s) => s.rtt));
  }

  serverNow(localNow: number): number {
    return localNow + (this.offset ?? 0);
  }
}
