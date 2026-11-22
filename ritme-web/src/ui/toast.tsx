import { useEffect, useState } from 'preact/hooks';
import type { Notice } from '../session/reducer';
import { noticeText } from './strings';

/** Shows each new notice for 3 s in a polite live region. */
export function Toast({ notice }: { notice: Notice | null }) {
  const [visible, setVisible] = useState<Notice | null>(null);
  useEffect(() => {
    if (!notice) return;
    setVisible(notice);
    const id = setTimeout(() => setVisible(null), 3_000);
    return () => clearTimeout(id);
  }, [notice?.id]);
  return (
    <div class="toast-region" role="status" aria-live="polite">
      {visible && <div class="toast">{noticeText[visible.key]}</div>}
    </div>
  );
}
