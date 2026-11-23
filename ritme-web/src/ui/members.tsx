import type { ComponentChildren } from 'preact';
import { useEffect, useId, useRef, useState } from 'preact/hooks';
import type { Member, RoomState } from '../protocol';
import { isHost } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { Avatar, Badge, Button, IconButton, cx } from './components';
import { copyLink, roomLinkLabel } from './share';
import { ConfirmDialog } from './sheet';
import { t } from './strings';

type Confirming = { kind: 'kick'; id: string; name: string } | { kind: 'end' } | null;

/**
 * sheet: the phone's members sheet (handoff G), with "Tutup". panel: the same content inline (the
 * desktop column). compact: the heading and the list only, no settings or actions (theater side).
 */
export type MembersVariant = 'sheet' | 'panel' | 'compact';

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  online: boolean;
  variant?: MembersVariant;
  /** Shows the close button (the panel is in a sheet). */
  onClose?: () => void;
}

/**
 * Anggota (handoff G, J5QAxI/k9N6F): the host's switch cards, the member rows (with the host's
 * icon actions), the share card, then "Keluar Ritme" and, for the host, "Akhiri Ritme untuk semua".
 * Kick and end are confirmed in an in-page dialog.
 */
export function MembersPanel({ session, room, memberId, online, variant = 'panel', onClose }: Props) {
  const compact = variant === 'compact';
  const host = online && isHost(room, memberId) && !compact;
  const [confirming, setConfirming] = useState<Confirming>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  /** Set by an action whose result removes the focused button: a kick, or handing over the host role. */
  const refocus = useRef(false);
  // The button disappears when the room update arrives, after the dialog closed or the tap; if that
  // dropped focus to the page, bring it back to the heading (still inside the sheet).
  useEffect(() => {
    if (!refocus.current) return;
    refocus.current = false; // only the first members change after the action counts
    if (!document.activeElement || document.activeElement === document.body) heading.current?.focus();
  }, [room.members]);

  const body = (
    <div class="members-body">
      <div class="sheet-header members-header">
        <h2 id="members-title" ref={heading} tabIndex={-1}>
          {t.membersTitle}
        </h2>
        <span class="members-count">{t.members(room.members.length)}</span>
        {onClose && <IconButton icon="close" label={t.close} variant="filled" onClick={onClose} />}
      </div>
      {host && (
        <SwitchCard
          title={t.guestsCanControl}
          hint={t.guestsCanControlHint}
          checked={room.settings.guestsCanControl}
          onChange={(on) => session.setGuestsCanControl(on)}
        />
      )}
      {host && (
        <SwitchCard
          title={t.guestsCanAdd}
          hint={t.guestsCanAddHint}
          checked={room.settings.guestsCanAdd}
          onChange={(on) => session.setGuestsCanAdd(on)}
        />
      )}
      <ul class="members">
        {room.members.map((m) => (
          <MemberRow key={m.id} member={m} you={m.id === memberId}>
            {host && m.id !== memberId && (
              <span class="member-actions">
                {/* The server hands the role only to someone connected. */}
                <IconButton
                  icon="star"
                  label={t.makeHostFor(m.name)}
                  variant="filled"
                  disabled={!m.connected}
                  onClick={() => {
                    refocus.current = true;
                    session.transferHost(m.id);
                  }}
                />
                <IconButton
                  icon="person_remove"
                  label={t.kickFor(m.name)}
                  variant="danger"
                  onClick={() => setConfirming({ kind: 'kick', id: m.id, name: m.name })}
                />
              </span>
            )}
          </MemberRow>
        ))}
      </ul>
      {!compact && <ShareCard code={room.code} session={session} />}
      {!compact && (
        <div class="members-leave">
          <Button icon="logout" block onClick={() => session.leave()}>
            {t.leave}
          </Button>
          {host && (
            <Button variant="danger" icon="power_settings_new" block onClick={() => setConfirming({ kind: 'end' })}>
              {t.endRoom}
            </Button>
          )}
        </div>
      )}
    </div>
  );

  const dialogs = (
    <>
      {confirming?.kind === 'kick' && (
        <ConfirmDialog
          title={t.confirmKick(confirming.name)}
          confirmLabel={t.kick}
          onCancel={() => setConfirming(null)}
          returnFocus={heading}
          onConfirm={() => {
            refocus.current = true;
            session.kick(confirming.id);
            setConfirming(null);
          }}
        />
      )}
      {confirming?.kind === 'end' && (
        <ConfirmDialog
          title={t.confirmEnd}
          confirmLabel={t.endConfirm}
          onCancel={() => setConfirming(null)}
          onConfirm={() => {
            session.endRoom();
            setConfirming(null);
          }}
        />
      )}
    </>
  );

  // In a sheet the dialog names itself after the heading; inline, the section does.
  if (variant === 'sheet') {
    return (
      <div class="members-panel">
        {body}
        {dialogs}
      </div>
    );
  }
  return (
    <section class={cx('members-panel', compact && 'members-compact')} aria-labelledby="members-title">
      {body}
      {dialogs}
    </section>
  );
}

/** A 60 px row: 44 px avatar, the name with "(kamu)" and the Host badge, the status under it. */
function MemberRow({ member: m, you, children }: { member: Member; you: boolean; children?: ComponentChildren }) {
  const status = !m.connected ? t.disconnected : m.isHost ? t.hostRoom : t.connected;
  return (
    <li class={cx('member', !m.connected && 'member-away')}>
      <Avatar name={m.name} size="lg" />
      <span class="member-text">
        <span class="member-name">
          <span class="member-label">
            <span>{m.name}</span> {you && <span>{t.youSuffix}</span>}
          </span>
          {m.isHost && <Badge kind="host" />}
        </span>
        <span class="member-status">{status}</span>
      </span>
      {children}
    </li>
  );
}

interface SwitchCardProps {
  title: string;
  hint: string;
  checked: boolean;
  onChange: (on: boolean) => void;
}

/**
 * A host setting as a card: the switch is named by the title and described by the hint, and the
 * whole card toggles it (it is the switch's label). The input is 52 × 44, its track drawn in CSS.
 */
function SwitchCard({ title, hint, checked, onChange }: SwitchCardProps) {
  const id = useId();
  return (
    <label class="switch-card">
      <span class="switch-text">
        <span id={`${id}-title`} class="switch-title">
          {title}
        </span>
        <span id={`${id}-hint`} class="switch-hint">
          {hint}
        </span>
      </span>
      <input
        type="checkbox"
        role="switch"
        class="switch-input"
        checked={checked}
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-hint`}
        onChange={(e) => onChange(e.currentTarget.checked)}
      />
    </label>
  );
}

/** "Bagikan kode": the room code at 40 px, the link, and a round "Salin link". */
function ShareCard({ code, session }: { code: string; session: SessionApi }) {
  const id = useId();
  return (
    <div class="share-card" role="group" aria-labelledby={`${id}-share`}>
      <div class="share-text">
        <p id={`${id}-share`} class="share-caption">
          {t.shareCode}
        </p>
        <p class="share-code">{code}</p>
        <p class="share-link">{roomLinkLabel(code)}</p>
      </div>
      <IconButton icon="content_copy" label={t.copyLink} variant="accent" onClick={() => void copyLink(code, session)} />
    </div>
  );
}
