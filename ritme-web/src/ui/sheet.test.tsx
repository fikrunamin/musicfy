// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { useState } from 'preact/hooks';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmDialog, Sheet, behindModal } from './sheet';

afterEach(cleanup);

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <main {...behindModal(open)}>
        <button type="button" onClick={() => setOpen(true)}>
          Buka
        </button>
      </main>
      {open && (
        <Sheet labelledBy="sheet-title" onClose={() => setOpen(false)}>
          <h2 id="sheet-title">Anggota</h2>
          <button type="button">Satu</button>
          <button type="button">Dua</button>
        </Sheet>
      )}
    </>
  );
}

describe('Sheet', () => {
  it('opens as a labelled modal dialog, focuses inside, hides the page behind and locks its scroll', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }));
    const dialog = screen.getByRole('dialog', { name: 'Anggota' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.activeElement?.textContent).toBe('Satu');
    expect(document.querySelector('main')?.getAttribute('aria-hidden')).toBe('true');
    expect(document.querySelector('main')?.hasAttribute('inert')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Buka' })).toBeNull(); // hidden from the accessibility tree
    expect(document.body.classList.contains('modal-open')).toBe(true);
  });

  it('closes on Escape and gives focus back to the opener', () => {
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Buka' });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.body.classList.contains('modal-open')).toBe(false);
    expect(document.querySelector('main')?.hasAttribute('aria-hidden')).toBe(false);
  });

  it('closes when the scrim is tapped', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }));
    fireEvent.click(document.querySelector('.sheet-scrim')!);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps Tab inside the sheet', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }));
    const [one, two] = screen.getAllByRole('button');
    two.focus();
    fireEvent.keyDown(two, { key: 'Tab' });
    expect(document.activeElement).toBe(one);
    fireEvent.keyDown(one, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(two);
  });
});

describe('ConfirmDialog', () => {
  const dialog = (onConfirm = vi.fn(), onCancel = vi.fn()) => {
    render(<ConfirmDialog title="Keluarkan Sari dari Ritme?" confirmLabel="Keluarkan" onConfirm={onConfirm} onCancel={onCancel} />);
    return { onConfirm, onCancel };
  };

  it('is an alertdialog named by its question with Batal focused first', () => {
    dialog();
    expect(screen.getByRole('alertdialog', { name: 'Keluarkan Sari dari Ritme?' })).toBeTruthy();
    expect(document.activeElement?.textContent).toBe('Batal');
  });

  it('confirms only through the danger button', () => {
    const { onConfirm, onCancel } = dialog();
    fireEvent.click(screen.getByRole('button', { name: 'Keluarkan' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('cancels with Batal, Escape and the scrim', () => {
    const { onConfirm, onCancel } = dialog();
    fireEvent.click(screen.getByRole('button', { name: 'Batal' }));
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' });
    fireEvent.click(document.querySelector('.sheet-scrim')!);
    expect(onCancel).toHaveBeenCalledTimes(3);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
