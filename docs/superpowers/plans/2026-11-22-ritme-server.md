# Ritme Server (protocol + Ktor relay + deploy) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and package the Ritme ("listen/watch together") relay server — music, YouTube video and browser-found video URLs share one room model — a shared protocol module, an in-memory room server over WebSocket, a metadata-only search endpoint, a landing page, and Docker/Caddy deploy files.

**Architecture:** `modules/ritme-protocol` (plain Kotlin/JVM, kotlinx.serialization) defines every message. `ritme-server/` is a **standalone Gradle build** that reuses the repo's version catalog and includes the protocol module, so its Docker image builds without the Android SDK. Room rules are a pure function (`RoomLogic`); `Room` adds membership, locking and fan-out behind a `Peer` interface; Ktor only adapts WebSocket frames to `Peer`. Spec: `docs/superpowers/specs/2026-11-22-ritme-listen-together-design.md` (the "Revisi" section wins on conflicts).

**Tech Stack:** Kotlin 2.3.10, JDK 21, Gradle 9.3.1 wrapper, Ktor 3.4.0 (server: netty, websockets, forwarded-header; client: okhttp), kotlinx.serialization 1.9.0, JUnit 4.13.2, logback 1.5.18, Docker + Caddy 2.

## Global Constraints

- Feature name **Ritme**. Protocol package `com.example.musicfy.ritme.protocol`; server package `com.example.musicfy.ritme.server`.
- WebSocket path `/ritme`; share link `https://<domain>/r/{code}`; deep link `musicfy://ritme/{code}`; health `GET /healthz` → `ok`.
- JSON: discriminator field `"type"`, `encodeDefaults = true`, `ignoreUnknownKeys = true`.
- `PROTOCOL_VERSION = 1`.
- Media kinds: `YOUTUBE_MUSIC` (default), `YOUTUBE_VIDEO`, `URL`. YouTube kinds need `videoId`; `URL` needs an `http(s)` `url` (≤ 2048 chars) and sends `videoId = ""`. `durationMs = 0` = unknown/live: never auto-advanced, position not clamped, any `TrackEnded` for it is accepted.
- Limits: 20 members/room, 500 queue entries, 10 commands/s/member, 64 KB frames, names 1–32 chars after trim, 30 s reconnect grace, room deleted 60 s after no member is connected, new track starts `now + 1500 ms`, fallback advance at `durationMs + 5000 ms`, `Previous` restarts if position > 3000 ms, `TrackEnded` ignored if position < `durationMs − 10000`, search 30/min/IP, search cache 10 min / 1000 entries, max 20 results.
- Room codes: 6 chars from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`. Resume tokens: 128-bit random, base64url.
- Privacy: no disk writes; never log names, videoIds, search queries or IPs.
- Server env: `PORT` (default 8080), `PUBLIC_URL` (default `http://localhost:8080`), `WEB_DIR` (default `/app/web`).
- Server is reachable only through the Caddy reverse proxy, so it trusts `X-Forwarded-For`.
- Every Gradle command needs: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH`
- Work in the worktree `/Users/suryasoft/WebstormProjects/musicfy/.worktrees/ritme` (branch `feature/ritme`).
- Commit messages end with: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## File Structure

| Path | Responsibility |
|---|---|
| `gradle/libs.versions.toml` | + kotlin-jvm plugin, ktor server libs, kotlinx-serialization-json, logback |
| `settings.gradle.kts` | + `include(":modules:ritme-protocol")` (Android build, for later app integration) |
| `modules/ritme-protocol/` | `Models.kt`, `Messages.kt`, `RitmeJson.kt`; tests + `fixtures/*.json` golden files |
| `ritme-server/settings.gradle.kts`, `build.gradle.kts` | Standalone build, includes `../modules/ritme-protocol` as `:ritme-protocol` |
| `ritme-server/src/main/kotlin/.../server/Main.kt` | `main()` → Netty |
| `.../server/ServerConfig.kt` | env → config |
| `.../server/Application.kt` | `ServerDeps`, `Application.ritmeModule()`: plugins, routes, sweep loop |
| `.../server/Clock.kt`, `Ids.kt`, `Limits.kt`, `RateLimiter.kt` | Small seams and constants |
| `.../server/RoomLogic.kt` | Pure room rules (`Outcome`, `Effect`) |
| `.../server/Room.kt`, `RoomRegistry.kt` | Stateful room (mutex, peers, tokens, grace) and registry |
| `.../server/RitmeSocket.kt` | WebSocket ↔ `Peer` adapter |
| `.../server/Search.kt` | InnerTube backend, parser, cached service, per-IP limiter |
| `.../server/Landing.kt` | Built-in landing page |
| `ritme-server/src/main/resources/logback.xml` | INFO logging, no request logs |
| `ritme-server/Dockerfile`, `Dockerfile.dockerignore` | Image built from repo root context |
| `ritme-server/deploy/docker-compose.yml`, `Caddyfile`, `.env.example` | VPS deploy |
| `ritme-server/README.md` | Run, test, deploy, privacy |
| `.github/workflows/ritme-server.yml` | Server tests + image build |

---

### Task 1: `ritme-protocol` module

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Create: `modules/ritme-protocol/build.gradle.kts`
- Create: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt`
- Create: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Messages.kt`
- Create: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/RitmeJson.kt`
- Test: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/PositionAtTest.kt`
- Test: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/Samples.kt`
- Test: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/MessageFixturesTest.kt`
- Create (generated): `modules/ritme-protocol/fixtures/*.json`

**Interfaces:**
- Produces (used by every later task and by the future Android/web clients):
  - `const val PROTOCOL_VERSION = 1`
  - data classes `RoomSettings(guestsCanControl)`, `Member(id, name, isHost, connected, joinedAt)`, `enum class MediaKind { YOUTUBE_MUSIC, YOUTUBE_VIDEO, URL }`, `NewEntry(videoId, title, artist, thumbnailUrl?, durationMs, kind = YOUTUBE_MUSIC, url? = null)`, `QueueEntry(entryId, videoId, title, artist, thumbnailUrl?, durationMs, addedBy, kind = YOUTUBE_MUSIC, url? = null)`, `val QueueEntry.isLive`, `Playback(isPlaying, positionMs, anchorServerTimeMs)`, `RoomState(code, version, hostId, settings, members, queue, currentEntryId?, playback)`
  - `fun RoomState.currentEntry(): QueueEntry?`, `fun RoomState.positionAt(serverNow: Long): Long`
  - `enum class ErrorCode`, sealed `Command`, sealed `ClientMessage` (`Create`, `Join`, `Leave`, `Ping`, `Send` [serialized as `"Command"`], `TrackEnded`), sealed `ServerMessage` (`Welcome`, `State`, `Pong`, `Error`, `Kicked`, `RoomClosed`)
  - `val RitmeJson: Json`, `ClientMessage.encode()`, `ServerMessage.encode()`, `decodeClientMessage(String)`, `decodeServerMessage(String)`

- [ ] **Step 1: Catalog entries**

In `gradle/libs.versions.toml` `[libraries]`, after the `ktor-serialization-json` line, add:
```toml
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
ktor-server-forwarded-header = { module = "io.ktor:ktor-server-forwarded-header", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version = "1.9.0" }
logback = { module = "ch.qos.logback:logback-classic", version = "1.5.18" }
```
In `[plugins]`, add:
```toml
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

- [ ] **Step 2: Register the module in the Android build and create its build file**

Append to `settings.gradle.kts` after `include(":modules:monochrome")`:
```kotlin
include(":modules:ritme-protocol")
```

`modules/ritme-protocol/build.gradle.kts`:
```kotlin
// Ritme wire protocol. Plain Kotlin on purpose: the Android app, the Ktor server (ritme-server/,
// a separate Gradle build that includes this directory) and, via golden JSON fixtures, the web
// client all agree on these shapes.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :modules:ritme-protocol:test -PupdateGolden=true rewrites fixtures/*.json
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
```

- [ ] **Step 3: Write the failing position test**

`modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/PositionAtTest.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionAtTest {
    private val entry = QueueEntry("e1", "vid", "Title", "Artist", null, durationMs = 200_000, addedBy = "m1")
    private fun state(playback: Playback, current: String? = "e1") =
        RoomState(code = "ABCDEF", version = 1, hostId = "m1", queue = listOf(entry), currentEntryId = current, playback = playback)

    @Test
    fun playingAdvancesWithServerTime() {
        assertEquals(15_000, state(Playback(true, 10_000, 1_000)).positionAt(6_000))
    }

    @Test
    fun pausedStaysPut() {
        assertEquals(10_000, state(Playback(false, 10_000, 1_000)).positionAt(99_000))
    }

    @Test
    fun anchorInTheFutureMeansNotStartedYet() {
        assertEquals(0, state(Playback(true, 0, 10_000)).positionAt(8_500))
    }

    @Test
    fun clampedToTrackLength() {
        assertEquals(200_000, state(Playback(true, 190_000, 0)).positionAt(60_000))
    }

    @Test
    fun nothingCurrentIsZero() {
        val idle = state(Playback(true, 5_000, 0), current = null)
        assertNull(idle.currentEntry())
        assertEquals(0, idle.positionAt(10_000))
    }

    @Test
    fun unknownDurationLiveStreamsAreNotClamped() {
        val live = QueueEntry("e1", "", "Live", "", null, durationMs = 0, addedBy = "m1", kind = MediaKind.URL, url = "https://example.com/live.m3u8")
        val state = RoomState(code = "ABCDEF", version = 1, hostId = "m1", queue = listOf(live), currentEntryId = "e1", playback = Playback(true, 0, 0))
        assertEquals(3_600_000, state.positionAt(3_600_000))
    }
}
```

Run: `./gradlew :modules:ritme-protocol:test`
Expected: FAIL (`Unresolved reference: QueueEntry`, …).

- [ ] **Step 4: Implement the models**

`modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.Serializable

/** Bump on any incompatible message change. Create/Join carry it; the server refuses mismatches. */
const val PROTOCOL_VERSION = 1

@Serializable
data class RoomSettings(val guestsCanControl: Boolean = true)

@Serializable
data class Member(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val connected: Boolean,
    val joinedAt: Long,
)

/**
 * What an entry is. The server treats all kinds alike (same queue, same sync); clients decide how
 * to render: audio for [YOUTUBE_MUSIC], a video player for [YOUTUBE_VIDEO], and the URL itself
 * (a direct video file or HLS/DASH stream found by the in-app browser) for [URL].
 */
@Serializable
enum class MediaKind { YOUTUBE_MUSIC, YOUTUBE_VIDEO, URL }

/**
 * An entry as a client proposes it. The server assigns [QueueEntry.entryId] and [QueueEntry.addedBy].
 * YouTube kinds need [videoId]; [MediaKind.URL] needs an http(s) [url] and sends `videoId = ""`.
 * `durationMs = 0` means unknown (live stream): no automatic advance, position is not clamped.
 */
@Serializable
data class NewEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long,
    val kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    val url: String? = null,
)

@Serializable
data class QueueEntry(
    val entryId: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long,
    val addedBy: String,
    val kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    val url: String? = null,
)

/** True when the length is unknown (live stream). */
val QueueEntry.isLive: Boolean get() = durationMs <= 0

/**
 * Where playback is, as of [anchorServerTimeMs] on the server clock. While playing, the position
 * moves forward with server time; an anchor in the future means "start at that moment".
 */
@Serializable
data class Playback(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorServerTimeMs: Long = 0,
)

@Serializable
data class RoomState(
    val code: String,
    val version: Long,
    val hostId: String,
    val settings: RoomSettings = RoomSettings(),
    val members: List<Member> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val playback: Playback = Playback(),
)

fun RoomState.currentEntry(): QueueEntry? =
    currentEntryId?.let { id -> queue.firstOrNull { it.entryId == id } }

/**
 * Playback position at [serverNow], clamped to the current entry (only at 0 for live entries).
 * 0 when nothing is current.
 */
fun RoomState.positionAt(serverNow: Long): Long {
    val entry = currentEntry() ?: return 0
    val raw = if (playback.isPlaying) {
        playback.positionMs + (serverNow - playback.anchorServerTimeMs)
    } else {
        playback.positionMs
    }
    return if (entry.isLive) raw.coerceAtLeast(0) else raw.coerceIn(0, entry.durationMs)
}
```

Run: `./gradlew :modules:ritme-protocol:test`
Expected: PASS (6 tests).

- [ ] **Step 5: Write the failing message/fixture test**

`modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/Samples.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

/** One instance of every message. Fixture file names are the map keys. */
object Samples {
    private val entry = QueueEntry(
        entryId = "e1",
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        durationMs = 213_000,
        addedBy = "m1",
    )
    private val newEntry = NewEntry("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, 213_000)
    private val state = RoomState(
        code = "K7M2QX",
        version = 3,
        hostId = "m1",
        settings = RoomSettings(guestsCanControl = true),
        members = listOf(Member("m1", "Budi", true, true, 1_000), Member("m2", "Sari", false, false, 2_000)),
        queue = listOf(entry),
        currentEntryId = "e1",
        playback = Playback(true, 5_000, 1_700_000_000_000),
    )

    private fun send(cmd: Command) = ClientMessage.Send("r1", cmd)

    val client: Map<String, ClientMessage> = linkedMapOf(
        "client-create" to ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(newEntry), startIndex = 0, startPositionMs = 0),
        "client-join" to ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Sari", resumeToken = "tok"),
        "client-leave" to ClientMessage.Leave,
        "client-ping" to ClientMessage.Ping(123),
        "client-track-ended" to ClientMessage.TrackEnded("e1"),
        "cmd-play" to send(Command.Play),
        "cmd-pause" to send(Command.Pause),
        "cmd-seek" to send(Command.Seek(42_000)),
        "cmd-next" to send(Command.Next),
        "cmd-previous" to send(Command.Previous),
        "cmd-play-entry" to send(Command.PlayEntry("e1")),
        "cmd-add" to send(Command.Add(listOf(newEntry), playNext = true)),
        "cmd-add-youtube-video" to send(
            Command.Add(listOf(NewEntry("dQw4w9WgXcQ", "Never Gonna Give You Up (Official Video)", "Rick Astley", null, 213_000, MediaKind.YOUTUBE_VIDEO))),
        ),
        "cmd-add-url-live" to send(
            Command.Add(listOf(NewEntry("", "Live stream", "", null, 0, MediaKind.URL, "https://example.com/live/index.m3u8"))),
        ),
        "cmd-remove" to send(Command.Remove("e1")),
        "cmd-move" to send(Command.Move("e1", afterEntryId = null)),
        "cmd-set-settings" to send(Command.SetSettings(RoomSettings(guestsCanControl = false))),
        "cmd-kick" to send(Command.Kick("m2")),
        "cmd-transfer-host" to send(Command.TransferHost("m2")),
        "cmd-end-room" to send(Command.EndRoom),
    )

    val server: Map<String, ServerMessage> = linkedMapOf(
        "server-welcome" to ServerMessage.Welcome("m1", "tok", state, 1_700_000_000_500),
        "server-state" to ServerMessage.State(state),
        "server-pong" to ServerMessage.Pong(123, 1_700_000_000_500),
        "server-error" to ServerMessage.Error("r1", ErrorCode.FORBIDDEN),
        "server-kicked" to ServerMessage.Kicked,
        "server-room-closed" to ServerMessage.RoomClosed,
    )
}
```

`modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/MessageFixturesTest.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Golden JSON for every message. The web client's tests read the same files, so a shape change
 * on one side only fails CI. Regenerate deliberately with -PupdateGolden=true.
 */
class MessageFixturesTest {
    private val pretty = Json(from = RitmeJson) { prettyPrint = true }
    private val dir = File("fixtures")
    private val update = System.getProperty("updateGolden") == "true"

    @Test
    fun clientMessagesRoundTripAndMatchFixtures() {
        Samples.client.forEach { (name, message) ->
            assertEquals(name, message, decodeClientMessage(message.encode()))
            check(name, pretty.encodeToString(ClientMessage.serializer(), message))
        }
    }

    @Test
    fun serverMessagesRoundTripAndMatchFixtures() {
        Samples.server.forEach { (name, message) ->
            assertEquals(name, message, decodeServerMessage(message.encode()))
            check(name, pretty.encodeToString(ServerMessage.serializer(), message))
        }
    }

    @Test
    fun commandsUseTheCommandDiscriminator() {
        val json = RitmeJson.parseToJsonElement(Samples.client.getValue("cmd-seek").encode()).jsonObject
        assertEquals("Command", json.getValue("type").jsonPrimitive.content)
        assertEquals("Seek", json.getValue("cmd").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra = """{"type":"Ping","clientTime":5,"future":"field"}"""
        assertEquals(ClientMessage.Ping(5), decodeClientMessage(withExtra))
    }

    private fun check(name: String, json: String) {
        val file = File(dir, "$name.json")
        if (update) {
            dir.mkdirs()
            file.writeText(json + "\n")
            return
        }
        assertEquals("fixture $name", file.readText().trim(), json)
    }
}
```

Run: `./gradlew :modules:ritme-protocol:test`
Expected: FAIL (`Unresolved reference: ClientMessage`, …).

- [ ] **Step 6: Implement messages and JSON**

`modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Messages.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode { ROOM_NOT_FOUND, ROOM_FULL, FORBIDDEN, RATE_LIMITED, UPGRADE_REQUIRED, INVALID, QUEUE_FULL }

@Serializable
sealed interface Command {
    @Serializable @SerialName("Play")
    data object Play : Command

    @Serializable @SerialName("Pause")
    data object Pause : Command

    @Serializable @SerialName("Seek")
    data class Seek(val positionMs: Long) : Command

    @Serializable @SerialName("Next")
    data object Next : Command

    @Serializable @SerialName("Previous")
    data object Previous : Command

    @Serializable @SerialName("PlayEntry")
    data class PlayEntry(val entryId: String) : Command

    @Serializable @SerialName("Add")
    data class Add(val entries: List<NewEntry>, val playNext: Boolean = false) : Command

    @Serializable @SerialName("Remove")
    data class Remove(val entryId: String) : Command

    /** `afterEntryId = null` means "right after the current entry". */
    @Serializable @SerialName("Move")
    data class Move(val entryId: String, val afterEntryId: String? = null) : Command

    @Serializable @SerialName("SetSettings")
    data class SetSettings(val settings: RoomSettings) : Command

    @Serializable @SerialName("Kick")
    data class Kick(val memberId: String) : Command

    @Serializable @SerialName("TransferHost")
    data class TransferHost(val memberId: String) : Command

    @Serializable @SerialName("EndRoom")
    data object EndRoom : Command
}

@Serializable
sealed interface ClientMessage {
    @Serializable @SerialName("Create")
    data class Create(
        val protocolVersion: Int,
        val name: String,
        val initialQueue: List<NewEntry> = emptyList(),
        val startIndex: Int = 0,
        val startPositionMs: Long = 0,
    ) : ClientMessage

    @Serializable @SerialName("Join")
    data class Join(
        val protocolVersion: Int,
        val code: String,
        val name: String,
        val resumeToken: String? = null,
    ) : ClientMessage

    @Serializable @SerialName("Leave")
    data object Leave : ClientMessage

    @Serializable @SerialName("Ping")
    data class Ping(val clientTime: Long) : ClientMessage

    /** On the wire `"type":"Command"`; named Send so it does not clash with [Command]. */
    @Serializable @SerialName("Command")
    data class Send(val reqId: String, val cmd: Command) : ClientMessage

    @Serializable @SerialName("TrackEnded")
    data class TrackEnded(val entryId: String) : ClientMessage
}

@Serializable
sealed interface ServerMessage {
    @Serializable @SerialName("Welcome")
    data class Welcome(val memberId: String, val resumeToken: String, val state: RoomState, val serverTime: Long) : ServerMessage

    @Serializable @SerialName("State")
    data class State(val state: RoomState) : ServerMessage

    @Serializable @SerialName("Pong")
    data class Pong(val clientTime: Long, val serverTime: Long) : ServerMessage

    @Serializable @SerialName("Error")
    data class Error(val reqId: String? = null, val code: ErrorCode) : ServerMessage

    @Serializable @SerialName("Kicked")
    data object Kicked : ServerMessage

    @Serializable @SerialName("RoomClosed")
    data object RoomClosed : ServerMessage
}
```

`modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/RitmeJson.kt`:
```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.Json

val RitmeJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun ClientMessage.encode(): String = RitmeJson.encodeToString(ClientMessage.serializer(), this)

fun ServerMessage.encode(): String = RitmeJson.encodeToString(ServerMessage.serializer(), this)

fun decodeClientMessage(text: String): ClientMessage = RitmeJson.decodeFromString(ClientMessage.serializer(), text)

fun decodeServerMessage(text: String): ServerMessage = RitmeJson.decodeFromString(ServerMessage.serializer(), text)
```

- [ ] **Step 7: Generate fixtures, inspect, run**

Run: `./gradlew :modules:ritme-protocol:test -PupdateGolden=true && ls modules/ritme-protocol/fixtures | wc -l && cat modules/ritme-protocol/fixtures/cmd-seek.json`
Expected: `26` files; `cmd-seek.json` shows `"type": "Command"`, `"reqId": "r1"`, and `"cmd": { "type": "Seek", "positionMs": 42000 }`.

Run: `./gradlew :modules:ritme-protocol:test`
Expected: PASS (10 tests).

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts modules/ritme-protocol
git commit -m "feat(ritme): add ritme-protocol module with golden JSON fixtures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Standalone `ritme-server` build with `/healthz`

**Files:**
- Create: `ritme-server/settings.gradle.kts`
- Create: `ritme-server/build.gradle.kts`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Main.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/ServerConfig.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Clock.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt`
- Create: `ritme-server/src/main/resources/logback.xml`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/TestDeps.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/HealthTest.kt`
- Modify: `.gitignore` (add `ritme-server/build/` and `ritme-server/.gradle/` if not already covered)

**Interfaces:**
- Consumes: `:ritme-protocol` (Task 1).
- Produces:
  - `data class ServerConfig(val port: Int, val publicUrl: String, val webDir: File)` with `companion fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig`
  - `fun interface Clock { fun now(): Long }`, `object SystemClock : Clock`
  - `class ServerDeps(val config: ServerConfig, val clock: Clock = SystemClock)` (later tasks add parameters with defaults)
  - `fun Application.ritmeModule(deps: ServerDeps)`
  - Test helper `fun testDeps(webDir: File = File("build/no-web")): ServerDeps`

- [ ] **Step 1: Build files**

`ritme-server/settings.gradle.kts`:
```kotlin
// Standalone build so the Docker image needs only a JDK (no Android SDK). Shares the repo's
// version catalog and compiles ../modules/ritme-protocol as :ritme-protocol.
rootProject.name = "ritme-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":ritme-protocol")
project(":ritme-protocol").projectDir = file("../modules/ritme-protocol")
```

`ritme-server/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.musicfy.ritme.server.MainKt")
}

dependencies {
    implementation(project(":ritme-protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.logback)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}
```

Check `.gitignore`: run `git check-ignore -q ritme-server/build/x && echo ignored`. If it does not print `ignored`, append to `.gitignore`:
```gitignore
ritme-server/build/
ritme-server/.gradle/
```

- [ ] **Step 2: Write the failing test**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/TestDeps.kt`:
```kotlin
package com.example.musicfy.ritme.server

import java.io.File

fun testDeps(webDir: File = File("build/no-web")): ServerDeps =
    ServerDeps(ServerConfig(port = 0, publicUrl = "https://ritme.test", webDir = webDir))
```

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/HealthTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HealthTest {
    @Test
    fun healthzSaysOk() = testApplication {
        application { ritmeModule(testDeps()) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun configReadsEnvironmentWithDefaults() {
        assertEquals(ServerConfig(8080, "http://localhost:8080", File("/app/web")), ServerConfig.fromEnv(emptyMap()))
        assertEquals(
            ServerConfig(9000, "https://ritme.example.com", File("/srv/web")),
            ServerConfig.fromEnv(mapOf("PORT" to "9000", "PUBLIC_URL" to "https://ritme.example.com/", "WEB_DIR" to "/srv/web")),
        )
    }
}
```

Run (from the worktree root): `./gradlew -p ritme-server test`
Expected: FAIL (`Unresolved reference: ServerDeps`, …).

- [ ] **Step 3: Implement**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Clock.kt`:
```kotlin
package com.example.musicfy.ritme.server

/** Server time in epoch milliseconds. Clients sync to this clock. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
```

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/ServerConfig.kt`:
```kotlin
package com.example.musicfy.ritme.server

import java.io.File

data class ServerConfig(
    val port: Int,
    /** Public origin used in share links, without trailing slash. */
    val publicUrl: String,
    /** Built web client; when it has no index.html the built-in landing page is served. */
    val webDir: File,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            publicUrl = (env["PUBLIC_URL"] ?: "http://localhost:8080").trimEnd('/'),
            webDir = File(env["WEB_DIR"] ?: "/app/web"),
        )
    }
}
```

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt`:
```kotlin
package com.example.musicfy.ritme.server

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
)

fun Application.ritmeModule(deps: ServerDeps) {
    routing {
        get("/healthz") { call.respondText("ok") }
    }
}
```

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Main.kt`:
```kotlin
package com.example.musicfy.ritme.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port) {
        ritmeModule(ServerDeps(config))
    }.start(wait = true)
}
```

`ritme-server/src/main/resources/logback.xml`:
```xml
<configuration>
    <!-- No request logging: Ritme never records names, songs, queries or IPs. -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSX} %-5level %logger{20} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
    <logger name="io.netty" level="WARN"/>
</configuration>
```

- [ ] **Step 4: Run tests and start the server once**

Run: `./gradlew -p ritme-server test`
Expected: PASS (2 tests).

Run: `./gradlew -p ritme-server installDist && (PORT=18080 ritme-server/build/install/ritme-server/bin/ritme-server & echo $! > /tmp/ritme.pid; sleep 4; curl -s localhost:18080/healthz; echo; kill $(cat /tmp/ritme.pid))`
Expected: prints `ok`.

- [ ] **Step 5: Commit**

```bash
git add ritme-server .gitignore
git commit -m "feat(ritme): standalone ritme-server build with health endpoint

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Pure room rules (`RoomLogic`)

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Limits.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Ids.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/TestSupport.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomLogicTest.kt`

**Interfaces:**
- Consumes: protocol types (Task 1).
- Produces:
  - `object Limits` (constants listed in Global Constraints, plus `SWEEP_INTERVAL_MS = 1000`, `ROOM_CODE_LENGTH = 6`, `ROOM_CODE_ALPHABET`)
  - `interface IdGen { fun memberId(): String; fun entryId(): String; fun resumeToken(): String; fun roomCode(): String }`, `class SecureIdGen : IdGen`
  - `sealed interface Effect { data class Kicked(memberId); data object Closed }`
  - `sealed interface Outcome { data class Ok(state, effect: Effect? = null); data class Rejected(code: ErrorCode) }`
  - `object RoomLogic` with `apply(state, by, cmd, now, ids): Outcome`, `newRoom(code, hostId, hostName, create, now, ids): RoomState`, `onTrackEnded(state, entryId, now): RoomState?`, `tick(state, now): RoomState?`, `cleanName(raw): String?`, `nextHost(state, excluding): String?`, `addMember(state, member)`, `setConnected(state, memberId, connected)`, `removeMembers(state, memberIds: Set<String>)`, `withHost(state, hostId)`, `startEntry(state, entryId, now)`, `advance(state, now)`
  - Test helpers: `SeqIds`, `FakeClock`, `entry()`, `newEntry()`, `roomState()`, `HOST`, `GUEST`

- [ ] **Step 1: Constants and ids**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Limits.kt`:
```kotlin
package com.example.musicfy.ritme.server

object Limits {
    const val MAX_MEMBERS = 20
    const val MAX_QUEUE = 500
    const val COMMANDS_PER_SECOND = 10
    const val MAX_FRAME_BYTES = 64L * 1024
    const val MAX_NAME_LENGTH = 32
    const val MAX_VIDEO_ID_LENGTH = 64
    const val MAX_TEXT_LENGTH = 300
    const val MAX_URL_LENGTH = 2_048

    /** A disconnected member keeps their seat (and host role) this long. */
    const val GRACE_MS = 30_000L

    /** A room with nobody connected is deleted after this long. */
    const val EMPTY_ROOM_MS = 60_000L

    /** New tracks start this far in the future so every device can buffer and begin together. */
    const val START_DELAY_MS = 1_500L

    /** If no client reports TrackEnded, the server advances this long after the track's end. */
    const val FALLBACK_SLACK_MS = 5_000L

    /** Previous past this position restarts the current track instead. */
    const val PREVIOUS_RESTART_MS = 3_000L

    /** TrackEnded earlier than (duration - this) is treated as stale and ignored. */
    const val TRACK_ENDED_TOLERANCE_MS = 10_000L

    const val SWEEP_INTERVAL_MS = 1_000L

    const val ROOM_CODE_LENGTH = 6
    const val ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    const val SEARCH_PER_MINUTE = 30
    const val SEARCH_MAX_QUERY_LENGTH = 200
}
```

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Ids.kt`:
```kotlin
package com.example.musicfy.ritme.server

import java.security.SecureRandom
import java.util.Base64

interface IdGen {
    fun memberId(): String

    fun entryId(): String

    /** 128-bit, unguessable: it is the only credential for reclaiming a seat. */
    fun resumeToken(): String

    fun roomCode(): String
}

class SecureIdGen(private val random: SecureRandom = SecureRandom()) : IdGen {
    override fun memberId(): String = token(9)

    override fun entryId(): String = token(9)

    override fun resumeToken(): String = token(16)

    override fun roomCode(): String = buildString {
        repeat(Limits.ROOM_CODE_LENGTH) {
            append(Limits.ROOM_CODE_ALPHABET[random.nextInt(Limits.ROOM_CODE_ALPHABET.length)])
        }
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
}
```

- [ ] **Step 2: Test helpers and the failing tests**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/TestSupport.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState

const val HOST = "host"
const val GUEST = "guest"

class SeqIds : IdGen {
    private var n = 0

    override fun memberId() = "m${++n}"

    override fun entryId() = "e${++n}"

    override fun resumeToken() = "t${++n}"

    override fun roomCode() = "RM%04d".format(++n)
}

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun now(): Long = now
}

fun entry(id: String, durationMs: Long = 200_000, addedBy: String = HOST) =
    QueueEntry(id, "vid-$id", "Title $id", "Artist", null, durationMs, addedBy)

fun newEntry(videoId: String = "dQw4w9WgXcQ", durationMs: Long = 200_000) =
    NewEntry(videoId, "Song $videoId", "Artist", null, durationMs)

fun roomState(
    queue: List<QueueEntry> = emptyList(),
    currentEntryId: String? = null,
    playback: Playback = Playback(),
    guestsCanControl: Boolean = true,
    guestConnected: Boolean = true,
) = RoomState(
    code = "ABCDEF",
    version = 1,
    hostId = HOST,
    settings = RoomSettings(guestsCanControl),
    members = listOf(Member(HOST, "Host", true, true, 1), Member(GUEST, "Guest", false, guestConnected, 2)),
    queue = queue,
    currentEntryId = currentEntryId,
    playback = playback,
)
```

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomLogicTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLogicTest {
    private val ids = SeqIds()
    private val now = 1_000_000L

    /** Playing "a" since 5 s ago from 10 s in, so 15 s in at [now]. */
    private val playingA = roomState(
        queue = listOf(entry("a"), entry("b"), entry("c")),
        currentEntryId = "a",
        playback = Playback(true, 10_000, now - 5_000),
    )

    private fun apply(state: RoomState, by: String, cmd: Command) = RoomLogic.apply(state, by, cmd, now, ids)

    private fun ok(outcome: Outcome): RoomState {
        assertTrue("expected Ok but was $outcome", outcome is Outcome.Ok)
        return (outcome as Outcome.Ok).state
    }

    private fun rejected(outcome: Outcome): ErrorCode {
        assertTrue("expected Rejected but was $outcome", outcome is Outcome.Rejected)
        return (outcome as Outcome.Rejected).code
    }

    @Test
    fun guestCannotControlPlaybackOrOthersEntriesWhenLocked() {
        val locked = playingA.copy(settings = RoomSettings(guestsCanControl = false))
        listOf(
            Command.Play, Command.Pause, Command.Seek(0), Command.Next, Command.Previous,
            Command.PlayEntry("b"), Command.Move("c", "a"), Command.Remove("b"),
        ).forEach { assertEquals("$it", ErrorCode.FORBIDDEN, rejected(apply(locked, GUEST, it))) }
    }

    @Test
    fun guestMayAddAndRemoveOwnEntryWhenLocked() {
        val locked = playingA.copy(settings = RoomSettings(guestsCanControl = false))
        val added = ok(apply(locked, GUEST, Command.Add(listOf(newEntry()))))
        val own = added.queue.last()
        assertEquals(GUEST, own.addedBy)
        assertFalse(ok(apply(added, GUEST, Command.Remove(own.entryId))).queue.any { it.entryId == own.entryId })
    }

    @Test
    fun hostOnlyCommandsRejectGuestsEvenWhenControlIsOpen() {
        listOf(Command.SetSettings(RoomSettings(false)), Command.Kick(HOST), Command.TransferHost(GUEST), Command.EndRoom)
            .forEach { assertEquals("$it", ErrorCode.FORBIDDEN, rejected(apply(playingA, GUEST, it))) }
    }

    @Test
    fun pauseFreezesCurrentPositionAndBumpsVersion() {
        val paused = ok(apply(playingA, HOST, Command.Pause))
        assertEquals(Playback(false, 15_000, now), paused.playback)
        assertEquals(playingA.version + 1, paused.version)
    }

    @Test
    fun playResumesFromFrozenPositionAnchoredNow() {
        val paused = playingA.copy(playback = Playback(false, 15_000, now - 60_000))
        assertEquals(Playback(true, 15_000, now), ok(apply(paused, HOST, Command.Play)).playback)
    }

    @Test
    fun playWhilePlayingIsANoOp() {
        assertSame(playingA, (apply(playingA, HOST, Command.Play) as Outcome.Ok).state)
    }

    @Test
    fun nextStartsTheFollowingEntryAfterTheStartDelay() {
        val next = ok(apply(playingA, HOST, Command.Next))
        assertEquals("b", next.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), next.playback)
    }

    @Test
    fun nextOnTheLastEntryStopsAtItsEnd() {
        val ended = ok(apply(playingA.copy(currentEntryId = "c"), HOST, Command.Next))
        assertEquals("c", ended.currentEntryId)
        assertEquals(Playback(false, 200_000, now), ended.playback)
    }

    @Test
    fun previousRestartsTheCurrentEntryWhenPastTheThreshold() {
        val restarted = ok(apply(playingA.copy(currentEntryId = "b"), HOST, Command.Previous))
        assertEquals("b", restarted.currentEntryId)
        assertEquals(Playback(true, 0, now), restarted.playback)
    }

    @Test
    fun previousGoesBackWhenNearTheStart() {
        val earlyB = playingA.copy(currentEntryId = "b", playback = Playback(true, 0, now - 1_000))
        val back = ok(apply(earlyB, HOST, Command.Previous))
        assertEquals("a", back.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), back.playback)
    }

    @Test
    fun seekClampsIntoTheTrackAndKeepsPlayState() {
        assertEquals(Playback(true, 200_000, now), ok(apply(playingA, HOST, Command.Seek(999_999))).playback)
        assertEquals(Playback(true, 0, now), ok(apply(playingA, HOST, Command.Seek(-5))).playback)
    }

    @Test
    fun addToAnIdleRoomStartsTheFirstAddedEntry() {
        val started = ok(apply(roomState(), GUEST, Command.Add(listOf(newEntry("x"), newEntry("y")))))
        assertEquals(started.queue.first().entryId, started.currentEntryId)
        assertEquals(Playback(true, 0, now + Limits.START_DELAY_MS), started.playback)
    }

    @Test
    fun addPlayNextInsertsRightAfterTheCurrentEntry() {
        val added = ok(apply(playingA, HOST, Command.Add(listOf(newEntry("n")), playNext = true)))
        assertEquals(listOf("a", "n", "b", "c"), added.queue.map { it.videoId.removePrefix("vid-") })
    }

    @Test
    fun addRejectsInvalidAndOversizedBatches() {
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(emptyList()))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry(" "))))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(newEntry(durationMs = -1))))))
        val tooMany = List(Limits.MAX_QUEUE - 2) { newEntry("v$it") }
        assertEquals(ErrorCode.QUEUE_FULL, rejected(apply(playingA, HOST, Command.Add(tooMany))))
    }

    @Test
    fun removingTheCurrentEntryAdvancesAndRemovingTheLastOneClearsPlayback() {
        val removedA = ok(apply(playingA, HOST, Command.Remove("a")))
        assertEquals("b", removedA.currentEntryId)
        assertEquals(listOf("b", "c"), removedA.queue.map { it.entryId })

        val onlyC = roomState(queue = listOf(entry("c")), currentEntryId = "c", playback = Playback(true, 0, now))
        val cleared = ok(apply(onlyC, HOST, Command.Remove("c")))
        assertNull(cleared.currentEntryId)
        assertEquals(Playback(), cleared.playback)
        assertTrue(cleared.queue.isEmpty())
    }

    @Test
    fun moveWithoutAnAnchorPlacesTheEntryRightAfterCurrent() {
        assertEquals(listOf("a", "c", "b"), ok(apply(playingA, HOST, Command.Move("c", null))).queue.map { it.entryId })
    }

    @Test
    fun moveRejectsTheCurrentEntryUnknownIdsAndSelfAnchors() {
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("a", "c"))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("zzz", null))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("b", "b"))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Move("b", "zzz"))))
    }

    @Test
    fun kickRemovesTheMemberAndReportsTheEffect() {
        val outcome = apply(playingA, HOST, Command.Kick(GUEST)) as Outcome.Ok
        assertEquals(Effect.Kicked(GUEST), outcome.effect)
        assertEquals(listOf(HOST), outcome.state.members.map { it.id })
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Kick(HOST))))
    }

    @Test
    fun transferHostMovesTheFlagAndRefusesDisconnectedTargets() {
        val moved = ok(apply(playingA, HOST, Command.TransferHost(GUEST)))
        assertEquals(GUEST, moved.hostId)
        assertEquals(mapOf(HOST to false, GUEST to true), moved.members.associate { it.id to it.isHost })
        assertEquals(ErrorCode.INVALID, rejected(apply(roomState(guestConnected = false), HOST, Command.TransferHost(GUEST))))
    }

    @Test
    fun endRoomReportsTheClosedEffect() {
        assertEquals(Effect.Closed, (apply(playingA, HOST, Command.EndRoom) as Outcome.Ok).effect)
    }

    @Test
    fun trackEndedAdvancesOnlyForTheCurrentEntryNearItsEnd() {
        val nearEnd = playingA.copy(playback = Playback(true, 195_000, now))
        val advanced = RoomLogic.onTrackEnded(nearEnd, "a", now)
        assertEquals("b", advanced?.currentEntryId)
        assertNull(RoomLogic.onTrackEnded(nearEnd, "b", now)) // not the current entry
        assertNull(RoomLogic.onTrackEnded(playingA, "a", now)) // 15 s in: stale report
        assertNull(RoomLogic.onTrackEnded(advanced!!, "a", now)) // duplicate after advancing
    }

    @Test
    fun tickAdvancesOnlyAfterTheFallbackSlack() {
        val atEnd = playingA.copy(playback = Playback(true, 200_000, now))
        assertNull(RoomLogic.tick(atEnd, now + Limits.FALLBACK_SLACK_MS - 1))
        assertEquals("b", RoomLogic.tick(atEnd, now + Limits.FALLBACK_SLACK_MS)?.currentEntryId)
    }

    @Test
    fun newRoomStartsAtTheRequestedIndexAfterTheDelay() {
        val create = ClientMessage.Create(PROTOCOL_VERSION, "Host", listOf(newEntry("x"), newEntry("y")), startIndex = 1, startPositionMs = 30_000)
        val state = RoomLogic.newRoom("ABCDEF", HOST, "Host", create, now, ids)
        assertEquals(state.queue[1].entryId, state.currentEntryId)
        assertEquals(Playback(true, 30_000, now + Limits.START_DELAY_MS), state.playback)
        assertEquals(listOf(Member(HOST, "Host", true, true, now)), state.members)
        assertNull(RoomLogic.newRoom("ABCDEF", HOST, "Host", create.copy(initialQueue = emptyList()), now, ids).currentEntryId)
    }

    @Test
    fun cleanNameTrimsAndBoundsLength() {
        assertEquals("Budi", RoomLogic.cleanName("  Budi "))
        assertNull(RoomLogic.cleanName("   "))
        assertNull(RoomLogic.cleanName("x".repeat(Limits.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun nextHostIsTheEarliestConnectedMemberOtherThanTheOneLeaving() {
        val three = playingA.copy(members = playingA.members + Member("late", "Late", false, true, 99))
        assertEquals(GUEST, RoomLogic.nextHost(three, excluding = HOST))
        val guestOffline = three.copy(members = three.members.map { if (it.id == GUEST) it.copy(connected = false) else it })
        assertEquals("late", RoomLogic.nextHost(guestOffline, excluding = HOST))
        assertNull(RoomLogic.nextHost(roomState(guestConnected = false), excluding = HOST))
    }

    @Test
    fun removingTheHostHandsTheRoleToTheNextHost() {
        val after = RoomLogic.removeMembers(playingA, setOf(HOST))
        assertEquals(GUEST, after.hostId)
        assertTrue(after.members.single().isHost)
    }

    @Test
    fun urlEntriesNeedAnHttpUrlAndKeepTheirKind() {
        fun url(u: String?) = NewEntry("", "Clip", "", null, 60_000, MediaKind.URL, u)
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url(null))))))
        assertEquals(ErrorCode.INVALID, rejected(apply(playingA, HOST, Command.Add(listOf(url("ftp://x/v.mp4"))))))
        val added = ok(apply(playingA, HOST, Command.Add(listOf(url("https://cdn.example/v.mp4"))))).queue.last()
        assertEquals(MediaKind.URL, added.kind)
        assertEquals("https://cdn.example/v.mp4", added.url)
        val video = ok(apply(playingA, HOST, Command.Add(listOf(newEntry().copy(kind = MediaKind.YOUTUBE_VIDEO))))).queue.last()
        assertEquals(MediaKind.YOUTUBE_VIDEO, video.kind)
    }

    @Test
    fun liveEntriesNeverAutoAdvanceSeekFreelyAndAcceptAnyEndReport() {
        val live = QueueEntry("live", "", "Live", "", null, 0, HOST, MediaKind.URL, "https://example.com/live.m3u8")
        val state = roomState(queue = listOf(live, entry("b")), currentEntryId = "live", playback = Playback(true, 0, now))
        assertNull(RoomLogic.tick(state, now + 24 * 3_600_000L))
        assertEquals(Playback(true, 7_200_000, now), ok(apply(state, HOST, Command.Seek(7_200_000))).playback)
        assertEquals("b", RoomLogic.onTrackEnded(state, "live", now + 1_000)?.currentEntryId)
        val ended = ok(apply(state.copy(queue = listOf(live)), HOST, Command.Next))
        assertEquals(false, ended.playback.isPlaying)
    }
}
```

Run: `./gradlew -p ritme-server test`
Expected: FAIL (`Unresolved reference: RoomLogic`, `Outcome`, `Effect`).

- [ ] **Step 3: Implement `RoomLogic`**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry
import com.example.musicfy.ritme.protocol.isLive
import com.example.musicfy.ritme.protocol.positionAt

/** Side effects a command asks the stateful [Room] to carry out. */
sealed interface Effect {
    data class Kicked(val memberId: String) : Effect

    data object Closed : Effect
}

sealed interface Outcome {
    /** [state] is the same instance when nothing changed (no broadcast needed). */
    data class Ok(val state: RoomState, val effect: Effect? = null) : Outcome

    data class Rejected(val code: ErrorCode) : Outcome
}

/** Pure room rules: (state, who, what, when) -> outcome. No I/O, no locks, no clock. */
object RoomLogic {
    private val FORBIDDEN = Outcome.Rejected(ErrorCode.FORBIDDEN)
    private val INVALID = Outcome.Rejected(ErrorCode.INVALID)

    fun apply(state: RoomState, by: String, cmd: Command, now: Long, ids: IdGen): Outcome {
        val isHost = by == state.hostId
        val canControl = isHost || state.settings.guestsCanControl
        fun control(block: () -> Outcome): Outcome = if (canControl) block() else FORBIDDEN
        fun hostOnly(block: () -> Outcome): Outcome = if (isHost) block() else FORBIDDEN

        return when (cmd) {
            Command.Play -> control { play(state, now) }
            Command.Pause -> control { pause(state, now) }
            is Command.Seek -> control { seek(state, cmd.positionMs, now) }
            Command.Next -> control { changed(advance(state, now)) }
            Command.Previous -> control { previous(state, now) }
            is Command.PlayEntry -> control {
                if (state.queue.any { it.entryId == cmd.entryId }) changed(startEntry(state, cmd.entryId, now)) else INVALID
            }
            is Command.Add -> add(state, by, cmd, now, ids)
            is Command.Remove -> remove(state, by, canControl, cmd.entryId, now)
            is Command.Move -> control { move(state, cmd.entryId, cmd.afterEntryId) }
            is Command.SetSettings -> hostOnly { changed(state.copy(settings = cmd.settings)) }
            is Command.Kick -> hostOnly { kick(state, by, cmd.memberId) }
            is Command.TransferHost -> hostOnly { transferHost(state, cmd.memberId) }
            Command.EndRoom -> hostOnly { Outcome.Ok(state, Effect.Closed) }
        }
    }

    fun newRoom(code: String, hostId: String, hostName: String, create: ClientMessage.Create, now: Long, ids: IdGen): RoomState {
        val queue = create.initialQueue
            .filter { it.isValid() }
            .take(Limits.MAX_QUEUE)
            .map { it.toQueueEntry(ids.entryId(), hostId) }
        val base = RoomState(
            code = code,
            version = 1,
            hostId = hostId,
            members = listOf(Member(hostId, hostName, isHost = true, connected = true, joinedAt = now)),
            queue = queue,
        )
        val start = queue.getOrNull(create.startIndex) ?: queue.firstOrNull() ?: return base
        val startAt = if (start.isLive) create.startPositionMs.coerceAtLeast(0) else create.startPositionMs.coerceIn(0, start.durationMs)
        return base.copy(
            currentEntryId = start.entryId,
            playback = Playback(true, startAt, now + Limits.START_DELAY_MS),
        )
    }

    /**
     * Advances on the first plausible end report for the current entry; null means ignore.
     * Live entries have no known end, so any report for them counts (the stream ended).
     */
    fun onTrackEnded(state: RoomState, entryId: String, now: Long): RoomState? {
        val current = state.currentEntry() ?: return null
        if (current.entryId != entryId || !state.playback.isPlaying) return null
        if (!current.isLive && state.positionAt(now) < current.durationMs - Limits.TRACK_ENDED_TOLERANCE_MS) return null
        return bump(advance(state, now))
    }

    /** Fallback when no client reports the end; null means nothing to do. Never for live entries. */
    fun tick(state: RoomState, now: Long): RoomState? {
        val current = state.currentEntry() ?: return null
        if (!state.playback.isPlaying || current.isLive) return null
        val raw = state.playback.positionMs + (now - state.playback.anchorServerTimeMs)
        return if (raw >= current.durationMs + Limits.FALLBACK_SLACK_MS) bump(advance(state, now)) else null
    }

    fun cleanName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= Limits.MAX_NAME_LENGTH }

    fun nextHost(state: RoomState, excluding: String): String? =
        state.members.filter { it.connected && it.id != excluding }.minByOrNull { it.joinedAt }?.id

    fun addMember(state: RoomState, member: Member): RoomState = bump(state.copy(members = state.members + member))

    fun setConnected(state: RoomState, memberId: String, connected: Boolean): RoomState =
        bump(state.copy(members = state.members.map { if (it.id == memberId) it.copy(connected = connected) else it }))

    /** Removes members; if the host is among them the role goes to [nextHost]. */
    fun removeMembers(state: RoomState, memberIds: Set<String>): RoomState {
        val remaining = state.copy(members = state.members.filterNot { it.id in memberIds })
        val newHost = if (state.hostId in memberIds) nextHost(remaining, excluding = state.hostId) else null
        return bump(if (newHost != null) withHost(remaining, newHost) else remaining)
    }

    fun withHost(state: RoomState, hostId: String): RoomState =
        state.copy(hostId = hostId, members = state.members.map { it.copy(isHost = it.id == hostId) })

    fun startEntry(state: RoomState, entryId: String, now: Long): RoomState =
        state.copy(currentEntryId = entryId, playback = Playback(true, 0, now + Limits.START_DELAY_MS))

    /** Next entry, or stop at the end of the last one. With nothing current, starts the first. */
    fun advance(state: RoomState, now: Long): RoomState {
        val index = state.queue.indexOfFirst { it.entryId == state.currentEntryId }
        val next = state.queue.getOrNull(index + 1)
        if (next != null) return startEntry(state, next.entryId, now)
        val current = state.currentEntry() ?: return state.copy(playback = Playback())
        val stopAt = if (current.isLive) state.positionAt(now) else current.durationMs
        return state.copy(playback = Playback(false, stopAt, now))
    }

    private fun play(state: RoomState, now: Long): Outcome {
        val current = state.currentEntry()
            ?: return state.queue.firstOrNull()?.let { changed(startEntry(state, it.entryId, now)) } ?: Outcome.Ok(state)
        if (state.playback.isPlaying) return Outcome.Ok(state)
        val position = state.positionAt(now)
        val from = if (!current.isLive && position >= current.durationMs) 0 else position
        return changed(state.copy(playback = Playback(true, from, now)))
    }

    private fun pause(state: RoomState, now: Long): Outcome {
        if (state.currentEntry() == null || !state.playback.isPlaying) return Outcome.Ok(state)
        return changed(state.copy(playback = Playback(false, state.positionAt(now), now)))
    }

    private fun seek(state: RoomState, positionMs: Long, now: Long): Outcome {
        val current = state.currentEntry() ?: return INVALID
        val clamped = if (current.isLive) positionMs.coerceAtLeast(0) else positionMs.coerceIn(0, current.durationMs)
        return changed(state.copy(playback = Playback(state.playback.isPlaying, clamped, now)))
    }

    private fun previous(state: RoomState, now: Long): Outcome {
        val current = state.currentEntry() ?: return INVALID
        val previous = state.queue.getOrNull(state.queue.indexOf(current) - 1)
        return if (previous == null || state.positionAt(now) > Limits.PREVIOUS_RESTART_MS) {
            seek(state, 0, now)
        } else {
            changed(startEntry(state, previous.entryId, now))
        }
    }

    private fun add(state: RoomState, by: String, cmd: Command.Add, now: Long, ids: IdGen): Outcome {
        if (cmd.entries.isEmpty() || cmd.entries.any { !it.isValid() }) return INVALID
        if (state.queue.size + cmd.entries.size > Limits.MAX_QUEUE) return Outcome.Rejected(ErrorCode.QUEUE_FULL)
        val added = cmd.entries.map { it.toQueueEntry(ids.entryId(), by) }
        val currentIndex = state.queue.indexOfFirst { it.entryId == state.currentEntryId }
        val queue = if (cmd.playNext && currentIndex >= 0) {
            state.queue.toMutableList().apply { addAll(currentIndex + 1, added) }
        } else {
            state.queue + added
        }
        val withQueue = state.copy(queue = queue)
        return changed(if (state.currentEntryId == null) startEntry(withQueue, added.first().entryId, now) else withQueue)
    }

    private fun remove(state: RoomState, by: String, canControl: Boolean, entryId: String, now: Long): Outcome {
        val entry = state.queue.firstOrNull { it.entryId == entryId } ?: return INVALID
        if (entry.addedBy != by && !canControl) return FORBIDDEN
        val base = if (entryId == state.currentEntryId) {
            val advanced = advance(state, now)
            if (advanced.currentEntryId == entryId) advanced.copy(currentEntryId = null, playback = Playback()) else advanced
        } else {
            state
        }
        return changed(base.copy(queue = base.queue.filterNot { it.entryId == entryId }))
    }

    private fun move(state: RoomState, entryId: String, afterEntryId: String?): Outcome {
        if (entryId == state.currentEntryId || entryId == afterEntryId) return INVALID
        val entry = state.queue.firstOrNull { it.entryId == entryId } ?: return INVALID
        val rest = state.queue.filterNot { it.entryId == entryId }
        val anchorId = afterEntryId ?: state.currentEntryId
        val insertAt = if (anchorId == null) {
            0
        } else {
            val anchorIndex = rest.indexOfFirst { it.entryId == anchorId }
            if (anchorIndex < 0) return INVALID
            anchorIndex + 1
        }
        return changed(state.copy(queue = rest.toMutableList().apply { add(insertAt, entry) }))
    }

    private fun kick(state: RoomState, by: String, target: String): Outcome {
        if (target == by || state.members.none { it.id == target }) return INVALID
        return Outcome.Ok(bump(state.copy(members = state.members.filterNot { it.id == target })), Effect.Kicked(target))
    }

    private fun transferHost(state: RoomState, target: String): Outcome {
        val member = state.members.firstOrNull { it.id == target } ?: return INVALID
        if (!member.connected || target == state.hostId) return INVALID
        return changed(withHost(state, target))
    }

    private fun bump(state: RoomState): RoomState = state.copy(version = state.version + 1)

    private fun changed(state: RoomState): Outcome = Outcome.Ok(bump(state))

    private fun NewEntry.isValid(): Boolean {
        val source = when (kind) {
            MediaKind.YOUTUBE_MUSIC, MediaKind.YOUTUBE_VIDEO ->
                videoId.isNotBlank() && videoId.length <= Limits.MAX_VIDEO_ID_LENGTH
            MediaKind.URL -> url != null && url.length <= Limits.MAX_URL_LENGTH &&
                (url.startsWith("https://") || url.startsWith("http://"))
        }
        return source &&
            title.isNotBlank() && title.length <= Limits.MAX_TEXT_LENGTH &&
            artist.length <= Limits.MAX_TEXT_LENGTH &&
            (thumbnailUrl?.length ?: 0) <= Limits.MAX_URL_LENGTH &&
            durationMs >= 0
    }

    private fun NewEntry.toQueueEntry(entryId: String, addedBy: String) =
        QueueEntry(entryId, videoId.trim(), title.trim(), artist.trim(), thumbnailUrl, durationMs, addedBy, kind, url?.trim())
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew -p ritme-server test`
Expected: PASS (2 + 28 = 30 tests).

- [ ] **Step 5: Commit**

```bash
git add ritme-server/src
git commit -m "feat(ritme): pure room rules with permission matrix and playback transitions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Stateful `Room` and `RoomRegistry`

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RateLimiter.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Room.kt`
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomRegistry.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/FakePeer.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomRegistryTest.kt`

**Interfaces:**
- Consumes: `RoomLogic`, `Outcome`, `Effect`, `Limits`, `IdGen`, `Clock` (Tasks 2–3); test helpers from Task 3.
- Produces:
  - `class RateLimiter(capacity: Int, refillPerSecond: Double, clock: Clock) { fun tryAcquire(): Boolean }` (not thread-safe)
  - `interface Peer { suspend fun send(message: ServerMessage); suspend fun close() }`
  - `class Room` with `state`, `closed`, `code`, `suspend attachCreator(memberId, peer)`, `join(name, resumeToken, peer): JoinResult`, `leave(memberId)`, `disconnected(memberId, peer)`, `command(memberId, reqId, cmd)`, `trackEnded(memberId, entryId)`, `sweep(): Boolean`
  - `sealed interface JoinResult { data class Joined(room: Room, memberId: String); data class Rejected(code: ErrorCode) }`
  - `class RoomRegistry(clock, ids)` with `size`, `get(code)`, `suspend create(Create, peer)`, `suspend join(Join, peer)`, `suspend sweep()`

- [ ] **Step 1: Write the failing tests**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/FakePeer.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ServerMessage

class FakePeer : Peer {
    val received = mutableListOf<ServerMessage>()
    var closed = false
        private set

    override suspend fun send(message: ServerMessage) {
        received += message
    }

    override suspend fun close() {
        closed = true
    }

    inline fun <reified T : ServerMessage> last(): T = received.filterIsInstance<T>().last()
}
```

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomRegistryTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRegistryTest {
    private val clock = FakeClock()
    private val ids = SeqIds()
    private val registry = RoomRegistry(clock, ids)

    private suspend fun create(peer: FakePeer = FakePeer(), queue: List<NewEntry> = emptyList()): JoinResult.Joined =
        registry.create(ClientMessage.Create(PROTOCOL_VERSION, "Host", queue), peer) as JoinResult.Joined

    private suspend fun join(code: String, peer: FakePeer = FakePeer(), token: String? = null): JoinResult =
        registry.join(ClientMessage.Join(PROTOCOL_VERSION, code, "Guest", token), peer)

    @Test
    fun createWelcomesTheHost() = runBlocking {
        val peer = FakePeer()
        val host = create(peer)
        val welcome = peer.last<ServerMessage.Welcome>()
        assertEquals(host.memberId, welcome.memberId)
        assertEquals(host.memberId, welcome.state.hostId)
        assertEquals(clock.now, welcome.serverTime)
        assertSame(host.room, registry.get(welcome.state.code))
    }

    @Test
    fun createAndJoinValidateVersionNameAndCode() = runBlocking {
        assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.create(ClientMessage.Create(PROTOCOL_VERSION + 1, "Host"), FakePeer()))
        assertEquals(JoinResult.Rejected(ErrorCode.INVALID), registry.create(ClientMessage.Create(PROTOCOL_VERSION, "  "), FakePeer()))
        val code = create().room.code
        assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.join(ClientMessage.Join(PROTOCOL_VERSION + 1, code, "G"), FakePeer()))
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND), join("NOPE00"))
    }

    @Test
    fun joinIsCaseInsensitiveAndBroadcastsToExistingMembers() = runBlocking {
        val hostPeer = FakePeer()
        val code = create(hostPeer).room.code
        val guest = join(" ${code.lowercase()} ") as JoinResult.Joined
        val seenByHost = hostPeer.last<ServerMessage.State>().state
        assertEquals(listOf("Host", "Guest"), seenByHost.members.map { it.name })
        assertTrue(seenByHost.members.single { it.id == guest.memberId }.connected)
    }

    @Test
    fun roomRejectsTheMemberAfterTheLimit() = runBlocking {
        val code = create().room.code
        repeat(Limits.MAX_MEMBERS - 1) { assertTrue(join(code) is JoinResult.Joined) }
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_FULL), join(code))
    }

    @Test
    fun resumeTokenRestoresTheSameMemberWithinGrace() = runBlocking {
        val code = create().room.code
        val firstPeer = FakePeer()
        val guest = join(code, firstPeer) as JoinResult.Joined
        val token = firstPeer.last<ServerMessage.Welcome>().resumeToken
        guest.room.disconnected(guest.memberId, firstPeer)
        assertFalse(guest.room.state.members.single { it.id == guest.memberId }.connected)

        clock.now += Limits.GRACE_MS - 1
        guest.room.sweep()
        val secondPeer = FakePeer()
        val again = join(code, secondPeer, token) as JoinResult.Joined
        assertEquals(guest.memberId, again.memberId)
        assertTrue(again.room.state.members.single { it.id == guest.memberId }.connected)
        assertEquals(token, secondPeer.last<ServerMessage.Welcome>().resumeToken)
    }

    @Test
    fun staleDisconnectFromAReplacedConnectionIsIgnored() = runBlocking {
        val code = create().room.code
        val oldPeer = FakePeer()
        val guest = join(code, oldPeer) as JoinResult.Joined
        val token = oldPeer.last<ServerMessage.Welcome>().resumeToken
        join(code, FakePeer(), token)
        assertTrue(oldPeer.closed)
        guest.room.disconnected(guest.memberId, oldPeer)
        assertTrue(guest.room.state.members.single { it.id == guest.memberId }.connected)
    }

    @Test
    fun hostRoleMovesWhenTheHostMissesTheGracePeriod() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val guest = join(host.room.code) as JoinResult.Joined
        host.room.disconnected(host.memberId, hostPeer)
        clock.now += Limits.GRACE_MS
        assertFalse(host.room.sweep())
        assertEquals(guest.memberId, host.room.state.hostId)
        assertEquals(listOf(guest.memberId), host.room.state.members.map { it.id })
    }

    @Test
    fun hostLeavingHandsOverImmediately() = runBlocking {
        val host = create()
        val guest = join(host.room.code) as JoinResult.Joined
        host.room.leave(host.memberId)
        assertEquals(guest.memberId, host.room.state.hostId)
    }

    @Test
    fun emptyRoomIsDeletedSixtySecondsAfterTheLastConnectionDrops() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val code = host.room.code
        host.room.disconnected(host.memberId, hostPeer)
        registry.sweep()
        clock.now += Limits.EMPTY_ROOM_MS - 1
        registry.sweep()
        assertNotNull(registry.get(code))
        clock.now += 1
        registry.sweep()
        assertNull(registry.get(code))
    }

    @Test
    fun commandsBeyondTheRateLimitAreRejected() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry()))
        repeat(Limits.COMMANDS_PER_SECOND) { host.room.command(host.memberId, "r$it", Command.Pause) }
        host.room.command(host.memberId, "over", Command.Pause)
        assertEquals(ServerMessage.Error("over", ErrorCode.RATE_LIMITED), hostPeer.last<ServerMessage.Error>())
        clock.now += 1_000
        host.room.command(host.memberId, "later", Command.Play)
        assertFalse(hostPeer.received.contains(ServerMessage.Error("later", ErrorCode.RATE_LIMITED)))
    }

    @Test
    fun kickedMemberIsDisconnectedAndItsTokenStopsWorking() = runBlocking {
        val host = create()
        val guestPeer = FakePeer()
        val guest = join(host.room.code, guestPeer) as JoinResult.Joined
        val token = guestPeer.last<ServerMessage.Welcome>().resumeToken
        host.room.command(host.memberId, "k", Command.Kick(guest.memberId))
        assertEquals(ServerMessage.Kicked, guestPeer.received.last())
        assertTrue(guestPeer.closed)
        val rejoined = join(host.room.code, FakePeer(), token) as JoinResult.Joined
        assertNotEquals(guest.memberId, rejoined.memberId)
    }

    @Test
    fun endRoomTellsEveryoneAndTheRoomIsGoneAfterSweep() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val guestPeer = FakePeer()
        join(host.room.code, guestPeer)
        host.room.command(host.memberId, "end", Command.EndRoom)
        assertEquals(ServerMessage.RoomClosed, guestPeer.received.last())
        assertTrue(hostPeer.closed && guestPeer.closed)
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND), join(host.room.code))
        registry.sweep()
        assertNull(registry.get(host.room.code))
    }

    @Test
    fun trackEndedNearTheEndAdvancesForEveryone() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry("a"), newEntry("b")))
        val guest = join(host.room.code) as JoinResult.Joined
        val first = host.room.state.queue[0].entryId
        clock.now += Limits.START_DELAY_MS + 195_000
        guest.room.trackEnded(guest.memberId, first)
        assertEquals(host.room.state.queue[1].entryId, hostPeer.last<ServerMessage.State>().state.currentEntryId)
    }

    @Test
    fun sweepRunsTheFallbackAdvance() = runBlocking {
        val host = create(queue = listOf(newEntry("a", 10_000), newEntry("b", 10_000)))
        clock.now += Limits.START_DELAY_MS + 10_000 + Limits.FALLBACK_SLACK_MS
        host.room.sweep()
        assertEquals(host.room.state.queue[1].entryId, host.room.state.currentEntryId)
    }
}
```

Run: `./gradlew -p ritme-server test`
Expected: FAIL (`Unresolved reference: Peer`, `RoomRegistry`, `JoinResult`).

- [ ] **Step 2: Implement the rate limiter**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RateLimiter.kt`:
```kotlin
package com.example.musicfy.ritme.server

/** Token bucket. Not thread-safe: callers serialize access (room mutex, or synchronized). */
class RateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val clock: Clock,
) {
    private var tokens = capacity.toDouble()
    private var last = clock.now()

    fun tryAcquire(): Boolean {
        val now = clock.now()
        tokens = minOf(capacity.toDouble(), tokens + (now - last) * refillPerSecond / 1_000.0)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
```

- [ ] **Step 3: Implement `Room` and `RoomRegistry`**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Room.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One client connection, as the room sees it. */
interface Peer {
    suspend fun send(message: ServerMessage)

    suspend fun close()
}

/**
 * Live room: applies [RoomLogic] under a mutex, tracks connections, resume tokens and grace
 * periods, and fans state out to every connected peer.
 */
class Room(initial: RoomState, private val clock: Clock, private val ids: IdGen) {
    private val mutex = Mutex()
    private val peers = HashMap<String, Peer>()
    private val tokens = HashMap<String, String>() // resumeToken -> memberId
    private val disconnectedAt = HashMap<String, Long>()
    private val limiters = HashMap<String, RateLimiter>()
    private var emptySince: Long? = null

    @Volatile
    var state: RoomState = initial
        private set

    @Volatile
    var closed: Boolean = false
        private set

    val code: String get() = state.code

    /** The creator's Member is already in the initial state; this wires up their connection. */
    suspend fun attachCreator(memberId: String, peer: Peer) = mutex.withLock {
        val token = ids.resumeToken()
        tokens[token] = memberId
        peers[memberId] = peer
        peer.send(ServerMessage.Welcome(memberId, token, state, clock.now()))
    }

    suspend fun join(name: String, resumeToken: String?, peer: Peer): JoinResult = mutex.withLock {
        if (closed) return@withLock JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND)
        val now = clock.now()
        val resumedId = resumeToken?.let { tokens[it] }?.takeIf { id -> state.members.any { it.id == id } }
        val memberId: String
        val token: String
        if (resumedId != null && resumeToken != null) {
            memberId = resumedId
            token = resumeToken
            val previous = peers.put(memberId, peer)
            if (previous != null && previous !== peer) runCatching { previous.close() }
            disconnectedAt.remove(memberId)
            state = RoomLogic.setConnected(state, memberId, true)
        } else {
            if (state.members.size >= Limits.MAX_MEMBERS) return@withLock JoinResult.Rejected(ErrorCode.ROOM_FULL)
            memberId = ids.memberId()
            token = ids.resumeToken()
            tokens[token] = memberId
            peers[memberId] = peer
            state = RoomLogic.addMember(state, Member(memberId, name, isHost = false, connected = true, joinedAt = now))
        }
        emptySince = null
        peer.send(ServerMessage.Welcome(memberId, token, state, now))
        broadcast(ServerMessage.State(state), except = memberId)
        JoinResult.Joined(this, memberId)
    }

    suspend fun leave(memberId: String) = mutex.withLock {
        forget(memberId)
        if (state.members.any { it.id == memberId }) {
            state = RoomLogic.removeMembers(state, setOf(memberId))
            broadcast(ServerMessage.State(state))
        }
    }

    /** Ignored unless [peer] is still this member's live connection (a reconnect replaces it). */
    suspend fun disconnected(memberId: String, peer: Peer) = mutex.withLock {
        if (peers[memberId] !== peer) return@withLock
        peers.remove(memberId)
        if (state.members.none { it.id == memberId }) return@withLock
        disconnectedAt[memberId] = clock.now()
        state = RoomLogic.setConnected(state, memberId, false)
        broadcast(ServerMessage.State(state))
    }

    suspend fun command(memberId: String, reqId: String, cmd: Command) = mutex.withLock {
        val sender = peers[memberId] ?: return@withLock
        val limiter = limiters.getOrPut(memberId) {
            RateLimiter(Limits.COMMANDS_PER_SECOND, Limits.COMMANDS_PER_SECOND.toDouble(), clock)
        }
        if (!limiter.tryAcquire()) {
            sender.send(ServerMessage.Error(reqId, ErrorCode.RATE_LIMITED))
            return@withLock
        }
        when (val outcome = RoomLogic.apply(state, memberId, cmd, clock.now(), ids)) {
            is Outcome.Rejected -> sender.send(ServerMessage.Error(reqId, outcome.code))
            is Outcome.Ok -> {
                val changed = outcome.state !== state
                state = outcome.state
                when (val effect = outcome.effect) {
                    is Effect.Kicked -> {
                        val kicked = peers[effect.memberId]
                        forget(effect.memberId)
                        if (kicked != null) runCatching {
                            kicked.send(ServerMessage.Kicked)
                            kicked.close()
                        }
                    }
                    Effect.Closed -> {
                        closeAll()
                        return@withLock
                    }
                    null -> Unit
                }
                if (changed) broadcast(ServerMessage.State(state))
            }
        }
    }

    suspend fun trackEnded(memberId: String, entryId: String) = mutex.withLock {
        if (memberId !in peers) return@withLock
        val next = RoomLogic.onTrackEnded(state, entryId, clock.now()) ?: return@withLock
        state = next
        broadcast(ServerMessage.State(next))
    }

    /** Expires members past grace, runs the fallback advance; true when the room should be deleted. */
    suspend fun sweep(): Boolean = mutex.withLock {
        if (closed) return@withLock true
        val now = clock.now()
        var next = state
        val expired = disconnectedAt.filterValues { now - it >= Limits.GRACE_MS }.keys.toSet()
        if (expired.isNotEmpty()) {
            expired.forEach { forget(it) }
            next = RoomLogic.removeMembers(next, expired)
        }
        RoomLogic.tick(next, now)?.let { next = it }
        if (next !== state) {
            state = next
            broadcast(ServerMessage.State(next))
        }
        if (next.members.any { it.connected }) {
            emptySince = null
        } else if (emptySince == null) {
            emptySince = now
        }
        emptySince?.let { now - it >= Limits.EMPTY_ROOM_MS } ?: false
    }

    private fun forget(memberId: String) {
        peers.remove(memberId)
        tokens.entries.removeIf { it.value == memberId }
        disconnectedAt.remove(memberId)
        limiters.remove(memberId)
    }

    private suspend fun closeAll() {
        closed = true
        peers.values.forEach { peer ->
            runCatching {
                peer.send(ServerMessage.RoomClosed)
                peer.close()
            }
        }
        peers.clear()
        tokens.clear()
    }

    private suspend fun broadcast(message: ServerMessage, except: String? = null) {
        peers.forEach { (id, peer) -> if (id != except) runCatching { peer.send(message) } }
    }
}
```

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomRegistry.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import java.util.concurrent.ConcurrentHashMap

sealed interface JoinResult {
    data class Joined(val room: Room, val memberId: String) : JoinResult

    data class Rejected(val code: ErrorCode) : JoinResult
}

class RoomRegistry(private val clock: Clock, private val ids: IdGen) {
    private val rooms = ConcurrentHashMap<String, Room>()

    val size: Int get() = rooms.size

    fun get(code: String): Room? = rooms[normalize(code)]

    suspend fun create(msg: ClientMessage.Create, peer: Peer): JoinResult {
        if (msg.protocolVersion != PROTOCOL_VERSION) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        val hostId = ids.memberId()
        repeat(CODE_ATTEMPTS) {
            val code = ids.roomCode()
            val room = Room(RoomLogic.newRoom(code, hostId, name, msg, clock.now(), ids), clock, ids)
            if (rooms.putIfAbsent(code, room) == null) {
                room.attachCreator(hostId, peer)
                return JoinResult.Joined(room, hostId)
            }
        }
        error("no free room code after $CODE_ATTEMPTS attempts")
    }

    suspend fun join(msg: ClientMessage.Join, peer: Peer): JoinResult {
        if (msg.protocolVersion != PROTOCOL_VERSION) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        val room = get(msg.code) ?: return JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND)
        return room.join(name, msg.resumeToken, peer)
    }

    suspend fun sweep() {
        for (room in rooms.values) {
            if (room.sweep()) rooms.remove(room.code, room)
        }
    }

    private fun normalize(code: String) = code.trim().uppercase()

    private companion object {
        const val CODE_ATTEMPTS = 20
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew -p ritme-server test`
Expected: PASS (30 + 14 = 44 tests).

- [ ] **Step 5: Commit**

```bash
git add ritme-server/src
git commit -m "feat(ritme): live rooms with resume tokens, grace period, host hand-off and rate limit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: WebSocket endpoint `/ritme`

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RitmeSocket.kt`
- Modify (full replacement): `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RitmeSocketTest.kt`

**Interfaces:**
- Consumes: `RoomRegistry`, `JoinResult`, `Peer` (Task 4); protocol encode/decode (Task 1).
- Produces: `class ServerDeps(config, clock = SystemClock, ids: IdGen = SecureIdGen())`; WebSocket route `/ritme`; a background sweep every `Limits.SWEEP_INTERVAL_MS`.

- [ ] **Step 1: Write the failing integration tests**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RitmeSocketTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RitmeSocketTest {
    private suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) =
        send(Frame.Text(message.encode()))

    private suspend fun DefaultClientWebSocketSession.receiveServer(): ServerMessage = withTimeout(5_000) {
        var frame = incoming.receive()
        while (frame !is Frame.Text) frame = incoming.receive()
        decodeServerMessage((frame as Frame.Text).readText())
    }

    @Test
    fun pingIsAnsweredBeforeJoining() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            sendClient(ClientMessage.Ping(42))
            assertEquals(42L, (receiveServer() as ServerMessage.Pong).clientTime)
        }
    }

    @Test
    fun joiningAnUnknownRoomIsRefused() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
    }

    @Test
    fun garbageIsAnsweredWithInvalid() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            send(Frame.Text("""{"type":"Nope"}"""))
            assertEquals(ServerMessage.Error(code = ErrorCode.INVALID), receiveServer())
        }
    }

    @Test
    fun twoClientsShareOneRoom() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            val host = this
            host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(NewEntry("dQw4w9WgXcQ", "Song", "Artist", null, 200_000))))
            val welcome = host.receiveServer() as ServerMessage.Welcome

            client.webSocket("/ritme") {
                val guest = this
                guest.sendClient(ClientMessage.Join(PROTOCOL_VERSION, welcome.state.code, "Sari"))
                val guestWelcome = guest.receiveServer() as ServerMessage.Welcome
                assertEquals(listOf("Budi", "Sari"), guestWelcome.state.members.map { it.name })
                assertEquals(2, (host.receiveServer() as ServerMessage.State).state.members.size)

                guest.sendClient(ClientMessage.Send("r1", Command.Pause))
                val seenByGuest = guest.receiveServer() as ServerMessage.State
                val seenByHost = host.receiveServer() as ServerMessage.State
                assertFalse(seenByGuest.state.playback.isPlaying)
                assertEquals(seenByGuest.state.version, seenByHost.state.version)
            }

            val afterGuestLeft = host.receiveServer() as ServerMessage.State
            assertFalse(afterGuestLeft.state.members.single { it.name == "Sari" }.connected)
        }
    }
}
```

Run: `./gradlew -p ritme-server test`
Expected: the 4 new tests FAIL (no `/ritme` route; the client gets a 404 handshake failure).

- [ ] **Step 2: Implement the socket adapter**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RitmeSocket.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class WebSocketPeer(private val session: DefaultWebSocketServerSession) : Peer {
    override suspend fun send(message: ServerMessage) {
        session.outgoing.send(Frame.Text(message.encode()))
    }

    override suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
    }
}

/**
 * One connection: Ping works any time; the first other message must be Create or Join; after
 * that, commands go to the room. Dropping the socket starts the member's grace period.
 */
suspend fun DefaultWebSocketServerSession.ritmeSession(registry: RoomRegistry, clock: Clock) {
    val peer = WebSocketPeer(this)
    var session: JoinResult.Joined? = null
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = runCatching { decodeClientMessage(frame.readText()) }.getOrNull()
            if (message == null) {
                peer.send(ServerMessage.Error(code = ErrorCode.INVALID))
                continue
            }
            if (message is ClientMessage.Ping) {
                peer.send(ServerMessage.Pong(message.clientTime, clock.now()))
                continue
            }
            val joined = session
            if (joined == null) {
                val result = when (message) {
                    is ClientMessage.Create -> registry.create(message, peer)
                    is ClientMessage.Join -> registry.join(message, peer)
                    else -> JoinResult.Rejected(ErrorCode.INVALID)
                }
                when (result) {
                    is JoinResult.Joined -> session = result
                    is JoinResult.Rejected -> {
                        peer.send(ServerMessage.Error(code = result.code))
                        peer.close()
                        return
                    }
                }
                continue
            }
            when (message) {
                is ClientMessage.Send -> joined.room.command(joined.memberId, message.reqId, message.cmd)
                is ClientMessage.TrackEnded -> joined.room.trackEnded(joined.memberId, message.entryId)
                ClientMessage.Leave -> {
                    session = null
                    joined.room.leave(joined.memberId)
                    peer.close()
                    return
                }
                is ClientMessage.Create, is ClientMessage.Join -> peer.send(ServerMessage.Error(code = ErrorCode.INVALID))
                is ClientMessage.Ping -> Unit
            }
        }
    } finally {
        val joined = session
        if (joined != null) {
            // The session may be cancelled here; the room still has to hear about it.
            withContext(NonCancellable) { joined.room.disconnected(joined.memberId, peer) }
        }
    }
}
```

- [ ] **Step 3: Wire it into the application**

Replace `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt` with:
```kotlin
package com.example.musicfy.ritme.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
    val ids: IdGen = SecureIdGen(),
)

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids)

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Limits.MAX_FRAME_BYTES
        masking = false
    }

    launch {
        while (isActive) {
            delay(Limits.SWEEP_INTERVAL_MS)
            runCatching { registry.sweep() }.onFailure { log.error("room sweep failed", it) }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/ritme") { ritmeSession(registry, deps.clock) }
    }
}
```
If the compiler reports `pingPeriod`/`timeout` as members rather than extensions, delete those two imports; the assignments stay the same.

- [ ] **Step 4: Run tests**

Run: `./gradlew -p ritme-server test`
Expected: PASS (44 + 4 = 48 tests).

- [ ] **Step 5: Commit**

```bash
git add ritme-server/src
git commit -m "feat(ritme): /ritme WebSocket endpoint and background room sweep

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `GET /api/search` (metadata only)

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Search.kt`
- Modify (full replacement): `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt`
- Create: `ritme-server/src/test/resources/innertube-search-minimal.json`
- Create (recorded): `ritme-server/src/test/resources/innertube-search-recorded.json`
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SearchTest.kt`

**Interfaces:**
- Consumes: `Clock`, `RateLimiter`, `Limits` (Tasks 2–4); `RitmeJson` (Task 1).
- Produces:
  - `@Serializable data class SearchResult(videoId, title, artist, durationMs, thumbnailUrl?)`
  - `fun interface SearchBackend { suspend fun fetch(query: String): String }`, `class InnerTubeSearchBackend(http: HttpClient)` (+ `companion fun requestBody(query): String`)
  - `object InnerTubeSearchParser { fun parse(json: String): List<SearchResult>; fun parseDuration(text: String): Long }`
  - `class SearchService(backend, clock, ttlMs = 600_000, maxEntries = 1_000) { suspend fun search(query): List<SearchResult> }`
  - `class IpRateLimiter(clock, perMinute = Limits.SEARCH_PER_MINUTE) { fun tryAcquire(ip): Boolean }`
  - `ServerDeps(..., search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock))`, `fun defaultHttpClient(): HttpClient`

- [ ] **Step 1: Fixtures**

`ritme-server/src/test/resources/innertube-search-minimal.json`:
```json
{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
{"musicResponsiveListItemRenderer":{
  "playlistItemData":{"videoId":"dQw4w9WgXcQ"},
  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://small.example/t.jpg"},{"url":"https://large.example/t.jpg"}]}}},
  "flexColumns":[
    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Never Gonna Give You Up"}]}}},
    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
      {"text":"Rick Astley","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
      {"text":" • "},
      {"text":"Whenever You Need Somebody","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
      {"text":" • "},
      {"text":"3:34"}
    ]}}}
  ]}},
{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"No video id here"}]}}}]}}
]}}]}}}}]}}}
```

Record a real response (metadata only, one request):
```bash
curl -s -X POST 'https://music.youtube.com/youtubei/v1/search?prettyPrint=false' \
  -H 'Content-Type: application/json' \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0' \
  -H 'X-Goog-Api-Format-Version: 1' -H 'X-YouTube-Client-Name: 67' -H 'X-YouTube-Client-Version: 1.20260707.12.00' \
  -H 'X-Origin: https://music.youtube.com' -H 'Referer: https://music.youtube.com/' \
  --data '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260707.12.00","hl":"en","gl":"US"}},"query":"never gonna give you up","params":"EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"}' \
  -o ritme-server/src/test/resources/innertube-search-recorded.json
wc -c ritme-server/src/test/resources/innertube-search-recorded.json
grep -o '"videoId":"[A-Za-z0-9_-]\{11\}"' ritme-server/src/test/resources/innertube-search-recorded.json | head -3
```
Expected: more than 20000 bytes, and at least one `"videoId":"…"`. If the file is small or has no videoIds (blocked/consent page), STOP and report BLOCKED with its first 500 bytes.

- [ ] **Step 2: Write the failing tests**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/SearchTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()
    private val minimal = resource("innertube-search-minimal.json")

    @Test
    fun parsesTitleArtistDurationAndLargestThumbnailAndSkipsItemsWithoutVideoId() {
        assertEquals(
            listOf(SearchResult("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 214_000, "https://large.example/t.jpg")),
            InnerTubeSearchParser.parse(minimal),
        )
    }

    @Test
    fun parsesDurations() {
        assertEquals(214_000, InnerTubeSearchParser.parseDuration("3:34"))
        assertEquals(3_723_000, InnerTubeSearchParser.parseDuration("1:02:03"))
    }

    @Test
    fun parsesARecordedResponse() {
        val results = InnerTubeSearchParser.parse(resource("innertube-search-recorded.json"))
        assertTrue("expected results", results.isNotEmpty())
        results.forEach {
            assertEquals(11, it.videoId.length)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.durationMs > 0)
        }
        assertTrue(results.first().artist.contains("Rick Astley"))
    }

    @Test
    fun requestBodyAsksForSongsFromTheWebRemixClient() {
        val body = RitmeJson.parseToJsonElement(InnerTubeSearchBackend.requestBody("abc")).jsonObject
        assertEquals("abc", body.getValue("query").jsonPrimitive.content)
        assertEquals(InnerTubeSearchBackend.SONGS_FILTER, body.getValue("params").jsonPrimitive.content)
        assertEquals("WEB_REMIX", body.getValue("context").jsonObject.getValue("client").jsonObject.getValue("clientName").jsonPrimitive.content)
    }

    @Test
    fun serviceCachesNormalizedQueriesUntilTheTtl() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = SearchService({ calls++; minimal }, clock, ttlMs = 1_000)
        service.search("Rick")
        service.search("  rick ")
        assertEquals(1, calls)
        clock.now += 1_000
        service.search("rick")
        assertEquals(2, calls)
    }

    @Test
    fun ipLimiterAllowsThirtyPerMinute() {
        val clock = FakeClock()
        val limiter = IpRateLimiter(clock)
        repeat(Limits.SEARCH_PER_MINUTE) { assertTrue(limiter.tryAcquire("1.2.3.4")) }
        assertEquals(false, limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("5.6.7.8"))
        clock.now += 2_000
        assertTrue(limiter.tryAcquire("1.2.3.4"))
    }

    private fun depsWith(backend: SearchBackend) = ServerDeps(
        config = ServerConfig(0, "https://ritme.test", File("build/no-web")),
        search = SearchService(backend, SystemClock),
    )

    @Test
    fun endpointReturnsJsonResults() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        val response = client.get("/api/search?q=rick")
        assertEquals(HttpStatusCode.OK, response.status)
        val results = RitmeJson.decodeFromString(ListSerializer(SearchResult.serializer()), response.bodyAsText())
        assertEquals("dQw4w9WgXcQ", results.single().videoId)
    }

    @Test
    fun endpointValidatesTheQuery() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/search?q=%20").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/search?q=" + "a".repeat(Limits.SEARCH_MAX_QUERY_LENGTH + 1)).status)
    }

    @Test
    fun endpointRateLimitsPerForwardedIp() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        repeat(Limits.SEARCH_PER_MINUTE) {
            assertEquals(HttpStatusCode.OK, client.get("/api/search?q=q$it") { header("X-Forwarded-For", "9.9.9.9") }.status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/search?q=more") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=other") { header("X-Forwarded-For", "8.8.8.8") }.status)
    }

    @Test
    fun endpointReportsBackendFailureAsBadGateway() = testApplication {
        application { ritmeModule(depsWith { error("down") }) }
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/search?q=rick").status)
    }
}
```

Run: `./gradlew -p ritme-server test`
Expected: FAIL (`Unresolved reference: SearchResult`, …).

- [ ] **Step 3: Implement search**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Search.kt`:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

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

/** One InnerTube `search` call, songs only. Metadata only: no stream URLs are ever requested. */
class InnerTubeSearchBackend(private val http: HttpClient) : SearchBackend {
    override suspend fun fetch(query: String): String {
        val response = http.post(URL) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-Goog-Api-Format-Version", "1")
            header("X-YouTube-Client-Name", CLIENT_ID)
            header("X-YouTube-Client-Version", CLIENT_VERSION)
            header("X-Origin", ORIGIN)
            header(HttpHeaders.Referrer, "$ORIGIN/")
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
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", SONGS_FILTER)
        }.toString()
    }
}

object InnerTubeSearchParser {
    private val durationPattern = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
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
        val duration = details.mapNotNull { it.at("text").asString() }.lastOrNull { durationPattern.matches(it) } ?: return null
        val thumbnail = item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
        return SearchResult(videoId, title, artist, parseDuration(duration), thumbnail)
    }

    private fun column(item: JsonElement, index: Int): List<JsonElement> =
        item.at("flexColumns", index, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").asArray()

    private fun JsonElement?.at(vararg path: Any): JsonElement? = path.fold(this) { node, key ->
        when (key) {
            is String -> (node as? JsonObject)?.get(key)
            is Int -> (node as? JsonArray)?.getOrNull(key)
            else -> null
        }
    }

    private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

class SearchService(
    private val backend: SearchBackend,
    private val clock: Clock,
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 1_000,
) {
    private class Cached(val at: Long, val results: List<SearchResult>)

    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean = size > maxEntries
    }

    suspend fun search(query: String): List<SearchResult> {
        val key = query.trim().lowercase()
        synchronized(cache) {
            cache[key]?.takeIf { clock.now() - it.at < ttlMs }?.let { return it.results }
        }
        val results = InnerTubeSearchParser.parse(backend.fetch(query.trim())).take(MAX_RESULTS)
        synchronized(cache) { cache[key] = Cached(clock.now(), results) }
        return results
    }

    companion object {
        const val MAX_RESULTS = 20
    }
}

class IpRateLimiter(private val clock: Clock, private val perMinute: Int = Limits.SEARCH_PER_MINUTE) {
    private val buckets = ConcurrentHashMap<String, RateLimiter>()

    fun tryAcquire(ip: String): Boolean {
        if (buckets.size > MAX_TRACKED_IPS) buckets.clear()
        val limiter = buckets.computeIfAbsent(ip) { RateLimiter(perMinute, perMinute / 60.0, clock) }
        return synchronized(limiter) { limiter.tryAcquire() }
    }

    private companion object {
        const val MAX_TRACKED_IPS = 10_000
    }
}
```

- [ ] **Step 4: Route + forwarded headers**

Replace `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt` with:
```kotlin
package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.seconds

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
    val ids: IdGen = SecureIdGen(),
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock),
)

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids)
    val searchLimiter = IpRateLimiter(deps.clock)

    // Only Caddy can reach this server, so its X-Forwarded-For is the real client IP.
    install(XForwardedHeaders)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Limits.MAX_FRAME_BYTES
        masking = false
    }

    launch {
        while (isActive) {
            delay(Limits.SWEEP_INTERVAL_MS)
            runCatching { registry.sweep() }.onFailure { log.error("room sweep failed", it) }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/ritme") { ritmeSession(registry, deps.clock) }

        get("/api/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty() || query.length > Limits.SEARCH_MAX_QUERY_LENGTH) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (!searchLimiter.tryAcquire(call.request.origin.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            val results = try {
                deps.search.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log the query: it is user content.
                application.log.warn("search backend failed: ${e::class.simpleName}")
                call.respond(HttpStatusCode.BadGateway)
                return@get
            }
            call.respondText(RitmeJson.encodeToString(ListSerializer(SearchResult.serializer()), results), ContentType.Application.Json)
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew -p ritme-server test`
Expected: PASS (48 + 10 = 58 tests).

- [ ] **Step 6: Commit**

```bash
git add ritme-server/src
git commit -m "feat(ritme): metadata-only song search with cache and per-IP limit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Landing page, Docker image, deploy files, CI

**Files:**
- Create: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Landing.kt`
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Application.kt` (add web routes)
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/LandingTest.kt`
- Create: `ritme-server/Dockerfile`, `ritme-server/Dockerfile.dockerignore`
- Create: `ritme-server/deploy/docker-compose.yml`, `ritme-server/deploy/Caddyfile`, `ritme-server/deploy/.env.example`
- Create: `ritme-server/README.md`
- Create: `.github/workflows/ritme-server.yml`

**Interfaces:**
- Consumes: `ServerConfig.webDir`, `Limits.ROOM_CODE_*` (Tasks 2–3).
- Produces: `object Landing { const val RELEASES_URL; fun page(rawCode: String?): String }`; routes `GET /` and `GET /r/{code}`; static web serving when `WEB_DIR/index.html` exists.

- [ ] **Step 1: Write the failing tests**

`ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/LandingTest.kt`:
```kotlin
package com.example.musicfy.ritme.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LandingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun validCodeGetsADeepLink() {
        val page = Landing.page("k7m2qx")
        assertTrue(page.contains("musicfy://ritme/K7M2QX"))
        assertTrue(page.contains(Landing.RELEASES_URL))
    }

    @Test
    fun invalidCodeIsNeverEchoed() {
        val page = Landing.page("<script>alert(1)</script>")
        assertFalse(page.contains("<script>alert"))
        assertFalse(page.contains("musicfy://ritme/"))
    }

    @Test
    fun shareLinkServesTheLandingPageWhenNoWebBuildExists() = testApplication {
        application { ritmeModule(testDeps(webDir = tmp.newFolder("empty"))) }
        val response = client.get("/r/K7M2QX")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("musicfy://ritme/K7M2QX"))
        assertTrue(client.get("/").bodyAsText().contains("Ritme"))
    }

    @Test
    fun webBuildIndexWinsWhenPresent() = testApplication {
        val web = tmp.newFolder("web").apply { resolve("index.html").writeText("<html>ritme-web</html>") }
        application { ritmeModule(testDeps(webDir = web)) }
        assertEquals("<html>ritme-web</html>", client.get("/r/K7M2QX").bodyAsText())
        assertEquals("<html>ritme-web</html>", client.get("/").bodyAsText())
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }
}
```

Run: `./gradlew -p ritme-server test`
Expected: FAIL (`Unresolved reference: Landing`).

- [ ] **Step 2: Implement the landing page**

`ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/Landing.kt`:
```kotlin
package com.example.musicfy.ritme.server

/** Shown at / and /r/{code} until the web client (ritme-web) is deployed into WEB_DIR. */
object Landing {
    const val RELEASES_URL = "https://github.com/realidkroo/musicfy/releases/latest"
    private val codePattern = Regex("^[${Limits.ROOM_CODE_ALPHABET}]{${Limits.ROOM_CODE_LENGTH}}$")

    /** Only a code matching the room-code alphabet is ever written into the page. */
    fun page(rawCode: String?): String {
        val code = rawCode?.trim()?.uppercase()?.takeIf { codePattern.matches(it) }
        val heading = if (code != null) "Kamu diajak dengar bareng" else "Ritme"
        val body = if (code != null) {
            """
            <p class="code">Kode room: <b>$code</b></p>
            <a class="btn" href="musicfy://ritme/$code">Buka di aplikasi Musicfy</a>
            """
        } else {
            """<p>Minta kode atau link room dari temanmu, lalu buka di aplikasi Musicfy.</p>"""
        }
        return """
            <!doctype html>
            <html lang="id">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ritme</title>
            <style>
              :root { color-scheme: light dark; --bg: #f6f6f4; --fg: #161616; --muted: #6b6b6b; --accent: #161616; --on-accent: #fff; }
              @media (prefers-color-scheme: dark) { :root { --bg: #111; --fg: #f2f2f2; --muted: #a0a0a0; --accent: #f2f2f2; --on-accent: #111; } }
              body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg); color: var(--fg);
                     font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
              main { max-width: 420px; padding: 32px 16px; text-align: center; }
              h1 { font-size: 28px; margin: 0 0 12px; }
              .code b { letter-spacing: 0.15em; }
              .btn { display: inline-block; margin: 16px 0; padding: 12px 20px; border-radius: 999px; background: var(--accent);
                     color: var(--on-accent); text-decoration: none; font-weight: 600; }
              .muted { color: var(--muted); font-size: 14px; }
              .muted a { color: inherit; }
            </style>
            </head>
            <body>
            <main>
              <h1>$heading</h1>
              $body
              <p class="muted">Belum punya Musicfy? <a href="$RELEASES_URL">Unduh di GitHub</a>.</p>
            </main>
            </body>
            </html>
        """.trimIndent()
    }
}
```

- [ ] **Step 3: Add the web routes**

In `Application.kt`, add these imports:
```kotlin
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondFile
import java.io.File
```
and add this block inside `routing { ... }`, after the `/api/search` route:
```kotlin
        val webIndex = File(deps.config.webDir, "index.html")
        if (webIndex.isFile) {
            staticFiles("/", deps.config.webDir) { default("index.html") }
            get("/r/{code}") { call.respondFile(webIndex) }
        } else {
            get("/") { call.respondText(Landing.page(null), ContentType.Text.Html) }
            get("/r/{code}") { call.respondText(Landing.page(call.parameters["code"]), ContentType.Text.Html) }
        }
```

Run: `./gradlew -p ritme-server test`
Expected: PASS (58 + 4 = 62 tests).

- [ ] **Step 4: Docker image**

`ritme-server/Dockerfile`:
```dockerfile
# Build from the repository root:  docker build -f ritme-server/Dockerfile -t ritme-server .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY modules/ritme-protocol ./modules/ritme-protocol
COPY ritme-server ./ritme-server
RUN ./gradlew --no-daemon -p ritme-server installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ritme && mkdir -p /app/web
WORKDIR /app
COPY --from=build /src/ritme-server/build/install/ritme-server /app
USER ritme
ENV PORT=8080 WEB_DIR=/app/web
EXPOSE 8080
ENTRYPOINT ["/app/bin/ritme-server"]
```

`ritme-server/Dockerfile.dockerignore` (BuildKit uses it for this Dockerfile; keeps the Android app out of the build context):
```gitignore
*
!gradlew
!gradle/
!modules/ritme-protocol/
!ritme-server/
**/build/
**/.gradle/
```

Run (Docker must be running; check with `docker info`):
```bash
docker build -f ritme-server/Dockerfile -t ritme-server:dev .
docker run -d --rm --name ritme-dev -p 18080:8080 ritme-server:dev
sleep 5
curl -s localhost:18080/healthz; echo
curl -s localhost:18080/r/K7M2QX | grep -o 'musicfy://ritme/K7M2QX'
docker stop ritme-dev
```
Expected: `ok`, then `musicfy://ritme/K7M2QX`. If `docker info` fails (daemon not running), skip this step and say so in the report.

- [ ] **Step 5: Deploy files**

`ritme-server/deploy/docker-compose.yml`:
```yaml
# On the VPS, from this directory:  cp .env.example .env  (edit)  &&  docker compose up -d --build
services:
  ritme:
    build:
      context: ../..
      dockerfile: ritme-server/Dockerfile
    image: ritme-server:latest
    restart: unless-stopped
    environment:
      PUBLIC_URL: https://${RITME_DOMAIN}
    # Not published to the internet: only Caddy talks to it (it trusts X-Forwarded-For).
    expose:
      - "8080"

  caddy:
    image: caddy:2
    restart: unless-stopped
    depends_on:
      - ritme
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      RITME_DOMAIN: ${RITME_DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config

volumes:
  caddy_data:
  caddy_config:
```

`ritme-server/deploy/Caddyfile`:
```
# HTTPS certificates are issued automatically for this domain.
{$RITME_DOMAIN} {
	encode gzip
	reverse_proxy ritme:8080
}
```

`ritme-server/deploy/.env.example`:
```
# Domain whose DNS A/AAAA record points at this VPS
RITME_DOMAIN=ritme.example.com
```

Validate: `cd ritme-server/deploy && RITME_DOMAIN=ritme.example.com docker compose config --quiet && echo compose-ok; cd -`
Expected: `compose-ok` (skip if Docker is unavailable, and say so).

- [ ] **Step 6: README**

`ritme-server/README.md`:
````markdown
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
````

- [ ] **Step 7: CI workflow**

`.github/workflows/ritme-server.yml`:
```yaml
name: Ritme server

on:
  push:
    paths:
      - "ritme-server/**"
      - "modules/ritme-protocol/**"
      - "gradle/libs.versions.toml"
      - ".github/workflows/ritme-server.yml"
  pull_request:
    paths:
      - "ritme-server/**"
      - "modules/ritme-protocol/**"
      - "gradle/libs.versions.toml"
      - ".github/workflows/ritme-server.yml"

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          java-version: 21
          distribution: temurin
      - uses: gradle/actions/setup-gradle@v5
      - name: Test protocol and server
        run: ./gradlew --console=plain -p ritme-server test :ritme-protocol:test
      - name: Build image
        run: docker build -f ritme-server/Dockerfile -t ritme-server:ci .
```
Validate: `ruby -ryaml -e 'YAML.load_file(".github/workflows/ritme-server.yml"); puts "ok"'` → `ok`.

- [ ] **Step 8: Full test run and commit**

Run: `./gradlew -p ritme-server test :ritme-protocol:test`
Expected: PASS (62 server tests + 10 protocol tests).

```bash
git add ritme-server .github/workflows/ritme-server.yml
git commit -m "feat(ritme): landing page, Docker image, Caddy deploy files and CI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- Tasks 1–7 committed on `feature/ritme`.
- `./gradlew -p ritme-server test :ritme-protocol:test` is green.
- `docker build -f ritme-server/Dockerfile .` succeeds and the container answers `/healthz` and `/r/{code}`.
- Next: deploy to the user's VPS (needs SSH access, domain, and confirmation), then the `ritme-web` plan, then Android integration.
