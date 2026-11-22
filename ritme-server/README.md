# Ritme server

Relay for Ritme (listen together). It keeps rooms in memory, syncs queue and playback over
WebSocket `/ritme`, and offers metadata-only song search for the web client. It never streams
audio and never writes to disk.

## Develop

From the repository root (JDK 21):

```bash
./gradlew -p ritme-server test          # unit + integration tests
./gradlew -p ritme-server run           # http://localhost:8080
```

Protocol shapes live in `modules/ritme-protocol`. After an intentional protocol change, refresh
the golden fixtures with `./gradlew -p ritme-server :ritme-protocol:test -PupdateGolden=true` and
review `git diff modules/ritme-protocol/fixtures/`.

## Configuration

| Env | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `PUBLIC_URL` | `http://localhost:8080` | Reserved for share links; not used by the server yet |
| `WEB_DIR` | `/app/web` | Built web client; without `index.html` a small landing page is served |

## Deploy on a VPS (Docker + Caddy)

Requirements: Docker with the compose plugin, ports 80 and 443 open, and a DNS record for your
domain pointing at the VPS.

```bash
git clone <this repo> musicfy && cd musicfy/ritme-server/deploy
cp .env.example .env        # set RITME_DOMAIN
docker compose up -d --build
curl https://$RITME_DOMAIN/healthz   # -> ok
```

Update: `git pull && docker compose up -d --build`. Logs: `docker compose logs -f ritme`.

Rooms live only in memory, so every deploy or restart ends all rooms; clients get disconnected
and have to create a new room. Deploy when nobody is listening.

Container logs are rotated (json-file, 10 MB x 3 per service); the server runs with a 768 MB
memory limit (heap: 75 % of it, ~576 MB). Every 10 minutes it logs counts only: rooms, room-state
bytes, connections, unsent bytes and dropped clients.

### Heap budget

The limits are sized so their worst cases fit the ~576 MB heap (see `Limits.kt`):

| Part | Bound | Worst case in heap |
|---|---|---|
| Baseline (JVM, Ktor, Netty, caches) | measured | ~80 MB |
| Idle sockets | 1500 connections x ~60 KB | ~90 MB |
| Unsent outbound bytes | server-wide budget | 96 MB |
| Rooms at rest | 120 MB of encoded snapshots x 1.3 (measured heap/encoded: 0.88-1.27) | ~156 MB |
| Headroom (GC, in-flight encodes) | rest | >= 150 MB |

At most 1500 rooms, of which at most 480 can be full (120 MB / 256 KB).

The app's server URL setting must be `https://<RITME_DOMAIN>`.

## Endpoints

| Method | Path | |
|---|---|---|
| GET | `/healthz` | `ok` |
| WS | `/ritme` | Room protocol (see `modules/ritme-protocol`) |
| GET | `/api/search?q=` | Up to 20 songs `{videoId,title,artist,durationMs,thumbnailUrl}`; 30/min per IP (429), 300 uncached/min in total (503) |
| GET | `/`, `/r/{code}` | Web client, or the landing page with a `musicfy://ritme/{code}` button |

## Protocol notes

- Inbound WebSocket frames are limited to 256 KB. Send `Create` with at most 200 initial entries
  and the rest as `Add` batches of at most 200.
- A room's state is capped at 256 KB of JSON (a typical 500-entry queue is ~200 KB): `Add`
  beyond it gets `QUEUE_FULL`, and an over-large `Create` keeps the initial entries that fit.
  All rooms together are capped at 120 MB of snapshots: past that, `Add` gets `QUEUE_FULL` and
  `Create` is trimmed or refused with `ROOM_FULL`. `Welcome`/`State` carry the full room, so
  server frames stay below ~260 KB; clients must accept inbound frames of at least 1 MB.
- Titles, artists and names are cleaned on the server (NFC; control, format and zero-width
  characters removed).
- Clients decode with `decodeServerMessageOrNull` (unknown message types: skip), and must handle
  `MediaKind.UNKNOWN` (skip the entry) and `ErrorCode.UNKNOWN` (generic failure).
- The server accepts protocol versions `MIN_SUPPORTED_PROTOCOL_VERSION..PROTOCOL_VERSION`;
  `Welcome.protocolVersion` is the server's version.
- Entries: YouTube kinds carry a `videoId` matching `[A-Za-z0-9_-]{1,64}` and no `url`; `URL`
  entries carry an http(s) `url` (<= 2048 chars) and `videoId = ""`. `thumbnailUrl` is null or
  https (<= 512 chars), for YouTube kinds only from `*.ytimg.com`, `*.googleusercontent.com` or
  `*.ggpht.com`. `durationMs` is 0 (live) to 24 h.

### Abuse limits (`/ritme`)

| Limit | Value | When exceeded |
|---|---|---|
| Join deadline | a socket must have joined a room within 10 s | closed (policy violation), no message |
| Join limiter | per IP (IPv6: per /64): burst 30, refilled 10/min; charged for every `Create` and for failed Joins (`ROOM_NOT_FOUND`, `ROOM_FULL`, `UPGRADE_REQUIRED`); successful joins and resumes are free but need a non-empty bucket | `Error(RATE_LIMITED)`, closed |
| Concurrent sockets | 48 per IP, 1500 total (~60 KB per idle socket, ~90 MB at the cap) | `Error(RATE_LIMITED)`, closed (1013 try again later), hung up within 0.5 s |
| Rooms | 1500 total; 120 MB of snapshots in total | `Error(ROOM_FULL)` on Create, closed |
| Inbound frames | 20/s per socket, burst 40 (pings and garbage included) | closed, no message |
| Commands + TrackEnded | 10/s per member | `Error(RATE_LIMITED)` (TrackEnded: ignored) |
| Unsent messages per client | snapshots coalesce (only the latest `State` waits); at most 2 MB or 64 messages | disconnected; resume with the token for fresh state |
| Stalled client | a frame not written to the socket within 20 s | disconnected |
| Server-wide unsent bytes | 96 MB | clients that are behind are disconnected; `Create`/`Join` get `RATE_LIMITED` until it drains |

A client that ignores the server's Close frame is hung up on after at most 3 s.

## Privacy

Rooms exist only in memory and disappear 60 s after the last member disconnects. Nothing is
written to disk.

What passes through memory while a room exists: display names, the queue (titles, artists,
videoIds, URLs, thumbnail links), playback state and resume tokens. Client IP addresses are held
only as rate-limit and connection-count keys (IPv6 reduced to its /64). None of this is ever
persisted or logged: the server logs only startup, errors and periodic room/connection counts.
Search queries are cached in memory for 10 minutes and never logged.

Caddy is configured not to log either: access logs are off and the request error log
(`http.log.error`), Go's server log (`http.stdlib`, which would print client IPs on TLS errors)
and the reverse-proxy logger are excluded in the Caddyfile's global options. Caddy still logs
startup and certificate events.

Exposure note: a room's members see each other's queue entries. `URL` entries and every
thumbnail are loaded by each member's device directly from the given host, so whoever adds a
`URL` entry (or a non-YouTube thumbnail on a `URL` entry) learns the IP addresses of the members
who play or display it. YouTube-kind thumbnails are restricted to YouTube's image hosts for this
reason. Clients should only play `URL` entries from people they trust.
