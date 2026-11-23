import type { ComponentChildren, RefObject } from 'preact';
import { useEffect, useId, useRef } from 'preact/hooks';
import { Button } from './components';
import { t } from './strings';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

function focusables(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => !el.closest('[hidden], [inert]'));
}

/**
 * Modal focus handling for sheets and dialogs: focus moves in (to `initial`, else the first
 * control), Tab wraps inside, Escape closes, the page behind does not scroll, and focus goes back
 * to whatever opened it.
 */
export function useModal(panel: RefObject<HTMLElement>, onClose: () => void, initial?: RefObject<HTMLElement>) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const root = panel.current!;
    (initial?.current ?? focusables(root)[0] ?? root).focus();
    document.body.classList.add('modal-open');
    // A dialog opened inside a sheet handles its own keys first and stops them there.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' && e.key !== 'Tab') return;
      e.stopPropagation();
      if (e.key === 'Escape') {
        close.current();
        return;
      }
      const items = focusables(root);
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    root.addEventListener('keydown', onKey);
    return () => {
      root.removeEventListener('keydown', onKey);
      document.body.classList.remove('modal-open');
      if (opener?.isConnected) opener.focus();
    };
  }, []);
}

/** Props for the page behind an open sheet: out of the tab order and hidden from screen readers. */
export function behindModal(open: boolean): { inert?: boolean; 'aria-hidden'?: 'true' } {
  return open ? { inert: true, 'aria-hidden': 'true' } : {};
}

interface SheetProps {
  /** id of the heading inside that names the sheet. */
  labelledBy: string;
  onClose: () => void;
  children: ComponentChildren;
  class?: string;
}

/**
 * Bottom sheet (handoff "Sheet"): r28 top corners, handle, rises in 280 ms. Render it only while
 * open; the parent passes behindModal(open) to the page content.
 */
export function Sheet({ labelledBy, onClose, children, class: cls }: SheetProps) {
  const panel = useRef<HTMLDivElement>(null);
  useModal(panel, onClose);
  return (
    <div class="sheet-layer">
      <div class="sheet-scrim" aria-hidden="true" onClick={onClose} />
      <div ref={panel} class={cls ? `sheet ${cls}` : 'sheet'} role="dialog" aria-modal="true" aria-labelledby={labelledBy} tabIndex={-1}>
        <div class="sheet-handle" aria-hidden="true" />
        {children}
      </div>
    </div>
  );
}

interface ConfirmProps {
  title: string;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/** "Keluarkan Budi dari Ritme?" with Batal / a danger action; Batal has the initial focus. */
export function ConfirmDialog({ title, confirmLabel, onConfirm, onCancel }: ConfirmProps) {
  const panel = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  useModal(panel, onCancel, cancel);
  return (
    <div class="sheet-layer dialog-layer">
      <div class="sheet-scrim" aria-hidden="true" onClick={onCancel} />
      <div ref={panel} class="dialog" role="alertdialog" aria-modal="true" aria-labelledby={titleId}>
        <h2 id={titleId} class="dialog-title">
          {title}
        </h2>
        <div class="dialog-actions">
          <button ref={cancel} type="button" class="btn btn-secondary" onClick={onCancel}>
            <span class="btn-label">{t.cancel}</span>
          </button>
          <Button variant="danger" onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
