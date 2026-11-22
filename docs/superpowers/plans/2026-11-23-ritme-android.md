# Ritme Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Ritme "listen together" client to the Musicfy Android app: create or join rooms on the Ritme server, follow the room's queue and playback in sync (< 100 ms between Android devices), and turn local controls into room commands.

**Architecture:** A pure-Kotlin core under `app/src/main/kotlin/com/example/musicfy/ritme/` (links, clock sync, drift corrector, reducer, connection state machine, entry mapping, sync planner, controller), fully unit-tested in `app/src/test`. Android glue around it: an OkHttp WebSocket and main-thread timers, a `RitmePlaybackBridge` that drives `MusicService`'s ExoPlayer through a narrow `RitmePlayerHost`, a `RitmeGate` that every local control path consults, and a `ForwardingPlayer` installed on the media session only while a room is active. Compose UI is built from `PopupSheet`s. Outside a room the gate is closed and every existing code path runs exactly as before.

**Tech Stack:** Kotlin 2.3, Jetpack Compose + Material3, Hilt 2.59, Media3 1.7.1 (ExoPlayer, MediaSession), OkHttp WebSocket (already on the classpath through `ktor-client-okhttp`/`media3-datasource-okhttp`), kotlinx.serialization through `:modules:ritme-protocol`, ZXing core 3.5.3 (new, QR code only), JUnit 4.

## Global Constraints

- Feature name in all UI copy: **Ritme**. Code package `com.example.musicfy.ritme` (UI in `com.example.musicfy.ui.screens.ritme`).
- Default server URL `https://ritme.dubeli.id`; WebSocket `wss://ritme.dubeli.id/ritme`; share link `https://ritme.dubeli.id/r/{code}`.
- Deep links: `musicfy://ritme/{code}` and `https://ritme.dubeli.id/r/{code}`. The https intent filter has **no** `android:autoVerify` (no `assetlinks.json` exists).
- The server URL is overridable in Settings (DataStore key `ritmeServerUrl`, blank = default); display name in `ritmeDisplayName` (falls back to the profile `username`).
- Protocol truth is `:modules:ritme-protocol` (`PROTOCOL_VERSION = 1`), already included in `settings.gradle.kts`. Decode with `decodeServerMessageOrNull` (unknown messages are skipped); tolerate `MediaKind.UNKNOWN` and `ErrorCode.UNKNOWN`.
- **Ack flow control:** after applying each `Welcome`/`State` the client sends `Ack(state.version)` with that exact version, also when the snapshot is stale and also when a listener throws. The server stops sending snapshots to a peer whose Ack is outstanding and drops it after 45 s.
- `Create`/`Join` is sent the moment the socket opens (server join deadline 10 s); an open socket that is not welcomed within 10 s counts as a failed attempt; a socket that does not open within 8 s too.
- `Create.initialQueue` carries at most 200 entries; the rest follows after `Welcome` as `Add` batches of at most 200; never more than 500 entries in total. Server rate limits: 10 commands/s per member, 20/s per room.
- NewEntry validation, mirroring the server: `videoId` matches `[A-Za-z0-9_-]{1,64}`, `url = null`, title non-blank and ≤ 300 chars, artist ≤ 300 chars, `thumbnailUrl` null or `https://` ≤ 512 chars on `i.ytimg.com`, `*.ytimg.com`, `*.googleusercontent.com` or `*.ggpht.com`, `durationMs` in 0..86 400 000. Local files are never shared.
- Reconnect: backoff 1 → 2 → 4 → 8 → 15 s with the resume token; give up 30 s after the drop. No optimistic updates: playback and UI always follow the latest server snapshot; snapshots with `version ≤` the applied one are ignored.
- Clock sync: 5 pings 200 ms apart on connect, then one every 30 s; offset from the minimum-RTT sample of the last 8; local clock `SystemClock.elapsedRealtime()`.
- Drift (Android, evaluated every 1 s while playing): < 40 ms ignore; 40–1000 ms play at 0.97× (ahead) or 1.03× (behind) with pitch 1.0 until the drift is < 20 ms; > 1000 ms seek (at most once per 3 s unless the room itself changed). Target < 100 ms between Android devices.
- Android reports `TrackEnded(entryId)` once per entry when its player reaches `STATE_ENDED`; while in a room it never auto-skips a failed stream.
- Scope is sub-project #1 only: `YOUTUBE_MUSIC` plays; `YOUTUBE_VIDEO` plays audio-only (the same videoId resolver); `URL` and `UNKNOWN` entries decode, show "Not supported yet" / "Belum didukung" in the queue and are never played. No watch-together, no in-app browser.
- Isolation: when not in a room `RitmeGate.isActive == false` and the existing playback path is unchanged (every hook is one `ritmeGate.isActive` guard). `BuildConfig.RITME_ENABLED` (`true`) hides every entry point when set to `false`.
- All popups use `PopupSheet` (`LocalZoomOutOverlayState`), never `Dialog`/`ModalBottomSheet` (see `docs/popup-sheet.skill.md`); sheet chrome uses `MenuSurface`/`MenuRowSurface`; buttons are ~46 dp tall.
- Strings go into new `app/src/main/res/values/ritme_strings.xml` (English) and `app/src/main/res/values-in/ritme_strings.xml` (Indonesian), all prefixed `ritme_`.
- Dependencies are pinned in `gradle/libs.versions.toml`. The only new library is `com.google.zxing:core:3.5.3` (spec 3.3 QR). The WebSocket uses OkHttp, which the app already has.
- Threading: `RitmeSession`, `RitmeController`, `RitmePlaybackBridge` and every socket/timer callback run on the main thread.
- Environment and commands (run from `/Users/suryasoft/WebstormProjects/musicfy/.worktrees/ritme-android`, branch `feature/ritme-android`):
  - `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH`
  - unit tests: `./gradlew :app:testUniversalFossDebugUnitTest --tests "<fully.qualified.Test>"`; JVM modules (`:core:*`, `:modules:*`, `:providers:*`): `./gradlew jvmTests`
  - compile: `./gradlew :app:compileUniversalFossDebugKotlin`
  - APK: `./gradlew :app:assembleUniversalFossDebug`
- Commits: one per task, `git commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

## Decisions made while planning (read before reviewing)

1. **Revisi wins over §2.2:** `Create` carries `initialQueue: List<NewEntry>` + `startIndex`; clients send `NewEntry`, the server assigns `entryId`/`addedBy`.
2. **ExoPlayer holds only the room's current entry.** The bridge loads one `MediaItem` at a time; there is no local mirror of the room queue, so the player can never advance on its own. The 1.5 s start anchor covers loading the next track. The room queue is shown by Ritme's own UI (replacing `QueueScreen` while in a room).
3. **Interception has three layers:** (a) `RitmeGate.intercept(...)` at the top of `PlayerConnection`'s control methods, `MusicService.playQueue/playNext/addToQueue`, and the handful of UI call sites that touch `playerConnection.player` directly; (b) `RitmeSessionPlayer`, a `ForwardingPlayer` set as the media session's player only while in a room (notification, headset, Bluetooth, Android Auto); (c) the bridge's 1 s loop, which re-aligns position. Local pauses that bypass (a)/(b) — audio-focus loss, unplugged headphones — stay local until the room's playback changes.
   **Local pause for members without control (coordinator decision, overrides spec 3.4 for pause/resume only):** a member who may not control the room can still pause and resume *their own device*. Pause becomes `RitmeLocalPlayback.pauseLocally()` plus the toast "Paused on this device only" / "Dijeda di perangkat ini saja", and never sends a room command. While paused locally the planner leaves the device alone (same as an audio-focus pause); play resumes it in sync; any change of the room's playback (new entry, Play/Pause/Seek by someone with control) re-syncs it. Seek, next and previous stay blocked for them with "The host locked the controls". Members with control still pause the whole room.
4. **Drift hysteresis:** start correcting at ≥ 40 ms, stop below 20 ms (spec says both "< 40 ms ignore" and "until < 20 ms"). Seeks are rate-limited to one per 3 s, except when the room's playback state changed, where a drift > 250 ms is seeked away at once (so a room `Seek` does not crawl at 3 %).
5. **Initial room queue** = the current song plus what comes after it in play order (history is not carried), capped at 500; local songs are skipped with a toast.
6. **"Tambah ke Ritme" mode** is realised by interception: while in a room, tapping a song anywhere (search results included) adds it to the room; "Add to queue"/"Play next" in any menu become `Add(playNext = false/true)`.
7. `RitmeViewModel` from §1.1 is replaced by a Hilt `EntryPoint` + `rememberRitme()`: the sheets live in `PopupSheetHost`, outside the `NavHost`, and `PlaybackSpeedSheet` already uses this pattern.
8. Queue reordering uses ↑/↓ buttons (same `moveUp`/`moveDown` targets as the web client) instead of a drag handle.
9. Starting or joining is refused while casting (toast). Changing the server URL applies to the next room.
10. When the service is destroyed while in a room the bridge sends `Leave`.
11. No App Links: the https deep link stays without `autoVerify`. Follow-up (not in this plan): publish `/.well-known/assetlinks.json` on the Ritme server and set `autoVerify="true"` once the domain is final.

## File Structure

| Path | Responsibility |
|---|---|
| `app/src/main/kotlin/com/example/musicfy/ritme/RitmeConfig.kt` | BuildConfig switches (enabled, default URL) |
| `.../ritme/RitmeLinks.kt` | Pure: code normalisation, link parsing, server/socket/share URLs, display-name rules |
| `.../ritme/sync/ClockSync.kt` | Pure: server clock offset from ping samples |
| `.../ritme/sync/DriftCorrector.kt` | Pure: `(expected, actual, speed) → Ignore / SetSpeed / Seek` |
| `.../ritme/sync/RitmeSyncPlanner.kt` | Pure: room snapshot + local player view → player operations |
| `.../ritme/net/ConnectionEvents.kt` | Connection event/intent types, `ConnectionLike`, `RitmeConnector` |
| `.../ritme/net/RitmeSocket.kt` | Socket and timer abstractions |
| `.../ritme/net/RitmeConnection.kt` | Pure state machine: join, Ack, pings, reconnect, end reasons |
| `.../ritme/net/AndroidRitmeIo.kt` | OkHttp socket factory, main-thread timers, default connector |
| `.../ritme/session/RitmeUiState.kt` | Session state types |
| `.../ritme/session/RitmeReducer.kt` | Pure reducer (stale versions ignored) |
| `.../ritme/session/RitmePermissions.kt` | Pure permission table §2.3 + queue split/move targets |
| `.../ritme/session/RitmeSession.kt` | Hilt singleton: connection + clock + `StateFlow<RitmeUiState>` + commands |
| `.../ritme/session/RitmeModeTransitions.kt` | Pure: when to enter/leave "Ritme mode" |
| `.../ritme/entries/RitmeEntries.kt` | Pure: `MediaMetadata ↔ NewEntry/QueueEntry`, batching, playability |
| `.../ritme/RitmeGate.kt` | Local action types + the gate every control path consults |
| `.../ritme/RitmeNotifier.kt` | Toast types + notifier interface |
| `.../ritme/RitmeController.kt` | Local intents → permitted room commands; starting/joining; sheet requests |
| `.../ritme/RitmeTexts.kt` | Error/end reason → string resource |
| `.../ritme/RitmeAndroid.kt` | `TopToastRitmeNotifier`, `DataStoreRitmeSettings` |
| `.../ritme/RitmeQueueSongs.kt` | `Queue.ritmeSongs()` helper for interception |
| `.../ritme/playback/RitmePlayerHost.kt` | What the bridge needs from `MusicService` |
| `.../ritme/playback/RitmePlaybackBridge.kt` | Applies room state to ExoPlayer, 1 s drift loop, TrackEnded, enter/exit |
| `.../ritme/playback/RitmeSessionPlayer.kt` | Media-session `ForwardingPlayer` that routes external controls to the gate |
| `app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt` | Hilt bindings |
| `app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt` | Hilt entry point for UI |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt` | `rememberRitme()`, sheet opener, show functions |
| `.../ui/screens/ritme/RitmeComponents.kt` | Button, text field, avatar |
| `.../ui/screens/ritme/RitmeEntrySheet.kt` | Start / join flow |
| `.../ui/screens/ritme/RitmeRoomSheet.kt` | Room sheet, member actions, end confirm, queue page |
| `.../ui/screens/ritme/RitmeQrCode.kt` | QR rendering with ZXing |
| `.../ui/screens/ritme/RitmePill.kt` | "Ritme · N" pill |
| `.../ui/screens/settings/RitmeSettingsScreen.kt` | Server URL + display name |
| Modified | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`, `core/settings/.../constants/PreferenceKeys.kt` + `core/settings/src/test/resources/preference-keys.golden.txt`, `MusicService.kt`, `PlayerConnection.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `NavigationBuilder.kt`, `MusicfySettingsScreen.kt`, `BottomSheetPlayer.kt`, `BottomSheet.kt`, `PlayerControls.kt`, `PlayerProgressSlider.kt`, `LyricsScreen.kt`, `StyleKit.kt`, `TurntableControls.kt`, `PlayerMenu.kt`, `OldPlayerMenu.kt`, `SongMenu.kt`, `YouTubeSongMenu.kt`, `AlbumScreen.kt`, `OnlineSearchResult.kt`, `PlayerMenuActions.kt` |
| Tests | `app/src/test/kotlin/com/example/musicfy/ritme/**` (+ `testing/RitmeBuilders.kt`, `testing/RitmeFakes.kt`) |

All paths below are relative to the worktree root. `.../ritme/` means `app/src/main/kotlin/com/example/musicfy/ritme/`.

---

### Task 1: Wire the protocol module, build config and links

**Files:**
- Modify: `app/build.gradle.kts` (defaultConfig, dependencies)
- Modify: `app/proguard-rules.pro` (append)
- Modify: `core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt` (append; since the desktop-d1 merge the keys live in `:core:settings`, same package `com.example.musicfy.constants`)
- Modify: `core/settings/src/test/resources/preference-keys.golden.txt` (regenerated: two new lines)
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeConfig.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeLinks.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/RitmeLinksTest.kt`

**Interfaces:**
- Consumes: `:modules:ritme-protocol` (already in `settings.gradle.kts`, line `include(":modules:ritme-protocol")`; the app does not depend on it yet); `:core:settings` (holds `PreferenceKeys.kt`, already an app dependency).
- Produces:
  - `BuildConfig.RITME_SERVER_URL: String`, `BuildConfig.RITME_ENABLED: Boolean`, manifest placeholder `${ritmeHost}`.
  - `object RitmeConfig { val ENABLED: Boolean; val DEFAULT_SERVER_URL: String }`
  - `object RitmeLinks { const val CODE_ALPHABET: String; const val CODE_LENGTH = 6; const val MAX_NAME_LENGTH = 32; fun normalizeCode(input: String): String?; fun findRoomCode(text: String): String?; fun normalizeServerUrl(input: String): String?; fun socketUrl(serverUrl: String): String; fun shareLink(serverUrl: String, code: String): String; fun cleanDisplayName(input: String): String? }`
  - `val RitmeServerUrlKey: Preferences.Key<String>`, `val RitmeDisplayNameKey: Preferences.Key<String>`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/example/musicfy/ritme/RitmeLinksTest.kt`:

```kotlin
package com.example.musicfy.ritme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeLinksTest {

    @Test
    fun codesAreUppercasedAndChecked() {
        assertEquals("K7M2QX", RitmeLinks.normalizeCode(" k7m2qx "))
        assertNull(RitmeLinks.normalizeCode("K7M2Q"))
        // 0, 1, I and O are not in the alphabet
        assertNull(RitmeLinks.normalizeCode("K7M2Q1"))
        assertNull(RitmeLinks.normalizeCode("K7M2QO"))
    }

    @Test
    fun roomCodesAreFoundInLinksAndSharedText() {
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("musicfy://ritme/K7M2QX"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/k7m2qx"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("Listen with me on Ritme: https://ritme.dubeli.id/r/K7M2QX"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("http://192.168.1.5:8080/r/K7M2QX"))
        assertNull(RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/K7M2QXY"))
        assertNull(RitmeLinks.findRoomCode("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun serverUrlsAreNormalised() {
        assertEquals("https://ritme.dubeli.id", RitmeLinks.normalizeServerUrl(" https://ritme.dubeli.id/ "))
        assertEquals("http://10.0.2.2:8080", RitmeLinks.normalizeServerUrl("http://10.0.2.2:8080"))
        assertNull(RitmeLinks.normalizeServerUrl("ritme.dubeli.id"))
        assertNull(RitmeLinks.normalizeServerUrl("ftp://ritme.dubeli.id"))
        assertNull(RitmeLinks.normalizeServerUrl("https://"))
        assertNull(RitmeLinks.normalizeServerUrl("https://ritme.dubeli.id/?x=1"))
    }

    @Test
    fun socketAndShareLinksFollowTheServerUrl() {
        assertEquals("wss://ritme.dubeli.id/ritme", RitmeLinks.socketUrl("https://ritme.dubeli.id"))
        assertEquals("ws://10.0.2.2:8080/ritme", RitmeLinks.socketUrl("http://10.0.2.2:8080"))
        assertEquals("wss://example.com/sub/ritme", RitmeLinks.socketUrl("https://example.com/sub/"))
        assertEquals("https://ritme.dubeli.id/r/K7M2QX", RitmeLinks.shareLink("https://ritme.dubeli.id/", "K7M2QX"))
    }

    @Test
    fun displayNamesAreTrimmedAndBounded() {
        assertEquals("Budi", RitmeLinks.cleanDisplayName("  Budi "))
        assertNull(RitmeLinks.cleanDisplayName("   "))
        assertNull(RitmeLinks.cleanDisplayName("x".repeat(33)))
        assertEquals("x".repeat(32), RitmeLinks.cleanDisplayName("x".repeat(32)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.RitmeLinksTest"`
Expected: FAIL — compilation error `Unresolved reference 'RitmeLinks'`.

- [ ] **Step 3: Add build configuration**

In `app/build.gradle.kts`, inside `defaultConfig { ... }`, directly after the line `buildConfigField("long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")`, add:

```kotlin

        // Ritme (listen together): the official relay; users can point the app at their own in Settings.
        val ritmeServerUrl = "https://ritme.dubeli.id"
        buildConfigField("String", "RITME_SERVER_URL", "\"$ritmeServerUrl\"")
        // Kill switch: false hides every Ritme entry point and keeps RitmeGate closed.
        buildConfigField("Boolean", "RITME_ENABLED", "true")
        // Host for the https://<host>/r/{code} deep link in AndroidManifest.xml.
        manifestPlaceholders["ritmeHost"] = java.net.URI(ritmeServerUrl).host
```

In the `dependencies { ... }` block, directly after `implementation(project(":core:settings"))`, add:

```kotlin
    implementation(project(":modules:ritme-protocol"))
```

Append to `app/proguard-rules.pro`:

```
# Ritme wire protocol (kotlinx.serialization sealed hierarchies, decoded by "type")
-keep class com.example.musicfy.ritme.protocol.** { *; }
```

Append to the end of `core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt` (every key must be declared in this file; `PreferenceKeyNamesTest` pins the names):

```kotlin

// Ritme (listen together): server URL override (blank = the official server) and display name
val RitmeServerUrlKey = stringPreferencesKey("ritmeServerUrl")
val RitmeDisplayNameKey = stringPreferencesKey("ritmeDisplayName")
```

- [ ] **Step 4: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeConfig.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.BuildConfig

/** Build-time Ritme switches. Set RITME_ENABLED to false in app/build.gradle.kts to ship without Ritme. */
object RitmeConfig {
    val ENABLED: Boolean get() = BuildConfig.RITME_ENABLED
    val DEFAULT_SERVER_URL: String get() = BuildConfig.RITME_SERVER_URL
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeLinks.kt`:

```kotlin
package com.example.musicfy.ritme

import java.net.URI

/** Room codes, links and server URLs. Pure, so it runs in JVM unit tests. */
object RitmeLinks {
    const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 6
    const val MAX_NAME_LENGTH = 32

    private val LINK = Regex(
        """(?:musicfy://ritme/|https?://[^\s/?#]+/r/)([A-Za-z0-9]{6})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** Upper-cased code, or null unless it is 6 characters of [CODE_ALPHABET]. */
    fun normalizeCode(input: String): String? {
        val code = input.trim().uppercase()
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
    }

    /** The room code in a `musicfy://ritme/{code}` or `http(s)://<host>/r/{code}` link, also inside shared text. */
    fun findRoomCode(text: String): String? = LINK.find(text)?.groupValues?.get(1)?.let(::normalizeCode)

    /** `http(s)://host[:port][/path]` without a trailing slash, query or fragment; null if it is not one. */
    fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val http = trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
        if (!http) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed
    }

    /** `https://x` → `wss://x/ritme`, `http://x` → `ws://x/ritme`. */
    fun socketUrl(serverUrl: String): String {
        val base = serverUrl.trimEnd('/')
        val socket = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        return "$socket/ritme"
    }

    fun shareLink(serverUrl: String, code: String): String = "${serverUrl.trimEnd('/')}/r/$code"

    /** Trimmed and 1..32 characters, or null (the server answers INVALID otherwise). */
    fun cleanDisplayName(input: String): String? =
        input.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.RitmeLinksTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Pin the two new preference keys**

Run: `./gradlew :core:settings:test -PupdateGolden=true && git diff core/settings/src/test/resources/preference-keys.golden.txt`
Expected: exactly two added lines, `RitmeDisplayNameKey=ritmeDisplayName` and `RitmeServerUrlKey=ritmeServerUrl`, and nothing removed.

Run: `./gradlew :core:settings:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt core/settings/src/test/resources/preference-keys.golden.txt app/src/main/kotlin/com/example/musicfy/ritme/RitmeConfig.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeLinks.kt app/src/test/kotlin/com/example/musicfy/ritme/RitmeLinksTest.kt
git commit -m "feat(ritme-android): protocol module, build config and room links" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Clock sync and drift corrector

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/sync/ClockSync.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/sync/DriftCorrector.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/sync/ClockSyncTest.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/sync/DriftCorrectorTest.kt`

**Interfaces:**
- Produces:
  - `class ClockSync(window: Int = ClockSync.WINDOW) { fun addSample(t0: Long, t1: Long, serverTime: Long); fun seed(serverTime: Long, receivedAt: Long); val offset: Long?; val rtt: Long?; fun serverNow(localNow: Long): Long; fun reset(); companion object { const val WINDOW = 8 } }`
  - `sealed interface DriftAction { data object Ignore; data class SetSpeed(val speed: Float); data class Seek(val positionMs: Long) }`
  - `object DriftCorrector { const val START_CORRECTING_MS = 40L; const val SETTLED_MS = 20L; const val SEEK_ABOVE_MS = 1_000L; const val MIN_SEEK_INTERVAL_MS = 3_000L; const val SLOWER = 0.97f; const val FASTER = 1.03f; const val NORMAL = 1f; fun evaluate(expectedMs: Long, actualMs: Long, currentSpeed: Float, nowMs: Long, lastSeekAtMs: Long?, force: Boolean = false): DriftAction }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/example/musicfy/ritme/sync/ClockSyncTest.kt`:

```kotlin
package com.example.musicfy.ritme.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockSyncTest {

    @Test
    fun theSeedIsUsedUntilTheFirstPong() {
        val clock = ClockSync()
        assertNull(clock.offset)
        clock.seed(serverTime = 10_000, receivedAt = 400)
        assertEquals(9_600L, clock.offset)
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050)
        assertEquals(1_000L, clock.offset)
    }

    @Test
    fun theOffsetComesFromTheSmallestRoundTrip() {
        val clock = ClockSync()
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050) // rtt 100, offset 1000
        clock.addSample(t0 = 200, t1 = 220, serverTime = 1_215) // rtt 20, offset 1005
        clock.addSample(t0 = 300, t1 = 500, serverTime = 1_400) // rtt 200, offset 1000
        assertEquals(1_005L, clock.offset)
        assertEquals(20L, clock.rtt)
    }

    @Test
    fun onlyTheLastEightSamplesCount() {
        val clock = ClockSync()
        clock.addSample(t0 = 0, t1 = 1, serverTime = 7) // excellent but old: offset 7
        repeat(8) { i ->
            val t0 = 1_000L * (i + 1)
            clock.addSample(t0 = t0, t1 = t0 + 50, serverTime = t0 + 25 + 1_000)
        }
        assertEquals(1_000L, clock.offset)
        assertEquals(50L, clock.rtt)
    }

    @Test
    fun negativeRoundTripsAreIgnored() {
        val clock = ClockSync()
        clock.addSample(t0 = 500, t1 = 400, serverTime = 9_999)
        assertNull(clock.offset)
    }

    @Test
    fun serverNowAddsTheOffset() {
        val clock = ClockSync()
        assertEquals(1_234L, clock.serverNow(1_234))
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050)
        assertEquals(3_000L, clock.serverNow(2_000))
        clock.reset()
        assertNull(clock.offset)
    }
}
```

`app/src/test/kotlin/com/example/musicfy/ritme/sync/DriftCorrectorTest.kt`:

```kotlin
package com.example.musicfy.ritme.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftCorrectorTest {

    private fun evaluate(actual: Long, speed: Float = 1f, now: Long = 10_000, lastSeek: Long? = null, force: Boolean = false) =
        DriftCorrector.evaluate(expectedMs = 10_000, actualMs = actual, currentSpeed = speed, nowMs = now, lastSeekAtMs = lastSeek, force = force)

    @Test
    fun smallDriftIsIgnored() {
        assertEquals(DriftAction.Ignore, evaluate(10_030))
        assertEquals(DriftAction.Ignore, evaluate(9_961))
    }

    @Test
    fun beingAheadSlowsDownAndBeingBehindSpeedsUp() {
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(10_200))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_800))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(11_000)) // exactly 1000: still speed
    }

    @Test
    fun aCorrectionRunsUntilTheDriftIsUnderTwentyMs() {
        assertEquals(DriftAction.Ignore, evaluate(10_030, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_015, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(9_990, speed = DriftCorrector.FASTER))
    }

    @Test
    fun anOvershootFlipsTheCorrection() {
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_950, speed = DriftCorrector.SLOWER))
    }

    @Test
    fun bigDriftSeeksToTheExpectedPosition() {
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500))
        assertEquals(DriftAction.Seek(10_000), evaluate(8_000))
    }

    @Test
    fun seeksAreRateLimitedUnlessForced() {
        assertEquals(DriftAction.Ignore, evaluate(11_500, now = 10_000, lastSeek = 8_000))
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500, now = 11_000, lastSeek = 8_000))
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500, now = 10_000, lastSeek = 9_900, force = true))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.sync.*"`
Expected: FAIL — `Unresolved reference 'ClockSync'` / `'DriftCorrector'`.

- [ ] **Step 3: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/sync/ClockSync.kt`:

```kotlin
package com.example.musicfy.ritme.sync

/**
 * Server clock estimate (spec 2.4). Each Ping/Pong gives rtt = t1 - t0 and
 * offset = serverTime - (t0 + t1) / 2; the offset in use comes from the sample with the smallest
 * rtt among the last [window] samples. Local times come from SystemClock.elapsedRealtime().
 */
class ClockSync(private val window: Int = WINDOW) {
    private class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()
    private var seeded: Long? = null

    /** t0 = local time the Ping was sent (echoed back as clientTime), t1 = local time the Pong arrived. */
    fun addSample(t0: Long, t1: Long, serverTime: Long) {
        val rtt = t1 - t0
        if (rtt < 0) return
        samples.addLast(Sample(rtt, serverTime - (t0 + t1) / 2))
        while (samples.size > window) samples.removeFirst()
    }

    /** Rough offset from Welcome.serverTime, used only until the first Pong arrives. */
    fun seed(serverTime: Long, receivedAt: Long) {
        seeded = serverTime - receivedAt
    }

    val offset: Long? get() = samples.minByOrNull { it.rtt }?.offset ?: seeded

    val rtt: Long? get() = samples.minOfOrNull { it.rtt }

    fun serverNow(localNow: Long): Long = localNow + (offset ?: 0L)

    fun reset() {
        samples.clear()
        seeded = null
    }

    companion object {
        const val WINDOW = 8
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/sync/DriftCorrector.kt`:

```kotlin
package com.example.musicfy.ritme.sync

import kotlin.math.abs

sealed interface DriftAction {
    data object Ignore : DriftAction
    data class SetSpeed(val speed: Float) : DriftAction
    data class Seek(val positionMs: Long) : DriftAction
}

/**
 * Android drift policy (spec 2.5), evaluated about once a second while playing:
 * - under 40 ms: nothing; a running correction continues until the drift is under 20 ms, then
 *   the speed goes back to 1.0 (hysteresis, so it does not toggle around 40 ms);
 * - 40..1000 ms: 0.97x when ahead of the room, 1.03x when behind (pitch stays 1.0);
 * - over 1000 ms: seek to the expected position, at most once per [MIN_SEEK_INTERVAL_MS] unless
 *   [force] (the room's playback just changed), so a slow network cannot loop seek -> rebuffer -> seek.
 */
object DriftCorrector {
    const val START_CORRECTING_MS = 40L
    const val SETTLED_MS = 20L
    const val SEEK_ABOVE_MS = 1_000L
    const val MIN_SEEK_INTERVAL_MS = 3_000L
    const val SLOWER = 0.97f
    const val FASTER = 1.03f
    const val NORMAL = 1f

    fun evaluate(
        expectedMs: Long,
        actualMs: Long,
        currentSpeed: Float,
        nowMs: Long,
        lastSeekAtMs: Long?,
        force: Boolean = false,
    ): DriftAction {
        val drift = actualMs - expectedMs // > 0: ahead of the room
        val size = abs(drift)
        if (size > SEEK_ABOVE_MS) {
            val canSeek = force || lastSeekAtMs == null || nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS
            return if (canSeek) DriftAction.Seek(expectedMs) else DriftAction.Ignore
        }
        val correcting = currentSpeed != NORMAL
        if (correcting && size < SETTLED_MS) return DriftAction.SetSpeed(NORMAL)
        if (!correcting && size < START_CORRECTING_MS) return DriftAction.Ignore
        val wanted = if (drift > 0) SLOWER else FASTER
        return if (currentSpeed == wanted) DriftAction.Ignore else DriftAction.SetSpeed(wanted)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.sync.*"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/sync app/src/test/kotlin/com/example/musicfy/ritme/sync
git commit -m "feat(ritme-android): clock sync and drift corrector" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Session state, reducer and permissions

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/net/ConnectionEvents.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeUiState.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeReducer.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmePermissions.kt`
- Create: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeReducerTest.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/session/RitmePermissionsTest.kt`

**Interfaces:**
- Consumes: protocol types `RoomState`, `QueueEntry`, `Member`, `RoomSettings`, `Playback`, `MediaKind`, `ServerMessage.Welcome`, `ErrorCode`, `Command`, `NewEntry`.
- Produces (package `com.example.musicfy.ritme.net`):
  - `enum class ConnectionStatus { CONNECTING, ONLINE, RECONNECTING }`
  - `sealed interface EndReason { Left; Kicked; Closed; Lost; Unreachable; data class Rejected(val code: ErrorCode) }` (objects are `data object`)
  - `sealed interface JoinIntent { val name: String; data class Create(name, initialQueue: List<NewEntry> = emptyList(), startIndex: Int = 0, startPositionMs: Long = 0); data class Join(val code: String, name) }`
  - `sealed interface ConnectionEvent { Status(status); Welcome(welcome: ServerMessage.Welcome, receivedAt: Long); State(state: RoomState); Pong(clientTime: Long, serverTime: Long, receivedAt: Long); Failure(reqId: String?, code: ErrorCode); Ended(reason: EndReason) }`
  - `interface ConnectionLike { fun start(intent: JoinIntent); fun command(cmd: Command): String?; fun trackEnded(entryId: String): Boolean; fun leave() }`
  - `fun interface RitmeConnector { fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike }`
- Produces (package `com.example.musicfy.ritme.session`):
  - `enum class RitmePhase { IDLE, CONNECTING, IN_ROOM, ENDED }`, `enum class NoticeKey { FORBIDDEN, RATE_LIMITED, QUEUE_FULL, INVALID, FAILED }`, `data class RitmeNotice(val id: Long, val key: NoticeKey)`
  - `data class RitmeUiState(phase, connection, memberId: String?, room: RoomState?, serverProtocolVersion: Int?, ended: EndReason?, notice: RitmeNotice?) { val inRoom: Boolean }`
  - `object RitmeReducer { fun start(state): RitmeUiState; fun reset(state): RitmeUiState; fun withNotice(state, key): RitmeUiState; fun noticeFor(code: ErrorCode): NoticeKey; fun reduce(state, event: ConnectionEvent): RitmeUiState }`
  - `data class QueueView(played, current: QueueEntry?, upcoming)`, `data class MoveTarget(val afterEntryId: String?)`
  - `object RitmePermissions { fun isHost(room, memberId: String?): Boolean; fun canControl(room, memberId): Boolean; fun canRemove(room, memberId, entry): Boolean; fun split(room): QueueView; fun moveUpTarget(room, entryId): MoveTarget?; fun moveDownTarget(room, entryId): MoveTarget? }`
- Test builders (package `com.example.musicfy.ritme.testing`): `entry(id, addedBy = "m1", durationMs = 200_000, kind = YOUTUBE_MUSIC, videoId = "vid$id")`, `member(id, isHost = false, connected = true)`, `room(code = "K7M2QX", version = 1, hostId = "m1", queue, currentEntryId, playback, settings, members)`, `welcome(state, memberId = "m1", resumeToken = "tok", serverTime = 0)`.

- [ ] **Step 1: Write the test builders**

`app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt`:

```kotlin
package com.example.musicfy.ritme.testing

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage

fun entry(
    id: String,
    addedBy: String = "m1",
    durationMs: Long = 200_000,
    kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    videoId: String = "vid$id",
) = QueueEntry(
    entryId = id,
    videoId = videoId,
    title = "Song $id",
    artist = "Artist $id",
    thumbnailUrl = null,
    durationMs = durationMs,
    addedBy = addedBy,
    kind = kind,
)

fun member(id: String, isHost: Boolean = false, connected: Boolean = true) =
    Member(id = id, name = "Name $id", isHost = isHost, connected = connected, joinedAt = 0)

fun room(
    code: String = "K7M2QX",
    version: Long = 1,
    hostId: String = "m1",
    queue: List<QueueEntry> = emptyList(),
    currentEntryId: String? = null,
    playback: Playback = Playback(),
    settings: RoomSettings = RoomSettings(),
    members: List<Member> = listOf(member("m1", isHost = true), member("m2")),
) = RoomState(
    code = code,
    version = version,
    hostId = hostId,
    settings = settings,
    members = members,
    queue = queue,
    currentEntryId = currentEntryId,
    playback = playback,
)

fun welcome(state: RoomState, memberId: String = "m1", resumeToken: String = "tok", serverTime: Long = 0) =
    ServerMessage.Welcome(memberId = memberId, resumeToken = resumeToken, state = state, serverTime = serverTime)
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeReducerTest.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.testing.room
import com.example.musicfy.ritme.testing.welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeReducerTest {

    private val inRoom = RitmeReducer.reduce(
        RitmeReducer.start(RitmeUiState()),
        ConnectionEvent.Welcome(welcome(room(version = 5)), receivedAt = 0),
    )

    @Test
    fun startConnects() {
        assertEquals(RitmePhase.CONNECTING, RitmeReducer.start(RitmeUiState()).phase)
    }

    @Test
    fun welcomeEntersTheRoom() {
        assertEquals(RitmePhase.IN_ROOM, inRoom.phase)
        assertEquals("m1", inRoom.memberId)
        assertEquals(5L, inRoom.room?.version)
        assertTrue(inRoom.inRoom)
    }

    @Test
    fun aNewerStateReplacesTheRoom() {
        val next = RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 6)))
        assertEquals(6L, next.room?.version)
    }

    @Test
    fun staleOrEqualVersionsAreIgnored() {
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 5))))
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 4))))
    }

    @Test
    fun aStateForAnotherRoomIsIgnored() {
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(code = "ZZZZZZ", version = 9))))
    }

    @Test
    fun failuresBecomeNumberedNotices() {
        val a = RitmeReducer.reduce(inRoom, ConnectionEvent.Failure("r1", ErrorCode.FORBIDDEN))
        val b = RitmeReducer.reduce(a, ConnectionEvent.Failure(null, ErrorCode.UNKNOWN))
        assertEquals(RitmeNotice(1, NoticeKey.FORBIDDEN), a.notice)
        assertEquals(RitmeNotice(2, NoticeKey.FAILED), b.notice)
    }

    @Test
    fun anEndDropsTheRoom() {
        val ended = RitmeReducer.reduce(inRoom, ConnectionEvent.Ended(EndReason.Kicked))
        assertEquals(RitmePhase.ENDED, ended.phase)
        assertNull(ended.room)
        assertNull(ended.memberId)
        assertEquals(EndReason.Kicked, ended.ended)
    }

    @Test
    fun statusOnlyChangesTheConnection() {
        val next = RitmeReducer.reduce(inRoom, ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertEquals(ConnectionStatus.RECONNECTING, next.connection)
        assertEquals(inRoom.room, next.room)
    }

    @Test
    fun resetAndStartKeepTheLastNotice() {
        val noticed = RitmeReducer.withNotice(RitmeUiState(), NoticeKey.QUEUE_FULL)
        assertEquals(noticed.notice, RitmeReducer.reset(noticed).notice)
        assertEquals(noticed.notice, RitmeReducer.start(noticed).notice)
        assertEquals(RitmePhase.IDLE, RitmeReducer.reset(noticed).phase)
    }
}
```

`app/src/test/kotlin/com/example/musicfy/ritme/session/RitmePermissionsTest.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmePermissionsTest {

    private val queue = listOf(entry("a", addedBy = "m2"), entry("b"), entry("c", addedBy = "m2"), entry("d"))
    private val open = room(queue = queue, currentEntryId = "b")
    private val locked = open.copy(settings = RoomSettings(guestsCanControl = false))

    @Test
    fun theHostAlwaysControls() {
        assertTrue(RitmePermissions.isHost(locked, "m1"))
        assertTrue(RitmePermissions.canControl(locked, "m1"))
        assertFalse(RitmePermissions.isHost(locked, null))
    }

    @Test
    fun guestsControlOnlyWhileAllowed() {
        assertTrue(RitmePermissions.canControl(open, "m2"))
        assertFalse(RitmePermissions.canControl(locked, "m2"))
    }

    @Test
    fun anyoneMayRemoveTheirOwnEntries() {
        assertTrue(RitmePermissions.canRemove(locked, "m2", queue[2]))
        assertFalse(RitmePermissions.canRemove(locked, "m2", queue[3]))
        assertTrue(RitmePermissions.canRemove(open, "m2", queue[3]))
    }

    @Test
    fun theQueueSplitsAroundTheCurrentEntry() {
        val view = RitmePermissions.split(open)
        assertEquals(listOf("a"), view.played.map { it.entryId })
        assertEquals("b", view.current?.entryId)
        assertEquals(listOf("c", "d"), view.upcoming.map { it.entryId })
    }

    @Test
    fun withNothingCurrentEverythingIsUpcoming() {
        val view = RitmePermissions.split(open.copy(currentEntryId = null))
        assertNull(view.current)
        assertEquals(4, view.upcoming.size)
    }

    @Test
    fun moveTargetsMatchTheServerReading() {
        assertNull(RitmePermissions.moveUpTarget(open, "c")) // already right after the current entry
        assertEquals(MoveTarget(null), RitmePermissions.moveUpTarget(open, "d"))
        assertEquals(MoveTarget("d"), RitmePermissions.moveDownTarget(open, "c"))
        assertNull(RitmePermissions.moveDownTarget(open, "d")) // last
        assertNull(RitmePermissions.moveDownTarget(open, "b")) // the current entry never moves
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.session.*"`
Expected: FAIL — `Unresolved reference 'RitmeReducer'` / `'ConnectionEvent'`.

- [ ] **Step 4: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/net/ConnectionEvents.kt`:

```kotlin
package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage

enum class ConnectionStatus { CONNECTING, ONLINE, RECONNECTING }

/** Why a session ended. Exactly one Ended event is emitted per connection. */
sealed interface EndReason {
    data object Left : EndReason
    data object Kicked : EndReason
    data object Closed : EndReason
    /** The connection dropped after a Welcome and could not resume within 30 s. */
    data object Lost : EndReason
    /** No Welcome was ever received (server unreachable, timed out). */
    data object Unreachable : EndReason
    /** The server refused Create/Join before any Welcome. */
    data class Rejected(val code: ErrorCode) : EndReason
}

sealed interface JoinIntent {
    val name: String

    data class Create(
        override val name: String,
        val initialQueue: List<NewEntry> = emptyList(),
        val startIndex: Int = 0,
        val startPositionMs: Long = 0,
    ) : JoinIntent

    data class Join(val code: String, override val name: String) : JoinIntent
}

sealed interface ConnectionEvent {
    data class Status(val status: ConnectionStatus) : ConnectionEvent
    data class Welcome(val welcome: ServerMessage.Welcome, val receivedAt: Long) : ConnectionEvent
    data class State(val state: RoomState) : ConnectionEvent
    data class Pong(val clientTime: Long, val serverTime: Long, val receivedAt: Long) : ConnectionEvent
    /** A server Error while joined (a rejected command). Named Failure to stay clear of kotlin.Error. */
    data class Failure(val reqId: String?, val code: ErrorCode) : ConnectionEvent
    data class Ended(val reason: EndReason) : ConnectionEvent
}

/** What RitmeSession needs from a connection (a fake in tests). */
interface ConnectionLike {
    fun start(intent: JoinIntent)

    /** Sends a command and returns its reqId; null (dropped) while not joined. */
    fun command(cmd: Command): String?

    fun trackEnded(entryId: String): Boolean

    fun leave()
}

fun interface RitmeConnector {
    fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeUiState.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.RoomState

enum class RitmePhase { IDLE, CONNECTING, IN_ROOM, ENDED }

enum class NoticeKey { FORBIDDEN, RATE_LIMITED, QUEUE_FULL, INVALID, FAILED }

/** A server-side rejection to show once; [id] grows so equal keys still count as new. */
data class RitmeNotice(val id: Long, val key: NoticeKey)

data class RitmeUiState(
    val phase: RitmePhase = RitmePhase.IDLE,
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val memberId: String? = null,
    val room: RoomState? = null,
    val serverProtocolVersion: Int? = null,
    val ended: EndReason? = null,
    val notice: RitmeNotice? = null,
) {
    val inRoom: Boolean get() = phase == RitmePhase.IN_ROOM && room != null
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeReducer.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.protocol.ErrorCode

/**
 * Pure session state machine. The server snapshot is the only truth: no optimistic updates, and a
 * State whose version is not newer than the applied one, or that is for another room, is ignored
 * (spec 2.2).
 */
object RitmeReducer {
    fun start(state: RitmeUiState): RitmeUiState = RitmeUiState(phase = RitmePhase.CONNECTING, notice = state.notice)

    fun reset(state: RitmeUiState): RitmeUiState = RitmeUiState(notice = state.notice)

    fun withNotice(state: RitmeUiState, key: NoticeKey): RitmeUiState =
        state.copy(notice = RitmeNotice(id = (state.notice?.id ?: 0L) + 1, key = key))

    fun noticeFor(code: ErrorCode): NoticeKey = when (code) {
        ErrorCode.FORBIDDEN -> NoticeKey.FORBIDDEN
        ErrorCode.RATE_LIMITED -> NoticeKey.RATE_LIMITED
        ErrorCode.QUEUE_FULL -> NoticeKey.QUEUE_FULL
        ErrorCode.INVALID -> NoticeKey.INVALID
        else -> NoticeKey.FAILED
    }

    fun reduce(state: RitmeUiState, event: ConnectionEvent): RitmeUiState = when (event) {
        is ConnectionEvent.Status -> state.copy(connection = event.status)
        is ConnectionEvent.Welcome -> state.copy(
            phase = RitmePhase.IN_ROOM,
            memberId = event.welcome.memberId,
            room = event.welcome.state,
            serverProtocolVersion = event.welcome.protocolVersion,
            ended = null,
        )
        is ConnectionEvent.State -> {
            val room = state.room
            if (room == null || event.state.code != room.code || event.state.version <= room.version) {
                state
            } else {
                state.copy(room = event.state)
            }
        }
        is ConnectionEvent.Pong -> state
        is ConnectionEvent.Failure -> withNotice(state, noticeFor(event.code))
        is ConnectionEvent.Ended -> state.copy(
            phase = RitmePhase.ENDED,
            ended = event.reason,
            room = null,
            memberId = null,
        )
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/session/RitmePermissions.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState

data class QueueView(val played: List<QueueEntry>, val current: QueueEntry?, val upcoming: List<QueueEntry>)

/** A Move command target: `afterEntryId = null` means "right after the current entry". */
data class MoveTarget(val afterEntryId: String?)

/**
 * Client-side copy of the permission table (spec 2.3), used only to hide or refuse controls. The
 * server checks again and answers FORBIDDEN, so a stale view can never do harm.
 */
object RitmePermissions {
    fun isHost(room: RoomState, memberId: String?): Boolean = memberId != null && room.hostId == memberId

    /** Play, Pause, Seek, Next, Previous, PlayEntry, Move, and Remove of other people's entries. */
    fun canControl(room: RoomState, memberId: String?): Boolean = isHost(room, memberId) || room.settings.guestsCanControl

    fun canRemove(room: RoomState, memberId: String?, entry: QueueEntry): Boolean =
        entry.addedBy == memberId || canControl(room, memberId)

    fun split(room: RoomState): QueueView {
        val index = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        if (index < 0) return QueueView(emptyList(), null, room.queue)
        return QueueView(room.queue.subList(0, index), room.queue[index], room.queue.subList(index + 1, room.queue.size))
    }

    /** Swaps an upcoming entry with the one before it; null when it is already first. */
    fun moveUpTarget(room: RoomState, entryId: String): MoveTarget? {
        val currentIndex = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        val index = room.queue.indexOfFirst { it.entryId == entryId }
        if (index <= currentIndex + 1) return null
        val anchorIndex = index - 2
        if (anchorIndex <= currentIndex) return MoveTarget(null)
        return MoveTarget(room.queue[anchorIndex].entryId)
    }

    /** Swaps an upcoming entry with the one after it; null when it is last (or current, or played). */
    fun moveDownTarget(room: RoomState, entryId: String): MoveTarget? {
        val currentIndex = room.queue.indexOfFirst { it.entryId == room.currentEntryId }
        val index = room.queue.indexOfFirst { it.entryId == entryId }
        if (index <= currentIndex || index + 1 >= room.queue.size) return null
        return MoveTarget(room.queue[index + 1].entryId)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.session.*"`
Expected: PASS (15 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/net/ConnectionEvents.kt app/src/main/kotlin/com/example/musicfy/ritme/session app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt app/src/test/kotlin/com/example/musicfy/ritme/session
git commit -m "feat(ritme-android): session state, reducer and permission table" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: RitmeConnection (join, Ack, pings, reconnect)

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/net/RitmeSocket.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/net/RitmeConnection.kt`
- Create: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/net/RitmeConnectionTest.kt`

**Interfaces:**
- Consumes: Task 3 `ConnectionEvent`, `ConnectionLike`, `ConnectionStatus`, `EndReason`, `JoinIntent`; protocol `ClientMessage`, `ServerMessage`, `Command`, `ErrorCode`, `PROTOCOL_VERSION`, `encode()`, `decodeServerMessageOrNull`.
- Produces:
  - `interface RitmeSocket { fun send(text: String): Boolean; fun close() }`
  - `interface RitmeSocketListener { fun onOpen(); fun onMessage(text: String); fun onClosed() }`
  - `fun interface RitmeSocketFactory { fun open(url: String, listener: RitmeSocketListener): RitmeSocket }` — must never call the listener from inside `open()`.
  - `fun interface RitmeTimer { fun cancel() }`, `interface RitmeTimers { fun now(): Long; fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer }` — never runs `block` from inside `schedule()`.
  - `class RitmeConnection(url: String, socketFactory: RitmeSocketFactory, timers: RitmeTimers, onEvent: (ConnectionEvent) -> Unit) : ConnectionLike` with companion `BACKOFF_MS: LongArray`, `RECONNECT_GIVE_UP_MS = 30_000L`, `CONNECT_TIMEOUT_MS = 8_000L`, `JOIN_TIMEOUT_MS = 10_000L`, `PING_BURST = 5`, `PING_BURST_GAP_MS = 200L`, `PING_INTERVAL_MS = 30_000L`.
- Test fakes: `FakeTimers(time)` with `advanceBy(ms)`, `FakeSocket` (`url`, `sent`, `closed`, `open()`, `receive(ServerMessage)`, `receiveRaw(String)`, `drop()`, `sentMessages()`), `FakeSocketFactory` (`sockets`, `last`).

- [ ] **Step 1: Write the socket abstractions and fakes**

`app/src/main/kotlin/com/example/musicfy/ritme/net/RitmeSocket.kt`:

```kotlin
package com.example.musicfy.ritme.net

/** One WebSocket. [send] queues a text frame; [close] starts a normal close (queued frames go first). */
interface RitmeSocket {
    fun send(text: String): Boolean
    fun close()
}

/** Called on the connection's thread. [onClosed] covers a normal close and any failure, once. */
interface RitmeSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed()
}

/** Opens sockets. Must never call the listener from inside [open]. */
fun interface RitmeSocketFactory {
    fun open(url: String, listener: RitmeSocketListener): RitmeSocket
}

fun interface RitmeTimer {
    fun cancel()
}

/** Monotonic clock + delayed tasks on the connection's thread. Never runs a block from inside [schedule]. */
interface RitmeTimers {
    fun now(): Long
    fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer
}
```

`app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt`:

```kotlin
package com.example.musicfy.ritme.testing

import com.example.musicfy.ritme.net.RitmeSocket
import com.example.musicfy.ritme.net.RitmeSocketFactory
import com.example.musicfy.ritme.net.RitmeSocketListener
import com.example.musicfy.ritme.net.RitmeTimer
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode

/** Manual clock: tasks run in time order (then scheduling order) during [advanceBy]. */
class FakeTimers(var time: Long = 0) : RitmeTimers {
    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    override fun now(): Long = time

    override fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer {
        val task = Task(time + delayMs.coerceAtLeast(0), seq++, block)
        tasks += task
        return RitmeTimer { task.cancelled = true }
    }

    fun advanceBy(ms: Long) {
        val target = time + ms
        while (true) {
            val next = tasks
                .filter { !it.cancelled && it.at <= target }
                .minWithOrNull(compareBy<Task>({ it.at }, { it.seq }))
                ?: break
            tasks.remove(next)
            time = next.at
            next.block()
        }
        time = target
        tasks.removeAll { it.cancelled }
    }
}

class FakeSocket(val url: String, private val listener: RitmeSocketListener) : RitmeSocket {
    val sent = mutableListOf<String>()
    var closed = false
        private set

    override fun send(text: String): Boolean {
        if (closed) return false
        sent += text
        return true
    }

    override fun close() {
        closed = true
    }

    fun open() = listener.onOpen()

    fun receive(message: ServerMessage) = listener.onMessage(message.encode())

    fun receiveRaw(text: String) = listener.onMessage(text)

    fun drop() = listener.onClosed()

    fun sentMessages(): List<ClientMessage> = sent.map(::decodeClientMessage)
}

class FakeSocketFactory : RitmeSocketFactory {
    val sockets = mutableListOf<FakeSocket>()
    val last: FakeSocket get() = sockets.last()

    override fun open(url: String, listener: RitmeSocketListener): RitmeSocket =
        FakeSocket(url, listener).also { sockets += it }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/example/musicfy/ritme/net/RitmeConnectionTest.kt`:

```kotlin
package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.testing.FakeSocketFactory
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ritme.testing.room
import com.example.musicfy.ritme.testing.welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeConnectionTest {

    private val timers = FakeTimers()
    private val sockets = FakeSocketFactory()
    private val events = mutableListOf<ConnectionEvent>()

    private fun connection(onEvent: (ConnectionEvent) -> Unit = { events += it }) =
        RitmeConnection("wss://ritme.test/ritme", sockets, timers, onEvent)

    private fun joined(): RitmeConnection = connection().also {
        it.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
    }

    private fun sent(): List<ClientMessage> = sockets.sockets.flatMap { it.sentMessages() }

    private fun ended(): EndReason? = events.filterIsInstance<ConnectionEvent.Ended>().singleOrNull()?.reason

    @Test
    fun joinsAsSoonAsTheSocketOpensAndPingsInABurst() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        assertEquals("wss://ritme.test/ritme", sockets.last.url)
        assertTrue(sockets.last.sent.isEmpty())
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", null), sockets.last.sentMessages().first())
        timers.advanceBy(800)
        assertEquals(5, sockets.last.sentMessages().count { it is ClientMessage.Ping })
    }

    @Test
    fun theWelcomeAndEveryStateAreAcknowledgedWithTheirOwnVersion() {
        joined()
        sockets.last.receive(ServerMessage.State(room(version = 5)))
        sockets.last.receive(ServerMessage.State(room(version = 4))) // stale, still acknowledged
        assertEquals(listOf(3L, 5L, 4L), sent().filterIsInstance<ClientMessage.Ack>().map { it.version })
        assertTrue(events.contains(ConnectionEvent.Status(ConnectionStatus.ONLINE)))
    }

    @Test
    fun theAckIsSentEvenWhenTheListenerThrows() {
        val c = connection { if (it is ConnectionEvent.State) throw IllegalStateException("boom") }
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
        assertThrows(IllegalStateException::class.java) {
            sockets.last.receive(ServerMessage.State(room(version = 7)))
        }
        assertTrue(sent().contains(ClientMessage.Ack(7)))
    }

    @Test
    fun aDropAfterTheWelcomeReconnectsWithTheResumeToken() {
        joined()
        sockets.last.drop()
        assertEquals(ConnectionEvent.Status(ConnectionStatus.RECONNECTING), events.last())
        timers.advanceBy(999)
        assertEquals(1, sockets.sockets.size)
        timers.advanceBy(1)
        assertEquals(2, sockets.sockets.size)
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", "tok"), sockets.last.sentMessages().first())
    }

    @Test
    fun reconnectBacksOffAndGivesUpThirtySecondsAfterTheDrop() {
        joined()
        sockets.last.drop()
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)) {
            val before = sockets.sockets.size
            timers.advanceBy(delay - 1)
            assertEquals(before, sockets.sockets.size)
            timers.advanceBy(1)
            assertEquals(before + 1, sockets.sockets.size)
            assertNull(ended())
            sockets.last.drop()
        }
        assertEquals(EndReason.Lost, ended())
    }

    @Test
    fun aDropBeforeTheFirstWelcomeMeansUnreachable() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.drop()
        assertEquals(EndReason.Unreachable, ended())
    }

    @Test
    fun aSocketThatNeverOpensTimesOut() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        timers.advanceBy(8_000)
        assertEquals(EndReason.Unreachable, ended())
        assertTrue(sockets.last.closed)
    }

    @Test
    fun anOpenSocketThatIsNeverWelcomedTimesOut() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        timers.advanceBy(9_999)
        assertNull(ended())
        timers.advanceBy(1)
        assertEquals(EndReason.Unreachable, ended())
    }

    @Test
    fun anErrorBeforeTheWelcomeRejects() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND))
        assertEquals(EndReason.Rejected(ErrorCode.ROOM_NOT_FOUND), ended())
    }

    @Test
    fun rateLimitedWhileResumingIsLeftToTheBackoff() {
        joined()
        sockets.last.drop()
        timers.advanceBy(1_000)
        sockets.last.open()
        sockets.last.receive(ServerMessage.Error(code = ErrorCode.RATE_LIMITED))
        assertNull(ended())
        sockets.last.drop()
        timers.advanceBy(2_000)
        assertEquals(3, sockets.sockets.size)
    }

    @Test
    fun kickedAndRoomClosedEndTheSession() {
        joined()
        sockets.last.receive(ServerMessage.Kicked)
        assertEquals(EndReason.Kicked, ended())
        assertTrue(sockets.last.closed)

        events.clear()
        joined()
        sockets.last.receive(ServerMessage.RoomClosed)
        assertEquals(EndReason.Closed, ended())
    }

    @Test
    fun commandsAndTrackEndsNeedAJoinedSocket() {
        val c = connection()
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        assertNull(c.command(Command.Play))
        assertFalse(c.trackEnded("e1"))
        sockets.last.open()
        sockets.last.receive(welcome(room()))
        assertEquals("r1", c.command(Command.Play))
        assertEquals("r2", c.command(Command.Next))
        assertTrue(c.trackEnded("e1"))
        assertEquals(
            listOf(ClientMessage.Send("r1", Command.Play), ClientMessage.Send("r2", Command.Next), ClientMessage.TrackEnded("e1")),
            sent().filter { it is ClientMessage.Send || it is ClientMessage.TrackEnded },
        )
    }

    @Test
    fun leaveSendsLeaveThenCloses() {
        val c = joined()
        c.leave()
        assertEquals(ClientMessage.Leave, sockets.last.sentMessages().last())
        assertTrue(sockets.last.closed)
        assertEquals(EndReason.Left, ended())
    }

    @Test
    fun createTurnsIntoJoinAfterTheWelcome() {
        val newEntry = NewEntry(videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist", durationMs = 200_000)
        connection().start(JoinIntent.Create("Budi", listOf(newEntry), 0, 5_000))
        sockets.last.open()
        assertEquals(
            ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(newEntry), 0, 5_000),
            sockets.last.sentMessages().first(),
        )
        sockets.last.receive(welcome(room(code = "K7M2QX"), resumeToken = "tok2"))
        sockets.last.drop()
        timers.advanceBy(1_000)
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", "tok2"), sockets.last.sentMessages().first())
    }

    @Test
    fun unknownOrBrokenFramesAreSkipped() {
        joined()
        val before = events.size
        sockets.last.receiveRaw("""{"type":"FromTheFuture"}""")
        sockets.last.receiveRaw("not json")
        assertEquals(before, events.size)
        assertNull(ended())
    }

    @Test
    fun pongsCarryTheirArrivalTime() {
        joined()
        timers.advanceBy(150)
        sockets.last.receive(ServerMessage.Pong(clientTime = 0, serverTime = 5_000))
        assertEquals(ConnectionEvent.Pong(0, 5_000, 150), events.last())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.net.RitmeConnectionTest"`
Expected: FAIL — `Unresolved reference 'RitmeConnection'`.

- [ ] **Step 4: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/net/RitmeConnection.kt`:

```kotlin
package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessageOrNull
import com.example.musicfy.ritme.protocol.encode

/**
 * One Ritme session over WebSocket, mirroring ritme-web/src/net/connection.ts: Create/Join the
 * moment each socket opens (Join with the resume token after the first Welcome), Ack every
 * Welcome/State with its exact version, clock pings, reconnect with backoff, and exactly one
 * Ended event. Unknown or malformed frames are skipped. Single-threaded: every call and callback
 * runs on the timers' thread.
 */
class RitmeConnection(
    private val url: String,
    private val socketFactory: RitmeSocketFactory,
    private val timers: RitmeTimers,
    private val onEvent: (ConnectionEvent) -> Unit,
) : ConnectionLike {
    private var socket: RitmeSocket? = null
    private var intent: JoinIntent? = null
    private var welcomed = false
    private var joined = false
    private var ended = false
    private var attempt = 0
    private var droppedAt: Long? = null
    private var resumeCode: String? = null
    private var resumeToken: String? = null
    private var nextReqId = 1
    private val socketTimers = mutableListOf<RitmeTimer>()
    private var reconnectTimer: RitmeTimer? = null

    override fun start(intent: JoinIntent) {
        check(this.intent == null) { "RitmeConnection.start called twice" }
        this.intent = intent
        emit(ConnectionEvent.Status(ConnectionStatus.CONNECTING))
        open()
    }

    override fun command(cmd: Command): String? {
        if (!joined) return null
        val reqId = "r${nextReqId++}"
        send(ClientMessage.Send(reqId, cmd))
        return reqId
    }

    override fun trackEnded(entryId: String): Boolean {
        if (!joined) return false
        return send(ClientMessage.TrackEnded(entryId))
    }

    override fun leave() {
        if (ended) return
        // Before the first Welcome there is no member to remove: just close.
        if (joined) send(ClientMessage.Leave)
        finish(EndReason.Left)
    }

    private fun open() {
        joined = false
        var opened = false
        lateinit var current: RitmeSocket
        val listener = object : RitmeSocketListener {
            override fun onOpen() {
                if (socket !== current) return
                opened = true
                send(joinMessage())
                later(JOIN_TIMEOUT_MS) { if (!joined) drop(current) }
                for (i in 0 until PING_BURST) later(i * PING_BURST_GAP_MS) { ping() }
                later(PING_INTERVAL_MS) { pingLoop() }
            }

            override fun onMessage(text: String) {
                if (socket !== current) return
                decodeServerMessageOrNull(text)?.let(::handle)
            }

            override fun onClosed() = drop(current)
        }
        current = socketFactory.open(url, listener)
        socket = current
        later(CONNECT_TIMEOUT_MS) { if (!opened) drop(current) }
    }

    private fun joinMessage(): ClientMessage = when (val i = intent!!) {
        is JoinIntent.Create -> ClientMessage.Create(PROTOCOL_VERSION, i.name, i.initialQueue, i.startIndex, i.startPositionMs)
        is JoinIntent.Join -> ClientMessage.Join(PROTOCOL_VERSION, i.code, i.name, resumeToken.takeIf { resumeCode == i.code })
    }

    private fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                joined = true
                welcomed = true
                attempt = 0
                droppedAt = null
                // Every reconnect joins the room this Welcome is for, with its token.
                intent = JoinIntent.Join(message.state.code, intent!!.name)
                resumeCode = message.state.code
                resumeToken = message.resumeToken
                // finally: a throwing listener must not skip the Ack, or the server withholds every newer snapshot.
                try {
                    emit(ConnectionEvent.Welcome(message, timers.now()))
                    emit(ConnectionEvent.Status(ConnectionStatus.ONLINE))
                } finally {
                    ack(message.state.version)
                }
            }
            is ServerMessage.State -> if (joined) {
                // Acked even when the reducer drops it as stale: the server gates on the exact version it sent.
                try {
                    emit(ConnectionEvent.State(message.state))
                } finally {
                    ack(message.state.version)
                }
            }
            is ServerMessage.Pong -> emit(ConnectionEvent.Pong(message.clientTime, message.serverTime, timers.now()))
            is ServerMessage.Error -> when {
                joined -> emit(ConnectionEvent.Failure(message.reqId, message.code))
                // A reconnect hit a connection cap: the server closes, and the backoff tries again.
                welcomed && message.code == ErrorCode.RATE_LIMITED -> Unit
                else -> finish(if (welcomed) EndReason.Lost else EndReason.Rejected(message.code))
            }
            ServerMessage.Kicked -> finish(EndReason.Kicked)
            ServerMessage.RoomClosed -> finish(EndReason.Closed)
        }
    }

    private fun drop(dropped: RitmeSocket) {
        if (socket !== dropped) return
        detach(dropped)
        if (ended) return
        if (!welcomed) {
            finish(EndReason.Unreachable)
            return
        }
        val now = timers.now()
        val since = droppedAt ?: now
        if (droppedAt == null) {
            droppedAt = now
            emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        }
        // Give up 30 s after the first drop: delays are clamped so a last attempt lands at the 30 s mark.
        val remaining = since + RECONNECT_GIVE_UP_MS - now
        if (remaining <= 0) {
            finish(EndReason.Lost)
            return
        }
        val delay = minOf(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)], remaining)
        attempt++
        reconnectTimer = timers.schedule(delay) {
            reconnectTimer = null
            if (!ended) open()
        }
    }

    private fun finish(reason: EndReason) {
        if (ended) return
        ended = true
        resumeCode = null
        resumeToken = null
        reconnectTimer?.cancel()
        reconnectTimer = null
        socket?.let(::detach)
        emit(ConnectionEvent.Ended(reason))
    }

    /** Forgets a socket: its timers cancelled, its callbacks ignored from now on, closed. */
    private fun detach(s: RitmeSocket) {
        socketTimers.forEach { it.cancel() }
        socketTimers.clear()
        if (socket === s) socket = null
        joined = false
        s.close()
    }

    private fun ack(version: Long) {
        if (!ended) send(ClientMessage.Ack(version))
    }

    private fun ping() {
        send(ClientMessage.Ping(timers.now()))
    }

    private fun pingLoop() {
        ping()
        later(PING_INTERVAL_MS) { pingLoop() }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        lateinit var timer: RitmeTimer
        timer = timers.schedule(delayMs) {
            socketTimers.remove(timer)
            block()
        }
        socketTimers += timer
    }

    private fun send(message: ClientMessage): Boolean = socket?.send(message.encode()) ?: false

    private fun emit(event: ConnectionEvent) = onEvent(event)

    companion object {
        /** Reconnect delays (spec 4.1). */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        const val RECONNECT_GIVE_UP_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** The server closes a socket that has not joined within 10 s. */
        const val JOIN_TIMEOUT_MS = 10_000L
        const val PING_BURST = 5
        const val PING_BURST_GAP_MS = 200L
        const val PING_INTERVAL_MS = 30_000L
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.net.RitmeConnectionTest"`
Expected: PASS (16 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/net app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt app/src/test/kotlin/com/example/musicfy/ritme/net
git commit -m "feat(ritme-android): connection state machine with Ack flow control and reconnect" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: Entry mapping and batching

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt`

**Interfaces:**
- Consumes: `com.example.musicfy.models.MediaMetadata` (fields `id`, `title`, `artists: List<Artist(id, name)>`, `duration: Int` seconds, `thumbnailUrl`, `localUri`); protocol `NewEntry`, `QueueEntry`, `MediaKind`.
- Produces:
  - `data class InitialQueue(val first: List<NewEntry>, val startIndex: Int, val startPositionMs: Long, val overflow: List<List<NewEntry>>, val skipped: Int) { val total: Int; companion object { val EMPTY } }`
  - `data class AddBatches(val batches: List<List<NewEntry>>, val count: Int, val skipped: Int)`
  - `object RitmeEntries { const val MAX_BATCH = 200; const val MAX_QUEUE = 500; const val MAX_TEXT = 300; const val MAX_DURATION_MS = 86_400_000L; fun safeThumbnail(url: String?): String?; fun toNewEntry(song: MediaMetadata?): NewEntry?; fun buildInitialQueue(songs: List<MediaMetadata?>, currentIndex: Int, positionMs: Long): InitialQueue; fun toAddBatches(songs: List<MediaMetadata?>): AddBatches; fun isPlayable(entry: QueueEntry): Boolean; fun toMediaMetadata(entry: QueueEntry): MediaMetadata }`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt`:

```kotlin
package com.example.musicfy.ritme.entries

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.testing.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeEntriesTest {

    private fun song(
        id: String = "dQw4w9WgXcQ",
        title: String = "Never Gonna",
        artists: List<String> = listOf("Rick", "Astley"),
        duration: Int = 213,
        thumb: String? = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
        localUri: String? = null,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = duration,
        thumbnailUrl = thumb,
        localUri = localUri,
    )

    @Test
    fun aSongBecomesANewEntry() {
        assertEquals(
            NewEntry(
                videoId = "dQw4w9WgXcQ",
                title = "Never Gonna",
                artist = "Rick, Astley",
                thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
                durationMs = 213_000,
            ),
            RitmeEntries.toNewEntry(song()),
        )
    }

    @Test
    fun localAndMalformedSongsAreNotShared() {
        assertNull(RitmeEntries.toNewEntry(song(localUri = "content://media/1")))
        assertNull(RitmeEntries.toNewEntry(song(id = "local:42")))
        assertNull(RitmeEntries.toNewEntry(song(title = "   ")))
        assertNull(RitmeEntries.toNewEntry(null))
    }

    @Test
    fun onlyYouTubeThumbnailsAreKept() {
        assertEquals("https://lh3.googleusercontent.com/a=w120", RitmeEntries.safeThumbnail("https://lh3.googleusercontent.com/a=w120"))
        assertEquals("https://yt3.ggpht.com/x", RitmeEntries.safeThumbnail("https://yt3.ggpht.com/x"))
        assertNull(RitmeEntries.safeThumbnail("http://i.ytimg.com/vi/x/hq.jpg"))
        assertNull(RitmeEntries.safeThumbnail("https://example.com/x.jpg"))
        assertNull(RitmeEntries.safeThumbnail("https://i.ytimg.com/" + "a".repeat(600)))
        assertNull(RitmeEntries.toNewEntry(song(thumb = "https://example.com/x.jpg"))?.thumbnailUrl)
    }

    @Test
    fun textAndDurationAreBounded() {
        val e = RitmeEntries.toNewEntry(song(title = "t".repeat(400), duration = -1))!!
        assertEquals(300, e.title.length)
        assertEquals(0L, e.durationMs)
    }

    @Test
    fun theInitialQueueStartsAtTheCurrentSongAndKeepsItsPosition() {
        val q = RitmeEntries.buildInitialQueue(listOf(song("aaaa"), song("bbbb"), song("cccc")), currentIndex = 1, positionMs = 42_000)
        assertEquals(listOf("bbbb", "cccc"), q.first.map { it.videoId })
        assertEquals(0, q.startIndex)
        assertEquals(42_000L, q.startPositionMs)
        assertEquals(0, q.skipped)
    }

    @Test
    fun aLocalCurrentSongStartsTheRoomFromZero() {
        val q = RitmeEntries.buildInitialQueue(listOf(song(localUri = "content://1"), song("bbbb")), currentIndex = 0, positionMs = 42_000)
        assertEquals(listOf("bbbb"), q.first.map { it.videoId })
        assertEquals(0L, q.startPositionMs)
        assertEquals(1, q.skipped)
    }

    @Test
    fun bigQueuesAreBatchedAndCappedAtFiveHundred() {
        val q = RitmeEntries.buildInitialQueue((1..600).map { song("s$it") }, currentIndex = 0, positionMs = 0)
        assertEquals(200, q.first.size)
        assertEquals(listOf(200, 100), q.overflow.map { it.size })
        assertEquals(500, q.total)
        assertEquals(InitialQueue.EMPTY, RitmeEntries.buildInitialQueue(emptyList(), 0, 0))
    }

    @Test
    fun addBatchesSkipLocalSongs() {
        val b = RitmeEntries.toAddBatches((1..250).map { song("s$it") } + song(localUri = "content://1"))
        assertEquals(listOf(200, 50), b.batches.map { it.size })
        assertEquals(250, b.count)
        assertEquals(1, b.skipped)
    }

    @Test
    fun onlyYouTubeKindsArePlayable() {
        assertTrue(RitmeEntries.isPlayable(entry("a")))
        assertTrue(RitmeEntries.isPlayable(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertFalse(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "")))
        assertFalse(RitmeEntries.isPlayable(entry("x", kind = MediaKind.UNKNOWN)))
    }

    @Test
    fun aRoomEntryBecomesPlayerMetadata() {
        val m = RitmeEntries.toMediaMetadata(entry("a", durationMs = 213_500))
        assertEquals("vida", m.id)
        assertEquals("Song a", m.title)
        assertEquals(listOf("Artist a"), m.artists.map { it.name })
        assertEquals(213, m.duration)
        assertNull(m.localUri)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.entries.RitmeEntriesTest"`
Expected: FAIL — `Unresolved reference 'RitmeEntries'`.

- [ ] **Step 3: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt`:

```kotlin
package com.example.musicfy.ritme.entries

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.QueueEntry
import java.net.URI

/** What Create sends: [first] in the Create itself, [overflow] as Add batches after the Welcome. */
data class InitialQueue(
    val first: List<NewEntry>,
    val startIndex: Int,
    val startPositionMs: Long,
    val overflow: List<List<NewEntry>>,
    val skipped: Int,
) {
    val total: Int get() = first.size + overflow.sumOf { it.size }

    companion object {
        val EMPTY = InitialQueue(emptyList(), 0, 0, emptyList(), 0)
    }
}

data class AddBatches(val batches: List<List<NewEntry>>, val count: Int, val skipped: Int)

/**
 * Converts between the app's songs and room entries. Applies the server's NewEntry rules
 * (ritme-server RoomLogic.isValid) up front, so an invalid song is skipped here instead of
 * failing a whole Add with INVALID or shifting Create's startIndex.
 */
object RitmeEntries {
    const val MAX_BATCH = 200
    const val MAX_QUEUE = 500
    const val MAX_TEXT = 300
    const val MAX_DURATION_MS = 86_400_000L
    private const val MAX_THUMBNAIL_LENGTH = 512

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val THUMBNAIL_HOST_SUFFIXES = listOf(".ytimg.com", ".googleusercontent.com", ".ggpht.com")

    /** The thumbnail if the server accepts it for a YouTube entry, else null. */
    fun safeThumbnail(url: String?): String? {
        if (url == null || url.length > MAX_THUMBNAIL_LENGTH || !url.startsWith("https://")) return null
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
        val allowed = host == "i.ytimg.com" || THUMBNAIL_HOST_SUFFIXES.any { host.endsWith(it) }
        return url.takeIf { allowed }
    }

    /** A YOUTUBE_MUSIC entry for [song], or null for local files and anything the server would refuse. */
    fun toNewEntry(song: MediaMetadata?): NewEntry? {
        if (song == null || song.localUri != null || !VIDEO_ID.matches(song.id)) return null
        val title = song.title.trim().take(MAX_TEXT)
        if (title.isBlank()) return null
        val artist = song.artists.joinToString(", ") { it.name }.trim().take(MAX_TEXT)
        return NewEntry(
            videoId = song.id,
            title = title,
            artist = artist,
            thumbnailUrl = safeThumbnail(song.thumbnailUrl),
            durationMs = (song.duration.toLong() * 1000).coerceIn(0, MAX_DURATION_MS),
            kind = MediaKind.YOUTUBE_MUSIC,
        )
    }

    /**
     * The room's starting queue: the current song and everything after it in play order (history
     * stays behind), shareable songs only, at most [MAX_QUEUE]. The position carries over only when
     * the current song itself is shared.
     */
    fun buildInitialQueue(songs: List<MediaMetadata?>, currentIndex: Int, positionMs: Long): InitialQueue {
        if (songs.isEmpty()) return InitialQueue.EMPTY
        val considered = songs.drop(currentIndex.coerceIn(0, songs.size - 1))
        val mapped = considered.map(::toNewEntry)
        val entries = mapped.filterNotNull().take(MAX_QUEUE)
        return InitialQueue(
            first = entries.take(MAX_BATCH),
            startIndex = 0,
            startPositionMs = if (mapped.first() != null) positionMs.coerceAtLeast(0) else 0,
            overflow = entries.drop(MAX_BATCH).chunked(MAX_BATCH),
            skipped = mapped.count { it == null },
        )
    }

    fun toAddBatches(songs: List<MediaMetadata?>): AddBatches {
        val mapped = songs.map(::toNewEntry)
        val entries = mapped.filterNotNull().take(MAX_QUEUE)
        return AddBatches(entries.chunked(MAX_BATCH), entries.size, mapped.count { it == null })
    }

    /** Android plays YouTube kinds by videoId (YOUTUBE_VIDEO audio-only); URL and UNKNOWN are not supported yet. */
    fun isPlayable(entry: QueueEntry): Boolean =
        (entry.kind == MediaKind.YOUTUBE_MUSIC || entry.kind == MediaKind.YOUTUBE_VIDEO) && VIDEO_ID.matches(entry.videoId)

    fun toMediaMetadata(entry: QueueEntry): MediaMetadata = MediaMetadata(
        id = entry.videoId,
        title = entry.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = entry.artist)),
        duration = (entry.durationMs / 1000).toInt(),
        thumbnailUrl = entry.thumbnailUrl,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.entries.RitmeEntriesTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/entries app/src/test/kotlin/com/example/musicfy/ritme/entries
git commit -m "feat(ritme-android): map songs to room entries with server validation rules" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: RitmeSession, Android socket/timers and Hilt module

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeSession.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/net/AndroidRitmeIo.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt`
- Modify: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt` (append)
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeSessionTest.kt`

**Interfaces:**
- Consumes: Task 1 `RitmeLinks.socketUrl`; Task 2 `ClockSync`; Task 3 types + `RitmeReducer`; Task 4 `RitmeConnection`, `RitmeSocketFactory`, `RitmeTimers`; Task 5 `InitialQueue`.
- Produces:
  - `fun interface RitmeClock { fun now(): Long }`
  - `@Singleton class RitmeSession @Inject constructor(connector: RitmeConnector, clockSource: RitmeClock)` with `val state: StateFlow<RitmeUiState>`, `val clock: ClockSync`, `fun serverNow(): Long`, `fun create(serverUrl: String, name: String, initial: InitialQueue)`, `fun join(serverUrl: String, code: String, name: String)`, `fun leave()`, `fun dismiss()`, and Boolean-returning commands `play()`, `pause()`, `seek(positionMs: Long)`, `next()`, `previous()`, `playEntry(entryId: String)`, `add(entries: List<NewEntry>, playNext: Boolean)`, `remove(entryId: String)`, `move(entryId: String, afterEntryId: String?)`, `setGuestsCanControl(value: Boolean)`, `kick(memberId: String)`, `transferHost(memberId: String)`, `endRoom()`, `trackEnded(entryId: String)`. A command returns `false` (and sends nothing) unless `phase == IN_ROOM && connection == ONLINE`.
  - `class HandlerRitmeTimers : RitmeTimers`, `class OkHttpRitmeSocketFactory(client: OkHttpClient, timers: RitmeTimers) : RitmeSocketFactory`, `class DefaultRitmeConnector(sockets: RitmeSocketFactory, timers: RitmeTimers) : RitmeConnector`
  - `RitmeModule` provides `RitmeTimers`, `RitmeClock`, `RitmeConnector`.
- Test fakes: `FakeConnection` (`url`, `intent`, `commands`, `trackEnds`, `left`, `welcome(room, memberId)`, `emit(event)`), `FakeConnector` (`connections`, `last`).

- [ ] **Step 1: Append the connection fakes**

Append to `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt` (and add the imports to the top of the file):

```kotlin
// imports to add at the top:
// import com.example.musicfy.ritme.net.ConnectionEvent
// import com.example.musicfy.ritme.net.ConnectionLike
// import com.example.musicfy.ritme.net.ConnectionStatus
// import com.example.musicfy.ritme.net.EndReason
// import com.example.musicfy.ritme.net.JoinIntent
// import com.example.musicfy.ritme.net.RitmeConnector
// import com.example.musicfy.ritme.protocol.Command
// import com.example.musicfy.ritme.protocol.RoomState

/** Records what RitmeSession asks for; the test drives events with [emit]/[welcome]. */
class FakeConnection(val url: String, private val onEvent: (ConnectionEvent) -> Unit) : ConnectionLike {
    var intent: JoinIntent? = null
    val commands = mutableListOf<Command>()
    val trackEnds = mutableListOf<String>()
    var left = false

    override fun start(intent: JoinIntent) {
        this.intent = intent
        onEvent(ConnectionEvent.Status(ConnectionStatus.CONNECTING))
    }

    override fun command(cmd: Command): String? {
        commands += cmd
        return "r${commands.size}"
    }

    override fun trackEnded(entryId: String): Boolean {
        trackEnds += entryId
        return true
    }

    override fun leave() {
        left = true
        onEvent(ConnectionEvent.Ended(EndReason.Left))
    }

    fun welcome(state: RoomState, memberId: String = "m1", serverTime: Long = 0, receivedAt: Long = 0) {
        // Fully qualified: the builder, not this member function.
        onEvent(ConnectionEvent.Welcome(com.example.musicfy.ritme.testing.welcome(state, memberId, serverTime = serverTime), receivedAt))
        onEvent(ConnectionEvent.Status(ConnectionStatus.ONLINE))
    }

    fun emit(event: ConnectionEvent) = onEvent(event)
}

class FakeConnector : RitmeConnector {
    val connections = mutableListOf<FakeConnection>()
    val last: FakeConnection get() = connections.last()

    override fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike =
        FakeConnection(url, onEvent).also { connections += it }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeSessionTest.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeSessionTest {

    private var now = 0L
    private val connector = FakeConnector()
    private val session = RitmeSession(connector) { now }

    private fun joinedRoom(state: RoomState = room(version = 5), memberId: String = "m1") {
        session.join("https://ritme.dubeli.id", state.code, "Budi")
        connector.last.welcome(state, memberId)
    }

    private fun newEntry(id: String) = NewEntry(videoId = id, title = "Song $id", artist = "A", durationMs = 1_000)

    @Test
    fun joinOpensTheRoomSocket() {
        session.join("https://ritme.dubeli.id/", "K7M2QX", "Budi")
        assertEquals("wss://ritme.dubeli.id/ritme", connector.last.url)
        assertEquals(JoinIntent.Join("K7M2QX", "Budi"), connector.last.intent)
        assertEquals(RitmePhase.CONNECTING, session.state.value.phase)
    }

    @Test
    fun theWelcomeEntersTheRoom() {
        joinedRoom()
        assertTrue(session.state.value.inRoom)
        assertEquals(ConnectionStatus.ONLINE, session.state.value.connection)
    }

    @Test
    fun staleSnapshotsAreIgnored() {
        joinedRoom(room(version = 5))
        connector.last.emit(ConnectionEvent.State(room(version = 4, currentEntryId = "x")))
        assertEquals(5L, session.state.value.room?.version)
    }

    @Test
    fun commandsAreOnlySentWhileOnlineInARoom() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        assertFalse(session.play())
        connector.last.welcome(room())
        assertTrue(session.play())
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(session.next())
        assertEquals(listOf<Command>(Command.Play), connector.last.commands)
    }

    @Test
    fun overflowEntriesFollowTheWelcomeAsAddBatches() {
        val a = newEntry("aaaa")
        val b = newEntry("bbbb")
        val c = newEntry("cccc")
        session.create(
            "https://ritme.dubeli.id",
            "Budi",
            InitialQueue(first = listOf(a), startIndex = 0, startPositionMs = 0, overflow = listOf(listOf(b), listOf(c)), skipped = 0),
        )
        assertEquals(JoinIntent.Create("Budi", listOf(a), 0, 0), connector.last.intent)
        assertTrue(connector.last.commands.isEmpty())
        connector.last.welcome(room())
        assertEquals(listOf(Command.Add(listOf(b)), Command.Add(listOf(c))), connector.last.commands)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.ONLINE))
        assertEquals(2, connector.last.commands.size) // sent once only
    }

    @Test
    fun kickedEndsTheSession() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Ended(EndReason.Kicked))
        with(session.state.value) {
            assertEquals(RitmePhase.ENDED, phase)
            assertNull(room)
            assertEquals(EndReason.Kicked, ended)
        }
    }

    @Test
    fun dismissOnlyResetsAnEndedSession() {
        joinedRoom()
        session.dismiss()
        assertEquals(RitmePhase.IN_ROOM, session.state.value.phase)
        session.leave()
        assertTrue(connector.last.left)
        assertEquals(RitmePhase.ENDED, session.state.value.phase)
        session.dismiss()
        assertEquals(RitmePhase.IDLE, session.state.value.phase)
    }

    @Test
    fun pongsMoveTheServerClock() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Pong(clientTime = 900, serverTime = 5_000, receivedAt = 1_100))
        now = 2_000
        assertEquals(6_000L, session.serverNow())
    }

    @Test
    fun aSecondJoinWhileConnectingIsIgnored() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        session.join("https://ritme.dubeli.id", "ABCDEF", "Budi")
        assertEquals(1, connector.connections.size)
    }

    @Test
    fun eventsFromAReplacedConnectionAreIgnored() {
        joinedRoom()
        val old = connector.last
        old.emit(ConnectionEvent.Ended(EndReason.Lost))
        session.dismiss()
        joinedRoom(room(code = "ABCDEF", version = 1))
        old.emit(ConnectionEvent.State(room(code = "ABCDEF", version = 9)))
        assertEquals(1L, session.state.value.room?.version)
    }

    @Test
    fun rejectedCommandsBecomeNotices() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Failure("r1", ErrorCode.QUEUE_FULL))
        assertEquals(NoticeKey.QUEUE_FULL, session.state.value.notice?.key)
    }

    @Test
    fun trackEndedNeedsAnOnlineRoom() {
        joinedRoom()
        assertTrue(session.trackEnded("e1"))
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(session.trackEnded("e2"))
        assertEquals(listOf("e1"), connector.last.trackEnds)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.session.RitmeSessionTest"`
Expected: FAIL — `Unresolved reference 'RitmeSession'`.

- [ ] **Step 4: Write the session**

`app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeSession.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionLike
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.net.RitmeConnector
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.sync.ClockSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Monotonic local clock shared with the connection (SystemClock.elapsedRealtime in the app). */
fun interface RitmeClock {
    fun now(): Long
}

/**
 * The one owner of the room connection, the server clock and the session state. Main thread only.
 * Commands are dropped (returning false) unless the session is in a room and online; nothing is
 * applied optimistically - the next server snapshot is what changes [state].
 */
@Singleton
class RitmeSession @Inject constructor(
    private val connector: RitmeConnector,
    private val clockSource: RitmeClock,
) {
    private val _state = MutableStateFlow(RitmeUiState())
    val state: StateFlow<RitmeUiState> = _state.asStateFlow()

    val clock = ClockSync()

    private var conn: ConnectionLike? = null
    private var pendingOverflow: List<List<NewEntry>> = emptyList()

    fun serverNow(): Long = clock.serverNow(clockSource.now())

    fun create(serverUrl: String, name: String, initial: InitialQueue) {
        val c = begin(serverUrl) ?: return
        pendingOverflow = initial.overflow
        c.start(JoinIntent.Create(name, initial.first, initial.startIndex, initial.startPositionMs))
    }

    fun join(serverUrl: String, code: String, name: String) {
        val c = begin(serverUrl) ?: return
        pendingOverflow = emptyList()
        c.start(JoinIntent.Join(code, name))
    }

    fun leave() {
        conn?.leave()
    }

    /** Back to IDLE after the session ended (the entry sheet calls it before retrying or closing). */
    fun dismiss() {
        if (_state.value.phase == RitmePhase.ENDED) _state.value = RitmeReducer.reset(_state.value)
    }

    fun play(): Boolean = send(Command.Play)
    fun pause(): Boolean = send(Command.Pause)
    fun seek(positionMs: Long): Boolean = send(Command.Seek(positionMs.coerceAtLeast(0)))
    fun next(): Boolean = send(Command.Next)
    fun previous(): Boolean = send(Command.Previous)
    fun playEntry(entryId: String): Boolean = send(Command.PlayEntry(entryId))
    fun add(entries: List<NewEntry>, playNext: Boolean): Boolean = send(Command.Add(entries, playNext))
    fun remove(entryId: String): Boolean = send(Command.Remove(entryId))
    fun move(entryId: String, afterEntryId: String?): Boolean = send(Command.Move(entryId, afterEntryId))
    fun setGuestsCanControl(value: Boolean): Boolean = send(Command.SetSettings(RoomSettings(guestsCanControl = value)))
    fun kick(memberId: String): Boolean = send(Command.Kick(memberId))
    fun transferHost(memberId: String): Boolean = send(Command.TransferHost(memberId))
    fun endRoom(): Boolean = send(Command.EndRoom)

    fun trackEnded(entryId: String): Boolean = canSend() && conn?.trackEnded(entryId) == true

    private fun begin(serverUrl: String): ConnectionLike? {
        val phase = _state.value.phase
        if (phase == RitmePhase.CONNECTING || phase == RitmePhase.IN_ROOM) return null
        clock.reset()
        _state.value = RitmeReducer.start(_state.value)
        lateinit var c: ConnectionLike
        c = connector.connect(RitmeLinks.socketUrl(serverUrl)) { event -> if (conn === c) onEvent(event) }
        conn = c
        return c
    }

    private fun canSend(): Boolean =
        _state.value.phase == RitmePhase.IN_ROOM && _state.value.connection == ConnectionStatus.ONLINE

    private fun send(cmd: Command): Boolean = canSend() && conn?.command(cmd) != null

    private fun onEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Pong -> clock.addSample(event.clientTime, event.receivedAt, event.serverTime)
            is ConnectionEvent.Welcome -> clock.seed(event.welcome.serverTime, event.receivedAt)
            is ConnectionEvent.Ended -> {
                conn = null
                pendingOverflow = emptyList()
            }
            else -> Unit
        }
        _state.value = RitmeReducer.reduce(_state.value, event)
        // The rest of a big starting queue goes out once the room exists (Create holds at most 200).
        if (event is ConnectionEvent.Status && event.status == ConnectionStatus.ONLINE && pendingOverflow.isNotEmpty()) {
            val batches = pendingOverflow
            pendingOverflow = emptyList()
            batches.forEach { add(it, playNext = false) }
        }
    }
}
```

- [ ] **Step 5: Write the Android socket, timers and Hilt module**

`app/src/main/kotlin/com/example/musicfy/ritme/net/AndroidRitmeIo.kt`:

```kotlin
package com.example.musicfy.ritme.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread timers on the monotonic elapsedRealtime clock (spec 2.4). */
class HandlerRitmeTimers(private val handler: Handler = Handler(Looper.getMainLooper())) : RitmeTimers {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer {
        val runnable = Runnable { block() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return RitmeTimer { handler.removeCallbacks(runnable) }
    }
}

/**
 * OkHttp WebSocket, with every callback re-posted to the timers' (main) thread in arrival order.
 * OkHttp has no inbound frame cap, so ~260 KB snapshots are fine; close() sends queued frames
 * (e.g. Leave) before the close frame.
 */
class OkHttpRitmeSocketFactory(
    private val client: OkHttpClient,
    private val timers: RitmeTimers,
) : RitmeSocketFactory {
    override fun open(url: String, listener: RitmeSocketListener): RitmeSocket {
        val closedOnce = AtomicBoolean(false)
        fun closed() {
            if (closedOnce.compareAndSet(false, true)) timers.schedule(0) { listener.onClosed() }
        }
        val webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    timers.schedule(0) { listener.onOpen() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    timers.schedule(0) { listener.onMessage(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed()
            },
        )
        return object : RitmeSocket {
            override fun send(text: String): Boolean = webSocket.send(text)
            override fun close() {
                webSocket.close(1000, "bye")
            }
        }
    }
}

class DefaultRitmeConnector(
    private val sockets: RitmeSocketFactory,
    private val timers: RitmeTimers,
) : RitmeConnector {
    override fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike =
        RitmeConnection(url, sockets, timers, onEvent)
}
```

`app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt`:

```kotlin
package com.example.musicfy.di

import com.example.musicfy.ritme.net.DefaultRitmeConnector
import com.example.musicfy.ritme.net.HandlerRitmeTimers
import com.example.musicfy.ritme.net.OkHttpRitmeSocketFactory
import com.example.musicfy.ritme.net.RitmeConnector
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.session.RitmeClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RitmeModule {

    @Provides
    @Singleton
    fun provideRitmeTimers(): RitmeTimers = HandlerRitmeTimers()

    @Provides
    @Singleton
    fun provideRitmeClock(timers: RitmeTimers): RitmeClock = RitmeClock { timers.now() }

    @Provides
    @Singleton
    fun provideRitmeConnector(timers: RitmeTimers): RitmeConnector {
        val client = OkHttpClient.Builder()
            // A dead link (airplane mode) fails within ~15 s, so the reconnect backoff starts in time.
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        return DefaultRitmeConnector(OkHttpRitmeSocketFactory(client, timers), timers)
    }
}
```

- [ ] **Step 6: Run tests and compile**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.*"`
Expected: PASS (all Ritme tests so far, including 12 in `RitmeSessionTest`).

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL (Hilt graph compiles; `RitmeSession` is not injected anywhere yet).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeSession.kt app/src/main/kotlin/com/example/musicfy/ritme/net/AndroidRitmeIo.kt app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeSessionTest.kt
git commit -m "feat(ritme-android): RitmeSession with OkHttp socket and Hilt bindings" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: Sync planner and Ritme-mode transitions

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitions.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlannerTest.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitionsTest.kt`

**Interfaces:**
- Consumes: Task 2 `DriftCorrector`/`DriftAction`; Task 3 `RitmeUiState`, `RitmePhase`, `EndReason`; Task 5 `RitmeEntries.isPlayable`; protocol `RoomState.currentEntry()`, `positionAt()`, `QueueEntry.isLive`.
- Produces:
  - `data class LocalPlayerView(val loadedEntryId: String?, val positionMs: Long, val isAdvancing: Boolean, val playWhenReady: Boolean, val ended: Boolean, val speed: Float)`
  - `sealed interface PlayerOp { data class Load(val entry: QueueEntry, val startMs: Long); data object Play; data object Pause; data class SeekTo(val positionMs: Long); data class SetSpeed(val speed: Float); data object Halt }`
  - `data class SyncInput(val room: RoomState?, val serverNow: Long, val local: LocalPlayerView, val lastAppliedKey: String?, val localNowMs: Long, val lastSeekAtMs: Long?, val force: Boolean = false)`
  - `data class SyncPlan(val ops: List<PlayerOp>, val wakeAtServerMs: Long?, val appliedKey: String?)`
  - `object RitmeSyncPlanner { const val PAUSED_ALIGN_MS = 40L; const val RESYNC_ON_CHANGE_MS = 250L; fun expectedPosition(room, entry, serverNow): Long; fun appliedKey(room, serverNow): String?; fun plan(input: SyncInput): SyncPlan }`
  - `sealed interface RitmeTransition { data object None; data object Enter; data class Exit(val reason: EndReason?) }`, `object RitmeModeTransitions { fun next(inRitme: Boolean, state: RitmeUiState): RitmeTransition }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlannerTest.kt`:

```kotlin
package com.example.musicfy.ritme.sync

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeSyncPlannerTest {

    private val e1 = entry("e1", durationMs = 200_000)

    private fun playingRoom(positionMs: Long = 0, anchor: Long = 10_000, version: Long = 1) = room(
        version = version,
        queue = listOf(e1, entry("e2")),
        currentEntryId = "e1",
        playback = Playback(isPlaying = true, positionMs = positionMs, anchorServerTimeMs = anchor),
    )

    private fun local(
        loaded: String? = "e1",
        pos: Long = 0,
        advancing: Boolean = false,
        pwr: Boolean = false,
        ended: Boolean = false,
        speed: Float = 1f,
    ) = LocalPlayerView(loaded, pos, advancing, pwr, ended, speed)

    private fun plan(
        room: RoomState?,
        now: Long,
        local: LocalPlayerView,
        lastKey: String? = null,
        force: Boolean = false,
        lastSeekAt: Long? = null,
    ) = RitmeSyncPlanner.plan(SyncInput(room, now, local, lastKey, localNowMs = now, lastSeekAtMs = lastSeekAt, force = force))

    private fun keyAt(room: RoomState, now: Long) = RitmeSyncPlanner.appliedKey(room, now)

    @Test
    fun aNewEntryIsLoadedPausedUntilTheAnchor() {
        val p = plan(playingRoom(), now = 9_000, local = local(loaded = null))
        assertEquals(listOf(PlayerOp.Load(e1, 0)), p.ops)
        assertEquals(10_000L, p.wakeAtServerMs)
    }

    @Test
    fun reachingTheAnchorStartsPlayback() {
        val room = playingRoom()
        val first = plan(room, 9_000, local(loaded = null))
        val p = plan(room, 10_020, local(pos = 0), lastKey = first.appliedKey)
        assertEquals(listOf(PlayerOp.Play), p.ops)
        assertNull(p.wakeAtServerMs)
    }

    @Test
    fun joiningMidSongLoadsAtTheRoomPositionAndPlays() {
        assertEquals(listOf(PlayerOp.Load(e1, 60_000), PlayerOp.Play), plan(playingRoom(), 70_000, local(loaded = null)).ops)
    }

    @Test
    fun runningAheadSlowsDown() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 10_300, advancing = true, pwr = true), lastKey = keyAt(room, 20_000))
        assertEquals(listOf(PlayerOp.SetSpeed(DriftCorrector.SLOWER)), p.ops)
    }

    @Test
    fun fallingFarBehindSeeks() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 8_500, advancing = true, pwr = true), lastKey = keyAt(room, 20_000))
        assertEquals(listOf(PlayerOp.SeekTo(10_000)), p.ops)
    }

    @Test
    fun aPausedRoomPausesAndAlignsThePlayer() {
        val room = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 42_000, 0))
        val p = plan(room, 50_000, local(pos = 40_000, pwr = true), lastKey = keyAt(room, 50_000))
        assertEquals(listOf(PlayerOp.Pause, PlayerOp.SeekTo(42_000)), p.ops)
    }

    @Test
    fun aSpeedCorrectionIsUndoneWhenTheRoomPauses() {
        val room = room(queue = listOf(e1), currentEntryId = "e1", playback = Playback(false, 42_000, 0))
        val p = plan(room, 50_000, local(pos = 42_000, speed = DriftCorrector.SLOWER), lastKey = keyAt(room, 50_000))
        assertEquals(listOf(PlayerOp.SetSpeed(DriftCorrector.NORMAL)), p.ops)
    }

    @Test
    fun unsupportedEntriesHaltThePlayer() {
        val url = entry("u", kind = MediaKind.URL, videoId = "")
        val room = room(queue = listOf(url), currentEntryId = "u", playback = Playback(true, 0, 0))
        assertEquals(listOf(PlayerOp.Halt), plan(room, 1_000, local(pwr = true)).ops)
        assertEquals(emptyList<PlayerOp>(), plan(room, 1_000, local(pwr = false)).ops)
        assertNull(plan(room, 1_000, local()).appliedKey)
    }

    @Test
    fun aLocallyHeldPlayerIsLeftAloneUntilTheRoomChanges() {
        val room = playingRoom()
        assertEquals(emptyList<PlayerOp>(), plan(room, 20_000, local(pos = 5_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
    }

    @Test
    fun aLocalPauseResyncsAsSoonAsTheRoomChanges() {
        val room = playingRoom()
        // paused on this device only: every tick leaves it alone, drift and all
        assertEquals(emptyList<PlayerOp>(), plan(room, 20_000, local(pos = 4_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
        // someone with control seeks: the device follows the room again
        val seeked = playingRoom(positionMs = 50_000, anchor = 21_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(50_000), PlayerOp.Play), plan(seeked, 21_000, local(pos = 4_000, pwr = false), lastKey = keyAt(room, 20_000)).ops)
    }

    @Test
    fun aRoomSeekResyncsAndAResumeJustPlays() {
        val seeked = playingRoom(positionMs = 30_000, anchor = 20_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(30_100)), plan(seeked, 20_100, local(pos = 12_000, advancing = true, pwr = true)).ops)
        val resumed = playingRoom(positionMs = 12_000, anchor = 20_000, version = 3)
        assertEquals(listOf(PlayerOp.Play), plan(resumed, 20_050, local(pos = 12_000, pwr = false)).ops)
    }

    @Test
    fun anEndedTrackWaitsForTheRoom() {
        val room = playingRoom()
        assertEquals(emptyList<PlayerOp>(), plan(room, 215_000, local(pos = 200_000, ended = true), lastKey = keyAt(room, 215_000)).ops)
        val restarted = playingRoom(positionMs = 0, anchor = 215_000, version = 2)
        assertEquals(listOf(PlayerOp.SeekTo(0), PlayerOp.Play), plan(restarted, 215_000, local(pos = 200_000, ended = true)).ops)
    }

    @Test
    fun liveEntriesNeverSeek() {
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(listOf(PlayerOp.Load(live, 0), PlayerOp.Play), plan(room, 5_000, local(loaded = null)).ops)
        val steady = plan(room, 50_000, local(loaded = "l", pos = 1_000, advancing = true, pwr = true), lastKey = keyAt(room, 50_000))
        assertEquals(emptyList<PlayerOp>(), steady.ops)
    }

    @Test
    fun forceResyncsAnUnchangedRoom() {
        val room = playingRoom()
        val p = plan(room, 20_000, local(pos = 10_600, advancing = true, pwr = true), lastKey = keyAt(room, 20_000), force = true)
        assertEquals(listOf(PlayerOp.SeekTo(10_000)), p.ops)
    }

    @Test
    fun noDriftCorrectionAtTheVeryEnd() {
        val room = playingRoom()
        val p = plan(room, 250_000, local(pos = 201_500, advancing = true, pwr = true), lastKey = keyAt(room, 250_000))
        assertEquals(emptyList<PlayerOp>(), p.ops)
    }
}
```

`app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitionsTest.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeModeTransitionsTest {

    @Test
    fun aWelcomeEntersRitmeMode() {
        assertEquals(RitmeTransition.Enter, RitmeModeTransitions.next(false, RitmeUiState(phase = RitmePhase.IN_ROOM, room = room())))
    }

    @Test
    fun reconnectingStaysInRitmeMode() {
        val state = RitmeUiState(phase = RitmePhase.IN_ROOM, connection = ConnectionStatus.RECONNECTING, room = room())
        assertEquals(RitmeTransition.None, RitmeModeTransitions.next(true, state))
    }

    @Test
    fun anyEndLeavesRitmeModeWithItsReason() {
        val state = RitmeUiState(phase = RitmePhase.ENDED, ended = EndReason.Kicked)
        assertEquals(RitmeTransition.Exit(EndReason.Kicked), RitmeModeTransitions.next(true, state))
        assertEquals(RitmeTransition.Exit(null), RitmeModeTransitions.next(true, RitmeUiState()))
    }

    @Test
    fun failuresBeforeTheWelcomeNeverEnteredRitmeMode() {
        val state = RitmeUiState(phase = RitmePhase.ENDED, ended = EndReason.Unreachable)
        assertEquals(RitmeTransition.None, RitmeModeTransitions.next(false, state))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.sync.RitmeSyncPlannerTest" --tests "com.example.musicfy.ritme.session.RitmeModeTransitionsTest"`
Expected: FAIL — `Unresolved reference 'RitmeSyncPlanner'` / `'RitmeModeTransitions'`.

- [ ] **Step 3: Write the implementation**

`app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt`:

```kotlin
package com.example.musicfy.ritme.sync

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry
import com.example.musicfy.ritme.protocol.isLive
import com.example.musicfy.ritme.protocol.positionAt
import kotlin.math.abs

/** What the local player is doing, as the bridge reads it right before planning. */
data class LocalPlayerView(
    /** The room entry the bridge last loaded into the player, or null. */
    val loadedEntryId: String?,
    val positionMs: Long,
    /** Player.isPlaying: ready, wanting to play, and actually advancing. */
    val isAdvancing: Boolean,
    val playWhenReady: Boolean,
    /** The loaded entry reached STATE_ENDED and nothing has restarted it. */
    val ended: Boolean,
    val speed: Float,
)

sealed interface PlayerOp {
    /** Replace the player's item with [entry] at [startMs], paused. */
    data class Load(val entry: QueueEntry, val startMs: Long) : PlayerOp
    data object Play : PlayerOp
    data object Pause : PlayerOp
    data class SeekTo(val positionMs: Long) : PlayerOp
    data class SetSpeed(val speed: Float) : PlayerOp
    /** Nothing playable is current: pause and go back to 1.0x. */
    data object Halt : PlayerOp
}

data class SyncInput(
    val room: RoomState?,
    val serverNow: Long,
    val local: LocalPlayerView,
    /** [SyncPlan.appliedKey] of the previous plan. */
    val lastAppliedKey: String?,
    val localNowMs: Long,
    val lastSeekAtMs: Long?,
    /** Re-sync as if the room had just changed (after a reconnect, or a local "resume"). */
    val force: Boolean = false,
)

data class SyncPlan(val ops: List<PlayerOp>, val wakeAtServerMs: Long?, val appliedKey: String?)

/**
 * Decides how the local player follows the room (spec 2.5, 2.6). Pure: the bridge reads the
 * player into a [LocalPlayerView], runs [plan] on every snapshot, every second and at the start
 * anchor, and executes the ops.
 *
 * - A new current entry is loaded paused; it starts at the anchor (now + 1500 ms on the server),
 *   so every device starts together; a late joiner loads at the room position and plays.
 * - When the room's playback changes (play, pause, seek, anchor reached, [SyncInput.force]) the
 *   player is aligned: a drift over [RESYNC_ON_CHANGE_MS] is seeked away at once.
 * - Otherwise, while the player advances, [DriftCorrector] nudges speed or seeks.
 * - A player that is paused locally while the room plays (audio focus, unplugged headphones, or a
 *   member without control pausing "on this device only") is left alone until the room changes;
 *   an ended track waits for the room to move on.
 */
object RitmeSyncPlanner {
    const val PAUSED_ALIGN_MS = 40L
    const val RESYNC_ON_CHANGE_MS = 250L

    /** Room position at [serverNow]; held at positionMs until a future anchor. */
    fun expectedPosition(room: RoomState, entry: QueueEntry, serverNow: Long): Long {
        val p = room.playback
        if (p.isPlaying && serverNow < p.anchorServerTimeMs && !entry.isLive) {
            return p.positionMs.coerceIn(0, entry.durationMs)
        }
        return room.positionAt(serverNow)
    }

    /** Identifies the room's playback "instruction"; a different key means the room changed. */
    fun appliedKey(room: RoomState, serverNow: Long): String? {
        val entry = room.currentEntry()?.takeIf(RitmeEntries::isPlayable) ?: return null
        val p = room.playback
        val waiting = p.isPlaying && serverNow < p.anchorServerTimeMs
        return "${entry.entryId}|${p.isPlaying}|${p.positionMs}|${p.anchorServerTimeMs}|$waiting"
    }

    fun plan(input: SyncInput): SyncPlan {
        val room = input.room
        val local = input.local
        val entry = room?.currentEntry()
        if (room == null || entry == null || !RitmeEntries.isPlayable(entry)) {
            val halt = local.playWhenReady || local.speed != DriftCorrector.NORMAL
            return SyncPlan(if (halt) listOf(PlayerOp.Halt) else emptyList(), null, null)
        }
        val p = room.playback
        val now = input.serverNow
        val key = appliedKey(room, now)
        val waiting = p.isPlaying && now < p.anchorServerTimeMs
        val changed = input.force || key != input.lastAppliedKey
        val live = entry.isLive
        val expected = expectedPosition(room, entry, now)
        val justLoaded = local.loadedEntryId != entry.entryId
        val drift = local.positionMs - expected
        val ops = mutableListOf<PlayerOp>()

        if (justLoaded) ops += PlayerOp.Load(entry, if (live) 0 else expected)

        when {
            waiting -> {
                if (!justLoaded && local.playWhenReady) ops += PlayerOp.Pause
                if (!justLoaded && !live && abs(local.positionMs - expected) > PAUSED_ALIGN_MS) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                return SyncPlan(ops, p.anchorServerTimeMs, key)
            }
            !p.isPlaying -> {
                if (!justLoaded && local.playWhenReady) ops += PlayerOp.Pause
                if (!justLoaded && !live && abs(drift) > PAUSED_ALIGN_MS) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
            }
            justLoaded -> {
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                ops += PlayerOp.Play
            }
            changed -> {
                if (!live && (local.ended || abs(drift) > RESYNC_ON_CHANGE_MS)) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                if (!local.playWhenReady || local.ended) ops += PlayerOp.Play
            }
            local.ended -> Unit // waiting for the room to move on (TrackEnded or the server's fallback)
            // At the end the expected position is capped at the (second-rounded) duration; the
            // real media may run a little longer, so seeking back would replay the tail.
            local.isAdvancing && !live && expected < entry.durationMs -> {
                when (val action = DriftCorrector.evaluate(expected, local.positionMs, local.speed, input.localNowMs, input.lastSeekAtMs)) {
                    is DriftAction.Seek -> ops += PlayerOp.SeekTo(action.positionMs)
                    is DriftAction.SetSpeed -> ops += PlayerOp.SetSpeed(action.speed)
                    DriftAction.Ignore -> Unit
                }
            }
            else -> Unit // held locally: left alone until the room changes
        }
        return SyncPlan(ops, null, key)
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitions.kt`:

```kotlin
package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.EndReason

sealed interface RitmeTransition {
    data object None : RitmeTransition
    /** First Welcome: save the local queue and hand the player to the room. */
    data object Enter : RitmeTransition
    /** Left, kicked, closed or lost: restore the local queue (paused). */
    data class Exit(val reason: EndReason?) : RitmeTransition
}

/** When the playback bridge enters and leaves "Ritme mode". Reconnecting stays in the mode. */
object RitmeModeTransitions {
    fun next(inRitme: Boolean, state: RitmeUiState): RitmeTransition = when {
        !inRitme && state.phase == RitmePhase.IN_ROOM -> RitmeTransition.Enter
        inRitme && state.phase != RitmePhase.IN_ROOM -> RitmeTransition.Exit(state.ended)
        else -> RitmeTransition.None
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.sync.RitmeSyncPlannerTest" --tests "com.example.musicfy.ritme.session.RitmeModeTransitionsTest"`
Expected: PASS (15 + 4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt app/src/main/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitions.kt app/src/test/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlannerTest.kt app/src/test/kotlin/com/example/musicfy/ritme/session/RitmeModeTransitionsTest.kt
git commit -m "feat(ritme-android): sync planner (anchor start, resync, drift) and mode transitions" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Gate, controller, notifier and settings

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeGate.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeNotifier.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeTexts.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeAndroid.kt`
- Create: `app/src/main/res/values/ritme_strings.xml`
- Create: `app/src/main/res/values-in/ritme_strings.xml`
- Modify: `app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt`
- Modify: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt` (append)
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 `RitmeConfig`, `RitmeLinks`, prefs keys; Task 3 `RitmePermissions`, `NoticeKey`, `EndReason`; Task 5 `RitmeEntries`; Task 6 `RitmeSession`.
- Produces:
  - `sealed interface RitmeLocalAction { Play; Pause; TogglePlayPause; Seek(positionMs: Long); Next; Previous; AddItems(songs: List<MediaMetadata?>, playNext: Boolean); PlayQueue(songs: List<MediaMetadata?>); QueueEdit; Shuffle; Repeat; Speed }`
  - `@Singleton class RitmeGate @Inject constructor() { val active: StateFlow<Boolean>; val isActive: Boolean; var handler: ((RitmeLocalAction) -> Unit)?; fun intercept(action: RitmeLocalAction): Boolean; fun activate(); fun deactivate() }` — `intercept` returns `false` (caller runs its normal code) whenever the gate is closed.
  - `sealed interface RitmeToast { QueueSaved; NotAvailable; ControlsLocked; PausedLocally; Reconnecting; CastingBlocked; Added(count); SkippedLocal(count); Ended(reason: EndReason); CommandFailed(key: NoticeKey) }`, `fun interface RitmeNotifier { fun show(toast: RitmeToast) }`
  - `interface RitmeLocalPlayback { val isPlayingLocally: Boolean; val isCasting: Boolean; fun resumeLocally(); fun pauseLocally(); fun playOrderQueue(): LocalPlayOrder }`, `data class LocalPlayOrder(val songs: List<MediaMetadata?>, val currentIndex: Int, val positionMs: Long)`
  - `interface RitmeSettings { val serverUrl: String; val displayName: String }`
  - `data class RitmeSheetRequest(val id: Long, val kind: Kind) { sealed interface Kind { data object Room; data class Entry(val code: String? = null, val startWith: List<MediaMetadata?>? = null) } }`
  - `@Singleton class RitmeController @Inject constructor(session: RitmeSession, gate: RitmeGate, notifier: RitmeNotifier, settings: RitmeSettings)` with `var localPlayback: RitmeLocalPlayback?`, `val sheetRequest: StateFlow<RitmeSheetRequest?>`, `fun openSheet()`, `fun openJoin(code: String)`, `fun openStartWith(songs: List<MediaMetadata?>)`, `fun consumeSheetRequest(id: Long)`, `fun startRoom(name: String, startWith: List<MediaMetadata?>? = null)`, `fun joinRoom(code: String, name: String)`, `fun shareLink(code: String): String`, `fun handle(action: RitmeLocalAction)`.
  - `object RitmeTexts { fun notice(key): Int; fun endedToast(reason): Int?; fun entryError(reason: EndReason?): Int? }`
  - `class TopToastRitmeNotifier(context) : RitmeNotifier`, `class DataStoreRitmeSettings(context) : RitmeSettings`
  - Strings: `ritme_name`, `ritme_reconnecting`, `ritme_toast_*`, `ritme_error_*`, plurals `ritme_toast_added`, `ritme_toast_skipped_local`.
- Test fakes: `FakeNotifier` (`shown`), `FakeLocalPlayback` (`isPlayingLocally`, `isCasting`, `resumed`, `pausedLocally`, `order`).

- [ ] **Step 1: Append the fakes**

Append to `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt` (imports: `com.example.musicfy.ritme.LocalPlayOrder`, `com.example.musicfy.ritme.RitmeLocalPlayback`, `com.example.musicfy.ritme.RitmeNotifier`, `com.example.musicfy.ritme.RitmeToast`):

```kotlin
class FakeNotifier : RitmeNotifier {
    val shown = mutableListOf<RitmeToast>()
    override fun show(toast: RitmeToast) {
        shown += toast
    }
}

class FakeLocalPlayback : RitmeLocalPlayback {
    override var isPlayingLocally = false
    override var isCasting = false
    var resumed = 0
    var pausedLocally = 0
    var order = LocalPlayOrder(emptyList(), 0, 0)

    override fun resumeLocally() {
        resumed++
        isPlayingLocally = true
    }

    override fun pauseLocally() {
        pausedLocally++
        isPlayingLocally = false
    }

    override fun playOrderQueue(): LocalPlayOrder = order
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.FakeLocalPlayback
import com.example.musicfy.ritme.testing.FakeNotifier
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeControllerTest {

    private val connector = FakeConnector()
    private val session = RitmeSession(connector) { 0L }
    private val gate = RitmeGate()
    private val toasts = FakeNotifier()
    private val local = FakeLocalPlayback()
    private val settings = object : RitmeSettings {
        override val serverUrl = "https://ritme.dubeli.id"
        override val displayName = "Budi"
    }
    private val controller = RitmeController(session, gate, toasts, settings).also { it.localPlayback = local }

    private val playing = room(
        queue = listOf(entry("e1")),
        currentEntryId = "e1",
        playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = 0),
    )
    private val paused = playing.copy(playback = Playback(isPlaying = false, positionMs = 5_000, anchorServerTimeMs = 0))

    private fun inRoom(state: RoomState, memberId: String = "m1") {
        session.join(settings.serverUrl, state.code, "Budi")
        connector.last.welcome(state, memberId)
    }

    private val commands get() = connector.last.commands

    private fun song(id: String, local: Boolean = false) = MediaMetadata(
        id = id,
        title = "Song $id",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
        duration = 200,
        localUri = if (local) "content://media/$id" else null,
    )

    @Test
    fun aLockedGuestGetsAToastInsteadOfACommand() {
        inRoom(paused.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Next)
        controller.handle(RitmeLocalAction.Previous)
        controller.handle(RitmeLocalAction.Seek(1_000))
        assertTrue(commands.isEmpty())
        assertEquals(List<RitmeToast>(3) { RitmeToast.ControlsLocked }, toasts.shown)
    }

    @Test
    fun aLockedGuestPausesAndResumesOnlyTheirOwnDevice() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.Pause)
        assertEquals(1, local.pausedLocally)
        assertEquals(listOf<RitmeToast>(RitmeToast.PausedLocally), toasts.shown)
        controller.handle(RitmeLocalAction.TogglePlayPause) // paused locally, room playing: resume this device
        assertEquals(1, local.resumed)
        controller.handle(RitmeLocalAction.TogglePlayPause) // playing again: pause this device again
        assertEquals(2, local.pausedLocally)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aLockedGuestCannotStartAPausedRoom() {
        inRoom(paused.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Play)
        assertEquals(0, local.resumed)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.ControlsLocked), toasts.shown)
    }

    @Test
    fun aMemberWithControlPausesTheWholeRoom() {
        inRoom(playing, memberId = "m2")
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.Pause)
        assertEquals(listOf<Command>(Command.Pause), commands)
        assertEquals(0, local.pausedLocally)
    }

    @Test
    fun aGuestWithControlSendsCommands() {
        inRoom(paused, memberId = "m2")
        controller.handle(RitmeLocalAction.Next)
        controller.handle(RitmeLocalAction.Seek(42_000))
        controller.handle(RitmeLocalAction.Play)
        assertEquals(listOf(Command.Next, Command.Seek(42_000), Command.Play), commands)
    }

    @Test
    fun playWhileTheRoomPlaysResumesLocallyEvenWhenLocked() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanControl = false)), memberId = "m2")
        controller.handle(RitmeLocalAction.Play)
        assertEquals(1, local.resumed)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun toggleFollowsTheLocalPlayer() {
        inRoom(playing)
        local.isPlayingLocally = true
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(listOf<Command>(Command.Pause), commands)
        local.isPlayingLocally = false
        controller.handle(RitmeLocalAction.TogglePlayPause)
        assertEquals(1, local.resumed)
    }

    @Test
    fun commandsDroppedWhileReconnectingSaySo() {
        inRoom(paused)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        controller.handle(RitmeLocalAction.Next)
        assertEquals(listOf<RitmeToast>(RitmeToast.Reconnecting), toasts.shown)
    }

    @Test
    fun addingSkipsLocalSongsAndBatchesByTwoHundred() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems((1..250).map { song("s$it") } + song("x", local = true), playNext = false))
        assertEquals(listOf(200, 50), commands.map { (it as Command.Add).entries.size })
        assertTrue(commands.all { !(it as Command.Add).playNext })
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(250)), toasts.shown)
    }

    @Test
    fun playNextSendsTheLastBatchFirstSoTheOrderSurvives() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems((1..250).map { song("s$it") }, playNext = true))
        assertEquals(listOf(50, 200), commands.map { (it as Command.Add).entries.size })
        assertTrue(commands.all { (it as Command.Add).playNext })
    }

    @Test
    fun onlyLocalSongsAddNothing() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.AddItems(listOf(song("x", local = true)), playNext = false))
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.SkippedLocal(1)), toasts.shown)
    }

    @Test
    fun tappingASongInARoomAddsIt() {
        inRoom(playing)
        controller.handle(RitmeLocalAction.PlayQueue(listOf(song("a"))))
        assertEquals(listOf<Command>(Command.Add(listOf(RitmeEntries.toNewEntry(song("a"))!!), playNext = false)), commands)
        controller.handle(RitmeLocalAction.PlayQueue(emptyList()))
        assertEquals(RitmeToast.NotAvailable, toasts.shown.last())
    }

    @Test
    fun localOnlyFeaturesAreNotAvailable() {
        inRoom(playing)
        listOf(RitmeLocalAction.Shuffle, RitmeLocalAction.Repeat, RitmeLocalAction.Speed, RitmeLocalAction.QueueEdit).forEach(controller::handle)
        assertTrue(commands.isEmpty())
        assertEquals(List<RitmeToast>(4) { RitmeToast.NotAvailable }, toasts.shown)
    }

    @Test
    fun startRoomBuildsTheInitialQueueFromThePlayOrder() {
        local.order = LocalPlayOrder(listOf(song("old"), song("a"), song("x", local = true), song("b")), currentIndex = 1, positionMs = 5_000)
        controller.startRoom("Budi")
        val expected = listOf(song("a"), song("b")).map { RitmeEntries.toNewEntry(it)!! }
        assertEquals(JoinIntent.Create("Budi", expected, 0, 5_000), connector.last.intent)
        assertEquals("wss://ritme.dubeli.id/ritme", connector.last.url)
        assertEquals(listOf<RitmeToast>(RitmeToast.SkippedLocal(1)), toasts.shown)
    }

    @Test
    fun startWithUsesTheGivenSongs() {
        controller.startRoom("Budi", startWith = listOf(song("z")))
        assertEquals(JoinIntent.Create("Budi", listOf(RitmeEntries.toNewEntry(song("z"))!!), 0, 0), connector.last.intent)
    }

    @Test
    fun castingBlocksStartingAndJoining() {
        local.isCasting = true
        controller.startRoom("Budi")
        controller.joinRoom("K7M2QX", "Budi")
        assertTrue(connector.connections.isEmpty())
        assertEquals(List<RitmeToast>(2) { RitmeToast.CastingBlocked }, toasts.shown)
    }

    @Test
    fun theGateRoutesToTheControllerOnlyWhileActive() {
        inRoom(paused)
        assertFalse(gate.intercept(RitmeLocalAction.Next))
        gate.activate()
        assertTrue(gate.intercept(RitmeLocalAction.Next))
        assertEquals(listOf<Command>(Command.Next), commands)
        gate.deactivate()
        assertFalse(gate.isActive)
    }

    @Test
    fun sheetRequestsPickEntryOrRoom() {
        controller.openJoin("K7M2QX")
        val first = controller.sheetRequest.value!!
        assertEquals(RitmeSheetRequest.Kind.Entry(code = "K7M2QX"), first.kind)
        controller.consumeSheetRequest(first.id)
        assertNull(controller.sheetRequest.value)
        inRoom(paused)
        controller.openSheet()
        assertEquals(RitmeSheetRequest.Kind.Room, controller.sheetRequest.value?.kind)
        assertEquals("https://ritme.dubeli.id/r/K7M2QX", controller.shareLink("K7M2QX"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.RitmeControllerTest"`
Expected: FAIL — `Unresolved reference 'RitmeGate'` / `'RitmeController'`.

- [ ] **Step 4: Write gate, notifier and controller**

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeGate.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something the user asked the local player to do. While in a room it goes to the room instead. */
sealed interface RitmeLocalAction {
    data object Play : RitmeLocalAction
    data object Pause : RitmeLocalAction
    data object TogglePlayPause : RitmeLocalAction
    data class Seek(val positionMs: Long) : RitmeLocalAction
    data object Next : RitmeLocalAction
    data object Previous : RitmeLocalAction
    /** "Add to queue" (playNext = false) or "Play next" (playNext = true). */
    data class AddItems(val songs: List<MediaMetadata?>, val playNext: Boolean) : RitmeLocalAction
    /** Tapping a song/album to play it: the songs it would start with. */
    data class PlayQueue(val songs: List<MediaMetadata?>) : RitmeLocalAction
    /** Reordering or removing in the local queue page. */
    data object QueueEdit : RitmeLocalAction
    data object Shuffle : RitmeLocalAction
    data object Repeat : RitmeLocalAction
    data object Speed : RitmeLocalAction
}

/**
 * The single switch between the normal player and Ritme. Every local control path calls
 * [intercept] first: closed (not in a room) it returns false and the caller runs its usual code;
 * open it hands the action to [handler] (RitmeController) and returns true. Main thread only.
 */
@Singleton
class RitmeGate @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    @Volatile
    var handler: ((RitmeLocalAction) -> Unit)? = null

    fun intercept(action: RitmeLocalAction): Boolean {
        if (!_active.value) return false
        handler?.invoke(action)
        return true
    }

    fun activate() {
        _active.value = true
    }

    fun deactivate() {
        _active.value = false
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeNotifier.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.session.NoticeKey

sealed interface RitmeToast {
    data object QueueSaved : RitmeToast
    data object NotAvailable : RitmeToast
    data object ControlsLocked : RitmeToast
    data object PausedLocally : RitmeToast
    data object Reconnecting : RitmeToast
    data object CastingBlocked : RitmeToast
    data class Added(val count: Int) : RitmeToast
    data class SkippedLocal(val count: Int) : RitmeToast
    data class Ended(val reason: EndReason) : RitmeToast
    data class CommandFailed(val key: NoticeKey) : RitmeToast
}

fun interface RitmeNotifier {
    fun show(toast: RitmeToast)
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ritme.session.RitmeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The local player, as the controller needs it. RitmePlaybackBridge implements it. */
interface RitmeLocalPlayback {
    val isPlayingLocally: Boolean
    val isCasting: Boolean
    /** The room is playing but this device is paused locally: just play again and re-sync. */
    fun resumeLocally()
    /** Pause this device only (a member without control); the room plays on and nothing is sent. */
    fun pauseLocally()
    fun playOrderQueue(): LocalPlayOrder
}

/** The local queue in play order (shuffle applied), for a room's starting queue. */
data class LocalPlayOrder(val songs: List<MediaMetadata?>, val currentIndex: Int, val positionMs: Long)

interface RitmeSettings {
    val serverUrl: String
    val displayName: String
}

/** A request for the UI to show a Ritme sheet; [id] makes repeated requests distinct. */
data class RitmeSheetRequest(val id: Long, val kind: Kind) {
    sealed interface Kind {
        data object Room : Kind
        data class Entry(val code: String? = null, val startWith: List<MediaMetadata?>? = null) : Kind
    }
}

/**
 * Turns local intents into room commands, checking the permission table first (spec 2.3, 3.4):
 * refused controls show "The host locked the controls"; local-only features show "Not available
 * during Ritme". A member without control may still pause/resume their own device (no command;
 * "Paused on this device only"). Also starts/joins rooms and asks the UI for sheets. Main thread only.
 */
@Singleton
class RitmeController @Inject constructor(
    private val session: RitmeSession,
    gate: RitmeGate,
    private val notifier: RitmeNotifier,
    private val settings: RitmeSettings,
) {
    var localPlayback: RitmeLocalPlayback? = null

    private val _sheetRequest = MutableStateFlow<RitmeSheetRequest?>(null)
    val sheetRequest: StateFlow<RitmeSheetRequest?> = _sheetRequest.asStateFlow()
    private var requestIds = 0L

    init {
        gate.handler = ::handle
    }

    fun openSheet() = request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry())

    fun openJoin(code: String) =
        request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry(code = code))

    fun openStartWith(songs: List<MediaMetadata?>) =
        request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry(startWith = songs))

    fun consumeSheetRequest(id: Long) {
        if (_sheetRequest.value?.id == id) _sheetRequest.value = null
    }

    private fun request(kind: RitmeSheetRequest.Kind) {
        _sheetRequest.value = RitmeSheetRequest(++requestIds, kind)
    }

    fun shareLink(code: String): String = RitmeLinks.shareLink(settings.serverUrl, code)

    /** Creates a room from [startWith], or else from the local queue (current song onward, play order). */
    fun startRoom(name: String, startWith: List<MediaMetadata?>? = null) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        val order = startWith?.let { LocalPlayOrder(it, 0, 0) }
            ?: localPlayback?.playOrderQueue()
            ?: LocalPlayOrder(emptyList(), 0, 0)
        val initial = RitmeEntries.buildInitialQueue(order.songs, order.currentIndex, order.positionMs)
        if (initial.skipped > 0) notifier.show(RitmeToast.SkippedLocal(initial.skipped))
        session.create(settings.serverUrl, name, initial)
    }

    fun joinRoom(code: String, name: String) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        session.join(settings.serverUrl, code, name)
    }

    fun handle(action: RitmeLocalAction) {
        val state = session.state.value
        val room = state.room ?: return
        val me = state.memberId
        when (action) {
            RitmeLocalAction.Play -> play(room, me)
            RitmeLocalAction.Pause -> pause(room, me)
            RitmeLocalAction.TogglePlayPause ->
                if (localPlayback?.isPlayingLocally == true) pause(room, me) else play(room, me)
            is RitmeLocalAction.Seek -> control(room, me) { session.seek(action.positionMs) }
            RitmeLocalAction.Next -> control(room, me) { session.next() }
            RitmeLocalAction.Previous -> control(room, me) { session.previous() }
            is RitmeLocalAction.AddItems -> add(action.songs, action.playNext)
            is RitmeLocalAction.PlayQueue ->
                if (action.songs.isEmpty()) notifier.show(RitmeToast.NotAvailable) else add(action.songs, playNext = false)
            RitmeLocalAction.QueueEdit,
            RitmeLocalAction.Shuffle,
            RitmeLocalAction.Repeat,
            RitmeLocalAction.Speed -> notifier.show(RitmeToast.NotAvailable)
        }
    }

    private fun play(room: RoomState, me: String?) {
        // Already playing for the room: this device was paused locally, so only it resumes.
        if (room.playback.isPlaying) localPlayback?.resumeLocally() else control(room, me) { session.play() }
    }

    private fun pause(room: RoomState, me: String?) {
        if (RitmePermissions.canControl(room, me)) {
            control(room, me) { session.pause() }
            return
        }
        // No control: pause this device only. The room plays on; the next room change re-syncs it.
        val local = localPlayback ?: return
        if (!local.isPlayingLocally) return
        local.pauseLocally()
        notifier.show(RitmeToast.PausedLocally)
    }

    private inline fun control(room: RoomState, me: String?, send: () -> Boolean) {
        when {
            !RitmePermissions.canControl(room, me) -> notifier.show(RitmeToast.ControlsLocked)
            !send() -> notifier.show(RitmeToast.Reconnecting)
        }
    }

    private fun add(songs: List<MediaMetadata?>, playNext: Boolean) {
        val batches = RitmeEntries.toAddBatches(songs)
        if (batches.count == 0) {
            notifier.show(if (batches.skipped > 0) RitmeToast.SkippedLocal(batches.skipped) else RitmeToast.NotAvailable)
            return
        }
        // Each "play next" batch lands right after the current entry, so the last batch goes first.
        val ordered = if (playNext) batches.batches.asReversed() else batches.batches
        val sent = ordered.all { session.add(it, playNext) }
        notifier.show(if (sent) RitmeToast.Added(batches.count) else RitmeToast.Reconnecting)
    }
}
```

- [ ] **Step 5: Write strings, texts and Android implementations**

`app/src/main/res/values/ritme_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Ritme: listen together. Indonesian copy lives in values-in/ritme_strings.xml. -->
    <string name="ritme_name" translatable="false">Ritme</string>
    <string name="ritme_reconnecting">Reconnecting…</string>

    <string name="ritme_toast_queue_saved">Your queue is saved and comes back when you leave Ritme</string>
    <string name="ritme_toast_unavailable">Not available during Ritme</string>
    <string name="ritme_toast_locked">The host locked the controls</string>
    <string name="ritme_toast_paused_locally">Paused on this device only</string>
    <string name="ritme_toast_casting">Stop casting to use Ritme</string>
    <plurals name="ritme_toast_added">
        <item quantity="one">Added %d song to Ritme</item>
        <item quantity="other">Added %d songs to Ritme</item>
    </plurals>
    <plurals name="ritme_toast_skipped_local">
        <item quantity="one">%d local song skipped</item>
        <item quantity="other">%d local songs skipped</item>
    </plurals>
    <string name="ritme_toast_kicked">You were removed from Ritme</string>
    <string name="ritme_toast_closed">The host ended Ritme</string>
    <string name="ritme_toast_lost">Disconnected from Ritme</string>
    <string name="ritme_toast_forbidden">Not allowed in this Ritme</string>
    <string name="ritme_toast_queue_full">The Ritme queue is full</string>

    <string name="ritme_error_unreachable">Can\'t reach the Ritme server</string>
    <string name="ritme_error_not_found">Room not found</string>
    <string name="ritme_error_full">Room is full</string>
    <string name="ritme_error_upgrade">Update the app to join</string>
    <string name="ritme_error_rate_limited">Too many attempts. Try again in a moment</string>
    <string name="ritme_error_invalid">Check the name and the code</string>
    <string name="ritme_error_generic">Something went wrong</string>
</resources>
```

`app/src/main/res/values-in/ritme_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="ritme_reconnecting">Menyambung ulang…</string>

    <string name="ritme_toast_queue_saved">Antrean kamu disimpan, dan akan kembali setelah keluar dari Ritme</string>
    <string name="ritme_toast_unavailable">Tidak tersedia saat Ritme</string>
    <string name="ritme_toast_locked">Host mengunci kontrol</string>
    <string name="ritme_toast_paused_locally">Dijeda di perangkat ini saja</string>
    <string name="ritme_toast_casting">Hentikan cast untuk memakai Ritme</string>
    <plurals name="ritme_toast_added">
        <item quantity="other">%d lagu ditambahkan ke Ritme</item>
    </plurals>
    <plurals name="ritme_toast_skipped_local">
        <item quantity="other">%d lagu lokal dilewati</item>
    </plurals>
    <string name="ritme_toast_kicked">Kamu dikeluarkan dari Ritme</string>
    <string name="ritme_toast_closed">Ritme diakhiri host</string>
    <string name="ritme_toast_lost">Terputus dari Ritme</string>
    <string name="ritme_toast_forbidden">Tidak diizinkan di Ritme ini</string>
    <string name="ritme_toast_queue_full">Antrean Ritme penuh</string>

    <string name="ritme_error_unreachable">Tidak bisa terhubung ke server Ritme</string>
    <string name="ritme_error_not_found">Room tidak ditemukan</string>
    <string name="ritme_error_full">Room penuh</string>
    <string name="ritme_error_upgrade">Perbarui aplikasi untuk bergabung</string>
    <string name="ritme_error_rate_limited">Terlalu banyak percobaan. Coba lagi sebentar lagi</string>
    <string name="ritme_error_invalid">Periksa nama dan kodenya</string>
    <string name="ritme_error_generic">Terjadi kesalahan</string>
</resources>
```

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeTexts.kt`:

```kotlin
package com.example.musicfy.ritme

import androidx.annotation.StringRes
import com.example.musicfy.R
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.session.NoticeKey

/** Which string describes a Ritme rejection or ending (spec 4.1). */
object RitmeTexts {
    @StringRes
    fun notice(key: NoticeKey): Int = when (key) {
        NoticeKey.FORBIDDEN -> R.string.ritme_toast_forbidden
        NoticeKey.RATE_LIMITED -> R.string.ritme_error_rate_limited
        NoticeKey.QUEUE_FULL -> R.string.ritme_toast_queue_full
        NoticeKey.INVALID -> R.string.ritme_error_invalid
        NoticeKey.FAILED -> R.string.ritme_error_generic
    }

    /** Toast after leaving a room for [reason]; null for a plain "Leave". */
    @StringRes
    fun endedToast(reason: EndReason): Int? = when (reason) {
        EndReason.Kicked -> R.string.ritme_toast_kicked
        EndReason.Closed -> R.string.ritme_toast_closed
        EndReason.Lost -> R.string.ritme_toast_lost
        else -> null
    }

    /** Inline error in the start/join sheet. */
    @StringRes
    fun entryError(reason: EndReason?): Int? = when (reason) {
        EndReason.Unreachable -> R.string.ritme_error_unreachable
        EndReason.Lost -> R.string.ritme_toast_lost
        is EndReason.Rejected -> when (reason.code) {
            ErrorCode.ROOM_NOT_FOUND -> R.string.ritme_error_not_found
            ErrorCode.ROOM_FULL -> R.string.ritme_error_full
            ErrorCode.UPGRADE_REQUIRED -> R.string.ritme_error_upgrade
            ErrorCode.RATE_LIMITED -> R.string.ritme_error_rate_limited
            ErrorCode.INVALID -> R.string.ritme_error_invalid
            else -> R.string.ritme_error_generic
        }
        else -> null
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeAndroid.kt`:

```kotlin
package com.example.musicfy.ritme

import android.content.Context
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.RitmeServerUrlKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.ui.component.TopToaster
import com.example.musicfy.utils.dataStore
import com.example.musicfy.utils.get

/** Ritme toasts through the app's TopToaster pill. */
class TopToastRitmeNotifier(private val context: Context) : RitmeNotifier {
    override fun show(toast: RitmeToast) {
        val res = context.resources
        when (toast) {
            RitmeToast.QueueSaved -> TopToaster.show(res.getString(R.string.ritme_toast_queue_saved), iconRes = R.drawable.group)
            RitmeToast.NotAvailable -> TopToaster.show(res.getString(R.string.ritme_toast_unavailable), iconRes = R.drawable.group)
            RitmeToast.ControlsLocked -> TopToaster.error(res.getString(R.string.ritme_toast_locked))
            RitmeToast.PausedLocally -> TopToaster.show(res.getString(R.string.ritme_toast_paused_locally), iconRes = R.drawable.group)
            RitmeToast.Reconnecting -> TopToaster.show(res.getString(R.string.ritme_reconnecting), iconRes = R.drawable.group)
            RitmeToast.CastingBlocked -> TopToaster.error(res.getString(R.string.ritme_toast_casting))
            is RitmeToast.Added -> TopToaster.show(
                res.getQuantityString(R.plurals.ritme_toast_added, toast.count, toast.count),
                iconRes = R.drawable.queue_music,
            )
            is RitmeToast.SkippedLocal -> TopToaster.show(
                res.getQuantityString(R.plurals.ritme_toast_skipped_local, toast.count, toast.count),
                iconRes = R.drawable.group,
            )
            is RitmeToast.Ended -> RitmeTexts.endedToast(toast.reason)?.let {
                TopToaster.show(res.getString(it), iconRes = R.drawable.group)
            }
            is RitmeToast.CommandFailed -> TopToaster.error(res.getString(RitmeTexts.notice(toast.key)))
        }
    }
}

/** Server URL (blank or invalid = official server) and display name (falls back to the profile name). */
class DataStoreRitmeSettings(private val context: Context) : RitmeSettings {
    override val serverUrl: String
        get() = context.dataStore[RitmeServerUrlKey]?.let(RitmeLinks::normalizeServerUrl) ?: RitmeConfig.DEFAULT_SERVER_URL

    override val displayName: String
        get() = context.dataStore[RitmeDisplayNameKey]?.takeIf { it.isNotBlank() } ?: context.dataStore[UsernameKey].orEmpty()
}
```

In `app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt` add these imports and providers inside `object RitmeModule`:

```kotlin
import android.content.Context
import com.example.musicfy.ritme.DataStoreRitmeSettings
import com.example.musicfy.ritme.RitmeNotifier
import com.example.musicfy.ritme.RitmeSettings
import com.example.musicfy.ritme.TopToastRitmeNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
```

```kotlin
    @Provides
    @Singleton
    fun provideRitmeNotifier(@ApplicationContext context: Context): RitmeNotifier = TopToastRitmeNotifier(context)

    @Provides
    @Singleton
    fun provideRitmeSettings(@ApplicationContext context: Context): RitmeSettings = DataStoreRitmeSettings(context)
```

- [ ] **Step 6: Run tests and compile**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.*"`
Expected: PASS (including 18 in `RitmeControllerTest`).

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/RitmeGate.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeNotifier.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeTexts.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeAndroid.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/main/kotlin/com/example/musicfy/di/RitmeModule.kt app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeFakes.kt app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt
git commit -m "feat(ritme-android): gate and controller route local intents to the room" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: Playback bridge and MusicService host

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` (fields; `onCreate` after the session is built; `onDestroy`; host object; guards in `ensureQueueRunway`, `onPlaybackStateChanged`, `failStream`, `handleLongSilenceDetected`, `saveQueueToDisk`, `scheduleCrossfade`, `CrossmixHost.crossmixBlocker`, the Audio FX collector)

**Interfaces:**
- Consumes: Task 5 `RitmeEntries.toMediaMetadata`; Task 6 `RitmeSession`; Task 7 `RitmeSyncPlanner`, `LocalPlayerView`, `PlayerOp`, `SyncInput`, `RitmeModeTransitions`, `RitmeTransition`; Task 8 `RitmeGate`, `RitmeController`, `RitmeLocalPlayback`, `LocalPlayOrder`, `RitmeNotifier`, `RitmeToast`; app `MediaMetadata.toMediaItem()`, `Player.mediaItems`, `MediaItem.metadata`, `ListQueue`, `EmptyQueue`.
- Produces:
  - `interface RitmePlayerHost { val player: ExoPlayer; val isCasting: Boolean; fun playOrderQueue(): LocalPlayOrder; fun enterRitmeMode(): LocalQueueSnapshot; fun loadRitmeItem(item: MediaItem, startPositionMs: Long); fun exitRitmeMode(snapshot: LocalQueueSnapshot) }`
  - `data class LocalQueueSnapshot(val title: String?, val items: List<MediaItem>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeatMode: Int, val skipSilence: Boolean) { companion object { val EMPTY } }`
  - `@Singleton class RitmePlaybackBridge @Inject constructor(session, controller, gate, notifier) : RitmeLocalPlayback { fun attach(host: RitmePlayerHost); fun detach() }` (implements `pauseLocally()` with a plain `player.pause()`; Task 7's planner keeps that device paused until the room changes)
  - `MusicService.ritmeGate: RitmeGate` (public `@Inject lateinit var`, read by `PlayerConnection` in Task 10) and a private `ritmeHost` used by Task 10's session-player swap.

- [ ] **Step 1: Write the host interface**

`app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicfy.ritme.LocalPlayOrder

/** What RitmePlaybackBridge needs from MusicService, and nothing more. Main thread only. */
interface RitmePlayerHost {
    /** The service's current player. */
    val player: ExoPlayer
    val isCasting: Boolean

    fun playOrderQueue(): LocalPlayOrder

    /**
     * Saves the local queue and hands the player to the room: pauses it and switches off
     * everything that changes the queue or the speed locally (runway/infinite queue, automix,
     * crossfade/Crossmix, shuffle, repeat, skip silence, Audio FX speed).
     */
    fun enterRitmeMode(): LocalQueueSnapshot

    /** Replaces the player's item with [item] at [startPositionMs], prepared and paused. */
    fun loadRitmeItem(item: MediaItem, startPositionMs: Long)

    /** Puts [snapshot] back, paused, and restores the local settings. */
    fun exitRitmeMode(snapshot: LocalQueueSnapshot)
}

data class LocalQueueSnapshot(
    val title: String?,
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val skipSilence: Boolean,
) {
    companion object {
        val EMPTY = LocalQueueSnapshot(null, emptyList(), 0, 0, false, Player.REPEAT_MODE_OFF, false)
    }
}
```

- [ ] **Step 2: Write the bridge**

`app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicfy.extensions.toMediaItem
import com.example.musicfy.ritme.LocalPlayOrder
import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeLocalPlayback
import com.example.musicfy.ritme.RitmeNotifier
import com.example.musicfy.ritme.RitmeToast
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.session.RitmeModeTransitions
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.session.RitmeTransition
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ritme.sync.LocalPlayerView
import com.example.musicfy.ritme.sync.PlayerOp
import com.example.musicfy.ritme.sync.RitmeSyncPlanner
import com.example.musicfy.ritme.sync.SyncInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes MusicService's player follow the room (spec 1.2, 2.5, 2.6). Attached by MusicService for
 * its lifetime. On the first Welcome it saves the local queue (RitmePlayerHost.enterRitmeMode) and
 * opens the gate; on any end it closes the gate and restores the queue paused. In between it runs
 * RitmeSyncPlanner on every snapshot, every second and at the start anchor, reports TrackEnded
 * once per entry, and toasts rejected commands. Main thread only.
 */
@Singleton
class RitmePlaybackBridge @Inject constructor(
    private val session: RitmeSession,
    private val controller: RitmeController,
    private val gate: RitmeGate,
    private val notifier: RitmeNotifier,
) : RitmeLocalPlayback {
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var host: RitmePlayerHost? = null
    private var listening: Player? = null

    private var inRitme = false
    private var snapshot: LocalQueueSnapshot? = null
    private var loadedEntryId: String? = null
    private var endedEntryId: String? = null
    private var reportedEndFor: String? = null
    private var appliedKey: String? = null
    private var lastSeekAt: Long? = null
    private var lastVersion = Long.MIN_VALUE
    private var lastConnection: ConnectionStatus? = null
    private var lastNoticeId = 0L

    private val tick = object : Runnable {
        override fun run() {
            sync(force = false)
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val wake = Runnable { sync(force = false) }
    private val syncSoon = Runnable { sync(force = false) }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onLocalEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Posted: this can fire from inside our own play()/pause() while ops are executing.
            if (inRitme) {
                handler.removeCallbacks(syncSoon)
                handler.post(syncSoon)
            }
        }
    }

    fun attach(host: RitmePlayerHost) {
        detach()
        this.host = host
        controller.localPlayback = this
        lastNoticeId = session.state.value.notice?.id ?: 0L
        scope = MainScope().also { s -> s.launch { session.state.collect(::onState) } }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(wake)
        handler.removeCallbacks(syncSoon)
        listening?.removeListener(playerListener)
        listening = null
        if (inRitme) {
            // The service is going away, so nothing would play the room here any more.
            inRitme = false
            gate.deactivate()
            session.leave()
        }
        if (controller.localPlayback === this) controller.localPlayback = null
        host = null
    }

    private fun onState(state: RitmeUiState) {
        state.notice?.takeIf { it.id != lastNoticeId }?.let {
            lastNoticeId = it.id
            notifier.show(RitmeToast.CommandFailed(it.key))
        }
        when (val transition = RitmeModeTransitions.next(inRitme, state)) {
            RitmeTransition.Enter -> enter()
            is RitmeTransition.Exit -> exit(transition.reason)
            RitmeTransition.None -> Unit
        }
        if (!inRitme) return
        val room = state.room ?: return
        val reconnected = lastConnection == ConnectionStatus.RECONNECTING && state.connection == ConnectionStatus.ONLINE
        lastConnection = state.connection
        if (reconnected && loadedEntryId != null && endedEntryId == loadedEntryId) onLocalEnded()
        if (room.version != lastVersion || reconnected) {
            lastVersion = room.version
            sync(force = reconnected)
        }
    }

    private fun enter() {
        val h = host ?: return
        gate.activate()
        snapshot = h.enterRitmeMode()
        inRitme = true
        resetTracking()
        if (snapshot?.items?.isNotEmpty() == true) notifier.show(RitmeToast.QueueSaved)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun exit(reason: EndReason?) {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(wake)
        handler.removeCallbacks(syncSoon)
        inRitme = false
        gate.deactivate()
        host?.exitRitmeMode(snapshot ?: LocalQueueSnapshot.EMPTY)
        snapshot = null
        resetTracking()
        reason?.let { notifier.show(RitmeToast.Ended(it)) }
        session.dismiss()
    }

    private fun resetTracking() {
        loadedEntryId = null
        endedEntryId = null
        reportedEndFor = null
        appliedKey = null
        lastSeekAt = null
        lastVersion = Long.MIN_VALUE
        lastConnection = null
    }

    private fun sync(force: Boolean) {
        if (!inRitme) return
        val h = host ?: return
        val player = h.player
        if (listening !== player) {
            listening?.removeListener(playerListener)
            player.addListener(playerListener)
            listening = player
        }
        val localNow = SystemClock.elapsedRealtime()
        val local = LocalPlayerView(
            loadedEntryId = loadedEntryId,
            positionMs = player.currentPosition,
            isAdvancing = player.isPlaying,
            playWhenReady = player.playWhenReady,
            ended = loadedEntryId != null && endedEntryId == loadedEntryId,
            speed = player.playbackParameters.speed,
        )
        val plan = RitmeSyncPlanner.plan(
            SyncInput(session.state.value.room, session.serverNow(), local, appliedKey, localNow, lastSeekAt, force),
        )
        appliedKey = plan.appliedKey
        plan.ops.forEach { execute(h, player, it, localNow) }
        handler.removeCallbacks(wake)
        plan.wakeAtServerMs?.let { handler.postDelayed(wake, (it - session.serverNow()).coerceAtLeast(0)) }
    }

    private fun execute(h: RitmePlayerHost, player: ExoPlayer, op: PlayerOp, localNow: Long) {
        when (op) {
            is PlayerOp.Load -> {
                loadedEntryId = op.entry.entryId
                endedEntryId = null
                lastSeekAt = null
                h.loadRitmeItem(RitmeEntries.toMediaMetadata(op.entry).toMediaItem(), op.startMs)
            }
            PlayerOp.Play -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            PlayerOp.Pause -> player.pause()
            is PlayerOp.SeekTo -> {
                player.seekTo(op.positionMs)
                lastSeekAt = localNow
                endedEntryId = null
            }
            is PlayerOp.SetSpeed -> player.playbackParameters = PlaybackParameters(op.speed, 1f)
            PlayerOp.Halt -> {
                player.pause()
                if (player.playbackParameters != PlaybackParameters.DEFAULT) player.playbackParameters = PlaybackParameters.DEFAULT
            }
        }
    }

    /** Android reports the end of each entry once (spec 2.6); the server ignores stale reports. */
    private fun onLocalEnded() {
        val id = loadedEntryId ?: return
        endedEntryId = id
        if (session.state.value.room?.currentEntryId == id && reportedEndFor != id && session.trackEnded(id)) {
            reportedEndFor = id
        }
    }

    override val isPlayingLocally: Boolean get() = host?.player?.playWhenReady == true

    override val isCasting: Boolean get() = host?.isCasting == true

    override fun resumeLocally() {
        val player = host?.player ?: return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        sync(force = true)
    }

    /** Pause on this device only; the planner then leaves it alone until the room changes. */
    override fun pauseLocally() {
        host?.player?.pause()
    }

    override fun playOrderQueue(): LocalPlayOrder = host?.playOrderQueue() ?: LocalPlayOrder(emptyList(), 0, 0)

    private companion object {
        const val TICK_MS = 1_000L
    }
}
```

- [ ] **Step 3: Add the fields, attach/detach and host to MusicService**

In `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt`, after the block

```kotlin
    @Inject
    lateinit var widgetManager: MusicfyWidgetManager
```

add:

```kotlin

    /** Ritme: closed unless a listen-together room is active; see ritme/RitmeGate.kt */
    @Inject
    lateinit var ritmeGate: com.example.musicfy.ritme.RitmeGate

    @Inject
    lateinit var ritmeBridge: com.example.musicfy.ritme.playback.RitmePlaybackBridge
```

In `onCreate`, replace

```kotlin
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
```

with

```kotlin
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        ritmeBridge.attach(ritmeHost)
```

In `onDestroy`, replace

```kotlin
    override fun onDestroy() {
        isRunning = false
```

with

```kotlin
    override fun onDestroy() {
        ritmeBridge.detach()
        isRunning = false
```

Directly above `override fun onDestroy() {` add the host:

```kotlin
    // Ritme: the narrow surface RitmePlaybackBridge drives while a room is active.
    private val ritmeHost = object : com.example.musicfy.ritme.playback.RitmePlayerHost {
        override val player: ExoPlayer get() = this@MusicService.player

        override val isCasting: Boolean get() = castConnectionHandler?.isCasting?.value == true

        override fun playOrderQueue(): com.example.musicfy.ritme.LocalPlayOrder {
            val p = this@MusicService.player
            val timeline = p.currentTimeline
            if (timeline.isEmpty) return com.example.musicfy.ritme.LocalPlayOrder(emptyList(), 0, 0)
            val shuffle = p.shuffleModeEnabled
            val order = ArrayList<Int>(timeline.windowCount)
            var index = timeline.getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
                order += index
                index = timeline.getNextWindowIndex(index, REPEAT_MODE_OFF, shuffle)
            }
            return com.example.musicfy.ritme.LocalPlayOrder(
                songs = order.map { p.getMediaItemAt(it).metadata },
                currentIndex = order.indexOf(p.currentMediaItemIndex).coerceAtLeast(0),
                positionMs = p.currentPosition.coerceAtLeast(0),
            )
        }

        override fun enterRitmeMode(): com.example.musicfy.ritme.playback.LocalQueueSnapshot {
            val p = this@MusicService.player
            val snapshot = com.example.musicfy.ritme.playback.LocalQueueSnapshot(
                title = queueTitle,
                items = p.mediaItems,
                index = p.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = p.currentPosition.coerceAtLeast(0),
                shuffle = p.shuffleModeEnabled,
                repeatMode = p.repeatMode,
                skipSilence = p.skipSilenceEnabled,
            )
            clearStreamFailure()
            resetQueueRunway()
            currentQueue = EmptyQueue
            clearAutomix()
            crossfadeTriggerJob?.cancel()
            crossfadeTriggerJob = null
            if (isCrossfading) cleanupCrossfade()
            p.pause()
            p.shuffleModeEnabled = false
            p.repeatMode = REPEAT_MODE_OFF
            p.skipSilenceEnabled = false
            p.playbackParameters = PlaybackParameters.DEFAULT
            queueTitle = getString(R.string.ritme_name)
            return snapshot
        }

        override fun loadRitmeItem(item: MediaItem, startPositionMs: Long) {
            val p = this@MusicService.player
            clearStreamFailure()
            p.setMediaItem(item, startPositionMs)
            p.prepare()
            p.playWhenReady = false
        }

        override fun exitRitmeMode(snapshot: com.example.musicfy.ritme.playback.LocalQueueSnapshot) {
            val p = this@MusicService.player
            val fx = audioLab.playback.value
            p.playbackParameters = PlaybackParameters(fx.speed, fx.pitch)
            p.skipSilenceEnabled = snapshot.skipSilence
            p.repeatMode = snapshot.repeatMode
            if (snapshot.items.isEmpty()) {
                p.stop()
                p.clearMediaItems()
                queueTitle = null
            } else {
                playQueue(
                    com.example.musicfy.playback.queues.ListQueue(snapshot.title, snapshot.items, snapshot.index, snapshot.positionMs),
                    playWhenReady = false,
                )
            }
            p.shuffleModeEnabled = snapshot.shuffle
        }
    }

```

- [ ] **Step 4: Add the in-room guards to MusicService**

Each edit below is exact; outside a room every guard is `false` and the code runs as before.

1. `ensureQueueRunway()` — replace `        if (!playerInitialized.value) return` with:

```kotlin
        if (!playerInitialized.value) return
        // In a Ritme room the room owns the queue: no runway, infinite queue or Autoplay.
        if (ritmeGate.isActive) return
```

2. `onPlaybackStateChanged` — replace `        if (playbackState == Player.STATE_ENDED) {` with:

```kotlin
        // In a Ritme room the end of a song is reported to the room (RitmePlaybackBridge), not repeated or extended here.
        if (playbackState == Player.STATE_ENDED && !ritmeGate.isActive) {
```

3. `failStream()` — replace `        if (dataStore.get(DontSkipOnFailureKey, false)) {` with:

```kotlin
        // In a Ritme room a failed stream never skips: the device keeps retrying and follows the room (spec 4.1).
        if (dataStore.get(DontSkipOnFailureKey, false) || ritmeGate.isActive) {
```

4. `handleLongSilenceDetected()` — replace `        if (!instantSilenceSkipEnabled.value) return` with:

```kotlin
        if (!instantSilenceSkipEnabled.value) return
        if (ritmeGate.isActive) return
```

5. `saveQueueToDisk()` — replace

```kotlin
    private fun saveQueueToDisk() {
        if (player.mediaItemCount == 0) {
```

with

```kotlin
    private fun saveQueueToDisk() {
        // The room's single item is never persisted: the saved local queue stays what comes back.
        if (ritmeGate.isActive) return
        if (player.mediaItemCount == 0) {
```

6. `scheduleCrossfade()` — replace

```kotlin
    private fun scheduleCrossfade() {
        // Crossmix plans its own hand-overs; it cancels itself when it's off or can't run
        crossmixDirector.schedule()
```

with

```kotlin
    private fun scheduleCrossfade() {
        if (ritmeGate.isActive) {
            crossfadeTriggerJob?.cancel()
            crossfadeTriggerJob = null
            return
        }
        // Crossmix plans its own hand-overs; it cancels itself when it's off or can't run
        crossmixDirector.schedule()
```

7. `CrossmixHost.crossmixBlocker()` — replace `            !crossfadeEnabled -> "Crossfade is off"` with:

```kotlin
            ritmeGate.isActive -> "Ritme: the room keeps everyone in sync"
            !crossfadeEnabled -> "Crossfade is off"
```

8. Audio FX collector in `onCreate` — replace

```kotlin
                val params = PlaybackParameters(settings.speed, settings.pitch)
```

with

```kotlin
                // In a Ritme room the speed belongs to drift correction; exitRitmeMode re-applies Audio FX.
                if (ritmeGate.isActive) return@collect
                val params = PlaybackParameters(settings.speed, settings.pitch)
```

- [ ] **Step 5: Compile, test and smoke-check the non-Ritme path**

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testUniversalFossDebugUnitTest`
Expected: PASS (all app unit tests, Ritme and existing).

Run: `./gradlew :app:assembleUniversalFossDebug`, install the APK from `app/build/outputs/apk/universalFoss/debug/` with `adb install -r <apk>`, play a song, skip, toggle shuffle and repeat, let a track end into the next one.
Expected: playback behaves exactly as before (the gate is never opened yet).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/playback app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt
git commit -m "feat(ritme-android): playback bridge follows the room through a narrow MusicService host" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Route local controls to the room

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeSessionPlayer.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeQueueSongs.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` (`playQueue`, `playNext`, `addToQueue`, session-player swap in the host)
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/PlayerConnection.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/PlayerControls.kt`, `ui/player/styles/StyleKit.kt`, `ui/player/TurntableControls.kt`, `ui/player/PlayerProgressSlider.kt`, `ui/player/LyricsScreen.kt`, `ui/component/BottomSheet.kt`, `ui/player/BottomSheetPlayer.kt`, `ui/menu/OldPlayerMenu.kt`, `ui/menu/PlayerMenu.kt`, `ui/screens/AlbumScreen.kt`, `ui/screens/search/OnlineSearchResult.kt` (all under `app/src/main/kotlin/com/example/musicfy/`)

**Interfaces:**
- Consumes: Task 8 `RitmeGate`, `RitmeLocalAction`; Task 9 `MusicService.ritmeGate`, `ritmeHost`.
- Produces:
  - `class RitmeSessionPlayer(player: Player, gate: RitmeGate) : ForwardingPlayer`
  - `fun Queue.ritmeSongs(): List<MediaMetadata?>`
  - `PlayerConnection.ritmeGate: RitmeGate`, `inline fun PlayerConnection.ritmeOr(action: RitmeLocalAction, local: () -> Unit)` (member)

- [ ] **Step 1: Write the session player and queue helper**

`app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeSessionPlayer.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.musicfy.extensions.metadata
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeLocalAction

/**
 * The media session's player while a Ritme room is active (MusicService swaps it in and out).
 * Notification buttons, headset keys, Bluetooth and Android Auto all reach the player through the
 * session, so here they become room commands via [gate] instead of changing the local player.
 * Whether a pause is a room Pause or "this device only" is decided by RitmeController, not here.
 * Next/previous stay available although the real player only holds the current entry.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RitmeSessionPlayer(player: Player, private val gate: RitmeGate) : ForwardingPlayer(player) {

    private fun routed(action: RitmeLocalAction): Boolean = gate.intercept(action)

    override fun play() { if (!routed(RitmeLocalAction.Play)) super.play() }
    override fun pause() { if (!routed(RitmeLocalAction.Pause)) super.pause() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!routed(if (playWhenReady) RitmeLocalAction.Play else RitmeLocalAction.Pause)) super.setPlayWhenReady(playWhenReady)
    }

    override fun seekTo(positionMs: Long) { if (!routed(RitmeLocalAction.Seek(positionMs))) super.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!routed(RitmeLocalAction.Seek(positionMs))) super.seekTo(mediaItemIndex, positionMs)
    }
    override fun seekToDefaultPosition() { if (!routed(RitmeLocalAction.Seek(0))) super.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!routed(RitmeLocalAction.Seek(0))) super.seekToDefaultPosition(mediaItemIndex)
    }
    override fun seekBack() {
        if (!routed(RitmeLocalAction.Seek((currentPosition - seekBackIncrement).coerceAtLeast(0)))) super.seekBack()
    }
    override fun seekForward() {
        if (!routed(RitmeLocalAction.Seek(currentPosition + seekForwardIncrement))) super.seekForward()
    }
    override fun seekToNext() { if (!routed(RitmeLocalAction.Next)) super.seekToNext() }
    override fun seekToNextMediaItem() { if (!routed(RitmeLocalAction.Next)) super.seekToNextMediaItem() }
    override fun seekToPrevious() { if (!routed(RitmeLocalAction.Previous)) super.seekToPrevious() }
    override fun seekToPreviousMediaItem() { if (!routed(RitmeLocalAction.Previous)) super.seekToPreviousMediaItem() }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (!routed(RitmeLocalAction.Shuffle)) super.setShuffleModeEnabled(shuffleModeEnabled)
    }
    override fun setRepeatMode(repeatMode: Int) { if (!routed(RitmeLocalAction.Repeat)) super.setRepeatMode(repeatMode) }
    override fun setPlaybackSpeed(speed: Float) { if (!routed(RitmeLocalAction.Speed)) super.setPlaybackSpeed(speed) }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (!routed(RitmeLocalAction.Speed)) super.setPlaybackParameters(playbackParameters)
    }

    override fun setMediaItem(mediaItem: MediaItem) { if (!routedPlay(listOf(mediaItem))) super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!routedPlay(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!routedPlay(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) { if (!routedPlay(mediaItems)) super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (!routedPlay(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!routedPlay(mediaItems.drop(startIndex.coerceAtLeast(0)))) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun addMediaItem(mediaItem: MediaItem) { if (!routedAdd(listOf(mediaItem))) super.addMediaItem(mediaItem) }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) { if (!routedAdd(listOf(mediaItem))) super.addMediaItem(index, mediaItem) }
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) { if (!routedAdd(mediaItems)) super.addMediaItems(mediaItems) }
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        if (!routedAdd(mediaItems)) super.addMediaItems(index, mediaItems)
    }

    private fun routedPlay(items: List<MediaItem>) = routed(RitmeLocalAction.PlayQueue(items.map { it.metadata }))
    private fun routedAdd(items: List<MediaItem>) = routed(RitmeLocalAction.AddItems(items.map { it.metadata }, playNext = false))

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (!gate.isActive) return commands
        return commands.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
}
```

> If the Kotlin compiler reports a signature mismatch on `setMediaItems`/`addMediaItems`, change `MutableList<MediaItem>` to `List<MediaItem>` (Media3 declares `java.util.List`; both map to it). Nothing else changes.

`app/src/main/kotlin/com/example/musicfy/ritme/RitmeQueueSongs.kt`:

```kotlin
package com.example.musicfy.ritme

import com.example.musicfy.extensions.metadata
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.playback.queues.ListQueue
import com.example.musicfy.playback.queues.Queue

/**
 * The songs a queue would start with, for RitmeLocalAction.PlayQueue: the tapped song for radio
 * queues (their preloadItem), a list from its start index, otherwise nothing (it would need a
 * network load first; the controller then says "not available").
 */
fun Queue.ritmeSongs(): List<MediaMetadata?> =
    preloadItem?.let { listOf(it) }
        ?: (this as? ListQueue)?.let { queue -> queue.items.drop(queue.startIndex.coerceAtLeast(0)).map { it.metadata } }
        ?: emptyList()
```

- [ ] **Step 2: Guard the queue entry points and swap the session player in MusicService**

1. `playQueue` — replace `        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main) + Job()` with:

```kotlin
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main) + Job()

        if (ritmeGate.isActive) {
            // In a Ritme room the room owns the queue: the songs are offered to it instead.
            ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.PlayQueue(queue.ritmeSongs()))
            return
        }
```

and add `import com.example.musicfy.ritme.ritmeSongs` to the imports.

2. Replace `    fun playNext(items: List<MediaItem>) {` with:

```kotlin
    fun playNext(items: List<MediaItem>) {
        if (ritmeGate.isActive &&
            ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.AddItems(items.map { it.metadata }, playNext = true))
        ) return
```

3. Replace `    fun addToQueue(items: List<MediaItem>) {` with:

```kotlin
    fun addToQueue(items: List<MediaItem>) {
        if (ritmeGate.isActive &&
            ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.AddItems(items.map { it.metadata }, playNext = false))
        ) return
```

4. In the `ritmeHost` object from Task 9, replace

```kotlin
            queueTitle = getString(R.string.ritme_name)
            return snapshot
```

with

```kotlin
            queueTitle = getString(R.string.ritme_name)
            useRitmeSessionPlayer(true)
            return snapshot
```

and replace

```kotlin
            val fx = audioLab.playback.value
```

with

```kotlin
            useRitmeSessionPlayer(false)
            val fx = audioLab.playback.value
```

5. Directly below the `ritmeHost` object add:

```kotlin
    /** While in a Ritme room, external controllers talk to a RitmeSessionPlayer instead of the player itself. */
    private fun useRitmeSessionPlayer(enabled: Boolean) {
        val target: Player = if (enabled) com.example.musicfy.ritme.playback.RitmeSessionPlayer(player, ritmeGate) else player
        try {
            (mediaSession as MediaSession).player = target
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Ritme: could not swap the session player")
        }
    }
```

- [ ] **Step 3: Intercept in PlayerConnection**

In `app/src/main/kotlin/com/example/musicfy/playback/PlayerConnection.kt`:

1. Directly after `    var onSkipNext: (() -> Unit)? = null` add:

```kotlin

    /** Ritme: while a room is active, local controls become room commands (RitmeController). */
    val ritmeGate: com.example.musicfy.ritme.RitmeGate get() = service.ritmeGate

    /** Runs [local] unless a Ritme room takes [action] instead. */
    inline fun ritmeOr(action: com.example.musicfy.ritme.RitmeLocalAction, local: () -> Unit) {
        if (!ritmeGate.intercept(action)) local()
    }
```

2. In the `init` block that collects `service.playerFlow`, directly after that `scope.launch { ... }` block add:

```kotlin

            scope.launch {
                // Next/previous are always offered in a room, although the player holds one item.
                ritmeGate.active.collect { runCatching { updateCanSkipPreviousAndNext() } }
            }
```

3. Add a guard as the first statement of each method below (replace the shown opening line with the opening line plus the guard):

```kotlin
    fun playNext(items: List<MediaItem>) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.AddItems(items.map { it.metadata }, playNext = true))) return
```

```kotlin
    fun addToQueue(items: List<MediaItem>) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.AddItems(items.map { it.metadata }, playNext = false))) return
```

```kotlin
    fun playQueueItem(uid: Int) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.QueueEdit)) return
```

```kotlin
    fun moveQueueItem(uid: Int, afterUid: Int) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.QueueEdit)) return
```

```kotlin
    fun playQueueItemNext(uid: Int) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.QueueEdit)) return
```

```kotlin
    fun removeQueueItems(uids: Collection<Int>) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.QueueEdit)) return
```

```kotlin
    fun togglePlayPause() {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.TogglePlayPause)) return
```

```kotlin
    fun play() {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.Play)) return
```

```kotlin
    fun pause() {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.Pause)) return
```

```kotlin
    fun seekTo(position: Long) {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.Seek(position))) return
```

```kotlin
    fun seekToNext() {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.Next)) return
```

```kotlin
    fun seekToPrevious() {
        if (ritmeGate.intercept(com.example.musicfy.ritme.RitmeLocalAction.Previous)) return
```

(`metadata` is `com.example.musicfy.extensions.metadata`, already imported in this file.) The guards only hand the intent over; `RitmeController` decides between a room command, a local pause/resume (members without control) and a toast, so no UI call site needs to know the member's permissions.

4. In `updateCanSkipPreviousAndNext()`, replace

```kotlin
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                    !window.isLive ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive &&
                    window.isDynamic ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
```

with

```kotlin
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                    !window.isLive ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ||
                    ritmeGate.isActive
            canSkipNext.value = window.isLive &&
                    window.isDynamic ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
                    ritmeGate.isActive
```

- [ ] **Step 4: Route the UI call sites that touch the player directly**

Add `import com.example.musicfy.ritme.RitmeLocalAction` to each file edited here.

1. `ui/player/PlayerControls.kt`, styled transport buttons — replace

```kotlin
            onPlayPause = {
                if (transportState.playbackState == Player.STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.playWhenReady = !playerConnection.player.playWhenReady
                }
            },
```

with

```kotlin
            onPlayPause = {
                playerConnection.ritmeOr(RitmeLocalAction.TogglePlayPause) {
                    if (transportState.playbackState == Player.STATE_ENDED) {
                        playerConnection.player.seekTo(0, 0)
                        playerConnection.player.playWhenReady = true
                    } else {
                        playerConnection.player.playWhenReady = !playerConnection.player.playWhenReady
                    }
                }
            },
```

   and the default play/pause button — replace

```kotlin
                onClick = {
                    if (transportState.playbackState == Player.STATE_ENDED) {
                        playerConnection.player.seekTo(0, 0)
                        playerConnection.player.playWhenReady = true
                    } else {
                        playerConnection.togglePlayPause()
                    }
                },
```

with

```kotlin
                onClick = {
                    playerConnection.ritmeOr(RitmeLocalAction.TogglePlayPause) {
                        if (transportState.playbackState == Player.STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.togglePlayPause()
                        }
                    }
                },
```

2. `ui/player/styles/StyleKit.kt` — replace

```kotlin
            onClick = {
                if (transport.playbackState == Player.STATE_ENDED) {
                    connection.player.seekTo(0, 0)
                    connection.player.playWhenReady = true
                } else {
                    connection.togglePlayPause()
                }
            },
```

with

```kotlin
            onClick = {
                connection.ritmeOr(RitmeLocalAction.TogglePlayPause) {
                    if (transport.playbackState == Player.STATE_ENDED) {
                        connection.player.seekTo(0, 0)
                        connection.player.playWhenReady = true
                    } else {
                        connection.togglePlayPause()
                    }
                }
            },
```

3. `ui/player/TurntableControls.kt` — replace

```kotlin
                onClick = {
                    if (transport.playbackState == Player.STATE_ENDED) {
                        connection.player.seekTo(0, 0)
                        connection.player.playWhenReady = true
                    } else {
                        connection.togglePlayPause()
                    }
                },
```

with

```kotlin
                onClick = {
                    connection.ritmeOr(RitmeLocalAction.TogglePlayPause) {
                        if (transport.playbackState == Player.STATE_ENDED) {
                            connection.player.seekTo(0, 0)
                            connection.player.playWhenReady = true
                        } else {
                            connection.togglePlayPause()
                        }
                    }
                },
```

4. `ui/player/PlayerProgressSlider.kt` — replace `                sliderPosition?.let { playerConnection.player.seekTo(it) }` with:

```kotlin
                sliderPosition?.let { position ->
                    playerConnection.ritmeOr(RitmeLocalAction.Seek(position)) { playerConnection.player.seekTo(position) }
                }
```

5. `ui/player/LyricsScreen.kt` — replace `                                            playerConnection.player.seekTo(entry.time)` with:

```kotlin
                                            playerConnection.ritmeOr(RitmeLocalAction.Seek(entry.time)) {
                                                playerConnection.player.seekTo(entry.time)
                                            }
```

6. `ui/component/BottomSheet.kt` — replace

```kotlin
                                            if (currentOffset > 0) playerConnection.player.seekToPreviousMediaItem()
                                            else playerConnection.player.seekToNext()
```

with

```kotlin
                                            if (currentOffset > 0) {
                                                playerConnection.ritmeOr(RitmeLocalAction.Previous) { playerConnection.player.seekToPreviousMediaItem() }
                                            } else {
                                                playerConnection.ritmeOr(RitmeLocalAction.Next) { playerConnection.player.seekToNext() }
                                            }
```

7. `ui/player/BottomSheetPlayer.kt` — directly above `        fun menuActionActive(` add:

```kotlin
        val ritmeActive by playerConnection.ritmeGate.active.collectAsState()
```

   (add `import androidx.compose.runtime.collectAsState` if missing). Replace the three menu actions

```kotlin
                com.example.musicfy.ui.player.studio.PlayerMenuAction.REPEAT -> playerConnection.player.toggleRepeatMode()
                com.example.musicfy.ui.player.studio.PlayerMenuAction.SHUFFLE ->
                    playerConnection.player.shuffleModeEnabled = !playerConnection.player.shuffleModeEnabled
```

with

```kotlin
                com.example.musicfy.ui.player.studio.PlayerMenuAction.REPEAT ->
                    playerConnection.ritmeOr(RitmeLocalAction.Repeat) { playerConnection.player.toggleRepeatMode() }
                com.example.musicfy.ui.player.studio.PlayerMenuAction.SHUFFLE ->
                    playerConnection.ritmeOr(RitmeLocalAction.Shuffle) {
                        playerConnection.player.shuffleModeEnabled = !playerConnection.player.shuffleModeEnabled
                    }
```

   and replace `                com.example.musicfy.ui.player.studio.PlayerMenuAction.SPEED -> showPlaybackSpeed = true` with:

```kotlin
                com.example.musicfy.ui.player.studio.PlayerMenuAction.SPEED ->
                    playerConnection.ritmeOr(RitmeLocalAction.Speed) { showPlaybackSpeed = true }
```

   In the queue page's shuffle button replace

```kotlin
                                        onClick = { playerConnection.player.shuffleModeEnabled = !shuffleOn },
                                        hasShadow = false,
                                        modifier = Modifier.graphicsLayer { alpha = queuePageProgress },
```

with

```kotlin
                                        onClick = {
                                            playerConnection.ritmeOr(RitmeLocalAction.Shuffle) { playerConnection.player.shuffleModeEnabled = !shuffleOn }
                                        },
                                        hasShadow = false,
                                        // dimmed in a Ritme room (spec 3.4)
                                        modifier = Modifier.graphicsLayer { alpha = queuePageProgress * (if (ritmeActive) 0.38f else 1f) },
```

8. `ui/menu/OldPlayerMenu.kt` — replace

```kotlin
                                onClick = {
                                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                                    onDismiss()
                                }
```

with

```kotlin
                                onClick = {
                                    playerConnection.ritmeOr(RitmeLocalAction.Shuffle) {
                                        playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                                    }
                                    onDismiss()
                                }
```

9. `ui/menu/PlayerMenu.kt` (`TempoPitchDialog`) — replace

```kotlin
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters =
            PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
    }
```

with

```kotlin
    val updatePlaybackParameters = {
        playerConnection.ritmeOr(RitmeLocalAction.Speed) {
            playerConnection.player.playbackParameters =
                PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
        }
    }
```

10. `ui/screens/AlbumScreen.kt` — replace

```kotlin
                        isThisAlbum && isPlaying -> playerConnection.player.pause()
                        isThisAlbum -> playerConnection.player.play()
```

with

```kotlin
                        isThisAlbum && isPlaying -> playerConnection.ritmeOr(RitmeLocalAction.Pause) { playerConnection.player.pause() }
                        isThisAlbum -> playerConnection.ritmeOr(RitmeLocalAction.Play) { playerConnection.player.play() }
```

11. `ui/screens/search/OnlineSearchResult.kt` — replace `                    playerConnection.player.togglePlayPause()` with:

```kotlin
                    playerConnection.ritmeOr(RitmeLocalAction.TogglePlayPause) { playerConnection.player.togglePlayPause() }
```

- [ ] **Step 5: Compile and test**

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testUniversalFossDebugUnitTest`
Expected: PASS.

Run: `grep -rn "playerConnection.player.\(seekTo\|play()\|pause()\|playWhenReady =\|seekToNext\|seekToPrevious\|shuffleModeEnabled =\|playbackParameters =\|togglePlayPause\|toggleRepeatMode\)" app/src/main/kotlin | grep -v ritmeOr`
Expected: only lines that are inside a `ritmeOr { ... }` block from this step (check each remaining hit by eye; any new direct call must be wrapped the same way).

Install the debug APK and repeat the Task 9 smoke check (play, skip, shuffle, repeat, speed, seek from notification and lyrics): unchanged outside a room.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeSessionPlayer.kt app/src/main/kotlin/com/example/musicfy/ritme/RitmeQueueSongs.kt app/src/main/kotlin/com/example/musicfy/playback app/src/main/kotlin/com/example/musicfy/ui
git commit -m "feat(ritme-android): route player, notification and headset controls through the Ritme gate" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 11: Start/join and room sheets

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (ZXing)
- Create: `app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeComponents.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeEntrySheet.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeQrCode.kt`
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Modify: `app/src/main/kotlin/com/example/musicfy/MainActivity.kt` (mount the opener)

**Interfaces:**
- Consumes: Task 1 `RitmeConfig`, `RitmeLinks`, `RitmeDisplayNameKey`; Task 3 `RitmePermissions`, `RitmePhase`, `ConnectionStatus`; Task 5 `RitmeEntries.isPlayable`; Task 6 `RitmeSession`; Task 8 `RitmeController`, `RitmeSheetRequest`, `RitmeTexts`; app `PopupSheetState`, `PopupSheetHandle`, `LocalZoomOutOverlayState`, `LocalPlayerBottomSheetState`, `MenuSurface`, `MenuRowSurface`, `rememberPreference`, `UsernameKey`, `Screens.Search`.
- Produces:
  - `interface RitmeEntryPoint { fun ritmeSession(): RitmeSession; fun ritmeController(): RitmeController }`
  - `class RitmeUi(val session: RitmeSession, val controller: RitmeController)`, `@Composable fun rememberRitme(): RitmeUi`
  - `@Composable fun RitmeSheetOpener(navController: NavController)`
  - `fun PopupSheetState.showRitmeEntrySheet(ritme: RitmeUi, code: String?, startWith: List<MediaMetadata?>?)`, `fun PopupSheetState.showRitmeRoomSheet(ritme: RitmeUi, navController: NavController)`
  - `@Composable internal fun RitmeRoomContent(ritme: RitmeUi, onAddSongs: () -> Unit, onLeft: () -> Unit, modifier: Modifier = Modifier, bottomPadding: Dp = 16.dp)`
  - `@Composable fun RitmeQueuePage(onClose: () -> Unit, navController: NavController, bottomPadding: Dp, modifier: Modifier = Modifier)` (wired in Task 12)
  - `@Composable internal fun RitmeButton(...)`, `RitmeTextField(...)`, `RitmeAvatar(...)`, `RitmeQrCode(text, modifier)`

- [ ] **Step 1: Add ZXing**

In `gradle/libs.versions.toml`, under `[versions]` after `webkit = "1.12.1"` add `zxing = "3.5.3"`, and under `[libraries]` after the `junit = ...` line add:

```toml
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
```

In `app/build.gradle.kts` dependencies, after `implementation(project(":modules:ritme-protocol"))` add:

```kotlin
    // Ritme room QR code (spec 3.3); pure Java, no Play Services
    implementation(libs.zxing.core)
```

- [ ] **Step 2: Add the sheet strings**

Insert before `</resources>` in `app/src/main/res/values/ritme_strings.xml`:

```xml

    <string name="ritme_start">Start Ritme</string>
    <string name="ritme_start_desc">Listen together with friends, in sync</string>
    <string name="ritme_start_with_this">Start Ritme with this</string>
    <string name="ritme_join">Join</string>
    <string name="ritme_join_title">Join a Ritme</string>
    <string name="ritme_code_label">6-character code</string>
    <string name="ritme_display_name">Display name</string>
    <string name="ritme_connecting">Connecting…</string>
    <string name="ritme_retry">Try again</string>
    <string name="ritme_room_title" translatable="false">Ritme · %1$s</string>
    <string name="ritme_share">Share</string>
    <string name="ritme_qr" translatable="false">QR</string>
    <string name="ritme_share_text">Listen with me on Ritme: %1$s</string>
    <plurals name="ritme_members">
        <item quantity="one">%d member</item>
        <item quantity="other">%d members</item>
    </plurals>
    <string name="ritme_now_playing">Now playing</string>
    <string name="ritme_up_next">Up next</string>
    <string name="ritme_added_by">by %1$s</string>
    <string name="ritme_you">You</string>
    <string name="ritme_add_songs">+ Add songs</string>
    <string name="ritme_guests_can_control">Members can control</string>
    <string name="ritme_leave">Leave Ritme</string>
    <string name="ritme_end">End Ritme</string>
    <string name="ritme_end_confirm_title">End this Ritme for everyone?</string>
    <string name="ritme_cancel">Cancel</string>
    <string name="ritme_make_host">Make host</string>
    <string name="ritme_kick">Remove from Ritme</string>
    <string name="ritme_unsupported">Not supported yet</string>
    <string name="ritme_empty_queue">Nothing is playing. Add a song to start.</string>
```

Insert before `</resources>` in `app/src/main/res/values-in/ritme_strings.xml`:

```xml

    <string name="ritme_start">Mulai Ritme</string>
    <string name="ritme_start_desc">Dengar bareng teman, sinkron</string>
    <string name="ritme_start_with_this">Mulai Ritme dengan ini</string>
    <string name="ritme_join">Gabung</string>
    <string name="ritme_join_title">Gabung Ritme</string>
    <string name="ritme_code_label">Kode 6 karakter</string>
    <string name="ritme_display_name">Nama tampilan</string>
    <string name="ritme_connecting">Menghubungkan…</string>
    <string name="ritme_retry">Coba lagi</string>
    <string name="ritme_share">Bagikan</string>
    <string name="ritme_share_text">Dengar bareng aku di Ritme: %1$s</string>
    <plurals name="ritme_members">
        <item quantity="other">%d anggota</item>
    </plurals>
    <string name="ritme_now_playing">Sedang diputar</string>
    <string name="ritme_up_next">Berikutnya</string>
    <string name="ritme_added_by">oleh %1$s</string>
    <string name="ritme_you">Kamu</string>
    <string name="ritme_add_songs">+ Tambah lagu</string>
    <string name="ritme_guests_can_control">Anggota boleh kontrol</string>
    <string name="ritme_leave">Keluar Ritme</string>
    <string name="ritme_end">Akhiri Ritme</string>
    <string name="ritme_end_confirm_title">Akhiri Ritme ini untuk semua?</string>
    <string name="ritme_cancel">Batal</string>
    <string name="ritme_make_host">Jadikan host</string>
    <string name="ritme_kick">Keluarkan</string>
    <string name="ritme_unsupported">Belum didukung</string>
    <string name="ritme_empty_queue">Belum ada yang diputar. Tambah lagu untuk mulai.</string>
```

- [ ] **Step 3: Write the entry point, UI plumbing and components**

`app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt`:

```kotlin
package com.example.musicfy.di

import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.session.RitmeSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** For Ritme's sheets, pill and menu rows, which live outside any Hilt view model. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RitmeEntryPoint {
    fun ritmeSession(): RitmeSession
    fun ritmeController(): RitmeController
}
```

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.example.musicfy.di.RitmeEntryPoint
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.RitmeSheetRequest
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.screens.Screens
import dagger.hilt.android.EntryPointAccessors

/** The two singletons Ritme's UI talks to. */
@Stable
class RitmeUi(val session: RitmeSession, val controller: RitmeController)

@Composable
fun rememberRitme(): RitmeUi {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        val entry = EntryPointAccessors.fromApplication(app, RitmeEntryPoint::class.java)
        RitmeUi(entry.ritmeSession(), entry.ritmeController())
    }
}

/** Shows whatever sheet RitmeController asks for (player menu, song menus, deep links). Mounted once in MainActivity. */
@Composable
fun RitmeSheetOpener(navController: NavController) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val popup = LocalZoomOutOverlayState.current
    val request by ritme.controller.sheetRequest.collectAsState()
    LaunchedEffect(request?.id) {
        val current = request ?: return@LaunchedEffect
        ritme.controller.consumeSheetRequest(current.id)
        when (val kind = current.kind) {
            RitmeSheetRequest.Kind.Room -> popup.showRitmeRoomSheet(ritme, navController)
            is RitmeSheetRequest.Kind.Entry -> popup.showRitmeEntrySheet(ritme, kind.code, kind.startWith)
        }
    }
}

fun PopupSheetState.showRitmeEntrySheet(ritme: RitmeUi, code: String?, startWith: List<MediaMetadata?>?) {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        onClosed = {
            // Closed while still connecting: give that attempt up. After a failure: back to idle.
            when (ritme.session.state.value.phase) {
                RitmePhase.CONNECTING -> ritme.session.leave()
                RitmePhase.ENDED -> ritme.session.dismiss()
                else -> Unit
            }
        },
    ) {
        RitmeEntryContent(
            ritme = ritme,
            initialCode = code,
            startWith = startWith,
            onJoined = {
                handle?.dismiss()
                ritme.controller.openSheet()
            },
        )
    }
}

fun PopupSheetState.showRitmeRoomSheet(ritme: RitmeUi, navController: NavController) {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle {
        val playerSheet = LocalPlayerBottomSheetState.current
        RitmeRoomContent(
            ritme = ritme,
            onAddSongs = {
                handle?.dismiss()
                if (playerSheet.isExpanded) playerSheet.collapseSoft()
                // In a room, tapping a search result adds it to the room (RitmeController, PlayQueue).
                navController.navigate(Screens.Search.route)
            },
            onLeft = { handle?.dismiss() },
        )
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeComponents.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.musicfy.ui.player.menu.MenuRowSurface

private val Destructive = Color(0xFFEE5A52)

/** Compact 46 dp sheet button (docs/popup-sheet.skill.md). Primary = white, others on MenuRowSurface. */
@Composable
internal fun RitmeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val container = if (primary) Color.White else MenuRowSurface
    val content = when {
        primary -> Color.Black
        destructive -> Destructive
        else -> Color.White
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = text,
            color = content.copy(alpha = if (enabled) 1f else 0.6f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun RitmeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = Color.White,
            cursorColor = Color.White,
            focusedContainerColor = MenuRowSurface,
            unfocusedContainerColor = MenuRowSurface,
            disabledContainerColor = MenuRowSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Initial in a circle; [highlighted] (the host) is drawn inverted. */
@Composable
internal fun RitmeAvatar(name: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (highlighted) Color.White else MenuRowSurface),
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            color = if (highlighted) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeQrCode.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The room link as a QR code (spec 3.3). Black on white regardless of theme on purpose: phone
 * cameras need the contrast to scan it (the one allowed exception to the sheet palette).
 */
@Composable
internal fun RitmeQrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.aspectRatio(1f).background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
```

- [ ] **Step 4: Write the start/join sheet**

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeEntrySheet.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ritme.RitmeTexts
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.utils.rememberPreference

/**
 * Start a Ritme (with the current queue, or [startWith]) or join one by code (spec 3.2). The
 * display name is asked once and remembered. Errors show inline with "Try again" (spec 4.1); the
 * local queue is untouched until the server welcomes us.
 */
@Composable
internal fun RitmeEntryContent(
    ritme: RitmeUi,
    initialCode: String?,
    startWith: List<MediaMetadata?>?,
    onJoined: () -> Unit,
) {
    val state by ritme.session.state.collectAsState()
    val (savedName, setSavedName) = rememberPreference(RitmeDisplayNameKey, "")
    val (username) = rememberPreference(UsernameKey, "")
    var name by rememberSaveable { mutableStateOf(savedName.ifBlank { username }.take(RitmeLinks.MAX_NAME_LENGTH)) }
    var code by rememberSaveable { mutableStateOf(initialCode.orEmpty()) }
    var joining by rememberSaveable { mutableStateOf(initialCode != null) }

    LaunchedEffect(state.phase) {
        if (state.phase == RitmePhase.IN_ROOM) onJoined()
    }

    val busy = state.phase == RitmePhase.CONNECTING
    val error = if (state.phase == RitmePhase.ENDED) RitmeTexts.entryError(state.ended) else null
    val cleanName = RitmeLinks.cleanDisplayName(name)
    val cleanCode = RitmeLinks.normalizeCode(code)
    val canSubmit = !busy && cleanName != null && (!joining || cleanCode != null)
    val submit: () -> Unit = {
        if (canSubmit && cleanName != null) {
            setSavedName(cleanName)
            if (joining) cleanCode?.let { ritme.controller.joinRoom(it, cleanName) } else ritme.controller.startRoom(cleanName, startWith)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(if (joining) R.string.ritme_join_title else R.string.ritme_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(if (startWith != null) R.string.ritme_start_with_this else R.string.ritme_start_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
        RitmeTextField(
            value = name,
            onValueChange = { name = it.take(RitmeLinks.MAX_NAME_LENGTH) },
            label = stringResource(R.string.ritme_display_name),
            enabled = !busy,
            onDone = submit,
        )
        if (joining) {
            RitmeTextField(
                value = code,
                onValueChange = { input -> code = input.uppercase().filter { it.isLetterOrDigit() }.take(RitmeLinks.CODE_LENGTH) },
                label = stringResource(R.string.ritme_code_label),
                enabled = !busy,
                capitalization = KeyboardCapitalization.Characters,
                onDone = submit,
            )
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ritme_connecting))
            }
        }
        error?.let { Text(text = stringResource(it), color = Color(0xFFEE5A52)) }
        RitmeButton(
            text = stringResource(
                when {
                    error != null -> R.string.ritme_retry
                    joining -> R.string.ritme_join
                    else -> R.string.ritme_start
                },
            ),
            enabled = canSubmit,
            primary = true,
            onClick = submit,
        )
        if (startWith == null) {
            RitmeButton(
                text = stringResource(if (joining) R.string.ritme_start else R.string.ritme_join_title),
                enabled = !busy,
                onClick = { joining = !joining },
            )
        }
    }
}
```

- [ ] **Step 5: Write the room sheet**

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.example.musicfy.R
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ui.component.LocalPlayerBottomSheetState
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.player.menu.MenuRowSurface
import com.example.musicfy.ui.player.menu.MenuSurface
import com.example.musicfy.ui.screens.Screens

/**
 * The room (spec 3.3): code with share/QR, members, now playing, up next, add songs, host
 * settings, leave/end. Controls the member is not allowed to use are hidden (spec 2.3); the server
 * checks again. Everything shown comes from the latest server snapshot.
 */
@Composable
internal fun RitmeRoomContent(
    ritme: RitmeUi,
    onAddSongs: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
) {
    val state by ritme.session.state.collectAsState()
    val room = state.room
    LaunchedEffect(room == null) {
        if (room == null) onLeft()
    }
    if (room == null) return
    val me = state.memberId
    val isHost = RitmePermissions.isHost(room, me)
    val canControl = RitmePermissions.canControl(room, me)
    val queue = remember(room) { RitmePermissions.split(room) }
    val names = remember(room.members) { room.members.associate { it.id to it.name } }
    val popup = LocalZoomOutOverlayState.current
    val context = LocalContext.current
    val link = remember(room.code) { ritme.controller.shareLink(room.code) }
    val you = stringResource(R.string.ritme_you)
    fun addedBy(entry: QueueEntry): String = if (entry.addedBy == me) you else names[entry.addedBy] ?: "?"

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ritme_room_title, room.code),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.connection == ConnectionStatus.RECONNECTING) {
                        Text(
                            text = stringResource(R.string.ritme_reconnecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                }
                IconButton(onClick = { shareRoom(context, link) }) {
                    Icon(painterResource(R.drawable.share), contentDescription = stringResource(R.string.ritme_share))
                }
                IconButton(onClick = { popup.show { RitmeQrContent(link, room.code) } }) {
                    Text(stringResource(R.string.ritme_qr), fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            MembersRow(room = room, me = me, isHost = isHost, onMember = { popup.showMemberActions(ritme, it) })
        }
        item { SectionTitle(stringResource(R.string.ritme_now_playing)) }
        item {
            val current = queue.current
            if (current == null) {
                Text(
                    text = stringResource(R.string.ritme_empty_queue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )
            } else {
                EntryRow(
                    entry = current,
                    addedBy = addedBy(current),
                    isCurrent = true,
                    canPlay = false,
                    canRemove = RitmePermissions.canRemove(room, me, current),
                    canMoveUp = false,
                    canMoveDown = false,
                    onPlay = {},
                    onRemove = { ritme.session.remove(current.entryId) },
                    onMoveUp = {},
                    onMoveDown = {},
                )
            }
        }
        if (queue.upcoming.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.ritme_up_next)) }
        }
        items(queue.upcoming, key = { it.entryId }) { entry ->
            val up = if (canControl) RitmePermissions.moveUpTarget(room, entry.entryId) else null
            val down = if (canControl) RitmePermissions.moveDownTarget(room, entry.entryId) else null
            EntryRow(
                entry = entry,
                addedBy = addedBy(entry),
                isCurrent = false,
                canPlay = canControl,
                canRemove = RitmePermissions.canRemove(room, me, entry),
                canMoveUp = up != null,
                canMoveDown = down != null,
                onPlay = { ritme.session.playEntry(entry.entryId) },
                onRemove = { ritme.session.remove(entry.entryId) },
                onMoveUp = { up?.let { ritme.session.move(entry.entryId, it.afterEntryId) } },
                onMoveDown = { down?.let { ritme.session.move(entry.entryId, it.afterEntryId) } },
            )
        }
        item {
            RitmeButton(
                text = stringResource(R.string.ritme_add_songs),
                onClick = onAddSongs,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (isHost) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MenuRowSurface)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.ritme_guests_can_control), modifier = Modifier.weight(1f))
                    Switch(
                        checked = room.settings.guestsCanControl,
                        onCheckedChange = { ritme.session.setGuestsCanControl(it) },
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RitmeButton(text = stringResource(R.string.ritme_leave), onClick = { ritme.session.leave() })
                if (isHost) {
                    RitmeButton(text = stringResource(R.string.ritme_end), destructive = true, onClick = { popup.showEndConfirm(ritme) })
                }
            }
        }
    }
}

/** The room in place of QueueScreen while a Ritme room is active (spec 3.4). */
@Composable
fun RitmeQueuePage(onClose: () -> Unit, navController: NavController, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val ritme = rememberRitme()
    val playerSheet = LocalPlayerBottomSheetState.current
    Box(
        modifier
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        RitmeRoomContent(
            ritme = ritme,
            onAddSongs = {
                onClose()
                playerSheet.collapseSoft()
                navController.navigate(Screens.Search.route)
            },
            onLeft = onClose,
            bottomPadding = bottomPadding,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalContentColor.current.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MembersRow(room: RoomState, me: String?, isHost: Boolean, onMember: (Member) -> Unit) {
    val connected = room.members.count { it.connected }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = pluralStringResource(R.plurals.ritme_members, connected, connected),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            room.members.forEach { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(56.dp)
                        .alpha(if (member.connected) 1f else 0.4f)
                        .clip(RoundedCornerShape(10.dp))
                        // Host only: tap someone else for "Make host" / "Remove".
                        .clickable(enabled = isHost && member.id != me) { onMember(member) }
                        .padding(vertical = 4.dp),
                ) {
                    RitmeAvatar(name = member.name, highlighted = member.id == room.hostId)
                    Text(
                        text = if (member.id == me) stringResource(R.string.ritme_you) else member.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: QueueEntry,
    addedBy: String,
    isCurrent: Boolean,
    canPlay: Boolean,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val playable = RitmeEntries.isPlayable(entry)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MenuRowSurface)
            .clickable(enabled = canPlay && playable, onClick = onPlay)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = entry.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MenuSurface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = if (playable) {
                    "${entry.artist} · ${stringResource(R.string.ritme_added_by, addedBy)}"
                } else {
                    "${stringResource(R.string.ritme_unsupported)} · ${stringResource(R.string.ritme_added_by, addedBy)}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
        if (canMoveUp) {
            IconButton(onClick = onMoveUp) { Icon(painterResource(R.drawable.arrow_upward), contentDescription = null) }
        }
        if (canMoveDown) {
            IconButton(onClick = onMoveDown) { Icon(painterResource(R.drawable.arrow_downward), contentDescription = null) }
        }
        if (canRemove) {
            IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.close), contentDescription = null) }
        }
    }
}

@Composable
private fun RitmeQrContent(link: String, code: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        Text(stringResource(R.string.ritme_room_title, code), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        RitmeQrCode(text = link, modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(12.dp))
        Text(link, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
    }
}

private fun PopupSheetState.showMemberActions(ritme: RitmeUi, member: Member) {
    val popup = this
    show {
        Text(
            text = member.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        RitmeButton(
            text = stringResource(R.string.ritme_make_host),
            enabled = member.connected,
            onClick = {
                ritme.session.transferHost(member.id)
                popup.dismiss()
            },
        )
        Spacer(Modifier.height(8.dp))
        RitmeButton(
            text = stringResource(R.string.ritme_kick),
            destructive = true,
            onClick = {
                ritme.session.kick(member.id)
                popup.dismiss()
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

private fun PopupSheetState.showEndConfirm(ritme: RitmeUi) {
    val popup = this
    show {
        Text(
            text = stringResource(R.string.ritme_end_confirm_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        RitmeButton(
            text = stringResource(R.string.ritme_end),
            destructive = true,
            onClick = {
                ritme.session.endRoom()
                popup.dismiss()
            },
        )
        Spacer(Modifier.height(8.dp))
        RitmeButton(text = stringResource(R.string.ritme_cancel), onClick = { popup.dismiss() })
        Spacer(Modifier.height(16.dp))
    }
}

private fun shareRoom(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.ritme_share_text, link))
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
```

- [ ] **Step 6: Mount the opener in MainActivity**

In `app/src/main/kotlin/com/example/musicfy/MainActivity.kt`, replace

```kotlin
                    com.example.musicfy.ui.screens.update.UpdateNotificationOpener(
                        requests = openUpdateRequests,
                    )
```

with

```kotlin
                    com.example.musicfy.ui.screens.update.UpdateNotificationOpener(
                        requests = openUpdateRequests,
                    )
                    com.example.musicfy.ui.screens.ritme.RitmeSheetOpener(navController = navController)
```

- [ ] **Step 7: Compile and check on a device**

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.ritme.*"`
Expected: PASS.

The sheets have no entry point yet (Task 12); they are exercised on the device in Task 12 Step 6.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/main/kotlin/com/example/musicfy/MainActivity.kt
git commit -m "feat(ritme-android): start/join and room sheets with share and QR" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 12: Entry points, pill and the in-room queue page

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/studio/PlayerMenuActions.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/BottomSheetPlayer.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/menu/PlayerMenu.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/menu/SongMenu.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/menu/YouTubeSongMenu.kt`
- Modify: `app/src/main/res/values/ritme_strings.xml`

**Interfaces:**
- Consumes: Task 10 `PlayerConnection.ritmeGate`, `ritmeActive` in `BottomSheetPlayer`; Task 11 `rememberRitme()`, `RitmeQueuePage`; Task 8 `RitmeController.openSheet()`, `openStartWith(...)`.
- Produces: `@Composable fun RitmePill(alphaProvider: () -> Float, modifier: Modifier = Modifier)`; `PlayerMenuAction.RITME`.

- [ ] **Step 1: Write the pill**

Insert before `</resources>` in `app/src/main/res/values/ritme_strings.xml`:

```xml

    <string name="ritme_pill" translatable="false">Ritme · %1$d</string>
```

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.net.ConnectionStatus

/**
 * "Ritme · N" (connected members), or "Reconnecting…" (spec 3.4). Tapping it opens the room
 * sheet. [alphaProvider] fades it with the player sheet; it is not clickable while invisible.
 */
@Composable
fun RitmePill(alphaProvider: () -> Float, modifier: Modifier = Modifier) {
    if (!RitmeConfig.ENABLED) return
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val visible by remember { derivedStateOf { alphaProvider() > 0.05f } }
    if (!state.inRoom || room == null || !visible) return
    val text = if (state.connection == ConnectionStatus.RECONNECTING) {
        stringResource(R.string.ritme_reconnecting)
    } else {
        stringResource(R.string.ritme_pill, room.members.count { it.connected })
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { alpha = alphaProvider() }
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable { ritme.controller.openSheet() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(painterResource(R.drawable.group), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
```

- [ ] **Step 2: Player menu action and the full player**

In `ui/player/studio/PlayerMenuActions.kt` replace `    OUTPUT("Device output", R.drawable.cast);` with:

```kotlin
    OUTPUT("Device output", R.drawable.cast),
    RITME("Ritme", R.drawable.group);
```

In `ui/player/BottomSheetPlayer.kt`:

1. Directly below the `val ritmeActive by ...` line added in Task 10 add:

```kotlin
        val ritme = com.example.musicfy.ui.screens.ritme.rememberRitme()
```

2. Replace `                com.example.musicfy.ui.player.studio.PlayerMenuAction.OUTPUT -> showDeviceOutput = true` with:

```kotlin
                com.example.musicfy.ui.player.studio.PlayerMenuAction.OUTPUT -> showDeviceOutput = true
                com.example.musicfy.ui.player.studio.PlayerMenuAction.RITME -> ritme.controller.openSheet()
```

3. Replace the queue page opening

```kotlin
            if (queuePageMounted && sheetShowing) {
                QueueScreen(
```

with

```kotlin
            if (queuePageMounted && sheetShowing && ritmeActive) {
                // In a Ritme room the queue page shows the room's queue (spec 3.4).
                com.example.musicfy.ui.screens.ritme.RitmeQueuePage(
                    onClose = { transition.close() },
                    navController = navController,
                    bottomPadding = lyricsBottomInset + QueueButtonColumnHeight,
                    modifier = Modifier
                        .fillMaxSize()
                        .pageTransitionLayer(PlayerPage.QUEUE, transition, morph) { queuePageProgress },
                )
            } else if (queuePageMounted && sheetShowing) {
                QueueScreen(
```

4. In `sharedContent`, replace

```kotlin
                    headerTitleY = statusBarTopInset + 38.dp,
                    lyricsProgressProvider = { lyricsProgress },
                    sheetProgressProvider = progressProvider,
                )
            },
```

with

```kotlin
                    headerTitleY = statusBarTopInset + 38.dp,
                    lyricsProgressProvider = { lyricsProgress },
                    sheetProgressProvider = progressProvider,
                )
                // "Ritme · N" at the top of the open player (spec 3.4)
                com.example.musicfy.ui.screens.ritme.RitmePill(
                    alphaProvider = { ((progressProvider() - 0.5f) * 2f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusBarTopInset + 4.dp),
                )
            },
```

- [ ] **Step 3: Pill above the mini player**

In `MainActivity.kt` replace

```kotlin
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                            modifier = Modifier.launchRise(order = 3),
                                        )
```

with

```kotlin
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                            modifier = Modifier.launchRise(order = 3),
                                        )

                                        // "Ritme · N" just above the mini player while the player is collapsed (spec 3.4)
                                        com.example.musicfy.ui.screens.ritme.RitmePill(
                                            alphaProvider = {
                                                if (playerBottomSheetState.isDismissed) 0f
                                                else (1f - playerBottomSheetState.progress / 0.3f).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = bottomInset + navPadding + MiniPlayerHeight + 8.dp),
                                        )
```

- [ ] **Step 4: Menu rows**

`ui/menu/PlayerMenu.kt` — replace

```kotlin
    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
```

with

```kotlin
    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
    val ritme = com.example.musicfy.ui.screens.ritme.rememberRitme()
```

and replace

```kotlin
                items = buildList {
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
```

with

```kotlin
                items = buildList {
                    if (com.example.musicfy.ritme.RitmeConfig.ENABLED) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.ritme_name)) },
                                description = { Text(text = stringResource(R.string.ritme_start_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.group),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    ritme.controller.openSheet()
                                    onDismiss()
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
```

`ui/menu/SongMenu.kt` — add `import com.example.musicfy.models.toMediaMetadata` if missing; replace

```kotlin
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = database.song(originalSong.id).collectAsState(initial = originalSong)
```

with

```kotlin
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = database.song(originalSong.id).collectAsState(initial = originalSong)
    val ritme = com.example.musicfy.ui.screens.ritme.rememberRitme()
    val ritmeActive by playerConnection.ritmeGate.active.collectAsState()
```

and replace

```kotlin
                            onClick = {
                                onDismiss()
                                playerConnection.addToQueue(song.toMediaItem())
                            }
                        )
                    } else null
                )
```

with

```kotlin
                            onClick = {
                                onDismiss()
                                playerConnection.addToQueue(song.toMediaItem())
                            }
                        )
                    } else null,
                    if (com.example.musicfy.ritme.RitmeConfig.ENABLED && !ritmeActive) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.ritme_start_with_this)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.group),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                ritme.controller.openStartWith(listOf(song.toMediaMetadata()))
                            }
                        )
                    } else null
                )
```

`ui/menu/YouTubeSongMenu.kt` — add `import com.example.musicfy.models.toMediaMetadata` if missing; replace

```kotlin
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(song.id).collectAsState(initial = null)
```

with

```kotlin
    val playerConnection = LocalPlayerConnection.current ?: return
    val librarySong by database.song(song.id).collectAsState(initial = null)
    val ritme = com.example.musicfy.ui.screens.ritme.rememberRitme()
    val ritmeActive by playerConnection.ritmeGate.active.collectAsState()
```

and replace

```kotlin
                            onClick = {
                                playerConnection.addToQueue(song.toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null
                )
```

with

```kotlin
                            onClick = {
                                playerConnection.addToQueue(song.toMediaItem())
                                onDismiss()
                            }
                        )
                    } else null,
                    if (com.example.musicfy.ritme.RitmeConfig.ENABLED && !ritmeActive) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.ritme_start_with_this)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.group),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                ritme.controller.openStartWith(listOf(song.toMediaMetadata()))
                                onDismiss()
                            }
                        )
                    } else null
                )
```

(While in a room the existing "Play next"/"Add to queue" rows already go to the room through `PlayerConnection`.)

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileUniversalFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testUniversalFossDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: First end-to-end check (one device + one browser tab)**

Run: `./gradlew :app:assembleUniversalFossDebug`, then `adb install -r` the APK from `app/build/outputs/apk/universalFoss/debug/`.

1. Play any YouTube song, open the player's "⋯" menu → **Ritme** → enter a name → **Start Ritme**.
   Expected: room sheet "Ritme · XXXXXX", toast "Your queue is saved…", the song keeps playing from about the same position after ~1.5 s, pill "Ritme · 1" visible.
2. Open `https://ritme.dubeli.id/r/XXXXXX` in a desktop browser, join with another name, tap "Tap to listen".
   Expected: pill shows "Ritme · 2"; both play the same position.
3. On the phone: pause from the notification, resume from a headset/notification, skip next from the notification, seek with the slider.
   Expected: every action shows up in the browser; the phone never drifts audibly.
4. Browser: add a song. Expected: it appears under "Up next" on the phone.
5. Phone: **Leave Ritme**. Expected: the earlier local queue is back, paused; shuffle/repeat/speed as before.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ui app/src/main/kotlin/com/example/musicfy/MainActivity.kt app/src/main/res/values/ritme_strings.xml
git commit -m "feat(ritme-android): Ritme entry points, pill and room queue page" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Settings and deep links

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/settings/RitmeSettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/NavigationBuilder.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/MainActivity.kt` (`SubSettingsRoutes`, controller injection, `handleDeepLinkIntent`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/settings/MusicfySettingsScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`

**Interfaces:**
- Consumes: Task 1 `RitmeLinks`, `RitmeConfig`, prefs keys, `${ritmeHost}`; Task 8 `RitmeController.openJoin(code)`.
- Produces: route `"ritme_settings"`; `@Composable fun RitmeSettingsScreen(navController: NavController)`.

- [ ] **Step 1: Strings**

Insert before `</resources>` in `values/ritme_strings.xml`:

```xml

    <string name="ritme_server_url">Server URL</string>
    <string name="ritme_server_url_reset">Restore default</string>
    <string name="ritme_settings_note">Changes apply to the next Ritme you start or join.</string>
```

Insert before `</resources>` in `values-in/ritme_strings.xml`:

```xml

    <string name="ritme_server_url">URL server</string>
    <string name="ritme_server_url_reset">Kembalikan default</string>
    <string name="ritme_settings_note">Perubahan berlaku untuk Ritme berikutnya yang kamu mulai atau ikuti.</string>
```

- [ ] **Step 2: Settings screen**

`app/src/main/kotlin/com/example/musicfy/ui/screens/settings/RitmeSettingsScreen.kt`:

```kotlin
package com.example.musicfy.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.RitmeServerUrlKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ui.component.EditTextPreference
import com.example.musicfy.ui.component.PreferenceEntry
import com.example.musicfy.ui.component.SubSettingsScaffold
import com.example.musicfy.utils.rememberPreference

/** Ritme settings (spec 3.5): server URL for self-hosting, with "Restore default", and display name. */
@Composable
fun RitmeSettingsScreen(navController: NavController) {
    val (serverUrl, setServerUrl) = rememberPreference(RitmeServerUrlKey, "")
    val (displayName, setDisplayName) = rememberPreference(RitmeDisplayNameKey, "")
    val (username) = rememberPreference(UsernameKey, "")
    val shownUrl = RitmeLinks.normalizeServerUrl(serverUrl) ?: RitmeConfig.DEFAULT_SERVER_URL

    SubSettingsScaffold(
        title = stringResource(R.string.ritme_name),
        onBack = { navController.navigateUp() },
    ) {
        EditTextPreference(
            title = { Text(stringResource(R.string.ritme_server_url)) },
            icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
            value = shownUrl,
            onValueChange = { input ->
                val normalized = RitmeLinks.normalizeServerUrl(input)
                // Blank means "the official server", so a later default change reaches this user too.
                setServerUrl(if (normalized == null || normalized == RitmeConfig.DEFAULT_SERVER_URL) "" else normalized)
            },
            isInputValid = { RitmeLinks.normalizeServerUrl(it) != null },
        )
        PreferenceEntry(
            title = { Text(stringResource(R.string.ritme_server_url_reset)) },
            description = RitmeConfig.DEFAULT_SERVER_URL,
            icon = { Icon(painterResource(R.drawable.restore), contentDescription = null) },
            onClick = { setServerUrl("") },
            isEnabled = serverUrl.isNotBlank(),
        )
        EditTextPreference(
            title = { Text(stringResource(R.string.ritme_display_name)) },
            icon = { Icon(painterResource(R.drawable.person), contentDescription = null) },
            value = displayName.ifBlank { username },
            onValueChange = { setDisplayName(RitmeLinks.cleanDisplayName(it).orEmpty()) },
            isInputValid = { RitmeLinks.cleanDisplayName(it) != null },
        )
        Text(
            text = stringResource(R.string.ritme_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
```

- [ ] **Step 3: Register the route and the settings entry**

`ui/screens/NavigationBuilder.kt` — replace

```kotlin
    composable("crossmix_settings") {
        com.example.musicfy.ui.screens.settings.CrossmixSettingsScreen(navController = navController)
    }
```

with

```kotlin
    composable("crossmix_settings") {
        com.example.musicfy.ui.screens.settings.CrossmixSettingsScreen(navController = navController)
    }

    composable("ritme_settings") {
        com.example.musicfy.ui.screens.settings.RitmeSettingsScreen(navController = navController)
    }
```

`MainActivity.kt` `SubSettingsRoutes` — replace `    "crossmix_settings",` with:

```kotlin
    "crossmix_settings",
    "ritme_settings",
```

`ui/screens/settings/MusicfySettingsScreen.kt`:
- In `SettingsIndex`, after the `SettingsEntry("Provider order", ...)` line add:

```kotlin
    SettingsEntry("Ritme", "Ritme", "ritme_settings", "listen together jam room server self-host display name dengar bareng"),
```

- Replace

```kotlin
                        CategoryBubble(
                            title = "Modules",
                            iconRes = R.drawable.library_add,
                            onClick = { navController.navigate("modules") }
                        )
```

with

```kotlin
                        CategoryBubble(
                            title = "Modules",
                            iconRes = R.drawable.library_add,
                            onClick = { navController.navigate("modules") }
                        )

                        if (com.example.musicfy.ritme.RitmeConfig.ENABLED) {
                            CategoryBubble(
                                title = "Ritme",
                                iconRes = R.drawable.group,
                                onClick = { navController.navigate("ritme_settings") }
                            )
                        }
```

- [ ] **Step 4: Deep links**

Follow-up, not part of this plan: App Links (`assetlinks.json` on the server + `autoVerify="true"`) once the domain is final; until then the https link opens the browser by default on Android 12+ and the landing page's "Open in Musicfy app" button (`musicfy://ritme/{code}`) bridges it.

`app/src/main/AndroidManifest.xml` — directly before `            <!-- Share filter -->` add:

```xml
            <!-- Ritme room links. The https one has no autoVerify: there is no assetlinks.json (spec non-goals). -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data android:scheme="musicfy" android:host="ritme" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data android:scheme="https" android:host="${ritmeHost}" android:pathPrefix="/r/" />
            </intent-filter>

```

`MainActivity.kt` — after

```kotlin
    @Inject
    lateinit var syncUtils: SyncUtils
```

add

```kotlin

    @Inject
    lateinit var ritmeController: com.example.musicfy.ritme.RitmeController
```

and in `handleDeepLinkIntent`, replace

```kotlin
        if (importSharedAudio(intent)) return
```

with

```kotlin
        if (importSharedAudio(intent)) return
        if (com.example.musicfy.ritme.RitmeConfig.ENABLED) {
            // musicfy://ritme/{code}, https://<server>/r/{code}, or shared text containing either
            val text = intent.data?.toString() ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val ritmeCode = text?.let(com.example.musicfy.ritme.RitmeLinks::findRoomCode)
            if (ritmeCode != null) {
                intent.data = null
                intent.removeExtra(Intent.EXTRA_TEXT)
                ritmeController.openJoin(ritmeCode)
                return
            }
        }
```

- [ ] **Step 5: Build and check**

Run: `./gradlew :app:assembleUniversalFossDebug`
Expected: BUILD SUCCESSFUL; the merged manifest contains `android:host="ritme.dubeli.id"` (check with `grep -n "ritme.dubeli.id" app/build/intermediates/merged_manifests/universalFossDebug/processUniversalFossDebugManifest/AndroidManifest.xml`; if the path differs, `find app/build/intermediates -name AndroidManifest.xml -path "*universalFossDebug*" | xargs grep -ln ritme`).

Install, then:
- `adb shell am start -a android.intent.action.VIEW -d "musicfy://ritme/K7M2QX"` → the join sheet opens with `K7M2QX` filled in. (With the `.debug` application id both a release and a debug install may offer a chooser; pick the debug app.)
- `adb shell am start -a android.intent.action.VIEW -d "https://ritme.dubeli.id/r/K7M2QX" com.example.musicfy.debug` → same.
- Settings → Ritme: change the server URL to `http://10.0.2.2:8080` (emulator + `./gradlew -p ritme-server run`), start a Ritme → it connects to the local server; **Restore default** → back to `https://ritme.dubeli.id`. An invalid URL (`ritme.dubeli.id`) is refused by the dialog. Display name edits show up in the start sheet.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ui/screens/settings/RitmeSettingsScreen.kt app/src/main/kotlin/com/example/musicfy/ui/screens/NavigationBuilder.kt app/src/main/kotlin/com/example/musicfy/ui/screens/settings/MusicfySettingsScreen.kt app/src/main/kotlin/com/example/musicfy/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml
git commit -m "feat(ritme-android): Ritme settings and room deep links" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Manual verification against ritme.dubeli.id

**Files:** none (fix any finding in the task that owns the code, with its own commit).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Full test suite and release build**

Run: `./gradlew :app:testUniversalFossDebugUnitTest jvmTests`
Expected: PASS.

Run: `./gradlew :app:assembleUniversalFossRelease` (falls back to the debug keystore locally), install it, start and join a Ritme once.
Expected: works exactly like debug (R8 keeps the protocol classes; a decode failure would show as never leaving "Connecting…").

Also run: `./gradlew :app:assembleUniversalGmsDebug :app:assembleUniversalFdroidDebug`
Expected: BUILD SUCCESSFUL (Ritme is in every flavor).

- [ ] **Step 2: Spec 4.2 checklist (two phones + browser)**

Record each result in the PR description.

- [ ] 2–3 phones on different networks (Wi-Fi + mobile data): audio offset < 100 ms (record both with a third phone and compare the waveforms of a drum hit).
- [ ] 1 phone + desktop browser + phone browser on `https://ritme.dubeli.id/r/{code}`: web < 300 ms.
- [ ] Airplane mode for 10 s while a song plays: music continues, pill shows "Reconnecting…", then back to "Ritme · N" and in sync within a few seconds. Airplane mode for 40 s: "Disconnected from Ritme" toast and the local queue returns paused.
- [ ] Host force-stops the app (`adb shell am force-stop com.example.musicfy.debug`): another member becomes host after ~30 s.
- [ ] Host turns "Members can control" off; a member presses next/previous/seek on the headset, in the notification and in the app → "The host locked the controls", nothing changes in the room. The member presses pause → "Paused on this device only", only that phone stops, the room plays on and the phone stays paused through the 1 s loop; play resumes it in sync; when the host seeks or changes the song while the member is paused, the member's phone re-syncs and plays.
- [ ] Leave Ritme → the personal queue is back, paused, with shuffle/repeat/speed as before. Same after being kicked ("You were removed from Ritme") and after the host ends it ("The host ended Ritme").
- [ ] A track ends: every phone switches together ~1.5 s later; one phone with a broken stream shows the stream-failure UI, does not skip, and follows the room when it moves on.
- [ ] Shuffle, repeat, speed (player buttons, "⋯" menu, notification custom shuffle) show "Not available during Ritme" and do nothing.
- [ ] "Start Ritme with this" from a song menu creates a room with that song; "Add to queue"/"Play next" in any song menu and tapping a search result add to the room ("Added 1 song to Ritme"); local files are skipped with a toast.
- [ ] Share opens the Android share sheet with `https://ritme.dubeli.id/r/{code}`; the QR scans with the camera app and opens the web page.
- [ ] A browser member adds a non-YouTube `URL` entry (if the web build allows it) or one is present: Android shows "Not supported yet" and pauses while it is current.
- [ ] Indonesian locale (`adb shell cmd locale set-app-locales com.example.musicfy.debug --locales in`): all Ritme copy is Indonesian.
- [ ] Outside a room (never joined, and after leaving): playback, radio/autoplay, crossfade, sleep timer and the queue page behave as before.

- [ ] **Step 3: Commit any fixes**

Fix inside the owning task's files, rerun Step 1, and commit each fix separately:

```bash
git commit -m "fix(ritme-android): <what was wrong>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

| Spec requirement | Task |
|---|---|
| Revisi 1: name Ritme, `:modules:ritme-protocol`, `/ritme`, `/r/{code}`, `musicfy://ritme/{code}` | 1 (links, module), 13 (deep links) |
| Revisi 4: `NewEntry`, `initialQueue` + `startIndex` | 5, 6, 8 |
| Revisi 5/2.6: `TrackEnded` once per entry, server ignores stale | 9 (`onLocalEnded`) |
| Revisi 7: `MediaKind` (YOUTUBE_VIDEO audio-only, URL/UNKNOWN unsupported), `durationMs = 0` live | 5 (`isPlayable`), 7 (live: no seek), 11 ("Not supported yet") |
| 1.1 units: RitmeClient/ClockSync/RitmeSession/RitmePlaybackBridge/DriftCorrector/RitmeQueue/UI | 4 (connection = RitmeClient), 2, 6, 9, 2, 9 (single-item load replaces `RitmeQueue`, see Decision 2), 11–13 |
| 1.2 snapshot + restore paused; disable crossmix/shuffle/repeat/radio/speed | 9 (host enter/exit, guards), 10 (intercepts) |
| 1.2 deep links + host from build config, prefs `ritmeServerUrl`/`ritmeDisplayName`, all flavors | 1, 13, 14 (flavor builds) |
| 2.2 full snapshots, ignore stale versions, no optimistic updates | 3 (reducer), 8 (controller sends only), 7 (planner follows snapshot) |
| Ack flow control (README) | 4 (`ack` in `finally`, stale acked, test) |
| 2.3 permission table | 3 (`RitmePermissions`), 8 (controller), 11 (hidden controls) |
| 2.3a move/playNext semantics | 3 (move targets), 8 (reverse batches for playNext) |
| 2.4 clock: 5 pings/200 ms, 30 s, min-RTT of last 8, elapsedRealtime | 2, 4, 6 (`HandlerRitmeTimers`) |
| 2.5 Android drift zones, 1 s loop, pitch 1.0 | 2, 7, 9 (`tick`, `PlaybackParameters(speed, 1f)`) |
| 2.6 anchor start, late joiners catch up, end of queue | 7 (wait/load/play), 9 |
| 2.7 resume token, 30 s give-up, kicked/closed | 4, 9 (exit + toasts) |
| 2.8 limits: 200/500 entries, name 1–32, rate limits | 1 (`cleanDisplayName`), 5 (batches/caps), 8 (single batch burst ≤ 3 commands) |
| README: Create/Join within 10 s, NewEntry validation | 4 (join on open, 10 s timeout), 5 |
| 3.1 entry points: player button, song menu "Start Ritme with this", deep link | 12 (player menu row + `PlayerMenuAction.RITME`, Song/YouTubeSong menus), 13 |
| 3.2 create/join flow, name remembered, local entries skipped with toast, "queue saved" toast | 11, 8, 9 |
| 3.3 room sheet: share, QR (ZXing), members + host menu, ≡/✕ by permission, add songs, guests toggle, leave/end with confirm | 11 (↑/↓ instead of drag, Decision 8) |
| 3.3 menu "Add to queue/Play next" go to the room | 10 (`PlayerConnection`/`MusicService` intercepts) |
| 3.4 pill (mini + full player, reconnecting), queue page replaced, dimmed/blocked controls + toasts, headset/notification intercepted | 12, 10 (`RitmeSessionPlayer`, toasts), 9 |
| Coordinator decision: members without control pause/resume their own device only, re-sync on room change, seek/skip still blocked | 8 (`pause()`, `PausedLocally`, tests), 7 (planner test), 9 (`pauseLocally`), 10 (routing), 14 |
| Merge with main: `PreferenceKeys.kt` in `:core:settings` with a golden key test | 1 (path + golden regeneration) |
| 3.5 settings: server URL + restore default, display name | 13 |
| 4.1 errors: unreachable/retry, room not found/full/upgrade, reconnecting, lost, kicked/closed, stream failure no auto-skip, rejected commands toast, process death | 11 (inline errors), 8 (`RitmeTexts`), 9 (`failStream` guard, notices), 9 (`detach` leaves) |
| 4.2 Android JUnit: ClockSync, DriftCorrector, RitmeSession (stale, reconnect, Kicked) | 2, 6; reconnect in 4; "Kicked → queue restored" split into session end (6) + `RitmeModeTransitions` (7) + manual (14) |
| 4.2 manual checklist | 14 |
| PopupSheet only, no Dialog/ModalBottomSheet | 11, 12 (`TempoPitchDialog` is pre-existing and only gets a guard) |
| Strings in Indonesian + English | 8, 11, 12, 13 |

**Placeholder scan:** every code step shows the full code; the only conditional instruction is the `MutableList`→`List` override note in Task 10 Step 1 and the merged-manifest path fallback in Task 13 Step 5, both with the exact alternative.

**Type consistency checked:** `RitmeLocalAction`, `RitmeToast`, `LocalPlayOrder`, `InitialQueue`, `ConnectionEvent.Failure`, `PlayerOp`, `SyncInput`, `RitmePlayerHost.loadRitmeItem(item, startPositionMs)`, `RitmeController.openSheet/openJoin/openStartWith/startRoom/joinRoom/shareLink`, `RitmeRoomContent(ritme, onAddSongs, onLeft, modifier, bottomPadding)` and `RitmeQueuePage(onClose, navController, bottomPadding, modifier)` are used with the same names and parameters in every task.
