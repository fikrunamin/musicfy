import type { RefObject } from 'preact';
import { useEffect, useState } from 'preact/hooks';

/** The browser can fullscreen an element (not iPhone Safari, which has no element fullscreen). */
export const canFullscreen = (): boolean => typeof document !== 'undefined' && document.fullscreenEnabled === true;

/**
 * Fullscreen for an element that already exists (handoff J/N: the now-playing section, so the
 * player inside keeps its place in the tree). `active` follows the browser, so leaving with Esc
 * or the system gesture is seen too. A pure UI hook: it only changes what is shown.
 */
export function useFullscreen(target: RefObject<HTMLElement>): { active: boolean; toggle: () => void } {
  const [active, setActive] = useState(false);
  useEffect(() => {
    const sync = () => setActive(!!target.current && document.fullscreenElement === target.current);
    document.addEventListener('fullscreenchange', sync);
    return () => document.removeEventListener('fullscreenchange', sync);
  }, [target]);
  const toggle = () => {
    // A refusal (no gesture, a permissions policy) leaves the page as it was.
    Promise.resolve(active ? document.exitFullscreen() : target.current?.requestFullscreen()).catch(() => {});
  };
  return { active, toggle };
}
