// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { memoryStore } from '../net/storage';
import { initialSession } from '../session/reducer';
import { entry, room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { RoomScreen } from './room';
import { SearchPanel } from './search';

afterEach(cleanup);

const song = { videoId: 'dQw4w9WgXcQ', title: 'Never Gonna Give You Up', artist: 'Rick Astley', durationMs: 213_000, thumbnailUrl: null };
const answer = (body: unknown, status = 200) => vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;

const search = (query = 'rick') => {
  fireEvent.input(screen.getByLabelText('Cari lagu'), { target: { value: query } });
  fireEvent.click(screen.getByRole('button', { name: 'Cari' }));
};

describe('SearchPanel header and tabs', () => {
  it('has the "Tambah ke antrean" heading and a close button only when it can close', () => {
    const onClose = vi.fn();
    const { unmount } = render(<SearchPanel session={fakeSession()} online onClose={onClose} />);
    expect(screen.getByRole('heading', { level: 2, name: 'Tambah ke antrean' }).id).toBe('add-title');
    fireEvent.click(screen.getByRole('button', { name: 'Tutup' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
    render(<SearchPanel session={fakeSession()} online />);
    expect(screen.queryByRole('button', { name: 'Tutup' })).toBeNull();
  });

  it('uses real WAI-ARIA tabs: one tab stop, selected state and a labelled tabpanel', () => {
    render(<SearchPanel session={fakeSession()} online />);
    const list = screen.getByRole('tablist', { name: 'Yang ditambahkan' });
    const [songs, video] = within(list).getAllByRole('tab');
    expect(songs.textContent).toBe('Lagu');
    expect(video.textContent).toBe('Video');
    expect(songs.getAttribute('aria-selected')).toBe('true');
    expect(video.getAttribute('aria-selected')).toBe('false');
    expect(songs.tabIndex).toBe(0);
    expect(video.tabIndex).toBe(-1);
    const panel = screen.getByRole('tabpanel', { name: 'Lagu' });
    expect(songs.getAttribute('aria-controls')).toBe(panel.id);
    expect(within(panel).getByLabelText('Cari lagu')).toBeTruthy();
    // The other tab's panel exists for aria-controls, hidden and empty.
    const other = document.getElementById(video.getAttribute('aria-controls')!)!;
    expect(other.hidden).toBe(true);
    expect(other.childElementCount).toBe(0);
  });

  it('switches tabs on click', () => {
    render(<SearchPanel session={fakeSession()} online />);
    fireEvent.click(screen.getByRole('tab', { name: 'Video' }));
    expect(screen.getByRole('tab', { name: 'Video' }).getAttribute('aria-selected')).toBe('true');
    expect(within(screen.getByRole('tabpanel', { name: 'Video' })).getByLabelText('Tempel link video')).toBeTruthy();
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: 'Lagu' }));
    expect(screen.getByLabelText('Cari lagu')).toBeTruthy();
  });

  it('moves between tabs with the arrow keys (wrapping), Home and End, selecting and focusing the tab', () => {
    render(<SearchPanel session={fakeSession()} online />);
    const songs = screen.getByRole('tab', { name: 'Lagu' });
    const video = screen.getByRole('tab', { name: 'Video' });
    const selected = () => screen.getAllByRole('tab').find((tab) => tab.getAttribute('aria-selected') === 'true');
    const press = (key: string) => fireEvent.keyDown(document.activeElement!, { key });
    songs.focus();

    press('ArrowRight');
    expect(selected()).toBe(video);
    expect(document.activeElement).toBe(video);
    expect(video.tabIndex).toBe(0);
    expect(songs.tabIndex).toBe(-1);
    expect(screen.getByLabelText('Tempel link video')).toBeTruthy();

    press('ArrowRight'); // wraps
    expect(selected()).toBe(songs);
    expect(document.activeElement).toBe(songs);

    press('ArrowLeft'); // wraps backwards
    expect(selected()).toBe(video);
    expect(document.activeElement).toBe(video);

    press('Home');
    expect(selected()).toBe(songs);
    expect(document.activeElement).toBe(songs);

    press('End');
    expect(selected()).toBe(video);
    expect(document.activeElement).toBe(video);

    press('ArrowLeft');
    expect(selected()).toBe(songs);

    press('ArrowDown'); // not a horizontal tablist key
    press('a');
    expect(selected()).toBe(songs);
    expect(document.activeElement).toBe(songs);
  });

  it('leaves Alt, Ctrl and Meta with an arrow, Home or End to the browser', () => {
    render(<SearchPanel session={fakeSession()} online />);
    const songs = screen.getByRole('tab', { name: 'Lagu' });
    songs.focus();
    for (const modifier of ['altKey', 'ctrlKey', 'metaKey']) {
      for (const key of ['ArrowRight', 'ArrowLeft', 'Home', 'End']) {
        const event = new KeyboardEvent('keydown', { key, [modifier]: true, bubbles: true, cancelable: true });
        songs.dispatchEvent(event);
        expect(event.defaultPrevented).toBe(false);
        expect(songs.getAttribute('aria-selected')).toBe('true');
        expect(document.activeElement).toBe(songs);
      }
    }
  });
});

describe('SearchPanel song search', () => {
  it('is a labelled search field with a search icon and a primary Cari', () => {
    render(<SearchPanel session={fakeSession()} online />);
    const input = screen.getByLabelText('Cari lagu') as HTMLInputElement;
    expect(input.type).toBe('search');
    expect(input.placeholder).toBe('Judul atau artis');
    expect(input.maxLength).toBe(200);
    expect(input.closest('.input-wrap')?.querySelector('svg.icon')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Cari' }).className).toContain('btn-primary');
  });

  it('shows results as media rows: an icon "Putar berikutnya" and a "Tambah" button', async () => {
    const session = fakeSession();
    render(<SearchPanel session={session} online fetchImpl={answer([song])} />);
    search();
    const add = await screen.findByRole('button', { name: 'Tambah: Never Gonna Give You Up' });
    expect(add.textContent).toBe('Tambah');
    const next = screen.getByRole('button', { name: 'Putar berikutnya: Never Gonna Give You Up' });
    expect(next.className).toContain('icon-btn');
    expect(next.textContent).toBe('');
    const row = add.closest('li')!;
    expect(row.classList.contains('row')).toBe(true);
    expect(within(row).getByText('Never Gonna Give You Up').className).toBe('row-title');
    expect(within(row).getByText('Rick Astley · 3:33')).toBeTruthy();
    fireEvent.click(next);
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ videoId: 'dQw4w9WgXcQ' }), true);
  });

  it('turns a live result meta red', async () => {
    render(<SearchPanel session={fakeSession()} online fetchImpl={answer([{ ...song, durationMs: 0 }, { ...song, videoId: 'bbbbbbbbbbb', title: 'B' }])} />);
    search();
    expect((await screen.findByText('Rick Astley · LIVE')).classList.contains('meta-live')).toBe(true);
    expect(screen.getByText('Rick Astley · 3:33').classList.contains('meta-live')).toBe(false);
  });

  it('disables both add buttons while offline', async () => {
    render(<SearchPanel session={fakeSession()} online={false} fetchImpl={answer([song])} />);
    search();
    expect((await screen.findByRole('button', { name: 'Tambah: Never Gonna Give You Up' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Putar berikutnya: Never Gonna Give You Up' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows "Mencari…" as a status chip while searching, then "Tidak ada hasil."', async () => {
    let finish!: (r: Response) => void;
    const fetchImpl = vi.fn(() => new Promise<Response>((resolve) => (finish = resolve))) as unknown as typeof fetch;
    render(<SearchPanel session={fakeSession()} online fetchImpl={fetchImpl} />);
    search();
    const status = screen.getByRole('status');
    expect(status.querySelector('.add-chip')?.textContent).toBe('Mencari…');
    expect((screen.getByRole('button', { name: 'Cari' }) as HTMLButtonElement).disabled).toBe(true);
    finish(new Response('[]', { status: 200 }));
    expect(await screen.findByText('Tidak ada hasil.')).toBeTruthy();
    expect(screen.getByText('Tidak ada hasil.').className).toBe('add-chip');
    expect(screen.getByRole('status').contains(screen.getByText('Tidak ada hasil.'))).toBe(true);
    expect(screen.queryByText('Mencari…')).toBeNull();
  });

  it('shows the busy error as an alert chip', async () => {
    render(<SearchPanel session={fakeSession()} online fetchImpl={answer('', 503)} />);
    search();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Server pencarian sedang sibuk, coba lagi nanti.');
    expect(alert.classList.contains('add-chip')).toBe(true);
  });
});

describe('RoomScreen add sheet', () => {
  const host = { ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room() };
  const guest = (guestsCanAdd: boolean) => ({ ...host, memberId: 'm2', room: room({ settings: { guestsCanControl: true, guestsCanAdd } }) });

  it('has no inline search: "Tambah lagu" opens the sheet as a labelled modal dialog', () => {
    render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah lagu' }));
    const dialog = screen.getByRole('dialog', { name: 'Tambah ke antrean' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(within(dialog).getByLabelText('Cari lagu')).toBeTruthy();
    expect(document.querySelector('main.room')?.hasAttribute('inert')).toBe(true);
  });

  it('closes on "Tutup" and gives focus back to "Tambah lagu"', () => {
    render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    const opener = screen.getByRole('button', { name: 'Tambah lagu' });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Tutup' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.querySelector('main.room')?.hasAttribute('inert')).toBe(false);
  });

  it('closes on Escape and on the scrim, focus back on the opener', () => {
    render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    const opener = screen.getByRole('button', { name: 'Tambah lagu' });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    fireEvent.click(opener);
    fireEvent.click(document.querySelector('.sheet-scrim')!);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('gives focus to the "Tambah lagu" now shown when the one that opened the sheet is gone', () => {
    const view = render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    const opener = screen.getByRole('button', { name: 'Tambah lagu' }); // the empty state's
    opener.focus();
    fireEvent.click(opener);
    // The first song arrives while the sheet is open: the empty state gives way to the queue header.
    const filled = { ...host, room: room({ queue: [entry('a'), entry('b')], currentEntryId: 'a' }) };
    view.rerender(<RoomScreen session={fakeSession(filled)} state={filled} prefs={memoryStore()} />);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Tutup' }));
    expect(opener.isConnected).toBe(false);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Tambah lagu' }));
  });

  it('offers no add button while adding is locked', () => {
    const locked = guest(false);
    render(<RoomScreen session={fakeSession(locked)} state={locked} prefs={memoryStore()} />);
    expect(screen.queryByRole('button', { name: 'Tambah lagu' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
  });

  it('closes the open sheet when the host locks adding, keeps focus in the room, and does not reopen it on unlock', () => {
    const open = guest(true);
    const view = render(<RoomScreen session={fakeSession(open)} state={open} prefs={memoryStore()} />);
    const opener = screen.getByRole('button', { name: 'Tambah lagu' });
    opener.focus();
    fireEvent.click(opener);
    expect(screen.getByRole('dialog')).toBeTruthy();
    const locked = guest(false);
    view.rerender(<RoomScreen session={fakeSession(locked)} state={locked} prefs={memoryStore()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Tambah lagu' })).toBeNull();
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement?.tagName).toBe('H1');
    const unlocked = guest(true);
    view.rerender(<RoomScreen session={fakeSession(unlocked)} state={unlocked} prefs={memoryStore()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Tambah lagu' })).toBeTruthy();
  });
});
