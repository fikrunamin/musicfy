import { useState } from 'preact/hooks';
import type { KeyValueStore } from '../net/storage';
import { NAME_KEY } from '../net/storage';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { normalizeCode, normalizeName, sanitizeCodeInput } from './format';
import { RELEASES_URL, endText, t } from './strings';

interface Props {
  session: SessionApi;
  state: SessionState;
  /** From /r/{code}; prefills the code and shows the "open in app" button. */
  routeCode: string | null;
  prefs: KeyValueStore;
}

export function HomeScreen({ session, state, routeCode, prefs }: Props) {
  const [name, setName] = useState(prefs.get(NAME_KEY) ?? '');
  const [code, setCode] = useState(routeCode ?? '');
  const [error, setError] = useState<string | null>(null);
  const busy = state.phase === 'connecting';
  const ended = state.phase === 'ended' && state.ended ? endText(state.ended) : null;

  const validName = (): string | null => {
    const n = normalizeName(name);
    if (!n) setError(t.nameInvalid);
    else prefs.set(NAME_KEY, n);
    return n;
  };

  const onCreate = () => {
    setError(null);
    const n = validName();
    if (!n) return;
    session.dismiss();
    session.create(n);
  };

  const onJoin = (e: Event) => {
    e.preventDefault();
    setError(null);
    const n = validName();
    if (!n) return;
    const c = normalizeCode(code);
    if (!c) {
      setError(t.codeInvalid);
      return;
    }
    session.dismiss();
    session.join(c, n);
  };

  return (
    <main class="screen home">
      <header class="home-header">
        <h1>{routeCode ? t.invited : t.appName}</h1>
        <p class="muted">{t.tagline}</p>
      </header>

      {ended && (
        <p class="banner banner-error" role="alert">
          {ended}
        </p>
      )}

      <div class="card stack">
        <label class="field">
          <span>{t.nameLabel}</span>
          <input
            name="name"
            value={name}
            maxLength={32}
            autoComplete="nickname"
            placeholder={t.namePlaceholder}
            onInput={(e) => setName(e.currentTarget.value)}
          />
        </label>

        {!routeCode && (
          <>
            <button type="button" class="btn btn-primary" disabled={busy} onClick={onCreate}>
              {t.start}
            </button>
            <p class="divider">
              <span>{t.or}</span>
            </p>
          </>
        )}

        <form class="stack" onSubmit={onJoin}>
          <label class="field">
            <span>{t.codeLabel}</span>
            <input
              name="code"
              class="code-input"
              value={code}
              autoCapitalize="characters"
              autoComplete="off"
              spellcheck={false}
              placeholder={t.codePlaceholder}
              onInput={(e) => {
                const sanitized = sanitizeCodeInput(e.currentTarget.value);
                e.currentTarget.value = sanitized; // state may be unchanged (7th char), so Preact would not re-render
                setCode(sanitized);
              }}
            />
          </label>
          <button type="submit" class={routeCode ? 'btn btn-primary' : 'btn'} disabled={busy}>
            {busy ? t.connecting : t.join}
          </button>
        </form>

        {error && (
          <p class="error" role="alert">
            {error}
          </p>
        )}
      </div>

      {routeCode && (
        <div class="card stack center">
          <a class="btn" href={`musicfy://ritme/${routeCode}`}>
            {t.openInApp}
          </a>
          <p class="muted small">
            {t.noApp}{' '}
            <a href={RELEASES_URL} rel="noopener noreferrer">
              {t.download}
            </a>
          </p>
        </div>
      )}
    </main>
  );
}
