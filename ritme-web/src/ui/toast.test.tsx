// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TOAST_MS, Toast } from './toast';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Toast', () => {
  it('announces the notice politely with its icon and hides it after 3 s', () => {
    vi.useFakeTimers();
    render(<Toast notice={{ id: 1, key: 'forbidden' }} />);
    const region = screen.getByRole('status');
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.textContent).toBe('Host mengunci kontrol');
    expect(region.querySelector('.toast svg')?.getAttribute('aria-hidden')).toBe('true');
    act(() => {
      vi.advanceTimersByTime(TOAST_MS - 1);
    });
    expect(region.textContent).toBe('Host mengunci kontrol');
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(region.textContent).toBe('');
  });

  it('shows a repeated notice again (new id) for a fresh 3 s', () => {
    vi.useFakeTimers();
    const view = render(<Toast notice={{ id: 1, key: 'linkCopied' }} />);
    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    view.rerender(<Toast notice={{ id: 2, key: 'linkCopied' }} />);
    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    expect(screen.getByRole('status').textContent).toBe('Link disalin');
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.getByRole('status').textContent).toBe('');
  });
});
