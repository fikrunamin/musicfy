import { useEffect, useRef, useState } from 'preact/hooks';
import type { KeyValueStore } from '../net/storage';
import { NAME_KEY } from '../net/storage';
import type { SessionState } from '../session/reducer';
import type { SessionApi } from '../session/session';
import { Button, FieldError, LinkButton, TextField, cx } from './components';
import { normalizeCode, normalizeName, sanitizeCodeInput } from './format';
import { Icon } from './icon';
import { RELEASES_URL, refusalText, t } from './strings';

interface Props {
  session: SessionApi;
  state: SessionState;
  /** From /r/{code}: the invite page (flow B), joining that room. */
  routeCode: string | null;
  prefs: KeyValueStore;
  /** The code field's starting value at / (the code of a refused join, to fix it). */
  initialCode?: string | null;
  /** Each start that passed validation, just before it goes out: the room code, or null for a new room. */
  onStart?: (code: string | null) => void;
  /** Home replaces another screen (an ended session): focus starts on the title, not on <body>. */
  focusTitle?: boolean;
}

type FieldErrorState = { field: 'name' | 'code'; message: string } | null;

/** The vinyl and sleeve of flows A/B, drawn in CSS (monochrome: no artwork before a room). */
function HeroArt() {
  return (
    <div class="home-art" aria-hidden="true">
      <div class="sleeve" />
      <div class="vinyl">
        <div class="vinyl-label" />
      </div>
    </div>
  );
}

function DownloadLine() {
  return (
    <p class="home-download meta">
      {t.noApp}{' '}
      <a href={RELEASES_URL} rel="noopener noreferrer">
        {t.download}
      </a>
    </p>
  );
}

export function HomeScreen({ session, state, routeCode, prefs, initialCode, onStart, focusTitle }: Props) {
  const [name, setName] = useState(prefs.get(NAME_KEY) ?? '');
  const [code, setCode] = useState(routeCode ?? initialCode ?? '');
  const [error, setError] = useState<FieldErrorState>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  // Only refusals and leaving end on Home (App shows the other ends as screens of their own).
  const ended = state.phase === 'ended' && state.ended?.kind === 'rejected' ? refusalText(state.ended.code) : null;
  const invite = routeCode !== null;

  useEffect(() => {
    if (focusTitle && (!document.activeElement || document.activeElement === document.body)) title.current?.focus();
  }, []);

  // Move keyboard and screen-reader users to the field that needs fixing.
  useEffect(() => {
    if (error) (error.field === 'name' ? nameRef : codeRef).current?.focus();
  }, [error]);

  const validName = (): string | null => {
    const n = normalizeName(name);
    if (!n) setError({ field: 'name', message: t.nameInvalid });
    else prefs.set(NAME_KEY, n);
    return n;
  };

  const onCreate = () => {
    setError(null);
    const n = validName();
    if (!n) return;
    onStart?.(null);
    session.dismiss();
    session.create(n);
  };

  const onJoin = (e: Event) => {
    e.preventDefault();
    setError(null);
    const n = validName();
    if (!n) return;
    const c = invite ? routeCode : normalizeCode(code);
    if (!c) {
      setError({ field: 'code', message: t.codeInvalid });
      return;
    }
    onStart?.(c);
    session.dismiss();
    session.join(c, n);
  };

  const nameError = error?.field === 'name' ? { id: 'name-error', message: error.message } : null;
  const codeError = error?.field === 'code' ? { id: 'code-error', message: error.message } : null;

  const nameField = (
    <>
      <TextField
        id="name-input"
        name="name"
        label={t.nameLabel}
        value={name}
        maxLength={32}
        autoComplete="nickname"
        placeholder={t.namePlaceholder}
        errorId={nameError?.id}
        inputRef={nameRef}
        onInput={(e) => setName(e.currentTarget.value)}
      />
      {nameError && <FieldError id={nameError.id}>{nameError.message}</FieldError>}
    </>
  );

  return (
    <main class={cx('home', invite && 'home-invite')}>
      {invite && (
        <p class="room-chip">
          <Icon name="group" size={20} />
          {t.roomChip(routeCode)}
        </p>
      )}
      <HeroArt />
      <div class="home-panel">
        {invite ? null : (
          <p class="home-brand">
            <Icon name="radio_button_checked" size={22} />
            <span class="home-brand-mobile">{t.brand}</span>
            <span class="home-brand-desktop">{t.brandRitme}</span>
          </p>
        )}
        <header class="home-header">
          <h1 ref={title} tabIndex={-1}>
            {invite ? t.invited : t.appName}
          </h1>
          <p class="home-tagline">{t.tagline}</p>
        </header>

        {ended && (
          <p class="home-alert" role="alert">
            {ended}
          </p>
        )}

        {invite ? (
          <form class="home-form" onSubmit={onJoin}>
            {nameField}
            <Button type="submit" variant="primary" block>
              {t.join}
            </Button>
            <hr class="home-rule" />
            <LinkButton href={`musicfy://ritme/${routeCode}`} icon="open_in_new" block>
              {t.openInApp}
            </LinkButton>
            <DownloadLine />
          </form>
        ) : (
          <div class="home-form">
            {nameField}
            <Button variant="primary" block onClick={onCreate}>
              {t.start}
            </Button>
            <p class="divider">
              <span>{t.or}</span>
            </p>
            <form class="code-form" onSubmit={onJoin}>
              <div class="code-row">
                <TextField
                  id="code-input"
                  name="code"
                  class="code-field"
                  label={t.codeLabel}
                  value={code}
                  autoCapitalize="characters"
                  autoComplete="off"
                  spellcheck={false}
                  placeholder={t.codePlaceholder}
                  errorId={codeError?.id}
                  inputRef={codeRef}
                  onInput={(e) => {
                    const sanitized = sanitizeCodeInput(e.currentTarget.value);
                    e.currentTarget.value = sanitized; // state may be unchanged (7th char), so Preact would not re-render
                    setCode(sanitized);
                  }}
                />
                <Button type="submit">{t.join}</Button>
              </div>
              {codeError && <FieldError id={codeError.id}>{codeError.message}</FieldError>}
            </form>
            <DownloadLine />
          </div>
        )}
      </div>
    </main>
  );
}
