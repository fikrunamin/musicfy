import { useState } from 'preact/hooks';
import type { RoomState } from '../protocol';
import { isHost } from '../session/permissions';
import type { SessionApi } from '../session/session';
import { ConfirmDialog, behindModal } from './sheet';
import { t } from './strings';

type Confirming = { kind: 'kick'; id: string; name: string } | { kind: 'end' } | null;

interface Props {
  session: SessionApi;
  room: RoomState;
  memberId: string | null;
  online: boolean;
}

export function MembersPanel({ session, room, memberId, online }: Props) {
  const host = online && isHost(room, memberId);
  const [confirming, setConfirming] = useState<Confirming>(null);
  return (
    <section class="card" aria-labelledby="members-title">
      <div {...behindModal(confirming !== null)}>
      <h2 id="members-title">
        {t.membersTitle} <span class="muted">· {room.members.length}</span>
      </h2>
      <ul class="members">
        {room.members.map((m) => (
          <li key={m.id} class={m.connected ? 'member' : 'member member-away'}>
            <span class="avatar" aria-hidden="true">
              {m.name.slice(0, 1).toUpperCase()}
            </span>
            <span class="member-name">
              {m.name} {m.id === memberId && <span class="muted">{t.youSuffix}</span>}
              {m.isHost && <span class="badge">{t.host}</span>}
              {!m.connected && <span class="muted small"> · {t.disconnected}</span>}
            </span>
            {host && m.id !== memberId && (
              <span class="member-actions">
                {m.connected && (
                  <button type="button" class="btn btn-small" onClick={() => session.transferHost(m.id)}>
                    {t.makeHost}
                  </button>
                )}
                <button type="button" class="btn btn-small btn-danger" onClick={() => setConfirming({ kind: 'kick', id: m.id, name: m.name })}>
                  {t.kick}
                </button>
              </span>
            )}
          </li>
        ))}
      </ul>
      {host && (
        <label class="switch">
          <input
            type="checkbox"
            role="switch"
            checked={room.settings.guestsCanControl}
            onChange={(e) => session.setGuestsCanControl(e.currentTarget.checked)}
          />
          <span>{t.guestsCanControl}</span>
        </label>
      )}
      {host && (
        <label class="switch">
          <input
            type="checkbox"
            role="switch"
            checked={room.settings.guestsCanAdd}
            onChange={(e) => session.setGuestsCanAdd(e.currentTarget.checked)}
          />
          <span>{t.guestsCanAdd}</span>
        </label>
      )}
      {host && (
        <button
          type="button"
          class="btn btn-danger btn-block"
          onClick={() => setConfirming({ kind: 'end' })}
        >
          {t.endRoom}
        </button>
      )}
      </div>
      {confirming?.kind === 'kick' && (
        <ConfirmDialog
          title={t.confirmKick(confirming.name)}
          confirmLabel={t.kick}
          onCancel={() => setConfirming(null)}
          onConfirm={() => {
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
    </section>
  );
}
