// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { memoryStore } from '../net/storage';
import { initialSession } from '../session/reducer';
import { room } from '../testing/builders';
import { fakeSession } from '../testing/fake-session';
import { MembersPanel } from './members';
import { RoomScreen } from './room';

afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  vi.unstubAllGlobals();
});

/** Budi hosts (m1), Sari is here (m2), Rani dropped out (m3). */
const three = () =>
  room({
    members: [
      { id: 'm1', name: 'Budi', isHost: true, connected: true, joinedAt: 1 },
      { id: 'm2', name: 'Sari', isHost: false, connected: true, joinedAt: 2 },
      { id: 'm3', name: 'Rani', isHost: false, connected: false, joinedAt: 3 },
    ],
  });

const rowOf = (name: string) => screen.getByText(name, { exact: true }).closest('li') as HTMLElement;
const describedBy = (el: HTMLElement) =>
  (el.getAttribute('aria-describedby') ?? '')
    .split(' ')
    .map((id) => document.getElementById(id)?.textContent)
    .join(' ');

describe('MembersPanel switch cards', () => {
  it('names each switch by its title and describes it by its hint', () => {
    render(<MembersPanel session={fakeSession()} room={room()} memberId="m1" online />);
    const control = screen.getByRole('switch', { name: 'Anggota boleh kontrol' });
    const add = screen.getByRole('switch', { name: 'Anggota boleh menambah' });
    for (const [sw, title, hint] of [
      [control, 'Anggota boleh kontrol', 'Putar, jeda, dan lompat lagu'],
      [add, 'Anggota boleh menambah', 'Tambah lagu dan video ke antrean'],
    ] as const) {
      expect(document.getElementById(sw.getAttribute('aria-labelledby')!)?.textContent).toBe(title);
      expect(describedBy(sw)).toBe(hint);
      expect(sw.closest('.switch-card')).toBeTruthy();
    }
  });

  it('shows the current settings and toggles them, from the switch or from the card text', () => {
    const session = fakeSession();
    const view = render(
      <MembersPanel session={session} room={room({ settings: { guestsCanControl: true, guestsCanAdd: false } })} memberId="m1" online />,
    );
    const control = screen.getByRole('switch', { name: 'Anggota boleh kontrol' }) as HTMLInputElement;
    const add = screen.getByRole('switch', { name: 'Anggota boleh menambah' }) as HTMLInputElement;
    expect(control.checked).toBe(true);
    expect(add.checked).toBe(false);
    fireEvent.click(control);
    expect(session.setGuestsCanControl).toHaveBeenLastCalledWith(false);
    fireEvent.click(screen.getByText('Tambah lagu dan video ke antrean'));
    expect(session.setGuestsCanAdd).toHaveBeenLastCalledWith(true);
    view.rerender(
      <MembersPanel session={session} room={room({ settings: { guestsCanControl: false, guestsCanAdd: true } })} memberId="m1" online />,
    );
    expect(control.checked).toBe(false);
    expect(add.checked).toBe(true);
  });

  it('gives guests and an offline host no switches', () => {
    const view = render(<MembersPanel session={fakeSession()} room={room()} memberId="m2" online />);
    expect(screen.queryByRole('switch')).toBeNull();
    view.rerender(<MembersPanel session={fakeSession()} room={room()} memberId="m1" online={false} />);
    expect(screen.queryByRole('switch')).toBeNull();
  });
});

describe('MembersPanel member rows', () => {
  it('shows a 44 px avatar, the name, "(kamu)", the Host badge and the status', () => {
    render(<MembersPanel session={fakeSession()} room={three()} memberId="m1" online />);
    const budi = rowOf('Budi');
    expect(budi.querySelector('.avatar.avatar-lg')?.textContent).toBe('B');
    expect(within(budi).getByText('(kamu)')).toBeTruthy();
    expect(within(budi).getByText('Host').classList.contains('badge-host')).toBe(true);
    expect(within(budi).getByText('Host room')).toBeTruthy();

    const sari = rowOf('Sari');
    expect(within(sari).queryByText('(kamu)')).toBeNull();
    expect(within(sari).queryByText('Host')).toBeNull();
    expect(within(sari).getByText('Tersambung')).toBeTruthy();
    expect(sari.classList.contains('member-away')).toBe(false);

    const rani = rowOf('Rani');
    expect(within(rani).getByText('terputus')).toBeTruthy();
    expect(rani.classList.contains('member-away')).toBe(true);
  });

  it('marks the guest\'s own row with "(kamu)"', () => {
    render(<MembersPanel session={fakeSession()} room={three()} memberId="m2" online />);
    expect(within(rowOf('Sari')).getByText('(kamu)')).toBeTruthy();
    expect(within(rowOf('Budi')).queryByText('(kamu)')).toBeNull();
  });
});

describe('MembersPanel host actions', () => {
  it('gives the host a star "Jadikan host: <name>" and a red "Keluarkan: <name>" per other member', () => {
    const session = fakeSession();
    render(<MembersPanel session={session} room={three()} memberId="m1" online />);
    expect(within(rowOf('Budi')).queryAllByRole('button')).toHaveLength(0);
    const makeHost = screen.getByRole('button', { name: 'Jadikan host: Sari' });
    const kick = screen.getByRole('button', { name: 'Keluarkan: Sari' });
    expect(rowOf('Sari').contains(makeHost) && rowOf('Sari').contains(kick)).toBe(true);
    expect(kick.classList.contains('icon-btn-danger')).toBe(true);
    fireEvent.click(makeHost);
    expect(session.transferHost).toHaveBeenCalledWith('m2');
    fireEvent.click(kick);
    fireEvent.click(within(screen.getByRole('alertdialog', { name: 'Keluarkan Sari dari Ritme?' })).getByRole('button', { name: 'Keluarkan' }));
    expect(session.kick).toHaveBeenCalledWith('m2');
  });

  it('cannot hand the host role to someone who dropped out, but can still remove them', () => {
    const session = fakeSession();
    render(<MembersPanel session={session} room={three()} memberId="m1" online />);
    const makeHost = screen.getByRole('button', { name: 'Jadikan host: Rani' }) as HTMLButtonElement;
    expect(makeHost.disabled).toBe(true); // a disabled button takes no clicks in a browser
    expect((screen.getByRole('button', { name: 'Keluarkan: Rani' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('gives guests no member actions', () => {
    render(<MembersPanel session={fakeSession()} room={three()} memberId="m2" online />);
    expect(screen.queryByRole('button', { name: /^Jadikan host/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Keluarkan/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeNull();
  });

  it('keeps focus in the panel when handing over the host role removes the host controls', () => {
    const session = fakeSession();
    const r = three();
    const view = render(<MembersPanel session={session} room={r} memberId="m1" online />);
    const makeHost = screen.getByRole('button', { name: 'Jadikan host: Sari' });
    makeHost.focus();
    fireEvent.click(makeHost);
    const handedOver = {
      ...r,
      hostId: 'm2',
      members: r.members.map((m) => ({ ...m, isHost: m.id === 'm2' })),
    };
    view.rerender(<MembersPanel session={session} room={handedOver} memberId="m1" online />);
    expect(makeHost.isConnected).toBe(false);
    expect(document.activeElement?.id).toBe('members-title');
  });
});

describe('MembersPanel share card and leaving', () => {
  it('shows "Bagikan kode", the code, the link, and copies the link on "Salin link"', async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const session = fakeSession();
    render(<MembersPanel session={session} room={room()} memberId="m2" online />);
    const card = screen.getByText('Bagikan kode').closest('.share-card') as HTMLElement;
    expect(within(card).getByText('K7M2QX').classList.contains('share-code')).toBe(true);
    expect(within(card).getByText(`${location.host}/r/K7M2QX`)).toBeTruthy();
    fireEvent.click(within(card).getByRole('button', { name: 'Salin link' }));
    await Promise.resolve();
    expect(writeText).toHaveBeenCalledWith(`${location.origin}/r/K7M2QX`);
    await vi.waitFor(() => expect(session.notify).toHaveBeenCalledWith('linkCopied'));
  });

  it('offers "Keluar Ritme" to everyone and the danger "Akhiri Ritme untuk semua" to the host only', () => {
    const session = fakeSession();
    const view = render(<MembersPanel session={session} room={room()} memberId="m2" online />);
    fireEvent.click(screen.getByRole('button', { name: 'Keluar Ritme' }));
    expect(session.leave).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Akhiri Ritme untuk semua' })).toBeNull();
    view.rerender(<MembersPanel session={session} room={room()} memberId="m1" online />);
    expect(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }).classList.contains('btn-danger')).toBe(true);
  });
});

describe('MembersPanel variants', () => {
  it('sheet: the "Anggota" heading, the member count and "Tutup"', () => {
    const onClose = vi.fn();
    render(<MembersPanel session={fakeSession()} room={three()} memberId="m1" online variant="sheet" onClose={onClose} />);
    expect(screen.getByRole('heading', { name: 'Anggota' }).id).toBe('members-title');
    expect(screen.getByText('3 anggota')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Tutup' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('compact: the list only, no settings, actions or share card', () => {
    render(<MembersPanel session={fakeSession()} room={three()} memberId="m1" online variant="compact" />);
    expect(screen.getByText('Sari')).toBeTruthy();
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByText('Bagikan kode')).toBeNull();
  });
});

describe('RoomScreen members sheet', () => {
  const host = { ...initialSession, phase: 'in-room' as const, connection: 'online' as const, memberId: 'm1', room: room() };
  const openSheet = () => fireEvent.click(screen.getByRole('button', { name: '2 anggota' }));

  it('has no inline members panel or footer: the "N anggota" avatar stack opens the sheet', () => {
    render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    expect(document.querySelector('section[aria-labelledby="members-title"]')).toBeNull();
    expect(document.querySelector('.room-footer')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Keluar Ritme' })).toBeNull();
    const opener = screen.getByRole('button', { name: '2 anggota' });
    expect(opener.querySelector('.avatar-stack')).toBeTruthy();
    openSheet();
    const dialog = screen.getByRole('dialog', { name: 'Anggota' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(document.querySelector('main.room')?.hasAttribute('inert')).toBe(true);
    expect(dialog.querySelector('.members')?.textContent).toContain('Sari');
    expect(within(dialog).getByRole('button', { name: 'Keluar Ritme' })).toBeTruthy();
  });

  it('gives focus back to "N anggota" on Tutup and on Escape', () => {
    render(<RoomScreen session={fakeSession(host)} state={host} prefs={memoryStore()} />);
    const opener = screen.getByRole('button', { name: '2 anggota' });
    opener.focus();
    openSheet();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Tutup' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    openSheet();
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.querySelector('main.room')?.hasAttribute('inert')).toBe(false);
  });

  it('keeps the sheet open and focus in it after a confirmed kick removes the member', () => {
    const session = fakeSession(host);
    const view = render(<RoomScreen session={session} state={host} prefs={memoryStore()} />);
    openSheet();
    const kick = screen.getByRole('button', { name: 'Keluarkan: Sari' });
    kick.focus();
    fireEvent.click(kick);
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Keluarkan' }));
    expect(session.kick).toHaveBeenCalledWith('m2');
    const after = { ...host, room: { ...host.room, members: host.room.members.filter((m) => m.id !== 'm2') } };
    view.rerender(<RoomScreen session={session} state={after} prefs={memoryStore()} />);
    const dialog = screen.getByRole('dialog', { name: 'Anggota' });
    expect(document.activeElement?.id).toBe('members-title');
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(screen.getByRole('button', { name: '1 anggota', hidden: true })).toBeTruthy(); // behind the sheet
  });

  it('ends the room from the sheet after confirming', () => {
    const session = fakeSession(host);
    render(<RoomScreen session={session} state={host} prefs={memoryStore()} />);
    openSheet();
    fireEvent.click(screen.getByRole('button', { name: 'Akhiri Ritme untuk semua' }));
    fireEvent.click(within(screen.getByRole('alertdialog', { name: 'Akhiri Ritme untuk semua anggota?' })).getByRole('button', { name: 'Akhiri' }));
    expect(session.endRoom).toHaveBeenCalled();
  });
});
