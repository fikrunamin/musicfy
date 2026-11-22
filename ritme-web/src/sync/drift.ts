/**
 * Web drift policy (spec 2.5): seek only, when the player is more than SEEK_THRESHOLD_MS away
 * from the room, and at most once every MIN_SEEK_INTERVAL_MS. `force` skips the interval (the
 * room's playback changed, the start anchor was reached, the tab is visible again) but never the
 * threshold.
 */
export const SEEK_THRESHOLD_MS = 300;
export const MIN_SEEK_INTERVAL_MS = 5_000;

export interface DriftInput {
  expectedMs: number;
  actualMs: number;
  nowMs: number;
  lastSeekAtMs: number | null;
  force?: boolean;
}

export function shouldSeek({ expectedMs, actualMs, nowMs, lastSeekAtMs, force = false }: DriftInput): boolean {
  if (Math.abs(expectedMs - actualMs) <= SEEK_THRESHOLD_MS) return false;
  if (force || lastSeekAtMs === null) return true;
  return nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS;
}
