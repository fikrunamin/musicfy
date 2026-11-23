/**
 * Stands in for https://www.youtube.com/iframe_api: a YT.Player that records what it was asked
 * to play in window.__fakeYt. Like the real API, cueVideoById() reports CUED asynchronously
 * (about 100 ms later), and a playVideo() issued before that fails with onError 2: the race that
 * made every YouTube entry "Tidak bisa diputar di web" for a viewer joining a playing room.
 */
export const FAKE_IFRAME_API = `
window.__fakeYt = { cued: null, startSeconds: null, playing: false, errors: [] };
window.YT = {
  Player: function (el, opts) {
    var fake = window.__fakeYt;
    var frame = document.createElement('div');
    frame.setAttribute('data-testid', 'fake-yt');
    el.replaceWith(frame);
    var t = 0;
    var since = 0;
    var state = -1;
    var cueInFlight = false;
    var cueSeq = 0;
    var setState = function (s) { state = s; opts.events.onStateChange({ data: s }); };
    var now = function () { return state === 1 ? t + (Date.now() - since) / 1000 : t; };
    this.cueVideoById = function (o) {
      fake.cued = o.videoId;
      fake.startSeconds = o.startSeconds === undefined ? null : o.startSeconds;
      fake.playing = false;
      t = o.startSeconds || 0;
      cueInFlight = true;
      var seq = ++cueSeq;
      setState(-1);
      setTimeout(function () {
        if (seq !== cueSeq) return;
        cueInFlight = false;
        setState(5);
      }, 100);
    };
    this.playVideo = function () {
      if (cueInFlight) {
        setTimeout(function () { fake.errors.push(2); opts.events.onError({ data: 2 }); }, 0);
        return;
      }
      fake.playing = true;
      setTimeout(function () { since = Date.now(); setState(1); }, 0);
    };
    this.pauseVideo = function () {
      t = now();
      fake.playing = false;
      if (state === 1 || state === 3) setState(2);
    };
    this.seekTo = function (s) { t = s; since = Date.now(); };
    this.getCurrentTime = now;
    this.getPlayerState = function () { return state; };
    this.getVideoData = function () { return { video_id: fake.cued || '' }; };
    this.destroy = function () { frame.remove(); };
    setTimeout(function () { opts.events.onReady(); }, 0);
  },
};
setTimeout(function () { if (window.onYouTubeIframeAPIReady) window.onYouTubeIframeAPIReady(); }, 0);
`;
