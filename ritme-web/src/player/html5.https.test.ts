// @vitest-environment jsdom
// @vitest-environment-options {"url":"https://ritme.example/"}
import { describe, expect, it, vi } from 'vitest';
import { createVideoPlayer } from './html5';
import type { PlayerEvent } from './types';

describe('createVideoPlayer on an https page', () => {
  it('http URL is unplayable without setting src; https is loaded', () => {
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
    const events: PlayerEvent[] = [];
    const container = document.createElement('div');
    const player = createVideoPlayer(container, (e) => events.push(e));
    const video = container.querySelector('video')!;
    player.load({ kind: 'video', url: 'http://cdn.example/v.mp4', hls: false }, null, 5);
    expect(events).toEqual([{ type: 'unplayable', gen: 5 }]);
    expect(video.hasAttribute('src')).toBe(false);
    player.load({ kind: 'video', url: 'https://cdn.example/v.mp4', hls: false }, null, 6);
    expect(video.getAttribute('src')).toBe('https://cdn.example/v.mp4');
    expect(events).toHaveLength(1);
  });
});
