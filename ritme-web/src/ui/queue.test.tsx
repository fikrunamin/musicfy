// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it } from 'vitest';
import { entry, room } from '../testing/builders';
import { QueueList } from './queue';
import { fakeSession } from '../testing/fake-session';

afterEach(cleanup);

const queue = [entry('a'), entry('b', { addedBy: 'm2' }), entry('c', { addedBy: 'm1' }), entry('d', { kind: 'UNKNOWN' })];

function renderQueue(memberId: string, guestsCanControl: boolean, unplayable: Record<string, true> = {}) {
  const session = fakeSession();
  render(
    <QueueList
      session={session}
      room={room({ queue, currentEntryId: 'a', settings: { guestsCanControl, guestsCanAdd: true } })}
      memberId={memberId}
      unplayable={unplayable}
      online
      pageIsHttps
    />,
  );
  return session;
}

describe('QueueList', () => {
  it('shows video entries with a 16:9 thumbnail and a Video badge', () => {
    const videos = [
      entry('a'),
      entry('v', { kind: 'YOUTUBE_VIDEO', title: 'Klip' }),
      entry('u', { kind: 'URL', videoId: '', url: 'https://cdn.example.com/c.mp4', title: 'File' }),
    ];
    render(
      <QueueList session={fakeSession()} room={room({ queue: videos, currentEntryId: 'a' })} memberId="m1" unplayable={{}} online pageIsHttps />,
    );
    expect(document.querySelectorAll('.thumb-wide')).toHaveLength(2);
    expect(screen.getAllByText('Video')).toHaveLength(2);
  });

  it('badges a live video LIVE instead of Video and shows where a direct link comes from', () => {
    const videos = [
      entry('a'),
      entry('v', { kind: 'YOUTUBE_VIDEO', title: 'Siaran', artist: 'Kanal', durationMs: 0 }),
      entry('u', { kind: 'URL', videoId: '', url: 'https://www.xn--bcher-kva.example/c.m3u8', artist: 'spoof.example', title: 'File', durationMs: 0 }),
    ];
    render(
      <QueueList session={fakeSession()} room={room({ queue: videos, currentEntryId: 'a' })} memberId="m1" unplayable={{}} online pageIsHttps />,
    );
    expect(screen.queryByText('Video')).toBeNull();
    expect(screen.getAllByText('LIVE')).toHaveLength(2);
    expect(screen.getByText('Kanal · oleh Kamu')).toBeTruthy();
    expect(screen.getByText('dari bücher.example · oleh Kamu')).toBeTruthy();
  });

  it('shows only own remove buttons to a guest when control is locked', () => {
    renderQueue('m2', false);
    expect(screen.queryByRole('button', { name: /^Putar sekarang/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Naikkan/ })).toBeNull();
    expect(screen.getAllByRole('button', { name: /^Hapus/ }).map((b) => b.getAttribute('aria-label'))).toEqual(['Hapus: Lagu b']);
  });

  it('gives full controls when guests can control, and sends Move targets', () => {
    const session = renderQueue('m2', true);
    fireEvent.click(screen.getByRole('button', { name: 'Naikkan: Lagu c' }));
    expect(session.move).toHaveBeenCalledWith('c', null);
    fireEvent.click(screen.getByRole('button', { name: 'Turunkan: Lagu b' }));
    expect(session.move).toHaveBeenCalledWith('b', 'c');
    fireEvent.click(screen.getByRole('button', { name: 'Putar sekarang: Lagu c' }));
    expect(session.playEntry).toHaveBeenCalledWith('c');
  });

  it('labels unsupported and web-unplayable entries', () => {
    renderQueue('m1', true, { b: true });
    expect(screen.getByText('Tidak didukung di versi ini')).toBeTruthy();
    expect(screen.getByText('Tidak bisa diputar di web')).toBeTruthy();
  });

  it('names who added each entry, "Kamu" for yourself', () => {
    renderQueue('m2', true);
    expect(screen.getByText(/oleh Kamu/)).toBeTruthy();
    expect(screen.getAllByText(/oleh Budi/).length).toBeGreaterThan(0);
  });
});

describe('Thumb', () => {
  const thumbQueue = (thumbnailUrl: string | null) =>
    render(
      <QueueList
        session={fakeSession()}
        room={room({ queue: [entry('a'), entry('b', { thumbnailUrl })], currentEntryId: 'a' })}
        memberId="m1"
        unplayable={{}}
        online
        pageIsHttps
      />,
    );

  it('shows an https thumbnail', () => {
    thumbQueue('https://images.example/b.jpg');
    expect(document.querySelector('img.thumb')?.getAttribute('src')).toBe('https://images.example/b.jpg');
  });

  it('shows the placeholder for a non-https thumbnail', () => {
    thumbQueue('http://images.example/b.jpg');
    expect(document.querySelector('img.thumb')).toBeNull();
    expect(document.querySelector('.thumb-empty')).toBeTruthy();
    cleanup();
    thumbQueue('javascript:alert(1)');
    expect(document.querySelector('img.thumb')).toBeNull();
  });
});
