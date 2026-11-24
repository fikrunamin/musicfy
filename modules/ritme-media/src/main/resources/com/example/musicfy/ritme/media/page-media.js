// Ritme in-app browser page script (spec 4.7 step 2). Injected at document start into every frame;
// reports this frame's videos to the app through the `ritmeMedia` web message listener. It reads
// the page and never changes it. Plain ES2015 so old WebViews run it. Desktop (JCEF) will inject
// the same file. Tests: ritme-web/src/external/page-media.test.ts.
(function () {
  'use strict';
  if (window.__ritmeMediaInstalled) return;
  window.__ritmeMediaInstalled = true;
  var bridge = window.ritmeMedia;
  if (!bridge || typeof bridge.postMessage !== 'function') return;

  var MAX_URL = 2048;
  var MAX_ITEMS = 20;
  var MAX_SRCS = 5;
  var MAX_TEXT = 300;
  var DEBOUNCE_MS = 400;
  var eme = false;
  var last = '';
  var timer = 0;

  function text(value) {
    return typeof value === 'string' && value.trim() ? value.trim().slice(0, MAX_TEXT) : null;
  }

  // An absolute http(s) address without its #fragment, 'blob:' for a blob, else null.
  function address(value) {
    if (typeof value !== 'string' || !value || value.length > MAX_URL) return null;
    var parsed;
    try {
      parsed = new URL(value, location.href);
    } catch (e) {
      return null;
    }
    if (parsed.protocol === 'blob:') return 'blob:';
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    parsed.hash = '';
    return parsed.href.length > MAX_URL ? null : parsed.href;
  }

  function meta(name) {
    var el = document.querySelector('meta[property="' + name + '"], meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  function videos() {
    var out = [];
    var list = document.getElementsByTagName('video');
    for (var i = 0; i < list.length && out.length < MAX_ITEMS; i++) {
      var video = list[i];
      var candidates = [video.currentSrc, video.getAttribute('src')];
      var sources = video.getElementsByTagName('source');
      for (var j = 0; j < sources.length; j++) candidates.push(sources[j].getAttribute('src'));
      var srcs = [];
      var blob = false;
      for (var k = 0; k < candidates.length; k++) {
        var found = address(candidates[k]);
        if (found === 'blob:') blob = true;
        else if (found && srcs.indexOf(found) < 0 && srcs.length < MAX_SRCS) srcs.push(found);
      }
      var box = video.getBoundingClientRect();
      var duration = video.duration;
      out.push({
        srcs: srcs,
        blob: blob,
        duration: duration === Infinity ? -1 : isFinite(duration) && duration > 0 ? duration : null,
        w: video.videoWidth || 0,
        h: video.videoHeight || 0,
        area: Math.round(Math.max(0, box.width) * Math.max(0, box.height)),
        playing: !video.paused && !video.ended,
      });
    }
    return out;
  }

  function linkedData() {
    var out = [];
    function walk(node, depth) {
      if (!node || depth > 4 || out.length >= MAX_ITEMS) return;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) walk(node[i], depth + 1);
        return;
      }
      if (typeof node !== 'object') return;
      if (node['@graph']) walk(node['@graph'], depth + 1);
      var type = node['@type'];
      if (type === 'VideoObject' || (Array.isArray(type) && type.indexOf('VideoObject') >= 0)) {
        var url = address(node.contentUrl);
        out.push({ url: url === 'blob:' ? null : url, duration: text(node.duration), name: text(node.name) });
      }
    }
    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (var i = 0; i < scripts.length; i++) {
      try {
        walk(JSON.parse(scripts[i].textContent || ''), 0);
      } catch (e) {
        // not JSON: skip it
      }
    }
    return out;
  }

  function openGraph() {
    var out = [];
    var names = ['og:video:secure_url', 'og:video:url', 'og:video'];
    for (var i = 0; i < names.length; i++) {
      var found = address(meta(names[i]));
      if (found && found !== 'blob:' && out.indexOf(found) < 0) out.push(found);
    }
    return out;
  }

  function report() {
    timer = 0;
    var page = address(location.href);
    var message = JSON.stringify({
      v: 1,
      page: page === 'blob:' ? null : page,
      title: text(document.title),
      ogTitle: text(meta('og:title')),
      site: text(meta('og:site_name')),
      og: openGraph(),
      ld: linkedData(),
      videos: videos(),
      eme: eme,
    });
    if (message === last) return;
    last = message;
    try {
      bridge.postMessage(message);
    } catch (e) {
      // the app went away
    }
  }

  function schedule() {
    if (!timer) timer = setTimeout(report, DEBOUNCE_MS);
  }

  var WATCHED = { VIDEO: 1, SOURCE: 1, META: 1, SCRIPT: 1 };
  function touchesMedia(node) {
    if (!node || node.nodeType !== 1) return false;
    if (WATCHED[node.nodeName]) return true;
    return typeof node.querySelector === 'function' && node.querySelector('video, source, meta, script') !== null;
  }

  // DRM: a page asking for a key system (spec 4.7 table). Reported, never blocked.
  try {
    var request = navigator.requestMediaKeySystemAccess;
    if (typeof request === 'function') {
      navigator.requestMediaKeySystemAccess = function () {
        eme = true;
        schedule();
        return request.apply(navigator, arguments);
      };
    }
  } catch (e) {
    // read-only here: the playlist probe still sees DRM keys
  }

  ['loadedmetadata', 'durationchange', 'play', 'playing', 'pause', 'emptied'].forEach(function (type) {
    document.addEventListener(type, schedule, true);
  });
  document.addEventListener('DOMContentLoaded', schedule);
  window.addEventListener('load', schedule);
  try {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var record = records[i];
        var hit = record.type === 'attributes' ? touchesMedia(record.target) : Array.prototype.some.call(record.addedNodes, touchesMedia);
        if (hit) {
          schedule();
          return;
        }
      }
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'content'] });
  } catch (e) {
    // no observer: the media events above still report
  }
  schedule();
})();
