import type { MediaPlayer, PlayableSource, PlayerEvent } from './types';

/** The subset of hls.js used here (loaded on demand, only for .m3u8 without native HLS). */
export interface HlsLike {
  loadSource(url: string): void;
  attachMedia(media: HTMLMediaElement): void;
  on(event: string, handler: (event: string, data: { fatal?: boolean }) => void): void;
  destroy(): void;
}

export interface HlsConstructor {
  new (): HlsLike;
  isSupported(): boolean;
  Events: { ERROR: string };
}

const loadHlsJs = (): Promise<HlsConstructor> => import('hls.js').then((m) => m.default as unknown as HlsConstructor);

/** A pause this close to the end is the <video> announcing the natural end, not a user pause. */
const END_EPSILON_S = 0.05;

/**
 * MediaPlayer over a plain <video> element for URL entries. mp4/webm play natively (no CORS
 * needed); HLS uses native support (Safari) or hls.js, which needs CORS on the stream host. Any
 * fatal error marks the entry unplayable. Everything tied to a load (listeners, hls.js instance,
 * generation) is dropped when the next load() or destroy() happens, so a late event of an earlier
 * load can never be reported under a newer one.
 */
export function createVideoPlayer(
  container: HTMLElement,
  onEvent: (event: PlayerEvent) => void,
  loadHls: () => Promise<HlsConstructor> = loadHlsJs,
): MediaPlayer {
  const video = container.ownerDocument.createElement('video');
  video.playsInline = true;
  video.controls = true;
  video.preload = 'auto';
  video.className = 'player-video';
  container.appendChild(video);

  let destroyed = false;
  let abort: AbortController | null = null;
  let hls: HlsLike | null = null;
  let wantPlay = false;
  /** Start position still to apply (or being applied) for the current load. */
  let pendingStartMs: number | null = null;
  let metadataLoaded = false;
  /** A start seek was issued and 'seeked' has not arrived yet. */
  let awaitingSeeked = false;
  /** The current source reports its real position. */
  let positioned = false;

  const tryPlay = () => {
    // NotAllowedError (no user gesture yet) is handled by the controller's "tap" hint.
    void Promise.resolve(video.play()).catch(() => {});
  };

  const becomePositioned = () => {
    positioned = true;
    pendingStartMs = null;
    awaitingSeeked = false;
    if (wantPlay) tryPlay();
  };

  const applyStart = () => {
    if (pendingStartMs === null) {
      becomePositioned();
      return;
    }
    awaitingSeeked = true;
    video.currentTime = pendingStartMs / 1000;
  };

  const teardown = () => {
    abort?.abort();
    abort = null;
    hls?.destroy();
    hls = null;
    video.pause();
    video.removeAttribute('src');
    try {
      video.load();
    } catch {
      // not implemented (jsdom); the removed src is enough there
    }
  };

  return {
    load(source: PlayableSource, startMs: number | null, gen: number) {
      if (source.kind !== 'video') throw new Error('Video player got a non-video source');
      if (destroyed) return;
      teardown();
      const controller = new AbortController();
      abort = controller;
      const { signal } = controller;
      const emit = (type: PlayerEvent['type']) => {
        if (!signal.aborted && !destroyed) onEvent({ type, gen });
      };
      wantPlay = false;
      pendingStartMs = startMs;
      metadataLoaded = false;
      awaitingSeeked = false;
      positioned = false;

      video.addEventListener('playing', () => emit('playing'), { signal });
      video.addEventListener('pause', () => {
        const d = video.duration;
        const atEnd = video.ended || (Number.isFinite(d) && d > 0 && video.currentTime >= d - END_EPSILON_S);
        if (!atEnd && !video.seeking) emit('paused');
      }, { signal });
      video.addEventListener('ended', () => emit('ended'), { signal });
      video.addEventListener('error', () => emit('unplayable'), { signal });
      video.addEventListener('loadedmetadata', () => {
        metadataLoaded = true;
        applyStart();
      }, { signal });
      video.addEventListener('seeked', () => {
        if (awaitingSeeked) becomePositioned();
      }, { signal });

      const page = container.ownerDocument.location;
      if (page?.protocol === 'https:' && /^http:/i.test(source.url)) {
        emit('unplayable'); // mixed content would be blocked anyway
        return;
      }

      const nativeHls = video.canPlayType('application/vnd.apple.mpegurl') !== '';
      if (source.hls && !nativeHls) {
        loadHls().then(
          (Hls) => {
            if (signal.aborted) return;
            if (!Hls.isSupported()) {
              emit('unplayable');
              return;
            }
            const instance = new Hls();
            hls = instance;
            instance.on(Hls.Events.ERROR, (_event, data) => {
              if (data.fatal) emit('unplayable');
            });
            instance.loadSource(source.url);
            instance.attachMedia(video);
          },
          () => emit('unplayable'),
        );
      } else {
        video.src = source.url;
      }
    },
    play() {
      wantPlay = true;
      if (positioned) tryPlay();
    },
    pause() {
      wantPlay = false;
      video.pause();
    },
    seek(ms: number) {
      if (positioned) {
        video.currentTime = ms / 1000;
        return;
      }
      pendingStartMs = ms;
      if (metadataLoaded) applyStart();
    },
    currentMs() {
      return positioned ? video.currentTime * 1000 : null;
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      teardown();
      video.remove();
    },
  };
}
