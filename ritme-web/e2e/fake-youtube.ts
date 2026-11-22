/**
 * Stands in for https://www.youtube.com/iframe_api: a YT.Player that records what it was asked
 * to play in window.__fakeYt and reports PLAYING right after playVideo().
 */
export const FAKE_IFRAME_API = `
window.__fakeYt = { cued: null, playing: false };
window.YT = {
  Player: function (el, opts) {
    var frame = document.createElement('div');
    frame.setAttribute('data-testid', 'fake-yt');
    el.replaceWith(frame);
    var t = 0;
    this.cueVideoById = function (o) { window.__fakeYt.cued = o.videoId; t = o.startSeconds || 0; };
    this.playVideo = function () {
      window.__fakeYt.playing = true;
      setTimeout(function () { opts.events.onStateChange({ data: 1 }); }, 0);
    };
    this.pauseVideo = function () { window.__fakeYt.playing = false; opts.events.onStateChange({ data: 2 }); };
    this.seekTo = function (s) { t = s; };
    this.getCurrentTime = function () { return t; };
    this.destroy = function () { frame.remove(); };
    setTimeout(function () { opts.events.onReady(); }, 0);
  },
};
setTimeout(function () { if (window.onYouTubeIframeAPIReady) window.onYouTubeIframeAPIReady(); }, 0);
`;
