import { ICON_PATHS, type IconName } from './icon-paths';

export type { IconName };

/**
 * A Material Symbols Rounded icon as inline SVG in the current text colour. Always decorative:
 * the control around it carries the accessible name.
 */
export function Icon({ name, size = 24, class: cls }: { name: IconName; size?: number; class?: string }) {
  return (
    <svg
      class={cls ? `icon ${cls}` : 'icon'}
      width={size}
      height={size}
      viewBox="0 -960 960 960"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d={ICON_PATHS[name]} />
    </svg>
  );
}
