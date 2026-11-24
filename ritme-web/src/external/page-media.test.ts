// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The Ritme in-app browser's page script (modules/ritme-media), tested here because this package has jsdom.
const SCRIPT = readFileSync(
  resolve(
    import.meta.dirname,
    '../../../modules/ritme-media/src/main/resources/com/example/musicfy/ritme/media/page-media.js',
  ),
  'utf8',
);

interface Report {
  v: number;
  page: string | null;
  title: string | null;
  ogTitle: string | null;
  site: string | null;
  og: string[];
  ld: { url: string | null; duration: string | null; name: string | null }[];
  videos: { srcs: string[]; blob: boolean; duration: number | null; w: number; h: number; area: number; playing: boolean }[];
  eme: boolean;
}

const globals = window as unknown as Record<string, unknown>;

/** Runs the script the way the app injects it, with a fake `ritmeMedia` bridge; returns what it posts. */
function install(): string[] {
  const posted: string[] = [];
  globals.ritmeMedia = { postMessage: (message: string) => posted.push(message) };
  delete globals.__ritmeMediaInstalled;
  new Function(SCRIPT)();
  return posted;
}

const reports = (posted: string[]): Report[] => posted.map((message) => JSON.parse(message) as Report);
const absolute = (path: string): string => new URL(path, location.href).href;

beforeEach(() => {
  vi.useFakeTimers();
  document.head.innerHTML = '';
  document.body.innerHTML = '';
  document.title = '';
});

const emeBefore = Object.getOwnPropertyDescriptor(navigator, 'requestMediaKeySystemAccess');

afterEach(() => {
  vi.useRealTimers();
  // The script wraps navigator.requestMediaKeySystemAccess; put it back so hooks never stack across tests.
  if (emeBefore) Object.defineProperty(navigator, 'requestMediaKeySystemAccess', emeBefore);
  else delete (navigator as unknown as Record<string, unknown>).requestMediaKeySystemAccess;
  document.head.innerHTML = '';
});

describe('page-media.js', () => {
  it('reports a <video> once, after a short pause, as an absolute address without its fragment', async () => {
    document.body.innerHTML = '<video src="/films/a.mp4#t=10"></video>';
    const posted = install();
    expect(posted).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    const [report] = reports(posted);
    expect(report.v).toBe(1);
    expect(report.videos[0].srcs).toEqual([absolute('/films/a.mp4')]);
    expect(report.videos[0].blob).toBe(false);
  });

  it('flags a blob: source without reporting it, and reads <source> children', async () => {
    document.body.innerHTML = '<video src="blob:http://localhost:3000/123"><source src="https://cdn.example.com/v.webm"></video>';
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    const video = reports(posted)[0].videos[0];
    expect(video.blob).toBe(true);
    expect(video.srcs).toEqual(['https://cdn.example.com/v.webm']);
  });

  it('says -1 for a live (infinite) length, keeps a finite one, and whether it plays', async () => {
    document.body.innerHTML =
      '<video id="live" src="https://cdn.example.com/live.m3u8"></video><video id="vod" src="https://cdn.example.com/a.mp4"></video>';
    Object.defineProperty(document.getElementById('live'), 'duration', { value: Infinity });
    Object.defineProperty(document.getElementById('vod'), 'duration', { value: 61.5 });
    Object.defineProperty(document.getElementById('vod'), 'paused', { value: false });
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    const [live, vod] = reports(posted)[0].videos;
    expect(live.duration).toBe(-1);
    expect(vod.duration).toBe(61.5);
    expect(vod.playing).toBe(true);
  });

  it('reads og:video, the titles, the site name and JSON-LD VideoObjects (also inside @graph)', async () => {
    document.head.innerHTML = [
      '<meta property="og:title" content="Film Pendek">',
      '<meta property="og:site_name" content="Contoh TV">',
      '<meta property="og:video:secure_url" content="https://cdn.example.com/og.mp4">',
      '<meta property="og:video" content="https://cdn.example.com/og.mp4">',
      '<script type="application/ld+json">{"@graph":[{"@type":"VideoObject","contentUrl":"https://cdn.example.com/ld.m3u8","duration":"PT2M","name":"Klip"}]}</script>',
      '<script type="application/ld+json">not json</script>',
    ].join('');
    document.title = 'Judul halaman'; // after the head is rebuilt, which drops any <title>
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    const report = reports(posted)[0];
    expect(report.title).toBe('Judul halaman');
    expect(report.ogTitle).toBe('Film Pendek');
    expect(report.site).toBe('Contoh TV');
    expect(report.og).toEqual(['https://cdn.example.com/og.mp4']);
    expect(report.ld).toEqual([{ url: 'https://cdn.example.com/ld.m3u8', duration: 'PT2M', name: 'Klip' }]);
  });

  it('drops javascript:, data: and overlong addresses', async () => {
    document.body.innerHTML = `<video src="javascript:alert(1)"><source src="data:video/mp4;base64,AAAA"><source src="https://cdn.example.com/${'a'.repeat(2100)}.mp4"></video>`;
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(reports(posted)[0].videos[0].srcs).toEqual([]);
  });

  it('reports again only when something changed, such as a video added later', async () => {
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    document.dispatchEvent(new Event('play'));
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    const video = document.createElement('video');
    video.setAttribute('src', 'https://cdn.example.com/later.mp4');
    document.body.appendChild(video);
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(2);
    expect(reports(posted)[1].videos[0].srcs).toEqual(['https://cdn.example.com/later.mp4']);
  });

  it('notices DRM: a call to requestMediaKeySystemAccess sets eme and still reaches the browser', async () => {
    const original = vi.fn(() => Promise.reject(new Error('no key system')));
    Object.defineProperty(navigator, 'requestMediaKeySystemAccess', { value: original, configurable: true, writable: true });
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(reports(posted)[0].eme).toBe(false);
    const nav = navigator as unknown as { requestMediaKeySystemAccess: () => Promise<unknown> };
    await nav.requestMediaKeySystemAccess().catch(() => undefined);
    await vi.advanceTimersByTimeAsync(500);
    expect(reports(posted)[1].eme).toBe(true);
    expect(original).toHaveBeenCalledOnce();
  });

  it('runs once per frame and does nothing without the app bridge', async () => {
    const posted = install();
    new Function(SCRIPT)();
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    delete globals.ritmeMedia;
    delete globals.__ritmeMediaInstalled;
    expect(() => new Function(SCRIPT)()).not.toThrow();
  });
  it('resolves relative addresses against the document base, not the page address', async () => {
    document.head.innerHTML = '<base href="https://cdn.example.com/media/">';
    document.body.innerHTML = '<video src="clip.mp4"></video>';
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(reports(posted)[0].videos[0].srcs).toEqual(['https://cdn.example.com/media/clip.mp4']);
  });

  it('trims an oversize report so the app never has to discard it whole', async () => {
    const long = (n: number, k: number): string => `https://cdn.example.com/${'p'.repeat(1900)}/${n}-${k}.mp4`;
    document.body.innerHTML = Array.from({ length: 20 }, (_, n) => {
      const sources = Array.from({ length: 5 }, (_, k) => `<source src="${long(n, k)}">`).join('');
      return `<video>${sources}</video>`;
    }).join('');
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    expect(posted[0].length).toBeLessThanOrEqual(60_000);
    const { videos } = reports(posted)[0];
    expect(videos.length).toBeGreaterThan(0);
    expect(videos[0].srcs[0]).toBe(long(0, 0));
  });

  it('keeps reporting after a page clears every timeout', async () => {
    const posted = install();
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(1);
    const first = document.createElement('video');
    first.setAttribute('src', 'https://cdn.example.com/one.mp4');
    document.body.appendChild(first);
    await vi.advanceTimersByTimeAsync(10); // the observer has scheduled a report
    vi.clearAllTimers(); // the page cleared it
    await vi.advanceTimersByTimeAsync(2000);
    expect(posted).toHaveLength(1);
    const second = document.createElement('video');
    second.setAttribute('src', 'https://cdn.example.com/two.mp4');
    document.body.appendChild(second);
    await vi.advanceTimersByTimeAsync(500);
    expect(posted).toHaveLength(2);
    expect(reports(posted)[1].videos.map((v) => v.srcs[0])).toEqual(['https://cdn.example.com/one.mp4', 'https://cdn.example.com/two.mp4']);
  });

  it('does not throw into the page when scheduling fails inside the DRM hook', async () => {
    const original = vi.fn(() => Promise.resolve('access'));
    Object.defineProperty(navigator, 'requestMediaKeySystemAccess', { value: original, configurable: true, writable: true });
    install();
    await vi.advanceTimersByTimeAsync(500); // the first report is out: nothing is scheduled now
    const nav = navigator as unknown as { requestMediaKeySystemAccess: () => Promise<unknown> };
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(() => {
      throw new Error('no timers');
    });
    await expect(nav.requestMediaKeySystemAccess()).resolves.toBe('access');
    vi.restoreAllMocks();
  });
});
