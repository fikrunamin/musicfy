// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EndReason } from '../net/connection';
import { ConnectingScreen, EndScreen, type EndKind, endKind } from './status-screens';

afterEach(cleanup);

describe('endKind', () => {
  it.each<[EndReason, EndKind | null]>([
    [{ kind: 'lost' }, 'lost'],
    [{ kind: 'unreachable' }, 'unreachable'],
    [{ kind: 'closed' }, 'closed'],
    [{ kind: 'kicked' }, 'kicked'],
    [{ kind: 'rejected', code: 'UPGRADE_REQUIRED' }, 'upgrade'],
    // Leaving needs no screen, and the other refusals stay a banner on Home.
    [{ kind: 'left' }, null],
    [{ kind: 'rejected', code: 'ROOM_NOT_FOUND' }, null],
    [{ kind: 'rejected', code: 'ROOM_FULL' }, null],
    [{ kind: 'rejected', code: 'RATE_LIMITED' }, null],
    [{ kind: 'rejected', code: 'INVALID' }, null],
    [{ kind: 'rejected', code: 'UNKNOWN' }, null],
  ])('%j -> %s', (reason, kind) => {
    expect(endKind(reason)).toBe(kind);
  });
});

function renderEnd(kind: EndKind) {
  const actions = { onRetry: vi.fn(), onBack: vi.fn(), onReload: vi.fn() };
  render(<EndScreen kind={kind} {...actions} />);
  const buttons = screen.getAllByRole('button').map((b) => b.textContent);
  return { actions, buttons };
}

describe('EndScreen', () => {
  it('lost: title, hint, "Coba lagi" then "Kembali"', () => {
    const { actions, buttons } = renderEnd('lost');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Terputus dari Ritme');
    expect(screen.getByText('Periksa koneksi internetmu.')).toBeTruthy();
    expect(buttons).toEqual(['Coba lagi', 'Kembali']);
    fireEvent.click(screen.getByRole('button', { name: 'Coba lagi' }));
    expect(actions.onRetry).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    expect(actions.onBack).toHaveBeenCalledTimes(1);
    expect(actions.onReload).not.toHaveBeenCalled();
  });

  it('unreachable: its own title, same hint and buttons', () => {
    const { buttons } = renderEnd('unreachable');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Tidak bisa terhubung ke server Ritme');
    expect(screen.getByText('Periksa koneksi internetmu.')).toBeTruthy();
    expect(buttons).toEqual(['Coba lagi', 'Kembali']);
  });

  it('closed: "Ritme sudah diakhiri", only "Kembali"', () => {
    const { actions, buttons } = renderEnd('closed');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Ritme sudah diakhiri');
    expect(screen.getByText('Host mengakhiri Ritme untuk semua anggota.')).toBeTruthy();
    expect(buttons).toEqual(['Kembali']);
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    expect(actions.onBack).toHaveBeenCalledTimes(1);
  });

  it('kicked: "Kamu dikeluarkan dari Ritme", only "Kembali"', () => {
    const { actions, buttons } = renderEnd('kicked');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Kamu dikeluarkan dari Ritme');
    expect(screen.getByText('Host mengeluarkanmu dari room ini.')).toBeTruthy();
    expect(buttons).toEqual(['Kembali']);
    fireEvent.click(screen.getByRole('button', { name: 'Kembali' }));
    expect(actions.onBack).toHaveBeenCalledTimes(1);
  });

  it('upgrade: "Perlu versi baru", only "Muat ulang"', () => {
    const { actions, buttons } = renderEnd('upgrade');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Perlu versi baru');
    expect(screen.getByText('Room ini memakai versi Ritme yang lebih baru. Muat ulang halaman.')).toBeTruthy();
    expect(buttons).toEqual(['Muat ulang']);
    fireEvent.click(screen.getByRole('button', { name: 'Muat ulang' }));
    expect(actions.onReload).toHaveBeenCalledTimes(1);
    expect(actions.onBack).not.toHaveBeenCalled();
  });

  it('first action is the primary button', () => {
    renderEnd('lost');
    expect(screen.getByRole('button', { name: 'Coba lagi' }).classList.contains('btn-primary')).toBe(true);
    expect(screen.getByRole('button', { name: 'Kembali' }).classList.contains('btn-primary')).toBe(false);
    cleanup();
    renderEnd('kicked');
    expect(screen.getByRole('button', { name: 'Kembali' }).classList.contains('btn-primary')).toBe(true);
  });

  it('moves focus to the title so the reason is read out', () => {
    renderEnd('closed');
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 }));
  });

  it('hides the decorative disc from assistive tech', () => {
    const { container } = render(<EndScreen kind="lost" onRetry={() => {}} onBack={() => {}} onReload={() => {}} />);
    expect(container.querySelector('.status-disc')?.getAttribute('aria-hidden')).toBe('true');
  });
});

describe('ConnectingScreen', () => {
  it('shows the room code being joined', () => {
    render(<ConnectingScreen code="K7M2QX" />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Menghubungkan…');
    expect(screen.getByText('Room K7M2QX')).toBeTruthy();
    // Announced while it waits.
    expect(screen.getByRole('status').textContent).toContain('Menghubungkan…');
  });

  it('shows no room line while creating a room', () => {
    render(<ConnectingScreen code={null} />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Menghubungkan…');
    expect(screen.queryByText(/^Room /)).toBeNull();
  });

  it('draws the turning ring, hidden from assistive tech', () => {
    const { container } = render(<ConnectingScreen code="K7M2QX" />);
    const disc = container.querySelector('.status-disc');
    expect(disc?.getAttribute('aria-hidden')).toBe('true');
    expect(disc?.querySelector('.status-ring')).toBeTruthy();
  });
});
