import { httpsThumbnail } from '../net/search';
import { cx } from './components';
import { Crossfade } from './motion';

/**
 * The blurred artwork behind the room (brief §3): the current thumbnail (already loaded by the
 * stage or queue, so no extra third-party request), blurred, under the theme overlay. Videos get
 * a darker overlay so the eye stays on the video. When the song changes the new artwork fades in
 * over the old one (600 ms); without artwork a plain layer fades in instead.
 */
export function Backdrop({ url, video }: { url: string | null; video: boolean }) {
  const src = httpsThumbnail(url);
  return (
    <div class={cx('backdrop', video && 'backdrop-video')} aria-hidden="true">
      <Crossfade value={src} keyOf={(s) => s ?? ''}>
        {(s, entering) =>
          s ? (
            <img class={cx('backdrop-img', entering && 'xfade-in')} src={s} alt="" referrerpolicy="no-referrer" />
          ) : (
            <div class={cx('backdrop-blank', entering && 'xfade-in')} />
          )
        }
      </Crossfade>
    </div>
  );
}
