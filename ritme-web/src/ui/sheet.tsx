import type { ComponentChildren, RefObject } from 'preact';
import { useEffect, useId, useRef } from 'preact/hooks';
import { Button } from './components';
import { t } from './strings';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

function focusables(root: HTMLElement): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => !el.closest('[hidden], [inert]'));
}

interface ModalEntry {
  root: HTMLElement;
  onKey: (e: KeyboardEvent) => void;
}

/** Open modals, innermost last; only the last one reacts to keys. */
const stack: ModalEntry[] = [];
let scrollLocks = 0;

/** Keeps assistive tech reading toasts while a modal is open. */
const isLive = (el: Element) => el.getAttribute('role') === 'status' || el.hasAttribute('aria-live');

/** What an element looked like before any modal touched it, and how many modals are holding it. */
const held = new Map<Element, { count: number; inert: string | null; hidden: string | null }>();

function acquire(el: Element) {
  const h = held.get(el);
  if (h) h.count += 1;
  else held.set(el, { count: 1, inert: el.getAttribute('inert'), hidden: el.getAttribute('aria-hidden') });
  el.setAttribute('inert', '');
  el.setAttribute('aria-hidden', 'true');
}

function release(el: Element) {
  const h = held.get(el);
  if (!h) return;
  h.count -= 1;
  if (h.count > 0) return;
  held.delete(el);
  if (h.inert === null) el.removeAttribute('inert');
  else el.setAttribute('inert', h.inert);
  if (h.hidden === null) el.removeAttribute('aria-hidden');
  else el.setAttribute('aria-hidden', h.hidden);
}

/**
 * Makes everything outside `panel` inert and aria-hidden without moving any node: at each level
 * from the panel up to body, every sibling that is not on the path gets it. Reference-counted per
 * element, so modals may close in any order: the last release puts back the original values.
 */
function isolate(panel: HTMLElement): () => void {
  const mine: Element[] = [];
  let node: HTMLElement = panel;
  while (node.parentElement && node !== document.body) {
    const parent: HTMLElement = node.parentElement;
    for (const sib of Array.from(parent.children)) {
      if (sib === node || sib.tagName === 'SCRIPT' || sib.tagName === 'STYLE' || isLive(sib) || sib.classList.contains('sheet-scrim')) continue; // the scrim is the modal's own tap-to-close
      acquire(sib);
      mine.push(sib);
    }
    node = parent;
  }
  return () => mine.forEach(release);
}

/**
 * Modal focus handling for sheets and dialogs: focus moves in (to `initial`, else the first
 * control), Tab wraps inside, Escape closes (both listened for on the document, so they work
 * wherever focus is, for the topmost modal only), everything outside is inert, the page behind
 * does not scroll, and focus goes back to whatever opened it, or to `fallback` if that is gone.
 */
export function useModal(
  panel: RefObject<HTMLElement>,
  onClose: () => void,
  initial?: RefObject<HTMLElement>,
  fallback?: RefObject<HTMLElement>,
) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const root = panel.current!;
    const unisolate = isolate(root);
    (initial?.current ?? focusables(root)[0] ?? root).focus();
    scrollLocks += 1;
    document.body.classList.add('modal-open');
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' && e.key !== 'Tab') return;
      if (stack[stack.length - 1] !== entry) return;
      if (e.key === 'Escape') {
        close.current();
        return;
      }
      const items = focusables(root);
      if (items.length === 0) {
        e.preventDefault();
        root.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (!root.contains(active) || active === root) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
      } else if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    const entry: ModalEntry = { root, onKey };
    stack.push(entry);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      stack.splice(stack.indexOf(entry), 1);
      unisolate();
      scrollLocks -= 1;
      if (scrollLocks <= 0) {
        scrollLocks = 0;
        document.body.classList.remove('modal-open');
      }
      if (opener?.isConnected) opener.focus();
      else fallback?.current?.focus();
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
 * Bottom sheet (handoff "Sheet"): r28 top corners, handle. Render it only while open; whatever
 * is outside it goes inert by itself (see useModal).
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
  /** Where focus goes on close if what opened the dialog is gone. */
  returnFocus?: RefObject<HTMLElement>;
}

/** "Keluarkan Budi dari Ritme?" with Batal / a danger action; Batal has the initial focus. */
export function ConfirmDialog({ title, confirmLabel, onConfirm, onCancel, returnFocus }: ConfirmProps) {
  const panel = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  useModal(panel, onCancel, cancel, returnFocus);
  return (
    <div class="sheet-layer dialog-layer">
      <div class="sheet-scrim" aria-hidden="true" onClick={onCancel} />
      <div ref={panel} class="dialog" role="alertdialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
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
