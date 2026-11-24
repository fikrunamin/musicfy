import { Fragment, type RefObject, type VNode } from 'preact';
import { useEffect, useLayoutEffect, useReducer, useRef, useState } from 'preact/hooks';
import { useMediaQuery } from './hooks';

/**
 * Motion (handoff "Gerak", DECISIONS 3). The animations themselves are CSS (motion.css, all behind
 * `prefers-reduced-motion: no-preference`); this file only measures and keeps layers around.
 */

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** True when the user asked for less motion: nothing moves, changes are immediate. A pure UI hook. */
export function usePrefersReducedMotion(): boolean {
  return useMediaQuery(REDUCED_MOTION_QUERY);
}

// ---------------------------------------------------------------------------------------------
// Marquee: the one-line meta of a row scrolls to its end, pauses and comes back.

/** About 30 px per second… */
export const MARQUEE_PX_PER_S = 30;
/** …but one pass never takes less than 4 s. */
export const MARQUEE_MIN_MS = 4_000;

export type MarqueeTrigger = 'auto' | 'hover' | 'off';

/** How far one pass scrolls (the hidden part, whole pixels) and how long it takes (ms). */
export function marqueeParams(overflow: number): { distance: number; duration: number } {
  const distance = Math.ceil(overflow);
  return { distance, duration: Math.max(MARQUEE_MIN_MS, Math.round((distance * 1000) / MARQUEE_PX_PER_S)) };
}

/**
 * When the marquee runs (DECISIONS 3): by itself only on the "Sedang diputar" row, on hover or
 * focus on any other row, never with reduced motion, and never when the text fits.
 */
export function marqueeTrigger({ current, overflow, reducedMotion }: { current: boolean; overflow: number; reducedMotion: boolean }): MarqueeTrigger {
  if (reducedMotion || overflow <= 1) return 'off';
  return current ? 'auto' : 'hover';
}

/** How many px of the meta are cut off: the text after any badge, or the track as a whole. */
function overflowOf(clip: HTMLElement): number {
  const track = clip.firstElementChild as HTMLElement | null;
  if (!track) return 0;
  const text = track.lastElementChild as HTMLElement | null;
  return Math.max(0, track.scrollWidth - track.clientWidth, text ? text.scrollWidth - text.clientWidth : 0);
}

/**
 * Measures the Meta Clip after every render, on resize and once the fonts loaded, and returns what
 * the CSS needs: `data-marquee` and, while it may run, `--marquee-distance` / `--marquee-duration`.
 */
export function useMarquee(clip: RefObject<HTMLElement>, current: boolean) {
  const reducedMotion = usePrefersReducedMotion();
  const [overflow, setOverflow] = useState(0);
  const measure = () => {
    if (clip.current) setOverflow(overflowOf(clip.current));
  };
  // Every render: the text may have changed. setState bails out when the number is the same.
  useLayoutEffect(measure);
  useEffect(() => {
    // Inter arriving after the first paint changes the text width without resizing the clip.
    document.fonts?.ready.then(measure);
    if (typeof ResizeObserver === 'undefined' || !clip.current) return;
    const observer = new ResizeObserver(() => measure());
    observer.observe(clip.current);
    return () => observer.disconnect();
  }, []);
  const trigger = marqueeTrigger({ current, overflow, reducedMotion });
  if (trigger === 'off') return { 'data-marquee': trigger };
  const { distance, duration } = marqueeParams(overflow);
  return { 'data-marquee': trigger, style: { '--marquee-distance': `${distance}px`, '--marquee-duration': `${duration}ms` } };
}

// ---------------------------------------------------------------------------------------------
// Crossfade: the new image fades in over the old one, which stays underneath for 600 ms.

export const CROSSFADE_MS = 600;

interface Layer<T> {
  /** Unique per layer (the same picture can come back while its old layer still fades out). */
  id: number;
  key: string;
  value: T;
  entering: boolean;
}

interface CrossfadeProps<T> {
  value: T;
  /** Layers with the same key are the same picture: it re-renders in place, without a fade. */
  keyOf: (value: T) => string;
  /** One layer, bottom to top. `entering` is true while it fades in (give it the `xfade-in` class). */
  children: (value: T, entering: boolean) => VNode | null;
}

/**
 * Renders the current value as a layer; when the key changes, the new layer goes on top with
 * `entering` and the ones under it stay. A layer whose 600 ms are over is opaque, so it stops
 * entering and every layer under it goes. A fade that a new value interrupts therefore keeps
 * running under the new one: nothing jumps or vanishes mid-fade. No wrapper element: the layers
 * are the caller's own (absolutely placed) nodes. With reduced motion the new layer simply
 * replaces the old ones.
 */
export function Crossfade<T>({ value, keyOf, children }: CrossfadeProps<T>) {
  const reducedMotion = usePrefersReducedMotion();
  const key = keyOf(value);
  const state = useRef<{ layers: Layer<T>[]; next: number } | null>(null);
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());
  const [, settle] = useReducer((n: number) => n + 1, 0);
  if (state.current === null) state.current = { layers: [{ id: 0, key, value, entering: false }], next: 1 };
  const s = state.current;
  let top = s.layers[s.layers.length - 1];
  if (top.key !== key) {
    top = { id: s.next++, key, value, entering: !reducedMotion };
    s.layers = reducedMotion ? [top] : [...s.layers, top];
  } else {
    top.value = value;
    if (reducedMotion && s.layers.length > 1) s.layers = [{ ...top, entering: false }];
  }

  const enteringId = top.entering ? top.id : -1;
  useEffect(() => {
    if (enteringId < 0) return;
    const timer = setTimeout(() => {
      timers.current.delete(timer);
      const layers = state.current!.layers;
      const at = layers.findIndex((layer) => layer.id === enteringId);
      if (at < 0) return;
      state.current!.layers = [{ ...layers[at], entering: false }, ...layers.slice(at + 1)];
      settle(0);
    }, CROSSFADE_MS);
    timers.current.add(timer);
  }, [enteringId]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  return <>{s.layers.map((layer) => <Fragment key={layer.id}>{children(layer.value, layer.entering)}</Fragment>)}</>;
}
