// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubMatchMedia } from '../testing/media-query';
import { DESKTOP_QUERY, useMediaQuery } from './hooks';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function Probe({ query }: { query: string }) {
  return <p data-testid="probe">{useMediaQuery(query) ? 'yes' : 'no'}</p>;
}
const probe = () => document.querySelector('[data-testid="probe"]')!.textContent;

describe('useMediaQuery', () => {
  it('uses 1024 px as the desktop breakpoint', () => {
    expect(DESKTOP_QUERY).toBe('(min-width: 1024px)');
  });

  it('is false where the browser has no matchMedia', () => {
    expect(typeof window.matchMedia).toBe('undefined');
    render(<Probe query={DESKTOP_QUERY} />);
    expect(probe()).toBe('no');
  });

  it('matches from the first render, so a desktop window never paints the phone layout first', () => {
    stubMatchMedia({ [DESKTOP_QUERY]: true });
    const seen: boolean[] = [];
    function Record() {
      seen.push(useMediaQuery(DESKTOP_QUERY));
      return null;
    }
    render(<Record />);
    expect(seen[0]).toBe(true);
  });

  it('follows the query as the window crosses it, and unsubscribes on unmount', () => {
    const media = stubMatchMedia({ [DESKTOP_QUERY]: false });
    const view = render(<Probe query={DESKTOP_QUERY} />);
    expect(probe()).toBe('no');
    expect(media.listeners(DESKTOP_QUERY)).toBe(1);
    act(() => media.set(DESKTOP_QUERY, true));
    expect(probe()).toBe('yes');
    act(() => media.set(DESKTOP_QUERY, false));
    expect(probe()).toBe('no');
    view.unmount();
    expect(media.listeners(DESKTOP_QUERY)).toBe(0);
  });

  it('only reacts to its own query', () => {
    const media = stubMatchMedia({ [DESKTOP_QUERY]: false });
    render(<Probe query={DESKTOP_QUERY} />);
    act(() => media.set('(prefers-reduced-motion: reduce)', true));
    expect(probe()).toBe('no');
  });
});
