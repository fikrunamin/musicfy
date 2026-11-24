// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ProbeOptions, ProbeOutcome } from '../net/media-probe';
import { fakeSession } from '../testing/fake-session';
import { AddVideoPanel } from './add-video';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const INFO = {
  videoId: 'aqz-KE-bpKQ',
  title: 'Big Buck Bunny',
  channel: 'Blender',
  durationMs: 635_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
  live: false,
};

function respond(status: number, body: unknown = INFO) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
}

function probing(outcome: ProbeOutcome) {
  return vi.fn(async (_url: string, _options: ProbeOptions): Promise<ProbeOutcome> => outcome);
}

function submit(text: string) {
  fireEvent.input(screen.getByLabelText('Tempel link video'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Pratinjau' }));
}

describe('AddVideoPanel', () => {
  it('previews a YouTube link through /api/video and adds it as a YOUTUBE_VIDEO entry', async () => {
    const session = fakeSession();
    const fetchImpl = respond(200);
    render(<AddVideoPanel session={session} online pageIsHttps fetchImpl={fetchImpl} />);
    submit('https://youtu.be/aqz-KE-bpKQ?t=5');
    expect(await screen.findByText('Blender · 10:35')).toBeTruthy();
    expect(fetchImpl).toHaveBeenCalledWith('/api/video?id=aqz-KE-bpKQ', expect.anything());
    expect(document.querySelector('img.thumb-wide')?.getAttribute('src')).toBe(INFO.thumbnailUrl);
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: Big Buck Bunny' }));
    expect(session.add).toHaveBeenCalledWith(
      {
        videoId: 'aqz-KE-bpKQ',
        title: 'Big Buck Bunny',
        artist: 'Blender',
        thumbnailUrl: INFO.thumbnailUrl,
        durationMs: 635_000,
        kind: 'YOUTUBE_VIDEO',
        url: null,
      },
      false,
    );
    expect((screen.getByLabelText('Tempel link video') as HTMLInputElement).value).toBe('');
    expect(screen.queryByRole('button', { name: /^Tambah:/ })).toBeNull();
  });

  it('adds a music.youtube.com link as a song, and a live video as LIVE with duration 0', async () => {
    const session = fakeSession();
    render(<AddVideoPanel session={session} online pageIsHttps fetchImpl={respond(200, { ...INFO, live: true, durationMs: 0 })} />);
    submit('https://music.youtube.com/watch?v=aqz-KE-bpKQ');
    expect(await screen.findByText('Blender · LIVE')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Putar berikutnya: Big Buck Bunny' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ kind: 'YOUTUBE_MUSIC', durationMs: 0 }), true);
  });

  it('shows a music.youtube.com preview with a square thumbnail, like a song in the queue', async () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200)} />);
    submit('https://music.youtube.com/watch?v=aqz-KE-bpKQ');
    await screen.findByText('Blender · 10:35');
    const img = document.querySelector('img.thumb');
    expect(img?.classList.contains('thumb-wide')).toBe(false);
    expect(img?.getAttribute('width')).toBe('44');
  });

  it.each<[number, string]>([
    [404, 'Video tidak ditemukan'],
    [429, 'Terlalu banyak permintaan, coba lagi sebentar'],
    [503, 'Server sibuk'],
    [502, 'Info video tidak bisa diambil'],
  ])('explains HTTP %i and adds nothing', async (status, message) => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(status, '')} />);
    submit('https://www.youtube.com/watch?v=aqz-KE-bpKQ');
    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(screen.queryByRole('button', { name: /^Tambah:/ })).toBeNull();
  });

  it('refuses a video whose title the server would see as blank', async () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200, { ...INFO, title: '\u200B\u2800' })} />);
    submit('https://www.youtube.com/watch?v=aqz-KE-bpKQ');
    expect((await screen.findByRole('alert')).textContent).toBe('Info video tidak bisa diambil');
  });

  it('probes a direct link, takes an optional title and adds a URL entry', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 61_000, live: false });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/media/clip.mp4#t=3');
    expect(await screen.findByText('dari cdn.example.com')).toBeTruthy();
    expect(screen.getByText('MP4 · 1:01')).toBeTruthy();
    expect(probe).toHaveBeenCalledWith('https://cdn.example.com/media/clip.mp4', { hls: false, signal: expect.any(AbortSignal) });
    const title = screen.getByLabelText('Judul (opsional)') as HTMLInputElement;
    expect(title.placeholder).toBe('Kosongkan untuk pakai nama file');
    expect(document.querySelector('.avp-title')?.textContent).toBe('clip.mp4');
    fireEvent.input(title, { target: { value: 'Klip saya' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: Klip saya' }));
    expect(session.add).toHaveBeenCalledWith(
      {
        videoId: '',
        title: 'Klip saya',
        artist: 'cdn.example.com',
        thumbnailUrl: null,
        durationMs: 61_000,
        kind: 'URL',
        url: 'https://cdn.example.com/media/clip.mp4',
      },
      false,
    );
  });

  it('shows the file name when the typed title has nothing visible, and sends the same', async () => {
    const session = fakeSession();
    render(<AddVideoPanel session={session} online pageIsHttps probe={probing({ kind: 'ready', durationMs: 1_000, live: false })} />);
    submit('https://cdn.example.com/clip.mp4');
    await screen.findByText('MP4 · 0:01');
    fireEvent.input(screen.getByLabelText('Judul (opsional)'), { target: { value: '\u200B\u3164' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: clip.mp4' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ title: 'clip.mp4' }), false);
  });

  it('shows an IDN domain decoded but sends the punycode url and domain', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 2_000, live: false });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://bücher.example/film.mp4');
    expect(await screen.findByText('dari bücher.example')).toBeTruthy();
    expect(screen.getByText('MP4 · 0:02')).toBeTruthy();
    expect(probe).toHaveBeenCalledWith('https://xn--bcher-kva.example/film.mp4', expect.anything());
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: film.mp4' }));
    expect(session.add).toHaveBeenCalledWith(
      expect.objectContaining({ artist: 'xn--bcher-kva.example', url: 'https://xn--bcher-kva.example/film.mp4' }),
      false,
    );
  });

  it('probes HLS with hls and shows a live stream as LIVE', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 0, live: true });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://live.example.org/stream/index.m3u8');
    expect(await screen.findByText('dari live.example.org')).toBeTruthy();
    // The format line: the LIVE badge, then the format (no duration for a live stream).
    const format = screen.getByText('HLS · m3u8').parentElement!;
    expect(format.querySelector('.badge-live')?.textContent).toBe('LIVE');
    expect(probe).toHaveBeenCalledWith('https://live.example.org/stream/index.m3u8', { hls: true, signal: expect.any(AbortSignal) });
    fireEvent.click(screen.getByRole('button', { name: 'Putar berikutnya: index.m3u8' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ kind: 'URL', title: 'index.m3u8', durationMs: 0 }), true);
  });

  it('warns when this browser cannot play the link but still lets it be added', async () => {
    const session = fakeSession();
    render(<AddVideoPanel session={session} online pageIsHttps probe={probing({ kind: 'unplayable' })} />);
    submit('https://cdn.example.com/a.mov');
    expect(await screen.findByText('Video ini tidak bisa diputar di web')).toBeTruthy();
    expect(screen.getByText('dari cdn.example.com')).toBeTruthy();
    expect(screen.getByText('MOV · Durasi tidak diketahui')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: a.mov' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ kind: 'URL', durationMs: 0 }), false);
  });

  it('says the duration is unknown after a timeout', async () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'timeout' })} />);
    submit('https://cdn.example.com/a.webm');
    expect(await screen.findByText('WEBM · Durasi tidak diketahui')).toBeTruthy();
    expect(screen.getByText('dari cdn.example.com')).toBeTruthy();
    expect(screen.queryByText('Video ini tidak bisa diputar di web')).toBeNull();
  });

  it('keeps the add buttons disabled while probing', async () => {
    const probe = vi.fn((_url: string, _options: ProbeOptions) => new Promise<ProbeOutcome>(() => {}));
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/a.mp4');
    expect(await screen.findByText('MP4 · Memuat…')).toBeTruthy();
    expect(screen.getByText('dari cdn.example.com')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Tambah: a.mp4' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Putar berikutnya: a.mp4' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('disables adding while offline', async () => {
    render(<AddVideoPanel session={fakeSession()} online={false} pageIsHttps fetchImpl={respond(200)} />);
    submit('https://youtu.be/aqz-KE-bpKQ');
    expect((await screen.findByRole('button', { name: 'Tambah: Big Buck Bunny' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it.each<[string, string]>([
    ['http://cdn.example.com/a.mp4', 'Halaman harus https untuk link http'],
    ['https://cdn.example.com/a.mpd', 'Video ini tidak bisa diputar di web'],
    ['https://example.com/film/123', 'Link ini halaman web, bukan file video. Tempel link video langsung (mp4, webm, atau m3u8).'],
    ['http://192.168.1.10/a.mp4', 'Link ini tidak bisa ditambahkan'],
    ['bukan link', 'Link tidak dikenali'],
  ])('refuses %j without calling anything', async (input, message) => {
    const fetchImpl = respond(200);
    const probe = probing({ kind: 'timeout' });
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} probe={probe} />);
    submit(input);
    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(probe).not.toHaveBeenCalled();
  });

  it('allows http media on an http page', async () => {
    const probe = probing({ kind: 'ready', durationMs: 5_000, live: false });
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps={false} probe={probe} />);
    submit('http://cdn.example.com/a.mp4');
    expect(await screen.findByText('MP4 · 0:05')).toBeTruthy();
    expect(screen.getByText('dari cdn.example.com')).toBeTruthy();
  });

  it('drops the outcome of an earlier link once a newer one is submitted', async () => {
    let finishFirst: (outcome: ProbeOutcome) => void = () => {};
    const probe = vi.fn(
      (_url: string, _options: ProbeOptions) =>
        new Promise<ProbeOutcome>((resolve) => {
          finishFirst = resolve;
        }),
    );
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200)} probe={probe} />);
    submit('https://cdn.example.com/b.mp4');
    await screen.findByText('MP4 · Memuat…');
    const firstSignal = probe.mock.calls[0][1].signal;
    submit('https://youtu.be/aqz-KE-bpKQ');
    expect(await screen.findByText('Blender · 10:35')).toBeTruthy();
    expect(firstSignal?.aborted).toBe(true);
    finishFirst({ kind: 'ready', durationMs: 3_000, live: false });
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByText(/cdn\.example\.com/)).toBeNull();
    expect(screen.getByText('Blender · 10:35')).toBeTruthy();
  });

  it('shows no error for a lookup cut short by leaving the tab', async () => {
    let seenSignal: AbortSignal | undefined;
    const fetchImpl = vi.fn((_url: string, init?: RequestInit) => {
      seenSignal = init?.signal ?? undefined;
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('', 'AbortError')));
      });
    }) as unknown as typeof fetch;
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { unmount } = render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} />);
    submit('https://youtu.be/aqz-KE-bpKQ');
    await waitFor(() => expect(seenSignal).toBeDefined());
    unmount();
    await new Promise((r) => setTimeout(r, 0));
    expect(seenSignal?.aborted).toBe(true);
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('editing the link cancels the lookup in flight and clears the preview', async () => {
    let seenSignal: AbortSignal | undefined;
    const fetchImpl = vi.fn((_url: string, init?: RequestInit) => {
      seenSignal = init?.signal ?? undefined;
      return new Promise<Response>(() => {});
    }) as unknown as typeof fetch;
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} />);
    submit('https://youtu.be/aqz-KE-bpKQ');
    await waitFor(() => expect(seenSignal).toBeDefined());
    expect(screen.getByRole('button', { name: 'Memuat…' })).toBeTruthy();
    fireEvent.input(screen.getByLabelText('Tempel link video'), { target: { value: 'https://youtu.be/aqz-KE-bpK' } });
    expect(seenSignal?.aborted).toBe(true);
    expect((screen.getByRole('button', { name: 'Pratinjau' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('editing the link after a preview removes it, so the old link cannot be added', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 3_000, live: false });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/a.mp4');
    await screen.findByText('MP4 · 0:03');
    fireEvent.input(screen.getByLabelText('Tempel link video'), { target: { value: 'https://cdn.example.com/b.mp4' } });
    expect(screen.queryByRole('button', { name: /^Tambah:/ })).toBeNull();
    expect(screen.queryByLabelText('Judul (opsional)')).toBeNull();
    expect(session.add).not.toHaveBeenCalled();
  });

  it('pasting a link cancels the probe in flight and clears the preview', async () => {
    const readText = vi.fn(async () => 'https://cdn.example.com/b.mp4');
    vi.stubGlobal('navigator', { ...navigator, clipboard: { readText } });
    const probe = vi.fn((_url: string, _options: ProbeOptions) => new Promise<ProbeOutcome>(() => {}));
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/a.mp4');
    await screen.findByText('MP4 · Memuat…');
    fireEvent.click(screen.getByRole('button', { name: 'Tempel dari papan klip' }));
    await waitFor(() => expect((screen.getByLabelText('Tempel link video') as HTMLInputElement).value).toBe('https://cdn.example.com/b.mp4'));
    expect(probe.mock.calls[0][1].signal?.aborted).toBe(true);
    expect(screen.queryByText(/cdn\.example\.com/)).toBeNull();
  });

  it('announces loading, the preview and an unplayable link in one polite live region', async () => {
    let finish: (outcome: ProbeOutcome) => void = () => {};
    const probe = vi.fn(
      (_url: string, _options: ProbeOptions) =>
        new Promise<ProbeOutcome>((resolve) => {
          finish = resolve;
        }),
    );
    const { container } = render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probe} />);
    const regions = () => container.querySelectorAll('[aria-live="polite"]');
    expect(regions()).toHaveLength(1);
    const region = regions()[0];
    expect(region.textContent).toBe('');
    submit('https://cdn.example.com/a.mov');
    await waitFor(() => expect(region.textContent).toBe('Memuat…'));
    finish({ kind: 'unplayable' });
    await waitFor(() => expect(region.textContent).toBe('a.mov · dari cdn.example.com · Durasi tidak diketahui. Video ini tidak bisa diputar di web'));
    expect(regions()).toHaveLength(1);
    expect(regions()[0]).toBe(region);
  });

  it('announces a YouTube preview in the live region', async () => {
    const { container } = render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200)} />);
    submit('https://youtu.be/aqz-KE-bpKQ');
    await screen.findByText('Blender · 10:35');
    expect(container.querySelector('[aria-live="polite"]')?.textContent).toBe('Big Buck Bunny · Blender · 10:35');
  });

  it('pastes from the clipboard with an icon button and does not preview by itself', async () => {
    const readText = vi.fn(async () => '  https://youtu.be/aqz-KE-bpKQ ');
    vi.stubGlobal('navigator', { ...navigator, clipboard: { readText } });
    const fetchImpl = respond(200);
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} />);
    const paste = screen.getByRole('button', { name: 'Tempel dari papan klip' });
    expect(paste.classList.contains('icon-btn')).toBe(true);
    expect(paste.textContent).toBe('');
    fireEvent.click(paste);
    await waitFor(() => expect((screen.getByLabelText('Tempel link video') as HTMLInputElement).value).toBe('https://youtu.be/aqz-KE-bpKQ'));
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('hides the paste button where the clipboard cannot be read', () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: undefined });
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
    expect(screen.queryByRole('button', { name: 'Tempel dari papan klip' })).toBeNull();
  });

  it('always shows the tip about film sites', () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
    expect(screen.getByText('Link halaman situs film tidak bisa diputar langsung. Fitur deteksi video dari situs ada di aplikasi Musicfy.')).toBeTruthy();
  });
  describe('per handoff L/P', () => {
    const field = () => screen.getByLabelText('Tempel link video') as HTMLInputElement;

    it('marks the field invalid, explains under it with an icon, and hints at what an unrecognized link should be', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
      expect(field().getAttribute('aria-invalid')).toBeNull();
      submit('bukan link');
      const alert = await screen.findByRole('alert');
      expect(alert.textContent).toBe('Link tidak dikenali');
      expect(alert.classList.contains('field-error')).toBe(true);
      expect(alert.querySelector('svg')).toBeTruthy();
      expect(field().getAttribute('aria-invalid')).toBe('true');
      expect(field().getAttribute('aria-describedby')).toBe(alert.id);
      expect(screen.getByText('Tempel link dari YouTube atau link video lain yang bisa diputar di browser.')).toBeTruthy();
      // Editing the link clears the error state.
      fireEvent.input(field(), { target: { value: 'bukan link lagi' } });
      expect(screen.queryByRole('alert')).toBeNull();
      expect(field().getAttribute('aria-invalid')).toBeNull();
      expect(field().getAttribute('aria-describedby')).toBeNull();
    });

    it('gives the hint only for an unrecognized link', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
      submit('https://example.com/film/123');
      await screen.findByRole('alert');
      expect(field().getAttribute('aria-invalid')).toBe('true');
      expect(screen.queryByText(/^Tempel link dari YouTube/)).toBeNull();
    });

    it('looks a link up only on the "Pratinjau" icon button or Enter, never on typing', async () => {
      const fetchImpl = respond(200);
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} />);
      const preview = screen.getByRole('button', { name: 'Pratinjau' }) as HTMLButtonElement;
      expect(preview.type).toBe('submit');
      expect(preview.classList.contains('icon-btn-accent')).toBe(true);
      expect(preview.textContent).toBe('');
      fireEvent.input(field(), { target: { value: 'https://youtu.be/aqz-KE-bpKQ' } });
      fireEvent.change(field());
      await new Promise((r) => setTimeout(r, 0));
      expect(fetchImpl).not.toHaveBeenCalled();
      expect(screen.queryByText('Memuat…')).toBeNull();
      fireEvent.submit(field().form!); // Enter in the field
      expect(await screen.findByText('Blender · 10:35')).toBeTruthy();
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    });

    it('stays usable when reading the clipboard is refused: nothing thrown, the field kept and focused, and it says why', async () => {
      const readText = vi.fn(async () => {
        throw new DOMException('denied', 'NotAllowedError');
      });
      vi.stubGlobal('navigator', { ...navigator, clipboard: { readText } });
      const fetchImpl = respond(200);
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={fetchImpl} />);
      fireEvent.input(field(), { target: { value: 'https://youtu.be/aq' } });
      fireEvent.click(screen.getByRole('button', { name: 'Tempel dari papan klip' }));
      const note = 'Papan klip tidak bisa dibaca. Tempel link langsung di kolom ini.';
      // Shown under the field, and said once by the polite region.
      await waitFor(() => expect(document.querySelector('.add-hint')?.textContent).toBe(note));
      expect(readText).toHaveBeenCalledTimes(1);
      expect(field().value).toBe('https://youtu.be/aq');
      expect(document.activeElement).toBe(field());
      expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe(note);
      expect(screen.getByRole('button', { name: 'Tempel dari papan klip' })).toBeTruthy();
      expect(fetchImpl).not.toHaveBeenCalled();
      // Typing (or a paste that works) clears the note.
      fireEvent.input(field(), { target: { value: 'https://youtu.be/aqz-KE-bpKQ' } });
      expect(screen.queryAllByText(note)).toHaveLength(0);
    });

    it('shows a YouTube preview card: 16:9 thumbnail with Video badge and length, title, YouTube · channel · length', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200)} />);
      submit('https://youtu.be/aqz-KE-bpKQ');
      await screen.findByText('Blender · 10:35');
      const card = document.querySelector('.avp-card')!;
      const art = card.querySelector('.avp-art')!;
      expect(art.getAttribute('aria-hidden')).toBe('true');
      const img = art.querySelector('img')!;
      expect(img.getAttribute('src')).toBe(INFO.thumbnailUrl);
      expect(img.getAttribute('referrerpolicy')).toBe('no-referrer');
      expect(art.querySelector('.badge-video')?.textContent).toBe('Video');
      expect(art.querySelector('.chip-duration')?.textContent).toBe('10:35');
      expect(card.querySelector('.avp-title')?.textContent).toBe('Big Buck Bunny');
      expect(card.querySelector('.avp-meta')?.textContent).toBe('YouTube · Blender · 10:35');
    });

    it('marks a live YouTube preview LIVE, without a length chip', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps fetchImpl={respond(200, { ...INFO, live: true, durationMs: 0 })} />);
      submit('https://youtu.be/aqz-KE-bpKQ');
      await screen.findByText('Blender · LIVE');
      const art = document.querySelector('.avp-art')!;
      expect(art.querySelector('.badge-live')?.textContent).toBe('LIVE');
      expect(art.querySelector('.chip-duration')).toBeNull();
    });

    it('shows a direct-link preview card: the placeholder with badge and length, title, "dari domain" with a link icon, then the format line', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'ready', durationMs: 724_000, live: false })} />);
      submit('https://media.example.com/sawah-pagi.mp4');
      await screen.findByText('MP4 · 12:04');
      const card = document.querySelector('.avp-card')!;
      const placeholder = card.querySelector('.avp-art .video-placeholder')!;
      expect(placeholder.textContent).toContain('media.example.com');
      expect(placeholder.querySelector('.badge-video')).toBeTruthy();
      expect(placeholder.querySelector('.chip-duration')?.textContent).toBe('12:04');
      expect(card.querySelector('img')).toBeNull();
      expect(card.querySelector('.avp-title')?.textContent).toBe('sawah-pagi.mp4');
      const [source, format] = [...card.querySelectorAll('.avp-meta')];
      expect(source.textContent).toBe('dari media.example.com');
      expect(source.querySelector('svg')).toBeTruthy();
      expect(format.querySelector('.badge-video')?.textContent).toBe('Video');
      expect(format.textContent).toBe('VideoMP4 · 12:04');
    });

    it('says "HLS · m3u8 · Durasi tidak diketahui" when a stream has no known length', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'ready', durationMs: 0, live: false })} />);
      submit('https://live.example.org/kota/index.m3u8');
      expect(await screen.findByText('HLS · m3u8 · Durasi tidak diketahui')).toBeTruthy();
      expect(document.querySelector('.avp-art .chip-duration')).toBeNull();
    });

    it('puts "Video ini tidak bisa diputar di web" in the card, in the danger style with an icon', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'unplayable' })} />);
      submit('https://files.example.net/klip.webm');
      const warning = (await screen.findByText('Video ini tidak bisa diputar di web')).parentElement!;
      expect(warning.classList.contains('avp-warn')).toBe(true);
      expect(warning.closest('.avp-card')).toBeTruthy();
      expect(warning.querySelector('svg')).toBeTruthy();
      expect(warning.getAttribute('role')).toBeNull(); // announced once, by the polite region
    });

    it('offers "Judul (opsional)" with the file-name hint as placeholder, and the card shows the typed title', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'ready', durationMs: 1_000, live: false })} />);
      expect(screen.queryByLabelText('Judul (opsional)')).toBeNull();
      submit('https://cdn.example.com/klip.webm');
      const title = (await screen.findByLabelText('Judul (opsional)')) as HTMLInputElement;
      expect(title.placeholder).toBe('Kosongkan untuk pakai nama file');
      expect(title.classList.contains('input')).toBe(true);
      fireEvent.input(title, { target: { value: 'Konser' } });
      expect(document.querySelector('.avp-title')?.textContent).toBe('Konser');
      expect(screen.getByRole('button', { name: 'Tambah: Konser' })).toBeTruthy();
    });

    it('keeps the 52 px pair in view but disabled until there is something to add', async () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
      const pair = () => [screen.getByRole('button', { name: 'Putar berikutnya' }), screen.getByRole('button', { name: 'Tambah' })] as HTMLButtonElement[];
      expect(pair().map((b) => b.disabled)).toEqual([true, true]);
      expect(pair().every((b) => b.classList.contains('btn'))).toBe(true);
      submit('bukan link');
      await screen.findByRole('alert');
      expect(pair().map((b) => b.disabled)).toEqual([true, true]);
    });

    it('shows the tip with an info icon', () => {
      render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
      const tip = screen.getByText(/^Link halaman situs film/).parentElement!;
      expect(tip.classList.contains('add-tip')).toBe(true);
      expect(tip.querySelector('svg')).toBeTruthy();
    });
  });
});
