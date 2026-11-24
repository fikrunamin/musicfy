// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

  it('leaves out the separator of a video row whose channel is empty', () => {
    const videos = [entry('a'), entry('v', { kind: 'YOUTUBE_VIDEO', title: 'Klip', artist: '' })];
    render(
      <QueueList session={fakeSession()} room={room({ queue: videos, currentEntryId: 'a' })} memberId="m1" unplayable={{}} online pageIsHttps />,
    );
    const meta = screen.getByText('oleh Kamu');
    expect(meta.textContent).toBe('oleh Kamu');
    expect(meta.closest('.media-row, li')?.textContent).not.toContain('·');
  });

  it('shows only own remove buttons to a guest when control is locked', () => {
    renderQueue('m2', false);
    // Only the row with an action available gets the ⋮ menu.
    expect(screen.getAllByRole('button', { name: /^Aksi:/ }).map((b) => b.getAttribute('aria-label'))).toEqual(['Aksi: Lagu b']);
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu b' }));
    expect(screen.queryByRole('button', { name: /^Putar sekarang/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Naikkan/ })).toBeNull();
    expect(screen.getAllByRole('button', { name: /^Hapus/ }).map((b) => b.getAttribute('aria-label'))).toEqual(['Hapus: Lagu b']);
  });

  it('gives full controls when guests can control, and sends Move targets', () => {
    const session = renderQueue('m2', true);
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu c' }));
    fireEvent.click(screen.getByRole('button', { name: 'Naikkan: Lagu c' }));
    expect(session.move).toHaveBeenCalledWith('c', null);
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu b' }));
    fireEvent.click(screen.getByRole('button', { name: 'Turunkan: Lagu b' }));
    expect(session.move).toHaveBeenCalledWith('b', 'c');
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu c' }));
    fireEvent.click(screen.getByRole('button', { name: 'Putar sekarang: Lagu c' }));
    expect(session.playEntry).toHaveBeenCalledWith('c');
  });

  it('opens one row menu at a time and reports it with aria-expanded', () => {
    renderQueue('m1', true);
    const c = screen.getByRole('button', { name: 'Aksi: Lagu c' });
    expect(c.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(c);
    expect(c.getAttribute('aria-expanded')).toBe('true');
    expect(document.getElementById(c.getAttribute('aria-controls')!)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu b' }));
    expect(c.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('button', { name: 'Hapus: Lagu c' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Hapus: Lagu b' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu b' }));
    expect(screen.queryByRole('button', { name: /^Hapus/ })).toBeNull();
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

describe('QueueList layout', () => {
  const view = (queue: ReturnType<typeof entry>[], extra: Partial<Parameters<typeof QueueList>[0]> = {}) =>
    render(<QueueList session={fakeSession()} room={room({ queue, currentEntryId: queue[0]?.entryId ?? null })} memberId="m1" unplayable={{}} online pageIsHttps {...extra} />);

  it('keeps square 44 × 44 covers in a queue of songs only', () => {
    view([entry('a'), entry('b'), entry('c')]);
    expect(document.querySelectorAll('.thumb')).toHaveLength(2);
    expect(document.querySelectorAll('.thumb-wide')).toHaveLength(0);
  });

  it('gives every row a 78 × 44 thumbnail once the queue holds a video, songs included', () => {
    view([entry('a'), entry('b'), entry('v', { kind: 'YOUTUBE_VIDEO', durationMs: 1_458_000 })]);
    expect(document.querySelectorAll('.thumb-wide')).toHaveLength(2);
    expect(screen.getByText('24:18').className).toBe('chip-duration');
  });

  it('shows Sedang diputar with moving bars and no menu only in the full variant', () => {
    const { unmount } = view([entry('a'), entry('b')]);
    expect(screen.queryByText('Lagu a')).toBeNull();
    unmount();
    view([entry('a'), entry('b')], { variant: 'full' });
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Antrean');
    expect(screen.getByRole('heading', { name: 'Sedang diputar' })).toBeTruthy();
    expect(screen.getByText('Lagu a').closest('.row-current')?.querySelector('.eq')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Aksi: Lagu a' })).toBeNull();
  });

  it('folds the played entries behind "Sudah diputar (n)"', () => {
    render(<QueueList session={fakeSession()} room={room({ queue: [entry('p1'), entry('p2'), entry('a')], currentEntryId: 'a' })} memberId="m1" unplayable={{}} online pageIsHttps />);
    const toggle = screen.getByRole('button', { name: 'Sudah diputar (2)' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('Lagu p1')).toBeNull();
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('Lagu p1')).toBeTruthy();
  });

  it('offers Tambah lagu only when it may add, also in the empty state', () => {
    const onAdd = vi.fn();
    const { unmount } = view([entry('a'), entry('b')], { onAdd });
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(onAdd).toHaveBeenCalledTimes(1);
    unmount();
    const empty = view([], { onAdd });
    expect(screen.getByText('Antrean kosong.')).toBeTruthy();
    expect(screen.getByText('Belum ada lagu. Tambahkan lagu untuk mulai.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    expect(onAdd).toHaveBeenCalledTimes(2);
    empty.unmount();
    view([]);
    expect(screen.queryByRole('button', { name: 'Tambah lagu' })).toBeNull();
  });
});

describe('QueueList edge cases', () => {
  const props = { unplayable: {}, online: true, pageIsHttps: true } as const;
  const base = [entry('a'), entry('b'), entry('c')];

  it('shows no menu and no action bar while offline, even for a member with control', () => {
    render(<QueueList session={fakeSession()} room={room({ queue: base, currentEntryId: 'a' })} memberId="m1" {...props} online={false} />);
    expect(screen.queryByRole('button', { name: /^Aksi:/ })).toBeNull();
    expect(document.querySelector('.row-actions')).toBeNull();
  });

  it('full variant: current row has equalizer bars and no menu; played rows get play and remove but no move', () => {
    const queue = [entry('p'), entry('a'), entry('b')];
    render(<QueueList session={fakeSession()} room={room({ queue, currentEntryId: 'a' })} memberId="m1" {...props} variant="full" />);
    const current = screen.getByText('Lagu a').closest('.row-current');
    expect(current?.querySelector('.eq')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Aksi: Lagu a' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Sedang diputar' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sudah diputar (1)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu p' }));
    expect(screen.getByRole('button', { name: 'Putar sekarang: Lagu p' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Hapus: Lagu p' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Naikkan/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Turunkan/ })).toBeNull();
  });

  it('does not reopen a bar that closed because its actions vanished', () => {
    const view = (memberId: string, guestsCanControl: boolean, queue = base) => (
      <QueueList session={fakeSession()} room={room({ queue, currentEntryId: 'a', settings: { guestsCanControl, guestsCanAdd: true } })} memberId={memberId} {...props} />
    );
    const { rerender } = render(view('m1', true));
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu c' }));
    expect(document.querySelector('.row-actions')).toBeTruthy();
    // Control revoked from a guest who did not add c: its menu and bar go away.
    rerender(view('m2', false));
    expect(screen.queryByRole('button', { name: 'Aksi: Lagu c' })).toBeNull();
    // Control returns: the bar stays closed until the member opens it again.
    rerender(view('m2', true));
    expect(document.querySelector('.row-actions')).toBeNull();
    expect(screen.getByRole('button', { name: 'Aksi: Lagu c' }).getAttribute('aria-expanded')).toBe('false');
  });

  it('does not reopen a bar whose row was removed and comes back', () => {
    const view = (queue: typeof base) => <QueueList session={fakeSession()} room={room({ queue, currentEntryId: 'a' })} memberId="m1" {...props} />;
    const { rerender } = render(view(base));
    fireEvent.click(screen.getByRole('button', { name: 'Aksi: Lagu c' }));
    rerender(view([entry('a'), entry('b')]));
    rerender(view(base));
    expect(document.querySelector('.row-actions')).toBeNull();
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
