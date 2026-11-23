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
- The web client never reports track ends and never changes the room by itself.

## URL entries

`src/net/media-url-rules.ts` is the TypeScript twin of `MediaUrlRules.kt`; both are tested against
`modules/ritme-protocol/fixtures/rules/media-urls.json`, so change them together. Pasted links go
through `src/net/video-link.ts` (YouTube links: `fixtures/rules/youtube-links.json`), and a direct
link the rules refuse is never sent. What is sent is exactly what `toUrlEntry` returns: the
WHATWG-normalized url (punycode host, no fragment) and the punycode domain as artist. Only text
shown on screen decodes IDN hosts (`src/net/idn.ts`, "dari bücher.example"), and a label that
mixes Latin with Cyrillic/Greek or decodes to symbols stays punycode. For a `URL` entry the shown
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
