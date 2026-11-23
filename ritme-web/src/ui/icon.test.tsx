// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/preact';
import { afterEach, describe, expect, it } from 'vitest';
import { Icon } from './icon';
import { ICON_PATHS } from './icon-paths';

afterEach(cleanup);

describe('Icon', () => {
  it('renders a decorative inline SVG path in the text colour', () => {
    const { container } = render(<Icon name="play_arrow" size={20} />);
    const svg = container.querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('focusable')).toBe('false');
    expect(svg.getAttribute('viewBox')).toBe('0 -960 960 960');
    expect(svg.getAttribute('fill')).toBe('currentColor');
    expect(svg.getAttribute('width')).toBe('20');
    expect(svg.querySelector('path')!.getAttribute('d')).toBe(ICON_PATHS.play_arrow);
  });

  it('ships only path data (no external references)', () => {
    for (const d of Object.values(ICON_PATHS)) expect(d).toMatch(/^[MmLlHhVvCcSsQqTtAaZz0-9.,\s-]+$/);
  });
});
