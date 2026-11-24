# Ritme web

Browser companion for Ritme (listen together): create or join a room, see and edit the shared
queue, control playback (as permitted by the host), and listen in sync through the YouTube IFrame
player, or use it as a silent remote. It is served by the Ritme server from the same origin
(`/`, `/r/{code}`), so the WebSocket (`/ritme`), search (`/api/search`) and video info
(`/api/video`) need no CORS.

## Develop

Node 24 and, for the server, JDK 21.

```bash
./gradlew -p ritme-server run          # from the repo root: server on :8080
cd ritme-web && npm ci && npm run dev  # http://localhost:5173 (proxies /ritme and /api to :8080)
```

## Test

```bash
npm test           # Vitest: protocol vs. golden fixtures, clock, drift, reducer, connection, players, UI
npm run build      # tsc --noEmit + vite build -> dist/
npm run size       # bundle budget and self-hosting check on dist/ (see "Size budget")
npm run e2e        # Playwright: builds and starts the real server on :47821 serving dist/ (run build first)
```

`src/protocol.ts` mirrors `modules/ritme-protocol`. Its tests read
`modules/ritme-protocol/fixtures/*.json`, so a protocol change made on one side only fails CI.

## How sync works

- Clock: 5 pings 200 ms apart after every Welcome, then every 30 s; the offset comes from the
  lowest-RTT sample of the last 8 (`src/sync/clock.ts`). Each Welcome starts a fresh window, and
  so does waking from sleep (noticed on return to the tab when `Date.now()` moved more than 1 s
  further than `performance.now()`), followed by a new burst; the forced re-sync then waits for
  the first fresh Pong.
- Playback follows the room snapshot only (`src/player/controller.ts`): new tracks are prepared
  paused and start at the server's anchor; drift over 300 ms is fixed by seeking, at most every
  5 s (room seeks, track changes and returning to the tab re-sync at once; the end of an ad is
  checked immediately). Live entries are never seeked.
- An unknown duration means LIVE: an entry with `durationMs` 0 (a live stream, or a direct link
  whose length the metadata probe could not read) is shown as LIVE and gets no sync seek at all,
  neither when it loads nor for drift. It only follows play and pause, so members watching it can
  drift apart.
- The web client never reports track ends and never changes the room by itself.

## URL entries

`src/net/media-url-rules.ts` is the TypeScript twin of `MediaUrlRules.kt`; both are tested against
`modules/ritme-protocol/fixtures/rules/media-urls.json`, so change them together. Pasted links go
through `src/net/video-link.ts` (YouTube links: `fixtures/rules/youtube-links.json`), and a direct
link the rules refuse is never sent. What is sent is exactly what `toUrlEntry` returns: the
WHATWG-normalized url (punycode host, no fragment) and the punycode domain as artist. Only text
shown on screen decodes IDN hosts (`src/net/idn.ts`, "dari bücher.example"), and a label that
mixes Latin with Cyrillic/Greek, is all Cyrillic or all Greek under an ASCII TLD (`аррӏе.com`), or
decodes to symbols stays punycode. For a `URL` entry the shown
domain comes from its url, not from the artist text another client sent. Titles are checked like
the server does (`src/net/visible-text.ts`, a port of `RoomLogic.hasVisible(cleanText())`), so an
Add is never refused for a title that only looks filled.

The rules are pure string checks: like the server (spec D6), the web never resolves a host, and it
has no guard after DNS. A public DNS name that resolves to a private or loopback address
(`127.0.0.1.nip.io`, a router's own name such as `fritz.box`) therefore passes by design. Against
those the playing browser's own Local Network Access protections (permission prompts or blocks for
public pages reaching private addresses) are what applies.

## Privacy

No analytics, no cookies of our own, no external fonts or CDNs. The only third-party requests are
the YouTube IFrame API script and the privacy-enhanced embed (`youtube-nocookie.com`) in listen
mode, thumbnails (sent without a referrer), the media of `URL` entries, and, in the "Video" tab,
the metadata of a pasted media link (a hidden `<video preload="metadata" muted>`, to the host that
link points at). A Content Security Policy in `index.html` enforces this. The display name is kept
in `localStorage`, the room's resume token in `sessionStorage` (this tab only).

## Design: tokens, fonts, icons

The UI follows the designer handoff in `docs/design/handoff/` (`ritme-web-handoff.html`, with the
decisions on open points in `DECISIONS.md`); frame IDs such as `eBVC3` in comments and screenshot
names refer to its screens.

- Design tokens: `src/styles/tokens.css` is the single source, copied from the handoff's "Token
  warna" table (dark first, light under `prefers-color-scheme: light`), plus radii, spacing, touch
  sizes, the type scale and motion timings. `src/styles/tokens.test.ts` pins the table. Screens use
  the variables only; the stylesheets are split per concern and imported in order by
  `src/styles.css` (tokens, base, components, then screens and motion last).
- Inter is self-hosted from `@fontsource/inter@5.3.0` (SIL OFL 1.1, `src/fonts/OFL.txt`): weight
  600 for all text, 400 as a fallback. No font CDN, so the CSP stays as it is.
- Icons are Material Symbols Rounded (weight 400, Apache-2.0, `src/ui/icon-paths.LICENSE.txt`) as
  inline SVG paths in `src/ui/icon-paths.ts`. To add one, add its name to `NAMES` in
  `scripts/build-icons.mjs` and regenerate: `npm pack @material-symbols/svg-400@0.49.0` into a
  temp dir, untar it, then `node scripts/build-icons.mjs <dir>/package/rounded`.
- Every animation and transition sits behind `prefers-reduced-motion: no-preference`; with reduced
  motion nothing moves (`src/styles/base.css`, `motion.css`).

## Size budget

```bash
npm run build && npm run size
```

`scripts/check-size.mjs` reads `dist/` and fails when the main JS chunk (the script
`dist/index.html` loads; the lazy hls.js chunk does not count) is over 45 kB gzip, when the CSS is
over 10 kB gzip, or when anything in `dist/` points at an external font or stylesheet. It uses
node's `zlib` only, and kB means 1000 bytes, as Vite reports it.

## Screenshots

`npm run build && npm run shots` runs `e2e/redesign.shots.ts` with the real server (started through
Gradle, like the e2e), the fake YouTube and gradient artwork, and writes one PNG per screen for
390×844 and 1440×900, dark and light (projects `mobile-dark`, `mobile-light`, `desktop-dark`,
`desktop-light`), to `test-results/redesign/<set>/<project>/<screen>.png` (gitignored).

- Sets: `after` by default; `SHOTS_SET=before npm run shots` writes a `before/` baseline to
  compare with.
- Tags pick a part: `npm run shots -- --grep @room`. They are `@home`, `@room`, `@desktop`,
  `@overlays`, `@locked`, `@queue`, `@add`, `@addvideo`, `@members`, `@status` and `@video`
  (`@add` also matches `@addvideo`; use `--grep "@add$"` for the song sheet only).
- Each capture also runs a soft touch-target audit: every visible control smaller than 44 × 44
  (inline text links, controls behind an open modal and visually hidden ones excepted) is listed as
  a `[touch-target]` console warning and a `touch-target` annotation on the test. It never fails a
  run.
