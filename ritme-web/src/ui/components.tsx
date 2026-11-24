import type { ComponentChildren, JSX, Ref } from 'preact';
import { useRef } from 'preact/hooks';
import { Icon, type IconName } from './icon';
import { useMarquee } from './motion';
import { t } from './strings';

/** Joins the truthy class names. */
export function cx(...names: (string | false | null | undefined)[]): string {
  return names.filter(Boolean).join(' ');
}

export type ButtonVariant = 'primary' | 'secondary' | 'danger';

type ButtonProps = Omit<JSX.ButtonHTMLAttributes<HTMLButtonElement>, 'icon' | 'type'> & {
  variant?: ButtonVariant;
  icon?: IconName;
  block?: boolean;
  type?: 'button' | 'submit';
};

/** Pill button, 52 px high (handoff "Tombol Utama / Kedua / Bahaya"). */
export function Button({ variant = 'secondary', icon, block, type = 'button', class: cls, children, ...rest }: ButtonProps) {
  return (
    <button type={type} class={cx('btn', `btn-${variant}`, block && 'btn-block', cls as string)} {...rest}>
      {icon && <Icon name={icon} size={20} />}
      <span class="btn-label">{children}</span>
    </button>
  );
}

type LinkButtonProps = Omit<JSX.AnchorHTMLAttributes<HTMLAnchorElement>, 'icon'> & { variant?: ButtonVariant; icon?: IconName; block?: boolean; href: string };

export function LinkButton({ variant = 'secondary', icon, block, class: cls, children, ...rest }: LinkButtonProps) {
  return (
    <a class={cx('btn', `btn-${variant}`, block && 'btn-block', cls as string)} {...rest}>
      {icon && <Icon name={icon} size={20} />}
      <span class="btn-label">{children}</span>
    </a>
  );
}

export type IconButtonVariant = 'plain' | 'filled' | 'accent' | 'danger';

type IconButtonProps = Omit<JSX.ButtonHTMLAttributes<HTMLButtonElement>, 'icon' | 'label' | 'size' | 'type'> & {
  icon: IconName;
  /** The accessible name (the icon is decorative). */
  label: string;
  variant?: IconButtonVariant;
  /** md: 44 px target, lg: the 64 px play button. */
  size?: 'md' | 'lg';
  type?: 'button' | 'submit';
};

export function IconButton({ icon, label, variant = 'plain', size = 'md', type = 'button', class: cls, ...rest }: IconButtonProps) {
  return (
    <button type={type} class={cx('icon-btn', `icon-btn-${variant}`, size === 'lg' && 'icon-btn-lg', cls as string)} {...rest} aria-label={label}>
      <Icon name={icon} size={size === 'lg' ? 32 : 24} />
    </button>
  );
}

type TextFieldProps = Omit<JSX.InputHTMLAttributes<HTMLInputElement>, 'label'> & {
  label: string;
  id: string;
  /** Label only for screen readers (the desktop search field shows an icon instead). */
  hideLabel?: boolean;
  /** The id of the FieldError that explains why the value is invalid. */
  errorId?: string | null;
  inputRef?: Ref<HTMLInputElement>;
  leadingIcon?: IconName;
};

/** Label above a 52 px r16 field (handoff "Input"). */
export function TextField({ label, id, hideLabel, errorId, inputRef, leadingIcon, class: cls, ...input }: TextFieldProps) {
  return (
    <div class={cx('field', cls as string)}>
      <label for={id} class={hideLabel ? 'visually-hidden' : 'field-label'}>
        {label}
      </label>
      <div class={cx('input-wrap', leadingIcon && 'input-wrap-icon')}>
        {leadingIcon && <Icon name={leadingIcon} size={20} class="input-icon" />}
        <input
          id={id}
          ref={inputRef}
          class="input"
          aria-invalid={errorId ? 'true' : undefined}
          aria-describedby={errorId ?? undefined}
          {...input}
        />
      </div>
    </div>
  );
}

/** A validation message under a field: danger text with an error icon, announced at once. */
export function FieldError({ id, children }: { id?: string; children: ComponentChildren }) {
  return (
    <p id={id} class="field-error" role="alert">
      <Icon name="error" size={18} />
      <span>{children}</span>
    </p>
  );
}

export type BadgeKind = 'video' | 'live' | 'host';

/** Video / LIVE (red dot) / Host (handoff "Lencana"). */
export function Badge({ kind, class: cls }: { kind: BadgeKind; class?: string }) {
  const text = kind === 'video' ? t.videoBadge : kind === 'live' ? t.live : t.host;
  return (
    <span class={cx('badge', `badge-${kind}`, cls)}>
      {kind === 'live' && <span class="badge-dot" aria-hidden="true" />}
      {text}
    </span>
  );
}

/** First character of a name (emoji-safe), uppercased. */
export function initialOf(name: string): string {
  return Array.from(name.trim())[0]?.toUpperCase() ?? '?';
}

/** Circle with the member's initial (handoff "Avatar"): xs 22, sm 32, md 36, lg 44 px. Decorative: the name is next to it. */
export function Avatar({ name, size = 'sm' }: { name: string; size?: 'xs' | 'sm' | 'md' | 'lg' }) {
  return (
    <span class={`avatar avatar-${size}`} aria-hidden="true">
      {initialOf(name)}
    </span>
  );
}

/** The first three members, overlapped (room header). */
export function AvatarStack({ names }: { names: string[] }) {
  return (
    <span class="avatar-stack" aria-hidden="true">
      {names.slice(0, 3).map((name, i) => (
        <Avatar key={i} name={name} size="md" />
      ))}
    </span>
  );
}

/** 24:18 on a thumbnail or placeholder. */
export function DurationChip({ children }: { children: ComponentChildren }) {
  return <span class="chip-duration">{children}</span>;
}

/** The small status dot next to "Tersinkron" (pulses, see motion.css). */
export function SyncDot({ state }: { state: 'online' | 'reconnecting' }) {
  return <span class="sync-dot" data-state={state} aria-hidden="true" />;
}

/** Equalizer bars that mark what is playing; they move only while the room plays. */
export function EqBars({ playing }: { playing: boolean }) {
  return (
    <span class="eq" data-playing={playing ? 'true' : 'false'} aria-hidden="true">
      <i />
      <i />
      <i />
      <i />
    </span>
  );
}

/**
 * One-line meta ("Meta Clip"): cut with an ellipsis at rest. When it is cut, motion.css scrolls
 * it to the end and back (marquee): by itself with `marquee="auto"` (the "Sedang diputar" row),
 * on hover or focus of the row otherwise; never with reduced motion (DECISIONS 3).
 */
export function MetaClip({ children, marquee = 'hover' }: { children: ComponentChildren; marquee?: 'auto' | 'hover' }) {
  const clip = useRef<HTMLSpanElement>(null);
  const motion = useMarquee(clip, marquee === 'auto');
  return (
    <span ref={clip} class="meta-clip" {...motion}>
      <span class="meta-track">
        {typeof children === 'string' || typeof children === 'number' ? <span>{children}</span> : children}
      </span>
    </span>
  );
}
