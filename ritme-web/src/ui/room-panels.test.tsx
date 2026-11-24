// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { MembersPanel } from './members';
import { NowPlaying } from './now-playing';
import { SearchPanel } from './search';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('MembersPanel', () => {
  /** The confirmation is an in-page alertdialog, never window.confirm. */
  const noNativeConfirm = () => {
    const native = vi.fn(() => true);
    vi.stubGlobal('confirm', native);
    return native;
  };
  const confirmIn = (question: string, action: string) =>
    fireEvent.click(within(screen.getByRole('alertdialog', { name: question })).getByRole('button', { name: action }));

  it('gives the host member actions and the control switch', () => {
    const native = noNativeConfirm();
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Jadikan host: Sari' }));
    expect(session.transferHost).toHaveBeenCalledWith('m2');
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan: Sari' }));
    expect(session.kick).not.toHaveBeenCalled();
    confirmIn('Keluarkan Sari dari Ritme?', 'Keluarkan');
    expect(session.kick).toHaveBeenCalledWith('m2');
    expect(screen.queryByRole('alertdialog')).toBeNull();
    fireEvent.click(screen.getByRole('switch', { name: 'Anggota boleh kontrol' }));
    expect(session.setGuestsCanControl).toHaveBeenCalledWith(false);
    fireEvent.click(screen.getByRole('switch', { name: 'Anggota boleh menambah' }));
    expect(session.setGuestsCanAdd).toHaveBeenCalledWith(false);
    expect(native).not.toHaveBeenCalled();
  });

  it('keeps focus in the panel after a confirmed kick removes the opener', () => {
    const session = fakeSession();
    const r = room();
    const { rerender } = render(<MembersPanel session={session} room={r} memberId="m1" online />);
    const opener = screen.getByRole('button', { name: 'Keluarkan: Sari' });
    opener.focus();
    fireEvent.click(opener);
    confirmIn('Keluarkan Sari dari Ritme?', 'Keluarkan');
    rerender(<MembersPanel session={session} room={{ ...r, members: r.members.filter((m) => m.id !== 'm2') }} memberId="m1" online />);
    expect(opener.isConnected).toBe(false);
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement?.id).toBe('members-title');
  });

  it('does not pull focus to the heading on a later members change after a kick that changed nothing', () => {
    const session = fakeSession();
    const r = room();
    const { rerender } = render(<MembersPanel session={session} room={r} memberId="m1" online />);
    const opener = screen.getByRole('button', { name: 'Keluarkan: Sari' });
    opener.focus();
    fireEvent.click(opener);
    confirmIn('Keluarkan Sari dari Ritme?', 'Keluarkan');
    // the kick failed: members did not change. An unrelated update arrives while focus is on a control.
    const other = screen.getByRole('button', { name: 'Jadikan host: Sari' });
    other.focus();
    rerender(<MembersPanel session={session} room={{ ...r, members: r.members.map((m) => ({ ...m })) }} memberId="m1" online />);
    expect(document.activeElement).toBe(other);
    // a later update with focus on the page must not be treated as the kick's aftermath
    (document.activeElement as HTMLElement).blur();
    rerender(<MembersPanel session={session} room={{ ...r, members: r.members.map((m) => ({ ...m })) }} memberId="m1" online />);
    expect(document.activeElement).toBe(document.body);
  });

  it('does not kick or end the room when the host declines the confirmation', () => {
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan: Sari' }));
    confirmIn('Keluarkan Sari dari Ritme?', 'Batal');
    fireEvent.click(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }));
    confirmIn('Akhiri Ritme untuk semua anggota?', 'Batal');
    expect(session.kick).not.toHaveBeenCalled();
    expect(session.endRoom).not.toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('ends the room for everyone after confirming', () => {
    const native = noNativeConfirm();
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }));
    expect(session.endRoom).not.toHaveBeenCalled();
    confirmIn('Akhiri Ritme untuk semua anggota?', 'Akhiri');
    expect(session.endRoom).toHaveBeenCalled();
    expect(native).not.toHaveBeenCalled();
  });

  it('shows guests the list only', () => {
    render(<MembersPanel session={fakeSession()} room={room()} memberId="m2" online />);
    expect(screen.queryByRole('button', { name: /^Keluarkan/ })).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeNull();
    expect(screen.getByText('(kamu)')).toBeTruthy();
  });
});

describe('NowPlaying', () => {
  const playingRoom = (guestsCanControl: boolean) =>
    room({
      settings: { guestsCanControl, guestsCanAdd: true },
      queue: [entry('e1', { title: 'Lagu Satu' })],
      currentEntryId: 'e1',
      playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 },
    });

  it('sends Pause and Next when control is allowed', () => {
    const session = fakeSession();
    render(<NowPlaying session={session} room={playingRoom(true)} memberId="m2" unplayable={{}} online pageIsHttps mode="remote" />);
    expect(screen.getByText('Lagu Satu')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Jeda' }));
    expect(session.pause).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }));
    expect(session.next).toHaveBeenCalled();
  });

  it('dims locked controls, explains why, and answers a tap with the "Host mengunci kontrol" toast', () => {
    const session = fakeSession();
    render(<NowPlaying session={session} room={playingRoom(false)} memberId="m2" unplayable={{}} online pageIsHttps mode="remote" />);
    const pause = screen.getByRole('button', { name: 'Jeda' });
    expect(pause.getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByRole('button', { name: 'Berikutnya' }).getAttribute('aria-disabled')).toBe('true');
    expect((screen.getByRole('slider', { name: 'Posisi lagu' }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText('Host mengunci kontrol')).toBeTruthy();
    expect(pause.getAttribute('aria-describedby')).toBe(screen.getByText('Host mengunci kontrol').id);
    fireEvent.click(pause);
    fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }));
    expect(session.pause).not.toHaveBeenCalled();
    expect(session.next).not.toHaveBeenCalled();
    expect(session.notify).toHaveBeenCalledTimes(2);
    expect(session.notify).toHaveBeenCalledWith('forbidden');
  });

  it('disables the controls while offline, without the locked toast', () => {
    const session = fakeSession();
    render(<NowPlaying session={session} room={playingRoom(true)} memberId="m2" unplayable={{}} online={false} pageIsHttps mode="remote" />);
    expect((screen.getByRole('button', { name: 'Jeda' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText('Host mengunci kontrol')).toBeNull();
  });

  it('gives the seek slider the formatted position as aria-valuetext', () => {
    const paused = room({ queue: [entry('e1')], currentEntryId: 'e1', playback: { isPlaying: false, positionMs: 83_400, anchorServerTimeMs: 0 } });
    render(<NowPlaying session={fakeSession()} room={paused} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    expect(screen.getByRole('slider', { name: 'Posisi lagu' }).getAttribute('aria-valuetext')).toBe('1:23');
  });

  it('shows a video with a 16:9 thumbnail, and a direct link with the domain it comes from', () => {
    const url = entry('e1', { kind: 'URL', videoId: '', url: 'https://www.xn--bcher-kva.example/a.mp4', artist: 'youtube.com', title: 'Klip' });
    render(<NowPlaying session={fakeSession()} room={room({ queue: [url], currentEntryId: 'e1' })} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    expect(document.querySelector('.stage.stage-wide .video-placeholder .vp-domain')?.textContent).toBe('bücher.example');
    // The domain on its own 🔗 line, the adder in the badge row (handoff M).
    expect(document.querySelector('.np-source')?.textContent).toBe('dari bücher.example');
    expect(document.querySelector('.np-badges .np-by')?.textContent).toContain('oleh Kamu');
    expect(screen.queryByText(/youtube\.com/)).toBeNull();
  });

  it('shows LIVE instead of a seek bar for live entries', () => {
    const live = room({ queue: [entry('e1', { durationMs: 0 })], currentEntryId: 'e1' });
    render(<NowPlaying session={fakeSession()} room={live} memberId="m1" unplayable={{}} online pageIsHttps mode="remote" />);
    expect(screen.getByText('LIVE')).toBeTruthy();
    expect(screen.queryByRole('slider')).toBeNull();
  });
});

describe('SearchPanel', () => {
  const song = { videoId: 'dQw4w9WgXcQ', title: 'Never Gonna Give You Up', artist: 'Rick Astley', durationMs: 213_000, thumbnailUrl: null };

  it('switches between the Lagu and Video tabs', () => {
    render(<SearchPanel session={fakeSession()} online />);
    expect(screen.getByRole('heading', { name: 'Tambah ke antrean' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Lagu', selected: true })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'Video' }));
    expect(screen.getByRole('tab', { name: 'Video', selected: true })).toBeTruthy();
    expect(screen.getByLabelText('Tempel link video')).toBeTruthy();
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: 'Lagu' }));
    expect(screen.getByRole('tab', { name: 'Lagu', selected: true })).toBeTruthy();
    expect(screen.getByLabelText('Cari lagu')).toBeTruthy();
  });

  it('refuses http media links on an https page by default and allows them when told the page is http', async () => {
    const probe = vi.fn(async () => ({ kind: 'timeout' as const }));
    const { unmount } = render(<SearchPanel session={fakeSession()} online probe={probe} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Video' }));
    fireEvent.input(screen.getByLabelText('Tempel link video'), { target: { value: 'http://cdn.example.com/a.mp4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Pratinjau' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Halaman harus https untuk link http');
    unmount();
    render(<SearchPanel session={fakeSession()} online pageIsHttps={false} probe={probe} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Video' }));
    fireEvent.input(screen.getByLabelText('Tempel link video'), { target: { value: 'http://cdn.example.com/a.mp4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Pratinjau' }));
    expect(await screen.findByText('MP4 · Durasi tidak diketahui')).toBeTruthy();
    expect(screen.getByText('dari cdn.example.com')).toBeTruthy();
  });

  it('searches on submit and adds a result to the end or as next', async () => {
    const session = fakeSession();
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([song]), { status: 200 })) as unknown as typeof fetch;
    render(<SearchPanel session={session} online fetchImpl={fetchImpl} />);
    fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Tambah: Never Gonna Give You Up' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ videoId: 'dQw4w9WgXcQ', kind: 'YOUTUBE_MUSIC' }), false);
    fireEvent.click(screen.getByRole('button', { name: 'Putar berikutnya: Never Gonna Give You Up' }));
    expect(session.add).toHaveBeenLastCalledWith(expect.objectContaining({ videoId: 'dQw4w9WgXcQ' }), true);
  });

  it('shows only https YouTube thumbnails in the results, else the placeholder', async () => {
    const results = [
      { ...song, videoId: 'aaaaaaaaaaa', title: 'A', thumbnailUrl: 'https://i.ytimg.com/vi/a/hq.jpg' },
      { ...song, videoId: 'bbbbbbbbbbb', title: 'B', thumbnailUrl: 'http://i.ytimg.com/vi/b/hq.jpg' },
      { ...song, videoId: 'ccccccccccc', title: 'C', thumbnailUrl: 'https://evil.example/c.jpg' },
    ];
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(results), { status: 200 })) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} />);
    fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    await screen.findByRole('button', { name: 'Tambah: A' });
    expect([...document.querySelectorAll('img.thumb')].map((i) => i.getAttribute('src'))).toEqual(['https://i.ytimg.com/vi/a/hq.jpg']);
    expect(document.querySelectorAll('.thumb-empty')).toHaveLength(2);
  });

  it('labels a live result (durationMs 0) LIVE instead of 0:00', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([{ ...song, durationMs: 0 }]), { status: 200 })) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} />);
    fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    expect(await screen.findByText('Rick Astley · LIVE')).toBeTruthy();
    expect(screen.queryByText(/0:00/)).toBeNull();
  });

  it('as the desktop panel: a labelled region, an icon field and round "+" add buttons', async () => {
    const session = fakeSession();
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([song]), { status: 200 })) as unknown as typeof fetch;
    render(<SearchPanel session={session} online fetchImpl={fetchImpl} variant="panel" />);
    const region = screen.getByRole('region', { name: 'Tambah ke antrean' });
    expect(within(region).queryByRole('button', { name: 'Tutup' })).toBeNull();
    // The label stays for screen readers; the field shows the search icon instead.
    const field = screen.getByLabelText('Cari lagu');
    expect(document.querySelector(`label[for="${field.id}"]`)?.classList.contains('visually-hidden')).toBe(true);
    expect(field.closest('.input-wrap-icon')).toBeTruthy();
    fireEvent.input(field, { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    const add = await screen.findByRole('button', { name: 'Tambah: Never Gonna Give You Up' });
    expect(add.classList.contains('icon-btn')).toBe(true);
    expect(add.textContent).toBe('');
    fireEvent.click(add);
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ videoId: 'dQw4w9WgXcQ' }), false);
  });

  it('as the sheet: a visible field label and the "Tambah" text button', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([song]), { status: 200 })) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} onClose={() => {}} />);
    expect(screen.queryByRole('region')).toBeNull();
    const field = screen.getByLabelText('Cari lagu');
    expect(document.querySelector(`label[for="${field.id}"]`)?.classList.contains('visually-hidden')).toBe(false);
    fireEvent.input(field, { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    expect((await screen.findByRole('button', { name: 'Tambah: Never Gonna Give You Up' })).textContent).toBe('Tambah');
  });

  it('explains rate limiting', async () => {
    const fetchImpl = vi.fn(async () => new Response('', { status: 429 })) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} />);
    fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Terlalu banyak pencarian, tunggu sebentar.');
  });
});
