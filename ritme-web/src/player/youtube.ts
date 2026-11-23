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
/** After a load, events naming the previous video are dropped for at most this long. */
const STALE_WINDOW_MS = 3_000;
const RECHECK_MS = 250;

export interface YTPlayer {
  cueVideoById(options: { videoId: string; startSeconds?: number }): void;
  playVideo(): void;
  pauseVideo(): void;
  seekTo(seconds: number, allowSeekAhead: boolean): void;
  getCurrentTime(): number;
  getPlayerState(): number;
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
  let previousVideoId: string | null = null;
  /** load() was called and the new video has not reported CUED/PLAYING yet: position unknown. */
  let awaitingNewVideo = false;
  /** Known-stale filtering is on from load() until the first accepted CUED/PLAYING, or STALE_WINDOW_MS. */
  let filterActive = false;
  let loadedAt = 0;
  /** Last accepted state; -1 right after a (re-)cue. Cued/unstarted: seekTo would start playback. */
  let lastState = -1;
  let cuedStartMs: number | null = null;
  /**
   * A cue is in flight. YouTube answers a playVideo() issued before the cue reports CUED with
   * onError 2 (seen on the live site; a setTimeout(0) is not enough), so play() is held until then.
   */
  let cuePending = false;
  let cuedAt = 0;
  let dropped = false;
  let recheckTimers: (ReturnType<typeof setTimeout> | undefined)[] = [];

  const cue = (videoId: string, startMs: number | null) => {
    cuedStartMs = startMs;
    lastState = -1;
    cuePending = true;
    cuedAt = Date.now();
    yt!.cueVideoById(startMs === null ? { videoId } : { videoId, startSeconds: startMs / 1000 });
  };

  /** True only for an event known to belong to the previous video, shortly after a load. */
  const isStale = (): boolean => {
    if (!filterActive) return false;
    if (Date.now() - loadedAt > STALE_WINDOW_MS) {
      filterActive = false;
      return false;
    }
    const shown = yt?.getVideoData?.()?.video_id;
    return !!shown && shown === previousVideoId && shown !== requestedVideoId;
  };

  const emit = (type: PlayerEvent['type']) => onEvent({ type, gen: currentGen });

  const acceptState = (state: number) => {
    dropped = false;
    if (state === STATE_CUED || state === STATE_PLAYING) {
      awaitingNewVideo = false;
      filterActive = false;
    }
    lastState = state;
    if (cuePending && (state === STATE_CUED || state === STATE_PLAYING)) {
      cuePending = false;
      // The play() that arrived while the cue was in flight.
      if (state === STATE_CUED && wantPlay) yt!.playVideo();
    }
    if (state === STATE_PLAYING) emit('playing');
    else if (state === STATE_PAUSED) emit('paused');
    else if (state === STATE_ENDED) emit('ended');
  };

  const clearRechecks = () => {
    for (const t of recheckTimers) if (t !== undefined) clearTimeout(t);
    recheckTimers = [];
    dropped = false;
  };

  /** Something was dropped as stale (cached video data may lag): read the player's actual state. */
  const recheck = () => {
    if (destroyed || !dropped || !yt || isStale()) return;
    acceptState(yt.getPlayerState());
  };

  const scheduleRecheck = () => {
    const arm = (slot: 0 | 1, delay: number) => {
      if (recheckTimers[slot]) return;
      recheckTimers[slot] = setTimeout(() => {
        recheckTimers[slot] = undefined;
        recheck();
      }, delay);
    };
    arm(0, RECHECK_MS);
    arm(1, Math.max(0, loadedAt + STALE_WINDOW_MS - Date.now()) + 1);
  };

  const connect = () => {
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
              if (destroyed) return;
              ready = true;
              if (pendingLoad) cue(pendingLoad.videoId, pendingLoad.startMs);
              pendingLoad = null;
              if (wantPlay && !cuePending) yt!.playVideo();
            },
            onStateChange: (e) => {
              if (destroyed) return;
              if (isStale()) {
                dropped = true;
                scheduleRecheck();
                return;
              }
              acceptState(e.data);
            },
            onError: (e) => {
              if (destroyed || isStale()) return;
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
  };
  connect();

  return {
    load(source: PlayableSource, startMs: number | null, gen: number) {
      if (source.kind !== 'youtube') throw new Error('YouTube player got a non-YouTube source');
      currentGen = gen;
      if (requestedVideoId !== source.videoId) previousVideoId = requestedVideoId;
      requestedVideoId = source.videoId;
      clearRechecks();
      awaitingNewVideo = true;
      filterActive = true;
      loadedAt = Date.now();
      lastState = -1;
      cuedStartMs = startMs;
      if (ready) cue(source.videoId, startMs);
      else {
        pendingLoad = { videoId: source.videoId, startMs };
        if (failed) {
          // The API script failed earlier: try again for this entry.
          failed = false;
          connect();
        }
      }
    },
    play() {
      wantPlay = true;
      if (!ready) return;
      if (!cuePending) {
        yt!.playVideo();
        return;
      }
      // Normally the CUED event releases the play. If it never came (a controller retry finds the
      // cue long settled), trust the player's own state, which is safe to play from once CUED.
      if (Date.now() - cuedAt > STALE_WINDOW_MS && !isStale() && yt!.getPlayerState() === STATE_CUED) {
        acceptState(STATE_CUED);
      }
    },
    pause() {
      wantPlay = false;
      if (ready) yt!.pauseVideo();
    },
    seek(ms: number) {
      if (pendingLoad) {
        pendingLoad.startMs = ms;
        cuedStartMs = ms;
        return;
      }
      if (!ready) return;
      // seekTo on a cued/unstarted video starts playback; re-cue at the new position instead.
      if (!wantPlay && requestedVideoId !== null && (lastState === STATE_CUED || lastState === STATE_ENDED || lastState === -1)) {
        awaitingNewVideo = true;
        cue(requestedVideoId, ms);
        return;
      }
      yt!.seekTo(ms / 1000, true);
    },
    currentMs() {
      if (!ready || awaitingNewVideo) return null;
      if (lastState === STATE_CUED && cuedStartMs !== null) return cuedStartMs;
      return yt!.getCurrentTime() * 1000;
    },
    destroy() {
      destroyed = true;
      clearRechecks();
      yt?.destroy();
      mount.remove();
    },
  };
}
