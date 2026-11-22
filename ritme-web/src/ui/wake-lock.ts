/** Asks the browser to keep the screen on while listening (re-acquired when the tab is visible again). */
export function keepScreenOn(): () => void {
  if (!('wakeLock' in navigator)) return () => {};
  let sentinel: WakeLockSentinel | null = null;
  let active = true;
  const acquire = () => {
    if (!active || document.visibilityState !== 'visible') return;
    navigator.wakeLock.request('screen').then(
      (s) => {
        if (active) sentinel = s;
        else void s.release();
      },
      () => {},
    );
  };
  document.addEventListener('visibilitychange', acquire);
  acquire();
  return () => {
    active = false;
    document.removeEventListener('visibilitychange', acquire);
    void sentinel?.release().catch(() => {});
  };
}
