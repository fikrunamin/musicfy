import type { MediaPlayer, PlayableSource, PlayerEvent } from './types';

/** The only third-party script the app loads. */
export const IFRAME_API_URL = 'https://www.youtube.com/iframe_api';
/** Privacy-enhanced embed host: no YouTube cookies until the viewer interacts. */
export const EMBED_HOST = 'https://www.youtube-nocookie.com';
/** 2 invalid id, 100 removed/private, 101 and 150 embedding not allowed by the owner. */
export const UNPLAYABLE_ERRORS = [2, 100, 101, 150];

const STATE_ENDED = 0;
const STATE_PLAYING = 1;
const STATE_PAUSED = 2;
const STATE_CUED = 5;

export interface YTPlayer {
  cueVideoById(options: { videoId: string; startSeconds?: number }): void;
  playVideo(): void;
  pauseVideo(): void;
  seekTo(seconds: number, allowSeekAhead: boolean): void;
  getCurrentTime(): number;
  getVideoData(): { video_id?: string };
  destroy(): void;
}

export interface YTPlayerOptions {
  host: string;
  width: string;
  height: string;
  playerVars: Record<string, string | number>;
  events: {
    onReady: () => void;
    onStateChange: (event: { data: number }) => void;
    onError: (event: { data: number }) => void;
  };
}

export interface YTNamespace {
  Player: new (element: HTMLElement, options: YTPlayerOptions) => YTPlayer;
}

type YTWindow = Window & { YT?: YTNamespace; onYouTubeIframeAPIReady?: () => void };

/** Loads the IFrame API script at most once per page; rejects (and allows a retry) if it fails. */
export function createYouTubeApiLoader(doc: Document, win: YTWindow): () => Promise<YTNamespace> {
  let pending: Promise<YTNamespace> | null = null;
  return () => {
    if (win.YT?.Player) return Promise.resolve(win.YT);
    if (!pending) {
      pending = new Promise<YTNamespace>((resolve, reject) => {
        const previous = win.onYouTubeIframeAPIReady;
        win.onYouTubeIframeAPIReady = () => {
          previous?.();
          resolve(win.YT!);
        };
        const script = doc.createElement('script');
        script.src = IFRAME_API_URL;
        script.async = true;
        script.onerror = () => {
          pending = null;
          script.remove();
          reject(new Error('YouTube IFrame API failed to load'));
        };
        doc.head.appendChild(script);
      });
    }
    return pending;
  };
}

export const loadYouTubeApi: () => Promise<YTNamespace> =
  typeof document === 'undefined'
    ? () => Promise.reject(new Error('no document'))
    : createYouTubeApiLoader(document, window as YTWindow);

/** MediaPlayer over the YouTube IFrame Player API; queues calls until the player is ready. */
export function createYouTubePlayer(
  container: HTMLElement,
  onEvent: (event: PlayerEvent) => void,
  loadApi: () => Promise<YTNamespace> = loadYouTubeApi,
): MediaPlayer {
  const mount = container.ownerDocument.createElement('div');
  container.appendChild(mount);
  let yt: YTPlayer | null = null;
  let ready = false;
  let failed = false;
  let destroyed = false;
  let pendingLoad: { videoId: string; startMs: number | null } | null = null;
  let wantPlay = false;
  /** Generation of the latest load(); updated only there, so every event echoes the load it belongs to. */
  let currentGen = 0;
  let requestedVideoId: string | null = null;
  /** load() was called and the new video has not reported CUED/PLAYING yet: position unknown. */
  let awaitingNewVideo = false;

  const cue = (videoId: string, startMs: number | null) => {
    yt!.cueVideoById(startMs === null ? { videoId } : { videoId, startSeconds: startMs / 1000 });
  };

  /** False while the player still reports a different (previous) video than the one requested. */
  const showsRequestedVideo = (): boolean => {
    if (requestedVideoId === null) return true;
    const shown = yt?.getVideoData?.()?.video_id;
    return !shown || shown === requestedVideoId;
  };

  const emit = (type: PlayerEvent['type']) => onEvent({ type, gen: currentGen });

  loadApi().then(
    (YT) => {
      if (destroyed) return;
      yt = new YT.Player(mount, {
        host: EMBED_HOST,
        width: '100%',
        height: '100%',
        playerVars: { playsinline: 1, rel: 0, origin: container.ownerDocument.location.origin },
        events: {
          onReady: () => {
            ready = true;
            if (pendingLoad) cue(pendingLoad.videoId, pendingLoad.startMs);
            pendingLoad = null;
            if (wantPlay) yt!.playVideo();
          },
          onStateChange: (e) => {
            // A late state of the previous video must not be reported under the new generation.
            if (!showsRequestedVideo()) return;
            if (e.data === STATE_CUED || e.data === STATE_PLAYING) awaitingNewVideo = false;
            if (e.data === STATE_PLAYING) emit('playing');
            else if (e.data === STATE_PAUSED) emit('paused');
            else if (e.data === STATE_ENDED) emit('ended');
          },
          onError: (e) => {
            if (UNPLAYABLE_ERRORS.includes(e.data)) emit('unplayable');
          },
        },
      });
    },
    () => {
      failed = true;
      if (!destroyed && pendingLoad) emit('unplayable');
    },
  );

  return {
    load(source: PlayableSource, startMs: number | null, gen: number) {
      if (source.kind !== 'youtube') throw new Error('YouTube player got a non-YouTube source');
      currentGen = gen;
      requestedVideoId = source.videoId;
      awaitingNewVideo = true;
      if (failed) {
        queueMicrotask(() => {
          if (!destroyed && gen === currentGen) onEvent({ type: 'unplayable', gen });
        });
        return;
      }
      if (ready) cue(source.videoId, startMs);
      else pendingLoad = { videoId: source.videoId, startMs };
    },
    play() {
      wantPlay = true;
      if (ready) yt!.playVideo();
    },
    pause() {
      wantPlay = false;
      if (ready) yt!.pauseVideo();
    },
    seek(ms: number) {
      if (ready) yt!.seekTo(ms / 1000, true);
    },
    currentMs() {
      if (!ready || awaitingNewVideo) return null;
      return yt!.getCurrentTime() * 1000;
    },
    destroy() {
      destroyed = true;
      yt?.destroy();
      mount.remove();
    },
  };
}
