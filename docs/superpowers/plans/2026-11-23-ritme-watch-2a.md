# Ritme Watch-Together 2a (Server + Web) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let rooms hold videos: the server gets `GET /api/video?id=`, hardened `URL`-entry validation and the `guestsCanAdd` room setting; the web client gets a "Video" tab that adds YouTube links and direct mp4/webm/m3u8 links; the Android room sheet gets the host's "Anggota boleh menambah" switch.

**Architecture:** One shared, pure URL-shape rule set (`MediaUrlRules`) lives in `:modules:ritme-protocol`, with a JSON fixture that the Kotlin tests and a TypeScript twin both read. The server's search cache is generalised into `SingleFlightCache` + a shared `UpstreamBudget`, and `/api/video` reuses both with one InnerTube `music/get_queue` call (metadata only). The web Video tab classifies the pasted link locally, asks `/api/video` for YouTube links, and probes direct links with a hidden `<video preload="metadata" muted>`. `RoomSettings.guestsCanAdd` (default `true`) is enforced in `RoomLogic` and mirrored by every client.

**Tech Stack:** Kotlin 2.3 + Ktor 3.4.0 (server, its own Gradle build in `ritme-server/` using `../gradle/libs.versions.toml`), kotlinx.serialization, JUnit 4; Preact 10 + TypeScript 5.9 + Vite 7 + Vitest 4 + Playwright 1.65 (web); Jetpack Compose (Android).

## Global Constraints

- Work in `/Users/suryasoft/WebstormProjects/musicfy/.worktrees/ritme-watch` (branch `feature/ritme-watch`). Every command below runs from that directory unless it says `cd`.
- Scope is sub-project **2a only** (spec `docs/superpowers/specs/2026-11-23-ritme-watch-together-design.md` §3.1–3.3, §6.1, §10 row 2a; decisions U6 and U8 approved). No Android video playback, no `streamType`/`referrer`, no in-app browser, no `:modules:ritme-media`.
- **Server:** Ktor 3.4.0. Tests: `./gradlew --console=plain -p ritme-server test :ritme-protocol:test`. Before every Gradle command (server, Android, and `npm run e2e`, which runs Gradle) run `pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE`; continue only on `FREE` (one Gradle build machine-wide). JDK: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH`.
- **Protocol stays backward compatible:** `PROTOCOL_VERSION` stays `1`; every new field has a default; old clients still decode (`ignoreUnknownKeys`). Refresh golden fixtures only with `./gradlew --console=plain -p ritme-server :ritme-protocol:test -PupdateGolden=true` and review `git diff modules/ritme-protocol/fixtures/`.
- Shared rule fixtures go in `modules/ritme-protocol/fixtures/rules/` (a subdirectory, because `ritme-web/src/protocol.test.ts` requires every top-level `fixtures/*.json` to be a message sample).
- The server **never** fetches, probes or DNS-resolves an entry URL (spec D6), and never logs a video id, URL, title or query. `/api/video` logs only `video backend failed: <ExceptionClass>`.
- `/api/video` (spec 3.1, exact): `id` matches `^[A-Za-z0-9_-]{11}$` else 400; own `IpRateLimiter` 30/min per IP (429); upstream budget **shared with search**, 300/min global (503); one `POST https://music.youtube.com/youtubei/v1/music/get_queue` (`WEB_REMIX`, same headers as search) with body `{"videoIds":[id]}`; 200 `{videoId,title,channel,durationMs,thumbnailUrl,live}`; 404 when there is no renderer; 502 when the backend fails; LRU 1000 entries, TTL 6 h (live: 5 min), single flight per id. `thumbnailUrl` only on `i.ytimg.com`, `*.ytimg.com`, `*.googleusercontent.com`, `*.ggpht.com` (https, ≤ 512 chars), else `null`.
- `MediaUrlRules` (spec 3.2, exact): http/https only; host required; ≤ 2048 chars; reject userinfo, whitespace/`\p{Cc}`/backslash, `#fragment`; reject `localhost`, `*.localhost`, `*.local`, `*.internal`, loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, fe80::/10), CGNAT 100.64/10, `0.0.0.0`, multicast.
- **Web:** Node via `source ~/.nvm/nvm.sh >/dev/null;`. Full check: `source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck && npm run build && npm run e2e` (e2e needs the JDK export and the Gradle guard).
- The CSP in `ritme-web/index.html` must not change (`media-src 'self' blob: https: http:; connect-src 'self' https: http:` already cover the probe). No new npm dependencies, no new third-party requests (`/api/video` is same-origin).
- All web copy goes in `ritme-web/src/ui/strings.ts`, in Indonesian, using the spec/brief wording verbatim where given.
- **UI must match the existing components exactly.** Web: only existing classes (`card`, `segmented`, `search-form`, `queue`, `queue-row`, `queue-text`, `title`, `muted small`, `queue-actions`, `btn btn-small`, `btn-primary`, `badge`, `field`, `stack`, `switch`, `error`, `visually-hidden`) plus one size modifier `.thumb-wide` (78 × 44, brief §12). Android: reuse `RitmeMenuSwitchRow` (Task 11 of the Android plan) and `Material3MenuGroup`; no new components.
- **Android:** strings in `app/src/main/res/values/ritme_strings.xml` (English) and `app/src/main/res/values-in/ritme_strings.xml` (Indonesian). Gradle always with the light flags `--max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g`; assemble flavors in separate invocations.
- Commits: one per task, message ending with the trailer line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push (the user pushes).
- Deploy: once merged to `main` and pushed by the user, the "Ritme server" workflow publishes `ghcr.io/fikrunamin/ritme-server:latest`; the VPS update is `cd /opt/ritme && docker compose pull && docker compose up -d` (`ssh dubeli.id`, root).

## Decisions made while planning (read before reviewing)

1. **Fixture location.** `media-urls.json` and `youtube-links.json` live in `modules/ritme-protocol/fixtures/rules/`. `youtube-links.json` is only tested by the web for now; 2b's Kotlin `YouTubeLinks` will read the same file.
2. **URL rules go slightly beyond the spec list**, all in the spirit of U8 ("a member cannot make other members' devices reach their LAN"): numeric hosts that are not a canonical dotted quad (`2130706433`, `0x7f.1`, `127.1`, `010.0.0.1`) are rejected as `NON_CANONICAL_IP` (browsers read them as IPv4), single-label hosts (`router`, `nas`) and `*.lan`, `*.home.arpa` count as local, IPv4-mapped/NAT64/IPv4-compatible IPv6 literals are checked by their embedded IPv4, `fec0::/10` and `240/4` are rejected, non-ASCII hosts are `MALFORMED` (clients send punycode). The server also applies the rules to the `thumbnailUrl` of `URL` entries.
3. **Negative caching.** A `/api/video` 404 is cached for 5 min like a live video, so a repeated bogus id does not spend the shared upstream budget. Only "no renderer" or "renderer for another id" is 404; `unplayableText` is ignored.
4. **`SearchOverloadedException`** becomes `typealias SearchOverloadedException = UpstreamOverloadedException`, so `SearchTest` stays byte-for-byte unchanged and green.
5. **SetSettings replaces all settings.** Every client therefore sends the full settings object, built from the latest snapshot with one field changed. Without this, toggling "Anggota boleh kontrol" would reset `guestsCanAdd` to `true`. (An old Android build still does that: risk R9, accepted, documented in the server README.)
6. **Web Video tab:** the preview starts on the "Pratinjau" button (or Enter), not per keystroke (the endpoint allows 30/min per IP). `music.youtube.com` links become `YOUTUBE_MUSIC` (same as Android, spec 4.6). The add buttons stay disabled while a direct link is being probed (≤ 10 s). The field and preview clear after adding. The brief §13 tip is always shown under the field.
7. **`guestsCanAdd = false` hides the add UI** for guests: the whole "Tambah lagu" card on the web, the "Tambah lagu" row in the Android room sheet. Android's song-tap/menu interception refuses with the existing `ritme_toast_forbidden` toast.
8. **Minimal video rows (spec §10 2a fallback, designs not delivered):** web queue rows for `YOUTUBE_VIDEO`/`URL` entries get a 16:9 thumbnail (`.thumb-wide`, ▶ placeholder) and a "Video" badge. Nothing else of brief §12/§13 is built.
9. One shared `HttpClient` serves search and video info.

---

## File Structure

| Path | Responsibility |
|---|---|
| `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt` | `RoomSettings.guestsCanAdd` (modify) |
| `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRules.kt` | Shared URL-shape rules (create) |
| `modules/ritme-protocol/fixtures/rules/media-urls.json` | Shared verdicts for Kotlin + TS (create) |
| `modules/ritme-protocol/fixtures/rules/youtube-links.json` | Shared YouTube link cases (create; web reads it now, 2b later) |
| `modules/ritme-protocol/fixtures/{server-state,server-welcome,cmd-set-settings}.json` | Regenerated goldens |
| `modules/ritme-protocol/src/test/kotlin/.../{Samples,ForwardCompatibilityTest,MediaUrlRulesTest}.kt` | Protocol tests |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt` | `guestsCanAdd`, `MediaUrlRules` (modify) |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/SingleFlightCache.kt` | `SingleFlightCache`, `UpstreamBudget`, `UpstreamOverloadedException` (create) |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/InnerTubeJson.kt` | JSON path helpers shared by the parsers (create) |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Search.kt` | Search on `SingleFlightCache`; shared InnerTube headers/context (modify) |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Video.kt` | `VideoInfo`, backend, parser, `VideoInfoService` (create) |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/{Application,Limits}.kt` | `/api/video` route, deps, limits (modify) |
| `ritme-server/src/test/kotlin/.../{SingleFlightCacheTest,VideoTest}.kt` + `src/test/resources/innertube-get-queue-*.json` | Server tests (create) |
| `ritme-web/src/protocol.ts` | `guestsCanAdd` mirror (modify) |
| `ritme-web/src/net/media-url-rules.ts` | TS twin of `MediaUrlRules` (create) |
| `ritme-web/src/net/video-link.ts` | YouTube link parser + pasted-link classifier (create) |
| `ritme-web/src/net/video.ts` | `/api/video` client, `toVideoEntry`, `toUrlEntry` (create) |
| `ritme-web/src/net/media-probe.ts` | Hidden `<video>` metadata probe (create) |
| `ritme-web/src/ui/add-video.tsx` | Video tab content (create) |
| `ritme-web/src/ui/search.tsx` | Card with Lagu/Video tabs (modify) |
| `ritme-web/src/ui/{queue,members,room,strings}.tsx/.ts`, `src/styles.css`, `src/session/{session,permissions}.ts` | Video rows, switch, hiding, copy (modify) |
| `ritme-web/e2e/smoke.spec.ts` | Video and guests-add flows (modify) |
| `app/src/main/kotlin/com/example/musicfy/ritme/session/{RitmeSession,RitmePermissions}.kt`, `ritme/RitmeController.kt`, `ui/screens/ritme/RitmeRoomSheet.kt`, `res/values*/ritme_strings.xml` | Android `guestsCanAdd` (modify) |

---

### Task 1: `RoomSettings.guestsCanAdd` (protocol, server, TS mirror)

**Files:**
- Modify: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt:21-22`
- Modify: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/Samples.kt:50`
- Modify: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/ForwardCompatibilityTest.kt` (new test)
- Regenerate: `modules/ritme-protocol/fixtures/server-state.json`, `server-welcome.json`, `cmd-set-settings.json`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt:45-67`
- Modify: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomLogicTest.kt` (new tests)
- Modify: `ritme-server/README.md` ("Protocol notes")
- Modify: `ritme-web/src/protocol.ts`, `ritme-web/src/protocol.test.ts`, `ritme-web/src/session/session.ts`, `ritme-web/src/session/session.test.ts`, `ritme-web/src/testing/builders.ts`, `ritme-web/src/ui/room-panels.test.tsx:60`, `ritme-web/src/ui/queue.test.tsx:17`, `ritme-web/src/session/permissions.test.ts:6`

**Interfaces:**
- Produces (Kotlin): `data class RoomSettings(val guestsCanControl: Boolean = true, val guestsCanAdd: Boolean = true)`. `RoomLogic.apply` answers `Command.Add` from a non-host with `FORBIDDEN` when `guestsCanAdd` is false.
- Produces (TS): `interface RoomSettings { guestsCanControl: boolean; guestsCanAdd: boolean }`; `Session` has a private `sendSettings(change: Partial<RoomSettings>)` that merges into the latest snapshot's settings (Task 7 adds `setGuestsCanAdd` on top of it).

- [ ] **Step 1: Write the failing Kotlin tests**

Append to `ForwardCompatibilityTest` (inside the class):

```kotlin
    @Test
    fun settingsFromAnOlderPeerDefaultGuestsCanAddToTrue() {
        val state = """{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","settings":{"guestsCanControl":false}}}"""
        assertEquals(
            RoomSettings(guestsCanControl = false, guestsCanAdd = true),
            (decodeServerMessageOrNull(state) as ServerMessage.State).state.settings,
        )
        val cmd = """{"type":"Command","reqId":"r1","cmd":{"type":"SetSettings","settings":{"guestsCanControl":true}}}"""
        assertEquals(
            Command.SetSettings(RoomSettings(guestsCanControl = true, guestsCanAdd = true)),
            (decodeClientMessage(cmd) as ClientMessage.Send).cmd,
        )
    }
```

Append to `RoomLogicTest` (inside the class):

```kotlin
    @Test
    fun guestsMayNotAddWhileTheHostTurnedAddingOff() {
        val noAdds = playingA.copy(settings = RoomSettings(guestsCanControl = true, guestsCanAdd = false))
        assertEquals(ErrorCode.FORBIDDEN, rejected(apply(noAdds, GUEST, Command.Add(listOf(newEntry())))))
        assertEquals(4, ok(apply(noAdds, HOST, Command.Add(listOf(newEntry())))).queue.size)
        // Adding and controlling are separate switches.
        assertFalse(ok(apply(noAdds, GUEST, Command.Pause)).playback.isPlaying)
        val addsOnly = playingA.copy(settings = RoomSettings(guestsCanControl = false, guestsCanAdd = true))
        assertEquals(4, ok(apply(addsOnly, GUEST, Command.Add(listOf(newEntry())))).queue.size)
    }

    @Test
    fun setSettingsReplacesBothSwitches() {
        val changed = ok(apply(playingA, HOST, Command.SetSettings(RoomSettings(guestsCanControl = false, guestsCanAdd = false))))
        assertEquals(RoomSettings(guestsCanControl = false, guestsCanAdd = false), changed.settings)
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
```
Expected: FAIL, compilation error `No parameter with name 'guestsCanAdd' found`.

- [ ] **Step 3: Implement**

`Models.kt` line 21-22, replace

```kotlin
@Serializable
data class RoomSettings(val guestsCanControl: Boolean = true)
```

with

```kotlin
/**
 * Host switches. SetSettings replaces all of them, so clients send every field (the latest
 * snapshot's values with one changed). A field missing on the wire takes its default, which is
 * how settings from an older peer decode.
 */
@Serializable
data class RoomSettings(
    /** Members other than the host may Play, Pause, Seek, Next, Previous, PlayEntry, Move and remove others' entries. */
    val guestsCanControl: Boolean = true,
    /** Members other than the host may Add (spec U6). */
    val guestsCanAdd: Boolean = true,
)
```

`RoomLogic.kt`, in `apply`: after `val canControl = isHost || state.settings.guestsCanControl` add

```kotlin
        val canAdd = isHost || state.settings.guestsCanAdd
```

and replace the line `is Command.Add -> add(state, by, cmd, now, ids, size)` with

```kotlin
            is Command.Add -> if (canAdd) add(state, by, cmd, now, ids, size) else FORBIDDEN
```

`Samples.kt` line 50, replace `"cmd-set-settings" to send(Command.SetSettings(RoomSettings(guestsCanControl = false))),` with

```kotlin
        "cmd-set-settings" to send(Command.SetSettings(RoomSettings(guestsCanControl = false, guestsCanAdd = false))),
```

- [ ] **Step 4: Regenerate the golden fixtures and review them**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server :ritme-protocol:test -PupdateGolden=true
git diff --stat modules/ritme-protocol/fixtures/
git diff modules/ritme-protocol/fixtures/
```
Expected: exactly 3 files changed. `server-state.json` and `server-welcome.json` each change only
```
-            "guestsCanControl": true
+            "guestsCanControl": true,
+            "guestsCanAdd": true
```
and `cmd-set-settings.json` changes only
```
-            "guestsCanControl": false
+            "guestsCanControl": false,
+            "guestsCanAdd": false
```
Anything else in the diff is a bug: stop and fix it.

- [ ] **Step 5: Run the Kotlin tests**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 6: Mirror the field in TypeScript (tests first)**

`ritme-web/src/protocol.test.ts`:
- line 45 (`const state`): `settings: { guestsCanControl: true, guestsCanAdd: true },`
- line 99: `'cmd-set-settings': send({ type: 'SetSettings', settings: { guestsCanControl: false, guestsCanAdd: false } }),`
- line 189 (minimal-state expectation): `settings: { guestsCanControl: true, guestsCanAdd: true },`
- add inside `describe('forward compatibility (mirrors ForwardCompatibilityTest.kt)', ...)`:

```ts
  it('defaults guestsCanAdd to true for settings from an older server', () => {
    const msg = parseServerMessage(
      '{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","settings":{"guestsCanControl":false}}}',
    );
    expect(msg?.type === 'State' && msg.state.settings).toEqual({ guestsCanControl: false, guestsCanAdd: true });
  });
```

`ritme-web/src/session/session.test.ts`, add inside `describe('Session', ...)`:

```ts
  it('SetSettings sends every setting, changing only the one asked for', () => {
    const t = setup();
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.session.setGuestsCanControl(false);
    expect(t.conns[0].commands).toEqual([{ type: 'SetSettings', settings: { guestsCanControl: false, guestsCanAdd: true } }]);
  });
```

Literal `settings` objects elsewhere (otherwise `npm run typecheck` fails):
- `src/testing/builders.ts` line 24: `settings: { guestsCanControl: true, guestsCanAdd: true },`
- `src/ui/room-panels.test.tsx` line 60: `settings: { guestsCanControl, guestsCanAdd: true },`
- `src/ui/queue.test.tsx` line 17: `room={room({ queue, currentEntryId: 'a', settings: { guestsCanControl, guestsCanAdd: true } })}`
- `src/session/permissions.test.ts` line 6: `const locked = room({ settings: { guestsCanControl: false, guestsCanAdd: true } });`

- [ ] **Step 7: Run the web tests to verify they fail**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npx vitest run src/protocol.test.ts src/session/session.test.ts
```
Expected: FAIL (`cmd-set-settings` encodes without `guestsCanAdd`; `server-state`/`server-welcome` parse without it; the new tests fail).

- [ ] **Step 8: Implement the TS mirror**

`src/protocol.ts`: replace the `RoomSettings` interface with

```ts
export interface RoomSettings {
  guestsCanControl: boolean;
  /** Members other than the host may Add (default true; older servers omit it). */
  guestsCanAdd: boolean;
}
```

in `encodeCommand` replace the `SetSettings` case with

```ts
    case 'SetSettings':
      return {
        type: 'SetSettings',
        settings: { guestsCanControl: cmd.settings.guestsCanControl, guestsCanAdd: cmd.settings.guestsCanAdd },
      };
```

and replace `parseSettings` with

```ts
function parseSettings(v: unknown): RoomSettings {
  if (v === undefined) return { guestsCanControl: true, guestsCanAdd: true };
  const o = obj(v);
  return { guestsCanControl: bool(o, 'guestsCanControl', true), guestsCanAdd: bool(o, 'guestsCanAdd', true) };
}
```

`src/session/session.ts`: change the import to `import type { Command, NewEntry, RoomSettings } from '../protocol';`, replace `setGuestsCanControl` with

```ts
  setGuestsCanControl(value: boolean): void {
    this.sendSettings({ guestsCanControl: value });
  }
```

and add next to `send`:

```ts
  /** SetSettings replaces every setting: keep the others as the latest snapshot has them. */
  private sendSettings(change: Partial<RoomSettings>): void {
    const current = this.state.room?.settings ?? { guestsCanControl: true, guestsCanAdd: true };
    this.send({ type: 'SetSettings', settings: { ...current, ...change } });
  }
```

- [ ] **Step 9: Run the web unit tests and typecheck**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck
```
Expected: all tests pass; `tsc --noEmit` prints nothing.

- [ ] **Step 10: Document it**

In `ritme-server/README.md` "Protocol notes", after the `Entries:` bullet add:

```markdown
- Settings: `guestsCanControl` and `guestsCanAdd` (both default `true`). With `guestsCanAdd = false`
  only the host may `Add` (others get `FORBIDDEN`). `SetSettings` replaces all settings, so clients
  send every field, taking the others from the latest snapshot. A host on an older client that does
  not know `guestsCanAdd` resets it to `true` whenever it changes another setting (accepted: the web
  client always ships with the server).
```

- [ ] **Step 11: Commit**

```bash
git add modules/ritme-protocol ritme-server ritme-web/src
git commit -m "feat(ritme): RoomSettings.guestsCanAdd, host-only Add when off (protocol, server, web mirror)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `MediaUrlRules` and hardened `URL` entries on the server

**Files:**
- Create: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRules.kt`
- Create: `modules/ritme-protocol/fixtures/rules/media-urls.json`
- Create: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRulesTest.kt`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt:315-341`
- Modify: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomLogicTest.kt` (new tests)
- Modify: `ritme-server/README.md` ("Protocol notes" Entries bullet, "Privacy")

**Interfaces:**
- Produces: `object MediaUrlRules { const val MAX_URL_LENGTH = 2_048; enum class Rejection { TOO_LONG, FORBIDDEN_CHARACTER, FRAGMENT, MALFORMED, SCHEME, USERINFO, NO_HOST, NON_CANONICAL_IP, LOCAL_HOST }; fun check(url: String): Rejection?; fun isAcceptableMediaUrl(url: String): Boolean }`
- Produces: fixture `{"cases":[{"url": String, "reject": RejectionName | null}]}` that Task 5's TS twin reads; reason names are the enum names.

- [ ] **Step 1: Write the shared fixture**

Create `modules/ritme-protocol/fixtures/rules/media-urls.json`:

```json
{
  "_comment": "Shared by MediaUrlRulesTest.kt and ritme-web src/net/media-url-rules.test.ts. reject: null = accepted, else the MediaUrlRules.Rejection name. Order of checks: TOO_LONG, FORBIDDEN_CHARACTER, FRAGMENT, MALFORMED/SCHEME, USERINFO, MALFORMED (port/host), NO_HOST, then host rules.",
  "cases": [
    { "url": "https://cdn.example.com/video.mp4", "reject": null },
    { "url": "http://cdn.example.com/video.mp4", "reject": null },
    { "url": "HTTPS://CDN.EXAMPLE.COM/Video.MP4", "reject": null },
    { "url": "https://cdn.example.com:8443/live/index.m3u8?token=abc&exp=1700000000", "reject": null },
    { "url": "https://cdn.example.com", "reject": null },
    { "url": "https://cdn.example.com?x=1", "reject": null },
    { "url": "https://cdn.example.com:/v.mp4", "reject": null },
    { "url": "https://8.8.8.8/v.mp4", "reject": null },
    { "url": "https://172.32.0.1/v.mp4", "reject": null },
    { "url": "https://100.128.0.1/v.mp4", "reject": null },
    { "url": "https://[2001:db8::1]/v.mp4", "reject": null },
    { "url": "https://[2606:4700::6810:85e5]:443/v.mp4", "reject": null },
    { "url": "https://[::ffff:8.8.8.8]/v.mp4", "reject": null },
    { "url": "http://[1:2:3:4:5:6:7:8]/v.mp4", "reject": null },
    { "url": "https://my_cdn.example.com/v.mp4", "reject": null },
    { "url": "https://cdn.example.com./v.mp4", "reject": null },
    { "url": "https://xn--bcher-kva.example/v.mp4", "reject": null },
    { "url": "https://cdn.example.com/%E2%9C%93.mp4", "reject": null },
    { "url": "https://cdn.example.com/a@b.mp4", "reject": null },
    { "url": "https://cdn.example.com/path?redirect=http://127.0.0.1/", "reject": null },

    { "url": "", "reject": "MALFORMED" },
    { "url": "cdn.example.com/v.mp4", "reject": "MALFORMED" },
    { "url": "https:cdn.example.com/v.mp4", "reject": "MALFORMED" },
    { "url": "ftp://cdn.example.com/v.mp4", "reject": "SCHEME" },
    { "url": "javascript:alert(1)", "reject": "SCHEME" },
    { "url": "file:///etc/passwd", "reject": "SCHEME" },
    { "url": "data:video/mp4;base64,AAAA", "reject": "SCHEME" },
    { "url": "content://media/external/video/1", "reject": "SCHEME" },
    { "url": "intent://scan/", "reject": "SCHEME" },
    { "url": "https:///v.mp4", "reject": "NO_HOST" },
    { "url": "https://:443/v.mp4", "reject": "NO_HOST" },
    { "url": "https://user:pass@cdn.example.com/v.mp4", "reject": "USERINFO" },
    { "url": "https://user@cdn.example.com/v.mp4", "reject": "USERINFO" },
    { "url": "https://cdn.example.com@evil.example/v.mp4", "reject": "USERINFO" },
    { "url": "https://cdn.example.com/v.mp4#t=10", "reject": "FRAGMENT" },
    { "url": "https://cdn.example.com/a b.mp4", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "https://cdn.example.com/v.mp4\n", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "\thttps://cdn.example.com/v.mp4", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "https://cdn.example.com/\u0000.mp4", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "https://cdn.example.com/\u0085.mp4", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "https://cdn.example.com\\@evil.example/v.mp4", "reject": "FORBIDDEN_CHARACTER" },
    { "url": "https://cdn.example.com:99999/v.mp4", "reject": "MALFORMED" },
    { "url": "https://cdn.example.com:8a/v.mp4", "reject": "MALFORMED" },
    { "url": "https://bücher.example/v.mp4", "reject": "MALFORMED" },
    { "url": "https://cdn..example.com/v.mp4", "reject": "MALFORMED" },

    { "url": "https://localhost/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://LOCALHOST./v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://app.localhost/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://printer.local/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://metadata.google.internal/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://router.lan/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://nas.home.arpa/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "https://intranet/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://127.0.0.1/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://127.1.2.3:8080/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://10.0.0.5/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://172.16.0.1/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://172.31.255.255/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://192.168.1.1/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://169.254.169.254/latest/meta-data", "reject": "LOCAL_HOST" },
    { "url": "http://100.64.0.1/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://100.127.255.255/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://0.0.0.0/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://224.0.0.1/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://255.255.255.255/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://2130706433/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://0x7f.0.0.1/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://127.1/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://010.0.0.1/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://1.2.3.4.5/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://256.1.1.1/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://cdn.0x10/v.mp4", "reject": "NON_CANONICAL_IP" },
    { "url": "http://[::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[::]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[::ffff:127.0.0.1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[::ffff:7f00:1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[::ffff:c0a8:101]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[fe80::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[FE80::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[fec0::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[fd12:3456::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[ff02::1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[64:ff9b::a00:1]/v.mp4", "reject": "LOCAL_HOST" },
    { "url": "http://[fe80::1%25eth0]/v.mp4", "reject": "MALFORMED" },
    { "url": "http://[::1/v.mp4", "reject": "MALFORMED" },
    { "url": "http://[::1]x/v.mp4", "reject": "MALFORMED" },
    { "url": "http://[1:2:3:4:5:6:7:8:9]/v.mp4", "reject": "MALFORMED" },
    { "url": "http://[1::2::3]/v.mp4", "reject": "MALFORMED" },
    { "url": "http://[12345::1]/v.mp4", "reject": "MALFORMED" }
  ]
}
```

- [ ] **Step 2: Write the failing Kotlin test**

Create `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRulesTest.kt`:

```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The verdicts in fixtures/rules/media-urls.json; ritme-web's media-url-rules.test.ts checks the same file. */
class MediaUrlRulesTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/media-urls.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVerdict() {
        assertTrue("expected the fixture cases", cases.size >= 80)
        cases.forEach { case ->
            val url = case.getValue("url").jsonPrimitive.content
            val expected = case.getValue("reject").jsonPrimitive.contentOrNull
            assertEquals(url, expected, MediaUrlRules.check(url)?.name)
        }
    }

    @Test
    fun theLengthIsCappedAt2048() {
        val base = "https://cdn.example.com/"
        assertNull(MediaUrlRules.check(base + "a".repeat(MediaUrlRules.MAX_URL_LENGTH - base.length)))
        assertEquals(
            MediaUrlRules.Rejection.TOO_LONG,
            MediaUrlRules.check(base + "a".repeat(MediaUrlRules.MAX_URL_LENGTH - base.length + 1)),
        )
    }

    @Test
    fun isAcceptableIsCheckWithoutTheReason() {
        assertTrue(MediaUrlRules.isAcceptableMediaUrl("https://cdn.example.com/v.mp4"))
        assertFalse(MediaUrlRules.isAcceptableMediaUrl("http://10.0.0.1/v.mp4"))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server :ritme-protocol:test --tests 'com.example.musicfy.ritme.protocol.MediaUrlRulesTest'
```
Expected: FAIL, `Unresolved reference 'MediaUrlRules'`.

- [ ] **Step 4: Implement `MediaUrlRules`**

Create `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRules.kt`:

```kotlin
package com.example.musicfy.ritme.protocol

/**
 * Shape rules for the `url` of a [MediaKind.URL] entry (spec 3.2), shared by the server
 * (RoomLogic) and every client, which apply them before sending so one bad entry never fails a
 * whole Add. Pure string checks: nothing is resolved or fetched (guarding against DNS rebinding is
 * the playing device's job). The goal: a member cannot make other members' devices reach their
 * router or LAN, and credentials never get broadcast to a room.
 *
 * Cases: fixtures/rules/media-urls.json, also read by ritme-web, whose
 * src/net/media-url-rules.ts is this file's TypeScript twin. Change both together.
 */
object MediaUrlRules {
    const val MAX_URL_LENGTH = 2_048

    enum class Rejection {
        TOO_LONG,

        /** Whitespace, a C0/C1 control character or a backslash anywhere. */
        FORBIDDEN_CHARACTER,

        /** A `#fragment`: clients strip it before sending. */
        FRAGMENT,
        MALFORMED,

        /** Anything but http/https. */
        SCHEME,

        /** `user:pass@`: credentials would be broadcast to the room. */
        USERINFO,
        NO_HOST,

        /** A numeric host that is not a dotted quad (`2130706433`, `0x7f.0.0.1`, `127.1`, `010.0.0.1`): browsers read these as IPv4. */
        NON_CANONICAL_IP,

        /** Loopback, private, link-local, CGNAT, unspecified, multicast or reserved addresses, and local names. */
        LOCAL_HOST,
    }

    fun isAcceptableMediaUrl(url: String): Boolean = check(url) == null

    /** Why [url] is refused, or null when it is acceptable. */
    fun check(url: String): Rejection? {
        if (url.length > MAX_URL_LENGTH) return Rejection.TOO_LONG
        if (url.any { it <= ' ' || it in '\u007F'..'\u009F' || it == '\\' }) return Rejection.FORBIDDEN_CHARACTER
        if ('#' in url) return Rejection.FRAGMENT
        val scheme = SCHEME.find(url)?.groupValues?.get(1) ?: return Rejection.MALFORMED
        val lower = scheme.lowercase()
        if (lower != "http" && lower != "https") return Rejection.SCHEME
        val rest = url.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return Rejection.MALFORMED
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' }
        if ('@' in authority) return Rejection.USERINFO
        val host = hostOf(authority) ?: return Rejection.MALFORMED
        if (host.isEmpty()) return Rejection.NO_HOST
        return hostRejection(host)
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val PORT = Regex(":([0-9]{1,5})?")
    private val HOST_NAME = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*\\.?$")
    private val NUMERIC_LABEL = Regex("^(0x[0-9a-f]*|[0-9]+)$")
    private val IPV4_PART = Regex("^(0|[1-9][0-9]{0,2})$")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")

    /** The host part of [authority] (an IPv6 literal keeps its brackets), lowercased; null when malformed. */
    private fun hostOf(authority: String): String? {
        val hostEnd = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            close + 1
        } else {
            authority.lastIndexOf(':').takeIf { it >= 0 } ?: authority.length
        }
        if (hostEnd < authority.length) {
            val port = PORT.matchEntire(authority.substring(hostEnd)) ?: return null
            val digits = port.groupValues[1]
            if (digits.isNotEmpty() && digits.toInt() > 65_535) return null
        }
        return authority.substring(0, hostEnd).lowercase()
    }

    private fun hostRejection(host: String): Rejection? {
        if (host.startsWith("[")) {
            val bytes = parseIpv6(host.substring(1, host.length - 1)) ?: return Rejection.MALFORMED
            return if (isLocalIpv6(bytes)) Rejection.LOCAL_HOST else null
        }
        if (!HOST_NAME.matches(host)) return Rejection.MALFORMED
        val name = host.removeSuffix(".")
        if (NUMERIC_LABEL.matches(name.substringAfterLast('.'))) {
            val v4 = parseIpv4(name) ?: return Rejection.NON_CANONICAL_IP
            return if (isLocalIpv4(v4)) Rejection.LOCAL_HOST else null
        }
        // Single-label names resolve through the LAN's search domain (router, nas, printer).
        if ('.' !in name || name == "home.arpa" || LOCAL_SUFFIXES.any { name.endsWith(it) }) return Rejection.LOCAL_HOST
        return null
    }

    /** Four decimal parts 0-255 without leading zeros, or null. */
    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { !IPV4_PART.matches(it) }) return null
        val bytes = IntArray(4) { parts[it].toInt() }
        return bytes.takeIf { b -> b.all { it <= 255 } }
    }

    /** 0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12, 192.168/16, and 224/4 + 240/4 (multicast, reserved, broadcast). */
    private fun isLocalIpv4(a: IntArray): Boolean =
        a[0] == 0 || a[0] == 10 || a[0] == 127 || a[0] >= 224 ||
            (a[0] == 100 && a[1] in 64..127) || (a[0] == 169 && a[1] == 254) ||
            (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168)

    /** 16 bytes, or null. Accepts `::` and a dotted IPv4 tail; no zone ids. */
    private fun parseIpv6(text: String): IntArray? {
        if (text.isEmpty() || text.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.') }) return null
        val halves = text.split("::")
        if (halves.size > 2 || (halves.size == 2 && '.' in halves[0])) return null
        val head = ipv6Words(halves[0]) ?: return null
        val tail = if (halves.size == 2) ipv6Words(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        val fits = if (halves.size == 2) missing >= 1 else missing == 0
        if (!fits) return null
        val words = head + List(missing) { 0 } + tail
        return IntArray(16) { i -> if (i % 2 == 0) words[i / 2] shr 8 else words[i / 2] and 0xFF }
    }

    private fun ipv6Words(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val pieces = part.split(':')
        val words = mutableListOf<Int>()
        for ((i, piece) in pieces.withIndex()) {
            if (i == pieces.lastIndex && '.' in piece) {
                val v4 = parseIpv4(piece) ?: return null
                words += (v4[0] shl 8) or v4[1]
                words += (v4[2] shl 8) or v4[3]
            } else {
                if (piece.isEmpty() || piece.length > 4 || '.' in piece) return null
                words += piece.toInt(16)
            }
        }
        return words
    }

    private fun isLocalIpv6(b: IntArray): Boolean {
        val embeddedV4 = b.copyOfRange(12, 16)
        return when {
            // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
            (0 until 12).all { b[it] == 0 } -> true
            // IPv4-mapped ::ffff:a.b.c.d
            (0 until 10).all { b[it] == 0 } && b[10] == 0xFF && b[11] == 0xFF -> isLocalIpv4(embeddedV4)
            // NAT64 64:ff9b::/96
            b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && (4 until 12).all { b[it] == 0 } -> isLocalIpv4(embeddedV4)
            // fc00::/7 unique local
            (b[0] and 0xFE) == 0xFC -> true
            // fe80::/10 link-local and fec0::/10 site-local
            b[0] == 0xFE && b[1] >= 0x80 -> true
            // ff00::/8 multicast
            b[0] == 0xFF -> true
            else -> false
        }
    }
}
```

- [ ] **Step 5: Run the protocol test**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server :ritme-protocol:test --tests 'com.example.musicfy.ritme.protocol.MediaUrlRulesTest'
```
Expected: PASS (3 tests). If a fixture case fails, the assertion message is the URL: fix the code, not the fixture, unless the fixture contradicts the Global Constraints.

- [ ] **Step 6: Write the failing server tests**

Append to `RoomLogicTest` (inside the class):

```kotlin
    @Test
    fun urlEntriesFollowTheSharedMediaUrlRules() {
        fun add(u: String) = apply(playingA, HOST, Command.Add(listOf(NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, u))))
        listOf(
            "https://user:pass@cdn.example/v.mp4",
            "http://192.168.1.1/v.mp4",
            "http://127.0.0.1:8080/v.mp4",
            "https://localhost/v.mp4",
            "http://[::1]/v.mp4",
            "http://2130706433/v.mp4",
            "https://cdn.example/v.mp4#t=5",
            "https://cdn.example/a b.mp4",
            "https://router/v.mp4",
        ).forEach { assertEquals(it, ErrorCode.INVALID, rejected(add(it))) }
        val good = "https://cdn.example.com:8443/live/index.m3u8?token=abc"
        assertEquals(good, ok(add(good)).queue.last().url)
    }

    @Test
    fun urlEntryThumbnailsFollowTheSameHostRules() {
        val url = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, "https://cdn.example/v.mp4")
        listOf("https://192.168.1.1/poster.jpg", "https://localhost/poster.jpg", "https://user@cdn.example/poster.jpg").forEach {
            assertEquals(it, ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url.copy(thumbnailUrl = it))))))
        }
    }
```

- [ ] **Step 7: Run them to verify they fail**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test --tests 'com.example.musicfy.ritme.server.RoomLogicTest'
```
Expected: FAIL in both new tests (`expected Rejected but was Ok`).

- [ ] **Step 8: Use the rules in `RoomLogic`**

Add `import com.example.musicfy.ritme.protocol.MediaUrlRules`. Replace the KDoc + body of `NewEntry.isValid()`'s `MediaKind.URL` branch:

```kotlin
            MediaKind.URL -> videoId.isEmpty() && url?.let { u ->
                u.length <= Limits.MAX_URL_LENGTH && (u.startsWith("https://") || u.startsWith("http://"))
            } == true
```

with

```kotlin
            MediaKind.URL -> videoId.isEmpty() && url?.let(MediaUrlRules::isAcceptableMediaUrl) == true
```

and in its KDoc replace `URL entries only an http(s) url` with `URL entries only a url that passes MediaUrlRules`. Replace `isValidThumbnail` with

```kotlin
    private fun isValidThumbnail(thumbnailUrl: String?, youtube: Boolean): Boolean {
        if (thumbnailUrl == null) return true
        if (thumbnailUrl.length > Limits.MAX_THUMBNAIL_URL_LENGTH || !thumbnailUrl.startsWith("https://")) return false
        // Other members' devices load it directly: the same host rules as a URL entry's url.
        if (!youtube) return MediaUrlRules.isAcceptableMediaUrl(thumbnailUrl)
        val host = runCatching { URI(thumbnailUrl).host }.getOrNull()?.lowercase() ?: return false
        return host == "i.ytimg.com" || YOUTUBE_THUMBNAIL_HOST_SUFFIXES.any { host.endsWith(it) }
    }
```

(`Limits.MAX_URL_LENGTH` stays; it equals `MediaUrlRules.MAX_URL_LENGTH`.)

- [ ] **Step 9: Run all server and protocol tests**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
```
Expected: `BUILD SUCCESSFUL`, 0 failures (the existing URL tests use `cdn.example`/`example.com` hosts, which stay valid).

- [ ] **Step 10: Document it**

In `ritme-server/README.md` "Protocol notes", replace the `Entries:` bullet with:

```markdown
- Entries: YouTube kinds carry a `videoId` matching `[A-Za-z0-9_-]{1,64}` and no `url`; `URL`
  entries carry a `url` that passes `MediaUrlRules` (`modules/ritme-protocol`) and `videoId = ""`:
  http(s), at most 2048 chars, no whitespace, control characters, backslash or `#fragment`, no
  `user:pass@`, and no local host (`localhost`, `*.localhost`, `*.local`, `*.internal`, `*.lan`,
  `*.home.arpa`, single-label names, loopback/private/link-local/CGNAT/unspecified/multicast/reserved
  IPv4 and IPv6 literals, or numeric hosts that are not a dotted quad). The server never resolves or
  fetches the URL. `thumbnailUrl` is null or https (<= 512 chars), for YouTube kinds only from
  `*.ytimg.com`, `*.googleusercontent.com` or `*.ggpht.com`, for `URL` entries under the same host
  rules as the url. `durationMs` is 0 (live) to 24 h.
```

In "Privacy", at the end of the "Exposure note" paragraph add: `Local and private hosts are refused for URL entries and their thumbnails, so a member cannot point other members' devices at their own router or LAN.`

- [ ] **Step 11: Commit**

```bash
git add modules/ritme-protocol ritme-server
git commit -m "feat(ritme): MediaUrlRules shared URL rules; server refuses userinfo, fragments, control chars and local hosts in URL entries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `SingleFlightCache` + shared `UpstreamBudget` (search refactor, no behaviour change)

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/SingleFlightCache.kt`
- Create: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SingleFlightCacheTest.kt`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Search.kt:23-24,135-189`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt:33-41,127`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Limits.kt:133-134`
- Test (unchanged, must stay green): `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SearchTest.kt`

**Interfaces:**
- Produces: `class UpstreamOverloadedException : RuntimeException`; `typealias SearchOverloadedException = UpstreamOverloadedException`; `class UpstreamBudget(perMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE, clock: Clock) { fun tryAcquire(): Boolean }`; `class SingleFlightCache<K : Any, V>(clock: Clock, budget: UpstreamBudget, maxEntries: Int, ttlMs: (V) -> Long) { val size: Int; suspend fun get(key: K, load: suspend () -> V): V }`.
- Produces: `SearchService(backend, clock, ttlMs = 600_000, maxEntries = 1_000, globalPerMinute = 300, budget: UpstreamBudget = UpstreamBudget(globalPerMinute, clock))`; `ServerDeps.upstreamBudget: UpstreamBudget` (Task 4 passes it to the video service).

- [ ] **Step 1: Write the failing test**

Create `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SingleFlightCacheTest.kt`:

```kotlin
package com.example.musicfy.ritme.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SingleFlightCacheTest {
    @Test
    fun eachValueKeepsItsOwnTtl() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 10) {
            if (it == "short") 1_000 else 10_000
        }
        var loads = 0
        suspend fun get(key: String) = cache.get(key) { loads++; key }
        get("short")
        get("long")
        clock.now += 999
        get("short")
        get("long")
        assertEquals(2, loads)
        clock.now += 1
        get("short")
        get("long")
        assertEquals(3, loads)
        clock.now += 9_000
        get("long")
        assertEquals(4, loads)
    }

    @Test
    fun nullIsCachedLikeAnyValue() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String?>(clock, UpstreamBudget(clock = clock), maxEntries = 10) { 1_000 }
        var loads = 0
        assertNull(cache.get("x") { loads++; null })
        assertNull(cache.get("x") { loads++; null })
        assertEquals(1, loads)
    }

    @Test
    fun theLeastRecentlyUsedEntryGoesFirst() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 2) { 60_000 }
        var loads = 0
        suspend fun get(key: String) = cache.get(key) { loads++; key }
        get("a")
        get("b")
        get("a")
        get("c") // over capacity: evicts b, the least recently used
        assertEquals(2, cache.size)
        get("a")
        assertEquals(3, loads)
        get("b")
        assertEquals(4, loads)
    }

    @Test
    fun cachesSharingABudgetRunOutTogether() = runBlocking {
        val clock = FakeClock()
        val budget = UpstreamBudget(perMinute = 2, clock = clock)
        val first = SingleFlightCache<String, String>(clock, budget, maxEntries = 10) { 60_000 }
        val second = SingleFlightCache<String, String>(clock, budget, maxEntries = 10) { 60_000 }
        first.get("a") { "a" }
        second.get("b") { "b" }
        assertThrows(UpstreamOverloadedException::class.java) { runBlocking { first.get("c") { "c" } } }
        assertThrows(UpstreamOverloadedException::class.java) { runBlocking { second.get("d") { "d" } } }
        assertEquals("a", first.get("a") { error("must be cached") })
        clock.now += 30_000 // 2 per minute: one token back
        assertEquals("d", second.get("d") { "d" })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test --tests 'com.example.musicfy.ritme.server.SingleFlightCacheTest'
```
Expected: FAIL, `Unresolved reference 'SingleFlightCache'`.

- [ ] **Step 3: Implement**

Create `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/SingleFlightCache.kt`:

```kotlin
package com.example.musicfy.ritme.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** The global upstream budget is spent: the endpoint answers 503. */
class UpstreamOverloadedException : RuntimeException("upstream budget exhausted")

/** The name search used before the budget was shared; kept so its callers and tests read the same. */
typealias SearchOverloadedException = UpstreamOverloadedException

/**
 * Uncached calls to YouTube's InnerTube from all endpoints together (search and video info):
 * at most [perMinute], refilled evenly. Thread-safe.
 */
class UpstreamBudget(perMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE, clock: Clock) {
    private val limiter = RateLimiter(perMinute, perMinute / 60.0, clock)

    fun tryAcquire(): Boolean = synchronized(this) { limiter.tryAcquire() }
}

/**
 * LRU cache with single flight: concurrent misses for one key share one load, and every load
 * first spends a token of [budget] (none left: [UpstreamOverloadedException]); hits cost nothing.
 * A value lives [ttlMs] of itself, so callers can keep some answers shorter. A failed load is not
 * cached: its waiters fail too (never with the loader's own cancellation) and the next call tries
 * again. Null is a value like any other.
 */
class SingleFlightCache<K : Any, V>(
    private val clock: Clock,
    private val budget: UpstreamBudget,
    private val maxEntries: Int,
    private val ttlMs: (V) -> Long,
) {
    private class Cached<V>(val expiresAt: Long, val value: V)

    private val lock = Any()
    private val cache = object : LinkedHashMap<K, Cached<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Cached<V>>): Boolean = size > maxEntries
    }
    private val inFlight = HashMap<K, CompletableDeferred<V>>()

    val size: Int get() = synchronized(lock) { cache.size }

    suspend fun get(key: K, load: suspend () -> V): V {
        val call = CompletableDeferred<V>()
        val shared = synchronized(lock) {
            cache[key]?.takeIf { clock.now() < it.expiresAt }?.let { return it.value }
            inFlight[key] ?: run {
                if (!budget.tryAcquire()) throw UpstreamOverloadedException()
                inFlight[key] = call
                null
            }
        }
        if (shared != null) return shared.await()
        try {
            val value = load()
            synchronized(lock) {
                cache[key] = Cached(clock.now() + ttlMs(value), value)
                inFlight.remove(key)
            }
            call.complete(value)
            return value
        } catch (e: Throwable) {
            synchronized(lock) { inFlight.remove(key) }
            // Waiters get a plain failure (502), never this caller's cancellation.
            call.completeExceptionally(if (e is CancellationException) IllegalStateException("upstream call cancelled") else e)
            throw e
        }
    }
}
```

In `Search.kt` delete the imports `import kotlinx.coroutines.CancellationException` and `import kotlinx.coroutines.CompletableDeferred`, and replace everything from `/** The global upstream budget is spent: the endpoint answers 503. */` down to the closing `}` of `class SearchService` (the line before `/**\n * Token bucket per client`) with:

```kotlin
/**
 * Cached, single-flight search: identical uncached queries running at the same time share one
 * upstream call, and every uncached query spends a token of the [budget] that video info shares.
 */
class SearchService(
    private val backend: SearchBackend,
    clock: Clock,
    ttlMs: Long = 10 * 60_000L,
    maxEntries: Int = 1_000,
    globalPerMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE,
    budget: UpstreamBudget = UpstreamBudget(globalPerMinute, clock),
) {
    private val cache = SingleFlightCache<String, List<SearchResult>>(clock, budget, maxEntries) { ttlMs }

    suspend fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        return cache.get(trimmed.lowercase()) { InnerTubeSearchParser.parse(backend.fetch(trimmed)).take(MAX_RESULTS) }
    }

    companion object {
        const val MAX_RESULTS = 20
    }
}
```

`Application.kt`: in `ServerDeps` replace

```kotlin
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock),
```

with

```kotlin
    /** Uncached InnerTube calls of every endpoint together (300/min). */
    val upstreamBudget: UpstreamBudget = UpstreamBudget(clock = clock),
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock, budget = upstreamBudget),
```

and in the `/api/search` route replace `} catch (_: SearchOverloadedException) {` with `} catch (_: UpstreamOverloadedException) {`.

`Limits.kt`: replace the KDoc `/** All upstream (uncached) searches together; beyond this /api/search answers 503. */` with `/** All uncached InnerTube calls together, search and video info (UpstreamBudget); beyond this both endpoints answer 503. */`.

- [ ] **Step 4: Run the new test and the untouched search tests**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test --tests 'com.example.musicfy.ritme.server.SingleFlightCacheTest' --tests 'com.example.musicfy.ritme.server.SearchTest'
git diff --quiet -- ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SearchTest.kt && echo "SearchTest unchanged"
```
Expected: PASS; prints `SearchTest unchanged`.

- [ ] **Step 5: Run everything**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add ritme-server
git commit -m "refactor(ritme-server): SingleFlightCache and a shared UpstreamBudget under search (no behaviour change)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `GET /api/video?id=` (InnerTube `music/get_queue`, metadata only)

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/InnerTubeJson.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Video.kt`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Search.kt` (top part: imports, backend, parser)
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt` (`isValidThumbnail` YouTube branch)
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt` (`ServerDeps`, route)
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Limits.kt`
- Create: `ritme-server/src/test/resources/innertube-get-queue-video.json`, `innertube-get-queue-live.json`, `innertube-get-queue-wrapped.json`, `innertube-get-queue-empty.json`, `innertube-get-queue-recorded.json` (recorded)
- Create: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/VideoTest.kt`
- Modify: `ritme-server/README.md` (Endpoints, Privacy, intro)

**Interfaces:**
- Consumes: `SingleFlightCache`, `UpstreamBudget`, `UpstreamOverloadedException`, `ServerDeps.upstreamBudget` (Task 3).
- Produces (HTTP, used by Task 5): `GET /api/video?id=<11 chars>` → 200 `{"videoId":String,"title":String,"channel":String,"durationMs":Long,"thumbnailUrl":String|null,"live":Boolean}`; 400 / 404 / 429 / 502 / 503 with empty body.
- Produces (Kotlin): `@Serializable data class VideoInfo(videoId, title, channel, durationMs, thumbnailUrl: String? = null, live)`; `fun interface VideoBackend { suspend fun fetch(videoId: String): String }`; `class InnerTubeVideoBackend(http: HttpClient)`; `object InnerTubeVideoParser { fun parse(json: String, videoId: String): VideoInfo? }`; `class VideoInfoService(backend, clock, budget = UpstreamBudget(clock = clock), maxEntries = 1_000) { suspend fun lookup(videoId: String): VideoInfo?; companion { TTL_MS = 21_600_000; SHORT_TTL_MS = 300_000; fun isVideoId(id: String): Boolean } }`; `internal fun isYouTubeImageUrl(url: String): Boolean`; `internal fun HttpRequestBuilder.innerTubeHeaders()`; `internal fun JsonObjectBuilder.putWebRemixContext()`; `Limits.VIDEO_PER_MINUTE = 30`; `ServerDeps.video: VideoInfoService`.

- [ ] **Step 1: Add the hand-written fixtures**

`ritme-server/src/test/resources/innertube-get-queue-video.json`:

```json
{
  "responseContext": { "visitorData": "x" },
  "queueDatas": [
    {
      "content": {
        "playlistPanelVideoRenderer": {
          "title": { "runs": [{ "text": "Never Gonna Give You Up" }] },
          "longBylineText": {
            "runs": [
              { "text": "Rick Astley", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCuAXFkgsw1L7xaCfnd5JJOw" } } },
              { "text": " • " },
              { "text": "Whenever You Need Somebody" },
              { "text": " • " },
              { "text": "1987" }
            ]
          },
          "thumbnail": {
            "thumbnails": [
              { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg", "width": 120, "height": 90 },
              { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", "width": 480, "height": 360 }
            ]
          },
          "lengthText": { "runs": [{ "text": "3:34" }] },
          "videoId": "dQw4w9WgXcQ",
          "selected": true,
          "navigationEndpoint": { "watchEndpoint": { "videoId": "dQw4w9WgXcQ" } }
        }
      }
    }
  ]
}
```

`ritme-server/src/test/resources/innertube-get-queue-live.json`:

```json
{
  "queueDatas": [
    {
      "content": {
        "playlistPanelVideoRenderer": {
          "title": { "runs": [{ "text": "lofi hip hop radio" }] },
          "longBylineText": { "runs": [{ "text": "Lofi Girl" }] },
          "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault_live.jpg", "width": 480, "height": 360 }] },
          "videoId": "jfKfPfyJRdk",
          "selected": true,
          "navigationEndpoint": { "watchEndpoint": { "videoId": "jfKfPfyJRdk" } }
        }
      }
    }
  ]
}
```

`ritme-server/src/test/resources/innertube-get-queue-wrapped.json`:

```json
{
  "queueDatas": [
    {
      "content": {
        "playlistPanelVideoWrapperRenderer": {
          "primaryRenderer": {
            "playlistPanelVideoRenderer": {
              "title": { "runs": [{ "text": "Never Gonna Give You Up" }] },
              "longBylineText": { "runs": [{ "text": "Rick Astley" }] },
              "thumbnail": { "thumbnails": [{ "url": "https://evil.example/t.jpg", "width": 480, "height": 360 }] },
              "lengthText": { "runs": [{ "text": "3:33" }] },
              "videoId": "dQw4w9WgXcQ",
              "selected": true,
              "navigationEndpoint": { "watchEndpoint": { "videoId": "dQw4w9WgXcQ" } }
            }
          }
        }
      }
    }
  ]
}
```

`ritme-server/src/test/resources/innertube-get-queue-empty.json`:

```json
{ "responseContext": {}, "queueDatas": [] }
```

- [ ] **Step 2: Record one real response**

```bash
curl -sS 'https://music.youtube.com/youtubei/v1/music/get_queue?prettyPrint=false' \
  -H 'Content-Type: application/json' \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0' \
  -H 'X-Goog-Api-Format-Version: 1' -H 'X-YouTube-Client-Name: 67' -H 'X-YouTube-Client-Version: 1.20260707.12.00' \
  -H 'X-Origin: https://music.youtube.com' -H 'Referer: https://music.youtube.com/' \
  --data '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260707.12.00","hl":"en","gl":"US"}},"videoIds":["dQw4w9WgXcQ"]}' \
  -o ritme-server/src/test/resources/innertube-get-queue-recorded.json
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); c=d["queueDatas"][0]["content"]; r=c.get("playlistPanelVideoRenderer") or c["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"]; print(r["videoId"], r["title"]["runs"][0]["text"], r["lengthText"]["runs"][0]["text"])' ritme-server/src/test/resources/innertube-get-queue-recorded.json
```
Expected: `dQw4w9WgXcQ Never Gonna Give You Up (…) 3:3x` (title may carry a suffix). If the response is not JSON or has no `queueDatas`, stop and report BLOCKED with the first 300 bytes of the file: the parser cannot be validated against reality.

- [ ] **Step 3: Write the failing tests**

Create `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/VideoTest.kt`:

```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()
    private val video = resource("innertube-get-queue-video.json")
    private val live = resource("innertube-get-queue-live.json")
    private val wrapped = resource("innertube-get-queue-wrapped.json")
    private val empty = resource("innertube-get-queue-empty.json")
    private val searchJson = resource("innertube-search-minimal.json")
    private val id = "dQw4w9WgXcQ"
    private val liveId = "jfKfPfyJRdk"

    @Test
    fun parsesTitleChannelDurationAndTheLargestYoutubeThumbnail() {
        assertEquals(
            VideoInfo(id, "Never Gonna Give You Up", "Rick Astley", 214_000, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false),
            InnerTubeVideoParser.parse(video, id),
        )
    }

    @Test
    fun noLengthTextMeansLive() {
        assertEquals(
            VideoInfo(liveId, "lofi hip hop radio", "Lofi Girl", 0, "https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault_live.jpg", live = true),
            InnerTubeVideoParser.parse(live, liveId),
        )
    }

    @Test
    fun readsTheWrappedRendererAndDropsThumbnailsFromOtherHosts() {
        val info = InnerTubeVideoParser.parse(wrapped, id)!!
        assertEquals("Never Gonna Give You Up", info.title)
        assertEquals(213_000, info.durationMs)
        assertNull(info.thumbnailUrl)
    }

    @Test
    fun noRendererOrAnotherVideoMeansNotFound() {
        assertNull(InnerTubeVideoParser.parse(empty, id))
        assertNull(InnerTubeVideoParser.parse("{}", id))
        assertNull(InnerTubeVideoParser.parse(video, "aaaaaaaaaaa"))
    }

    @Test
    fun parsesARecordedResponse() {
        val info = InnerTubeVideoParser.parse(resource("innertube-get-queue-recorded.json"), id)!!
        assertTrue(info.title, info.title.contains("Never Gonna Give You Up"))
        assertTrue(info.channel, info.channel.contains("Rick Astley"))
        assertTrue("${info.durationMs}", info.durationMs in 200_000..240_000)
        assertFalse(info.live)
        assertNotNull(info.thumbnailUrl)
    }

    @Test
    fun requestBodyAsksTheWebRemixClientForOneVideo() {
        val body = RitmeJson.parseToJsonElement(InnerTubeVideoBackend.requestBody(id)).jsonObject
        assertEquals(listOf(id), body.getValue("videoIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("WEB_REMIX", body.getValue("context").jsonObject.getValue("client").jsonObject.getValue("clientName").jsonPrimitive.content)
    }

    @Test
    fun videoIdsAreElevenUrlSafeCharacters() {
        assertTrue(VideoInfoService.isVideoId(id))
        assertTrue(VideoInfoService.isVideoId("aqz-KE-bpKQ"))
        listOf("", "abc", "dQw4w9WgXcQx", "dQw4w9WgXc!", "dQw4w9WgXc ").forEach { assertFalse(it, VideoInfoService.isVideoId(it)) }
    }

    @Test
    fun serviceKeepsVideosSixHoursAndLiveOrMissingOnesFiveMinutes() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = VideoInfoService({
            calls++
            when (it) {
                id -> video
                liveId -> live
                else -> empty
            }
        }, clock)
        service.lookup(id)
        service.lookup(liveId)
        assertNull(service.lookup("aaaaaaaaaaa"))
        assertEquals(3, calls)
        clock.now += VideoInfoService.SHORT_TTL_MS - 1
        listOf(id, liveId, "aaaaaaaaaaa").forEach { service.lookup(it) }
        assertEquals(3, calls)
        clock.now += 1
        listOf(id, liveId, "aaaaaaaaaaa").forEach { service.lookup(it) }
        assertEquals(5, calls)
        clock.now += VideoInfoService.TTL_MS - VideoInfoService.SHORT_TTL_MS
        service.lookup(id)
        assertEquals(6, calls)
    }

    @Test
    fun concurrentLookupsOfOneVideoShareOneUpstreamCall() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val service = VideoInfoService({ calls++; gate.await(); video }, FakeClock())
        val first = async { service.lookup(id) }
        val second = async { service.lookup(id) }
        yield()
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    private fun depsWith(budget: UpstreamBudget = UpstreamBudget(clock = SystemClock), backend: VideoBackend) = ServerDeps(
        config = ServerConfig(0, "https://ritme.test", File("build/no-web")),
        upstreamBudget = budget,
        search = SearchService({ searchJson }, SystemClock, budget = budget),
        video = VideoInfoService(backend, SystemClock, budget),
    )

    @Test
    fun endpointReturnsTheVideoAsJson() = testApplication {
        application { ritmeModule(depsWith { video }) }
        val response = client.get("/api/video?id=$id")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(InnerTubeVideoParser.parse(video, id), RitmeJson.decodeFromString(VideoInfo.serializer(), response.bodyAsText()))
    }

    @Test
    fun endpointValidatesTheId() = testApplication {
        application { ritmeModule(depsWith { video }) }
        listOf("/api/video", "/api/video?id=", "/api/video?id=abc", "/api/video?id=dQw4w9WgXcQx", "/api/video?id=dQw4w9WgXc%21")
            .forEach { assertEquals(it, HttpStatusCode.BadRequest, client.get(it).status) }
    }

    @Test
    fun endpointAnswers404WhenYoutubeReturnsNoVideo() = testApplication {
        application { ritmeModule(depsWith { empty }) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/video?id=$id").status)
    }

    @Test
    fun endpointRateLimitsPerIpSeparatelyFromSearch() = testApplication {
        application { ritmeModule(depsWith { video }) }
        repeat(Limits.VIDEO_PER_MINUTE) {
            assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id") { header("X-Forwarded-For", "9.9.9.9") }.status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/video?id=$id") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=rick") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id") { header("X-Forwarded-For", "8.8.8.8") }.status)
    }

    @Test
    fun endpointSharesTheUpstreamBudgetWithSearch() = testApplication {
        application { ritmeModule(depsWith(budget = UpstreamBudget(perMinute = 2, clock = SystemClock)) { video }) }
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=a").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/video?id=aaaaaaaaaaa").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/search?q=b").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id").status) // cached answers cost nothing
    }

    @Test
    fun endpointReportsBackendFailureAsBadGateway() = testApplication {
        application { ritmeModule(depsWith { error("down") }) }
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/video?id=$id").status)
    }
}
```

- [ ] **Step 4: Run them to verify they fail**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test --tests 'com.example.musicfy.ritme.server.VideoTest'
```
Expected: FAIL, `Unresolved reference 'VideoInfo'` (and the other new names).

- [ ] **Step 5: Share the InnerTube plumbing**

Create `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/InnerTubeJson.kt`:

```kotlin
package com.example.musicfy.ritme.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Walks InnerTube's nested JSON: String keys index objects, Int keys arrays; null when the path is missing. */
internal fun JsonElement?.at(vararg path: Any): JsonElement? = path.fold(this) { node, key ->
    when (key) {
        is String -> (node as? JsonObject)?.get(key)
        is Int -> (node as? JsonArray)?.getOrNull(key)
        else -> null
    }
}

internal fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
```

In `Search.kt` replace everything from the first line down to (not including) the KDoc `/**\n * Cached, single-flight search` with:

```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String? = null,
)

/** Returns the raw InnerTube search JSON for [query]. */
fun interface SearchBackend {
    suspend fun fetch(query: String): String
}

fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { requestTimeoutMillis = 10_000 }
}

/** The WEB_REMIX client's headers, as YouTube Music's web app sends them (search and video info). */
internal fun HttpRequestBuilder.innerTubeHeaders() {
    header(HttpHeaders.UserAgent, InnerTubeSearchBackend.USER_AGENT)
    header("X-Goog-Api-Format-Version", "1")
    header("X-YouTube-Client-Name", InnerTubeSearchBackend.CLIENT_ID)
    header("X-YouTube-Client-Version", InnerTubeSearchBackend.CLIENT_VERSION)
    header("X-Origin", InnerTubeSearchBackend.ORIGIN)
    header(HttpHeaders.Referrer, "${InnerTubeSearchBackend.ORIGIN}/")
}

/** `"context": {"client": {WEB_REMIX, hl en, gl US}}`, the first key of every request body. */
internal fun JsonObjectBuilder.putWebRemixContext() {
    putJsonObject("context") {
        putJsonObject("client") {
            put("clientName", InnerTubeSearchBackend.CLIENT_NAME)
            put("clientVersion", InnerTubeSearchBackend.CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        }
    }
}

/** One InnerTube `search` call, songs only. Metadata only: no stream URLs are ever requested. */
class InnerTubeSearchBackend(private val http: HttpClient) : SearchBackend {
    override suspend fun fetch(query: String): String {
        val response = http.post(URL) {
            innerTubeHeaders()
            setBody(TextContent(requestBody(query), ContentType.Application.Json))
        }
        check(response.status.isSuccess()) { "InnerTube search failed: ${response.status.value}" }
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val ORIGIN = "https://music.youtube.com"
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_ID = "67"

        // Keep in sync with providers/innertube YouTubeClient.WEB_REMIX.
        const val CLIENT_VERSION = "1.20260707.12.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        // providers/innertube YouTube.SearchFilter.FILTER_SONG
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        fun requestBody(query: String): String = buildJsonObject {
            putWebRemixContext()
            put("query", query)
            put("params", SONGS_FILTER)
        }.toString()
    }
}

object InnerTubeSearchParser {
    /** `m:ss` or `h:mm:ss`, as InnerTube writes lengths. */
    internal val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"

    fun parse(json: String): List<SearchResult> {
        val sections = RitmeJson.parseToJsonElement(json)
            .at("contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents")
            .asArray()
        return sections
            .flatMap { it.at("musicShelfRenderer", "contents").asArray() }
            .mapNotNull { item -> item.at("musicResponsiveListItemRenderer")?.let(::parseItem) }
    }

    fun parseDuration(text: String): Long = text.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1_000

    private fun parseItem(item: JsonElement): SearchResult? {
        val videoId = item.at("playlistItemData", "videoId").asString() ?: return null
        val title = column(item, 0).firstOrNull()?.at("text").asString() ?: return null
        val details = column(item, 1)
        val artists = details
            .filter {
                it.at("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
                    .asString() == ARTIST_PAGE
            }
            .mapNotNull { it.at("text").asString() }
        val artist = artists.ifEmpty { listOfNotNull(details.firstOrNull()?.at("text").asString()) }.joinToString(", ")
        val duration = details.mapNotNull { it.at("text").asString() }.lastOrNull { DURATION.matches(it) } ?: return null
        val thumbnail = item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
        return SearchResult(videoId, title, artist, parseDuration(duration), thumbnail)
    }

    private fun column(item: JsonElement, index: Int): List<JsonElement> =
        item.at("flexColumns", index, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").asArray()
}

```

(Request body key order stays `context`, `query`, `params`; `SearchTest.requestBodyAsksForSongsFromTheWebRemixClient` covers it.)

- [ ] **Step 6: Implement the video service**

Create `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Video.kt`:

```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/** GET /api/video's answer: what the web needs to preview and add a YOUTUBE_VIDEO entry. */
@Serializable
data class VideoInfo(
    val videoId: String,
    val title: String,
    /** First run of the byline: the channel (or artist). */
    val channel: String,
    /** 0 when YouTube gives no length, which means live. */
    val durationMs: Long,
    /** Only on a YouTube image host ([isYouTubeImageUrl]), else null. */
    val thumbnailUrl: String? = null,
    val live: Boolean,
)

/** Returns the raw InnerTube `music/get_queue` JSON for one video. */
fun interface VideoBackend {
    suspend fun fetch(videoId: String): String
}

/** One InnerTube `music/get_queue` call for one video (spec D5). Metadata only: never a stream URL. */
class InnerTubeVideoBackend(private val http: HttpClient) : VideoBackend {
    override suspend fun fetch(videoId: String): String {
        val response = http.post(URL) {
            innerTubeHeaders()
            setBody(TextContent(requestBody(videoId), ContentType.Application.Json))
        }
        check(response.status.isSuccess()) { "InnerTube get_queue failed: ${response.status.value}" }
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://music.youtube.com/youtubei/v1/music/get_queue?prettyPrint=false"

        fun requestBody(videoId: String): String = buildJsonObject {
            putWebRemixContext()
            putJsonArray("videoIds") { add(videoId) }
        }.toString()
    }
}

/** Reads `queueDatas[0].content` like the app's NextPage.fromPlaylistPanelVideoRenderer. */
object InnerTubeVideoParser {
    /** The video [videoId], or null when YouTube returned none (private, removed, unavailable) or another one. */
    fun parse(json: String, videoId: String): VideoInfo? {
        val content = RitmeJson.parseToJsonElement(json).at("queueDatas", 0, "content")
        val renderer = content.at("playlistPanelVideoRenderer")
            ?: content.at("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer")
            ?: return null
        if (renderer.at("videoId").asString() != videoId) return null
        val title = renderer.at("title", "runs", 0, "text").asString()?.takeIf { it.isNotBlank() } ?: return null
        val channel = renderer.at("longBylineText", "runs", 0, "text").asString().orEmpty()
        val length = renderer.at("lengthText", "runs", 0, "text").asString()
        val durationMs = length?.takeIf { InnerTubeSearchParser.DURATION.matches(it) }?.let(InnerTubeSearchParser::parseDuration) ?: 0L
        val thumbnail = renderer.at("thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
            ?.takeIf(::isYouTubeImageUrl)
        return VideoInfo(videoId, title, channel, durationMs, thumbnail, live = durationMs == 0L)
    }
}

/**
 * Cached, single-flight video info on the [budget] search uses too. Videos stay 6 h; live ones
 * (their status can change) and missing ones (so a bogus id cannot drain the budget) 5 min.
 */
class VideoInfoService(
    private val backend: VideoBackend,
    clock: Clock,
    budget: UpstreamBudget = UpstreamBudget(clock = clock),
    maxEntries: Int = 1_000,
) {
    private val cache = SingleFlightCache<String, VideoInfo?>(clock, budget, maxEntries) { info ->
        if (info == null || info.live) SHORT_TTL_MS else TTL_MS
    }

    suspend fun lookup(videoId: String): VideoInfo? =
        cache.get(videoId) { InnerTubeVideoParser.parse(backend.fetch(videoId), videoId) }

    companion object {
        const val TTL_MS = 6 * 60 * 60_000L
        const val SHORT_TTL_MS = 5 * 60_000L
        private val ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun isVideoId(id: String): Boolean = ID.matches(id)
    }
}

private val YOUTUBE_IMAGE_HOST_SUFFIXES = listOf(".ytimg.com", ".googleusercontent.com", ".ggpht.com")

/** https, at most 512 chars, on a YouTube image host: what RoomLogic accepts as a YouTube entry's thumbnail. */
internal fun isYouTubeImageUrl(url: String): Boolean {
    if (url.length > Limits.MAX_THUMBNAIL_URL_LENGTH || !url.startsWith("https://")) return false
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "i.ytimg.com" || YOUTUBE_IMAGE_HOST_SUFFIXES.any { host.endsWith(it) }
}
```

`RoomLogic.kt`: delete `private val YOUTUBE_THUMBNAIL_HOST_SUFFIXES = ...` and, in `isValidThumbnail`, replace the last two lines

```kotlin
        val host = runCatching { URI(thumbnailUrl).host }.getOrNull()?.lowercase() ?: return false
        return host == "i.ytimg.com" || YOUTUBE_THUMBNAIL_HOST_SUFFIXES.any { host.endsWith(it) }
```

with

```kotlin
        return isYouTubeImageUrl(thumbnailUrl)
```

and remove `import java.net.URI` if nothing else in the file uses it.

`Limits.kt`: after `SEARCH_MAX_QUERY_LENGTH` add

```kotlin

    /** /api/video per client IP: a bucket of its own, so adding videos does not use up searches. */
    const val VIDEO_PER_MINUTE = 30
```

- [ ] **Step 7: Add the route**

`Application.kt`, `ServerDeps`: replace

```kotlin
    /** Uncached InnerTube calls of every endpoint together (300/min). */
    val upstreamBudget: UpstreamBudget = UpstreamBudget(clock = clock),
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock, budget = upstreamBudget),
```

with

```kotlin
    /** One client for every InnerTube call. */
    http: HttpClient = defaultHttpClient(),
    /** Uncached InnerTube calls of every endpoint together (300/min). */
    val upstreamBudget: UpstreamBudget = UpstreamBudget(clock = clock),
    val search: SearchService = SearchService(InnerTubeSearchBackend(http), clock, budget = upstreamBudget),
    val video: VideoInfoService = VideoInfoService(InnerTubeVideoBackend(http), clock, upstreamBudget),
```

and add `import io.ktor.client.HttpClient`. In `ritmeModule`, after `val searchLimiter = IpRateLimiter(deps.clock)` add

```kotlin
    val videoLimiter = IpRateLimiter(deps.clock, perMinute = Limits.VIDEO_PER_MINUTE)
```

and after the `/api/search` route add

```kotlin
        get("/api/video") {
            val id = call.request.queryParameters["id"].orEmpty()
            if (!VideoInfoService.isVideoId(id)) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (!videoLimiter.tryAcquire(call.request.origin.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            val info = try {
                deps.video.lookup(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: UpstreamOverloadedException) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            } catch (e: Exception) {
                // Never log the id: what people watch is user content.
                call.application.log.warn("video backend failed: ${e::class.simpleName}")
                call.respond(HttpStatusCode.BadGateway)
                return@get
            }
            if (info == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(RitmeJson.encodeToString(VideoInfo.serializer(), info), ContentType.Application.Json)
        }
```

- [ ] **Step 8: Run the video tests, then everything**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test --tests 'com.example.musicfy.ritme.server.VideoTest'
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
grep -n 'log\.' ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt
```
Expected: both runs `BUILD SUCCESSFUL`, 0 failures. The grep shows only the sweep/metrics lines, `search backend failed: ${e::class.simpleName}` and `video backend failed: ${e::class.simpleName}` (no `id`, no `query`).

- [ ] **Step 9: Document it**

`ritme-server/README.md`:
- Intro sentence: replace `and offers metadata-only song search for the web client.` with `and offers metadata-only song search and video info for the web client.`
- Endpoints table, after the `/api/search` row add:

```markdown
| GET | `/api/video?id=` | One YouTube video's metadata `{videoId,title,channel,durationMs,thumbnailUrl,live}` from InnerTube `music/get_queue` (no stream URLs). 400 for an id that is not 11 URL-safe characters, 404 when YouTube returns no such video, 30/min per IP (429, a bucket separate from search), 503 when the 300/min upstream budget shared with search is spent, 502 when YouTube fails. Cached 6 h; live videos and misses 5 min |
```

- Privacy: replace `Search queries are cached in memory for 10 minutes and never logged.` with `Search queries (10 minutes) and looked-up video ids (up to 6 hours) are cached in memory and never logged.`

- [ ] **Step 10: Commit**

```bash
git add ritme-server
git commit -m "feat(ritme-server): GET /api/video (InnerTube get_queue, cached, single-flight, own rate limit, upstream budget shared with search)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Web net layer: URL rules twin, link parsing, `/api/video` client

**Files:**
- Create: `ritme-web/src/net/media-url-rules.ts`, `ritme-web/src/net/media-url-rules.test.ts`
- Create: `modules/ritme-protocol/fixtures/rules/youtube-links.json`
- Create: `ritme-web/src/net/video-link.ts`, `ritme-web/src/net/video-link.test.ts`
- Create: `ritme-web/src/net/video.ts`, `ritme-web/src/net/video.test.ts`
- Modify: `ritme-web/src/net/search.ts:19,21` (export two constants)

**Interfaces:**
- Consumes: fixture `modules/ritme-protocol/fixtures/rules/media-urls.json` (Task 2); `GET /api/video` (Task 4).
- Produces (used by Task 6):
  - `media-url-rules.ts`: `MAX_MEDIA_URL_LENGTH = 2048`; `type MediaUrlRejection`; `checkMediaUrl(url: string): MediaUrlRejection | null`.
  - `video-link.ts`: `interface YouTubeLink { videoId: string; music: boolean }`; `interface DirectVideoLink { kind: 'direct'; url: string; hls: boolean; fileName: string; domain: string }`; `type VideoLinkProblem = 'unrecognized' | 'page' | 'dash' | 'unsafe' | 'insecure'`; `type VideoLink = ({ kind: 'youtube' } & YouTubeLink) | DirectVideoLink | { kind: 'rejected'; reason: VideoLinkProblem }`; `parseYouTubeLink(raw: string): YouTubeLink | null`; `classifyVideoLink(raw: string, pageIsHttps: boolean): VideoLink`.
  - `video.ts`: `interface VideoInfo { videoId; title; channel; durationMs; thumbnailUrl: string | null; live: boolean }`; `type VideoInfoFailure = 'invalid' | 'not-found' | 'rate-limited' | 'busy' | 'failed'`; `type VideoInfoOutcome`; `fetchVideoInfo(videoId, fetchImpl = fetch, signal?): Promise<VideoInfoOutcome>`; `toVideoEntry(info: VideoInfo, music: boolean): NewEntry | null`; `toUrlEntry(link: DirectVideoLink, title: string, durationMs: number): NewEntry | null`.
  - `search.ts`: `export const MAX_TEXT_LENGTH = 300; export const MAX_DURATION_MS = 86_400_000;`

- [ ] **Step 1: Write the YouTube link fixture**

Create `modules/ritme-protocol/fixtures/rules/youtube-links.json`:

```json
{
  "_comment": "YouTube links a user may paste. videoId null = not a YouTube video link. Read by ritme-web src/net/video-link.test.ts now and by the Android YouTubeLinks parser in 2b.",
  "cases": [
    { "input": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&index=2", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "youtube.com/watch?v=dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "http://www.youtube.com/watch?v=dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "HTTPS://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "  https://youtu.be/dQw4w9WgXcQ  ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://youtu.be/dQw4w9WgXcQ?si=abc&t=10", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "youtu.be/dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://www.youtube.com/shorts/aqz-KE-bpKQ", "videoId": "aqz-KE-bpKQ", "music": false },
    { "input": "https://youtube.com/shorts/aqz-KE-bpKQ?feature=share", "videoId": "aqz-KE-bpKQ", "music": false },
    { "input": "https://www.youtube.com/live/jfKfPfyJRdk?si=x", "videoId": "jfKfPfyJRdk", "music": false },
    { "input": "https://www.youtube.com/embed/dQw4w9WgXcQ?start=30", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": true },
    { "input": "vnd.youtube:dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },
    { "input": "vnd.youtube://dQw4w9WgXcQ", "videoId": "dQw4w9WgXcQ", "music": false },

    { "input": "https://www.youtube.com/watch?v=dQw4w9WgXc", "videoId": null },
    { "input": "https://www.youtube.com/watch?v=dQw4w9WgXcQQ", "videoId": null },
    { "input": "https://www.youtube.com/watch?list=PL123", "videoId": null },
    { "input": "https://www.youtube.com/playlist?list=PL123", "videoId": null },
    { "input": "https://www.youtube.com/@RickAstleyYT", "videoId": null },
    { "input": "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", "videoId": null },
    { "input": "https://youtu.be/", "videoId": null },
    { "input": "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "videoId": null },
    { "input": "https://evil.example/watch?v=dQw4w9WgXcQ", "videoId": null },
    { "input": "https://evilyoutube.com/watch?v=dQw4w9WgXcQ", "videoId": null },
    { "input": "ftp://youtube.com/watch?v=dQw4w9WgXcQ", "videoId": null },
    { "input": "dQw4w9WgXcQ", "videoId": null },
    { "input": "never gonna give you up", "videoId": null },
    { "input": "", "videoId": null }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

`ritme-web/src/net/media-url-rules.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { MAX_MEDIA_URL_LENGTH, checkMediaUrl } from './media-url-rules';

const FIXTURE = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/media-urls.json', import.meta.url));
const { cases } = JSON.parse(readFileSync(FIXTURE, 'utf8')) as { cases: { url: string; reject: string | null }[] };

describe('checkMediaUrl (same fixture as MediaUrlRulesTest.kt)', () => {
  it('has the shared cases', () => {
    expect(cases.length).toBeGreaterThanOrEqual(80);
  });

  for (const c of cases) {
    it(`${JSON.stringify(c.url)} -> ${c.reject ?? 'accepted'}`, () => {
      expect(checkMediaUrl(c.url)).toBe(c.reject);
    });
  }

  it('caps the length at 2048 characters', () => {
    const base = 'https://cdn.example.com/';
    expect(checkMediaUrl(base + 'a'.repeat(MAX_MEDIA_URL_LENGTH - base.length))).toBeNull();
    expect(checkMediaUrl(base + 'a'.repeat(MAX_MEDIA_URL_LENGTH - base.length + 1))).toBe('TOO_LONG');
  });
});
```

`ritme-web/src/net/video-link.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { classifyVideoLink, parseYouTubeLink } from './video-link';

const FIXTURE = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/youtube-links.json', import.meta.url));
const { cases } = JSON.parse(readFileSync(FIXTURE, 'utf8')) as {
  cases: { input: string; videoId: string | null; music?: boolean }[];
};

describe('parseYouTubeLink (shared fixture)', () => {
  for (const c of cases) {
    it(JSON.stringify(c.input), () => {
      expect(parseYouTubeLink(c.input)).toEqual(c.videoId === null ? null : { videoId: c.videoId, music: c.music ?? false });
    });
  }
});

describe('classifyVideoLink', () => {
  it('recognizes YouTube links first', () => {
    expect(classifyVideoLink('https://youtu.be/dQw4w9WgXcQ', true)).toEqual({ kind: 'youtube', videoId: 'dQw4w9WgXcQ', music: false });
  });

  it('accepts direct media links, normalized and without the fragment', () => {
    expect(classifyVideoLink(' https://www.Example.com/media/My%20Clip.MP4?token=1#t=5 ', true)).toEqual({
      kind: 'direct',
      url: 'https://www.example.com/media/My%20Clip.MP4?token=1',
      hls: false,
      fileName: 'My Clip.MP4',
      domain: 'example.com',
    });
    expect(classifyVideoLink('cdn.example.org/live/index.m3u8', true)).toEqual({
      kind: 'direct',
      url: 'https://cdn.example.org/live/index.m3u8',
      hls: true,
      fileName: 'index.m3u8',
      domain: 'cdn.example.org',
    });
    expect(classifyVideoLink('http://cdn.example.org/a.webm', false)).toMatchObject({ kind: 'direct', url: 'http://cdn.example.org/a.webm' });
  });

  it.each([
    ['', 'unrecognized'],
    ['bukan link', 'unrecognized'],
    ['ftp://cdn.example.org/a.mp4', 'unrecognized'],
    ['https://cdn.example.org/a.mpd', 'dash'],
    ['https://example.com/film/123', 'page'],
    ['https://www.youtube.com/playlist?list=PL1', 'page'],
    ['https://user:pw@cdn.example.org/a.mp4', 'unsafe'],
    ['http://192.168.1.10/a.mp4', 'unsafe'],
    ['http://2130706433/a.mp4', 'unsafe'],
    ['https://localhost/a.mp4', 'unsafe'],
    ['http://cdn.example.org/a.mp4', 'insecure'],
  ])('rejects %j as %s', (input, reason) => {
    expect(classifyVideoLink(input, true)).toEqual({ kind: 'rejected', reason });
  });
});
```

`ritme-web/src/net/video.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { type VideoInfo, fetchVideoInfo, toUrlEntry, toVideoEntry } from './video';
import type { DirectVideoLink } from './video-link';

const info: VideoInfo = {
  videoId: 'aqz-KE-bpKQ',
  title: 'Big Buck Bunny',
  channel: 'Blender',
  durationMs: 635_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
  live: false,
};

function respond(status: number, body: unknown = info) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
}

describe('fetchVideoInfo', () => {
  it('requests /api/video with the id and returns the info', async () => {
    const fetchImpl = respond(200);
    expect(await fetchVideoInfo('aqz-KE-bpKQ', fetchImpl)).toEqual({ ok: true, info });
    expect(fetchImpl).toHaveBeenCalledWith('/api/video?id=aqz-KE-bpKQ', expect.anything());
  });

  it('never calls the server for a malformed id', async () => {
    const fetchImpl = respond(200);
    expect(await fetchVideoInfo('abc', fetchImpl)).toEqual({ ok: false, reason: 'invalid' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it.each<[number, string]>([
    [400, 'invalid'],
    [404, 'not-found'],
    [429, 'rate-limited'],
    [503, 'busy'],
    [502, 'failed'],
    [500, 'failed'],
  ])('maps HTTP %i to %s', async (status, reason) => {
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(status, ''))).toEqual({ ok: false, reason });
  });

  it('treats a network error, a malformed body or another video as failed', async () => {
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    }) as unknown as typeof fetch;
    expect(await fetchVideoInfo('aqz-KE-bpKQ', offline)).toEqual({ ok: false, reason: 'failed' });
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(200, { videoId: 1 }))).toEqual({ ok: false, reason: 'failed' });
    expect(await fetchVideoInfo('aqz-KE-bpKQ', respond(200, { ...info, videoId: 'dQw4w9WgXcQ' }))).toEqual({ ok: false, reason: 'failed' });
  });
});

describe('toVideoEntry', () => {
  it('makes a YOUTUBE_VIDEO entry the server accepts', () => {
    expect(toVideoEntry(info, false)).toEqual({
      videoId: 'aqz-KE-bpKQ',
      title: 'Big Buck Bunny',
      artist: 'Blender',
      thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
      durationMs: 635_000,
      kind: 'YOUTUBE_VIDEO',
      url: null,
    });
  });

  it('uses YOUTUBE_MUSIC for music.youtube.com links, 0 for live, and drops foreign thumbnails', () => {
    expect(toVideoEntry(info, true)?.kind).toBe('YOUTUBE_MUSIC');
    expect(toVideoEntry({ ...info, live: true, durationMs: 0 }, false)?.durationMs).toBe(0);
    expect(toVideoEntry({ ...info, thumbnailUrl: 'https://evil.example/t.jpg' }, false)?.thumbnailUrl).toBeNull();
  });

  it('trims long text and refuses a blank title', () => {
    expect(toVideoEntry({ ...info, title: 'x'.repeat(400) }, false)?.title).toHaveLength(300);
    expect(toVideoEntry({ ...info, title: '   ' }, false)).toBeNull();
  });
});

describe('toUrlEntry', () => {
  const link: DirectVideoLink = {
    kind: 'direct',
    url: 'https://cdn.example.com/media/clip.mp4',
    hls: false,
    fileName: 'clip.mp4',
    domain: 'cdn.example.com',
  };

  it('makes a URL entry with the typed title, the domain as artist and no thumbnail', () => {
    expect(toUrlEntry(link, '  Klip saya ', 61_000)).toEqual({
      videoId: '',
      title: 'Klip saya',
      artist: 'cdn.example.com',
      thumbnailUrl: null,
      durationMs: 61_000,
      kind: 'URL',
      url: 'https://cdn.example.com/media/clip.mp4',
    });
  });

  it('falls back to the file name, then the domain, and clamps the duration', () => {
    expect(toUrlEntry(link, '', 0)?.title).toBe('clip.mp4');
    expect(toUrlEntry({ ...link, fileName: '' }, '', 0)?.title).toBe('cdn.example.com');
    expect(toUrlEntry(link, '', 90_000_000)?.durationMs).toBe(86_400_000);
    expect(toUrlEntry(link, '', Number.NaN)?.durationMs).toBe(0);
  });

  it('refuses a link the server would refuse', () => {
    expect(toUrlEntry({ ...link, url: 'http://192.168.1.1/clip.mp4' }, '', 0)).toBeNull();
  });
});
```

- [ ] **Step 3: Run them to verify they fail**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npx vitest run src/net/media-url-rules.test.ts src/net/video-link.test.ts src/net/video.test.ts
```
Expected: FAIL, `Failed to resolve import "./media-url-rules"` (and `./video-link`, `./video`).

- [ ] **Step 4: Implement**

`src/net/search.ts`: change line 19 `const MAX_TEXT_LENGTH = 300;` to `export const MAX_TEXT_LENGTH = 300;` and line 21 `const MAX_DURATION_MS = 86_400_000;` to `export const MAX_DURATION_MS = 86_400_000;`.

Create `ritme-web/src/net/media-url-rules.ts`:

```ts
/**
 * TypeScript twin of modules/ritme-protocol MediaUrlRules.kt (spec 3.2): the shape a URL entry's
 * url must have for the server to accept it. Pure string checks; nothing is resolved. Both sides
 * are tested against modules/ritme-protocol/fixtures/rules/media-urls.json, so change them together.
 */

export const MAX_MEDIA_URL_LENGTH = 2048;

export type MediaUrlRejection =
  | 'TOO_LONG'
  | 'FORBIDDEN_CHARACTER'
  | 'FRAGMENT'
  | 'MALFORMED'
  | 'SCHEME'
  | 'USERINFO'
  | 'NO_HOST'
  | 'NON_CANONICAL_IP'
  | 'LOCAL_HOST';

const SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):/;
const PORT = /^:([0-9]{1,5})?$/;
const HOST_NAME = /^[a-z0-9_-]+(\.[a-z0-9_-]+)*\.?$/;
const NUMERIC_LABEL = /^(0x[0-9a-f]*|[0-9]+)$/;
const IPV4_PART = /^(0|[1-9][0-9]{0,2})$/;
const IPV6_CHARS = /^[0-9a-f:.]+$/;
const LOCAL_SUFFIXES = ['.localhost', '.local', '.internal', '.lan', '.home.arpa'];

/** Why `url` is refused, or null when it is acceptable. */
export function checkMediaUrl(url: string): MediaUrlRejection | null {
  if (url.length > MAX_MEDIA_URL_LENGTH) return 'TOO_LONG';
  for (let i = 0; i < url.length; i++) {
    const c = url.charCodeAt(i);
    if (c <= 0x20 || (c >= 0x7f && c <= 0x9f) || c === 0x5c) return 'FORBIDDEN_CHARACTER';
  }
  if (url.includes('#')) return 'FRAGMENT';
  const match = SCHEME.exec(url);
  if (!match) return 'MALFORMED';
  const scheme = match[1].toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return 'SCHEME';
  const rest = url.slice(match[1].length + 1);
  if (!rest.startsWith('//')) return 'MALFORMED';
  const authority = /^[^/?]*/.exec(rest.slice(2))![0];
  if (authority.includes('@')) return 'USERINFO';
  const host = hostOf(authority);
  if (host === null) return 'MALFORMED';
  if (host === '') return 'NO_HOST';
  return hostRejection(host);
}

/** The host part of `authority` (an IPv6 literal keeps its brackets), lowercased; null when malformed. */
function hostOf(authority: string): string | null {
  let hostEnd: number;
  if (authority.startsWith('[')) {
    const close = authority.indexOf(']');
    if (close < 0) return null;
    hostEnd = close + 1;
  } else {
    const colon = authority.lastIndexOf(':');
    hostEnd = colon < 0 ? authority.length : colon;
  }
  if (hostEnd < authority.length) {
    const port = PORT.exec(authority.slice(hostEnd));
    if (!port) return null;
    if (port[1] !== undefined && Number(port[1]) > 65_535) return null;
  }
  return authority.slice(0, hostEnd).toLowerCase();
}

function hostRejection(host: string): MediaUrlRejection | null {
  if (host.startsWith('[')) {
    const bytes = parseIpv6(host.slice(1, -1));
    if (!bytes) return 'MALFORMED';
    return isLocalIpv6(bytes) ? 'LOCAL_HOST' : null;
  }
  if (!HOST_NAME.test(host)) return 'MALFORMED';
  const name = host.endsWith('.') ? host.slice(0, -1) : host;
  if (NUMERIC_LABEL.test(name.slice(name.lastIndexOf('.') + 1))) {
    const v4 = parseIpv4(name);
    if (!v4) return 'NON_CANONICAL_IP';
    return isLocalIpv4(v4) ? 'LOCAL_HOST' : null;
  }
  // Single-label names resolve through the LAN's search domain (router, nas, printer).
  if (!name.includes('.') || name === 'home.arpa' || LOCAL_SUFFIXES.some((s) => name.endsWith(s))) return 'LOCAL_HOST';
  return null;
}

/** Four decimal parts 0-255 without leading zeros, or null. */
function parseIpv4(text: string): number[] | null {
  const parts = text.split('.');
  if (parts.length !== 4 || parts.some((p) => !IPV4_PART.test(p))) return null;
  const bytes = parts.map(Number);
  return bytes.every((b) => b <= 255) ? bytes : null;
}

/** 0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12, 192.168/16, and 224/4 + 240/4. */
function isLocalIpv4(a: number[]): boolean {
  return (
    a[0] === 0 ||
    a[0] === 10 ||
    a[0] === 127 ||
    a[0] >= 224 ||
    (a[0] === 100 && a[1] >= 64 && a[1] <= 127) ||
    (a[0] === 169 && a[1] === 254) ||
    (a[0] === 172 && a[1] >= 16 && a[1] <= 31) ||
    (a[0] === 192 && a[1] === 168)
  );
}

/** 16 bytes, or null. Accepts `::` and a dotted IPv4 tail; no zone ids. */
function parseIpv6(text: string): number[] | null {
  if (text === '' || !IPV6_CHARS.test(text)) return null;
  const halves = text.split('::');
  if (halves.length > 2 || (halves.length === 2 && halves[0].includes('.'))) return null;
  const head = ipv6Words(halves[0]);
  if (!head) return null;
  const tail = halves.length === 2 ? ipv6Words(halves[1]) : [];
  if (!tail) return null;
  const missing = 8 - head.length - tail.length;
  const fits = halves.length === 2 ? missing >= 1 : missing === 0;
  if (!fits) return null;
  const words = [...head, ...new Array<number>(missing).fill(0), ...tail];
  const bytes: number[] = [];
  for (const w of words) bytes.push(w >> 8, w & 0xff);
  return bytes;
}

function ipv6Words(part: string): number[] | null {
  if (part === '') return [];
  const pieces = part.split(':');
  const words: number[] = [];
  for (let i = 0; i < pieces.length; i++) {
    const piece = pieces[i];
    if (i === pieces.length - 1 && piece.includes('.')) {
      const v4 = parseIpv4(piece);
      if (!v4) return null;
      words.push((v4[0] << 8) | v4[1], (v4[2] << 8) | v4[3]);
    } else {
      if (piece === '' || piece.length > 4 || piece.includes('.')) return null;
      words.push(parseInt(piece, 16));
    }
  }
  return words;
}

function isLocalIpv6(b: number[]): boolean {
  const zero = (from: number, to: number) => b.slice(from, to).every((x) => x === 0);
  const embeddedV4 = b.slice(12, 16);
  if (zero(0, 12)) return true; // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
  if (zero(0, 10) && b[10] === 0xff && b[11] === 0xff) return isLocalIpv4(embeddedV4); // ::ffff:a.b.c.d
  if (b[0] === 0x00 && b[1] === 0x64 && b[2] === 0xff && b[3] === 0x9b && zero(4, 12)) return isLocalIpv4(embeddedV4); // 64:ff9b::/96
  if ((b[0] & 0xfe) === 0xfc) return true; // fc00::/7
  if (b[0] === 0xfe && b[1] >= 0x80) return true; // fe80::/10, fec0::/10
  return b[0] === 0xff; // multicast
}
```

Create `ritme-web/src/net/video-link.ts`:

```ts
import { checkMediaUrl } from './media-url-rules';
import { MAX_TEXT_LENGTH } from './search';

/** A YouTube video named by a pasted link. `music`: a music.youtube.com link (added as a song). */
export interface YouTubeLink {
  videoId: string;
  music: boolean;
}

/** A direct media link the web can try: mp4/webm/m4v/mov, or HLS (.m3u8). */
export interface DirectVideoLink {
  kind: 'direct';
  /** WHATWG-normalized (punycode host, percent-encoded path), without fragment. */
  url: string;
  hls: boolean;
  /** Last path segment, decoded: the default title. */
  fileName: string;
  /** Host without "www.": shown as the source and sent as the entry's artist. */
  domain: string;
}

export type VideoLinkProblem = 'unrecognized' | 'page' | 'dash' | 'unsafe' | 'insecure';

export type VideoLink = ({ kind: 'youtube' } & YouTubeLink) | DirectVideoLink | { kind: 'rejected'; reason: VideoLinkProblem };

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const HAS_SCHEME = /^[a-z][a-z0-9+.-]*:\/\//i;
const VND = /^vnd\.youtube:(?:\/\/)?([A-Za-z0-9_-]{11})(?:[?&#].*)?$/i;
const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'youtube-nocookie.com',
  'www.youtube-nocookie.com',
  'youtu.be',
]);
const PATH_ID_PREFIXES = new Set(['shorts', 'live', 'embed']);
const DIRECT_PATH = /\.(mp4|webm|m4v|mov|m3u8)$/i;
const HLS_PATH = /\.m3u8$/i;
const DASH_PATH = /\.mpd$/i;

/** An http(s) URL from pasted text (no scheme means https), or null. */
function parseWebUrl(text: string): URL | null {
  if (text === '') return null;
  try {
    const url = new URL(HAS_SCHEME.test(text) ? text : `https://${text}`);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url : null;
  } catch {
    return null;
  }
}

/**
 * The video in a YouTube link: watch?v=, youtu.be/, /shorts/, /live/, /embed/ on youtube.com
 * (www., m., music.) and youtube-nocookie.com, and vnd.youtube:. Timestamps (t=) and playlists
 * (list=) are ignored. Cases: modules/ritme-protocol/fixtures/rules/youtube-links.json.
 */
export function parseYouTubeLink(raw: string): YouTubeLink | null {
  const text = raw.trim();
  const vnd = VND.exec(text);
  if (vnd) return { videoId: vnd[1], music: false };
  const url = parseWebUrl(text);
  if (!url) return null;
  const host = url.hostname.toLowerCase();
  if (!YOUTUBE_HOSTS.has(host)) return null;
  const segments = url.pathname.split('/').filter((s) => s !== '');
  let id: string | null = null;
  if (host === 'youtu.be') {
    if (segments.length === 1) id = segments[0];
  } else if (segments.length === 1 && segments[0] === 'watch') {
    id = url.searchParams.get('v');
  } else if (segments.length === 2 && PATH_ID_PREFIXES.has(segments[0])) {
    id = segments[1];
  }
  return id !== null && VIDEO_ID.test(id) ? { videoId: id, music: host === 'music.youtube.com' } : null;
}

function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/**
 * What the Video tab does with pasted text (spec 6.1): a YouTube video, a direct media link, or
 * why not. Direct links are normalized and lose their fragment; `.mpd` is refused (no DASH on the
 * web), other paths are web pages, links the server would refuse are `unsafe`, and http media
 * cannot play on an https page (`insecure`).
 */
export function classifyVideoLink(raw: string, pageIsHttps: boolean): VideoLink {
  const text = raw.trim();
  const youtube = parseYouTubeLink(text);
  if (youtube) return { kind: 'youtube', ...youtube };
  const url = parseWebUrl(text);
  if (!url) return { kind: 'rejected', reason: 'unrecognized' };
  url.hash = '';
  if (DASH_PATH.test(url.pathname)) return { kind: 'rejected', reason: 'dash' };
  if (!DIRECT_PATH.test(url.pathname)) return { kind: 'rejected', reason: 'page' };
  if (checkMediaUrl(url.href) !== null) return { kind: 'rejected', reason: 'unsafe' };
  if (pageIsHttps && url.protocol === 'http:') return { kind: 'rejected', reason: 'insecure' };
  const last = url.pathname.slice(url.pathname.lastIndexOf('/') + 1);
  return {
    kind: 'direct',
    url: url.href,
    hls: HLS_PATH.test(url.pathname),
    fileName: decodeSegment(last).trim().slice(0, MAX_TEXT_LENGTH),
    domain: url.hostname.replace(/^www\./, ''),
  };
}
```

Create `ritme-web/src/net/video.ts`:

```ts
import type { NewEntry } from '../protocol';
import { checkMediaUrl } from './media-url-rules';
import { MAX_DURATION_MS, MAX_TEXT_LENGTH, validThumbnail } from './search';
import type { DirectVideoLink } from './video-link';

/** GET /api/video?id= (ritme-server Video.kt VideoInfo). */
export interface VideoInfo {
  videoId: string;
  title: string;
  channel: string;
  /** 0 for live videos. */
  durationMs: number;
  thumbnailUrl: string | null;
  live: boolean;
}

export type VideoInfoFailure = 'invalid' | 'not-found' | 'rate-limited' | 'busy' | 'failed';

export type VideoInfoOutcome = { ok: true; info: VideoInfo } | { ok: false; reason: VideoInfoFailure };

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

function parseVideoInfo(v: unknown): VideoInfo | null {
  if (typeof v !== 'object' || v === null) return null;
  const o = v as Record<string, unknown>;
  if (typeof o.videoId !== 'string' || typeof o.title !== 'string' || typeof o.channel !== 'string') return null;
  if (typeof o.durationMs !== 'number' || typeof o.live !== 'boolean') return null;
  return {
    videoId: o.videoId,
    title: o.title,
    channel: o.channel,
    durationMs: o.durationMs,
    thumbnailUrl: typeof o.thumbnailUrl === 'string' ? o.thumbnailUrl : null,
    live: o.live,
  };
}

/** Same-origin metadata lookup; nothing is added unless this succeeds (no made-up titles). */
export async function fetchVideoInfo(
  videoId: string,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<VideoInfoOutcome> {
  if (!VIDEO_ID.test(videoId)) return { ok: false, reason: 'invalid' };
  let response: Response;
  try {
    response = await fetchImpl(`/api/video?id=${encodeURIComponent(videoId)}`, {
      signal,
      headers: { Accept: 'application/json' },
    });
  } catch {
    return { ok: false, reason: 'failed' };
  }
  if (response.status === 400) return { ok: false, reason: 'invalid' };
  if (response.status === 404) return { ok: false, reason: 'not-found' };
  if (response.status === 429) return { ok: false, reason: 'rate-limited' };
  if (response.status === 503) return { ok: false, reason: 'busy' };
  if (!response.ok) return { ok: false, reason: 'failed' };
  try {
    const info = parseVideoInfo(await response.json());
    return info && info.videoId === videoId ? { ok: true, info } : { ok: false, reason: 'failed' };
  } catch {
    return { ok: false, reason: 'failed' };
  }
}

function clampDuration(ms: number): number {
  return Number.isFinite(ms) ? Math.min(Math.max(0, Math.round(ms)), MAX_DURATION_MS) : 0;
}

/** A YouTube video as an Add entry the server accepts; null when the id or title is unusable. */
export function toVideoEntry(info: VideoInfo, music: boolean): NewEntry | null {
  if (!VIDEO_ID.test(info.videoId)) return null;
  const title = info.title.trim().slice(0, MAX_TEXT_LENGTH);
  if (title === '') return null;
  return {
    videoId: info.videoId,
    title,
    artist: info.channel.trim().slice(0, MAX_TEXT_LENGTH),
    thumbnailUrl: validThumbnail(info.thumbnailUrl),
    durationMs: info.live ? 0 : clampDuration(info.durationMs),
    kind: music ? 'YOUTUBE_MUSIC' : 'YOUTUBE_VIDEO',
    url: null,
  };
}

/**
 * A direct link as a URL entry: the typed title, else the file name, else the domain; the domain
 * as artist; never a thumbnail (no image from an arbitrary host for every member). Null when the
 * server would refuse the url.
 */
export function toUrlEntry(link: DirectVideoLink, title: string, durationMs: number): NewEntry | null {
  if (checkMediaUrl(link.url) !== null) return null;
  const name = (title.trim() || link.fileName || link.domain).slice(0, MAX_TEXT_LENGTH);
  if (name === '') return null;
  return {
    videoId: '',
    title: name,
    artist: link.domain.slice(0, MAX_TEXT_LENGTH),
    thumbnailUrl: null,
    durationMs: clampDuration(durationMs),
    kind: 'URL',
    url: link.url,
  };
}
```

- [ ] **Step 5: Run the tests and typecheck**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npx vitest run src/net/ && npm run typecheck
```
Expected: all `src/net` tests pass (including the existing `search.test.ts`); typecheck prints nothing. A failing fixture case names the URL: fix the TS code until it agrees with the Kotlin verdict (Task 2 is the reference).

- [ ] **Step 6: Commit**

```bash
git add modules/ritme-protocol/fixtures/rules/youtube-links.json ritme-web/src/net
git commit -m "feat(ritme-web): MediaUrlRules twin, YouTube/direct link classifier and /api/video client" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Web "Video" tab (probe, preview, add) and 16:9 video rows

**Files:**
- Modify: `ritme-web/src/player/html5.ts:17` (export `loadHlsJs`)
- Create: `ritme-web/src/net/media-probe.ts`, `ritme-web/src/net/media-probe.test.ts`
- Create: `ritme-web/src/ui/add-video.tsx`, `ritme-web/src/ui/add-video.test.tsx`
- Modify: `ritme-web/src/ui/search.tsx` (whole file), `ritme-web/src/ui/room.tsx:71`
- Modify: `ritme-web/src/ui/queue.tsx:18-46` (`Thumb`, `Row`), `ritme-web/src/styles.css` (after `.thumb-empty`)
- Modify: `ritme-web/src/ui/strings.ts`
- Modify: `ritme-web/src/ui/room-panels.test.tsx`, `ritme-web/src/ui/queue.test.tsx`
- Modify: `ritme-web/e2e/smoke.spec.ts` (whole file)
- Modify: `ritme-web/README.md` (Privacy, intro)

**Interfaces:**
- Consumes (Task 5): `classifyVideoLink`, `DirectVideoLink`, `fetchVideoInfo`, `VideoInfo`, `toVideoEntry`, `toUrlEntry`, `MAX_MEDIA_URL_LENGTH`, `VideoLinkProblem`, `VideoInfoFailure`; `MAX_TEXT_LENGTH` from `search.ts`.
- Produces: `media-probe.ts`: `type ProbeOutcome = { kind: 'ready'; durationMs: number; live: boolean } | { kind: 'unplayable' } | { kind: 'timeout' }`; `PROBE_TIMEOUT_MS = 10_000`; `interface ProbeVideo`; `interface ProbeOptions { hls: boolean; signal?: AbortSignal }`; `interface ProbeDeps { createVideo(): ProbeVideo; loadHls(): Promise<HlsConstructor>; timeoutMs: number }`; `probeMedia(url: string, options: ProbeOptions, deps?: ProbeDeps): Promise<ProbeOutcome>`.
- Produces: `AddVideoPanel({ session, online, pageIsHttps, fetchImpl?, probe? })`; `SearchPanel({ session, online, pageIsHttps = true, fetchImpl?, probe? })`; `Thumb({ url, wide = false })`; strings `t.addVideo`, `t.tabSongs`, `t.tabVideo`, `t.addKindGroup`, `t.videoLinkLabel`, `t.videoLinkPlaceholder`, `t.preview`, `t.loadingPreview`, `t.titleOptional`, `t.unknownDuration`, `t.videoBadge`, `t.videoUnplayableWeb`, `t.videoTip`, `videoLinkProblemText`, `videoInfoFailureText`. Task 7's e2e uses the `openRoom` helper defined here.

- [ ] **Step 1: Write the failing probe test**

Create `ritme-web/src/net/media-probe.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HlsConstructor } from '../player/html5';
import { PROBE_TIMEOUT_MS, type ProbeDeps, probeMedia } from './media-probe';

class FakeVideo extends EventTarget {
  muted = false;
  preload = '';
  src = '';
  duration = Number.NaN;
  removed = false;
  nativeHls = false;
  canPlayType(type: string): string {
    return this.nativeHls && type === 'application/vnd.apple.mpegurl' ? 'maybe' : '';
  }
  removeAttribute(name: string): void {
    if (name === 'src') this.src = '';
  }
  load(): void {}
  remove(): void {
    this.removed = true;
  }
  metadata(seconds: number): void {
    this.duration = seconds;
    this.dispatchEvent(new Event('loadedmetadata'));
  }
}

function deps(video: FakeVideo, loadHls?: () => Promise<HlsConstructor>): ProbeDeps {
  return {
    createVideo: () => video,
    loadHls: loadHls ?? (() => Promise.reject(new Error('hls.js is not expected here'))),
    timeoutMs: PROBE_TIMEOUT_MS,
  };
}

function fakeHls(calls: string[], handlers: Array<(event: string, data: { fatal?: boolean }) => void>): HlsConstructor {
  class FakeHls {
    static Events = { ERROR: 'hlsError' };
    static isSupported() {
      return true;
    }
    loadSource(url: string) {
      calls.push(`load ${url}`);
    }
    attachMedia() {
      calls.push('attach');
    }
    on(_event: string, handler: (event: string, data: { fatal?: boolean }) => void) {
      handlers.push(handler);
    }
    destroy() {
      calls.push('destroy');
    }
  }
  return FakeHls as unknown as HlsConstructor;
}

afterEach(() => {
  vi.useRealTimers();
});

describe('probeMedia', () => {
  it('loads only the metadata, muted, reads the duration and cleans up', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    expect(video.src).toBe('https://cdn.example.com/a.mp4');
    expect(video.muted).toBe(true);
    expect(video.preload).toBe('metadata');
    video.metadata(61.4);
    expect(await result).toEqual({ kind: 'ready', durationMs: 61_400, live: false });
    expect(video.src).toBe('');
    expect(video.removed).toBe(true);
  });

  it('reports Infinity as live and an unknown duration as 0 but not live', async () => {
    const live = new FakeVideo();
    const liveResult = probeMedia('https://cdn.example.com/live.m3u8', { hls: false }, deps(live));
    live.metadata(Number.POSITIVE_INFINITY);
    expect(await liveResult).toEqual({ kind: 'ready', durationMs: 0, live: true });
    const unknown = new FakeVideo();
    const unknownResult = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(unknown));
    unknown.metadata(Number.NaN);
    expect(await unknownResult).toEqual({ kind: 'ready', durationMs: 0, live: false });
  });

  it('caps a duration beyond a day at 24 h', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    video.metadata(100_000);
    expect(await result).toEqual({ kind: 'ready', durationMs: 86_400_000, live: false });
  });

  it('reports a media error as unplayable', async () => {
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    video.dispatchEvent(new Event('error'));
    expect(await result).toEqual({ kind: 'unplayable' });
    expect(video.removed).toBe(true);
  });

  it('gives up after 10 s', async () => {
    vi.useFakeTimers();
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false }, deps(video));
    vi.advanceTimersByTime(PROBE_TIMEOUT_MS);
    expect(await result).toEqual({ kind: 'timeout' });
    expect(video.removed).toBe(true);
  });

  it('stops when aborted', async () => {
    const video = new FakeVideo();
    const controller = new AbortController();
    const result = probeMedia('https://cdn.example.com/a.mp4', { hls: false, signal: controller.signal }, deps(video));
    controller.abort();
    expect(await result).toEqual({ kind: 'timeout' });
    expect(video.removed).toBe(true);
  });

  it('uses hls.js for HLS without native support and destroys it afterwards', async () => {
    const calls: string[] = [];
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video, async () => fakeHls(calls, [])));
    await vi.waitFor(() => expect(calls).toEqual(['load https://cdn.example.com/live.m3u8', 'attach']));
    expect(video.src).toBe('');
    video.metadata(Number.POSITIVE_INFINITY);
    expect(await result).toEqual({ kind: 'ready', durationMs: 0, live: true });
    expect(calls).toContain('destroy');
  });

  it('reports a fatal hls.js error as unplayable', async () => {
    const handlers: Array<(event: string, data: { fatal?: boolean }) => void> = [];
    const video = new FakeVideo();
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video, async () => fakeHls([], handlers)));
    await vi.waitFor(() => expect(handlers).toHaveLength(1));
    handlers[0]('hlsError', { fatal: false });
    handlers[0]('hlsError', { fatal: true });
    expect(await result).toEqual({ kind: 'unplayable' });
  });

  it('plays HLS natively where the browser can (Safari)', async () => {
    const video = new FakeVideo();
    video.nativeHls = true;
    const result = probeMedia('https://cdn.example.com/live.m3u8', { hls: true }, deps(video));
    expect(video.src).toBe('https://cdn.example.com/live.m3u8');
    video.metadata(30);
    expect(await result).toEqual({ kind: 'ready', durationMs: 30_000, live: false });
  });
});
```

- [ ] **Step 2: Write the failing UI tests**

Create `ritme-web/src/ui/add-video.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ProbeOptions, ProbeOutcome } from '../net/media-probe';
import { fakeSession } from '../testing/fake-session';
import { AddVideoPanel } from './add-video';

afterEach(cleanup);

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

  it('probes a direct link, takes an optional title and adds a URL entry', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 61_000, live: false });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/media/clip.mp4#t=3');
    expect(await screen.findByText('cdn.example.com · 1:01')).toBeTruthy();
    expect(probe).toHaveBeenCalledWith('https://cdn.example.com/media/clip.mp4', { hls: false, signal: expect.any(AbortSignal) });
    const title = screen.getByLabelText('Judul (opsional)') as HTMLInputElement;
    expect(title.placeholder).toBe('clip.mp4');
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

  it('probes HLS with hls and shows a live stream as LIVE', async () => {
    const session = fakeSession();
    const probe = probing({ kind: 'ready', durationMs: 0, live: true });
    render(<AddVideoPanel session={session} online pageIsHttps probe={probe} />);
    submit('https://live.example.org/stream/index.m3u8');
    expect(await screen.findByText('live.example.org · LIVE')).toBeTruthy();
    expect(probe).toHaveBeenCalledWith('https://live.example.org/stream/index.m3u8', { hls: true, signal: expect.any(AbortSignal) });
    fireEvent.click(screen.getByRole('button', { name: 'Putar berikutnya: index.m3u8' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ kind: 'URL', title: 'index.m3u8', durationMs: 0 }), true);
  });

  it('warns when this browser cannot play the link but still lets it be added', async () => {
    const session = fakeSession();
    render(<AddVideoPanel session={session} online pageIsHttps probe={probing({ kind: 'unplayable' })} />);
    submit('https://cdn.example.com/a.mov');
    expect(await screen.findByText('Video ini tidak bisa diputar di web')).toBeTruthy();
    expect(screen.getByText('cdn.example.com · Durasi tidak diketahui')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Tambah: a.mov' }));
    expect(session.add).toHaveBeenCalledWith(expect.objectContaining({ kind: 'URL', durationMs: 0 }), false);
  });

  it('says the duration is unknown after a timeout', async () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probing({ kind: 'timeout' })} />);
    submit('https://cdn.example.com/a.webm');
    expect(await screen.findByText('cdn.example.com · Durasi tidak diketahui')).toBeTruthy();
    expect(screen.queryByText('Video ini tidak bisa diputar di web')).toBeNull();
  });

  it('keeps the add buttons disabled while probing', async () => {
    const probe = vi.fn((_url: string, _options: ProbeOptions) => new Promise<ProbeOutcome>(() => {}));
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps probe={probe} />);
    submit('https://cdn.example.com/a.mp4');
    expect(await screen.findByText('cdn.example.com · Memuat…')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Tambah: a.mp4' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Putar berikutnya: a.mp4' }) as HTMLButtonElement).disabled).toBe(true);
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
    expect(await screen.findByText('cdn.example.com · 0:05')).toBeTruthy();
  });

  it('always shows the tip about film sites', () => {
    render(<AddVideoPanel session={fakeSession()} online pageIsHttps />);
    expect(screen.getByText('Link halaman situs film tidak bisa diputar langsung. Fitur deteksi video dari situs ada di aplikasi Musicfy.')).toBeTruthy();
  });
});
```

In `ritme-web/src/ui/room-panels.test.tsx`, add inside `describe('SearchPanel', ...)`:

```tsx
  it('switches between the Lagu and Video tabs', () => {
    render(<SearchPanel session={fakeSession()} online />);
    expect(screen.getByRole('heading', { name: 'Tambah lagu' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Video' }));
    expect(screen.getByRole('heading', { name: 'Tambah video' })).toBeTruthy();
    expect(screen.getByLabelText('Tempel link video')).toBeTruthy();
    expect(screen.queryByLabelText('Cari lagu')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Lagu' }));
    expect(screen.getByLabelText('Cari lagu')).toBeTruthy();
  });
```

In `ritme-web/src/ui/queue.test.tsx`, add inside `describe('QueueList', ...)`:

```tsx
  it('shows video entries with a 16:9 thumbnail and a Video badge', () => {
    const videos = [
      entry('a'),
      entry('v', { kind: 'YOUTUBE_VIDEO', title: 'Klip' }),
      entry('u', { kind: 'URL', videoId: '', url: 'https://cdn.example.com/c.mp4', title: 'File' }),
    ];
    render(
      <QueueList session={fakeSession()} room={room({ queue: videos, currentEntryId: 'a' })} memberId="m1" unplayable={{}} online pageIsHttps />,
    );
    expect(document.querySelectorAll('.thumb-wide')).toHaveLength(2);
    expect(screen.getAllByText('Video')).toHaveLength(2);
  });
```

- [ ] **Step 3: Run them to verify they fail**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npx vitest run src/net/media-probe.test.ts src/ui/add-video.test.tsx src/ui/room-panels.test.tsx src/ui/queue.test.tsx
```
Expected: FAIL (`Failed to resolve import "./media-probe"` / `"./add-video"`; no "Video" button; no `.thumb-wide`).

- [ ] **Step 4: Implement the probe**

`src/player/html5.ts` line 17: `const loadHlsJs = ...` becomes `export const loadHlsJs = ...` (unchanged otherwise).

Create `ritme-web/src/net/media-probe.ts`:

```ts
import { type HlsConstructor, type HlsLike, loadHlsJs } from '../player/html5';

/**
 * What a hidden <video preload="metadata" muted> learned about a pasted media link (spec 6.1):
 * `ready` with the duration (a live stream reports Infinity: live, durationMs 0; an unknown
 * duration is 0 and not live), `unplayable` when this browser cannot play it, `timeout` when
 * nothing arrived within PROBE_TIMEOUT_MS. An aborted probe also resolves as `timeout`; callers
 * drop it.
 */
export type ProbeOutcome = { kind: 'ready'; durationMs: number; live: boolean } | { kind: 'unplayable' } | { kind: 'timeout' };

export const PROBE_TIMEOUT_MS = 10_000;
const MAX_DURATION_MS = 86_400_000;

/** The part of HTMLVideoElement the probe uses (a fake in tests). */
export interface ProbeVideo extends EventTarget {
  muted: boolean;
  preload: string;
  src: string;
  readonly duration: number;
  canPlayType(type: string): string;
  removeAttribute(name: string): void;
  load(): void;
  remove(): void;
}

export interface ProbeOptions {
  hls: boolean;
  signal?: AbortSignal;
}

export interface ProbeDeps {
  createVideo(): ProbeVideo;
  loadHls(): Promise<HlsConstructor>;
  timeoutMs: number;
}

function hiddenVideo(): ProbeVideo {
  const video = document.createElement('video');
  video.hidden = true;
  document.body.appendChild(video);
  return video;
}

const defaultDeps: ProbeDeps = { createVideo: hiddenVideo, loadHls: loadHlsJs, timeoutMs: PROBE_TIMEOUT_MS };

/**
 * Loads only the metadata of `url` the way the room's players would (hls.js for HLS where the
 * browser has no native HLS), then tears everything down. The request goes to the media host the
 * entry will be played from anyway, which the CSP's media-src/connect-src already allow.
 */
export function probeMedia(url: string, options: ProbeOptions, deps: ProbeDeps = defaultDeps): Promise<ProbeOutcome> {
  return new Promise((resolve) => {
    const video = deps.createVideo();
    let hls: HlsLike | null = null;
    let done = false;
    const finish = (outcome: ProbeOutcome) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', onAbort);
      hls?.destroy();
      hls = null;
      video.removeAttribute('src');
      try {
        video.load();
      } catch {
        // not implemented (jsdom); the removed src is enough there
      }
      video.remove();
      resolve(outcome);
    };
    const onAbort = () => finish({ kind: 'timeout' });
    const timer = setTimeout(() => finish({ kind: 'timeout' }), deps.timeoutMs);
    if (options.signal?.aborted) {
      finish({ kind: 'timeout' });
      return;
    }
    options.signal?.addEventListener('abort', onAbort);
    video.muted = true;
    video.preload = 'metadata';
    video.addEventListener('loadedmetadata', () => {
      const seconds = video.duration;
      if (seconds === Number.POSITIVE_INFINITY) finish({ kind: 'ready', durationMs: 0, live: true });
      else if (Number.isFinite(seconds) && seconds > 0) {
        finish({ kind: 'ready', durationMs: Math.min(Math.round(seconds * 1000), MAX_DURATION_MS), live: false });
      } else finish({ kind: 'ready', durationMs: 0, live: false });
    });
    video.addEventListener('error', () => finish({ kind: 'unplayable' }));
    const nativeHls = video.canPlayType('application/vnd.apple.mpegurl') !== '';
    if (options.hls && !nativeHls) {
      deps.loadHls().then(
        (Hls) => {
          if (done) return;
          if (!Hls.isSupported()) {
            finish({ kind: 'unplayable' });
            return;
          }
          const instance = new Hls();
          hls = instance;
          instance.on(Hls.Events.ERROR, (_event, data) => {
            if (data.fatal) finish({ kind: 'unplayable' });
          });
          instance.loadSource(url);
          instance.attachMedia(video as unknown as HTMLMediaElement);
        },
        () => finish({ kind: 'unplayable' }),
      );
    } else {
      video.src = url;
    }
  });
}
```

- [ ] **Step 5: Copy, styles and video rows**

`src/ui/strings.ts`: add at the top

```ts
import type { VideoInfoFailure } from '../net/video';
import type { VideoLinkProblem } from '../net/video-link';
```

in `t`, after `close: 'Tutup',` add

```ts

  addVideo: 'Tambah video',
  addKindGroup: 'Yang ditambahkan',
  tabSongs: 'Lagu',
  tabVideo: 'Video',
  videoLinkLabel: 'Tempel link video',
  videoLinkPlaceholder: 'Link YouTube atau link video',
  preview: 'Pratinjau',
  loadingPreview: 'Memuat…',
  titleOptional: 'Judul (opsional)',
  unknownDuration: 'Durasi tidak diketahui',
  videoBadge: 'Video',
  videoUnplayableWeb: 'Video ini tidak bisa diputar di web',
  videoTip: 'Link halaman situs film tidak bisa diputar langsung. Fitur deteksi video dari situs ada di aplikasi Musicfy.',
```

and after `searchFailureText` add

```ts
export const videoLinkProblemText: Record<VideoLinkProblem, string> = {
  unrecognized: 'Link tidak dikenali',
  page: 'Link ini halaman web, bukan file video. Tempel link video langsung (mp4, webm, atau m3u8).',
  dash: 'Video ini tidak bisa diputar di web',
  unsafe: 'Link ini tidak bisa ditambahkan',
  insecure: 'Halaman harus https untuk link http',
};

export const videoInfoFailureText: Record<VideoInfoFailure, string> = {
  invalid: 'Link tidak dikenali',
  'not-found': 'Video tidak ditemukan',
  'rate-limited': 'Terlalu banyak permintaan, coba lagi sebentar',
  busy: 'Server sibuk',
  failed: 'Info video tidak bisa diambil',
};
```

`src/styles.css`, right after the `.thumb-empty { ... }` rule:

```css
/* Video entries (brief §12): the same thumbnail at 16:9. */
.thumb-wide {
  width: 78px;
  height: 44px;
}
```

`src/ui/queue.tsx`: replace `Thumb` with

```tsx
/**
 * A 48 px thumbnail (78 × 44 for videos), or the placeholder unless the URL is https (entries
 * come from any client).
 */
export function Thumb({ url, wide = false }: { url: string | null; wide?: boolean }) {
  const src = httpsThumbnail(url);
  const cls = wide ? 'thumb thumb-wide' : 'thumb';
  return src ? (
    <img class={cls} src={src} alt="" loading="lazy" referrerpolicy="no-referrer" width={wide ? 78 : 48} height={wide ? 44 : 48} />
  ) : (
    <div class={`${cls} thumb-empty`} aria-hidden="true">
      {wide ? '▶' : '♪'}
    </div>
  );
}
```

and in `Row`, after `const label = unplayableLabel(entry, unplayable, pageIsHttps);` add `const video = entry.kind === 'YOUTUBE_VIDEO' || entry.kind === 'URL';`, replace `<Thumb url={entry.thumbnailUrl} />` with `<Thumb url={entry.thumbnailUrl} wide={video} />`, and replace `{label && <span class="badge">{label}</span>}` with

```tsx
        {video && <span class="badge">{t.videoBadge}</span>}
        {label && <span class="badge">{label}</span>}
```

- [ ] **Step 6: The Video tab**

Create `ritme-web/src/ui/add-video.tsx`:

```tsx
import { useEffect, useRef, useState } from 'preact/hooks';
import { type ProbeOutcome, probeMedia } from '../net/media-probe';
import { MAX_MEDIA_URL_LENGTH } from '../net/media-url-rules';
import { MAX_TEXT_LENGTH } from '../net/search';
import { type VideoInfo, fetchVideoInfo, toUrlEntry, toVideoEntry } from '../net/video';
import { type DirectVideoLink, classifyVideoLink } from '../net/video-link';
import type { NewEntry } from '../protocol';
import type { SessionApi } from '../session/session';
import { formatMs } from './format';
import { Thumb } from './queue';
import { t, videoInfoFailureText, videoLinkProblemText } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** http:// media cannot play on an https page (mixed content), so such links are refused. */
  pageIsHttps: boolean;
  /** Injected in tests. */
  fetchImpl?: typeof fetch;
  probe?: typeof probeMedia;
}

type Preview =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'youtube'; info: VideoInfo; entry: NewEntry }
  /** probe is null while the hidden <video> is still loading the metadata. */
  | { kind: 'direct'; link: DirectVideoLink; probe: ProbeOutcome | null };

function directDuration(probe: ProbeOutcome | null): string {
  if (probe === null) return t.loadingPreview;
  if (probe.kind !== 'ready') return t.unknownDuration;
  if (probe.live) return t.live;
  return probe.durationMs > 0 ? formatMs(probe.durationMs) : t.unknownDuration;
}

interface PreviewRowProps {
  thumbnailUrl: string | null;
  title: string;
  meta: string[];
  enabled: boolean;
  onAdd: (playNext: boolean) => void;
}

/** One preview, laid out exactly like a search result row (16:9 thumbnail). */
function PreviewRow({ thumbnailUrl, title, meta, enabled, onAdd }: PreviewRowProps) {
  return (
    <ul class="queue">
      <li class="queue-row">
        <Thumb url={thumbnailUrl} wide />
        <div class="queue-text">
          <span class="title">{title}</span>
          <span class="muted small">{meta.filter(Boolean).join(' · ')}</span>
        </div>
        <div class="queue-actions">
          <button type="button" class="btn btn-small" disabled={!enabled} aria-label={`${t.playNext}: ${title}`} onClick={() => onAdd(true)}>
            {t.playNext}
          </button>
          <button
            type="button"
            class="btn btn-small btn-primary"
            disabled={!enabled}
            aria-label={`${t.addToQueue}: ${title}`}
            onClick={() => onAdd(false)}
          >
            {t.addToQueue}
          </button>
        </div>
      </li>
    </ul>
  );
}

/**
 * The "Video" tab of the add card (spec 6.1, brief §12 L and §13): paste a link, preview it, add
 * it. Nothing is added automatically. YouTube links need /api/video to answer (no made-up
 * titles); direct links are probed by a hidden <video> and may be added even when this browser
 * cannot play them, because an Android member might.
 */
export function AddVideoPanel({ session, online, pageIsHttps, fetchImpl, probe }: Props) {
  const [text, setText] = useState('');
  const [title, setTitle] = useState('');
  const [preview, setPreview] = useState<Preview>({ kind: 'idle' });
  const inflight = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      inflight.current?.abort();
      inflight.current = null;
    },
    [],
  );

  const onSubmit = async (e: Event) => {
    e.preventDefault();
    inflight.current?.abort();
    const controller = new AbortController();
    inflight.current = controller;
    const link = classifyVideoLink(text, pageIsHttps);
    if (link.kind === 'rejected') {
      setPreview({ kind: 'error', message: videoLinkProblemText[link.reason] });
      return;
    }
    if (link.kind === 'youtube') {
      setPreview({ kind: 'loading' });
      const outcome = await fetchVideoInfo(link.videoId, fetchImpl ?? fetch, controller.signal);
      if (inflight.current !== controller) return;
      if (!outcome.ok) {
        setPreview({ kind: 'error', message: videoInfoFailureText[outcome.reason] });
        return;
      }
      const entry = toVideoEntry(outcome.info, link.music);
      setPreview(entry ? { kind: 'youtube', info: outcome.info, entry } : { kind: 'error', message: videoInfoFailureText.failed });
      return;
    }
    setTitle('');
    setPreview({ kind: 'direct', link, probe: null });
    const outcome = await (probe ?? probeMedia)(link.url, { hls: link.hls, signal: controller.signal });
    if (inflight.current !== controller) return;
    setPreview({ kind: 'direct', link, probe: outcome });
  };

  const add = (playNext: boolean) => {
    let entry: NewEntry | null = null;
    if (preview.kind === 'youtube') entry = preview.entry;
    else if (preview.kind === 'direct' && preview.probe) {
      entry = toUrlEntry(preview.link, title, preview.probe.kind === 'ready' ? preview.probe.durationMs : 0);
    }
    if (!entry) return;
    session.add(entry, playNext);
    setText('');
    setTitle('');
    setPreview({ kind: 'idle' });
  };

  const busy = preview.kind === 'loading';
  return (
    <>
      <form class="search-form" onSubmit={onSubmit}>
        <label class="visually-hidden" for="video-link-input">
          {t.videoLinkLabel}
        </label>
        <input
          id="video-link-input"
          type="text"
          inputMode="url"
          autoComplete="off"
          spellcheck={false}
          value={text}
          maxLength={MAX_MEDIA_URL_LENGTH}
          placeholder={t.videoLinkPlaceholder}
          onInput={(e) => setText(e.currentTarget.value)}
        />
        <button type="submit" class="btn" disabled={busy}>
          {busy ? t.loadingPreview : t.preview}
        </button>
      </form>
      {preview.kind === 'error' && (
        <p class="error" role="alert">
          {preview.message}
        </p>
      )}
      {preview.kind === 'youtube' && (
        <PreviewRow
          thumbnailUrl={preview.entry.thumbnailUrl}
          title={preview.entry.title}
          meta={[preview.entry.artist, preview.info.live ? t.live : formatMs(preview.entry.durationMs)]}
          enabled={online}
          onAdd={add}
        />
      )}
      {preview.kind === 'direct' && (
        <div class="stack">
          <label class="field">
            {t.titleOptional}
            <input
              type="text"
              value={title}
              maxLength={MAX_TEXT_LENGTH}
              placeholder={preview.link.fileName}
              onInput={(e) => setTitle(e.currentTarget.value)}
            />
          </label>
          <PreviewRow
            thumbnailUrl={null}
            title={title.trim() || preview.link.fileName || preview.link.domain}
            meta={[preview.link.domain, directDuration(preview.probe)]}
            enabled={online && preview.probe !== null}
            onAdd={add}
          />
          {preview.probe?.kind === 'unplayable' && (
            <p class="muted small" role="status">
              {t.videoUnplayableWeb}
            </p>
          )}
        </div>
      )}
      <p class="muted small">{t.videoTip}</p>
    </>
  );
}
```

Replace `ritme-web/src/ui/search.tsx` with:

```tsx
import { useRef, useState } from 'preact/hooks';
import type { probeMedia } from '../net/media-probe';
import { type SearchResult, searchSongs, toNewEntry, validThumbnail } from '../net/search';
import type { SessionApi } from '../session/session';
import { AddVideoPanel } from './add-video';
import { formatMs } from './format';
import { Thumb } from './queue';
import { searchFailureText, t } from './strings';

interface Props {
  session: SessionApi;
  online: boolean;
  /** Links to http:// media are refused on an https page (mixed content). */
  pageIsHttps?: boolean;
  /** Injected in tests. */
  fetchImpl?: typeof fetch;
  probe?: typeof probeMedia;
}

type Tab = 'songs' | 'video';

/** The add card: song search (Lagu) and pasting a video link (Video), brief §12 L. */
export function SearchPanel({ session, online, pageIsHttps = true, fetchImpl, probe }: Props) {
  const [tab, setTab] = useState<Tab>('songs');
  return (
    <section class="card" aria-labelledby="search-title">
      <h2 id="search-title">{tab === 'songs' ? t.addSong : t.addVideo}</h2>
      <div class="segmented" role="group" aria-label={t.addKindGroup}>
        <button type="button" aria-pressed={tab === 'songs'} onClick={() => setTab('songs')}>
          {t.tabSongs}
        </button>
        <button type="button" aria-pressed={tab === 'video'} onClick={() => setTab('video')}>
          {t.tabVideo}
        </button>
      </div>
      {tab === 'songs' ? (
        <SongSearch session={session} online={online} fetchImpl={fetchImpl} />
      ) : (
        <AddVideoPanel session={session} online={online} pageIsHttps={pageIsHttps} fetchImpl={fetchImpl} probe={probe} />
      )}
    </section>
  );
}

/** Search submits on Enter/button only (the server allows 30 searches per minute per IP). */
function SongSearch({ session, online, fetchImpl }: { session: SessionApi; online: boolean; fetchImpl?: typeof fetch }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inflight = useRef<AbortController | null>(null);

  const onSubmit = async (e: Event) => {
    e.preventDefault();
    inflight.current?.abort();
    const controller = new AbortController();
    inflight.current = controller;
    setBusy(true);
    setError(null);
    const outcome = await searchSongs(query, fetchImpl ?? fetch, controller.signal);
    if (inflight.current !== controller) return;
    setBusy(false);
    if (outcome.ok) setResults(outcome.results);
    else setError(searchFailureText[outcome.reason]);
  };

  const add = (result: SearchResult, playNext: boolean) => {
    const entry = toNewEntry(result);
    if (entry) session.add(entry, playNext);
  };

  return (
    <>
      <form class="search-form" role="search" onSubmit={onSubmit}>
        <label class="visually-hidden" for="search-input">
          {t.searchLabel}
        </label>
        <input
          id="search-input"
          type="search"
          value={query}
          maxLength={200}
          placeholder={t.searchPlaceholder}
          onInput={(e) => setQuery(e.currentTarget.value)}
        />
        <button type="submit" class="btn" disabled={busy}>
          {busy ? t.searching : t.search}
        </button>
      </form>
      {error && (
        <p class="error" role="alert">
          {error}
        </p>
      )}
      {results && results.length === 0 && <p class="muted">{t.noResults}</p>}
      {results && results.length > 0 && (
        <ul class="queue">
          {results.map((r) => (
            <li key={r.videoId} class="queue-row">
              <Thumb url={validThumbnail(r.thumbnailUrl)} />
              <div class="queue-text">
                <span class="title">{r.title}</span>
                <span class="muted small">
                  {r.artist} · {r.durationMs > 0 ? formatMs(r.durationMs) : t.live}
                </span>
              </div>
              <div class="queue-actions">
                <button type="button" class="btn btn-small" disabled={!online} aria-label={`${t.playNext}: ${r.title}`} onClick={() => add(r, true)}>
                  {t.playNext}
                </button>
                <button
                  type="button"
                  class="btn btn-small btn-primary"
                  disabled={!online}
                  aria-label={`${t.addToQueue}: ${r.title}`}
                  onClick={() => add(r, false)}
                >
                  {t.addToQueue}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
```

`src/ui/room.tsx` line 71: `<SearchPanel session={session} online={online} pageIsHttps={https} />`.

- [ ] **Step 7: Run the unit tests, typecheck and build**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck && npm run build
```
Expected: all tests pass (the existing `SearchPanel` tests keep working: "Lagu" is the default tab); typecheck silent; `vite build` writes `dist/` without new warnings (hls.js stays its own lazy chunk).

- [ ] **Step 8: Extend the smoke test**

Replace `ritme-web/e2e/smoke.spec.ts` with:

```ts
import { type BrowserContext, type Page, expect, test } from '@playwright/test';
import { FAKE_IFRAME_API } from './fake-youtube';

const SONG = {
  videoId: 'dQw4w9WgXcQ',
  title: 'Never Gonna Give You Up',
  artist: 'Rick Astley',
  durationMs: 213_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
};

const VIDEO = {
  videoId: 'aqz-KE-bpKQ',
  title: 'Big Buck Bunny',
  channel: 'Blender',
  durationMs: 635_000,
  thumbnailUrl: 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg',
  live: false,
};

/** No real network beyond the local server: YouTube is faked, search and video info are canned, the rest blocked. */
async function isolate(context: BrowserContext) {
  // Routes registered later win, so the catch-all goes first.
  await context.route(/^https?:\/\/(?!localhost[:/])/, (route) => route.abort());
  await context.route('https://www.youtube.com/iframe_api', (route) =>
    route.fulfill({ contentType: 'text/javascript', body: FAKE_IFRAME_API }),
  );
  await context.route('**/api/search?**', (route) => route.fulfill({ json: [SONG] }));
  await context.route('**/api/video?**', (route) => route.fulfill({ json: VIDEO }));
}

/** Tab A starts a room as Budi; tab B joins it through /r/{code} as Sari. */
async function openRoom(context: BrowserContext): Promise<{ a: Page; b: Page; code: string }> {
  const a = await context.newPage();
  await a.goto('/');
  await a.getByLabel('Nama tampilan').fill('Budi');
  await a.getByRole('button', { name: 'Mulai Ritme' }).click();
  const heading = a.getByRole('heading', { level: 1 });
  await expect(heading).toHaveText(/^Ritme · [2-9A-HJ-NP-Z]{6}$/);
  const code = (await heading.textContent())!.slice(-6);
  await expect(a).toHaveURL(new RegExp(`/r/${code}$`));

  const b = await context.newPage();
  await b.goto(`/r/${code}`);
  await expect(b.getByRole('link', { name: 'Buka di aplikasi Musicfy' })).toHaveAttribute('href', `musicfy://ritme/${code}`);
  await b.getByLabel('Nama tampilan').fill('Sari');
  await b.getByRole('button', { name: 'Gabung' }).click();
  await expect(b.getByRole('heading', { level: 1 })).toHaveText(`Ritme · ${code}`);
  await expect(a.locator('.members')).toContainText('Sari');
  return { a, b, code };
}

test('two tabs in one room: the song tab A adds shows up in tab B', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);

  await b.getByRole('button', { name: 'Ketuk untuk ikut dengar' }).click();

  await a.getByLabel('Cari lagu').fill('rick');
  await a.getByRole('button', { name: 'Cari', exact: true }).click();
  await a.getByRole('button', { name: `Tambah: ${SONG.title}` }).click();

  const nowPlaying = b.locator('section.now-playing');
  await expect(nowPlaying).toContainText(SONG.title);
  await expect(nowPlaying).toContainText('oleh Budi');
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { cued: string } }).__fakeYt?.cued)).toBe(SONG.videoId);
  await expect.poll(() => b.evaluate(() => (window as unknown as { __fakeYt?: { playing: boolean } }).__fakeYt?.playing)).toBe(true);
});

test('a YouTube link and a direct mp4 link from the Video tab reach the other tab', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);

  await a.getByRole('button', { name: 'Video', exact: true }).click();
  await a.getByLabel('Tempel link video').fill(`https://youtu.be/${VIDEO.videoId}?t=42`);
  await a.getByRole('button', { name: 'Pratinjau' }).click();
  await expect(a.getByText(`${VIDEO.channel} · 10:35`)).toBeVisible();
  await a.getByRole('button', { name: `Tambah: ${VIDEO.title}` }).click();
  await expect(b.locator('section.now-playing')).toContainText(VIDEO.title);

  // Every non-local request is blocked here, so the probe fails: the preview says so and still allows adding.
  await a.getByLabel('Tempel link video').fill('https://media.example/clips/sample.mp4');
  await a.getByRole('button', { name: 'Pratinjau' }).click();
  await expect(a.getByText('Video ini tidak bisa diputar di web')).toBeVisible();
  await a.getByLabel('Judul (opsional)').fill('Klip contoh');
  await a.getByRole('button', { name: 'Tambah: Klip contoh' }).click();

  const queue = b.locator('section[aria-labelledby="queue-title"]');
  await expect(queue).toContainText('Klip contoh');
  await expect(queue).toContainText('media.example');
  await expect(queue.locator('.thumb-wide')).toHaveCount(1);
});
```

- [ ] **Step 9: Run the smoke test**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm run build && npm run e2e
```
Expected: `2 passed`.

- [ ] **Step 10: Check the guard rails and document**

```bash
git diff --quiet -- ritme-web/index.html ritme-web/package.json ritme-web/package-lock.json && echo "CSP and dependencies unchanged"
```
Expected: `CSP and dependencies unchanged`.

`ritme-web/README.md`:
- Intro: replace `so the WebSocket (\`/ritme\`) and search (\`/api/search\`) need no CORS.` with `so the WebSocket (\`/ritme\`), search (\`/api/search\`) and video info (\`/api/video\`) need no CORS.`
- Privacy: replace `and the media of \`URL\` entries.` with `the media of \`URL\` entries, and, in the "Video" tab, the metadata of a pasted media link (a hidden \`<video preload="metadata" muted>\`, to the host that link points at).`

- [ ] **Step 11: Commit**

```bash
git add ritme-web
git commit -m "feat(ritme-web): Video tab (YouTube links via /api/video, mp4/webm/m3u8 links with a metadata probe) and 16:9 video rows" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Web `guestsCanAdd` controls

**Files:**
- Modify: `ritme-web/src/session/permissions.ts`, `ritme-web/src/session/permissions.test.ts`
- Modify: `ritme-web/src/session/session.ts` (`SessionApi`, `Session`), `ritme-web/src/session/session.test.ts`
- Modify: `ritme-web/src/testing/fake-session.ts`
- Modify: `ritme-web/src/ui/members.tsx`, `ritme-web/src/ui/room-panels.test.tsx`
- Modify: `ritme-web/src/ui/room.tsx`, `ritme-web/src/ui/strings.ts`
- Modify: `ritme-web/e2e/smoke.spec.ts` (new test)

**Interfaces:**
- Consumes: `RoomSettings.guestsCanAdd`, `Session.sendSettings` (Task 1); `SearchPanel`, `openRoom` (Task 6).
- Produces: `canAdd(room: RoomState, memberId: string | null): boolean`; `SessionApi.setGuestsCanAdd(value: boolean): void`; `t.guestsCanAdd = 'Anggota boleh menambah'`.

- [ ] **Step 1: Write the failing tests**

`src/session/permissions.test.ts`: add `canAdd` to the import from `./permissions` and add inside `describe('permissions', ...)`:

```ts
  it('host can always add; guests only while guestsCanAdd', () => {
    const noAdds = room({ settings: { guestsCanControl: true, guestsCanAdd: false } });
    expect(canAdd(room(), 'm2')).toBe(true);
    expect(canAdd(noAdds, 'm2')).toBe(false);
    expect(canAdd(noAdds, 'm1')).toBe(true);
    expect(canAdd(noAdds, null)).toBe(false);
  });
```

`src/session/session.test.ts`, replace the test added in Task 1 (`'SetSettings sends every setting, changing only the one asked for'`) with:

```ts
  it('SetSettings sends every setting, changing only the one asked for', () => {
    const t = setup();
    t.session.create('Budi');
    t.welcome(t.conns[0]);
    t.session.setGuestsCanControl(false);
    t.session.setGuestsCanAdd(false);
    expect(t.conns[0].commands).toEqual([
      { type: 'SetSettings', settings: { guestsCanControl: false, guestsCanAdd: true } },
      // Built from the latest snapshot, which still says guestsCanControl: true (no optimistic updates).
      { type: 'SetSettings', settings: { guestsCanControl: true, guestsCanAdd: false } },
    ]);
  });
```

`src/ui/room-panels.test.tsx`, in `'gives the host member actions and the control switch'` after the `setGuestsCanControl` assertion add

```tsx
    fireEvent.click(screen.getByRole('switch', { name: 'Anggota boleh menambah' }));
    expect(session.setGuestsCanAdd).toHaveBeenCalledWith(false);
```

`src/testing/fake-session.ts`: after `setGuestsCanControl: vi.fn(),` add `setGuestsCanAdd: vi.fn(),`.

- [ ] **Step 2: Run them to verify they fail**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npx vitest run src/session src/ui/room-panels.test.tsx
```
Expected: FAIL (`canAdd` is not exported; `setGuestsCanAdd` is not a function; no switch named "Anggota boleh menambah").

- [ ] **Step 3: Implement**

`src/session/permissions.ts`, after `canControl`:

```ts
/** Add (spec U6): the host always; others only while the host allows it. */
export function canAdd(room: RoomState, memberId: string | null): boolean {
  return isHost(room, memberId) || (memberId !== null && room.settings.guestsCanAdd);
}
```

`src/session/session.ts`: in `SessionApi` after `setGuestsCanControl(value: boolean): void;` add `setGuestsCanAdd(value: boolean): void;`, and in `Session` after `setGuestsCanControl` add

```ts
  setGuestsCanAdd(value: boolean): void {
    this.sendSettings({ guestsCanAdd: value });
  }
```

`src/ui/strings.ts`: after `guestsCanControl: 'Anggota boleh kontrol',` add `guestsCanAdd: 'Anggota boleh menambah',`.

`src/ui/members.tsx`: replace the `{host && ( <label class="switch"> ... guestsCanControl ... </label> )}` block with

```tsx
      {host && (
        <label class="switch">
          <input
            type="checkbox"
            role="switch"
            checked={room.settings.guestsCanControl}
            onChange={(e) => session.setGuestsCanControl(e.currentTarget.checked)}
          />
          <span>{t.guestsCanControl}</span>
        </label>
      )}
      {host && (
        <label class="switch">
          <input
            type="checkbox"
            role="switch"
            checked={room.settings.guestsCanAdd}
            onChange={(e) => session.setGuestsCanAdd(e.currentTarget.checked)}
          />
          <span>{t.guestsCanAdd}</span>
        </label>
      )}
```

`src/ui/room.tsx`: add `import { canAdd } from '../session/permissions';` and replace the `SearchPanel` line with

```tsx
      {/* Hidden, not disabled, while the host keeps guests from adding (spec 8). */}
      {canAdd(room, memberId) && <SearchPanel session={session} online={online} pageIsHttps={https} />}
```

- [ ] **Step 4: Run the unit tests and typecheck**

```bash
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck
```
Expected: all pass; typecheck silent.

- [ ] **Step 5: Add the smoke test**

Append to `ritme-web/e2e/smoke.spec.ts`:

```ts
test('the host can stop guests from adding', async ({ context }) => {
  await isolate(context);
  const { a, b } = await openRoom(context);
  await expect(b.getByRole('heading', { name: 'Tambah lagu' })).toBeVisible();
  await a.getByRole('switch', { name: 'Anggota boleh menambah' }).click();
  await expect(b.getByRole('heading', { name: 'Tambah lagu' })).toHaveCount(0);
  await expect(a.getByRole('heading', { name: 'Tambah lagu' })).toBeVisible();
  await a.getByRole('switch', { name: 'Anggota boleh menambah' }).click();
  await expect(b.getByRole('heading', { name: 'Tambah lagu' })).toBeVisible();
});
```

- [ ] **Step 6: Run the full web check**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck && npm run build && npm run e2e
```
Expected: unit tests pass, typecheck silent, build OK, `3 passed`.

- [ ] **Step 7: Commit**

```bash
git add ritme-web
git commit -m "feat(ritme-web): host switch 'Anggota boleh menambah'; the add card is hidden for guests while it is off" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Android `guestsCanAdd` (session, permission, controller, room sheet switch)

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeSession.kt:107`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmePermissions.kt:19`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt:145-180`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt:258-287`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActions.kt` (KDoc of `canManageRoom`)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/session/RitmePermissionsTest.kt`, `.../session/RitmeSessionTest.kt`, `.../ritme/RitmeControllerTest.kt`

**Interfaces:**
- Consumes: `RoomSettings.guestsCanAdd` (Task 1); `RitmeMenuSwitchRow(icon, title, checked, enabled, onCheckedChange)` from `ui/screens/ritme/RitmeComponents.kt` (Android plan Task 11); existing `RitmeToast.CommandFailed(NoticeKey.FORBIDDEN)` → `R.string.ritme_toast_forbidden`.
- Produces: `RitmePermissions.canAdd(room: RoomState, memberId: String?): Boolean`; `RitmeSession.setGuestsCanAdd(value: Boolean): Boolean`; `R.string.ritme_guests_can_add`.

- [ ] **Step 1: Write the failing tests**

`RitmePermissionsTest`, add inside the class:

```kotlin
    @Test
    fun theHostAlwaysAddsAndGuestsOnlyWhileAllowed() {
        val noAdds = open.copy(settings = RoomSettings(guestsCanAdd = false))
        assertTrue(RitmePermissions.canAdd(open, "m2"))
        assertFalse(RitmePermissions.canAdd(noAdds, "m2"))
        assertTrue(RitmePermissions.canAdd(noAdds, "m1"))
        assertFalse(RitmePermissions.canAdd(noAdds, null))
    }
```

`RitmeSessionTest`: add `import com.example.musicfy.ritme.protocol.RoomSettings` and inside the class:

```kotlin
    @Test
    fun aSettingsChangeKeepsTheOtherSetting() {
        joinedRoom(room(settings = RoomSettings(guestsCanControl = true, guestsCanAdd = true)))
        assertTrue(session.setGuestsCanAdd(false))
        assertTrue(session.setGuestsCanControl(false))
        assertEquals(
            listOf<Command>(
                Command.SetSettings(RoomSettings(guestsCanControl = true, guestsCanAdd = false)),
                // Built from the latest snapshot, which still allows adding: no optimistic updates.
                Command.SetSettings(RoomSettings(guestsCanControl = false, guestsCanAdd = true)),
            ),
            connector.last.commands,
        )
    }
```

`RitmeControllerTest`: add `import com.example.musicfy.ritme.session.NoticeKey` and inside the class:

```kotlin
    @Test
    fun guestsCannotAddWhileTheHostTurnedAddingOff() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.AddItems(listOf(song("a")), playNext = false))
        controller.handle(RitmeLocalAction.PlayQueue(listOf(song("b"))))
        assertTrue(commands.isEmpty())
        assertEquals(
            listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.FORBIDDEN), RitmeToast.CommandFailed(NoticeKey.FORBIDDEN)),
            toasts.shown,
        )
    }

    @Test
    fun theHostAddsEvenWhileGuestsMayNot() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m1")
        controller.handle(RitmeLocalAction.AddItems(listOf(song("a")), playNext = false))
        assertEquals(1, commands.size)
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.session.RitmePermissionsTest" --tests "com.example.musicfy.ritme.session.RitmeSessionTest" --tests "com.example.musicfy.ritme.RitmeControllerTest"
```
Expected: FAIL, `Unresolved reference 'canAdd'` / `'setGuestsCanAdd'`.

- [ ] **Step 3: Implement the logic**

`RitmePermissions.kt`, after `canControl`:

```kotlin
    /** Add (spec U6): the host always; others only while the host allows it. */
    fun canAdd(room: RoomState, memberId: String?): Boolean =
        isHost(room, memberId) || (memberId != null && room.settings.guestsCanAdd)
```

`RitmeSession.kt`: replace

```kotlin
    fun setGuestsCanControl(value: Boolean): Boolean = send(Command.SetSettings(RoomSettings(guestsCanControl = value)))
```

with

```kotlin
    fun setGuestsCanControl(value: Boolean): Boolean = changeSettings { it.copy(guestsCanControl = value) }
    fun setGuestsCanAdd(value: Boolean): Boolean = changeSettings { it.copy(guestsCanAdd = value) }
```

and add next to `send`:

```kotlin
    /** SetSettings replaces all settings: change one, keep the others as the latest snapshot has them. */
    private fun changeSettings(change: (RoomSettings) -> RoomSettings): Boolean =
        send(Command.SetSettings(change(_state.value.room?.settings ?: RoomSettings())))
```

`RitmeController.kt`: add `import com.example.musicfy.ritme.session.NoticeKey`; in `handle` replace

```kotlin
            is RitmeLocalAction.AddItems -> add(action.songs, action.playNext)
            is RitmeLocalAction.PlayQueue ->
                if (action.songs.isEmpty()) notifier.show(RitmeToast.NotAvailable) else add(action.songs, playNext = false)
```

with

```kotlin
            is RitmeLocalAction.AddItems -> add(room, me, action.songs, action.playNext)
            is RitmeLocalAction.PlayQueue ->
                if (action.songs.isEmpty()) notifier.show(RitmeToast.NotAvailable) else add(room, me, action.songs, playNext = false)
```

and replace the first two lines of `private fun add(songs: List<MediaMetadata?>, playNext: Boolean) {` with

```kotlin
    private fun add(room: RoomState, me: String?, songs: List<MediaMetadata?>, playNext: Boolean) {
        // The server would answer FORBIDDEN; say it right away with the same toast (spec 8).
        if (!RitmePermissions.canAdd(room, me)) {
            notifier.show(RitmeToast.CommandFailed(NoticeKey.FORBIDDEN))
            return
        }
        val batches = RitmeEntries.toAddBatches(songs)
```

(the rest of `add` is unchanged).

- [ ] **Step 4: Run the unit tests**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.*" --tests "com.example.musicfy.ui.screens.ritme.*"
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: The room sheet switch (UI, matching the existing rows)**

`app/src/main/res/values/ritme_strings.xml`, after `ritme_guests_can_control`:

```xml
    <string name="ritme_guests_can_add">Members can add</string>
```

`app/src/main/res/values-in/ritme_strings.xml`, after `ritme_guests_can_control`:

```xml
    <string name="ritme_guests_can_add">Anggota boleh menambah</string>
```

`RitmeRoomSheet.kt`, in `item(key = "actions")`, replace from `val guestsCanControl = room.settings.guestsCanControl` through the closing `}` of `if (isHost) { RitmeMenuSwitchRow(...) }` with:

```kotlin
            val guestsCanControl = room.settings.guestsCanControl
            val guestsCanAdd = room.settings.guestsCanAdd
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Hidden, not dimmed, while the host keeps guests from adding (spec 8).
                if (RitmePermissions.canAdd(room, me)) {
                    Material3MenuGroup(
                        items = listOf(
                            Material3MenuItemData(
                                icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                                title = { Text(stringResource(R.string.ritme_add_songs)) },
                                onClick = onAddSongs,
                            ),
                        ),
                    )
                }
                if (isHost) {
                    RitmeMenuSwitchRow(
                        icon = {
                            Icon(
                                painterResource(if (guestsCanControl) R.drawable.lock_open else R.drawable.lock),
                                contentDescription = null,
                            )
                        },
                        title = stringResource(R.string.ritme_guests_can_control),
                        checked = guestsCanControl,
                        enabled = canManage,
                        onCheckedChange = { ritme.session.setGuestsCanControl(it) },
                    )
                    RitmeMenuSwitchRow(
                        icon = { Icon(painterResource(R.drawable.playlist_add), contentDescription = null) },
                        title = stringResource(R.string.ritme_guests_can_add),
                        checked = guestsCanAdd,
                        enabled = canManage,
                        onCheckedChange = { ritme.session.setGuestsCanAdd(it) },
                    )
                }
```

(the following `Material3MenuGroup` with Leave/End stays as it is, inside the same `Column`). Update the comment above it to: `// The same menu rows as the song, queue and player menus (Material3MenuGroup, 8 dp apart); a row is dimmed when its onClick is null. The switch rows are that row's mirror with a real switch (RitmeMenuSwitchRow).`

`RitmeRoomActions.kt`: the KDoc of `canManageRoom` becomes `/** "Members can control", "Members can add" and "End Ritme". */`.

- [ ] **Step 6: Compile and build both flavors (separate invocations)**

```bash
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalFossDebug
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalGmsDebug
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Check on a device (when one is connected)**

```bash
adb devices
```
If a device is listed: `adb install -r app/build/outputs/apk/universalFoss/debug/*.apk`, start a room on the phone, open the room sheet: below "Tambah lagu" the host sees two switches of identical shape ("Anggota boleh kontrol", "Anggota boleh menambah"). Join from the web as a guest, switch "Anggota boleh menambah" off on the phone: the web's add card disappears; switch it on again: it returns. With no device, note "device check pending" in the task report.

- [ ] **Step 8: Commit**

```bash
git add app/src
git commit -m "feat(ritme-android): host switch 'Anggota boleh menambah' in the room sheet; guests' adds refused while it is off" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Full verification and deploy check

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything above; the "Ritme server" GitHub workflow (`.github/workflows/ritme-server.yml`) that tests, builds and publishes `ghcr.io/fikrunamin/ritme-server:latest` from `main`.

- [ ] **Step 1: Full local run, one Gradle build at a time**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --console=plain -p ritme-server test :ritme-protocol:test
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
(source ~/.nvm/nvm.sh >/dev/null; cd ritme-web && npm test && npm run typecheck && npm run build && npm run e2e)
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || echo FREE
./gradlew --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.*" --tests "com.example.musicfy.ui.screens.ritme.*"
git status --short
```
Expected: every step green (`BUILD SUCCESSFUL`, Vitest all passed, `3 passed` in Playwright); `git status --short` empty (everything committed in Tasks 1–8).

- [ ] **Step 2: Hand over for merge**

Tell the user: branch `feature/ritme-watch` is ready; they merge it into `main` and push (this session cannot push). Deploying restarts the server, which ends every live room: pick a moment when nobody is listening.

- [ ] **Step 3: Wait for the image**

```bash
gh run list --workflow ritme-server.yml --branch main --limit 1 --json status,conclusion,headSha
git rev-parse origin/main
```
Expected (after `git fetch`): `status` `completed`, `conclusion` `success`, `headSha` equal to `origin/main`. If it failed, open it with `gh run view --log-failed` and stop.

- [ ] **Step 4: Update the VPS**

```bash
ssh dubeli.id 'cd /opt/ritme && docker compose pull && docker compose up -d && docker compose ps'
```
Expected: the `ritme` service `Up` (recreated).

- [ ] **Step 5: Verify the deployment**

```bash
curl -fsS https://ritme.dubeli.id/healthz; echo
curl -sS -o /dev/null -w '%{http_code}\n' 'https://ritme.dubeli.id/api/video?id=bad'
curl -fsS 'https://ritme.dubeli.id/api/video?id=dQw4w9WgXcQ' | python3 -m json.tool
curl -sS -o /dev/null -w '%{http_code}\n' 'https://ritme.dubeli.id/api/video?id=AAAAAAAAAAA'
curl -fsS https://ritme.dubeli.id/ | grep -o "connect-src[^;]*"
ssh dubeli.id 'cd /opt/ritme && docker compose logs --since 15m ritme' | grep -c -e dQw4w9WgXcQ -e AAAAAAAAAAA
```
Expected, in order: `ok`; `400`; JSON with `"videoId": "dQw4w9WgXcQ"`, `"live": false`, `"durationMs"` about `213000`, a `https://i.ytimg.com/...` thumbnail; `404`; `connect-src 'self' https: http:` (CSP unchanged); `0` (no id in the logs). If the third call answers 502 (YouTube refusing the VPS, risk R11), read `docker compose logs --since 15m ritme | grep 'video backend failed'` for the exception class and report it; do not retry in a loop (each call spends the shared budget).

- [ ] **Step 6: Manual check in a browser**

On `https://ritme.dubeli.id` in two browsers (or a phone + a laptop): start a room, open "Video", paste `https://www.youtube.com/watch?v=aqz-KE-bpKQ` → preview with thumbnail, channel and duration → "Tambah" → both play the large YouTube player. Paste `https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4` → preview shows `storage.googleapis.com` and a duration of about 9:56 → "Tambah" → it appears in the other browser's queue with a 16:9 placeholder and the "Video" badge. Paste `http://192.168.1.1/video.mp4` → "Link ini tidak bisa ditambahkan". As host, switch "Anggota boleh menambah" off → the guest's add card disappears. An Android phone in the same room shows the URL entry as "Belum didukung" until 2b; that is expected.

---

## Self-review

**Spec coverage (2a scope):**
- §3.1 `/api/video`: validation, separate per-IP limiter, shared upstream budget, `get_queue` request, parse incl. wrapper and live, response shape, thumbnail host rule, 400/404/429/502/503, LRU 1000 + TTL 6 h/5 min, single flight, no-id logging, `SingleFlightCache` refactor with search tests unchanged → Tasks 3, 4.
- §3.2 `MediaUrlRules` in `:modules:ritme-protocol`, used by `RoomLogic.isValid`; fixture shared with the web client (§9) → Tasks 2, 5. Item 5 (`streamType`/`referrer`) is #3, not here.
- §3.3 / U6 `guestsCanAdd`: default `true`, `FORBIDDEN` for non-hosts, goldens regenerated, TS parser, R9 documented → Task 1; web UI → Task 7; Android room sheet switch → Task 8.
- §6.1 web Video tab: tabs Lagu/Video, YouTube link → parser with shared fixture → `/api/video` → preview → `YOUTUBE_VIDEO`, failures add nothing; direct `.mp4/.webm/.m4v/.mov/.m3u8` → hidden `<video preload="metadata" muted>` (hls.js where needed), `Infinity` → live, 10 s timeout → "Durasi tidak diketahui" and still addable, error → "Video ini tidak bisa diputar di web" and still addable, optional title defaulting to the file name, `artist` = domain; refusals for http-on-https, `.mpd`, page links; TS `MediaUrlRules` before sending → Tasks 5, 6.
- §8 error copy for `/api/video` 404/429/503/502 and `FORBIDDEN` hiding → Tasks 6, 7, 8.
- §10 row 2a "minimal: badge and 16:9 rows" → Task 6.
- Privacy notes (§8 end) → server README (Tasks 2, 4) and web README (Task 6).
- Deploy → Task 9.

**Placeholder scan:** every code step carries the code; the only data not written in the plan is the recorded `get_queue` response, which Task 4 Step 2 records with an exact command and checks.

**Type consistency:** `RoomSettings(guestsCanControl, guestsCanAdd)` (Kotlin and TS); `MediaUrlRules.check`/`Rejection` names = TS `MediaUrlRejection` strings = fixture `reject` values; `UpstreamBudget(perMinute, clock)` and `SingleFlightCache(clock, budget, maxEntries, ttlMs)` as used by `SearchService` and `VideoInfoService`; `VideoInfo` fields identical in `Video.kt`, `video.ts` and the e2e mock; `ProbeOutcome`/`ProbeOptions` shared by `media-probe.ts`, `add-video.tsx` and its test; `DirectVideoLink` produced by `classifyVideoLink` and consumed by `toUrlEntry`; `setGuestsCanAdd` on `SessionApi`, `fakeSession` and Android `RitmeSession`; `canAdd` in TS and Kotlin with the same rule.
