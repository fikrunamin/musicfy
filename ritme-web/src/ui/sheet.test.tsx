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

  it('lets everything outside the sheet go inert, anywhere in the tree, and restores it on close', () => {
    function App() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <header>
            <button type="button">Kepala</button>
          </header>
          <div class="toast-region" role="status" aria-live="polite" />
          <main>
            <section>
              <button type="button" onClick={() => setOpen(true)}>
                Buka
              </button>
            </section>
            <aside>
              <button type="button">Samping</button>
            </aside>
          </main>
          {open && (
            <Sheet labelledBy="t" onClose={() => setOpen(false)}>
              <h2 id="t">Anggota</h2>
              <button type="button">Satu</button>
            </Sheet>
          )}
        </>
      );
    }
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }));
    for (const name of ['Kepala', 'Samping', 'Buka']) {
      const el = screen.getByText(name);
      expect(el.closest('[inert]')).not.toBeNull();
    }
    expect(document.querySelector('.toast-region')!.hasAttribute('inert')).toBe(false);
    expect(screen.getByRole('button', { name: 'Satu' }).closest('[inert]')).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.querySelectorAll('[inert]').length).toBe(0);
    expect(document.querySelectorAll('[aria-hidden]').length).toBe(0);
  });

  it('keeps the scroll lock until the last of two nested modals closes', () => {
    function Nested() {
      const [dlg, setDlg] = useState(true);
      return (
        <Sheet labelledBy="t" onClose={() => {}}>
          <h2 id="t">Anggota</h2>
          <button type="button">Satu</button>
          {dlg && <ConfirmDialog title="Yakin?" confirmLabel="Ya" onConfirm={() => {}} onCancel={() => setDlg(false)} />}
        </Sheet>
      );
    }
    render(<Nested />);
    expect(document.body.classList.contains('modal-open')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Batal' }));
    expect(document.body.classList.contains('modal-open')).toBe(true);
    cleanup();
    expect(document.body.classList.contains('modal-open')).toBe(false);
  });

  it('makes the sheet inert under a nested dialog and interactive again after Batal', () => {
    function Nested() {
      const [dlg, setDlg] = useState(false);
      return (
        <>
          <Sheet labelledBy="t" onClose={() => {}}>
            <h2 id="t">Anggota</h2>
            <button type="button" onClick={() => setDlg(true)}>
              Satu
            </button>
          </Sheet>
          {dlg && <ConfirmDialog title="Yakin?" confirmLabel="Ya" onConfirm={() => {}} onCancel={() => setDlg(false)} />}
        </>
      );
    }
    render(<Nested />);
    const sheet = document.querySelector('.sheet')!;
    expect(sheet.closest('[inert]')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Satu' }));
    expect(sheet.closest('[inert]')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Batal' }));
    expect(sheet.closest('[inert]')).toBeNull();
    expect(document.querySelectorAll('[inert]').length).toBe(0);
  });

  it('keeps handling Escape and Tab after focus dropped to body', () => {
    const onClose = vi.fn();
    render(
      <Sheet labelledBy="t" onClose={onClose}>
        <h2 id="t">Anggota</h2>
        <button type="button">Satu</button>
        <button type="button">Dua</button>
      </Sheet>,
    );
    (document.activeElement as HTMLElement).blur();
    expect(document.activeElement).toBe(document.body);
    fireEvent.keyDown(document.body, { key: 'Tab' });
    expect(document.querySelector('.sheet')!.contains(document.activeElement)).toBe(true);
    (document.activeElement as HTMLElement).blur();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
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

  it('traps Tab and survives focus dropping to body', () => {
    const { onCancel } = dialog();
    const batal = screen.getByRole('button', { name: 'Batal' });
    const ya = screen.getByRole('button', { name: 'Keluarkan' });
    ya.focus();
    fireEvent.keyDown(ya, { key: 'Tab' });
    expect(document.activeElement).toBe(batal);
    fireEvent.keyDown(batal, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(ya);
    expect(screen.getByRole('alertdialog').getAttribute('tabindex')).toBe('-1');
    (document.activeElement as HTMLElement).blur();
    fireEvent.keyDown(document.body, { key: 'Tab' });
    expect(screen.getByRole('alertdialog').contains(document.activeElement)).toBe(true);
    (document.activeElement as HTMLElement).blur();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('isolation bookkeeping', () => {
  const snapshot = () =>
    [...document.body.querySelectorAll('*')].map((e) => `${e.tagName}|${e.getAttribute('inert')}|${e.getAttribute('aria-hidden')}`).join('\n');

  function Nested() {
    const [outer, setOuter] = useState(true);
    const [inner, setInner] = useState(false);
    return (
      <>
        <main>
          <button type="button">Halaman</button>
        </main>
        {outer && (
          <Sheet labelledBy="t" onClose={() => setOuter(false)}>
            <h2 id="t">Luar</h2>
            <button type="button" onClick={() => setInner(true)}>Tombol</button>
            {inner && <ConfirmDialog title="Yakin?" confirmLabel="Ya" onConfirm={() => setInner(false)} onCancel={() => setInner(false)} />}
          </Sheet>
        )}
        <button type="button" data-testid="close-outer" onClick={() => setOuter(false)} />
      </>
    );
  }

  it('restores everything when a sheet unmounts while its dialog is still open', () => {
    render(<Nested />);
    fireEvent.click(screen.getByRole('button', { name: 'Tombol' }));
    expect(screen.getByRole('alertdialog')).toBeTruthy();
    expect(document.querySelector('main')?.hasAttribute('inert')).toBe(true);
    expect(document.querySelector('main')?.getAttribute('aria-hidden')).toBe('true');
    fireEvent.click(screen.getByTestId('close-outer'));
    expect(document.querySelector('main')?.hasAttribute('inert')).toBe(false);
    expect(document.querySelector('main')?.hasAttribute('aria-hidden')).toBe(false);
    expect(screen.getByTestId('close-outer').hasAttribute('inert')).toBe(false);
    expect(document.querySelectorAll('[inert]')).toHaveLength(0);
    expect(document.querySelectorAll('[aria-hidden="true"]')).toHaveLength(0);
  });

  function Two({ a, b }: { a: boolean; b: boolean }) {
    return (
      <>
        <main id="page">
          <button type="button">p</button>
        </main>
        {a && (
          <Sheet labelledBy="ta" onClose={() => {}}>
            <h2 id="ta">A</h2>
          </Sheet>
        )}
        {b && (
          <Sheet labelledBy="tb" onClose={() => {}}>
            <h2 id="tb">B</h2>
          </Sheet>
        )}
      </>
    );
  }

  it('returns to the original state when modals close out of order', () => {
    const { rerender } = render(<Two a={false} b={false} />);
    const original = snapshot();
    rerender(<Two a b={false} />);
    rerender(<Two a b />);
    rerender(<Two a={false} b />);
    expect(document.getElementById('page')?.hasAttribute('inert')).toBe(true); // B still open
    rerender(<Two a={false} b={false} />);
    expect(snapshot()).toBe(original);
  });

  it('keeps values that were already inert or aria-hidden before any modal opened', () => {
    const { rerender } = render(
      <>
        <div id="pre" inert aria-hidden="false" />
        <div id="hid" aria-hidden="true" />
        <Two a={false} b={false} />
      </>,
    );
    const open = (a: boolean, b: boolean) =>
      rerender(
        <>
          <div id="pre" inert aria-hidden="false" />
          <div id="hid" aria-hidden="true" />
          <Two a={a} b={b} />
        </>,
      );
    open(true, false);
    open(true, true);
    open(false, true);
    open(false, false);
    const pre = document.getElementById('pre')!;
    expect(pre.hasAttribute('inert')).toBe(true);
    expect(pre.getAttribute('aria-hidden')).toBe('false');
    expect(document.getElementById('hid')?.getAttribute('aria-hidden')).toBe('true');
    expect(document.getElementById('page')?.hasAttribute('inert')).toBe(false);
  });
});

describe('Shift+Tab from the panel root', () => {
  it('goes to the last control', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }));
    const root = screen.getByRole('dialog');
    root.focus();
    expect(document.activeElement).toBe(root);
    fireEvent.keyDown(root, { key: 'Tab', shiftKey: true });
    expect(document.activeElement?.textContent).toBe('Dua');
  });
});
