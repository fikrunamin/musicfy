import { httpsThumbnail } from '../net/search';
import { cx } from './components';

/**
 * The blurred artwork behind the room (brief §3): the current thumbnail (already loaded by the
 * stage or queue, so no extra third-party request), blurred, under the theme overlay. Videos get
 * a darker overlay so the eye stays on the video.
 */
export function Backdrop({ url, video }: { url: string | null; video: boolean }) {
  const src = httpsThumbnail(url);
  return (
    <div class={cx('backdrop', video && 'backdrop-video')} aria-hidden="true">
      {src && <img class="backdrop-img" src={src} alt="" referrerpolicy="no-referrer" />}
    </div>
  );
}
