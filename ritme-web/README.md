# Ritme web

Browser companion for Ritme (listen together): create or join a room, see and edit the shared
queue, control playback (as permitted by the host), and listen in sync through the YouTube IFrame
player, or use it as a silent remote. It is served by the Ritme server from the same origin
(`/`, `/r/{code}`), so the WebSocket (`/ritme`) and search (`/api/search`) need no CORS.

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

## Privacy

No analytics, no cookies of our own, no external fonts or CDNs. The only third-party requests are
the YouTube IFrame API script and the privacy-enhanced embed (`youtube-nocookie.com`) in listen
mode, thumbnails (sent without a referrer), and the media of `URL` entries. A Content Security
Policy in `index.html` enforces this. The display name is kept in `localStorage`, the room's
resume token in `sessionStorage` (this tab only).
