import { describe, expect, it } from 'vitest';
import { ClockSync } from './clock';

describe('ClockSync', () => {
  it('knows nothing before a sample and treats serverNow as local time', () => {
    const clock = new ClockSync();
    expect(clock.offset).toBeNull();
    expect(clock.rtt).toBeNull();
    expect(clock.serverNow(1_000)).toBe(1_000);
  });

  it('computes offset = serverTime - midpoint', () => {
    const clock = new ClockSync();
    clock.addSample(1_000, 1_100, 50_050); // midpoint 1_050
    expect(clock.offset).toBe(49_000);
    expect(clock.rtt).toBe(100);
    expect(clock.serverNow(2_000)).toBe(51_000);
  });

  it('uses the sample with the smallest rtt', () => {
    const clock = new ClockSync();
    clock.addSample(0, 300, 10_000); // rtt 300, offset 9_850
    clock.addSample(1_000, 1_040, 11_000); // rtt 40, offset 9_980
    clock.addSample(2_000, 2_200, 12_500); // rtt 200, offset 10_400
    expect(clock.offset).toBe(9_980);
    expect(clock.rtt).toBe(40);
  });

  it('forgets samples older than the last 8', () => {
    const clock = new ClockSync();
    clock.addSample(0, 10, 1_000); // best ever (rtt 10, offset 995)
    for (let i = 1; i <= 8; i++) clock.addSample(i * 1_000, i * 1_000 + 50, i * 1_000 + 2_025);
    expect(clock.rtt).toBe(50);
    expect(clock.offset).toBe(2_000);
  });

  it('ignores negative rtt samples', () => {
    const clock = new ClockSync();
    clock.addSample(100, 50, 9_999);
    expect(clock.offset).toBeNull();
  });

  it('uses the Welcome seed until the first Pong', () => {
    const clock = new ClockSync();
    clock.seed(10_000, 400);
    expect(clock.offset).toBe(9_600);
    clock.addSample(1_000, 1_020, 11_000);
    expect(clock.offset).toBe(9_990);
  });
});
