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

describe('ClockSync.reset', () => {
  it('drops every sample but keeps serving the last offset until a new sample arrives', () => {
    const clock = new ClockSync();
    clock.seed(10_000, 400); // 9_600
    clock.addSample(1_000, 1_010, 11_000); // rtt 10, offset 9_995
    clock.reset(5_000);
    expect(clock.rtt).toBeNull();
    expect(clock.offset).toBe(9_995);
    expect(clock.serverNow(6_000)).toBe(15_995);
    expect(clock.addSample(5_000, 5_100, 70_050)).toBe(true); // rtt 100, offset 65_000
    expect(clock.offset).toBe(65_000);
    expect(clock.rtt).toBe(100);
  });

  it('shifts the fallback by the given amount (time the local clock missed while asleep)', () => {
    const clock = new ClockSync();
    clock.addSample(0, 10, 1_005); // offset 1_000
    clock.reset(20, 60_000);
    expect(clock.offset).toBe(61_000);
  });

  it('falls back to the seed when there were no samples', () => {
    const clock = new ClockSync();
    expect(clock.offset).toBeNull();
    clock.reset(0);
    expect(clock.offset).toBeNull();
    clock.seed(10_000, 400);
    clock.reset(500);
    expect(clock.offset).toBe(9_600);
  });

  it('ignores Pongs for Pings sent before the reset', () => {
    const clock = new ClockSync();
    clock.reset(1_000);
    expect(clock.addSample(999, 1_005, 50_000)).toBe(false);
    expect(clock.rtt).toBeNull();
    expect(clock.addSample(1_000, 1_005, 50_000)).toBe(true);
  });

  it('a seed after a reset replaces the fallback', () => {
    const clock = new ClockSync();
    clock.addSample(0, 10, 1_005);
    clock.reset(20);
    clock.seed(90_000, 100);
    expect(clock.offset).toBe(89_900);
  });
});
