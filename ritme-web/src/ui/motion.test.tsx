// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { stubMatchMedia } from '../testing/media-query';
import { Backdrop } from './backdrop';
import { MetaClip } from './components';
import { MediaRow } from './media';
import {
  CROSSFADE_MS,
  Crossfade,
  MARQUEE_MIN_MS,
  MARQUEE_PX_PER_S,
  REDUCED_MOTION_QUERY,
  marqueeParams,
  marqueeTrigger,
  usePrefersReducedMotion,
} from './motion';
import { NowPlaying } from './now-playing';
import { QueueList } from './queue';

/**
 * jsdom has no layout: every clip pretends to be 180 px wide and every meta text as wide as
 * `textWidth` says (by its text), so a row overflows by `textWidth - 180` px.
 */
const CLIP = 180;
let textWidth: (text: string) => number = () => 100;
const own = ['scrollWidth', 'clientWidth'] as const;

beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.matches('.meta-clip, .meta-track, .meta-track > *') ? CLIP : 0;
    },
  });
  Object.defineProperty(HTMLElement.prototype, 'scrollWidth', {
    configurable: true,
    get(this: HTMLElement) {
      if (this.matches('.meta-track > *')) return Math.max(CLIP, textWidth(this.textContent ?? ''));
      return this.matches('.meta-clip, .meta-track') ? CLIP : 0;
    },
  });
});

afterEach(() => {
  for (const key of own) delete (HTMLElement.prototype as unknown as Record<string, unknown>)[key];
  textWidth = () => 100;
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const clip = (root: ParentNode = document) => root.querySelector<HTMLElement>('.meta-clip')!;

describe('marqueeParams', () => {
  it('scrolls exactly the hidden part at about 30 px/s', () => {
    expect(MARQUEE_PX_PER_S).toBe(30);
    expect(marqueeParams(300)).toEqual({ distance: 300, duration: 10_000 });
    expect(marqueeParams(150.2)).toEqual({ distance: 151, duration: 5_033 });
  });

  it('never runs faster than one pass in 4 s', () => {
    expect(MARQUEE_MIN_MS).toBe(4_000);
    expect(marqueeParams(12)).toEqual({ distance: 12, duration: 4_000 });
    expect(marqueeParams(120)).toEqual({ distance: 120, duration: 4_000 });
  });
});

describe('marqueeTrigger', () => {
  it('runs by itself only on the "Sedang diputar" row, on hover or focus elsewhere', () => {
    expect(marqueeTrigger({ current: true, overflow: 40, reducedMotion: false })).toBe('auto');
    expect(marqueeTrigger({ current: false, overflow: 40, reducedMotion: false })).toBe('hover');
  });

  it('is off with prefers-reduced-motion, whatever the row', () => {
    expect(marqueeTrigger({ current: true, overflow: 40, reducedMotion: true })).toBe('off');
    expect(marqueeTrigger({ current: false, overflow: 40, reducedMotion: true })).toBe('off');
  });

  it('is off when the meta fits (sub-pixel rounding included)', () => {
    expect(marqueeTrigger({ current: true, overflow: 0, reducedMotion: false })).toBe('off');
    expect(marqueeTrigger({ current: true, overflow: 1, reducedMotion: false })).toBe('off');
    expect(marqueeTrigger({ current: false, overflow: 0, reducedMotion: false })).toBe('off');
  });
});

describe('MetaClip', () => {
  it('measures the overflow and hands the distance and duration to the CSS', () => {
    textWidth = () => 480; // 300 px hidden
    render(<MetaClip marquee="auto">Artis yang sangat panjang sekali · oleh Budi</MetaClip>);
    expect(clip().dataset.marquee).toBe('auto');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('300px');
    expect(clip().style.getPropertyValue('--marquee-duration')).toBe('10000ms');
  });

  it('measures the text after a badge, not the badge', () => {
    textWidth = (text) => (text === 'Video' ? 40 : 300);
    render(
      <MetaClip marquee="hover">
        <span class="badge">Video</span>
        <span>YouTube · oleh Budi dengan judul panjang</span>
      </MetaClip>,
    );
    expect(clip().dataset.marquee).toBe('hover');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('120px');
    expect(clip().style.getPropertyValue('--marquee-duration')).toBe('4000ms');
  });

  it('stays cut with an ellipsis (off, no variables) when the text fits', () => {
    render(<MetaClip marquee="auto">Artis · oleh Budi</MetaClip>);
    expect(clip().dataset.marquee).toBe('off');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('');
  });

  it('measures again when the text changes', () => {
    textWidth = (text) => text.length * 10;
    const view = render(<MetaClip marquee="auto">{'x'.repeat(10)}</MetaClip>);
    expect(clip().dataset.marquee).toBe('off');
    view.rerender(<MetaClip marquee="auto">{'x'.repeat(30)}</MetaClip>);
    expect(clip().dataset.marquee).toBe('auto');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('120px');
  });

  it('measures again when the clip is resized', () => {
    let resized: () => void = () => {};
    const observed: Element[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: () => void) {
          resized = cb;
        }
        observe(el: Element) {
          observed.push(el);
        }
        disconnect() {}
      },
    );
    render(<MetaClip marquee="auto">Artis · oleh Budi</MetaClip>);
    expect(observed).toContain(clip());
    expect(clip().dataset.marquee).toBe('off');
    textWidth = () => 400;
    act(() => resized());
    expect(clip().dataset.marquee).toBe('auto');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('220px');
  });

  it('measures again once the web font has loaded, which changes the text width', async () => {
    let loaded: () => void = () => {};
    const ready = new Promise<void>((resolve) => {
      loaded = resolve;
    });
    Object.defineProperty(document, 'fonts', { configurable: true, value: { ready } });
    try {
      render(<MetaClip marquee="auto">Artis · oleh Budi</MetaClip>);
      expect(clip().dataset.marquee).toBe('off');
      textWidth = () => 400; // Inter is wider than the fallback it replaced
      await act(async () => {
        loaded();
        await ready;
      });
      expect(clip().dataset.marquee).toBe('auto');
      expect(clip().style.getPropertyValue('--marquee-distance')).toBe('220px');
    } finally {
      delete (document as unknown as Record<string, unknown>).fonts;
    }
  });

  it('never runs with prefers-reduced-motion, and stops when the setting turns on', () => {
    textWidth = () => 480;
    const media = stubMatchMedia({ [REDUCED_MOTION_QUERY]: true });
    render(<MetaClip marquee="auto">Artis yang sangat panjang sekali · oleh Budi</MetaClip>);
    expect(clip().dataset.marquee).toBe('off');
    expect(clip().style.getPropertyValue('--marquee-distance')).toBe('');
    act(() => media.set(REDUCED_MOTION_QUERY, false));
    expect(clip().dataset.marquee).toBe('auto');
    act(() => media.set(REDUCED_MOTION_QUERY, true));
    expect(clip().dataset.marquee).toBe('off');
  });
});

describe('the marquee in rows', () => {
  it('runs by itself on the current row of the full queue, on hover or focus on the others', () => {
    textWidth = () => 400;
    const r = room({ queue: [entry('a'), entry('b'), entry('c')], currentEntryId: 'a' });
    render(<QueueList session={fakeSession()} room={r} memberId="m1" unplayable={{}} online pageIsHttps variant="full" />);
    const rows = [...document.querySelectorAll('.row')];
    expect(rows.map((row) => row.querySelector<HTMLElement>('.meta-clip')!.dataset.marquee)).toEqual(['auto', 'hover', 'hover']);
    expect(rows[0].classList.contains('row-current')).toBe(true);
  });

  it('is a hover marquee in a row that is not current (search results, upcoming)', () => {
    textWidth = () => 400;
    render(
      <ul>
        <MediaRow thumb={null} title="Lagu" meta="Artis yang panjang · oleh Budi" />
      </ul>,
    );
    expect(clip().dataset.marquee).toBe('hover');
  });
});

describe('usePrefersReducedMotion', () => {
  function Probe() {
    return <p data-testid="rm">{usePrefersReducedMotion() ? 'reduce' : 'motion'}</p>;
  }
  const probe = () => screen.getByTestId('rm').textContent;

  it('follows (prefers-reduced-motion: reduce)', () => {
    expect(REDUCED_MOTION_QUERY).toBe('(prefers-reduced-motion: reduce)');
    const media = stubMatchMedia({ [REDUCED_MOTION_QUERY]: true });
    render(<Probe />);
    expect(probe()).toBe('reduce');
    act(() => media.set(REDUCED_MOTION_QUERY, false));
    expect(probe()).toBe('motion');
  });

  it('allows motion where the browser has no matchMedia', () => {
    render(<Probe />);
    expect(probe()).toBe('motion');
  });
});

describe('Crossfade', () => {
  const Img = ({ src }: { src: string }) => (
    <Crossfade value={src} keyOf={(s) => s}>
      {(s, entering) => <img class={entering ? 'layer xfade-in' : 'layer'} data-src={s} alt="" />}
    </Crossfade>
  );
  const layers = () => [...document.querySelectorAll<HTMLElement>('.layer')].map((el) => `${el.dataset.src}${el.classList.contains('xfade-in') ? '+in' : ''}`);

  it('fades in 600 ms', () => {
    expect(CROSSFADE_MS).toBe(600);
  });

  it('shows the first image at once, without a fade', () => {
    render(<Img src="a" />);
    expect(layers()).toEqual(['a']);
  });

  it('keeps the old image under the new one for 600 ms, then drops it', () => {
    vi.useFakeTimers();
    const view = render(<Img src="a" />);
    view.rerender(<Img src="b" />);
    expect(layers()).toEqual(['a', 'b+in']);
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS - 1));
    expect(layers()).toEqual(['a', 'b+in']);
    act(() => void vi.advanceTimersByTime(1));
    expect(layers()).toEqual(['b']);
  });

  it('lets a fade that is cut short finish under the next one: no layer jumps or vanishes mid-fade', () => {
    vi.useFakeTimers();
    const view = render(<Img src="a" />);
    view.rerender(<Img src="b" />);
    act(() => void vi.advanceTimersByTime(300));
    const b = document.querySelector('[data-src="b"]');
    view.rerender(<Img src="c" />);
    // b keeps fading in (same element, still xfade-in) over a; c fades in over both.
    expect(layers()).toEqual(['a', 'b+in', 'c+in']);
    expect(document.querySelector('[data-src="b"]')).toBe(b);
    // b's own 600 ms are over: it is opaque now, so a under it goes.
    act(() => void vi.advanceTimersByTime(300));
    expect(layers()).toEqual(['b', 'c+in']);
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS - 301));
    expect(layers()).toEqual(['b', 'c+in']);
    act(() => void vi.advanceTimersByTime(1));
    expect(layers()).toEqual(['c']);
  });

  it('stays bounded when images keep changing faster than the fade', () => {
    vi.useFakeTimers();
    const view = render(<Img src="i0" />);
    for (let i = 1; i <= 10; i++) {
      view.rerender(<Img src={`i${i}`} />);
      act(() => void vi.advanceTimersByTime(250));
      expect(layers().length).toBeLessThanOrEqual(1 + Math.ceil(CROSSFADE_MS / 250));
    }
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS));
    expect(layers()).toEqual(['i10']);
  });

  it('leaves no timer behind when it unmounts mid-fade', () => {
    vi.useFakeTimers();
    const view = render(<Img src="a" />);
    view.rerender(<Img src="b" />);
    act(() => void vi.advanceTimersByTime(300));
    view.rerender(<Img src="c" />);
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('fades the old image back in when it comes back during the fade, then keeps one layer', () => {
    vi.useFakeTimers();
    const view = render(<Img src="a" />);
    view.rerender(<Img src="b" />);
    view.rerender(<Img src="a" />);
    expect(layers()).toEqual(['a', 'b+in', 'a+in']);
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS));
    expect(layers()).toEqual(['a']);
  });

  it('re-renders the same layer in place when only the value changes, not the key', () => {
    const seen: string[] = [];
    const Probe = ({ v }: { v: { id: string; n: number } }) => (
      <Crossfade value={v} keyOf={(x) => x.id}>
        {(x) => {
          seen.push(`${x.id}${x.n}`);
          return <i class="layer" data-src={`${x.id}${x.n}`} />;
        }}
      </Crossfade>
    );
    const view = render(<Probe v={{ id: 'a', n: 1 }} />);
    const el = document.querySelector('.layer');
    view.rerender(<Probe v={{ id: 'a', n: 2 }} />);
    expect(layers()).toEqual(['a2']);
    expect(document.querySelector('.layer')).toBe(el);
  });

  it('changes at once with prefers-reduced-motion', () => {
    stubMatchMedia({ [REDUCED_MOTION_QUERY]: true });
    vi.useFakeTimers();
    const view = render(<Img src="a" />);
    view.rerender(<Img src="b" />);
    expect(layers()).toEqual(['b']);
  });
});

describe('crossfades in the room', () => {
  const thumb = (id: string) => `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;

  it('crossfades the blurred backdrop when the song changes', () => {
    vi.useFakeTimers();
    const view = render(<Backdrop url={thumb('a')} video={false} />);
    expect([...document.querySelectorAll('.backdrop-img')].map((i) => i.getAttribute('src'))).toEqual([thumb('a')]);
    view.rerender(<Backdrop url={thumb('b')} video={false} />);
    const imgs = [...document.querySelectorAll('.backdrop-img')];
    expect(imgs.map((i) => i.getAttribute('src'))).toEqual([thumb('a'), thumb('b')]);
    expect(imgs[1].classList.contains('xfade-in')).toBe(true);
    expect(imgs.every((i) => i.getAttribute('referrerpolicy') === 'no-referrer')).toBe(true);
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS));
    expect([...document.querySelectorAll('.backdrop-img')].map((i) => i.getAttribute('src'))).toEqual([thumb('b')]);
  });

  it('fades the backdrop to plain when the new song has no artwork', () => {
    vi.useFakeTimers();
    const view = render(<Backdrop url={thumb('a')} video={false} />);
    view.rerender(<Backdrop url={null} video={false} />);
    expect(document.querySelector('.backdrop-blank.xfade-in')).toBeTruthy();
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS));
    expect(document.querySelector('.backdrop-img')).toBeNull();
  });

  it('crossfades the stage cover when the song changes (remote mode)', () => {
    vi.useFakeTimers();
    const at = (id: string, version: number) =>
      room({ queue: [entry('a', { thumbnailUrl: thumb('a') }), entry('b', { thumbnailUrl: thumb('b') })], currentEntryId: id, version });
    const session = fakeSession();
    const view = render(<NowPlaying session={session} room={at('a', 1)} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    view.rerender(<NowPlaying session={session} room={at('b', 2)} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    const covers = [...document.querySelectorAll('.stage > .stage-cover')];
    expect(covers.map((c) => c.getAttribute('src'))).toEqual([thumb('a'), thumb('b')]);
    expect(covers[1].classList.contains('xfade-in')).toBe(true);
    act(() => void vi.advanceTimersByTime(CROSSFADE_MS));
    expect([...document.querySelectorAll('.stage > .stage-cover')].map((c) => c.getAttribute('src'))).toEqual([thumb('b')]);
  });

  it('puts the artwork under everything in the stage as a no-referrer image, not a CSS background', () => {
    const r = room({ queue: [entry('a', { thumbnailUrl: thumb('a') })], currentEntryId: 'a' });
    render(<NowPlaying session={fakeSession()} room={r} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    const stage = document.querySelector<HTMLElement>('.stage')!;
    const art = stage.firstElementChild as HTMLImageElement;
    expect(art.className).toBe('stage-art');
    expect(art.tagName).toBe('IMG');
    expect(art.getAttribute('src')).toBe(thumb('a'));
    expect(art.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(art.getAttribute('alt')).toBe('');
    expect(art.getAttribute('aria-hidden')).toBe('true');
    expect(art.hidden).toBe(false);
    expect(stage.style.backgroundImage).toBe('');
  });

  it('keeps the art layer in its place, empty and hidden, without https artwork', () => {
    const r = room({ queue: [entry('a', { thumbnailUrl: 'http://i.ytimg.com/vi/a/hqdefault.jpg' })], currentEntryId: 'a' });
    render(<NowPlaying session={fakeSession()} room={r} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    const stage = document.querySelector<HTMLElement>('.stage')!;
    const art = stage.firstElementChild as HTMLImageElement;
    expect(art.className).toBe('stage-art');
    expect(art.hasAttribute('src')).toBe(false);
    expect(art.hidden).toBe(true);
    expect(stage.style.backgroundImage).toBe('');
  });
});
