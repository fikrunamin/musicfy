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
the golden fixtures with `./gradlew :modules:ritme-protocol:test -PupdateGolden=true`.

## Configuration

| Env | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `PUBLIC_URL` | `http://localhost:8080` | Public origin used in share links |
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

The app's server URL setting must be `https://<RITME_DOMAIN>`.

## Endpoints

| Method | Path | |
|---|---|---|
| GET | `/healthz` | `ok` |
| WS | `/ritme` | Room protocol (see `modules/ritme-protocol`) |
| GET | `/api/search?q=` | Up to 20 songs `{videoId,title,artist,durationMs,thumbnailUrl}`; 30/min/IP |
| GET | `/`, `/r/{code}` | Web client, or the landing page with a `musicfy://ritme/{code}` button |

## Privacy

Rooms exist only in memory and disappear 60 s after the last member disconnects. The server
logs no names, songs, search queries or IP addresses: only startup and errors.
