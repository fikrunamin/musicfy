import type { ComponentChildren } from 'preact';
import { httpsThumbnail } from '../net/search';
import { DurationChip, MetaClip, cx } from './components';
import { Icon } from './icon';

export type ThumbKind = 'song' | 'video' | 'direct';

interface ThumbProps {
  url: string | null;
  /** 78 × 44 (16:9) instead of 44 × 44: video rows, and every row of a queue that holds videos. */
  wide?: boolean;
  /** Which placeholder to show without an image: a note, a play mark, or the film of a direct link. */
  kind?: ThumbKind;
  /** Shown as a chip on wide thumbnails (videos). */
  duration?: string | null;
}

/**
 * A thumbnail (handoff "Baris Lagu" / "Baris Video"): 44 × 44 or 78 × 44, r8, or a placeholder
 * unless the URL is https (entries come from any client).
 */
export function Thumb({ url, wide = false, kind = 'song', duration = null }: ThumbProps) {
  const src = httpsThumbnail(url);
  const cls = cx('thumb', wide && 'thumb-wide');
  const image = src ? (
    <img class={cls} src={src} alt="" loading="lazy" referrerpolicy="no-referrer" width={wide ? 78 : 44} height={44} />
  ) : (
    <div class={cx(cls, 'thumb-empty', kind === 'direct' && 'thumb-direct')} aria-hidden="true">
      <Icon name={kind === 'song' ? 'music_note' : kind === 'direct' ? 'movie' : 'play_arrow'} size={20} />
    </div>
  );
  if (!wide || !duration) return image;
  return (
    <span class="thumb-frame">
      {image}
      <DurationChip>{duration}</DurationChip>
    </span>
  );
}

/**
 * The dark 16:9 frame for a direct link without a thumbnail (handoff "Placeholder Video"): film
 * icon and domain, with an optional badge (top left) and duration (bottom right).
 */
export function VideoPlaceholder({ domain, badge, duration }: { domain: string; badge?: ComponentChildren; duration?: string | null }) {
  return (
    <div class="video-placeholder">
      {badge && <span class="vp-badge">{badge}</span>}
      <Icon name="movie" size={32} />
      <span class="vp-domain">{domain}</span>
      {duration && (
        <span class="vp-duration">
          <DurationChip>{duration}</DurationChip>
        </span>
      )}
    </div>
  );
}

interface RowProps {
  thumb: ComponentChildren;
  title: string;
  /** The one-line meta; replaced by `warning` (danger colour) when the entry cannot play here. */
  meta: ComponentChildren;
  warning?: string | null;
  trailing?: ComponentChildren;
  /** Content under the row (the inline actions of an opened row). */
  below?: ComponentChildren;
  current?: boolean;
}

/** A 60 px song or video row: thumbnail, title, one-line meta, trailing control. */
export function MediaRow({ thumb, title, meta, warning, trailing, below, current = false }: RowProps) {
  return (
    <li class={cx('row', current && 'row-current')}>
      <div class="row-main">
        {thumb}
        <div class="row-text">
          <span class="row-title">{title}</span>
          {warning ? <span class="row-warning">{warning}</span> : <MetaClip>{meta}</MetaClip>}
        </div>
        {trailing && <div class="row-trailing">{trailing}</div>}
      </div>
      {below}
    </li>
  );
}
