// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
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
  it('gives the host member actions and the control switch', () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Jadikan host' }));
    expect(session.transferHost).toHaveBeenCalledWith('m2');
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan' }));
    expect(session.kick).toHaveBeenCalledWith('m2');
    fireEvent.click(screen.getByRole('switch', { name: 'Anggota boleh kontrol' }));
    expect(session.setGuestsCanControl).toHaveBeenCalledWith(false);
  });

  it('does not kick or end the room when the host declines the confirmation', () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan' }));
    fireEvent.click(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }));
    expect(session.kick).not.toHaveBeenCalled();
    expect(session.endRoom).not.toHaveBeenCalled();
  });

  it('ends the room for everyone after confirming', () => {
    const ask = vi.fn(() => true);
    vi.stubGlobal('confirm', ask);
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m1" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }));
    expect(ask).toHaveBeenCalledWith('Akhiri Ritme untuk semua anggota?');
    expect(session.endRoom).toHaveBeenCalled();
  });

  it('shows guests the list only', () => {
    render(<MembersPanel session={fakeSession()} room={room()} memberId="m2" online />);
    expect(screen.queryByRole('button', { name: 'Keluarkan' })).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeNull();
    expect(screen.getByText('(kamu)')).toBeTruthy();
  });
});

describe('NowPlaying', () => {
  const playingRoom = (guestsCanControl: boolean) =>
    room({
      settings: { guestsCanControl },
      queue: [entry('e1', { title: 'Lagu Satu' })],
      currentEntryId: 'e1',
      playback: { isPlaying: true, positionMs: 0, anchorServerTimeMs: 0 },
    });

  it('sends Pause and Next when control is allowed', () => {
    const session = fakeSession();
    render(<NowPlaying session={session} room={playingRoom(true)} memberId="m2" unplayable={{}} online pageIsHttps />);
    expect(screen.getByText('Lagu Satu')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Jeda' }));
    expect(session.pause).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }));
    expect(session.next).toHaveBeenCalled();
  });

  it('disables controls and explains why when the host locked them', () => {
    render(<NowPlaying session={fakeSession()} room={playingRoom(false)} memberId="m2" unplayable={{}} online pageIsHttps />);
    expect((screen.getByRole('button', { name: 'Jeda' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('slider', { name: 'Posisi lagu' }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText('Host mengunci kontrol')).toBeTruthy();
  });

  it('shows LIVE instead of a seek bar for live entries', () => {
    const live = room({ queue: [entry('e1', { durationMs: 0 })], currentEntryId: 'e1' });
    render(<NowPlaying session={fakeSession()} room={live} memberId="m1" unplayable={{}} online pageIsHttps />);
    expect(screen.getByText('LIVE')).toBeTruthy();
    expect(screen.queryByRole('slider')).toBeNull();
  });
});

describe('SearchPanel', () => {
  const song = { videoId: 'dQw4w9WgXcQ', title: 'Never Gonna Give You Up', artist: 'Rick Astley', durationMs: 213_000, thumbnailUrl: null };

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

  it('explains rate limiting', async () => {
    const fetchImpl = vi.fn(async () => new Response('', { status: 429 })) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} />);
    fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: 'rick' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Terlalu banyak pencarian, tunggu sebentar.');
  });
});
