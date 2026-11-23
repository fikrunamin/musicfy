import { useEffect, useState } from 'preact/hooks';
import type { Notice, NoticeKey } from '../session/reducer';
import { Icon, type IconName } from './icon';
import { noticeText } from './strings';

/** The icon each toast carries (handoff flow H, "Toast"). */
export const NOTICE_ICON: Record<NoticeKey, IconName> = {
  forbidden: 'lock',
  addForbidden: 'lock',
  rateLimited: 'timer',
  queueFull: 'queue_music',
  invalid: 'error',
  failed: 'error',
  linkCopied: 'check_circle',
};

export const TOAST_MS = 3_000;

/** Shows each new notice for 3 s at the top of the screen, in a polite live region. */
export function Toast({ notice }: { notice: Notice | null }) {
  const [visible, setVisible] = useState<Notice | null>(null);
  useEffect(() => {
    if (!notice) return;
    setVisible(notice);
    const id = setTimeout(() => setVisible(null), TOAST_MS);
    return () => clearTimeout(id);
  }, [notice?.id]);
  return (
    <div class="toast-region" role="status" aria-live="polite">
      {visible && (
        <div key={visible.id} class="toast">
          <Icon name={NOTICE_ICON[visible.key]} size={22} />
          <span>{noticeText[visible.key]}</span>
        </div>
      )}
    </div>
  );
}
