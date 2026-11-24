import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import type { EndReason } from '../net/connection';
import { Button } from './components';
import { Icon, type IconName } from './icon';
import { t } from './strings';

/** The ends that get a full screen (handoff H); everything else stays a banner on Home. */
export type EndKind = 'lost' | 'unreachable' | 'closed' | 'kicked' | 'upgrade';

export function endKind(reason: EndReason): EndKind | null {
  switch (reason.kind) {
    case 'lost':
    case 'unreachable':
    case 'closed':
    case 'kicked':
      return reason.kind;
    case 'rejected':
      return reason.code === 'UPGRADE_REQUIRED' ? 'upgrade' : null;
    case 'left':
      return null;
  }
}

type Action = 'retry' | 'back' | 'reload';

/** Icon, copy and buttons per screen; the first button is the primary one. */
const END: Record<EndKind, { icon: IconName; title: string; body: string; actions: Action[] }> = {
  lost: { icon: 'wifi_off', title: t.lostTitle, body: t.checkConnection, actions: ['retry', 'back'] }, // lrH2k / GfryT
  unreachable: { icon: 'wifi_off', title: t.unreachableTitle, body: t.checkConnection, actions: ['retry', 'back'] },
  closed: { icon: 'stop_circle', title: t.closedTitle, body: t.closedBody, actions: ['back'] }, // s9H6eO
  kicked: { icon: 'person_remove', title: t.kickedTitle, body: t.kickedBody, actions: ['back'] }, // H2D5o
  upgrade: { icon: 'update', title: t.upgradeTitle, body: t.upgradeBody, actions: ['reload'] }, // copmC
};

const LABEL: Record<Action, string> = { retry: t.retry, back: t.back, reload: t.reload };

interface EndProps {
  kind: EndKind;
  onRetry: () => void;
  onBack: () => void;
  onReload: () => void;
}

/**
 * The full-screen layout of handoff H: the disc, the title, then the rest. It replaces whatever
 * had focus, so focus starts on the title and the screen is read out from there.
 */
function StatusScreen({ icon, title, ring, children }: { icon: IconName; title: string; ring?: boolean; children: ComponentChildren }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => heading.current?.focus(), [title]);
  return (
    <main class="status-screen">
      <div class="status-body" role={ring ? 'status' : undefined}>
        <div class="status-disc" aria-hidden="true">
          {ring && <span class="status-ring" />}
          <Icon name={icon} size={32} />
        </div>
        <h1 ref={heading} class="status-title" tabIndex={-1}>
          {title}
        </h1>
        {children}
      </div>
    </main>
  );
}

/** A session that cannot go on: why, and what to do next. */
export function EndScreen({ kind, onRetry, onBack, onReload }: EndProps) {
  const { icon, title, body, actions } = END[kind];
  const handlers: Record<Action, () => void> = { retry: onRetry, back: onBack, reload: onReload };
  return (
    <StatusScreen icon={icon} title={title}>
      <p class="status-text">{body}</p>
      <div class="status-actions">
        {actions.map((action, i) => (
          <Button key={action} variant={i === 0 ? 'primary' : 'secondary'} block onClick={handlers[action]}>
            {LABEL[action]}
          </Button>
        ))}
      </div>
    </StatusScreen>
  );
}

/** Waiting for the first Welcome (handoff b3gHO): a disc with a turning ring, still under reduced motion. */
export function ConnectingScreen({ code }: { code: string | null }) {
  return (
    <StatusScreen icon="radio_button_checked" title={t.connecting} ring>
      {code && <p class="status-text">{t.roomChip(code)}</p>}
    </StatusScreen>
  );
}
