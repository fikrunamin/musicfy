import { type HlsConstructor, type HlsLike, loadHlsJs } from '../player/html5';

/**
 * What a hidden <video preload="metadata" muted> learned about a pasted media link (spec 6.1):
 * `ready` with the duration (a live stream reports Infinity: live, durationMs 0; an unknown
 * duration is 0 and not live), `unplayable` when this browser cannot play it, `timeout` when
 * nothing arrived within PROBE_TIMEOUT_MS. An aborted probe also resolves as `timeout`; callers
 * drop it.
 */
export type ProbeOutcome = { kind: 'ready'; durationMs: number; live: boolean } | { kind: 'unplayable' } | { kind: 'timeout' };

export const PROBE_TIMEOUT_MS = 10_000;
const MAX_DURATION_MS = 86_400_000;

/** The part of HTMLVideoElement the probe uses (a fake in tests). */
export interface ProbeVideo extends EventTarget {
  muted: boolean;
  preload: string;
  src: string;
  readonly duration: number;
  canPlayType(type: string): string;
  removeAttribute(name: string): void;
  load(): void;
  remove(): void;
}

export interface ProbeOptions {
  hls: boolean;
  signal?: AbortSignal;
}

export interface ProbeDeps {
  createVideo(): ProbeVideo;
  loadHls(): Promise<HlsConstructor>;
  timeoutMs: number;
}

function hiddenVideo(): ProbeVideo {
  const video = document.createElement('video');
  video.hidden = true;
  document.body.appendChild(video);
  return video;
}

const defaultDeps: ProbeDeps = { createVideo: hiddenVideo, loadHls: loadHlsJs, timeoutMs: PROBE_TIMEOUT_MS };

/**
 * Loads only the metadata of `url` the way the room's players would (hls.js for HLS where the
 * browser has no native HLS), then tears everything down. The request goes to the media host the
 * entry will be played from anyway, which the CSP's media-src/connect-src already allow.
 */
export function probeMedia(url: string, options: ProbeOptions, deps: ProbeDeps = defaultDeps): Promise<ProbeOutcome> {
  return new Promise((resolve) => {
    const video = deps.createVideo();
    let hls: HlsLike | null = null;
    let done = false;
    const finish = (outcome: ProbeOutcome) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', onAbort);
      hls?.destroy();
      hls = null;
      video.removeAttribute('src');
      try {
        video.load();
      } catch {
        // not implemented (jsdom); the removed src is enough there
      }
      video.remove();
      resolve(outcome);
    };
    const onAbort = () => finish({ kind: 'timeout' });
    const timer = setTimeout(() => finish({ kind: 'timeout' }), deps.timeoutMs);
    if (options.signal?.aborted) {
      finish({ kind: 'timeout' });
      return;
    }
    options.signal?.addEventListener('abort', onAbort);
    video.muted = true;
    video.preload = 'metadata';
    video.addEventListener('loadedmetadata', () => {
      const seconds = video.duration;
      if (seconds === Number.POSITIVE_INFINITY) finish({ kind: 'ready', durationMs: 0, live: true });
      else if (Number.isFinite(seconds) && seconds > 0) {
        finish({ kind: 'ready', durationMs: Math.min(Math.round(seconds * 1000), MAX_DURATION_MS), live: false });
      } else finish({ kind: 'ready', durationMs: 0, live: false });
    });
    video.addEventListener('error', () => finish({ kind: 'unplayable' }));
    const nativeHls = video.canPlayType('application/vnd.apple.mpegurl') !== '';
    if (options.hls && !nativeHls) {
      deps.loadHls().then(
        (Hls) => {
          if (done) return;
          if (!Hls.isSupported()) {
            finish({ kind: 'unplayable' });
            return;
          }
          const instance = new Hls();
          hls = instance;
          instance.on(Hls.Events.ERROR, (_event, data) => {
            if (data.fatal) finish({ kind: 'unplayable' });
          });
          instance.loadSource(url);
          instance.attachMedia(video as unknown as HTMLMediaElement);
        },
        () => finish({ kind: 'unplayable' }),
      );
    } else {
      video.src = url;
      // An explicit load() inside the click that started the probe: iOS ignores preload otherwise.
      try {
        video.load();
      } catch {
        // not implemented (jsdom)
      }
    }
  });
}
