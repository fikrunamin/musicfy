# Ritme In-App Browser (#3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a Ritme room on Android, let a member open an in-app browser, find the video or stream a web page plays, check it the way other members' devices will fetch it, confirm it, and add it to the room as a `URL` entry, with the optional protocol fields `streamType`/`referrer` understood by the server, the web client and Android playback.

**Architecture:** A new JVM-pure module `:modules:ritme-media` (desktop/JCEF reuses it later) holds all detection logic: URL heuristics (`MediaUrlClassifier`, ad hosts, sensitive/expiry hints), `PlaylistSniffer` (HLS/DASH/MP4 magic), `MediaProber` (cookie-less, Referer-less probes over a `ProbeHttp` interface), `BrowserAddress` (address bar), `PageReports` + the page script `page-media.js` (a Java resource), and `CandidateCollector` (merge/rank/caps). `:modules:ritme-protocol` gains `StreamType` and the two optional entry fields plus referrer rules; the server validates them; the web client honours `streamType`. The Android app adds the OkHttp probe glue (`RitmeProbes`), a state holder (`RitmeBrowserModel`), the WebView screen (`ritme/browser` route, own WebView profile, layer-1 interception that always returns null, layer-2 page script over `addWebMessageListener`), the found-list and confirmation sheets, and the entry points.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose + Material3, Hilt, androidx.webkit 1.12.1 (Profile/`MULTI_PROFILE`, `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`), Media3 1.7.1 (`media3-exoplayer-dash` is already in the app since #2b), OkHttp 5.3.2, kotlinx.serialization-json (via `:modules:ritme-protocol`), JUnit 4; Ktor 3.4 server (`ritme-server/`); Preact 10 + TypeScript 5.9 + Vitest 4 (jsdom) for `ritme-web/`.

## Global Constraints

- **Worktree:** create one with superpowers:using-git-worktrees, branch `feature/ritme-android-browser` from `main` (d50bb76 or later). Every command runs from the worktree root.
- **Source of truth:** `docs/superpowers/specs/2026-11-23-ritme-watch-together-design.md`, items tagged #3: D8, D9, D10, D12, §3.2 item 5, §4.1 rows `ui/screens/ritme/browser/*` and `:modules:ritme-media`, §4.5, §4.6 "Buka di browser Ritme", §4.7 (all), §5 (seam only, no desktop work), §6.2 `streamType`, §8, §9, §10 row 3, §11 R5/R6/R12, §12 U7/U11/U12, and "Keputusan pengguna (2026-11-23)". U1–U12 are approved as recommended.
- **Mandatory address bar (user decision 2026-11-23):** accepts a full URL, a bare domain (becomes `https://`), or keywords (DuckDuckGo); shows the current page URL, copyable and editable; back, forward and reload are available; `javascript:`, `file:`, `content:`, `intent:` (any non-http(s) scheme) and local/private addresses are refused with a clear message, by `MediaUrlRules`.
- **Existing theme, exactly.** All Ritme UI uses the app's existing components: popup sheets on `PopupSheetState` via `LocalZoomOutOverlayState` (`showWithHandle`, as every Ritme sheet does), `SheetLabel`, `SheetTextField`, `SheetPillButton`, `Material3MenuGroup`/`Material3MenuItemData`, `RitmeChip` (the Ritme pill style) with `MenuRowSurface` fill (as the floating pill above the mini player), `RitmeSecondaryText`, the room-sheet row metrics (`RowMinHeight`, `RowArtSize`, `RowVideoArtWidth`, `RowArtShape`, `RowHighlightShape`), the `WebLoginHeader`/`YouTubeLoginScreen` header layout (black page, 8 × 4 dp row, white `IconButton` glyphs, 2 dp `LinearProgressIndicator`). Existing drawables only (`arrow_back`, `arrow_forward`, `refresh`, `more_vert`, `content_copy`, `globe`, `delete`, `close`, `play`, `web_link`). No new colours, shapes, fonts or animations. Never `Dialog`/`ModalBottomSheet`.
- **Strings:** English in `app/src/main/res/values/ritme_strings.xml`, Indonesian in `app/src/main/res/values-in/ritme_strings.xml`, all prefixed `ritme_`, Indonesian copy as given in the tasks (spec wording where the spec has it).
- **The server never fetches entry URLs.** Probes run on the adding device only, through the existing `RitmeUrlHttp` client (`Proxy.NO_PROXY`, no cache, no cookie jar, `RitmeAddressGuard` socket factory + fail-closed network interceptor). No cookies ever; no `Referer` except the single explicit referrer-origin retry (and then only the origin). Never attach WebView or YouTube cookies to anything.
- **Interception never rewrites pages:** `shouldInterceptRequest` (WebView and service worker) always returns `null`. The JS bridge is `WebViewCompat.addWebMessageListener` (never `addJavascriptInterface`), http(s) origins only. Page data is untrusted: ≤ 50 candidates per page, URL ≤ 2048, every value re-validated natively.
- **Never add automatically.** The confirmation sheet is mandatory; DRM, needs-login and total failure keep the add buttons disabled (U12). Fragment stripped, `thumbnailUrl` never sent, `durationMs` = probed length or 0.
- **Nothing changes outside a room:** the browser route is reachable only from Ritme UI (room sheet, "Tambah video" sheet); a browser opened in a room closes when the room ends. Outside a room it opens only in start mode (from a start-mode "Tambah video" sheet), like 2b's Decision 12.
- **Desktop seam:** detection/probe/address/page-script logic stays JVM-pure in `:modules:ritme-media` (no Android, no OkHttp, no coroutines). Android-only code (WebView, Compose, OkHttp glue, MediaMetadataRetriever) stays in `app/`. No desktop work.
- **Protocol compatibility:** `PROTOCOL_VERSION` stays `1`; new fields default to `null`; `StreamType` decodes leniently (unknown → `UNKNOWN`); golden fixtures are regenerated deliberately and the web parser/encoder kept in step.
- **Android facts:** AGP 9, Kotlin 2.3.10, minSdk 26, compileSdk 36, flavors `foss`/`gms`/`fdroid`; variant names are `<abi><Variant>Debug` (unit tests: `:app:testUniversalGmsDebugUnitTest`).
- **Low resource use (the user works on the same 16 GB Mac):** one Gradle build machine-wide, light flags, targeted tests, Kotlin compile instead of assemble for Kotlin-only steps, **gms flavor only**, never edit `gradle.properties`, never `./gradlew --stop`/`pkill`/`kill` Gradle. Define this helper **in the same shell call** before any Gradle command in this plan (written below as `g …`):

  ```bash
  g() {
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
    if pgrep -f '[j]ava.*gradle-wrapper\.jar' >/dev/null; then echo BUSY; return 1; fi
    ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g "$@"
  }
  ```

  If it prints `BUSY`, nothing ran: wait (Monitor tool with an until-loop) until no Gradle runs, then run it again.
- **Web:** Node via nvm. Define (same shell call) `w() { source ~/.nvm/nvm.sh >/dev/null; (cd ritme-web && "$@"); }`. Targeted tests `w npx vitest run <file>`; full check `w npm test && w npm run typecheck && w npm run build && w npm run size` (the size budget must hold).
- **Commits:** one per task, `git commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`. Do not push (the user pushes).

## Decisions made while planning (spec vs. code; read before reviewing)

1. **`YouTubeLinks` stays in `:modules:ritme-protocol`** (it went there in #2b; moving it would churn server, web fixture paths and app imports for nothing). `:modules:ritme-media` depends on `:modules:ritme-protocol` (`api`) and uses `YouTubeLinks`, `MediaUrlRules` and the new `StreamType` from there.
2. **The "Cari video di web" row does not exist yet** (the spec assumed it). Task 15 adds it (`R.drawable.globe`) right under "Tambah video", inside the same `canAdd` group of the room sheet (also on the in-player room page `RitmeQueuePage`).
3. **`media3-exoplayer-dash` is already a dependency** (#2b); nothing to add for D11.
4. **Popup state:** the user's brief says `LocalBottomSheetPageState`; every existing Ritme sheet uses `LocalZoomOutOverlayState` (same `PopupSheetState` type). The browser's sheets follow the code (`LocalZoomOutOverlayState`), so they stack with the room/"Tambah video" sheets.
5. **Referrer on Android playback = `Referer` only.** Spec 4.4 says "Referer/Origin"; the probe (spec 4.7 step 4) tests only `Referer`, so playback sends exactly what was proven to work, through a per-item `ResolvingDataSource` in `MusicService` (no shared mutable state).
6. **Web types:** `NewEntry.streamType`/`referrer` are optional in TypeScript (the web client never sets them; the encoder writes `null` like Kotlin's `encodeDefaults`); `QueueEntry` always carries both (the parser fills `null`). `playable.ts` also treats a `.mpd` path as unsupported (DASH is never playable on the web, D11).
7. **The page script lives in `:modules:ritme-media` as a Java resource** (`page-media.js`, desktop JCEF injects the same file, spec §5) and is unit-tested by `ritme-web`'s Vitest in jsdom (the only JS test runner in the repo), the same way that package already reads `modules/ritme-protocol/fixtures`.
8. **Message listener origin rule `"*"`** (`addWebMessageListener`/`addDocumentStartJavaScript` rules cannot say "any http(s) origin"); the listener drops any message whose `sourceOrigin` is not http(s) and parses the rest as untrusted (`PageReports.parse`, 64 000-char cap).
9. **DRM signals (spec 4.7 table):** playlist keys (SAMPLE-AES, Widevine/FairPlay/PlayReady key formats), `<ContentProtection>`, CENC boxes (`pssh`/`encv`/`enca`/`tenc`) in an MP4 head, the page script's `requestMediaKeySystemAccess` hook, and WebView's protected-media permission request (always denied). Any of the last two marks every candidate of that page as DRM, as the spec says ("EME terpakai"). A player that calls EME only to test support would cause a false refusal; noted for the device checklist.
10. **A file whose length stays unknown is refused (`LENGTH_UNKNOWN`, "Coba lagi"), not added with `durationMs = 0`.** Spec 4.7 says "durasi probe atau 0", but 0 means live (joiners at 0:00, seeks ignored, "LIVE" label); #2b already turned that into a retryable problem for pasted files. Lengths come from the MP4 `mvhd` box, then the page's `<video>.duration`, then `MediaMetadataRetriever` (with the Referer when needed). HLS/DASH keep spec behaviour (live or unknown → 0).
11. **Refused finds:** DRM and needs-login show the add pills dimmed (`SheetPillButton(enabled = false)`, U12 "tombol nonaktif"; nothing is underneath a sheet's button bar, so the AppSwitch lesson does not apply); `FAILED`/`LENGTH_UNKNOWN` show one "Coba lagi" pill instead (re-probe).
12. **YouTube pages are never `URL` entries:** a YouTube watch page (or an embedded YouTube iframe/og:video) gets a "Video YouTube ini" row that opens the existing "Tambah video" sheet with the watch link (the `YOUTUBE_VIDEO` path from #2b).
13. **"Hapus data browser Ritme" only with a separate profile.** Without `MULTI_PROFILE` the browser shares the default WebView profile with the import/sign-in web views, so wiping it would sign those out; the row is hidden then (cookies/storage of the default profile are left alone; third-party cookies are off by default).
14. **Local-address guard in the browser:** typed addresses, page navigations (main frame and frames, redirects included) and `intent:`/app links are checked (`BrowserAddress.navigation`); sub-resource requests are not blocked, because interception must return `null` (D9). App links open only after a confirmation sheet and only for a user gesture, with `CATEGORY_BROWSABLE`, no component and no selector.
15. **Header buttons:** back (page history, then leave), the address field, reload, and ⋯ (Maju, Salin link, Buka di browser lain, Hapus data browser Ritme, Tutup browser). "Kembali/maju/muat ulang tersedia" is met by back + reload in the header and forward in the menu (dimmed when there is nothing ahead).
16. **"Tambah video" probes pasted streams too** (closes 2b Decision 13's limitation): a pasted `.m3u8`/`.mpd` is checked with the same prober; a finished stream keeps its length (no more restart-from-0 after a pause), DRM/login streams become problems, and `streamType` is sent. Files keep #2b's `MediaMetadataRetriever` path.
17. **CORS check origin** = origin of the room's server (`session.serverUrl ?: settings.serverUrl`, falling back to the default server when that is a local dev address): the web client is served from there.
18. **Pill count:** finds that are surely media (seen played or requested as media, a media extension, or checked fine) plus a YouTube row; og:video/JSON-LD links without a media extension are listed and checked once the list opens, but do not make the pill appear on their own (news pages put HTML player pages there).

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt` | `StreamType`, `NewEntry`/`QueueEntry.streamType`/`referrer` | 1 |
| `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRules.kt` | `checkReferrer`, `isAcceptableReferrer`, `referrerOf`, `NOT_ORIGIN` | 1 |
| `modules/ritme-protocol/fixtures/rules/referrers.json` (new), `fixtures/*.json` (regenerated), `fixtures/cmd-add-url-hls-referrer.json` (new) | shared cases / golden files | 1 |
| `ritme-web/src/protocol.ts`, `protocol.test.ts`, `testing/builders.ts` | TS mirror of the fields | 1 |
| `settings.gradle.kts`, `modules/ritme-media/build.gradle.kts` (new) | the new JVM module | 2 |
| `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/UrlParts.kt`, `AdHosts.kt`, `MediaUrlClassifier.kt`, `SensitiveParams.kt`, `ExpiryHints.kt` (new) | URL heuristics (layer 1, hints) | 2 |
| `…/media/PlaylistSniffer.kt` (new) | magic bytes, HLS/DASH parse, MP4 length | 3 |
| `…/media/MediaProber.kt` (new) | `ProbeHttp`, `ProbeVerdict`, `Refusal`, the probe | 4 |
| `…/media/BrowserAddress.kt` (new) | address bar input, navigation decisions | 5 |
| `modules/ritme-media/src/main/resources/com/example/musicfy/ritme/media/page-media.js` (new), `…/media/PageMediaScript.kt`, `…/media/PageReport.kt` (new) | layer-2 page script and its parser | 6 |
| `ritme-web/src/external/page-media.test.ts` (new) | the page script's tests (jsdom) | 6 |
| `…/media/CandidateCollector.kt` (new) | merge, caps, YouTube/blob/EME, ranking | 7 |
| `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt`, `ritme-server/README.md` | validation, copy, docs | 8 |
| `ritme-web/src/net/media-url-rules.ts`, `ritme-web/src/player/playable.ts` (+ tests) | `checkReferrer` twin, `streamType` playback | 9 |
| `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt`, `ritme/playback/RitmeItems.kt`, `playback/MusicService.kt`, `ritme/video/RitmeAddVideoModel.kt` | URL entries carry/play `streamType`/`referrer` | 10 |
| `app/build.gradle.kts`, `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeProbeHttp.kt`, `RitmeProbes.kt` (new), `ritme/video/RitmeFileLength.kt` (new), `RitmeVideoLookup.kt`, `RitmeAddVideoModel.kt`, `RitmeController.kt` | Android probe glue; "Tambah video" checks streams | 11 |
| `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeBrowserModel.kt`, `RitmeBrowserEntries.kt` (new) | browser state, confirmation model | 12 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeBrowserWebView.kt`, `RitmeBrowserScreen.kt` (new), `ui/screens/NavigationBuilder.kt` | the WebView, the screen, the route | 13 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeFoundSheet.kt` (new), `RitmeAddVideoSheet.kt` | found list, confirmation, pill | 14 |
| `RitmeController.kt`, `ui/screens/ritme/RitmeUi.kt`, `RitmeRoomSheet.kt`, `RitmeAddVideoSheet.kt` | entry points | 15 |
| `docs/superpowers/backlog.md` | follow-ups | 16 |

---

# Phase A: Protocol and the pure module

### Task 1: `streamType` and `referrer` in the protocol (Kotlin + TypeScript mirror)

**Files:**
- Modify: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/Models.kt`
- Modify: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/MediaUrlRules.kt`
- Create: `modules/ritme-protocol/fixtures/rules/referrers.json`
- Create: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/ReferrerRulesTest.kt`
- Modify: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/Samples.kt`
- Modify: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/ForwardCompatibilityTest.kt`
- Regenerate: `modules/ritme-protocol/fixtures/*.json` (+ new `cmd-add-url-hls-referrer.json`)
- Modify: `ritme-web/src/protocol.ts`, `ritme-web/src/protocol.test.ts`, `ritme-web/src/testing/builders.ts`

**Interfaces:**
- Produces (Kotlin, package `com.example.musicfy.ritme.protocol`): `enum class StreamType { HLS, DASH, FILE, UNKNOWN }` (lenient serializer); `NewEntry.streamType: StreamType? = null`, `NewEntry.referrer: String? = null`, same two on `QueueEntry` (both last in the constructor); `MediaUrlRules.MAX_REFERRER_LENGTH = 200`, `MediaUrlRules.Rejection.NOT_ORIGIN`, `MediaUrlRules.checkReferrer(referrer: String): Rejection?`, `MediaUrlRules.isAcceptableReferrer(referrer: String): Boolean`, `MediaUrlRules.referrerOf(pageUrl: String): String?` (`"https://host[:port]/"`, host lowercased).
- Produces (TS, `ritme-web/src/protocol.ts`): `STREAM_TYPES`, `type StreamType`, `NewEntry.streamType?: StreamType | null`, `NewEntry.referrer?: string | null`, `QueueEntry.streamType: StreamType | null`, `QueueEntry.referrer: string | null`.
- Produces (fixture): `fixtures/rules/referrers.json` with 20 cases `{ referrer, reject, padTo? }`.

- [ ] **Step 1: Write the failing Kotlin tests**

Create `modules/ritme-protocol/fixtures/rules/referrers.json`:

```json
{
  "_comment": "Shared by ReferrerRulesTest.kt and ritme-web src/net/media-url-rules.test.ts. reject: null = accepted, else the MediaUrlRules.Rejection name. padTo: when present, append \"a\" to referrer until it is padTo chars long. A referrer is a bare http(s) origin with a trailing slash, at most 200 chars, under the media url host rules.",
  "cases": [
    { "referrer": "https://example.com/", "reject": null },
    { "referrer": "http://example.com/", "reject": null },
    { "referrer": "https://video.example.co.id:8443/", "reject": null },
    { "referrer": "HTTPS://EXAMPLE.COM/", "reject": null },
    { "referrer": "https://[2001:db8::1]/", "reject": null },
    { "referrer": "https://example.com", "reject": "NOT_ORIGIN" },
    { "referrer": "https://example.com/watch/123", "reject": "NOT_ORIGIN" },
    { "referrer": "https://example.com/?a=1", "reject": "NOT_ORIGIN" },
    { "referrer": "https://example.com?a=1", "reject": "NOT_ORIGIN" },
    { "referrer": "https://example.com//", "reject": "NOT_ORIGIN" },
    { "referrer": "https://example.com/#top", "reject": "FRAGMENT" },
    { "referrer": "https://user:pw@example.com/", "reject": "USERINFO" },
    { "referrer": "https://192.168.1.1/", "reject": "LOCAL_HOST" },
    { "referrer": "http://localhost:8080/", "reject": "LOCAL_HOST" },
    { "referrer": "https://router.lan/", "reject": "LOCAL_HOST" },
    { "referrer": "ftp://example.com/", "reject": "SCHEME" },
    { "referrer": "javascript:alert(1)", "reject": "SCHEME" },
    { "referrer": "https://exa mple.com/", "reject": "FORBIDDEN_CHARACTER" },
    { "referrer": "", "reject": "MALFORMED" },
    { "referrer": "https://example.com/", "padTo": 201, "reject": "TOO_LONG" }
  ]
}
```

Create `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/ReferrerRulesTest.kt`:

```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The verdicts in fixtures/rules/referrers.json; ritme-web's media-url-rules.test.ts checks the same file. */
class ReferrerRulesTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/referrers.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVerdict() {
        assertEquals("fixture case count", 20, cases.size)
        val mismatches = cases.mapNotNull { case ->
            val base = case.getValue("referrer").jsonPrimitive.content
            val referrer = case["padTo"]?.jsonPrimitive?.int?.let { base.padEnd(it, 'a') } ?: base
            val expected = case.getValue("reject").jsonPrimitive.contentOrNull
            val actual = MediaUrlRules.checkReferrer(referrer)?.name
            "$base: expected $expected, was $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun theLengthIsCappedAt200() {
        val longest = "https://" + "a".repeat(187) + ".com/"
        assertEquals(200, longest.length)
        assertNull(MediaUrlRules.checkReferrer(longest))
        assertEquals(MediaUrlRules.Rejection.TOO_LONG, MediaUrlRules.checkReferrer("https://" + "a".repeat(188) + ".com/"))
    }

    @Test
    fun aPagesOriginBecomesItsReferrer() {
        assertEquals("https://video.example.com/", MediaUrlRules.referrerOf("https://Video.Example.com/watch?v=1#t=3"))
        assertEquals("http://example.com:8080/", MediaUrlRules.referrerOf("http://example.com:8080"))
        assertNull(MediaUrlRules.referrerOf("http://192.168.1.1/admin"))
        assertNull(MediaUrlRules.referrerOf("https://user:pw@example.com/"))
        assertNull(MediaUrlRules.referrerOf("about:blank"))
    }

    @Test
    fun isAcceptableReferrerIsCheckReferrerWithoutTheReason() {
        assertEquals(true, MediaUrlRules.isAcceptableReferrer("https://example.com/"))
        assertEquals(false, MediaUrlRules.isAcceptableReferrer("https://example.com/page"))
    }
}
```

In `ForwardCompatibilityTest.kt` add this test (inside the class, after `unknownMediaKindDecodesToUnknown`):

```kotlin
    @Test
    fun unknownStreamTypeDecodesToUnknownAndMissingOnesToNull() {
        val json = """{"entryId":"e1","videoId":"","title":"T","artist":"A","durationMs":0,"addedBy":"m1","kind":"URL","url":"https://x.example/v","streamType":"SMOOTH"}"""
        val entry = RitmeJson.decodeFromString(QueueEntry.serializer(), json)
        assertEquals(StreamType.UNKNOWN, entry.streamType)
        assertNull(entry.referrer)
        assertNull(RitmeJson.decodeFromString(QueueEntry.serializer(), entryJson).streamType)
        StreamType.entries.forEach {
            assertEquals("\"${it.name}\"", RitmeJson.encodeToString(StreamType.serializer(), it))
            assertEquals(it, RitmeJson.decodeFromString(StreamType.serializer(), "\"${it.name}\""))
        }
    }
```

In `Samples.kt`, right after the `"cmd-add-url-live"` entry of `client`, add:

```kotlin
        "cmd-add-url-hls-referrer" to send(
            Command.Add(
                listOf(
                    NewEntry(
                        "", "Film", "example.com", null, 5_400_000, MediaKind.URL,
                        "https://cdn.example.com/v/manifest?token=abc", StreamType.HLS, "https://example.com/",
                    ),
                ),
            ),
        ),
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :modules:ritme-protocol:test`
Expected: compilation FAILS (`Unresolved reference: StreamType`, `checkReferrer`, `referrerOf`).

- [ ] **Step 3: Add `StreamType` and the fields**

In `Models.kt`, after `internal object MediaKindSerializer …`, add:

```kotlin
/**
 * How a [MediaKind.URL] entry's url is served (spec D8), as the client that added it found out by
 * probing it. Lets players choose HLS/DASH for urls without a telling extension (`/manifest?token=`).
 * Names added by a newer protocol decode to [UNKNOWN], which players treat like a missing value.
 */
@Serializable(with = StreamTypeSerializer::class)
enum class StreamType { HLS, DASH, FILE, UNKNOWN }

internal object StreamTypeSerializer : LenientEnumSerializer<StreamType>("StreamType", StreamType.entries, StreamType.UNKNOWN)
```

In `NewEntry` and in `QueueEntry`, after `val url: String? = null,` add:

```kotlin
    /** URL entries only, optional (spec D8): how the url is served. */
    val streamType: StreamType? = null,
    /**
     * URL entries only, optional (spec D8): the origin of the page the video was found on
     * (`https://host[:port]/`, [MediaUrlRules.checkReferrer]), sent as the Referer by Android
     * players for hosts that check it. Never a path, a query or a cookie.
     */
    val referrer: String? = null,
```

- [ ] **Step 4: Add the referrer rules**

In `MediaUrlRules.kt`, after `const val MAX_URL_LENGTH = 2_048` add:

```kotlin
    /** A referrer is a bare origin: this is the longest accepted. */
    const val MAX_REFERRER_LENGTH = 200
```

In `enum class Rejection`, after `LOCAL_HOST,` add:

```kotlin

        /** A referrer with a path, a query or no trailing slash: only `scheme://host[:port]/` is accepted. */
        NOT_ORIGIN,
```

After `fun check(url: String): Rejection? { … }` add:

```kotlin
    fun isAcceptableReferrer(referrer: String): Boolean = checkReferrer(referrer) == null

    /**
     * Why [referrer] is refused as a URL entry's referrer (spec 3.2 item 5), or null when it is an
     * http(s) origin with a trailing slash and nothing else (`https://host[:port]/`), at most
     * [MAX_REFERRER_LENGTH] chars, under the same host rules as [check].
     * Cases: fixtures/rules/referrers.json (also read by ritme-web).
     */
    fun checkReferrer(referrer: String): Rejection? {
        if (referrer.length > MAX_REFERRER_LENGTH) return Rejection.TOO_LONG
        check(referrer)?.let { return it }
        val afterScheme = referrer.substring(referrer.indexOf("://") + 3)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' }
        return if (afterScheme.substring(authority.length) == "/") null else Rejection.NOT_ORIGIN
    }

    /** [pageUrl]'s origin as a referrer (`https://host[:port]/`, host lowercased), or null when [checkReferrer] refuses it. */
    fun referrerOf(pageUrl: String): String? {
        val url = pageUrl.substringBefore('#')
        if (check(url) != null) return null
        val schemeEnd = url.indexOf("://")
        val scheme = url.substring(0, schemeEnd).lowercase()
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' }.lowercase()
        return "$scheme://$authority/".takeIf { checkReferrer(it) == null }
    }
```

(`check()` only passes for `http(s)://…`, so `indexOf("://")` is always found after it.)

- [ ] **Step 5: Regenerate the golden files and run the tests**

Run: `g :modules:ritme-protocol:test -PupdateGolden=true` then `g :modules:ritme-protocol:test`
Expected: BUILD SUCCESSFUL, all tests pass. Then `git diff --stat modules/ritme-protocol/fixtures/`: `client-create`, `cmd-add`, `cmd-add-youtube-video`, `cmd-add-url-live`, `server-welcome`, `server-state` each gain `"streamType": null` and `"referrer": null` after `"url"` in every entry, and `cmd-add-url-hls-referrer.json` is new with `"streamType": "HLS"` and `"referrer": "https://example.com/"`. Nothing else changes.

- [ ] **Step 6: Mirror the fields in the web client (keeps its golden test green)**

In `ritme-web/src/protocol.ts`, after the `MediaKind` type add:

```ts
export const STREAM_TYPES = ['HLS', 'DASH', 'FILE', 'UNKNOWN'] as const;
export type StreamType = (typeof STREAM_TYPES)[number];
```

In `interface NewEntry`, after `url: string | null;` add:

```ts
  /** URL entries only (#3, optional): how the url is served. The web client never sets it. */
  streamType?: StreamType | null;
  /** URL entries only (#3, optional): the page's origin, sent as the Referer by Android players. */
  referrer?: string | null;
```

In `interface QueueEntry`, after `url: string | null;` add:

```ts
  /** How the url is served (#3); null when the adder did not say. Unknown names become 'UNKNOWN'. */
  streamType: StreamType | null;
  /** The page origin Android sends as the Referer (#3); the web cannot set one. */
  referrer: string | null;
```

In `encodeNewEntry`, after `url: e.url,` add:

```ts
    streamType: e.streamType ?? null,
    referrer: e.referrer ?? null,
```

In `parseQueueEntry`, after `url: optStr(o, 'url'),` add:

```ts
    streamType:
      o.streamType === undefined || o.streamType === null ? null : lenientEnum(STREAM_TYPES, o.streamType, 'UNKNOWN'),
    referrer: optStr(o, 'referrer'),
```

In `ritme-web/src/testing/builders.ts`, in `entry()` after `url: null,` add `streamType: null,` and `referrer: null,`.

In `ritme-web/src/protocol.test.ts`:
- In the `const entry: QueueEntry = { … }` sample, after `url: null,` add `streamType: null,` and `referrer: null,`.
- In `clientSamples`, after the `'cmd-add-url-live'` entry, add:

```ts
  'cmd-add-url-hls-referrer': send({
    type: 'Add',
    entries: [
      {
        videoId: '',
        title: 'Film',
        artist: 'example.com',
        thumbnailUrl: null,
        durationMs: 5_400_000,
        kind: 'URL',
        url: 'https://cdn.example.com/v/manifest?token=abc',
        streamType: 'HLS',
        referrer: 'https://example.com/',
      },
    ],
    playNext: false,
  }),
```

- In the test `fills Kotlin defaults: Welcome without protocolVersion, minimal state, minimal entry`, in the expected queue entry after `url: null,` add `streamType: null,` and `referrer: null,`.
- In `describe('forward compatibility …')` add:

```ts
  it('decodes an unknown stream type to UNKNOWN and a missing one to null', () => {
    const json = (extra: string) =>
      `{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","queue":[{"entryId":"e1","videoId":"","title":"T","artist":"A","durationMs":0,"addedBy":"m1","kind":"URL","url":"https://x.example/v"${extra}}]}}`;
    const unknown = parseServerMessage(json(',"streamType":"SMOOTH","referrer":"https://x.example/"'));
    expect(unknown?.type === 'State' && unknown.state.queue[0]).toMatchObject({ streamType: 'UNKNOWN', referrer: 'https://x.example/' });
    const missing = parseServerMessage(json(''));
    expect(missing?.type === 'State' && missing.state.queue[0]).toMatchObject({ streamType: null, referrer: null });
  });
```

- [ ] **Step 7: Run the web checks**

Run: `w npx vitest run src/protocol.test.ts` then `w npm test && w npm run typecheck`
Expected: all pass (the golden test now covers `cmd-add-url-hls-referrer`).

- [ ] **Step 8: Commit**

```bash
git add modules/ritme-protocol ritme-web/src/protocol.ts ritme-web/src/protocol.test.ts ritme-web/src/testing/builders.ts
git commit -m "feat(ritme-protocol): optional streamType and origin-only referrer on entries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `:modules:ritme-media` with the URL heuristics (layer 1, ad hosts, hints)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/ritme-media/build.gradle.kts`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/UrlParts.kt`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/AdHosts.kt`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/MediaUrlClassifier.kt`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/SensitiveParams.kt`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/ExpiryHints.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/MediaUrlClassifierTest.kt`, `UrlHintsTest.kt`

**Interfaces:**
- Consumes: `MediaUrlRules.check`, `StreamType` (Task 1).
- Produces (package `com.example.musicfy.ritme.media`):
  - `sealed interface Sniffed { data class Media(val url: String, val streamType: StreamType, val mediaRequest: Boolean); data object Segment; data object Ignored }`
  - `object MediaUrlClassifier { fun classify(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Sniffed; fun streamTypeOf(url: String): StreamType?; fun isMediaElementRequest(headers: Map<String, String>): Boolean; fun isYouTubeHost(host: String): Boolean; fun hostOf(url: String): String?; fun extensionOf(url: String): String; fun fileNameOf(url: String): String? }`
  - `object AdHosts { fun isAd(host: String): Boolean }`
  - `object SensitiveParams { fun names(url: String): List<String>; fun has(url: String): Boolean }`
  - `data class LinkHints(val expiresInMinutes: Long?, val ipLocked: Boolean)`; `object ExpiryHints { fun of(url: String, nowMs: Long): LinkHints }`
  - `internal object UrlParts { fun host(url: String): String?; fun path(url: String): String; fun extension(url: String): String; fun query(url: String): List<Pair<String, String>> }`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include(":modules:ritme-protocol")` add:

```kotlin
include(":modules:ritme-media")
```

Create `modules/ritme-media/build.gradle.kts`:

```kotlin
// Ritme's video detection for the in-app browser (spec D12, 4.7): URL heuristics, playlist sniffing,
// the cookie-less probe, the address bar rules and the page script. Plain JVM on purpose: the Android
// app uses it today and the desktop app (JCEF) will use the same code and the same page script.
plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":modules:ritme-protocol"))
    testImplementation(libs.junit)
}
```

- [ ] **Step 2: Write the failing tests**

Create `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/MediaUrlClassifierTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlClassifierTest {

    private val mediaHeaders = mapOf("Range" to "bytes=0-", "Accept-Encoding" to "identity;q=1, *;q=0")

    @Test
    fun extensionsSayWhatAUrlIs() {
        assertEquals(Sniffed.Media("https://cdn.example.com/v/master.m3u8?t=1", StreamType.HLS, false), MediaUrlClassifier.classify("https://cdn.example.com/v/master.m3u8?t=1#x"))
        assertEquals(Sniffed.Media("https://cdn.example.com/v/manifest.mpd", StreamType.DASH, false), MediaUrlClassifier.classify("https://cdn.example.com/v/manifest.mpd"))
        listOf("mp4", "m4v", "webm", "mov", "ogv", "mkv", "MP4").forEach {
            assertEquals(it, StreamType.FILE, (MediaUrlClassifier.classify("https://cdn.example.com/film.$it") as Sniffed.Media).streamType)
        }
    }

    @Test
    fun queryAndPathHintsCount() {
        assertEquals(StreamType.HLS, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?format=m3u8&id=1"))
        assertEquals(StreamType.HLS, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?type=hls"))
        assertEquals(StreamType.DASH, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?type=dash"))
        assertEquals(StreamType.FILE, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/get?mime=video%2Fmp4"))
        assertEquals(StreamType.UNKNOWN, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/v1/manifest?token=a"))
        assertEquals(StreamType.UNKNOWN, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/hls/,480p,720p,.mp4.urlset/index"))
        assertNull(MediaUrlClassifier.streamTypeOf("https://example.com/manifest.json"))
        assertNull(MediaUrlClassifier.streamTypeOf("https://example.com/watch/123"))
    }

    @Test
    fun aMediaElementsRequestIsACandidateEvenWithoutAnExtension() {
        assertEquals(
            Sniffed.Media("https://cdn.example.com/stream/abc123", StreamType.UNKNOWN, mediaRequest = true),
            MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123", "GET", mediaHeaders),
        )
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123"))
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123", "GET", mapOf("Range" to "bytes=0-")))
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/data.json", "GET", mediaHeaders))
        assertTrue(MediaUrlClassifier.isMediaElementRequest(mapOf("range" to "bytes=0-", "accept-encoding" to "identity;q=1,*;q=0")))
    }

    @Test
    fun segmentsOnlySayThePagePlaysAStream() {
        listOf("ts", "m4s", "aac", "key", "vtt").forEach {
            assertEquals(it, Sniffed.Segment, MediaUrlClassifier.classify("https://cdn.example.com/v/seg-001.$it"))
        }
    }

    @Test
    fun youTubeAdsLocalAndNonGetRequestsAreIgnored() {
        listOf(
            "https://rr1---sn-abc.googlevideo.com/videoplayback?mime=video%2Fmp4",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://imasdk.googleapis.com/js/core/ad.mp4",
            "https://s0.2mdn.net/videoplayback/ad.mp4",
            "https://ads.doubleclick.net/v.m3u8",
            "http://192.168.1.10/v.mp4",
            "https://user:pw@cdn.example.com/v.mp4",
            "blob:https://example.com/123",
        ).forEach { assertEquals(it, Sniffed.Ignored, MediaUrlClassifier.classify(it)) }
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/v.mp4", "POST"))
    }

    @Test
    fun adHostsMatchWholeLabelsOnly() {
        assertTrue(AdHosts.isAd("pubads.g.doubleclick.net"))
        assertTrue(AdHosts.isAd("doubleclick.net."))
        assertFalse(AdHosts.isAd("notdoubleclick.net"))
        assertFalse(AdHosts.isAd("doubleclick.net.example.com"))
    }

    @Test
    fun hostsFileNamesAndExtensions() {
        assertEquals("cdn.example.com", MediaUrlClassifier.hostOf("HTTPS://CDN.Example.com:8443/a.mp4"))
        assertEquals("[2001:db8::1]", MediaUrlClassifier.hostOf("https://[2001:db8::1]:443/a.mp4"))
        assertNull(MediaUrlClassifier.hostOf("ftp://example.com/a.mp4"))
        assertEquals("mp4", MediaUrlClassifier.extensionOf("https://cdn.example.com/a/b.MP4?x=1#t"))
        assertEquals("", MediaUrlClassifier.extensionOf("https://cdn.example.com/a/b"))
        assertEquals("My Film.mp4", MediaUrlClassifier.fileNameOf("https://cdn.example.com/films/My%20Film.mp4?x=1"))
        assertNull(MediaUrlClassifier.fileNameOf("https://cdn.example.com/"))
    }
}
```

Create `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/UrlHintsTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlHintsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun sensitiveParameterNamesAreFound() {
        assertEquals(
            listOf("token", "signature", "key-pair-id"),
            SensitiveParams.names("https://cdn.example.com/v.m3u8?token=a&Expires=1&Signature=x&Key-Pair-Id=k&quality=hd"),
        )
        assertEquals(listOf("session_id"), SensitiveParams.names("https://cdn.example.com/v.mp4?author=bob&session_id=1"))
        assertEquals(
            listOf("x-amz-signature", "x-amz-credential"),
            SensitiveParams.names("https://s3.example.com/v.mp4?X-Amz-Signature=a&X-Amz-Credential=b"),
        )
        assertEquals(listOf("token"), SensitiveParams.names("https://cdn.example.com/v.mp4?%74oken=1"))
        assertFalse(SensitiveParams.has("https://cdn.example.com/v.mp4#token=1"))
        assertFalse(SensitiveParams.has("https://cdn.example.com/v.mp4?quality=720"))
    }

    @Test
    fun expiryTimesBecomeMinutesFromNow() {
        assertEquals(LinkHints(10, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?expires=1700000600", now))
        assertEquals(LinkHints(10, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?Expires=1700000600000", now))
        assertEquals(LinkHints(20, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?hdnts=exp%3D1700001200~acl%3D%2F*~hmac%3Dab", now))
        // a small number is a lifetime, not a time
        assertEquals(LinkHints(null, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?exp=3600", now))
        assertTrue(ExpiryHints.of("https://cdn.example.com/v.m3u8?exp=1699999000", now).expiresInMinutes!! <= 0)
    }

    @Test
    fun anIpParameterMeansTheLinkMayBeLockedToANetwork() {
        assertTrue(ExpiryHints.of("https://cdn.example.com/v.mp4?ip=203.0.113.9", now).ipLocked)
        assertFalse(ExpiryHints.of("https://cdn.example.com/v.mp4?ip=", now).ipLocked)
        assertEquals(LinkHints(null, false), ExpiryHints.of("https://cdn.example.com/v.mp4", now))
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `g :modules:ritme-media:test`
Expected: compilation FAILS (`Unresolved reference: MediaUrlClassifier`, `Sniffed`, `AdHosts`, `SensitiveParams`, `ExpiryHints`, `LinkHints`).

- [ ] **Step 4: Write `UrlParts` and `AdHosts`**

Create `UrlParts.kt`:

```kotlin
package com.example.musicfy.ritme.media

import java.net.URLDecoder

/**
 * Hand-written URL pieces, like MediaUrlRules' parser: java.net.URI refuses many real-world links
 * (a `|` or an unencoded space in a query), and these only need to be good enough for heuristics.
 */
internal object UrlParts {

    /** The lowercased host of an http(s) [url] (an IPv6 literal keeps its brackets, a final dot is dropped), or null. */
    fun host(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().removeSuffix(".").takeIf { it.isNotEmpty() }
    }

    /** The lowercased path, without query or fragment ("" when there is none). */
    fun path(url: String): String {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
        return rest.dropWhile { it != '/' && it != '?' && it != '#' }.substringBefore('#').substringBefore('?').lowercase()
    }

    /** The last path segment's extension, lowercased ("" when it has none). */
    fun extension(url: String): String {
        val last = path(url).substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.') else ""
    }

    /** The query's parameters, names and values percent-decoded and lowercased, in order. */
    fun query(url: String): List<Pair<String, String>> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            decode(part.substringBefore('=')).lowercase() to decode(part.substringAfter('=', "")).lowercase()
        }
    }

    fun decode(text: String): String = runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
}
```

Create `AdHosts.kt`:

```kotlin
package com.example.musicfy.ritme.media

/**
 * Known ad and ad-video hosts (spec 4.7 step 3, R12): their videos are never offered. A small static
 * list on purpose; ranking by the largest playing video does the rest.
 */
object AdHosts {
    private val DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "imasdk.googleapis.com",
        "adservice.google.com", "2mdn.net", "moatads.com", "adnxs.com", "amazon-adsystem.com",
        "adsrvr.org", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "taboola.com", "outbrain.com", "teads.tv", "spotxchange.com", "spotx.tv", "springserve.com",
        "innovid.com", "smartadserver.com", "adform.net", "yieldmo.com", "vidoomy.com", "aniview.com",
        "adcolony.com", "applovin.com", "exoclick.com", "juicyads.com", "popads.net",
        "propellerads.com", "trafficjunky.net",
    )

    fun isAd(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        return DOMAINS.any { name == it || name.endsWith(".$it") }
    }
}
```

- [ ] **Step 5: Write `MediaUrlClassifier`**

Create `MediaUrlClassifier.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType

/** What one request the in-app browser saw says about the page's video (spec 4.7 step 1). */
sealed interface Sniffed {
    /** A video or stream worth offering; [mediaRequest]: known only from a media element's request headers. */
    data class Media(val url: String, val streamType: StreamType, val mediaRequest: Boolean) : Sniffed

    /** A piece of a stream (.ts, .m4s, …): the page plays a stream, but this is not one to offer. */
    data object Segment : Sniffed

    data object Ignored : Sniffed
}

/**
 * Layer 1 of the detection (spec 4.7): a request's url and headers → a candidate, a segment, or
 * nothing. Only GETs; YouTube's own hosts (offered as YOUTUBE_VIDEO instead), ad hosts and urls the
 * server would refuse (MediaUrlRules) are never candidates. Fragments are dropped.
 */
object MediaUrlClassifier {
    private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "ogv", "mkv")
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "aac", "key", "vtt")
    private val NOT_MEDIA_EXTENSIONS = setOf(
        "json", "js", "css", "html", "htm", "xml", "webmanifest", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2",
    )
    private val YOUTUBE_DOMAINS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com")

    fun classify(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Sniffed {
        if (!method.equals("GET", ignoreCase = true)) return Sniffed.Ignored
        val clean = url.substringBefore('#')
        val host = hostOf(clean) ?: return Sniffed.Ignored
        if (isYouTubeHost(host) || AdHosts.isAd(host)) return Sniffed.Ignored
        val extension = extensionOf(clean)
        if (extension in SEGMENT_EXTENSIONS) return Sniffed.Segment
        val byName = streamTypeOf(clean)
        val mediaRequest = byName == null && extension !in NOT_MEDIA_EXTENSIONS && isMediaElementRequest(headers)
        if (byName == null && !mediaRequest) return Sniffed.Ignored
        if (MediaUrlRules.check(clean) != null) return Sniffed.Ignored
        return Sniffed.Media(clean, byName ?: StreamType.UNKNOWN, mediaRequest)
    }

    /**
     * What [url]'s name says: the extension, query hints (`format=m3u8`, `type=hls`,
     * `mime=video/…`), or a stream-ish path (`/manifest`, `.urlset/`) as UNKNOWN (the probe decides).
     * Null when nothing hints at media.
     */
    fun streamTypeOf(url: String): StreamType? {
        val clean = url.substringBefore('#')
        when (extensionOf(clean)) {
            "m3u8" -> return StreamType.HLS
            "mpd" -> return StreamType.DASH
            in FILE_EXTENSIONS -> return StreamType.FILE
            in NOT_MEDIA_EXTENSIONS -> return null
        }
        for ((name, value) in UrlParts.query(clean)) {
            when (name) {
                "format", "type", "manifest_type", "protocol" -> when (value) {
                    "m3u8", "hls" -> return StreamType.HLS
                    "mpd", "dash" -> return StreamType.DASH
                }
                "mime", "mimetype", "mime_type" -> when {
                    "mpegurl" in value -> return StreamType.HLS
                    "dash+xml" in value -> return StreamType.DASH
                    value.startsWith("video/") -> return StreamType.FILE
                }
            }
        }
        val path = UrlParts.path(clean)
        val segments = path.split('/')
        if (segments.any { it == "manifest" || it.startsWith("manifest(") } || ".urlset/" in path) return StreamType.UNKNOWN
        return null
    }

    /** Chromium's media element asks for `Range: bytes=0-` with `Accept-Encoding: identity;q=1, *;q=0`. */
    fun isMediaElementRequest(headers: Map<String, String>): Boolean {
        val lower = headers.entries.associate { it.key.lowercase() to it.value.trim().lowercase() }
        return lower["range"] == "bytes=0-" && lower["accept-encoding"]?.replace(" ", "") == "identity;q=1,*;q=0"
    }

    fun isYouTubeHost(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        return YOUTUBE_DOMAINS.any { name == it || name.endsWith(".$it") }
    }

    fun hostOf(url: String): String? = UrlParts.host(url)

    fun extensionOf(url: String): String = UrlParts.extension(url)

    /** The last path segment, percent-decoded and trimmed; null when empty. */
    fun fileNameOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
        val last = rest.dropWhile { it != '/' && it != '?' && it != '#' }.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return UrlParts.decode(last).trim().takeIf { it.isNotEmpty() }
    }
}
```

- [ ] **Step 6: Write the hints**

Create `SensitiveParams.kt`:

```kotlin
package com.example.musicfy.ritme.media

/**
 * Query parameters that look like a private key to the stream (spec 4.7 step 6): the confirmation
 * warns "Link ini mungkin berisi kunci akses pribadi." before the link is shown to a room.
 */
object SensitiveParams {
    private val EXACT = setOf(
        "token", "auth", "key", "session", "jwt", "sig", "sid", "hmac", "policy", "key-pair-id", "pass",
        "apikey", "api_key", "hdnts", "hdnea",
    )
    private val PARTS = listOf("token", "signature", "session", "jwt", "secret", "password", "credential")

    /** The sensitive parameter names in [url]'s query, lowercased, in order, without repeats. */
    fun names(url: String): List<String> =
        UrlParts.query(url).map { it.first }.filter { name -> name in EXACT || PARTS.any { it in name } }.distinct()

    fun has(url: String): Boolean = names(url).isNotEmpty()
}
```

Create `ExpiryHints.kt`:

```kotlin
package com.example.musicfy.ritme.media

/** What a link's query says about how long and where it works (spec 4.7 step 4). */
data class LinkHints(
    /** Minutes until an `expires`/`exp` time in the link; 0 or less: already expired. Null: no such time. */
    val expiresInMinutes: Long?,
    /** An `ip=` parameter: the link may only work from the network that opened it. */
    val ipLocked: Boolean,
)

object ExpiryHints {
    private val EXPIRY_NAMES = setOf("expires", "exp", "expire", "expiry", "expiration")
    private val TOKEN_NAMES = setOf("hdnts", "hdnea", "__token__")
    private val TOKEN_EXPIRY = Regex("""(?:^|[~&])exp=(\d{9,13})""")
    private val IP_NAMES = setOf("ip", "ipaddr", "clientip", "client_ip")

    /** 2001-09-09: smaller numbers are lifetimes in seconds, not times. */
    private const val MIN_EPOCH_SECONDS = 1_000_000_000L

    fun of(url: String, nowMs: Long): LinkHints {
        val query = UrlParts.query(url)
        val expiresAt = query.mapNotNull { (name, value) ->
            when (name) {
                in EXPIRY_NAMES -> epochMs(value)
                in TOKEN_NAMES -> TOKEN_EXPIRY.find(value)?.groupValues?.get(1)?.let(::epochMs)
                else -> null
            }
        }.minOrNull()
        val minutes = expiresAt?.let { Math.floorDiv(it - nowMs + 59_999, 60_000L) }
        return LinkHints(minutes, query.any { it.first in IP_NAMES && it.second.isNotEmpty() })
    }

    /** Seconds (or milliseconds, 13 digits) since the epoch, or null when it is not a plausible time. */
    private fun epochMs(value: String): Long? {
        if (value.isEmpty() || value.length > 13 || !value.all(Char::isDigit)) return null
        val number = value.toLong()
        val ms = if (value.length == 13) number else number * 1000
        return ms.takeIf { it >= MIN_EPOCH_SECONDS * 1000 }
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL, `MediaUrlClassifierTest` and `UrlHintsTest` pass.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts modules/ritme-media
git commit -m "feat(ritme-media): new JVM module with request classifier, ad hosts and link hints" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `PlaylistSniffer` (magic bytes, HLS, DASH, MP4 length)

**Files:**
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/PlaylistSniffer.kt`
- Create: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/Mp4Fixtures.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/PlaylistSnifferTest.kt`

**Interfaces:**
- Produces: `enum class MediaFormat { HLS, DASH, MP4, WEBM, VIDEO, HTML, UNKNOWN }`; `data class HlsInfo(val master: Boolean, val variants: List<String>, val maxHeight: Int?, val ended: Boolean, val durationMs: Long?, val drm: Boolean, val aes128: Boolean)`; `data class DashInfo(val live: Boolean, val durationMs: Long?, val drm: Boolean, val maxHeight: Int?)`; `object PlaylistSniffer { fun detect(head: ByteArray, contentType: String?): MediaFormat; fun parseHls(text: String, baseUrl: String): HlsInfo; fun parseDash(text: String): DashInfo; fun mp4DurationMs(head: ByteArray): Long?; fun mp4Encrypted(head: ByteArray): Boolean; fun isoDurationMs(text: String): Long? }`.
- Produces (tests): `internal fun mp4Head(version: Int, timescale: Long, duration: Long, extraBox: String? = null): ByteArray` in `Mp4Fixtures.kt` (used again by Task 4).

- [ ] **Step 1: Write the test fixtures and failing tests**

Create `Mp4Fixtures.kt` (test sources):

```kotlin
package com.example.musicfy.ritme.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The start of a faststart MP4: `ftyp`, then `moov` holding `mvhd` (and [extraBox], an empty box of that type). */
internal fun mp4Head(version: Int, timescale: Long, duration: Long, extraBox: String? = null): ByteArray {
    val out = ByteArrayOutputStream()
    fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    out.write(box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(Charsets.ISO_8859_1)))
    val mvhd = ByteBuffer.allocate(if (version == 0) 100 else 112)
    mvhd.put(version.toByte()).put(byteArrayOf(0, 0, 0))
    if (version == 0) {
        mvhd.putInt(0).putInt(0).putInt(timescale.toInt()).putInt(duration.toInt())
    } else {
        mvhd.putLong(0).putLong(0).putInt(timescale.toInt()).putLong(duration)
    }
    val extra = extraBox?.let { box(it, ByteArray(0)) } ?: ByteArray(0)
    out.write(box("moov", box("mvhd", mvhd.array()) + extra))
    return out.toByteArray()
}
```

Create `PlaylistSnifferTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSnifferTest {

    private fun detect(text: String, type: String? = null) = PlaylistSniffer.detect(text.toByteArray(), type)

    @Test
    fun theFirstBytesSayWhatAResponseIs() {
        assertEquals(MediaFormat.HLS, detect("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertEquals(MediaFormat.HLS, detect("﻿  #EXTM3U\n"))
        assertEquals(MediaFormat.DASH, detect("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertEquals(MediaFormat.MP4, PlaylistSniffer.detect(mp4Head(0, 1000, 1000), null))
        assertEquals(MediaFormat.WEBM, PlaylistSniffer.detect(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2), null))
        assertEquals(MediaFormat.HTML, detect("<!DOCTYPE html><html><body>Not found</body></html>", "video/mp4"))
        assertEquals(MediaFormat.HLS, detect("??", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(MediaFormat.DASH, detect("??", "application/dash+xml"))
        assertEquals(MediaFormat.VIDEO, detect("??", "video/quicktime"))
        assertEquals(MediaFormat.UNKNOWN, detect("G@\u0011", "video/mp2t"))
        assertEquals(MediaFormat.UNKNOWN, detect("{\"a\":1}", "application/json"))
    }

    @Test
    fun aMasterPlaylistListsItsVariantsAbsolute() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            https://cdn2.example.com/720p/index.m3u8
        """.trimIndent()
        val info = PlaylistSniffer.parseHls(master, "https://cdn.example.com/v/master.m3u8?token=x")
        assertTrue(info.master)
        assertEquals(listOf("https://cdn.example.com/v/360p/index.m3u8", "https://cdn2.example.com/720p/index.m3u8"), info.variants)
        assertEquals(720, info.maxHeight)
        assertNull(info.durationMs)
        assertFalse(info.drm)
    }

    @Test
    fun aFinishedMediaPlaylistAddsUpItsSegments() {
        val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\ns0.ts\n#EXTINF:10,\ns1.ts\n#EXTINF:4.5,\ns2.ts\n#EXT-X-ENDLIST\n"
        val info = PlaylistSniffer.parseHls(vod, "https://cdn.example.com/v/index.m3u8")
        assertFalse(info.master)
        assertTrue(info.ended)
        assertEquals(24_500L, info.durationMs)
        assertFalse(PlaylistSniffer.parseHls(vod.replace("#EXT-X-ENDLIST\n", ""), "https://cdn.example.com/v/index.m3u8").ended)
    }

    @Test
    fun keysTellClearAesFromDrm() {
        fun keyed(line: String) = PlaylistSniffer.parseHls("#EXTM3U\n$line\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST", "https://cdn.example.com/i.m3u8")
        keyed("#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.com/k\"").let { assertTrue(it.aes128); assertFalse(it.drm) }
        assertTrue(keyed("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"").drm)
        assertTrue(keyed("#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"").drm)
        assertTrue(keyed("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.microsoft.playready\"").drm)
        keyed("#EXT-X-KEY:METHOD=NONE").let { assertFalse(it.aes128); assertFalse(it.drm) }
    }

    @Test
    fun dashManifestsSayLiveLengthDrmAndHeight() {
        val static = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S">
              <Period><AdaptationSet mimeType="video/mp4"><Representation id="1" width="1920" height="1080"/><Representation id="2" height="720"/></AdaptationSet></Period>
            </MPD>"""
        assertEquals(DashInfo(live = false, durationMs = 3_723_500, drm = false, maxHeight = 1080), PlaylistSniffer.parseDash(static))
        val live = static.replace("type=\"static\"", "type=\"dynamic\"")
        assertEquals(DashInfo(live = true, durationMs = null, drm = false, maxHeight = 1080), PlaylistSniffer.parseDash(live))
        val protected = static.replace("<Representation id=\"1\"", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/><Representation id=\"1\"")
        assertTrue(PlaylistSniffer.parseDash(protected).drm)
        assertTrue(PlaylistSniffer.parseDash(static.replace("<Period>", "<Period><dash:ContentProtection/>")).drm)
    }

    @Test
    fun isoDurations() {
        assertEquals(600_000L, PlaylistSniffer.isoDurationMs("PT10M"))
        assertEquals(86_401_000L, PlaylistSniffer.isoDurationMs("P1DT1S"))
        assertEquals(500L, PlaylistSniffer.isoDurationMs("pt0.5s"))
        assertNull(PlaylistSniffer.isoDurationMs("PT"))
        assertNull(PlaylistSniffer.isoDurationMs("10 minutes"))
    }

    @Test
    fun anMp4HeadGivesItsLengthAndItsProtection() {
        assertEquals(90_500L, PlaylistSniffer.mp4DurationMs(mp4Head(0, 1000, 90_500)))
        assertEquals(7_200_000L, PlaylistSniffer.mp4DurationMs(mp4Head(1, 90_000, 648_000_000)))
        assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(0, 1000, 0xFFFFFFFFL)))
        assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(0, 0, 1000)))
        assertNull(PlaylistSniffer.mp4DurationMs("#EXTM3U".toByteArray()))
        assertFalse(PlaylistSniffer.mp4Encrypted(mp4Head(0, 1000, 1000)))
        assertTrue(PlaylistSniffer.mp4Encrypted(mp4Head(0, 1000, 1000, extraBox = "pssh")))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :modules:ritme-media:test --tests 'com.example.musicfy.ritme.media.PlaylistSnifferTest'`
Expected: compilation FAILS (`Unresolved reference: PlaylistSniffer`, `MediaFormat`, `DashInfo`).

- [ ] **Step 3: Write `PlaylistSniffer`**

Create `PlaylistSniffer.kt`:

```kotlin
package com.example.musicfy.ritme.media

import java.net.URI

/** What a response is, from its first bytes, else its Content-Type (spec 4.7 step 4). */
enum class MediaFormat { HLS, DASH, MP4, WEBM, VIDEO, HTML, UNKNOWN }

data class HlsInfo(
    val master: Boolean,
    /** A master's variant playlists, absolute, in order. */
    val variants: List<String>,
    val maxHeight: Int?,
    /** A media playlist with #EXT-X-ENDLIST: a finished (VOD) stream. */
    val ended: Boolean,
    /** A media playlist's #EXTINF sum; null for a master. */
    val durationMs: Long?,
    val drm: Boolean,
    val aes128: Boolean,
)

data class DashInfo(val live: Boolean, val durationMs: Long?, val drm: Boolean, val maxHeight: Int?)

/**
 * Small, forgiving readers for what a probe gets back: the first 4 KiB of a response, or a whole
 * playlist. Never a full parser: just live or not, length, DRM, and the variants of a master.
 */
object PlaylistSniffer {
    private val MP4_BOXES = setOf("ftyp", "styp", "moov")
    private val WEBM_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val METHOD = Regex("""METHOD=([A-Za-z0-9-]+)""")
    private val KEYFORMAT = Regex("""KEYFORMAT="([^"]*)"""")
    private val EXTINF = Regex("""^#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val DRM_METHODS = setOf("SAMPLE-AES", "SAMPLE-AES-CTR", "SAMPLE-AES-CENC")
    private val DRM_KEYFORMATS = listOf(
        "com.widevine", "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "com.apple.streamingkeydelivery",
        "com.microsoft.playready", "9a04f079-9840-4286-ab92-e65be0885f95",
    )
    private val MPD_TAG = Regex("""<MPD\b[^>]*>""")
    private val TYPE_ATTRIBUTE = Regex("""\stype\s*=\s*"([^"]*)"""")
    private val DURATION_ATTRIBUTE = Regex("""\smediaPresentationDuration\s*=\s*"([^"]*)"""")
    private val CONTENT_PROTECTION = Regex("""<(?:[A-Za-z0-9_]+:)?ContentProtection\b""")
    private val HEIGHT_ATTRIBUTE = Regex("""\sheight\s*=\s*"(\d+)"""")
    private val ISO_DURATION =
        Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")

    fun detect(head: ByteArray, contentType: String?): MediaFormat {
        // Latin-1 keeps every byte as one char, so magic numbers and markup compare exactly.
        val text = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1)
        val start = text.removePrefix("ï»¿").trimStart()
        when {
            start.startsWith("#EXTM3U") -> return MediaFormat.HLS
            "<MPD" in text -> return MediaFormat.DASH
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_BOXES -> return MediaFormat.MP4
            head.size >= 4 && head.copyOfRange(0, 4).contentEquals(WEBM_MAGIC) -> return MediaFormat.WEBM
            start.lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") } -> return MediaFormat.HTML
        }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            "mpegurl" in type -> MediaFormat.HLS
            type == "application/dash+xml" -> MediaFormat.DASH
            type == "video/mp4" -> MediaFormat.MP4
            type == "video/webm" -> MediaFormat.WEBM
            type.startsWith("video/") && type != "video/mp2t" && type != "video/iso.segment" -> MediaFormat.VIDEO
            type == "text/html" -> MediaFormat.HTML
            else -> MediaFormat.UNKNOWN
        }
    }

    fun parseHls(text: String, baseUrl: String): HlsInfo {
        val variants = mutableListOf<String>()
        var maxHeight: Int? = null
        var master = false
        var expectVariant = false
        var drm = false
        var aes128 = false
        var ended = false
        var totalMs = 0L
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    master = true
                    expectVariant = true
                    val height = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()
                    if (height != null) maxHeight = maxOf(maxHeight ?: 0, height)
                }
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") -> {
                    val method = METHOD.find(line)?.groupValues?.get(1)?.uppercase()
                    val format = KEYFORMAT.find(line)?.groupValues?.get(1)?.lowercase().orEmpty()
                    if (method in DRM_METHODS || DRM_KEYFORMATS.any { it in format }) {
                        drm = true
                    } else if (method == "AES-128") {
                        aes128 = true
                    }
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF") -> EXTINF.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { totalMs += (it * 1000).toLong() }
                line.startsWith("#") -> Unit
                expectVariant -> {
                    resolve(baseUrl, line)?.let(variants::add)
                    expectVariant = false
                }
            }
        }
        return HlsInfo(master, variants, maxHeight, ended, if (master) null else totalMs, drm, aes128)
    }

    fun parseDash(text: String): DashInfo {
        val root = MPD_TAG.find(text)?.value.orEmpty()
        val live = TYPE_ATTRIBUTE.find(root)?.groupValues?.get(1) == "dynamic"
        val duration = DURATION_ATTRIBUTE.find(root)?.groupValues?.get(1)?.let(::isoDurationMs)
        val maxHeight = HEIGHT_ATTRIBUTE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        return DashInfo(live, if (live) null else duration, CONTENT_PROTECTION.containsMatchIn(text), maxHeight)
    }

    /** An ISO 8601 duration (`PT1H2M3.5S`, `P1DT1S`) in ms; null when malformed or zero. */
    fun isoDurationMs(text: String): Long? {
        val match = ISO_DURATION.matchEntire(text.trim().uppercase()) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
        val total = (days.toDoubleOrNull() ?: 0.0) * 86_400 + (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
            (minutes.toDoubleOrNull() ?: 0.0) * 60 + (seconds.toDoubleOrNull() ?: 0.0)
        return (total * 1000).toLong().takeIf { it > 0 }
    }

    /** A faststart MP4's length from its `mvhd` box (versions 0 and 1); null when not in [head]. */
    fun mp4DurationMs(head: ByteArray): Long? {
        val at = indexOf(head, "mvhd") ?: return null
        val version = head.getOrNull(at + 4)?.toInt() ?: return null
        val timescale: Long?
        val duration: Long?
        when (version) {
            0 -> {
                timescale = unsigned(head, at + 16, 4)
                duration = unsigned(head, at + 20, 4)?.takeIf { it != 0xFFFFFFFFL }
            }
            1 -> {
                timescale = unsigned(head, at + 24, 4)
                duration = unsigned(head, at + 28, 8)
            }
            else -> return null
        }
        if (timescale == null || duration == null || timescale <= 0 || duration <= 0 || duration > Long.MAX_VALUE / 1000) return null
        return duration * 1000 / timescale
    }

    /** CENC (common encryption) boxes in an MP4's header: protected content. */
    fun mp4Encrypted(head: ByteArray): Boolean = listOf("pssh", "encv", "enca", "tenc").any { indexOf(head, it) != null }

    private fun resolve(base: String, reference: String): String? =
        runCatching { URI(base).resolve(reference).toString() }.getOrNull()

    private fun indexOf(bytes: ByteArray, type: String): Int? {
        val target = type.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..bytes.size - target.size) {
            for (j in target.indices) if (bytes[i + j] != target[j]) continue@outer
            return i
        }
        return null
    }

    /** A big-endian unsigned number of [size] bytes at [at]; null when [bytes] ends first (or it overflows a Long). */
    private fun unsigned(bytes: ByteArray, at: Int, size: Int): Long? {
        if (at < 0 || at + size > bytes.size) return null
        val value = (0 until size).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }
        return value.takeIf { it >= 0 }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL, all `ritme-media` tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/ritme-media
git commit -m "feat(ritme-media): playlist sniffer for HLS, DASH and MP4 heads" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: `MediaProber` (the cookie-less, Referer-less check)

**Files:**
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/MediaProber.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/MediaProberTest.kt`

**Interfaces:**
- Consumes: `PlaylistSniffer`, `MediaFormat`, `mp4Head` (tests) from Task 3; `MediaUrlRules.referrerOf`, `MediaUrlRules.isAcceptableMediaUrl`, `StreamType` (Task 1).
- Produces:

```kotlin
class ProbeResponse(val status: Int, val contentType: String?, val body: ByteArray, val finalUrl: String, val allowOrigin: String?) { val ok: Boolean }
fun interface ProbeHttp { @Throws(IOException::class) fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse }
enum class Refusal { DRM, NEEDS_LOGIN, FAILED, NOT_MEDIA, LENGTH_UNKNOWN }
data class ProbeVerdict(
    val url: String, val streamType: StreamType, val format: MediaFormat,
    val live: Boolean = false, val durationMs: Long? = null, val height: Int? = null,
    val needsReferrer: Boolean = false, val webPlayable: Boolean = true, val aes128: Boolean = false,
    val variants: List<String> = emptyList(), val refusal: Refusal? = null,
) { companion object { fun refused(url: String, refusal: Refusal, streamType: StreamType = StreamType.UNKNOWN, format: MediaFormat = MediaFormat.UNKNOWN): ProbeVerdict } }
class MediaProber(http: ProbeHttp, webOrigin: String) { fun probe(url: String, pageUrl: String?): ProbeVerdict; companion object { const val HEAD_BYTES = 4_096; const val PLAYLIST_BYTES = 1 shl 20 } }
```

- [ ] **Step 1: Write the failing tests**

Create `MediaProberTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MediaProberTest {

    private class FakeHttp(private val answer: (url: String, headers: Map<String, String>) -> ProbeResponse?) : ProbeHttp {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse {
            calls += url to headers
            val full = answer(url, headers) ?: throw IOException("no answer")
            return ProbeResponse(full.status, full.contentType, full.body.copyOf(minOf(full.body.size, maxBytes)), full.finalUrl, full.allowOrigin)
        }
    }

    private fun text(url: String, body: String, type: String = "application/vnd.apple.mpegurl", allowOrigin: String? = null) =
        ProbeResponse(200, type, body.toByteArray(), url, allowOrigin)

    private fun bytes(url: String, body: ByteArray, type: String = "video/mp4") = ProbeResponse(200, type, body, url, null)

    private fun status(url: String, code: Int) = ProbeResponse(code, "text/html", "<!doctype html>".toByteArray(), url, null)

    private val web = "https://ritme.dubeli.id"
    private val mp4 = "https://cdn.example.com/films/a.mp4"
    private val master = "https://cdn.example.com/v/master.m3u8"
    private val variant = "https://cdn.example.com/v/360p/index.m3u8"
    private val masterText = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
        720p/index.m3u8
    """.trimIndent()
    private val vod = "#EXTM3U\n#EXTINF:10.0,\ns0.ts\n#EXTINF:10.0,\ns1.ts\n#EXTINF:4.5,\ns2.ts\n#EXT-X-ENDLIST\n"
    private val live = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:6.0,\ns120.ts\n#EXTINF:6.0,\ns121.ts\n"
    private val staticMpd = """<?xml version="1.0"?><MPD type="static" mediaPresentationDuration="PT10M"><Period><Representation height="1080"/></Period></MPD>"""

    @Test
    fun anMp4AnswersWithItsLengthAndPlaysOnTheWeb() {
        val http = FakeHttp { url, _ -> bytes(url, mp4Head(0, 1000, 90_500)) }
        val verdict = MediaProber(http, web).probe(mp4, "https://example.com/film")
        assertEquals(ProbeVerdict(mp4, StreamType.FILE, MediaFormat.MP4, durationMs = 90_500), verdict)
        // as another member: the first 4 KiB, no cookie, no Referer
        assertEquals(mapOf("Range" to "bytes=0-4095"), http.calls.single().second)
    }

    @Test
    fun aMasterPlaylistIsCheckedThroughItsFirstVariant() {
        val http = FakeHttp { url, headers ->
            when (url) {
                master -> text(url, masterText, allowOrigin = if ("Origin" in headers) "*" else null)
                variant -> text(url, vod)
                else -> null
            }
        }
        val verdict = MediaProber(http, web).probe(master, null)
        assertEquals(StreamType.HLS, verdict.streamType)
        assertFalse(verdict.live)
        assertEquals(24_500L, verdict.durationMs)
        assertEquals(720, verdict.height)
        assertEquals(listOf(variant, "https://cdn.example.com/v/720p/index.m3u8"), verdict.variants)
        assertTrue(verdict.webPlayable)
        assertNull(verdict.refusal)
        assertEquals(web, http.calls.last().second["Origin"])
    }

    @Test
    fun aLiveStreamWithoutCorsCannotPlayOnTheWeb() {
        val http = FakeHttp { url, _ -> text(url, live) }
        val verdict = MediaProber(http, web).probe("https://cdn.example.com/live/index.m3u8", null)
        assertTrue(verdict.live)
        assertNull(verdict.durationMs)
        assertFalse(verdict.webPlayable)
        assertNull(verdict.refusal)
    }

    @Test
    fun drmIsRefused() {
        val sampleAes = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\ns.ts\n#EXT-X-ENDLIST"
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> text(url, sampleAes) }, web).probe(master, null).refusal)
        val protectedMpd = staticMpd.replace("<Period>", "<Period><ContentProtection value=\"cenc\"/>")
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> text(url, protectedMpd, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null).refusal)
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> bytes(url, mp4Head(0, 1000, 1000, extraBox = "pssh")) }, web).probe(mp4, null).refusal)
    }

    @Test
    fun dashIsNeverForTheWebAndDynamicIsLive() {
        val verdict = MediaProber(FakeHttp { url, _ -> text(url, staticMpd, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null)
        assertEquals(ProbeVerdict("https://cdn.example.com/v.mpd", StreamType.DASH, MediaFormat.DASH, durationMs = 600_000, height = 1080, webPlayable = false), verdict)
        val dynamic = staticMpd.replace("type=\"static\"", "type=\"dynamic\"")
        assertTrue(MediaProber(FakeHttp { url, _ -> text(url, dynamic, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null).live)
    }

    @Test
    fun aLinkThatOnlyWorksWithTheRefererIsAppOnly() {
        val http = FakeHttp { url, headers -> if (headers["Referer"] == "https://example.com/") bytes(url, mp4Head(0, 1000, 60_000)) else status(url, 403) }
        val verdict = MediaProber(http, web).probe(mp4, "https://example.com/film/123#t=1")
        assertTrue(verdict.needsReferrer)
        assertFalse(verdict.webPlayable)
        assertEquals(60_000L, verdict.durationMs)
        assertNull(verdict.refusal)
        assertEquals(listOf(null, "https://example.com/"), http.calls.map { it.second["Referer"] })
    }

    @Test
    fun forbiddenWithAndWithoutTheRefererNeedsALogin() {
        val verdict = MediaProber(FakeHttp { url, _ -> status(url, 403) }, web).probe(mp4, "https://example.com/film")
        assertEquals(Refusal.NEEDS_LOGIN, verdict.refusal)
    }

    @Test
    fun aPageOnThisNetworkIsNeverUsedAsAReferrer() {
        val http = FakeHttp { url, _ -> status(url, 401) }
        assertEquals(Refusal.NEEDS_LOGIN, MediaProber(http, web).probe(mp4, "http://192.168.1.1/films").refusal)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun noAnswerOrAnErrorIsAFailure() {
        assertEquals(Refusal.FAILED, MediaProber(FakeHttp { _, _ -> null }, web).probe(mp4, null).refusal)
        assertEquals(Refusal.FAILED, MediaProber(FakeHttp { url, _ -> status(url, 404) }, web).probe(mp4, null).refusal)
        // a master whose variant cannot be read says nothing about live or length
        val noVariant = FakeHttp { url, _ -> if (url == master) text(url, masterText) else null }
        assertEquals(Refusal.FAILED, MediaProber(noVariant, web).probe(master, null).refusal)
    }

    @Test
    fun aWebPageIsNotMedia() {
        val verdict = MediaProber(FakeHttp { url, _ -> text(url, "<!DOCTYPE html><title>Film</title>", "text/html") }, web).probe("https://example.com/embed/1", null)
        assertEquals(Refusal.NOT_MEDIA, verdict.refusal)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :modules:ritme-media:test --tests 'com.example.musicfy.ritme.media.MediaProberTest'`
Expected: compilation FAILS (`Unresolved reference: ProbeHttp`, `ProbeResponse`, `MediaProber`, `ProbeVerdict`, `Refusal`).

- [ ] **Step 3: Write `MediaProber`**

Create `MediaProber.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import java.io.IOException

/** One HTTP answer, cut to the bytes the probe asked for. */
class ProbeResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    /** After redirects: the base for a playlist's relative links. */
    val finalUrl: String,
    /** The Access-Control-Allow-Origin header, for the web check. */
    val allowOrigin: String?,
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * How a probe reaches the network: a plain GET with exactly [headers] (nothing else, no cookies),
 * redirects followed, the body cut at [maxBytes]. Android: OkHttp through RitmeAddressGuard;
 * desktop later: its own client. Throws IOException when there is no answer.
 */
fun interface ProbeHttp {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse
}

/** Why a find cannot be added (U12), or why it is not listed at all ([NOT_MEDIA]). */
enum class Refusal { DRM, NEEDS_LOGIN, FAILED, NOT_MEDIA, LENGTH_UNKNOWN }

data class ProbeVerdict(
    val url: String,
    val streamType: StreamType,
    val format: MediaFormat,
    val live: Boolean = false,
    /** A finished stream's or a file's length; null when live or not known. */
    val durationMs: Long? = null,
    val height: Int? = null,
    /** Refused without a Referer, fine with the page's origin as one: Android only ("Hanya di aplikasi"). */
    val needsReferrer: Boolean = false,
    /** The web client could play it: no Referer needed, https, CORS for HLS, not DASH. */
    val webPlayable: Boolean = true,
    /** AES-128 keys: allowed, but the key server may want cookies. */
    val aes128: Boolean = false,
    /** A master playlist's variants: listed only through their master. */
    val variants: List<String> = emptyList(),
    val refusal: Refusal? = null,
) {
    companion object {
        fun refused(
            url: String,
            refusal: Refusal,
            streamType: StreamType = StreamType.UNKNOWN,
            format: MediaFormat = MediaFormat.UNKNOWN,
        ) = ProbeVerdict(url, streamType, format, webPlayable = false, refusal = refusal)
    }
}

/**
 * Checks a find the way another member's device will fetch it (spec 4.7 step 4): no cookies, no
 * Referer, the first [HEAD_BYTES] only; then a playlist in full (and a master's first variant).
 * Refused without headers but fine with the page's origin as Referer → [ProbeVerdict.needsReferrer];
 * 401/403 either way → [Refusal.NEEDS_LOGIN]. HLS also gets an `Origin: <web client>` request: no
 * matching Access-Control-Allow-Origin → not playable on the web. Blocking: callers bound it in time.
 */
class MediaProber(private val http: ProbeHttp, private val webOrigin: String) {

    fun probe(url: String, pageUrl: String?): ProbeVerdict {
        val plain = fetch(url, RANGE, HEAD_BYTES)
        var response = plain
        var extra = emptyMap<String, String>()
        val referrer = pageUrl?.let(MediaUrlRules::referrerOf)
        if (plain?.ok != true && referrer != null) {
            val withReferrer = fetch(url, RANGE + (REFERER to referrer), HEAD_BYTES)
            if (withReferrer?.ok == true) {
                response = withReferrer
                extra = mapOf(REFERER to referrer)
            }
        }
        if (response == null || !response.ok) {
            val denied = plain != null && plain.status in DENIED
            return ProbeVerdict.refused(url, if (denied) Refusal.NEEDS_LOGIN else Refusal.FAILED)
        }
        val needsReferrer = extra.isNotEmpty()
        return when (val format = PlaylistSniffer.detect(response.body, response.contentType)) {
            MediaFormat.HLS -> hls(url, response, extra, needsReferrer)
            MediaFormat.DASH -> dash(url, response, extra, needsReferrer)
            MediaFormat.MP4, MediaFormat.WEBM, MediaFormat.VIDEO -> file(url, format, response, needsReferrer)
            MediaFormat.HTML, MediaFormat.UNKNOWN -> ProbeVerdict.refused(url, Refusal.NOT_MEDIA)
        }
    }

    private fun hls(url: String, head: ProbeResponse, extra: Map<String, String>, needsReferrer: Boolean): ProbeVerdict {
        val failed = ProbeVerdict.refused(url, Refusal.FAILED, StreamType.HLS, MediaFormat.HLS)
        val top = whole(url, head, extra)?.let { PlaylistSniffer.parseHls(it.text, it.baseUrl) } ?: return failed
        val media = if (!top.master) {
            top
        } else {
            val variant = top.variants.firstOrNull(MediaUrlRules::isAcceptableMediaUrl) ?: return failed
            val answer = fetch(variant, extra, PLAYLIST_BYTES)?.takeIf { it.ok } ?: return failed
            PlaylistSniffer.parseHls(answer.body.decodeToString(), answer.finalUrl)
        }
        if (top.drm || media.drm) return ProbeVerdict.refused(url, Refusal.DRM, StreamType.HLS, MediaFormat.HLS)
        val live = !media.ended
        return ProbeVerdict(
            url = url,
            streamType = StreamType.HLS,
            format = MediaFormat.HLS,
            live = live,
            durationMs = if (live) null else media.durationMs?.takeIf { it > 0 },
            height = top.maxHeight,
            needsReferrer = needsReferrer,
            webPlayable = !needsReferrer && isHttps(url) && corsAllows(url, extra),
            aes128 = top.aes128 || media.aes128,
            variants = top.variants,
        )
    }

    private fun dash(url: String, head: ProbeResponse, extra: Map<String, String>, needsReferrer: Boolean): ProbeVerdict {
        val info = whole(url, head, extra)?.let { PlaylistSniffer.parseDash(it.text) }
            ?: return ProbeVerdict.refused(url, Refusal.FAILED, StreamType.DASH, MediaFormat.DASH)
        if (info.drm) return ProbeVerdict.refused(url, Refusal.DRM, StreamType.DASH, MediaFormat.DASH)
        // The web client plays no DASH (D11).
        return ProbeVerdict(url, StreamType.DASH, MediaFormat.DASH, info.live, info.durationMs, info.maxHeight, needsReferrer, webPlayable = false)
    }

    private fun file(url: String, format: MediaFormat, head: ProbeResponse, needsReferrer: Boolean): ProbeVerdict {
        if (format == MediaFormat.MP4 && PlaylistSniffer.mp4Encrypted(head.body)) {
            return ProbeVerdict.refused(url, Refusal.DRM, StreamType.FILE, format)
        }
        return ProbeVerdict(
            url = url,
            streamType = StreamType.FILE,
            format = format,
            durationMs = if (format == MediaFormat.MP4) PlaylistSniffer.mp4DurationMs(head.body) else null,
            needsReferrer = needsReferrer,
            // <video> plays a cross-origin file without CORS; only a Referer it cannot send stops it.
            webPlayable = !needsReferrer && isHttps(url),
        )
    }

    private class Text(val text: String, val baseUrl: String)

    /** The whole playlist: the head when it was shorter than the range asked for, else fetched in full. */
    private fun whole(url: String, head: ProbeResponse, extra: Map<String, String>): Text? {
        val answer = if (head.body.size < HEAD_BYTES) head else fetch(url, extra, PLAYLIST_BYTES)?.takeIf { it.ok } ?: return null
        return Text(answer.body.decodeToString(), answer.finalUrl)
    }

    private fun corsAllows(url: String, extra: Map<String, String>): Boolean {
        val answer = fetch(url, extra + RANGE + (ORIGIN to webOrigin), HEAD_BYTES) ?: return false
        val allowed = answer.allowOrigin?.trim() ?: return false
        return answer.ok && (allowed == "*" || allowed.equals(webOrigin, ignoreCase = true))
    }

    private fun fetch(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse? = try {
        http.get(url, headers, maxBytes)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // a url the HTTP client cannot even build a request for
        null
    }

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    companion object {
        const val HEAD_BYTES = 4_096
        const val PLAYLIST_BYTES = 1 shl 20
        private const val REFERER = "Referer"
        private const val ORIGIN = "Origin"
        private val RANGE = mapOf("Range" to "bytes=0-${HEAD_BYTES - 1}")
        private val DENIED = setOf(401, 403)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL, all `ritme-media` tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/ritme-media
git commit -m "feat(ritme-media): cookie-less media prober with referrer retry, DRM and web checks" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `BrowserAddress` (the address bar and navigation rules)

**Files:**
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/BrowserAddress.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/BrowserAddressTest.kt`

**Interfaces:**
- Consumes: `MediaUrlRules.check` (Task 1).
- Produces: `sealed interface AddressInput { data class Open(val url: String); data class Refused(val reason: AddressRefusal) }`; `enum class AddressRefusal { EMPTY, SCHEME, LOCAL, CREDENTIALS, MALFORMED }`; `enum class Navigation { ALLOW, REFUSE_LOCAL, ASK_EXTERNAL, BLOCK }`; `object BrowserAddress { const val START_URL = "https://duckduckgo.com/"; fun resolve(input: String): AddressInput; fun navigation(url: String, hasGesture: Boolean): Navigation }`.

- [ ] **Step 1: Write the failing tests**

Create `BrowserAddressTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressTest {

    private fun open(url: String) = AddressInput.Open(url)
    private fun refused(reason: AddressRefusal) = AddressInput.Refused(reason)

    @Test
    fun fullAddressesOpenAsTyped() {
        assertEquals(open("https://example.com/film?id=1#t=3"), BrowserAddress.resolve("  https://example.com/film?id=1#t=3 "))
        assertEquals(open("HTTP://Example.COM"), BrowserAddress.resolve("HTTP://Example.COM"))
    }

    @Test
    fun aBareDomainBecomesHttps() {
        assertEquals(open("https://example.com/video"), BrowserAddress.resolve("example.com/video"))
        assertEquals(open("https://example.com:8080/x"), BrowserAddress.resolve("example.com:8080/x"))
        assertEquals(open("https://[2001:db8::1]/v"), BrowserAddress.resolve("[2001:db8::1]/v"))
    }

    @Test
    fun wordsGoToDuckDuckGo() {
        assertEquals(open("https://duckduckgo.com/?q=lofi+hip+hop"), BrowserAddress.resolve("lofi hip hop"))
        assertEquals(open("https://duckduckgo.com/?q=lofi"), BrowserAddress.resolve("lofi"))
        assertEquals(open("https://duckduckgo.com/?q=3.5"), BrowserAddress.resolve("3.5"))
        assertEquals(open("https://duckduckgo.com/?q=c%2B%2B+%26+rust"), BrowserAddress.resolve("c++ & rust"))
    }

    @Test
    fun dangerousSchemesAreRefused() {
        listOf(
            "javascript:alert(1)", "JavaScript:void(0)", "file:///sdcard/a.mp4", "content://media/external/video/1",
            "intent://scan/#Intent;scheme=zxing;end", "data:text/html,<b>x</b>", "ftp://example.com/a", "about:blank",
        ).forEach { assertEquals(it, refused(AddressRefusal.SCHEME), BrowserAddress.resolve(it)) }
    }

    @Test
    fun localAndPrivateAddressesAreRefused() {
        listOf(
            "192.168.1.1", "10.0.0.1/admin", "localhost", "localhost:3000", "http://127.0.0.1:8080/",
            "router.local", "http://[::1]/", "http://2130706433/",
        ).forEach { assertEquals(it, refused(AddressRefusal.LOCAL), BrowserAddress.resolve(it)) }
    }

    @Test
    fun credentialsEmptyAndBrokenInputAreRefused() {
        assertEquals(refused(AddressRefusal.CREDENTIALS), BrowserAddress.resolve("https://user:pw@example.com/"))
        assertEquals(refused(AddressRefusal.CREDENTIALS), BrowserAddress.resolve("user:pw@example.com"))
        assertEquals(refused(AddressRefusal.EMPTY), BrowserAddress.resolve("   "))
        assertEquals(refused(AddressRefusal.MALFORMED), BrowserAddress.resolve("https://"))
    }

    @Test
    fun pageNavigations() {
        assertEquals(Navigation.ALLOW, BrowserAddress.navigation("https://example.com/next", hasGesture = false))
        assertEquals(Navigation.ALLOW, BrowserAddress.navigation("about:blank", hasGesture = false))
        assertEquals(Navigation.REFUSE_LOCAL, BrowserAddress.navigation("http://192.168.1.1/", hasGesture = true))
        assertEquals(Navigation.REFUSE_LOCAL, BrowserAddress.navigation("http://localhost:8080/", hasGesture = true))
        assertEquals(Navigation.ASK_EXTERNAL, BrowserAddress.navigation("intent://open#Intent;package=com.example;end", hasGesture = true))
        assertEquals(Navigation.ASK_EXTERNAL, BrowserAddress.navigation("market://details?id=com.example", hasGesture = true))
        assertEquals(Navigation.BLOCK, BrowserAddress.navigation("intent://open#Intent;end", hasGesture = false))
        listOf("javascript:alert(1)", "file:///etc/hosts", "content://x/y", "data:text/html,x").forEach {
            assertEquals(it, Navigation.BLOCK, BrowserAddress.navigation(it, hasGesture = true))
        }
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :modules:ritme-media:test --tests 'com.example.musicfy.ritme.media.BrowserAddressTest'`
Expected: compilation FAILS (`Unresolved reference: BrowserAddress`, `AddressInput`, `AddressRefusal`, `Navigation`).

- [ ] **Step 3: Write `BrowserAddress`**

Create `BrowserAddress.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import java.net.URLEncoder

/** What the address bar does with what was typed (user decision 2026-11-23). */
sealed interface AddressInput {
    data class Open(val url: String) : AddressInput

    data class Refused(val reason: AddressRefusal) : AddressInput
}

enum class AddressRefusal {
    EMPTY,

    /** javascript:, file:, content:, intent:, data:, and every scheme but http(s). */
    SCHEME,

    /** localhost, a private or local address, or a local name (MediaUrlRules). */
    LOCAL,

    /** `user:password@` in the address. */
    CREDENTIALS,
    MALFORMED,
}

/** What the browser does with a navigation the page starts. */
enum class Navigation { ALLOW, REFUSE_LOCAL, ASK_EXTERNAL, BLOCK }

/**
 * The Ritme browser's address rules: a full http(s) URL opens as typed, a bare domain
 * (`example.com/video`) gets `https://`, anything else is a DuckDuckGo search (U11). Other schemes
 * and local or private hosts (MediaUrlRules, the same rules as room URLs) are refused.
 */
object BrowserAddress {
    const val START_URL = "https://duckduckgo.com/"
    private const val SEARCH_URL = "https://duckduckgo.com/?q="
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Schemes written without "//": never a host name followed by a port. */
    private val OPAQUE_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent", "about", "blob", "mailto", "tel", "sms", "market",
        "view-source", "chrome", "ws", "wss", "ftp", "vnd.youtube",
    )

    fun resolve(input: String): AddressInput {
        val text = input.trim()
        if (text.isEmpty()) return AddressInput.Refused(AddressRefusal.EMPTY)
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        val typedScheme = scheme != null && (text.substring(scheme.length + 1).startsWith("//") || scheme in OPAQUE_SCHEMES)
        if (typedScheme) {
            if (scheme != "http" && scheme != "https") return AddressInput.Refused(AddressRefusal.SCHEME)
            return verdict(text, guessed = false) ?: AddressInput.Refused(AddressRefusal.MALFORMED)
        }
        if (looksLikeHost(text)) verdict("https://$text", guessed = true)?.let { return it }
        return AddressInput.Open(SEARCH_URL + URLEncoder.encode(text, Charsets.UTF_8))
    }

    fun navigation(url: String, hasGesture: Boolean): Navigation {
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return Navigation.BLOCK
        return when (scheme) {
            "http", "https" -> when (MediaUrlRules.check(url.substringBefore('#'))) {
                MediaUrlRules.Rejection.LOCAL_HOST, MediaUrlRules.Rejection.NON_CANONICAL_IP -> Navigation.REFUSE_LOCAL
                else -> Navigation.ALLOW
            }
            "about" -> if (url.equals("about:blank", ignoreCase = true)) Navigation.ALLOW else Navigation.BLOCK
            "javascript", "file", "content", "data", "blob", "chrome", "view-source" -> Navigation.BLOCK
            // intent:, market:, another app's scheme: only when the user tapped, and only after asking
            else -> if (hasGesture) Navigation.ASK_EXTERNAL else Navigation.BLOCK
        }
    }

    /** Null for a guessed host that is not one after all (`3.5`): the caller searches instead. */
    private fun verdict(url: String, guessed: Boolean): AddressInput? =
        when (MediaUrlRules.check(url.substringBefore('#'))) {
            null -> AddressInput.Open(url)
            MediaUrlRules.Rejection.LOCAL_HOST -> AddressInput.Refused(AddressRefusal.LOCAL)
            MediaUrlRules.Rejection.NON_CANONICAL_IP -> if (guessed) null else AddressInput.Refused(AddressRefusal.LOCAL)
            MediaUrlRules.Rejection.USERINFO -> AddressInput.Refused(AddressRefusal.CREDENTIALS)
            MediaUrlRules.Rejection.SCHEME -> AddressInput.Refused(AddressRefusal.SCHEME)
            else -> if (guessed) null else AddressInput.Refused(AddressRefusal.MALFORMED)
        }

    private fun looksLikeHost(text: String): Boolean {
        if (text.any(Char::isWhitespace)) return false
        val authority = text.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        if (authority.startsWith("[")) return true
        val name = authority.substringBefore(':')
        return name.equals("localhost", ignoreCase = true) ||
            ('.' in name && !name.startsWith('.') && !name.endsWith('.'))
    }
}
```

Check against the tests: `"http://2130706433/"` is a typed scheme, `check` says `NON_CANONICAL_IP` → `LOCAL`; `"3.5"` is guessed, `NON_CANONICAL_IP` → null → search; `"https://"` → `NO_HOST` → `MALFORMED`; `"about:blank"` typed (opaque) → `SCHEME`; `"user:pw@example.com"` → scheme `user` is neither `//` nor opaque, so it is a guessed host → `USERINFO` → `CREDENTIALS`.

- [ ] **Step 4: Run the tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add modules/ritme-media
git commit -m "feat(ritme-media): browser address bar and navigation rules" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The page script (`page-media.js`) and its parser (`PageReports`)

**Files:**
- Create: `modules/ritme-media/src/main/resources/com/example/musicfy/ritme/media/page-media.js`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/PageMediaScript.kt`
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/PageReport.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/PageReportsTest.kt`
- Test: `ritme-web/src/external/page-media.test.ts`

**Interfaces:**
- Consumes: `PlaylistSniffer.isoDurationMs` (Task 3), `MediaUrlRules.MAX_URL_LENGTH`.
- Produces: `object PageMediaScript { const val BRIDGE_NAME = "ritmeMedia"; val source: String }`; `data class PageVideo(val urls: List<String>, val blob: Boolean, val durationMs: Long?, val live: Boolean, val width: Int, val height: Int, val area: Long, val playing: Boolean)`; `data class PageLdVideo(val url: String?, val durationMs: Long?, val name: String?)`; `data class PageReport(val frameUrl: String?, val mainFrame: Boolean, val title: String?, val ogTitle: String?, val siteName: String?, val ogVideos: List<String>, val ldVideos: List<PageLdVideo>, val videos: List<PageVideo>, val eme: Boolean)`; `object PageReports { const val MAX_MESSAGE_CHARS = 64_000; const val MAX_ITEMS = 20; fun parse(message: String, mainFrame: Boolean): PageReport? }`.
- The message format (JSON, `v: 1`): `{ v, page, title, ogTitle, site, og: string[], ld: {url, duration, name}[], videos: {srcs: string[], blob, duration (seconds, -1 = live, null = unknown), w, h, area, playing}[], eme }`.

- [ ] **Step 1: Write the failing Kotlin test**

Create `PageReportsTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReportsTest {

    private val full = """
        {"v":1,"page":"https://example.com/film/1","title":"Film | Example","ogTitle":"Film","site":"Example TV",
         "og":["https://cdn.example.com/og.mp4","javascript:alert(1)"],
         "ld":[{"url":"https://cdn.example.com/ld.m3u8","duration":"PT2M","name":"Klip"},{"url":null,"duration":null,"name":null}],
         "videos":[{"srcs":["https://cdn.example.com/a.mp4"],"blob":false,"duration":61.5,"w":1280,"h":720,"area":230400,"playing":true},
                   {"srcs":[],"blob":true,"duration":-1,"w":0,"h":0,"area":0,"playing":false}],
         "eme":true}
    """.trimIndent()

    @Test
    fun aFullReportIsRead() {
        val report = PageReports.parse(full, mainFrame = true)!!
        assertEquals("https://example.com/film/1", report.frameUrl)
        assertTrue(report.mainFrame)
        assertEquals("Film | Example", report.title)
        assertEquals("Film", report.ogTitle)
        assertEquals("Example TV", report.siteName)
        assertEquals(listOf("https://cdn.example.com/og.mp4"), report.ogVideos)
        assertEquals(listOf(PageLdVideo("https://cdn.example.com/ld.m3u8", 120_000, "Klip"), PageLdVideo(null, null, null)), report.ldVideos)
        assertEquals(PageVideo(listOf("https://cdn.example.com/a.mp4"), false, 61_500, false, 1280, 720, 230_400, true), report.videos[0])
        assertEquals(PageVideo(emptyList(), true, null, true, 0, 0, 0, false), report.videos[1])
        assertTrue(report.eme)
    }

    @Test
    fun theScriptShipsWithTheModuleAndTalksOnlyToItsBridge() {
        val script = PageMediaScript.source
        assertTrue(script.contains("window.${PageMediaScript.BRIDGE_NAME}"))
        assertTrue(script.contains("postMessage"))
        assertTrue(!script.contains("XMLHttpRequest") && !script.contains("fetch("))
    }

    @Test
    fun anythingElseIsDropped() {
        assertNull(PageReports.parse("not json", true))
        assertNull(PageReports.parse("[1,2]", true))
        assertNull(PageReports.parse("""{"v":2}""", true))
        assertNull(PageReports.parse("""{"v":"1"}""", true))
        assertNull(PageReports.parse("""{"v":1,"title":"${"a".repeat(PageReports.MAX_MESSAGE_CHARS)}"}""", true))
    }

    @Test
    fun pageDataIsBoundedAndCheckedAgain() {
        // one overlong source only, so the whole message stays under MAX_MESSAGE_CHARS
        val tooLong = "https://cdn.example.com/" + "a".repeat(2100)
        val videos = (1..30).joinToString(",") { i ->
            val extra = if (i == 1) ""","$tooLong"""" else ""
            """{"srcs":["https://cdn.example.com/$i.mp4","data:x"$extra],"area":1e12,"w":99999,"h":-5}"""
        }
        val report = PageReports.parse("""{"v":1,"title":"${"t".repeat(400)}","videos":[$videos],"eme":"yes"}""", mainFrame = false)!!
        assertEquals(PageReports.MAX_ITEMS, report.videos.size)
        assertEquals(listOf("https://cdn.example.com/1.mp4"), report.videos[0].urls)
        assertEquals(100_000_000L, report.videos[0].area)
        assertEquals(16_384, report.videos[0].width)
        assertEquals(0, report.videos[0].height)
        assertEquals(300, report.title!!.length)
        assertEquals(false, report.eme)
        assertNull(report.frameUrl)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `g :modules:ritme-media:test --tests 'com.example.musicfy.ritme.media.PageReportsTest'`
Expected: compilation FAILS (`Unresolved reference: PageReports`, `PageVideo`, `PageLdVideo`).

- [ ] **Step 3: Write the page script**

Create `modules/ritme-media/src/main/resources/com/example/musicfy/ritme/media/page-media.js`:

```js
// Ritme in-app browser page script (spec 4.7 step 2). Injected at document start into every frame;
// reports this frame's videos to the app through the `ritmeMedia` web message listener. It reads
// the page and never changes it. Plain ES2015 so old WebViews run it. Desktop (JCEF) will inject
// the same file. Tests: ritme-web/src/external/page-media.test.ts.
(function () {
  'use strict';
  if (window.__ritmeMediaInstalled) return;
  window.__ritmeMediaInstalled = true;
  var bridge = window.ritmeMedia;
  if (!bridge || typeof bridge.postMessage !== 'function') return;

  var MAX_URL = 2048;
  var MAX_ITEMS = 20;
  var MAX_SRCS = 5;
  var MAX_TEXT = 300;
  var DEBOUNCE_MS = 400;
  var eme = false;
  var last = '';
  var timer = 0;

  function text(value) {
    return typeof value === 'string' && value.trim() ? value.trim().slice(0, MAX_TEXT) : null;
  }

  // An absolute http(s) address without its #fragment, 'blob:' for a blob, else null.
  function address(value) {
    if (typeof value !== 'string' || !value || value.length > MAX_URL) return null;
    var parsed;
    try {
      parsed = new URL(value, location.href);
    } catch (e) {
      return null;
    }
    if (parsed.protocol === 'blob:') return 'blob:';
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    parsed.hash = '';
    return parsed.href.length > MAX_URL ? null : parsed.href;
  }

  function meta(name) {
    var el = document.querySelector('meta[property="' + name + '"], meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  function videos() {
    var out = [];
    var list = document.getElementsByTagName('video');
    for (var i = 0; i < list.length && out.length < MAX_ITEMS; i++) {
      var video = list[i];
      var candidates = [video.currentSrc, video.getAttribute('src')];
      var sources = video.getElementsByTagName('source');
      for (var j = 0; j < sources.length; j++) candidates.push(sources[j].getAttribute('src'));
      var srcs = [];
      var blob = false;
      for (var k = 0; k < candidates.length; k++) {
        var found = address(candidates[k]);
        if (found === 'blob:') blob = true;
        else if (found && srcs.indexOf(found) < 0 && srcs.length < MAX_SRCS) srcs.push(found);
      }
      var box = video.getBoundingClientRect();
      var duration = video.duration;
      out.push({
        srcs: srcs,
        blob: blob,
        duration: duration === Infinity ? -1 : isFinite(duration) && duration > 0 ? duration : null,
        w: video.videoWidth || 0,
        h: video.videoHeight || 0,
        area: Math.round(Math.max(0, box.width) * Math.max(0, box.height)),
        playing: !video.paused && !video.ended,
      });
    }
    return out;
  }

  function linkedData() {
    var out = [];
    function walk(node, depth) {
      if (!node || depth > 4 || out.length >= MAX_ITEMS) return;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) walk(node[i], depth + 1);
        return;
      }
      if (typeof node !== 'object') return;
      if (node['@graph']) walk(node['@graph'], depth + 1);
      var type = node['@type'];
      if (type === 'VideoObject' || (Array.isArray(type) && type.indexOf('VideoObject') >= 0)) {
        var url = address(node.contentUrl);
        out.push({ url: url === 'blob:' ? null : url, duration: text(node.duration), name: text(node.name) });
      }
    }
    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (var i = 0; i < scripts.length; i++) {
      try {
        walk(JSON.parse(scripts[i].textContent || ''), 0);
      } catch (e) {
        // not JSON: skip it
      }
    }
    return out;
  }

  function openGraph() {
    var out = [];
    var names = ['og:video:secure_url', 'og:video:url', 'og:video'];
    for (var i = 0; i < names.length; i++) {
      var found = address(meta(names[i]));
      if (found && found !== 'blob:' && out.indexOf(found) < 0) out.push(found);
    }
    return out;
  }

  function report() {
    timer = 0;
    var page = address(location.href);
    var message = JSON.stringify({
      v: 1,
      page: page === 'blob:' ? null : page,
      title: text(document.title),
      ogTitle: text(meta('og:title')),
      site: text(meta('og:site_name')),
      og: openGraph(),
      ld: linkedData(),
      videos: videos(),
      eme: eme,
    });
    if (message === last) return;
    last = message;
    try {
      bridge.postMessage(message);
    } catch (e) {
      // the app went away
    }
  }

  function schedule() {
    if (!timer) timer = setTimeout(report, DEBOUNCE_MS);
  }

  var WATCHED = { VIDEO: 1, SOURCE: 1, META: 1, SCRIPT: 1 };
  function touchesMedia(node) {
    if (!node || node.nodeType !== 1) return false;
    if (WATCHED[node.nodeName]) return true;
    return typeof node.querySelector === 'function' && node.querySelector('video, source, meta, script') !== null;
  }

  // DRM: a page asking for a key system (spec 4.7 table). Reported, never blocked.
  try {
    var request = navigator.requestMediaKeySystemAccess;
    if (typeof request === 'function') {
      navigator.requestMediaKeySystemAccess = function () {
        eme = true;
        schedule();
        return request.apply(navigator, arguments);
      };
    }
  } catch (e) {
    // read-only here: the playlist probe still sees DRM keys
  }

  ['loadedmetadata', 'durationchange', 'play', 'playing', 'pause', 'emptied'].forEach(function (type) {
    document.addEventListener(type, schedule, true);
  });
  document.addEventListener('DOMContentLoaded', schedule);
  window.addEventListener('load', schedule);
  try {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var record = records[i];
        var hit = record.type === 'attributes' ? touchesMedia(record.target) : Array.prototype.some.call(record.addedNodes, touchesMedia);
        if (hit) {
          schedule();
          return;
        }
      }
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'content'] });
  } catch (e) {
    // no observer: the media events above still report
  }
  schedule();
})();
```

- [ ] **Step 4: Write the Kotlin side**

Create `PageMediaScript.kt`:

```kotlin
package com.example.musicfy.ritme.media

/**
 * The in-app browser's page script (spec 4.7 step 2, D10): injected at document start into every
 * frame, it reports the frame's `<video>` elements, og:video, JSON-LD VideoObjects and title as one
 * JSON message to [BRIDGE_NAME] (Android: WebViewCompat.addWebMessageListener; desktop later: JCEF).
 * The page is untrusted: [PageReports.parse] checks everything again.
 * Tests: ritme-web/src/external/page-media.test.ts runs it in jsdom.
 */
object PageMediaScript {
    const val BRIDGE_NAME = "ritmeMedia"
    private const val RESOURCE = "/com/example/musicfy/ritme/media/page-media.js"

    val source: String by lazy {
        val stream = PageMediaScript::class.java.getResourceAsStream(RESOURCE) ?: error("missing $RESOURCE")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
```

Create `PageReport.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One `<video>` as a frame reported it. */
data class PageVideo(
    /** Its http(s) sources (currentSrc, src, `<source>`s), at most 5. */
    val urls: List<String>,
    /** It plays a `blob:` (MSE): the stream behind it is only visible to the request sniffer. */
    val blob: Boolean,
    val durationMs: Long?,
    val live: Boolean,
    val width: Int,
    val height: Int,
    /** On-screen size in CSS px². */
    val area: Long,
    val playing: Boolean,
)

data class PageLdVideo(val url: String?, val durationMs: Long?, val name: String?)

/** A frame's report from page-media.js, checked again here: the page is untrusted. */
data class PageReport(
    val frameUrl: String?,
    val mainFrame: Boolean,
    val title: String?,
    val ogTitle: String?,
    val siteName: String?,
    val ogVideos: List<String>,
    val ldVideos: List<PageLdVideo>,
    val videos: List<PageVideo>,
    val eme: Boolean,
)

object PageReports {
    const val MAX_MESSAGE_CHARS = 64_000
    const val MAX_ITEMS = 20
    private const val MAX_SRCS = 5
    private const val MAX_TEXT = 300
    private const val MAX_DURATION_MS = 86_400_000L
    private const val MAX_SIDE = 16_384
    private const val MAX_AREA = 100_000_000L

    /** [message] as a report, or null when it is not one (too long, not JSON, another version). */
    fun parse(message: String, mainFrame: Boolean): PageReport? {
        if (message.length > MAX_MESSAGE_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
        if (root.number("v") != 1.0) return null
        return PageReport(
            frameUrl = address(root["page"]),
            mainFrame = mainFrame,
            title = root.text("title"),
            ogTitle = root.text("ogTitle"),
            siteName = root.text("site"),
            ogVideos = root.list("og").mapNotNull(::address).distinct().take(MAX_ITEMS),
            ldVideos = root.list("ld").mapNotNull { it as? JsonObject }.take(MAX_ITEMS).map { ld ->
                PageLdVideo(address(ld["url"]), ld.text("duration")?.let(PlaylistSniffer::isoDurationMs)?.coerceAtMost(MAX_DURATION_MS), ld.text("name"))
            },
            videos = root.list("videos").mapNotNull { it as? JsonObject }.take(MAX_ITEMS).map(::video),
            eme = root.flag("eme"),
        )
    }

    private fun video(o: JsonObject): PageVideo {
        val seconds = o.number("duration")
        return PageVideo(
            urls = o.list("srcs").mapNotNull(::address).distinct().take(MAX_SRCS),
            blob = o.flag("blob"),
            durationMs = seconds?.takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).toLong().coerceAtMost(MAX_DURATION_MS) },
            live = seconds == -1.0,
            width = (o.number("w") ?: 0.0).toInt().coerceIn(0, MAX_SIDE),
            height = (o.number("h") ?: 0.0).toInt().coerceIn(0, MAX_SIDE),
            area = (o.number("area") ?: 0.0).toLong().coerceIn(0, MAX_AREA),
            playing = o.flag("playing"),
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.text(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.content?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? = primitive(key)?.takeUnless { it.isString }?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean = primitive(key)?.takeUnless { it.isString }?.booleanOrNull ?: false

    private fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun address(element: JsonElement?): String? {
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val lower = value.lowercase()
        if (value.length > MediaUrlRules.MAX_URL_LENGTH || !(lower.startsWith("https://") || lower.startsWith("http://"))) return null
        return value
    }
}
```

- [ ] **Step 5: Run the Kotlin tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Write the page script's tests (jsdom, in ritme-web)**

Create `ritme-web/src/external/page-media.test.ts`:

```ts
// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The Ritme in-app browser's page script (modules/ritme-media), tested here because this package has jsdom.
const SCRIPT = readFileSync(
  fileURLToPath(
    new URL('../../../modules/ritme-media/src/main/resources/com/example/musicfy/ritme/media/page-media.js', import.meta.url),
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

afterEach(() => {
  vi.useRealTimers();
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
    document.title = 'Judul halaman';
    document.head.innerHTML = [
      '<meta property="og:title" content="Film Pendek">',
      '<meta property="og:site_name" content="Contoh TV">',
      '<meta property="og:video:secure_url" content="https://cdn.example.com/og.mp4">',
      '<meta property="og:video" content="https://cdn.example.com/og.mp4">',
      '<script type="application/ld+json">{"@graph":[{"@type":"VideoObject","contentUrl":"https://cdn.example.com/ld.m3u8","duration":"PT2M","name":"Klip"}]}</script>',
      '<script type="application/ld+json">not json</script>',
    ].join('');
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
});
```

- [ ] **Step 7: Run the script's tests**

Run: `w npx vitest run src/external/page-media.test.ts` then `w npm run typecheck`
Expected: 8 tests pass; typecheck clean.

- [ ] **Step 8: Commit**

```bash
git add modules/ritme-media ritme-web/src/external/page-media.test.ts
git commit -m "feat(ritme-media): page script reporting videos, og:video, JSON-LD and EME, with its parser" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `CandidateCollector` (merge, caps, YouTube/blob/EME, ranking)

**Files:**
- Create: `modules/ritme-media/src/main/kotlin/com/example/musicfy/ritme/media/CandidateCollector.kt`
- Test: `modules/ritme-media/src/test/kotlin/com/example/musicfy/ritme/media/CandidateCollectorTest.kt`

**Interfaces:**
- Consumes: `MediaUrlClassifier`, `Sniffed`, `AdHosts` (Task 2), `PageReport`, `PageVideo`, `PageLdVideo` (Task 6), `YouTubeLinks`, `MediaUrlRules`, `StreamType`.
- Produces:

```kotlin
enum class FoundVia { NETWORK, MEDIA_ELEMENT, PAGE_VIDEO, OPEN_GRAPH, JSON_LD }
data class Candidate(
    val url: String, val streamType: StreamType, val via: Set<FoundVia>, val firstSeenMs: Long, val lastSeenMs: Long,
    val pageDurationMs: Long? = null, val pageLive: Boolean = false, val width: Int = 0, val height: Int = 0,
    val area: Long = 0, val playing: Boolean = false, val name: String? = null,
)
data class PageFindings(
    val pageUrl: String? = null, val candidates: List<Candidate> = emptyList(), val youTubeVideoId: String? = null,
    val streamSeen: Boolean = false, val blobOnly: Boolean = false, val eme: Boolean = false,
    val title: String? = null, val ogTitle: String? = null, val siteName: String? = null,
)
class CandidateCollector(maxCandidates: Int = MAX_CANDIDATES) {
    fun startPage(url: String)
    fun urlChanged(url: String): Boolean
    fun onRequest(url: String, method: String, headers: Map<String, String>, nowMs: Long): Boolean
    fun onReport(report: PageReport, nowMs: Long): Boolean
    fun onProtectedMedia(): Boolean
    fun findings(): PageFindings
    companion object { const val MAX_CANDIDATES = 50; const val MAX_FRAMES = 20 }
}
object CandidateRanking { fun rank(candidates: List<Candidate>, hidden: Set<String> = emptySet()): List<Candidate> }
```

- [ ] **Step 1: Write the failing tests**

Create `CandidateCollectorTest.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateCollectorTest {

    private val page = "https://example.com/film/1"
    private val hls = "https://cdn.example.com/v/master.m3u8"
    private val mp4 = "https://cdn.example.com/v/film.mp4"

    private fun collector() = CandidateCollector().also { it.startPage(page) }

    private fun video(url: String? = null, blob: Boolean = false, durationMs: Long? = null, area: Long = 0, playing: Boolean = false, live: Boolean = false) =
        PageVideo(listOfNotNull(url), blob, durationMs, live, 1280, 720, area, playing)

    private fun report(
        videos: List<PageVideo> = emptyList(),
        og: List<String> = emptyList(),
        ld: List<PageLdVideo> = emptyList(),
        mainFrame: Boolean = true,
        frameUrl: String? = page,
        eme: Boolean = false,
    ) = PageReport(frameUrl, mainFrame, "Film | Example", "Film", "Example TV", og, ld, videos, eme)

    @Test
    fun requestsBecomeCandidatesSegmentsOnlyMarkAStream() {
        val c = collector()
        assertTrue(c.onRequest(hls, "GET", emptyMap(), 1))
        assertTrue(c.onRequest(mp4, "GET", emptyMap(), 2))
        assertTrue(c.onRequest("https://cdn.example.com/v/seg1.ts", "GET", emptyMap(), 3))
        assertFalse(c.onRequest("https://cdn.example.com/v/seg2.ts", "GET", emptyMap(), 4))
        assertFalse(c.onRequest("https://cdn.example.com/logo.png", "GET", emptyMap(), 5))
        assertFalse(c.onRequest("https://pubads.g.doubleclick.net/ad.mp4", "GET", emptyMap(), 6))
        assertFalse(c.onRequest("http://10.0.0.2/v.mp4", "GET", emptyMap(), 7))
        val findings = c.findings()
        assertEquals(setOf(hls, mp4), findings.candidates.map { it.url }.toSet())
        assertTrue(findings.streamSeen)
        assertEquals(StreamType.HLS, findings.candidates.first { it.url == hls }.streamType)
    }

    @Test
    fun theSameVideoSeenTwiceIsOneCandidate() {
        val c = collector()
        c.onRequest("$mp4#t=3", "GET", emptyMap(), 1)
        assertTrue(c.onReport(report(listOf(video(mp4))), 2))
        val single = c.findings().candidates.single()
        assertEquals(mp4, single.url)
        assertEquals(setOf(FoundVia.NETWORK, FoundVia.PAGE_VIDEO), single.via)
        assertEquals(1L, single.firstSeenMs)
        assertEquals(2L, single.lastSeenMs)
    }

    @Test
    fun atMostFiftyPerPage() {
        val c = collector()
        repeat(60) { c.onRequest("https://cdn.example.com/v/$it.mp4", "GET", emptyMap(), it.toLong()) }
        assertEquals(CandidateCollector.MAX_CANDIDATES, c.findings().candidates.size)
    }

    @Test
    fun theLargestPlayingVideoComesFirstThenTheLongestThenTheNewest() {
        val c = collector()
        val small = "https://cdn.example.com/small.mp4"
        val long = "https://cdn.example.com/long.mp4"
        val newest = "https://cdn.example.com/newest.mp4"
        c.onRequest(newest, "GET", emptyMap(), 9)
        c.onReport(report(listOf(video(small, area = 10_000, playing = true), video(long, durationMs = 7_200_000, area = 900_000), video(mp4, area = 300_000, playing = true))), 5)
        assertEquals(listOf(mp4, small, long, newest), c.findings().candidates.map { it.url })
    }

    @Test
    fun aStreamPlayedThroughABlobGetsThatVideosFacts() {
        val c = collector()
        c.onRequest(hls, "GET", emptyMap(), 1)
        c.onReport(report(listOf(video(blob = true, area = 200_000, playing = true, live = true))), 2)
        val candidate = c.findings().candidates.single()
        assertTrue(candidate.playing)
        assertTrue(candidate.pageLive)
        assertEquals(200_000L, candidate.area)
        assertFalse(c.findings().blobOnly)
    }

    @Test
    fun onlyABlobMeansNothingCanBeShared() {
        val c = collector()
        c.onReport(report(listOf(video(blob = true, playing = true))), 1)
        assertTrue(c.findings().blobOnly)
    }

    @Test
    fun youTubePagesAndEmbedsOfferTheirVideo() {
        val c = CandidateCollector()
        c.startPage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", c.findings().youTubeVideoId)
        assertTrue(c.urlChanged("https://m.youtube.com/"))
        assertNull(c.findings().youTubeVideoId)
        c.startPage(page)
        c.onReport(report(og = listOf("https://www.youtube.com/embed/jfKfPfyJRdk")), 1)
        assertEquals("jfKfPfyJRdk", c.findings().youTubeVideoId)
        c.startPage(page)
        c.onReport(report(mainFrame = false, frameUrl = "https://www.youtube-nocookie.com/embed/nPSvUczm8M0"), 1)
        assertEquals("nPSvUczm8M0", c.findings().youTubeVideoId)
        assertFalse(c.findings().blobOnly)
    }

    @Test
    fun openGraphAndJsonLdAddCandidatesWithLengthsAndNames() {
        val c = collector()
        c.onReport(report(og = listOf("https://cdn.example.com/og.mp4"), ld = listOf(PageLdVideo("https://cdn.example.com/ld.m3u8", 120_000, "Klip"))), 1)
        val byUrl = c.findings().candidates.associateBy { it.url }
        assertEquals(setOf(FoundVia.OPEN_GRAPH), byUrl.getValue("https://cdn.example.com/og.mp4").via)
        assertEquals(120_000L, byUrl.getValue("https://cdn.example.com/ld.m3u8").pageDurationMs)
        assertEquals("Klip", byUrl.getValue("https://cdn.example.com/ld.m3u8").name)
    }

    @Test
    fun theMainFrameNamesThePageAndDrmMarksIt() {
        val c = collector()
        c.onReport(report(mainFrame = false, frameUrl = "https://ads.example.net/frame").copy(title = "Ad"), 1)
        assertNull(c.findings().title)
        c.onReport(report(), 2)
        assertEquals("Film | Example", c.findings().title)
        assertEquals("Film", c.findings().ogTitle)
        assertEquals("Example TV", c.findings().siteName)
        assertFalse(c.findings().eme)
        assertTrue(c.onProtectedMedia())
        assertFalse(c.onProtectedMedia())
        assertTrue(c.findings().eme)
    }

    @Test
    fun aNewPageForgetsTheOldOne() {
        val c = collector()
        c.onRequest(hls, "GET", emptyMap(), 1)
        c.onProtectedMedia()
        c.startPage("https://example.com/other")
        assertEquals(PageFindings(pageUrl = "https://example.com/other"), c.findings())
    }

    @Test
    fun hiddenUrlsLeaveTheRanking() {
        val a = Candidate(hls, StreamType.HLS, setOf(FoundVia.NETWORK), 1, 1)
        val b = Candidate("https://cdn.example.com/v/720p/index.m3u8", StreamType.HLS, setOf(FoundVia.NETWORK), 2, 2)
        assertEquals(listOf(a), CandidateRanking.rank(listOf(a, b), hidden = setOf(b.url)))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :modules:ritme-media:test --tests 'com.example.musicfy.ritme.media.CandidateCollectorTest'`
Expected: compilation FAILS (`Unresolved reference: CandidateCollector`, `Candidate`, `PageFindings`, `FoundVia`, `CandidateRanking`).

- [ ] **Step 3: Write `CandidateCollector`**

Create `CandidateCollector.kt`:

```kotlin
package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import com.example.musicfy.ritme.protocol.YouTubeLinks

enum class FoundVia { NETWORK, MEDIA_ELEMENT, PAGE_VIDEO, OPEN_GRAPH, JSON_LD }

/** A video or stream found on the page, with what the page says about it. */
data class Candidate(
    val url: String,
    val streamType: StreamType,
    val via: Set<FoundVia>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** A finite length from the page's `<video>` or JSON-LD; null when unknown. */
    val pageDurationMs: Long? = null,
    val pageLive: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** On-screen size (CSS px²) of the `<video>` showing it; 0 when none. */
    val area: Long = 0,
    val playing: Boolean = false,
    /** A JSON-LD VideoObject's name. */
    val name: String? = null,
)

/** Everything found on the current page, ranked (spec 4.7 steps 1–3). */
data class PageFindings(
    val pageUrl: String? = null,
    val candidates: List<Candidate> = emptyList(),
    /** A YouTube video on or behind this page: offered as YOUTUBE_VIDEO, never as a URL entry. */
    val youTubeVideoId: String? = null,
    /** Segments were requested: the page plays a stream. */
    val streamSeen: Boolean = false,
    /** Only `blob:` videos and nothing else: "Video di halaman ini tidak bisa dibagikan". */
    val blobOnly: Boolean = false,
    /** The page asked for DRM (page script or WebView's protected-media request). */
    val eme: Boolean = false,
    val title: String? = null,
    val ogTitle: String? = null,
    val siteName: String? = null,
)

/**
 * What the browser found on the current page, from requests ([onRequest], any thread) and page
 * reports ([onReport]). Page data is untrusted: at most [maxCandidates] per page and [MAX_FRAMES]
 * frames, every url checked again (MediaUrlRules, ad hosts, YouTube's hosts). Thread-safe.
 */
class CandidateCollector(private val maxCandidates: Int = MAX_CANDIDATES) {

    private class Found(
        val url: String,
        var streamType: StreamType,
        val via: MutableSet<FoundVia>,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
    )

    private var pageUrl: String? = null
    private var pageYouTubeId: String? = null
    private var embeddedYouTubeId: String? = null
    private var streamSeen = false
    private var protectedMedia = false
    private val found = LinkedHashMap<String, Found>()
    private val reports = LinkedHashMap<String, PageReport>()

    /** A new page (a main-frame load): everything found so far belonged to the old one. */
    @Synchronized
    fun startPage(url: String) {
        pageUrl = url
        pageYouTubeId = youTubeIdOf(url)
        embeddedYouTubeId = null
        streamSeen = false
        protectedMedia = false
        found.clear()
        reports.clear()
    }

    /** Same document, new address (history.pushState): YouTube moves between videos like this. */
    @Synchronized
    fun urlChanged(url: String): Boolean {
        val id = youTubeIdOf(url)
        val changed = url != pageUrl || id != pageYouTubeId
        pageUrl = url
        pageYouTubeId = id
        return changed
    }

    /** True when the request added or changed something. */
    @Synchronized
    fun onRequest(url: String, method: String, headers: Map<String, String>, nowMs: Long): Boolean =
        when (val sniffed = MediaUrlClassifier.classify(url, method, headers)) {
            is Sniffed.Media -> add(sniffed.url, sniffed.streamType, if (sniffed.mediaRequest) FoundVia.MEDIA_ELEMENT else FoundVia.NETWORK, nowMs)
            Sniffed.Segment -> if (streamSeen) false else {
                streamSeen = true
                true
            }
            Sniffed.Ignored -> false
        }

    @Synchronized
    fun onReport(report: PageReport, nowMs: Long): Boolean {
        val key = report.frameUrl ?: if (report.mainFrame) MAIN_FRAME else OTHER_FRAME
        if (key !in reports && reports.size >= MAX_FRAMES) return false
        var changed = reports.put(key, report) != report
        for (video in report.videos) for (url in video.urls) changed = add(url, typeOf(url), FoundVia.PAGE_VIDEO, nowMs) || changed
        for (url in report.ogVideos) {
            val youTube = youTubeIdOf(url)
            if (youTube == null) {
                changed = add(url, typeOf(url), FoundVia.OPEN_GRAPH, nowMs) || changed
            } else if (embeddedYouTubeId == null) {
                embeddedYouTubeId = youTube
                changed = true
            }
        }
        for (ld in report.ldVideos) ld.url?.let { changed = add(it, typeOf(it), FoundVia.JSON_LD, nowMs) || changed }
        // An embedded YouTube player's own frame reports from youtube.com / youtube-nocookie.com.
        if (!report.mainFrame && embeddedYouTubeId == null) {
            report.frameUrl?.let(::youTubeIdOf)?.let {
                embeddedYouTubeId = it
                changed = true
            }
        }
        return changed
    }

    /** WebView was asked for protected media (DRM) by this page. */
    @Synchronized
    fun onProtectedMedia(): Boolean {
        if (protectedMedia) return false
        protectedMedia = true
        return true
    }

    @Synchronized
    fun findings(): PageFindings {
        val videos = reports.values.flatMap { it.videos }
        val streamVideo = videos.filter { it.blob && it.urls.isEmpty() }.maxWithOrNull(VIDEO_ORDER)
        val linked = reports.values.flatMap { it.ldVideos }.filter { it.url != null }
        val candidates = found.values.map { f ->
            // A stream the page plays through MSE shows as a blob: video; the stream is what was requested.
            val shown = videos.filter { f.url in it.urls }.maxWithOrNull(VIDEO_ORDER)
                ?: streamVideo?.takeIf { f.streamType != StreamType.FILE && FoundVia.NETWORK in f.via }
            val ld = linked.firstOrNull { it.url == f.url }
            Candidate(
                url = f.url,
                streamType = f.streamType,
                via = f.via.toSet(),
                firstSeenMs = f.firstSeenMs,
                lastSeenMs = f.lastSeenMs,
                pageDurationMs = shown?.durationMs ?: ld?.durationMs,
                pageLive = shown?.live == true,
                width = shown?.width ?: 0,
                height = shown?.height ?: 0,
                area = shown?.area ?: 0L,
                playing = shown?.playing == true,
                name = ld?.name,
            )
        }
        val main = reports.values.firstOrNull { it.mainFrame }
        val youTube = pageYouTubeId ?: embeddedYouTubeId
        val ranked = CandidateRanking.rank(candidates)
        return PageFindings(
            pageUrl = pageUrl,
            candidates = ranked,
            youTubeVideoId = youTube,
            streamSeen = streamSeen,
            blobOnly = ranked.isEmpty() && youTube == null && videos.any { it.blob },
            eme = protectedMedia || reports.values.any { it.eme },
            title = main?.title,
            ogTitle = main?.ogTitle,
            siteName = main?.siteName,
        )
    }

    private fun typeOf(url: String): StreamType = MediaUrlClassifier.streamTypeOf(url) ?: StreamType.UNKNOWN

    private fun add(rawUrl: String, type: StreamType, via: FoundVia, nowMs: Long): Boolean {
        val url = rawUrl.substringBefore('#')
        val host = MediaUrlClassifier.hostOf(url) ?: return false
        if (MediaUrlClassifier.isYouTubeHost(host) || AdHosts.isAd(host) || MediaUrlRules.check(url) != null) return false
        val existing = found[url]
        if (existing == null) {
            if (found.size >= maxCandidates) return false
            found[url] = Found(url, type, mutableSetOf(via), nowMs, nowMs)
            return true
        }
        existing.lastSeenMs = nowMs
        var changed = existing.via.add(via)
        if (existing.streamType == StreamType.UNKNOWN && type != StreamType.UNKNOWN) {
            existing.streamType = type
            changed = true
        }
        return changed
    }

    companion object {
        const val MAX_CANDIDATES = 50
        const val MAX_FRAMES = 20
        private const val MAIN_FRAME = "main"
        private const val OTHER_FRAME = "frame"
        private val VIDEO_ORDER = compareBy<PageVideo>({ it.playing }, { it.area })

        private fun youTubeIdOf(url: String): String? = YouTubeLinks.parse(url)?.takeUnless { it.music }?.videoId
    }
}

object CandidateRanking {
    /**
     * Spec 4.7 step 3: tied to the largest playing `<video>` first, then the largest shown one, then
     * the longest, then the newest; [hidden] (a checked master's variants, non-media) left out.
     */
    fun rank(candidates: List<Candidate>, hidden: Set<String> = emptySet()): List<Candidate> =
        candidates.filter { it.url !in hidden }.sortedWith(
            compareByDescending<Candidate> { if (it.playing) it.area + 1 else 0L }
                .thenByDescending { it.area }
                .thenByDescending { it.pageDurationMs ?: 0L }
                .thenByDescending { it.lastSeenMs },
        )
}
```

Check against `theLargestPlayingVideoComesFirstThenTheLongestThenTheNewest`: `mp4` (playing, 300 000) > `small` (playing, 10 000) > `long` (not playing, area 900 000) > `newest` (no video). The second key (area) puts `long` before `newest` too.

- [ ] **Step 4: Run the tests**

Run: `g :modules:ritme-media:test`
Expected: BUILD SUCCESSFUL, all `ritme-media` tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/ritme-media
git commit -m "feat(ritme-media): candidate collector with caps, YouTube offers, blob and DRM signals, ranking" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
# Phase B: Server and web

### Task 8: The server accepts and keeps `streamType`/`referrer`

**Files:**
- Modify: `ritme-server/src/main/kotlin/com/example/musicfy/ritme/server/RoomLogic.kt:320-343`
- Modify: `ritme-server/README.md` (Protocol notes "Entries" bullet, Privacy "Exposure note")
- Test: `ritme-server/src/test/kotlin/com/example/musicfy/ritme/server/RoomLogicTest.kt`

**Interfaces:**
- Consumes: `StreamType`, `NewEntry.streamType/referrer`, `QueueEntry` 11-argument constructor, `MediaUrlRules.isAcceptableReferrer` (Task 1).
- Produces: `RoomLogic` accepts `URL` entries with any `streamType` (lenient) and a `null` or origin-only `referrer`; refuses YouTube kinds that carry either; copies both into the `QueueEntry`.

- [ ] **Step 1: Write the failing tests**

In `RoomLogicTest.kt` add `import com.example.musicfy.ritme.protocol.StreamType` and these tests (inside the class, after `entriesCarryOnlyTheFieldsTheirKindNeeds`):

```kotlin
    @Test
    fun urlEntriesKeepTheirStreamTypeAndAnOriginReferrer() {
        fun add(e: NewEntry) = apply(playingA, HOST, Command.Add(listOf(e)))
        val url = NewEntry("", "Film", "example.com", null, 60_000, MediaKind.URL, "https://cdn.example/v/manifest?token=a")
        val added = ok(add(url.copy(streamType = StreamType.HLS, referrer = "https://example.com/"))).queue.last()
        assertEquals(StreamType.HLS, added.streamType)
        assertEquals("https://example.com/", added.referrer)
        // a type from a newer client decodes to UNKNOWN: kept, never refused
        assertEquals(StreamType.UNKNOWN, ok(add(url.copy(streamType = StreamType.UNKNOWN))).queue.last().streamType)
        listOf(
            "https://example.com",
            "https://example.com/watch?v=1",
            "http://192.168.1.1/",
            "https://user:pw@example.com/",
            "https://example.com/" + "a".repeat(200),
        ).forEach { assertEquals("referrer $it", ErrorCode.INVALID, rejected(add(url.copy(referrer = it)))) }
    }

    @Test
    fun youTubeEntriesCarryNoStreamTypeOrReferrer() {
        fun add(e: NewEntry) = apply(playingA, HOST, Command.Add(listOf(e)))
        assertEquals(ErrorCode.INVALID, rejected(add(newEntry().copy(streamType = StreamType.HLS))))
        assertEquals(ErrorCode.INVALID, rejected(add(newEntry().copy(kind = MediaKind.YOUTUBE_VIDEO, referrer = "https://example.com/"))))
        val plain = ok(add(newEntry())).queue.last()
        assertNull(plain.streamType)
        assertNull(plain.referrer)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `g -p ritme-server test --tests 'com.example.musicfy.ritme.server.RoomLogicTest'`
Expected: FAIL: `urlEntriesKeepTheirStreamTypeAndAnOriginReferrer` (streamType is null in the queue) and `youTubeEntriesCarryNoStreamTypeOrReferrer` (accepted).

- [ ] **Step 3: Validate and copy the fields**

In `RoomLogic.kt` replace `NewEntry.isValid()`'s `val source = when (kind) { … }` with:

```kotlin
        val source = when (kind) {
            MediaKind.YOUTUBE_MUSIC, MediaKind.YOUTUBE_VIDEO ->
                url == null && streamType == null && referrer == null && VIDEO_ID.matches(videoId)
            // streamType is lenient (an unknown name is UNKNOWN, still accepted); a referrer is a bare origin.
            MediaKind.URL -> videoId.isEmpty() && url?.let(MediaUrlRules::isAcceptableMediaUrl) == true &&
                referrer?.let(MediaUrlRules::isAcceptableReferrer) ?: true
            MediaKind.UNKNOWN -> false
        }
```

and replace `toQueueEntry` with:

```kotlin
    private fun NewEntry.toQueueEntry(entryId: String, addedBy: String) =
        QueueEntry(entryId, videoId, cleanText(title), cleanText(artist), thumbnailUrl, durationMs, addedBy, kind, url?.trim(), streamType, referrer)
```

Also extend the KDoc above `isValid` with: `URL entries may say how they are served (streamType) and carry the origin their host checks (referrer, MediaUrlRules.checkReferrer).`

- [ ] **Step 4: Document it**

In `ritme-server/README.md`, in the "Protocol notes" bullet that starts with "- Entries: YouTube kinds carry a `videoId`…", after its last sentence ("`durationMs` is 0 (live) to 24 h.") add:

```markdown
  `URL` entries may also carry `streamType` (`HLS`, `DASH`, `FILE`, `UNKNOWN`; a name from a newer
  client decodes to `UNKNOWN` and is kept) and `referrer`: null or a bare origin
  `http(s)://host[:port]/` (at most 200 chars, same host rules, `MediaUrlRules.checkReferrer`).
  YouTube kinds carry neither.
```

In "## Privacy", at the end of the "Exposure note" paragraph (after "…at their own router or LAN."), add:

```markdown
A `URL` entry's `referrer` (an origin only, never a path, query or cookie) tells the room which
site the video came from; Android members send it as the `Referer` when they play the entry. The
Android in-app browser that finds such videos checks them from the adding device only (never from
the server), never unlocks DRM, and refuses videos that need a sign-in.
```

- [ ] **Step 5: Run the server tests**

Run: `g -p ritme-server test`
Expected: BUILD SUCCESSFUL (all server tests, including the existing `RoomLogicTest`/`VideoTest`/`SearchTest`).

- [ ] **Step 6: Commit**

```bash
git add ritme-server
git commit -m "feat(ritme-server): accept streamType and origin-only referrer on URL entries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Web: the referrer rule twin and `streamType` in `playable.ts`

**Files:**
- Modify: `ritme-web/src/net/media-url-rules.ts` (append), `ritme-web/src/net/media-url-rules.test.ts`
- Modify: `ritme-web/src/player/playable.ts`, `ritme-web/src/player/playable.test.ts`

**Interfaces:**
- Consumes: `fixtures/rules/referrers.json`, `QueueEntry.streamType` (Task 1).
- Produces: `MAX_REFERRER_LENGTH`, `MediaUrlRejection` gains `'NOT_ORIGIN'`, `checkReferrer(referrer: string): MediaUrlRejection | null`; `playability()` honours `streamType`.

- [ ] **Step 1: Write the failing tests**

In `media-url-rules.test.ts` change the import to `import { MAX_MEDIA_URL_LENGTH, checkMediaUrl, checkReferrer } from './media-url-rules';` and append:

```ts
const REFERRERS = fileURLToPath(new URL('../../../modules/ritme-protocol/fixtures/rules/referrers.json', import.meta.url));
const referrerCases = (
  JSON.parse(readFileSync(REFERRERS, 'utf8')) as { cases: { referrer: string; reject: string | null; padTo?: number }[] }
).cases;

describe('checkReferrer (same fixture as ReferrerRulesTest.kt)', () => {
  it('has the shared cases', () => {
    expect(referrerCases).toHaveLength(20);
  });

  for (const c of referrerCases) {
    const value = c.padTo === undefined ? c.referrer : c.referrer.padEnd(c.padTo, 'a');
    const label = c.padTo === undefined ? JSON.stringify(c.referrer) : `${JSON.stringify(c.referrer)} padded to ${c.padTo}`;
    it(`${label} -> ${c.reject ?? 'accepted'}`, () => {
      expect(checkReferrer(value)).toBe(c.reject);
    });
  }
});
```

In `playable.test.ts`, inside `describe('playability', …)`, add:

```ts
  it('follows streamType: HLS without an extension uses hls.js, DASH and .mpd are unsupported, FILE is never HLS', () => {
    const manifest = entry('a', { kind: 'URL', videoId: '', url: 'https://cdn.example/v/manifest?token=1', streamType: 'HLS' });
    expect(playability(manifest, true)).toEqual({
      playable: true,
      source: { kind: 'video', url: 'https://cdn.example/v/manifest?token=1', hls: true },
      large: true,
    });
    expect(playability({ ...manifest, streamType: 'DASH' }, true)).toEqual({ playable: false, reason: 'unsupported' });
    expect(playability(entry('a', { kind: 'URL', videoId: '', url: 'https://cdn.example/v/a.mpd' }), true)).toEqual({
      playable: false,
      reason: 'unsupported',
    });
    const odd = entry('a', { kind: 'URL', videoId: '', url: 'https://cdn.example/odd.m3u8', streamType: 'FILE' });
    expect(playability(odd, true)).toMatchObject({ source: { hls: false } });
    expect(playability({ ...manifest, streamType: 'UNKNOWN' }, true)).toMatchObject({ source: { hls: false } });
    expect(playability({ ...odd, streamType: 'UNKNOWN' }, true)).toMatchObject({ source: { hls: true } });
  });
```

- [ ] **Step 2: Run them to see them fail**

Run: `w npx vitest run src/net/media-url-rules.test.ts src/player/playable.test.ts`
Expected: FAIL (`checkReferrer` is not exported; the `streamType` cases fail).

- [ ] **Step 3: Write the twin**

In `media-url-rules.ts`, add `'NOT_ORIGIN'` to `MediaUrlRejection` (after `'LOCAL_HOST'`):

```ts
  | 'LOCAL_HOST'
  /** A referrer with a path, a query or no trailing slash: only `scheme://host[:port]/`. */
  | 'NOT_ORIGIN';
```

(remove the `;` that ended the `'LOCAL_HOST'` line), add after `export const MAX_MEDIA_URL_LENGTH = 2048;`:

```ts
/** Kotlin MediaUrlRules.MAX_REFERRER_LENGTH. */
export const MAX_REFERRER_LENGTH = 200;
```

and append at the end of the file:

```ts
/**
 * Kotlin MediaUrlRules.checkReferrer: why a URL entry's referrer is refused, or null for a bare
 * http(s) origin with a trailing slash (`https://host[:port]/`), at most 200 chars, under the same
 * host rules as checkMediaUrl. The web client never sends one; this keeps the twins in step.
 */
export function checkReferrer(referrer: string): MediaUrlRejection | null {
  if (referrer.length > MAX_REFERRER_LENGTH) return 'TOO_LONG';
  const rejection = checkMediaUrl(referrer);
  if (rejection !== null) return rejection;
  const afterScheme = referrer.slice(referrer.indexOf('://') + 3);
  const end = afterScheme.search(/[/?]/);
  const authority = end < 0 ? afterScheme : afterScheme.slice(0, end);
  return afterScheme.slice(authority.length) === '/' ? null : 'NOT_ORIGIN';
}
```

- [ ] **Step 4: Honour `streamType`**

Replace `ritme-web/src/player/playable.ts` with:

```ts
import type { QueueEntry } from '../protocol';
import type { PlayableSource } from './types';

export type Playability =
  | { playable: true; source: PlayableSource; large: boolean }
  | { playable: false; reason: 'unsupported' | 'insecure' };

const HLS_PATH = /\.m3u8$/i;
const DASH_PATH = /\.mpd$/i;

/**
 * How (and whether) this browser can play an entry. YouTube kinds use the IFrame player (video
 * kinds shown large); URL entries use <video>, with hls.js for HLS. A URL entry's streamType (#3)
 * says what its path may not (`/manifest?token=`); without one, or UNKNOWN, the extension decides.
 * DASH is never playable here (D11). UNKNOWN kinds from a newer protocol are unsupported, and
 * http:// media cannot play on an https page (mixed content).
 */
export function playability(entry: QueueEntry, pageIsHttps: boolean): Playability {
  switch (entry.kind) {
    case 'YOUTUBE_MUSIC':
    case 'YOUTUBE_VIDEO':
      if (!entry.videoId) return { playable: false, reason: 'unsupported' };
      return { playable: true, source: { kind: 'youtube', videoId: entry.videoId }, large: entry.kind === 'YOUTUBE_VIDEO' };
    case 'URL': {
      let url: URL;
      try {
        url = new URL(entry.url ?? '');
      } catch {
        return { playable: false, reason: 'unsupported' };
      }
      if (url.protocol !== 'https:' && url.protocol !== 'http:') return { playable: false, reason: 'unsupported' };
      if (pageIsHttps && url.protocol === 'http:') return { playable: false, reason: 'insecure' };
      const type = entry.streamType === 'UNKNOWN' ? null : entry.streamType;
      if (type === 'DASH' || (type === null && DASH_PATH.test(url.pathname))) return { playable: false, reason: 'unsupported' };
      const hls = type === 'HLS' || (type === null && HLS_PATH.test(url.pathname));
      return { playable: true, source: { kind: 'video', url: url.href, hls }, large: true };
    }
    default:
      return { playable: false, reason: 'unsupported' };
  }
}
```

- [ ] **Step 5: Run the web checks**

Run: `w npm test && w npm run typecheck && w npm run build && w npm run size`
Expected: all tests pass, typecheck clean, build OK, size budget holds (no new dependency, a few lines of code).

- [ ] **Step 6: Commit**

```bash
git add ritme-web/src/net/media-url-rules.ts ritme-web/src/net/media-url-rules.test.ts ritme-web/src/player/playable.ts ritme-web/src/player/playable.test.ts
git commit -m "feat(ritme-web): play URL entries by streamType, referrer rule twin" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

# Phase C: Android probe and model

### Task 10: Android URL entries carry and play `streamType`/`referrer`

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt` (`toUrlEntry`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModel.kt` (`entryOf`)
- Replace: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` (`ritmeUrlSourceFactory` block, `createMediaSourceFactory`)
- Modify: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeItemsTest.kt`

**Interfaces:**
- Consumes: `StreamType`, `QueueEntry.streamType/referrer`, `MediaUrlRules.isAcceptableReferrer` (Task 1).
- Produces: `RitmeEntries.toUrlEntry(url: String, title: String, domain: String, durationMs: Long = 0, streamType: StreamType? = null, referrer: String? = null): NewEntry?` (UNKNOWN → null, a non-origin referrer → null); `RitmeUrlSpec(mediaId, url, mimeType, live, referrer: String? = null)`; `RitmeItems.EXTRA_REFERRER = "ritme.referrer"`; `RitmeItems.referrerOf(item: MediaItem): String?`; test builder `entry(…, streamType: StreamType? = null, referrer: String? = null)`.

- [ ] **Step 1: Extend the test builder**

In `RitmeBuilders.kt`, add `import com.example.musicfy.ritme.protocol.StreamType`, and replace `entry(...)` with:

```kotlin
fun entry(
    id: String,
    addedBy: String = "m1",
    durationMs: Long = 200_000,
    kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    videoId: String = "vid$id",
    url: String? = null,
    streamType: StreamType? = null,
    referrer: String? = null,
) = QueueEntry(
    entryId = id,
    videoId = videoId,
    title = "Song $id",
    artist = "Artist $id",
    thumbnailUrl = null,
    durationMs = durationMs,
    addedBy = addedBy,
    kind = kind,
    url = url,
    streamType = streamType,
    referrer = referrer,
)
```

- [ ] **Step 2: Write the failing tests**

In `RitmeEntriesTest.kt` add `import com.example.musicfy.ritme.protocol.StreamType` and:

```kotlin
    @Test
    fun aUrlEntryCarriesItsStreamTypeAndOnlyAnOriginReferrer() {
        val hls = RitmeEntries.toUrlEntry("https://cdn.example.com/v/manifest?token=a", "Film", "example.com", 0, StreamType.HLS, "https://example.com/")!!
        assertEquals(StreamType.HLS, hls.streamType)
        assertEquals("https://example.com/", hls.referrer)
        val file = { referrer: String? -> RitmeEntries.toUrlEntry("https://cdn.example.com/v.mp4", "Film", "example.com", 1_000, StreamType.FILE, referrer)!! }
        assertNull(file("https://example.com/watch/1").referrer)
        assertNull(file("http://192.168.1.1/").referrer)
        assertNull(RitmeEntries.toUrlEntry("https://cdn.example.com/v.mp4", "Film", "example.com", 1_000, StreamType.UNKNOWN)!!.streamType)
        assertNull(RitmeEntries.toUrlEntry("https://cdn.example.com/v.mp4", "Film", "example.com", 1_000)!!.streamType)
    }
```

In `RitmeItemsTest.kt` add `import com.example.musicfy.ritme.protocol.StreamType` and:

```kotlin
    @Test
    fun theStreamTypeSaysHowALinkWithoutAnExtensionPlays() {
        fun typed(type: StreamType?) = RitmeItems.urlSpec(link("u", "https://cdn.example.com/v/manifest?token=1").copy(streamType = type))!!
        assertEquals(MimeTypes.APPLICATION_M3U8, typed(StreamType.HLS).mimeType)
        assertEquals(MimeTypes.APPLICATION_MPD, typed(StreamType.DASH).mimeType)
        assertNull(typed(StreamType.FILE).mimeType)
        assertNull(typed(StreamType.UNKNOWN).mimeType)
        assertNull(typed(null).mimeType)
        // FILE wins over a misleading extension; UNKNOWN falls back to it
        val odd = link("o", "https://cdn.example.com/a.m3u8")
        assertNull(RitmeItems.urlSpec(odd.copy(streamType = StreamType.FILE))!!.mimeType)
        assertEquals(MimeTypes.APPLICATION_M3U8, RitmeItems.urlSpec(odd.copy(streamType = StreamType.UNKNOWN))!!.mimeType)
    }

    @Test
    fun onlyAnOriginReferrerIsKept() {
        fun referrer(value: String?) = RitmeItems.urlSpec(link("u", "https://cdn.example.com/a.mp4", 60_000).copy(referrer = value))!!.referrer
        assertEquals("https://example.com/", referrer("https://example.com/"))
        assertNull(referrer("https://example.com/watch/1"))
        assertNull(referrer("http://10.0.0.1/"))
        assertNull(referrer(null))
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.entries.RitmeEntriesTest' --tests 'com.example.musicfy.ritme.playback.RitmeItemsTest'`
Expected: compilation FAILS (`toUrlEntry` has no `streamType`/`referrer` parameters; `RitmeUrlSpec` has no `referrer`).

- [ ] **Step 4: `toUrlEntry` takes the new fields**

In `RitmeEntries.kt` add `import com.example.musicfy.ritme.protocol.StreamType` and replace `toUrlEntry` (KDoc included) with:

```kotlin
    /**
     * A URL entry (spec 4.6, 4.7): artist = the site ([domain], or the page's og:site_name), no
     * picture (spec 4.7), the title falls back to [domain]; null when MediaUrlRules refuses the url.
     * #3's optional fields: [streamType] (UNKNOWN is sent as null) and [referrer] (kept only when it
     * is a bare origin, MediaUrlRules.checkReferrer). A duration of 0 means live or unknown.
     */
    fun toUrlEntry(
        url: String,
        title: String,
        domain: String,
        durationMs: Long = 0,
        streamType: StreamType? = null,
        referrer: String? = null,
    ): NewEntry? {
        if (!MediaUrlRules.isAcceptableMediaUrl(url)) return null
        val cleanTitle = truncate(title.trim()).takeIf(::hasVisibleText)
            ?: truncate(domain.trim()).takeIf(::hasVisibleText)
            ?: return null
        return NewEntry(
            videoId = "",
            title = cleanTitle,
            artist = truncate(domain.trim()),
            thumbnailUrl = null,
            durationMs = durationMs.coerceIn(0, MAX_DURATION_MS),
            kind = MediaKind.URL,
            url = url,
            streamType = streamType?.takeUnless { it == StreamType.UNKNOWN },
            referrer = referrer?.takeIf(MediaUrlRules::isAcceptableReferrer),
        )
    }
```

In `RitmeAddVideoModel.kt`, in `entryOf`, replace `return RitmeEntries.toUrlEntry(url, title, entry.artist, entry.durationMs)` with:

```kotlin
            return RitmeEntries.toUrlEntry(url, title, entry.artist, entry.durationMs, entry.streamType, entry.referrer)
```

- [ ] **Step 5: Replace `RitmeItems.kt`**

```kotlin
package com.example.musicfy.ritme.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.musicfy.extensions.toMediaItem
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.StreamType
import com.example.musicfy.ritme.protocol.isLive

/** How a URL entry plays, worked out without Android types (unit-tested). [referrer]: the Referer its host needs (#3). */
data class RitmeUrlSpec(
    val mediaId: String,
    val url: String,
    val mimeType: String?,
    val live: Boolean,
    val referrer: String? = null,
)

/** The player item for a room entry (spec 4.4): YouTube kinds as songs, URL entries as their own stream. */
object RitmeItems {
    /** Every device keeps the same distance behind the live edge, so they stay a few seconds apart at most. */
    const val LIVE_TARGET_OFFSET_MS = 10_000L
    const val LIVE_MIN_SPEED = 0.97f
    const val LIVE_MAX_SPEED = 1.03f

    /** Used when the system WebView's user agent cannot be read (no WebView installed). */
    const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** RequestMetadata extra holding a URL item's referrer origin; MusicService sends it as the Referer. */
    const val EXTRA_REFERRER = "ritme.referrer"

    /**
     * Whether the library's facts about a song (its isVideo flag) may be updated from the player item
     * [mediaId]. Never in a room: a room item's video type comes from the room entry's kind
     * (YOUTUBE_MUSIC / YOUTUBE_VIDEO), which says nothing about the song saved in the library; and a
     * URL item is not a library song at all.
     */
    fun syncsLibraryFlags(mediaId: String?, roomActive: Boolean): Boolean =
        !roomActive && !RitmeEntries.isUrlMediaId(mediaId)

    fun urlSpec(entry: QueueEntry): RitmeUrlSpec? {
        if (entry.kind != MediaKind.URL || !RitmeEntries.isPlayable(entry)) return null
        val url = entry.url ?: return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        // streamType (#3) says what a path like /manifest?token= does not; UNKNOWN or none: the extension.
        val mimeType = when (entry.streamType) {
            StreamType.HLS -> MimeTypes.APPLICATION_M3U8
            StreamType.DASH -> MimeTypes.APPLICATION_MPD
            StreamType.FILE -> null
            StreamType.UNKNOWN, null -> when {
                path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                // a plain file: the extractors find out what it is
                else -> null
            }
        }
        // The room is untrusted: only a bare origin is ever sent as a Referer.
        val referrer = entry.referrer?.takeIf(MediaUrlRules::isAcceptableReferrer)
        return RitmeUrlSpec(RitmeEntries.urlMediaId(entry.entryId), url, mimeType, entry.isLive, referrer)
    }

    fun mediaItemFor(entry: QueueEntry): MediaItem {
        val metadata = RitmeEntries.toMediaMetadata(entry)
        val spec = urlSpec(entry) ?: return metadata.toMediaItem()
        val builder = MediaItem.Builder()
            .setMediaId(spec.mediaId)
            .setUri(spec.url.toUri())
            .setMimeType(spec.mimeType)
            // the UI reads its title and "artist" (the site) from the tag, like any song
            .setTag(metadata)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setDisplayTitle(metadata.title)
                    .setArtist(entry.artist)
                    .setSubtitle(entry.artist)
                    .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
        spec.referrer?.let { referrer ->
            builder.setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(Bundle().apply { putString(EXTRA_REFERRER, referrer) })
                    .build(),
            )
        }
        if (spec.live) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .build(),
            )
        }
        return builder.build()
    }

    /** The Referer a room URL item asks for, checked again, or null. */
    fun referrerOf(item: MediaItem): String? =
        item.requestMetadata.extras?.getString(EXTRA_REFERRER)?.takeIf(MediaUrlRules::isAcceptableReferrer)
}
```

- [ ] **Step 6: `MusicService` sends the Referer for that item only**

In `MusicService.kt` replace the whole `private val ritmeUrlSourceFactory by lazy { … }` block (the one that starts the user-agent coroutine and builds `DefaultMediaSourceFactory(OkHttpDataSource.Factory(…RitmeUrlHttp.client { ritmeUserAgent }))`) with:

```kotlin
    private val ritmeUrlDataSourceFactory by lazy {
        // Reading the WebView's user agent can load WebView, which must not hold up the player's
        // thread: requests send the fallback until it's ready.
        scope.launch(Dispatchers.IO) {
            runCatching {
                com.example.musicfy.ui.screens.settings.importsync.chromeUserAgent(android.webkit.WebSettings.getDefaultUserAgent(this@MusicService))
            }.onSuccess { ritmeUserAgent = it }
        }
        OkHttpDataSource.Factory(com.example.musicfy.ritme.playback.RitmeUrlHttp.client { ritmeUserAgent })
    }

    private val ritmeUrlSourceFactory by lazy {
        DefaultMediaSourceFactory(ritmeUrlDataSourceFactory)
            .setLoadErrorHandlingPolicy(com.example.musicfy.ritme.playback.RitmeUrlHttp.loadErrorPolicy)
    }

    /**
     * A room URL item's source. An entry that carries a referrer (a host that checks it, spec D8)
     * sends that origin as the Referer on every request of this item only; nothing else changes and
     * nothing is shared with other items.
     */
    private fun ritmeUrlMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
        val referrer = com.example.musicfy.ritme.playback.RitmeItems.referrerOf(mediaItem)
            ?: return ritmeUrlSourceFactory.createMediaSource(mediaItem)
        val withReferer = androidx.media3.datasource.ResolvingDataSource.Factory(ritmeUrlDataSourceFactory) { spec ->
            spec.withAdditionalHeaders(mapOf("Referer" to referrer))
        }
        return DefaultMediaSourceFactory(withReferer)
            .setLoadErrorHandlingPolicy(com.example.musicfy.ritme.playback.RitmeUrlHttp.loadErrorPolicy)
            .createMediaSource(mediaItem)
    }
```

and in `createMediaSourceFactory()`, replace `return ritmeUrlSourceFactory.createMediaSource(mediaItem)` with `return ritmeUrlMediaSource(mediaItem)`.

- [ ] **Step 7: Run the tests and compile**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'`
Expected: BUILD SUCCESSFUL, all Ritme tests pass (including `RitmeAddVideoModelTest`, unchanged).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModel.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt app/src/test/kotlin/com/example/musicfy/ritme
git commit -m "feat(ritme-android): URL entries carry streamType and referrer; playback sends the Referer per item" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Android probe glue; "Tambah video" checks pasted streams

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeProbeHttp.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeProbes.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeFileLength.kt`
- Replace: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLookup.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModel.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt` (`webOrigin`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt` (model creation, `problemText`)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/browser/RitmeProbeHttpTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModelTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt`

**Interfaces:**
- Consumes: `ProbeHttp`, `ProbeResponse`, `MediaProber`, `ProbeVerdict`, `Refusal`, `MediaUrlClassifier.streamTypeOf` (Tasks 2, 4); `RitmeUrlHttp.client`, `RitmeAddressGuard`, `RitmeProbeGate`, `RitmeItems.FALLBACK_USER_AGENT`; `RitmeEntries.toUrlEntry(…, streamType, referrer)` (Task 10).
- Produces:
  - `class RitmeProbeHttp(client: OkHttpClient) : ProbeHttp`
  - `object RitmeProbes { const val MAX_PARALLEL = 4; const val TIMEOUT_MS = 8_000L; @Volatile var userAgent: String; suspend fun probe(url: String, pageUrl: String?, webOrigin: String): ProbeVerdict }`
  - `object RitmeFileLength { suspend fun read(url: String, referrer: String? = null): Long? }`
  - `VideoLookup.probeStream(url: String): ProbeVerdict?`; `AddVideoProblem.PROTECTED`, `AddVideoProblem.NEEDS_LOGIN`
  - `class RitmeVideoLookup(webOrigin: String)`
  - `RitmeController.webOrigin(): String` (origin without trailing slash, e.g. `https://ritme.dubeli.id`)

- [ ] **Step 1: Depend on the module**

In `app/build.gradle.kts`, after `implementation(project(":modules:ritme-protocol"))` add:

```kotlin
    implementation(project(":modules:ritme-media"))
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/example/musicfy/ritme/browser/RitmeProbeHttpTest.kt`:

```kotlin
package com.example.musicfy.ritme.browser

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The probe's HTTP side, against an interceptor standing in for the server (no network). */
class RitmeProbeHttpTest {
    private val seen = mutableListOf<Request>()

    private fun client(body: String, type: String = "application/vnd.apple.mpegurl", allowOrigin: String? = "*") =
        OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            val response = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Type", type)
                .body(body.toResponseBody(type.toMediaType()))
            if (allowOrigin != null) response.header("Access-Control-Allow-Origin", allowOrigin)
            response.build()
        }.build()

    @Test
    fun sendsExactlyTheProbesHeaders() {
        RitmeProbeHttp(client("#EXTM3U\n")).get("https://cdn.example.com/v.m3u8", mapOf("Range" to "bytes=0-4095"), 4096)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("bytes=0-4095", request.header("Range"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Referer"))
        assertNull(request.header("Origin"))
    }

    @Test
    fun theAnswerIsCutAtMaxBytes() {
        val answer = RitmeProbeHttp(client("#EXTM3U\n" + "x".repeat(10_000))).get("https://cdn.example.com/v.m3u8", emptyMap(), 4096)
        assertEquals(4096, answer.body.size)
        assertEquals(206, answer.status)
        assertEquals("application/vnd.apple.mpegurl", answer.contentType)
        assertEquals("*", answer.allowOrigin)
        assertEquals("https://cdn.example.com/v.m3u8", answer.finalUrl)
    }

    @Test
    fun aShortAnswerComesWhole() {
        assertEquals(8, RitmeProbeHttp(client("#EXTM3U\n", allowOrigin = null)).get("https://cdn.example.com/v.m3u8", emptyMap(), 4096).body.size)
    }
}
```

In `RitmeAddVideoModelTest.kt` add imports `com.example.musicfy.ritme.media.MediaFormat`, `com.example.musicfy.ritme.media.ProbeVerdict`, `com.example.musicfy.ritme.media.Refusal`, `com.example.musicfy.ritme.protocol.StreamType`; replace `FakeLookup` with:

```kotlin
    private class FakeLookup(
        val infos: Map<String, VideoChoice> = emptyMap(),
        val results: List<VideoChoice> = emptyList(),
        val probed: Long? = null,
        val stream: ProbeVerdict? = null,
    ) : VideoLookup {
        val infoCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<String>()
        val probeCalls = mutableListOf<String>()
        val streamCalls = mutableListOf<String>()

        override suspend fun info(videoId: String): VideoChoice? {
            infoCalls += videoId
            return infos[videoId]
        }

        override suspend fun search(query: String): List<VideoChoice> {
            searchCalls += query
            return results
        }

        override suspend fun probeDurationMs(url: String): Long? {
            probeCalls += url
            return probed
        }

        override suspend fun probeStream(url: String): ProbeVerdict? {
            streamCalls += url
            return stream
        }
    }
```

and add these tests:

```kotlin
    @Test
    fun aCheckedFinishedStreamKeepsItsLengthAndType() {
        val url = "https://cdn.example.com/v/master.m3u8"
        val lookup = FakeLookup(stream = ProbeVerdict(url, StreamType.HLS, MediaFormat.HLS, durationMs = 1_500_000))
        val state = resolve(lookup, url) as AddVideoState.Preview
        assertEquals(1_500_000L, state.entry.durationMs)
        assertEquals(StreamType.HLS, state.entry.streamType)
        assertEquals(listOf(url), lookup.streamCalls)
        assertEquals(StreamType.HLS, RitmeAddVideoModel.entryOf(state, "Film")?.streamType)
    }

    @Test
    fun aLiveOrUncheckedStreamIsAddedAsLiveLikeBefore() {
        val url = "https://cdn.example.com/live/master.m3u8"
        val live = resolve(FakeLookup(stream = ProbeVerdict(url, StreamType.HLS, MediaFormat.HLS, live = true)), url) as AddVideoState.Preview
        assertEquals(0L, live.entry.durationMs)
        val failed = resolve(FakeLookup(stream = ProbeVerdict.refused(url, Refusal.FAILED)), url) as AddVideoState.Preview
        assertEquals(0L, failed.entry.durationMs)
        val unchecked = resolve(FakeLookup(stream = null), "https://cdn.example.com/v/manifest.mpd") as AddVideoState.Preview
        assertEquals(0L, unchecked.entry.durationMs)
        assertEquals(StreamType.DASH, unchecked.entry.streamType)
    }

    @Test
    fun aProtectedOrSignInOnlyStreamIsAProblem() {
        val url = "https://cdn.example.com/v/master.m3u8"
        assertEquals(AddVideoState.Problem(AddVideoProblem.PROTECTED), resolve(FakeLookup(stream = ProbeVerdict.refused(url, Refusal.DRM)), url))
        assertEquals(AddVideoState.Problem(AddVideoProblem.NEEDS_LOGIN), resolve(FakeLookup(stream = ProbeVerdict.refused(url, Refusal.NEEDS_LOGIN)), url))
    }

    @Test
    fun aFileIsNeverStreamChecked() {
        val lookup = FakeLookup(probed = 90_000)
        resolve(lookup, "https://cdn.example.com/v.mp4")
        assertTrue(lookup.streamCalls.isEmpty())
    }
```

In `RitmeControllerTest.kt` add:

```kotlin
    @Test
    fun theWebOriginIsTheRoomServersOrigin() {
        assertEquals("https://ritme.dubeli.id", controller.webOrigin())
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.browser.*' --tests 'com.example.musicfy.ritme.video.RitmeAddVideoModelTest' --tests 'com.example.musicfy.ritme.RitmeControllerTest'`
Expected: compilation FAILS (`RitmeProbeHttp`, `probeStream`, `PROTECTED`, `webOrigin` unresolved).

- [ ] **Step 4: Write the HTTP side and the probe runner**

Create `RitmeProbeHttp.kt`:

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.ProbeHttp
import com.example.musicfy.ritme.media.ProbeResponse
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [ProbeHttp] over OkHttp. Given the room's client (RitmeUrlHttp: no cookies, no cache, no proxy,
 * public addresses only), it sends exactly the headers the prober asks for, follows redirects
 * (each hop checked by RitmeAddressGuard) and reads at most `maxBytes` of the body.
 */
class RitmeProbeHttp(private val client: OkHttpClient) : ProbeHttp {

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        client.newCall(request).execute().use { response ->
            val source = response.body.source()
            source.request(maxBytes.toLong())
            val size = minOf(source.buffer.size, maxBytes.toLong())
            return ProbeResponse(
                status = response.code,
                contentType = response.header("Content-Type"),
                body = source.buffer.readByteArray(size),
                finalUrl = response.request.url.toString(),
                allowOrigin = response.header("Access-Control-Allow-Origin"),
            )
        }
    }
}
```

Create `RitmeProbes.kt`:

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.MediaProber
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.playback.RitmeItems
import com.example.musicfy.ritme.playback.RitmeUrlHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Checks found and pasted links on this device (spec 4.7 step 4), the way another member's device
 * will fetch them: the room's guarded OkHttp client (RitmeUrlHttp), never WebView or YouTube
 * cookies. At most [MAX_PARALLEL] at once, each given up after [TIMEOUT_MS] (a failure then).
 */
object RitmeProbes {
    const val MAX_PARALLEL = 4
    const val TIMEOUT_MS = 8_000L

    /** The Chrome user agent members' players send; the browser fills it in from its WebView. */
    @Volatile
    var userAgent: String = RitmeItems.FALLBACK_USER_AGENT

    private val slots = Semaphore(MAX_PARALLEL)
    private val http by lazy {
        RitmeProbeHttp(RitmeUrlHttp.client { userAgent }.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build())
    }

    /** [webOrigin]: the Ritme web client's origin (RitmeController.webOrigin), for the CORS check. */
    suspend fun probe(url: String, pageUrl: String?, webOrigin: String): ProbeVerdict = slots.withPermit {
        withTimeoutOrNull(TIMEOUT_MS) {
            // Blocking OkHttp calls, interrupted when the timeout (or the caller) gives up.
            runInterruptible(Dispatchers.IO) { MediaProber(http, webOrigin).probe(url, pageUrl) }
        } ?: ProbeVerdict.refused(url, Refusal.FAILED)
    }
}
```

Create `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeFileLength.kt`:

```kotlin
package com.example.musicfy.ritme.video

import android.media.MediaMetadataRetriever
import com.example.musicfy.ritme.playback.RitmeAddressGuard
import kotlinx.coroutines.ensureActive
import java.net.InetAddress
import java.net.URI

/**
 * A progressive file's length, read on this (the adder's) device by MediaMetadataRetriever (#2b
 * Decision 13, spec 4.7): no cookies, and a Referer only when the browser's check found that the
 * host needs one. At most [TIMEOUT_MS]; null on any failure. Only when every address of the host
 * is public (RitmeAddressGuard): this does not go through the room's guarded OkHttp client.
 */
object RitmeFileLength {
    private const val TIMEOUT_MS = 8_000L

    /** Process-wide: a probe outlives the sheet or page that asked for it. */
    private val gate = RitmeProbeGate(TIMEOUT_MS)

    suspend fun read(url: String, referrer: String? = null): Long? = gate.run {
        // setDataSource blocks and cannot be interrupted: RitmeProbeGate runs it apart, at most two
        // at a time, and gives up waiting after the timeout.
        runCatching {
            val host = URI(url).host ?: return@runCatching null
            if (!InetAddress.getAllByName(host).all(RitmeAddressGuard::isPublic)) return@runCatching null
            ensureActive()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Replace `RitmeVideoLookup.kt`**

```kotlin
package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.browser.RitmeProbes
import com.example.musicfy.ritme.media.ProbeVerdict
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Add video"'s network side (spec 4.6). YouTube is asked without the user's login: get_queue and
 * the general search as YouTube Music's client does, the WEB player fallback as the WEB client,
 * which sends no cookie (YouTubeClient.WEB.loginSupported is false). Pasted files and streams are
 * checked on this device only, never by the server: a file's length by RitmeFileLength, HLS/DASH
 * by RitmeProbes ([webOrigin]: the room's web client, for the CORS check).
 */
class RitmeVideoLookup(private val webOrigin: String) : VideoLookup {

    override suspend fun info(videoId: String): VideoChoice? = withContext(Dispatchers.IO) {
        RitmeVideoInfo.info(
            videoId,
            queue = { YouTube.queueRenderers(listOf(videoId)) },
            // Metadata only: RitmeVideoInfo.fromPlayer reads videoDetails, never streamingData.
            player = { YouTube.player(videoId, client = YouTubeClient.WEB) },
        )
    }

    override suspend fun search(query: String): List<VideoChoice> = withContext(Dispatchers.IO) {
        RitmeVideoInfo.search(
            web = { YouTube.searchWebVideos(query) },
            music = { YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).map { it.items.filterIsInstance<SongItem>() } },
        )
    }

    override suspend fun probeDurationMs(url: String): Long? = RitmeFileLength.read(url)

    override suspend fun probeStream(url: String): ProbeVerdict = RitmeProbes.probe(url, pageUrl = null, webOrigin = webOrigin)
}
```

- [ ] **Step 6: The model checks pasted streams**

In `RitmeAddVideoModel.kt`:
- add imports `com.example.musicfy.ritme.media.MediaUrlClassifier`, `com.example.musicfy.ritme.media.ProbeVerdict`, `com.example.musicfy.ritme.media.Refusal`, `com.example.musicfy.ritme.protocol.StreamType`;
- in `interface VideoLookup`, after `probeDurationMs`, add:

```kotlin
    /** An HLS/DASH link checked like the browser's finds (spec 4.6, 4.7 step 4); null when it could not be checked. */
    suspend fun probeStream(url: String): ProbeVerdict?
```

- in `enum class AddVideoProblem`, after `LENGTH_UNREADABLE,` add:

```kotlin

    /** A stream with DRM keys (U12). */
    PROTECTED,

    /** A stream whose host refuses it without a sign-in (U12). */
    NEEDS_LOGIN,
```

- in `resolve`, replace the whole `is VideoLink.Direct -> { … }` branch with `is VideoLink.Direct -> direct(link)`, and add to the class:

```kotlin
    private suspend fun direct(link: VideoLink.Direct): AddVideoState {
        if (!link.streaming) {
            // A file whose length could not be read is never added as live (joiners at 0:00, seeks
            // ignored, "LIVE" on its row): it is a problem the adder can try again.
            val durationMs = lookup.probeDurationMs(link.url) ?: return AddVideoState.Problem(AddVideoProblem.LENGTH_UNREADABLE)
            return urlPreview(RitmeEntries.toUrlEntry(link.url, link.fileName, link.domain, durationMs))
        }
        // HLS/DASH (#3): checked like the browser's finds. A finished stream keeps its length, so a
        // play after a pause resumes instead of jumping to the "live edge"; DRM and sign-in-only
        // streams are refused (U12). Not checkable: live (0), as in #2b.
        val verdict = lookup.probeStream(link.url)
        when (verdict?.refusal) {
            Refusal.DRM -> return AddVideoState.Problem(AddVideoProblem.PROTECTED)
            Refusal.NEEDS_LOGIN -> return AddVideoState.Problem(AddVideoProblem.NEEDS_LOGIN)
            else -> Unit
        }
        val checked = verdict?.takeIf { it.refusal == null }
        val durationMs = if (checked == null || checked.live) 0L else checked.durationMs ?: 0L
        val streamType = checked?.streamType?.takeUnless { it == StreamType.UNKNOWN } ?: MediaUrlClassifier.streamTypeOf(link.url)
        return urlPreview(RitmeEntries.toUrlEntry(link.url, link.fileName, link.domain, durationMs, streamType))
    }

    private fun urlPreview(entry: NewEntry?): AddVideoState =
        entry?.let { AddVideoState.Preview(it, editableTitle = true) } ?: AddVideoState.Problem(AddVideoProblem.UNSAFE)
```

- [ ] **Step 7: `RitmeController.webOrigin`**

In `RitmeController.kt` add `import com.example.musicfy.ritme.protocol.MediaUrlRules` and, after `shareLink`, add:

```kotlin
    /**
     * The Ritme web client's origin (where web members play from), for the probes' CORS check:
     * the room's server, or the default server when that is a local development address.
     */
    fun webOrigin(): String {
        val origin = MediaUrlRules.referrerOf(session.serverUrl ?: settings.serverUrl)
            ?: MediaUrlRules.referrerOf(RitmeConfig.DEFAULT_SERVER_URL)
        return origin?.removeSuffix("/") ?: RitmeConfig.DEFAULT_SERVER_URL.trimEnd('/')
    }
```

- [ ] **Step 8: The sheet uses it, and names the new problems**

In `RitmeAddVideoSheet.kt`:
- replace `val model = remember { RitmeAddVideoModel(RitmeVideoLookup()) }` with `val model = remember { RitmeAddVideoModel(RitmeVideoLookup(ritme.controller.webOrigin())) }`;
- in `problemText`, after `AddVideoProblem.LENGTH_UNREADABLE -> R.string.ritme_add_video_length_unreadable`, add:

```kotlin
            AddVideoProblem.PROTECTED -> R.string.ritme_add_video_protected
            AddVideoProblem.NEEDS_LOGIN -> R.string.ritme_add_video_needs_login
```

Strings: in `values/ritme_strings.xml`, before `</resources>`:

```xml
    <string name="ritme_add_video_protected">Protected videos can\'t be shared</string>
    <string name="ritme_add_video_needs_login">This site needs a sign-in, so other members can\'t play it</string>
```

in `values-in/ritme_strings.xml`, before `</resources>`:

```xml
    <string name="ritme_add_video_protected">Video berproteksi tidak bisa dibagikan</string>
    <string name="ritme_add_video_needs_login">Butuh login di situs ini, jadi tidak bisa diputar anggota lain</string>
```

- [ ] **Step 9: Run the tests and compile**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'` then `g :app:compileUniversalGmsDebugKotlin`
Expected: BUILD SUCCESSFUL both times.

- [ ] **Step 10: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/example/musicfy/ritme app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/test/kotlin/com/example/musicfy/ritme
git commit -m "feat(ritme-android): on-device probes for found and pasted streams; Add video keeps VOD stream lengths" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 12: Browser state (`RitmeBrowserModel`) and the confirmation model (`RitmeBrowserEntries`)

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeBrowserModel.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/browser/RitmeBrowserEntries.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/browser/RitmeBrowserModelTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/browser/RitmeBrowserEntriesTest.kt`

**Interfaces:**
- Consumes: `CandidateCollector`, `Candidate`, `CandidateRanking`, `FoundVia`, `PageFindings`, `PageReports` (Tasks 6–7); `ProbeVerdict`, `Refusal`, `MediaFormat` (Task 4); `SensitiveParams`, `ExpiryHints`, `MediaUrlClassifier` (Task 2); `RitmeEntries.toUrlEntry`, `hasVisibleText`, `MAX_TEXT` (Task 10).
- Produces:

```kotlin
sealed interface ProbeState { data object Checking : ProbeState; data class Done(val verdict: ProbeVerdict) : ProbeState }
data class FoundItem(val candidate: Candidate, val state: ProbeState?)
class RitmeBrowserModel(
    probe: suspend (url: String, pageUrl: String?) -> ProbeVerdict,
    fileLength: suspend (url: String, referrer: String?) -> Long?,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    collector: CandidateCollector = CandidateCollector(),
) {
    val findings: StateFlow<PageFindings>; val probes: StateFlow<Map<String, ProbeState>>
    fun pageStarted(url: String); fun urlChanged(url: String)
    fun onRequest(url: String, method: String, headers: Map<String, String>)   // any thread
    fun onReport(message: String, mainFrame: Boolean); fun onProtectedMedia()
    fun items(findings: PageFindings = …, probes: Map<String, ProbeState> = …): List<FoundItem>
    fun offers(findings: PageFindings = …, probes: Map<String, ProbeState> = …): Int
    fun verdictOf(url: String): ProbeVerdict?
    fun probeAll(): List<Job>; fun retry(url: String): Job?
}
enum class BrowserNote { SENSITIVE, EXPIRES, EXPIRED, IP_LOCKED, APP_ONLY, NOT_ON_WEB, KEY_MAY_NEED_LOGIN }
object RitmeBrowserEntries {
    fun defaultTitle(candidate: Candidate, findings: PageFindings): String
    fun rowTitle(candidate: Candidate, findings: PageFindings): String
    fun source(findings: PageFindings, candidate: Candidate): String
    fun entryOf(candidate: Candidate, verdict: ProbeVerdict, title: String, source: String, pageUrl: String?): NewEntry?
    fun notes(url: String, verdict: ProbeVerdict, nowMs: Long): List<BrowserNote>
    fun typeLabel(url: String, streamType: StreamType): String?
    fun domainOf(url: String): String
}
```

- [ ] **Step 1: Write the failing tests**

Create `RitmeBrowserModelTest.kt`:

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.MediaFormat
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.StreamType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeBrowserModelTest {
    private val page = "https://example.com/film/1"
    private val hls = "https://cdn.example.com/v/master.m3u8"
    private val variant = "https://cdn.example.com/v/720p/index.m3u8"
    private val mp4 = "https://cdn.example.com/v/film.mp4"

    private class Fakes(val length: Long? = null) {
        val verdicts = mutableMapOf<String, ProbeVerdict>()
        val probed = mutableListOf<String>()
        val lengthAsked = mutableListOf<Pair<String, String?>>()
        val probe: suspend (String, String?) -> ProbeVerdict = { url, _ ->
            probed += url
            verdicts[url] ?: ProbeVerdict.refused(url, Refusal.FAILED)
        }
        val fileLength: suspend (String, String?) -> Long? = { url, referrer ->
            lengthAsked += url to referrer
            length
        }
    }

    private fun CoroutineScope.model(fakes: Fakes) =
        RitmeBrowserModel(fakes.probe, fakes.fileLength, this, clock = { 1_000L }).also { it.pageStarted(page) }

    private fun hlsVerdict() = ProbeVerdict(hls, StreamType.HLS, MediaFormat.HLS, durationMs = 1_200_000, variants = listOf(variant))

    private fun fileVerdict(durationMs: Long?, needsReferrer: Boolean = false) =
        ProbeVerdict(mp4, StreamType.FILE, MediaFormat.MP4, durationMs = durationMs, needsReferrer = needsReferrer, webPlayable = !needsReferrer)

    private fun RitmeBrowserModel.request(url: String) = onRequest(url, "GET", emptyMap())

    @Test
    fun eachFindIsCheckedOnceAndOnlyWhenTheListAsks() = runBlocking<Unit> {
        val fakes = Fakes().apply {
            verdicts[hls] = hlsVerdict()
            verdicts[mp4] = fileVerdict(60_000)
        }
        val model = model(fakes)
        model.request(hls)
        model.request(mp4)
        assertTrue(fakes.probed.isEmpty())
        model.probeAll().joinAll()
        assertEquals(setOf(hls, mp4), fakes.probed.toSet())
        assertTrue(model.probeAll().isEmpty())
        assertEquals(2, fakes.probed.size)
        assertEquals(60_000L, model.verdictOf(mp4)?.durationMs)
    }

    @Test
    fun aMastersVariantsAndPagesThatAreNotVideosLeaveTheList() = runBlocking<Unit> {
        val embed = "https://example.com/embed/1.mp4"
        val fakes = Fakes().apply {
            verdicts[hls] = hlsVerdict()
            verdicts[embed] = ProbeVerdict.refused(embed, Refusal.NOT_MEDIA)
        }
        val model = model(fakes)
        model.request(hls)
        model.request(variant)
        model.request(embed)
        assertEquals(3, model.items().size)
        model.probeAll().joinAll()
        assertEquals(listOf(hls), model.items().map { it.candidate.url })
    }

    @Test
    fun anAnswerAboutAnOldPageIsIgnored() = runBlocking<Unit> {
        val answer = CompletableDeferred<ProbeVerdict>()
        val model = RitmeBrowserModel({ _, _ -> answer.await() }, { _, _ -> null }, this)
        model.pageStarted(page)
        model.request(mp4)
        val jobs = model.probeAll()
        model.pageStarted("https://example.com/other")
        answer.complete(fileVerdict(60_000))
        jobs.joinAll()
        assertTrue(model.probes.value.isEmpty())
        assertTrue(model.items().isEmpty())
    }

    @Test
    fun aFilesLengthComesFromThePageThenFromTheFile() = runBlocking<Unit> {
        // the page's <video> knows it: the file is not read
        val fromPage = Fakes(length = 30_000).apply { verdicts[mp4] = fileVerdict(null) }
        val first = model(fromPage)
        first.onReport("""{"v":1,"page":"$page","videos":[{"srcs":["$mp4"],"blob":false,"duration":61.5,"w":0,"h":0,"area":0,"playing":false}]}""", mainFrame = true)
        first.probeAll().joinAll()
        assertEquals(61_500L, first.verdictOf(mp4)?.durationMs)
        assertTrue(fromPage.lengthAsked.isEmpty())
        // read from the file, with the Referer when the host needs it
        val fromFile = Fakes(length = 30_000).apply { verdicts[mp4] = fileVerdict(null, needsReferrer = true) }
        val second = model(fromFile)
        second.request(mp4)
        second.probeAll().joinAll()
        assertEquals(30_000L, second.verdictOf(mp4)?.durationMs)
        assertEquals(listOf(mp4 to "https://example.com/"), fromFile.lengthAsked)
        // neither: refused, to be tried again
        val unknown = Fakes(length = null).apply { verdicts[mp4] = fileVerdict(null) }
        val third = model(unknown)
        third.request(mp4)
        third.probeAll().joinAll()
        assertEquals(Refusal.LENGTH_UNKNOWN, third.verdictOf(mp4)?.refusal)
    }

    @Test
    fun drmOnThePageRefusesItsFinds() = runBlocking<Unit> {
        val fakes = Fakes().apply { verdicts[mp4] = fileVerdict(60_000) }
        val model = model(fakes)
        model.request(mp4)
        model.onProtectedMedia()
        model.probeAll().joinAll()
        assertEquals(Refusal.DRM, model.verdictOf(mp4)?.refusal)
    }

    @Test
    fun retryChecksAgain() = runBlocking<Unit> {
        val fakes = Fakes()
        val model = model(fakes)
        model.request(mp4)
        model.probeAll().joinAll()
        assertEquals(Refusal.FAILED, model.verdictOf(mp4)?.refusal)
        fakes.verdicts[mp4] = fileVerdict(60_000)
        model.retry(mp4)!!.join()
        assertNull(model.verdictOf(mp4)?.refusal)
        assertEquals(2, fakes.probed.size)
    }

    @Test
    fun thePillCountsSureFindsAndAYouTubeVideo() = runBlocking<Unit> {
        val model = model(Fakes())
        model.onReport("""{"v":1,"page":"$page","og":["https://example.com/player/1"]}""", mainFrame = true)
        assertEquals(1, model.items().size)
        assertEquals(0, model.offers())
        model.request(hls)
        assertEquals(1, model.offers())
        model.pageStarted("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, model.offers())
    }
}
```

Create `RitmeBrowserEntriesTest.kt`:

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.FoundVia
import com.example.musicfy.ritme.media.MediaFormat
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeBrowserEntriesTest {
    private val candidate = Candidate("https://cdn.example.com/films/My%20Film.mp4", StreamType.FILE, setOf(FoundVia.NETWORK), 0, 0)
    private val findings = PageFindings(
        pageUrl = "https://www.example.com/watch/1",
        candidates = listOf(candidate),
        title = "Watch: My Film | Example",
        ogTitle = "My Film",
        siteName = "Example TV",
    )
    private val ok = ProbeVerdict(candidate.url, StreamType.FILE, MediaFormat.MP4, durationMs = 90_000)

    @Test
    fun theTitleComesFromOgTitleThenThePageThenTheFileThenTheSite() {
        assertEquals("My Film", RitmeBrowserEntries.defaultTitle(candidate, findings))
        assertEquals("Watch: My Film | Example", RitmeBrowserEntries.defaultTitle(candidate, findings.copy(ogTitle = null)))
        assertEquals("My Film.mp4", RitmeBrowserEntries.defaultTitle(candidate, findings.copy(ogTitle = null, title = " ")))
        assertEquals("example.com", RitmeBrowserEntries.defaultTitle(candidate.copy(url = "https://cdn.example.com/"), findings.copy(ogTitle = null, title = null)))
        assertEquals("My Film.mp4", RitmeBrowserEntries.rowTitle(candidate, findings))
        assertEquals("Klip", RitmeBrowserEntries.rowTitle(candidate.copy(name = "Klip"), findings))
    }

    @Test
    fun theSourceIsTheSiteNameElseTheDomain() {
        assertEquals("Example TV", RitmeBrowserEntries.source(findings, candidate))
        assertEquals("example.com", RitmeBrowserEntries.source(findings.copy(siteName = null), candidate))
    }

    @Test
    fun theEntryCarriesWhatTheCheckFound() {
        assertEquals(
            NewEntry("", "My Film", "Example TV", null, 90_000, MediaKind.URL, candidate.url, StreamType.FILE, null),
            RitmeBrowserEntries.entryOf(candidate, ok, "My Film", "Example TV", findings.pageUrl),
        )
        val hls = candidate.copy(url = "https://cdn.example.com/live/index.m3u8", streamType = StreamType.HLS)
        val live = ProbeVerdict(hls.url, StreamType.HLS, MediaFormat.HLS, live = true, needsReferrer = true, webPlayable = false)
        val entry = RitmeBrowserEntries.entryOf(hls, live, "Live", "Example TV", "https://www.example.com/live")!!
        assertEquals(0L, entry.durationMs)
        assertEquals("https://www.example.com/", entry.referrer)
        assertEquals(StreamType.HLS, entry.streamType)
        assertNull(entry.thumbnailUrl)
    }

    @Test
    fun refusedOrUnsendableFindsHaveNoEntry() {
        listOf(Refusal.DRM, Refusal.NEEDS_LOGIN, Refusal.FAILED, Refusal.LENGTH_UNKNOWN).forEach {
            assertNull(it.name, RitmeBrowserEntries.entryOf(candidate, ok.copy(refusal = it), "T", "S", findings.pageUrl))
        }
        // needs a Referer, but the page's origin is not one that may be sent
        assertNull(RitmeBrowserEntries.entryOf(candidate, ok.copy(needsReferrer = true), "T", "S", "http://192.168.1.1/"))
        assertNull(RitmeBrowserEntries.entryOf(candidate, ok.copy(streamType = StreamType.UNKNOWN), "T", "S", findings.pageUrl)!!.streamType)
    }

    @Test
    fun notesWarnAboutKeysExpiryNetworkAndWhereItPlays() {
        val url = "https://cdn.example.com/v.m3u8?token=abc&expires=1700000600&ip=203.0.113.9"
        assertEquals(
            listOf(BrowserNote.SENSITIVE, BrowserNote.EXPIRES, BrowserNote.IP_LOCKED, BrowserNote.NOT_ON_WEB, BrowserNote.KEY_MAY_NEED_LOGIN),
            RitmeBrowserEntries.notes(url, ok.copy(webPlayable = false, aes128 = true), 1_700_000_000_000),
        )
        assertEquals(listOf(BrowserNote.EXPIRED), RitmeBrowserEntries.notes("https://cdn.example.com/v.mp4?exp=1600000000", ok, 1_700_000_000_000))
        assertEquals(listOf(BrowserNote.APP_ONLY), RitmeBrowserEntries.notes(candidate.url, ok.copy(needsReferrer = true, webPlayable = false), 0))
        assertEquals(emptyList<BrowserNote>(), RitmeBrowserEntries.notes(candidate.url, ok, 0))
    }

    @Test
    fun typeLabels() {
        assertEquals("HLS", RitmeBrowserEntries.typeLabel("https://x.example/a", StreamType.HLS))
        assertEquals("DASH", RitmeBrowserEntries.typeLabel("https://x.example/a", StreamType.DASH))
        assertEquals("MP4", RitmeBrowserEntries.typeLabel("https://x.example/a.mp4", StreamType.FILE))
        assertEquals("WebM", RitmeBrowserEntries.typeLabel("https://x.example/a.webm", StreamType.UNKNOWN))
        assertNull(RitmeBrowserEntries.typeLabel("https://x.example/stream", StreamType.UNKNOWN))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.browser.*'`
Expected: compilation FAILS (`RitmeBrowserModel`, `ProbeState`, `RitmeBrowserEntries`, `BrowserNote` unresolved).

- [ ] **Step 3: Write `RitmeBrowserModel`**

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.CandidateCollector
import com.example.musicfy.ritme.media.CandidateRanking
import com.example.musicfy.ritme.media.FoundVia
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.PageReports
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProbeState {
    data object Checking : ProbeState

    data class Done(val verdict: ProbeVerdict) : ProbeState
}

/** A row of the found list: a find, and where its check stands (null: not asked yet). */
data class FoundItem(val candidate: Candidate, val state: ProbeState?)

/**
 * The Ritme browser's findings and checks (spec 4.7 steps 1–4). The WebView feeds it (requests on
 * any thread, reports and page changes on the main thread); the list and confirmation sheets read
 * it. Checks start when the list asks ([probeAll]), each find once per page; a new page drops
 * everything, and an answer about an old page is ignored.
 */
class RitmeBrowserModel(
    private val probe: suspend (url: String, pageUrl: String?) -> ProbeVerdict,
    private val fileLength: suspend (url: String, referrer: String?) -> Long?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val collector: CandidateCollector = CandidateCollector(),
) {
    private val _findings = MutableStateFlow(PageFindings())
    val findings: StateFlow<PageFindings> = _findings.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    val probes: StateFlow<Map<String, ProbeState>> = _probes.asStateFlow()

    private val lock = Any()
    private var page = 0
    private val jobs = mutableListOf<Job>()

    fun pageStarted(url: String) {
        synchronized(lock) {
            page++
            jobs.forEach { it.cancel() }
            jobs.clear()
            collector.startPage(url)
            _probes.value = emptyMap()
        }
        refresh()
    }

    fun urlChanged(url: String) {
        if (collector.urlChanged(url)) refresh()
    }

    fun onRequest(url: String, method: String, headers: Map<String, String>) {
        if (collector.onRequest(url, method, headers, clock())) refresh()
    }

    fun onReport(message: String, mainFrame: Boolean) {
        val report = PageReports.parse(message, mainFrame) ?: return
        if (collector.onReport(report, clock())) refresh()
    }

    fun onProtectedMedia() {
        if (collector.onProtectedMedia()) refresh()
    }

    /** The list's rows, ranked; a checked master's variants and checked non-videos left out. */
    fun items(findings: PageFindings = this.findings.value, probes: Map<String, ProbeState> = this.probes.value): List<FoundItem> {
        val verdicts = probes.values.mapNotNull { (it as? ProbeState.Done)?.verdict }
        val hidden = verdicts.flatMap { it.variants }.toSet() + verdicts.filter { it.refusal == Refusal.NOT_MEDIA }.map { it.url }
        return CandidateRanking.rank(findings.candidates, hidden).map { FoundItem(it, probes[it.url]) }
    }

    /**
     * What the pill counts (Decision 18): finds that are surely media (seen played or requested,
     * a media extension, or checked), plus the page's YouTube video. og:video / JSON-LD links
     * without an extension are listed and checked, but do not raise the pill alone.
     */
    fun offers(findings: PageFindings = this.findings.value, probes: Map<String, ProbeState> = this.probes.value): Int {
        val sure = items(findings, probes).count { item ->
            item.state is ProbeState.Done || item.candidate.streamType != StreamType.UNKNOWN || item.candidate.via.any { it in SEEN_AS_MEDIA }
        }
        return sure + if (findings.youTubeVideoId != null) 1 else 0
    }

    fun verdictOf(url: String): ProbeVerdict? = (probes.value[url] as? ProbeState.Done)?.verdict

    /** Checks every listed find not checked yet (spec 4.7 step 4: when the list opens). */
    fun probeAll(): List<Job> = items().filter { it.state == null }.mapNotNull { start(it.candidate) }

    /** "Coba lagi": checks [url] once more. */
    fun retry(url: String): Job? {
        val candidate = findings.value.candidates.firstOrNull { it.url == url } ?: return null
        synchronized(lock) {
            if (_probes.value[url] is ProbeState.Checking) return null
            _probes.update { it - url }
        }
        return start(candidate)
    }

    private fun start(candidate: Candidate): Job? = synchronized(lock) {
        if (_probes.value[candidate.url] != null) return null
        val generation = page
        val pageUrl = findings.value.pageUrl
        _probes.update { it + (candidate.url to ProbeState.Checking) }
        val job = scope.launch {
            val verdict = finish(candidate, probe(candidate.url, pageUrl), pageUrl)
            synchronized(lock) {
                if (generation == page) _probes.update { it + (candidate.url to ProbeState.Done(verdict)) }
            }
        }
        jobs += job
        job
    }

    /** The page's own signals on top of the check: DRM asked for by the page, and a file's length (Decision 10). */
    private suspend fun finish(candidate: Candidate, verdict: ProbeVerdict, pageUrl: String?): ProbeVerdict {
        if (verdict.refusal != null) return verdict
        if (findings.value.eme) return verdict.copy(refusal = Refusal.DRM, webPlayable = false)
        if (verdict.streamType != StreamType.FILE || verdict.durationMs != null) return verdict
        val referrer = if (verdict.needsReferrer) pageUrl?.let(MediaUrlRules::referrerOf) else null
        val length = candidate.pageDurationMs ?: fileLength(candidate.url, referrer)
        return if (length != null && length > 0) verdict.copy(durationMs = length) else verdict.copy(refusal = Refusal.LENGTH_UNKNOWN)
    }

    private fun refresh() {
        synchronized(lock) { _findings.value = collector.findings() }
    }

    private companion object {
        val SEEN_AS_MEDIA = setOf(FoundVia.NETWORK, FoundVia.MEDIA_ELEMENT, FoundVia.PAGE_VIDEO)
    }
}
```

- [ ] **Step 4: Write `RitmeBrowserEntries`**

```kotlin
package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.ExpiryHints
import com.example.musicfy.ritme.media.MediaUrlClassifier
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.SensitiveParams
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType

/** What the confirmation sheet says besides the title and source (spec 4.7 steps 4 and 6). */
enum class BrowserNote { SENSITIVE, EXPIRES, EXPIRED, IP_LOCKED, APP_ONLY, NOT_ON_WEB, KEY_MAY_NEED_LOGIN }

/** The found list's and the confirmation's words, and the entry the confirmation sends. Pure. */
object RitmeBrowserEntries {

    /** The confirmation's default title: og:title → document.title → the JSON-LD name → the file name → the page's domain. */
    fun defaultTitle(candidate: Candidate, findings: PageFindings): String =
        listOfNotNull(
            findings.ogTitle,
            findings.title,
            candidate.name,
            MediaUrlClassifier.fileNameOf(candidate.url),
            domainOf(findings.pageUrl ?: candidate.url),
        ).firstOrNull(RitmeEntries::hasVisibleText).orEmpty().take(RitmeEntries.MAX_TEXT)

    /** A list row's title: the find's own name (JSON-LD, file name) so rows differ, else [defaultTitle]. */
    fun rowTitle(candidate: Candidate, findings: PageFindings): String =
        listOfNotNull(candidate.name, MediaUrlClassifier.fileNameOf(candidate.url)).firstOrNull(RitmeEntries::hasVisibleText)
            ?: defaultTitle(candidate, findings)

    /** The entry's "artist" (spec 4.7 step 6): og:site_name, else the page's domain without www. */
    fun source(findings: PageFindings, candidate: Candidate): String =
        findings.siteName?.takeIf(RitmeEntries::hasVisibleText)?.take(RitmeEntries.MAX_TEXT)
            ?: domainOf(findings.pageUrl ?: candidate.url)

    /**
     * What "Tambah"/"Putar berikutnya" send, or null when it may not be added (U12: DRM, needs a
     * sign-in, failed, unknown file length) or the server would refuse it. Fragment stripped, no
     * picture, length from the check (0 = live or unknown stream length), the page's origin as
     * referrer only when the host needs it.
     */
    fun entryOf(candidate: Candidate, verdict: ProbeVerdict, title: String, source: String, pageUrl: String?): NewEntry? {
        if (verdict.refusal != null) return null
        val referrer = if (verdict.needsReferrer) pageUrl?.let(MediaUrlRules::referrerOf) ?: return null else null
        val durationMs = if (verdict.live) 0L else verdict.durationMs ?: 0L
        return RitmeEntries.toUrlEntry(candidate.url.substringBefore('#'), title, source, durationMs, verdict.streamType, referrer)
    }

    fun notes(url: String, verdict: ProbeVerdict, nowMs: Long): List<BrowserNote> = buildList {
        if (SensitiveParams.has(url)) add(BrowserNote.SENSITIVE)
        val hints = ExpiryHints.of(url, nowMs)
        hints.expiresInMinutes?.let { add(if (it <= 0) BrowserNote.EXPIRED else BrowserNote.EXPIRES) }
        if (hints.ipLocked) add(BrowserNote.IP_LOCKED)
        if (verdict.needsReferrer) add(BrowserNote.APP_ONLY) else if (!verdict.webPlayable) add(BrowserNote.NOT_ON_WEB)
        if (verdict.aes128) add(BrowserNote.KEY_MAY_NEED_LOGIN)
    }

    /** "HLS", "DASH", "MP4", "WebM", …; null when nothing says. */
    fun typeLabel(url: String, streamType: StreamType): String? = when (streamType) {
        StreamType.HLS -> "HLS"
        StreamType.DASH -> "DASH"
        StreamType.FILE, StreamType.UNKNOWN -> when (MediaUrlClassifier.extensionOf(url)) {
            "mp4", "m4v" -> "MP4"
            "webm" -> "WebM"
            "mov" -> "MOV"
            "mkv" -> "MKV"
            "ogv" -> "Ogg"
            else -> null
        }
    }

    fun domainOf(url: String): String = MediaUrlClassifier.hostOf(url)?.removePrefix("www.").orEmpty()
}
```

- [ ] **Step 5: Run the tests**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/browser app/src/test/kotlin/com/example/musicfy/ritme/browser
git commit -m "feat(ritme-android): browser findings, on-demand checks and the confirmation's entry" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

# Phase D: The browser screen

### Task 13: The WebView, the screen and its route (detection layers 1–2, address bar)

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeBrowserWebView.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeBrowserScreen.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/NavigationBuilder.kt` (after the `YouTubeLoginRoute` composable)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`

**Interfaces:**
- Consumes: `BrowserAddress`, `AddressInput`, `AddressRefusal`, `Navigation`, `PageMediaScript`, `PageReports.MAX_MESSAGE_CHARS` (Tasks 5–6); `RitmeBrowserModel` (Task 12); `RitmeProbes`, `RitmeFileLength`, `RitmeController.webOrigin()` (Task 11); `chromeUserAgent`, `hideAppIdentity` (`ui/screens/settings/importsync/CaptureWebView.kt`, `internal`); `rememberRitme()`, `RitmeSecondaryText`, `SheetTextField`, `SheetLabel`, `SheetPillButton`, `Material3MenuGroup`, `LocalZoomOutOverlayState`, `LocalPlayerAwareWindowInsets`.
- Produces: `const val RitmeBrowserRoutePattern = "ritme/browser?url={url}"`; `fun ritmeBrowserRoute(url: String? = null): String`; `@Composable fun RitmeBrowserScreen(navController: NavController, initialText: String?)`; `internal interface RitmeWebEvents`; `internal fun createRitmeWebView(context: Context, events: RitmeWebEvents): WebView`; `internal fun watchServiceWorkers(events: RitmeWebEvents): () -> Unit`; `internal fun clearRitmeBrowserData(view: WebView)`; `internal fun ritmeProfileSupported(): Boolean`.

There is no unit test for this task: everything it decides is pure and already tested (`BrowserAddress`, `PageReports`, `RitmeBrowserModel`). It is checked by compiling and on a device (checklist).

- [ ] **Step 1: Strings**

English (`values/ritme_strings.xml`, before `</resources>`):

```xml
    <string name="ritme_browser_back">Back</string>
    <string name="ritme_browser_reload">Reload</string>
    <string name="ritme_browser_menu">More</string>
    <string name="ritme_browser_address_hint">Search or type an address</string>
    <string name="ritme_browser_forward">Forward</string>
    <string name="ritme_browser_copy_link">Copy link</string>
    <string name="ritme_browser_link_copied">Link copied</string>
    <string name="ritme_browser_open_elsewhere">Open in another browser</string>
    <string name="ritme_browser_clear_data">Clear Ritme browser data</string>
    <string name="ritme_browser_data_cleared">Ritme browser data cleared</string>
    <string name="ritme_browser_close">Close browser</string>
    <string name="ritme_browser_refused_empty">Type an address or keywords</string>
    <string name="ritme_browser_refused_scheme">Addresses like javascript:, file:, content: or intent: can\'t be opened in the Ritme browser</string>
    <string name="ritme_browser_refused_local">Local and private network addresses can\'t be opened in the Ritme browser</string>
    <string name="ritme_browser_refused_credentials">Addresses with a user name and password can\'t be opened</string>
    <string name="ritme_browser_refused_malformed">That address isn\'t valid</string>
    <string name="ritme_browser_open_app_title">Open another app?</string>
    <string name="ritme_browser_open_app_body">This page wants to open another app. The Ritme browser stays here.</string>
    <string name="ritme_browser_open_app">Open</string>
```

Indonesian (`values-in/ritme_strings.xml`, before `</resources>`):

```xml
    <string name="ritme_browser_back">Kembali</string>
    <string name="ritme_browser_reload">Muat ulang</string>
    <string name="ritme_browser_menu">Lainnya</string>
    <string name="ritme_browser_address_hint">Cari atau ketik alamat</string>
    <string name="ritme_browser_forward">Maju</string>
    <string name="ritme_browser_copy_link">Salin link</string>
    <string name="ritme_browser_link_copied">Link disalin</string>
    <string name="ritme_browser_open_elsewhere">Buka di browser lain</string>
    <string name="ritme_browser_clear_data">Hapus data browser Ritme</string>
    <string name="ritme_browser_data_cleared">Data browser Ritme dihapus</string>
    <string name="ritme_browser_close">Tutup browser</string>
    <string name="ritme_browser_refused_empty">Ketik alamat atau kata kunci</string>
    <string name="ritme_browser_refused_scheme">Alamat seperti javascript:, file:, content:, atau intent: tidak bisa dibuka di browser Ritme</string>
    <string name="ritme_browser_refused_local">Alamat lokal atau jaringan pribadi tidak bisa dibuka di browser Ritme</string>
    <string name="ritme_browser_refused_credentials">Alamat berisi nama pengguna dan kata sandi tidak bisa dibuka</string>
    <string name="ritme_browser_refused_malformed">Alamat ini tidak valid</string>
    <string name="ritme_browser_open_app_title">Buka aplikasi lain?</string>
    <string name="ritme_browser_open_app_body">Halaman ini ingin membuka aplikasi lain. Browser Ritme tetap di sini.</string>
    <string name="ritme_browser_open_app">Buka</string>
```

- [ ] **Step 2: The WebView**

Create `RitmeBrowserWebView.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.musicfy.ritme.media.BrowserAddress
import com.example.musicfy.ritme.media.Navigation
import com.example.musicfy.ritme.media.PageMediaScript
import com.example.musicfy.ritme.media.PageReports
import com.example.musicfy.ui.screens.settings.importsync.chromeUserAgent
import com.example.musicfy.ui.screens.settings.importsync.hideAppIdentity

/** What the Ritme browser's WebView tells its screen. [onRequest] arrives on a WebView thread; the rest on the main thread. */
internal interface RitmeWebEvents {
    fun onPageStarted(url: String)
    fun onHistoryChanged(url: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgress(percent: Int)
    fun onRequest(url: String, method: String, headers: Map<String, String>)
    fun onPageReport(message: String, mainFrame: Boolean)
    fun onProtectedMedia()
    fun onLocalAddressRefused()
    fun onExternalApp(url: String)
}

/** The Ritme browser's own WebView profile (U11): its cookies and storage never mix with the app's other web views. */
internal const val RITME_BROWSER_PROFILE = "ritme-browser"

internal fun ritmeProfileSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/**
 * The browser's WebView (spec 4.7): its own profile where the device's WebView has profiles, JS on,
 * no file or content access, Safe Browsing on, one window (popups load here or are blocked),
 * downloads ignored, no app name header, the Chrome user agent. Detection: every request is shown
 * to [events] and never changed (shouldInterceptRequest always returns null, D9), and the page
 * script reports through a web message listener (D10; never addJavascriptInterface, which reaches
 * every frame without saying which).
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
internal fun createRitmeWebView(context: Context, events: RitmeWebEvents): WebView {
    val view = WebView(context)
    // First of all: a profile can only be set before the view is used.
    if (ritmeProfileSupported()) {
        ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE)
        WebViewCompat.setProfile(view, RITME_BROWSER_PROFILE)
    }
    // A WebView without LayoutParams lays pages out at zero height (see CaptureWebView).
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    with(view.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        safeBrowsingEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = false
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        userAgentString = chromeUserAgent(userAgentString)
    }
    view.hideAppIdentity()
    view.setDownloadListener { _, _, _, _, _ -> }
    val documentStart = installPageScript(view, events)
    view.webViewClient = RitmeWebClient(events, documentStart)
    view.webChromeClient = RitmeChromeClient(events)
    return view
}

/**
 * The page script and its listener. Every origin may post (rule "*": the rules cannot say "any
 * http(s) origin"), but only http(s) origins are listened to and the message is parsed as
 * untrusted. True when the script runs at document start in every frame.
 */
@SuppressLint("RequiresFeature")
private fun installPageScript(view: WebView, events: RitmeWebEvents): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
    WebViewCompat.addWebMessageListener(view, PageMediaScript.BRIDGE_NAME, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
        val scheme = sourceOrigin.scheme?.lowercase()
        val data = message.data
        if ((scheme == "https" || scheme == "http") && data != null && data.length <= PageReports.MAX_MESSAGE_CHARS) {
            events.onPageReport(data, isMainFrame)
        }
    }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
    WebViewCompat.addDocumentStartJavaScript(view, PageMediaScript.source, setOf("*"))
    return true
}

private class RitmeWebClient(private val events: RitmeWebEvents, private val documentStart: Boolean) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        events.onRequest(request.url.toString(), request.method, request.requestHeaders.orEmpty())
        return null // never rewrite the page (spec D9)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (BrowserAddress.navigation(url, request.hasGesture())) {
            Navigation.ALLOW -> false
            Navigation.REFUSE_LOCAL -> {
                events.onLocalAddressRefused()
                true
            }
            Navigation.ASK_EXTERNAL -> {
                if (request.isForMainFrame) events.onExternalApp(url)
                true
            }
            Navigation.BLOCK -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        url?.let(events::onPageStarted)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        // Without document-start scripts the page script goes in once the page is there; the
        // events it listens for from then on still count.
        if (!documentStart && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            view.evaluateJavascript(PageMediaScript.source, null)
        }
        url?.let { events.onHistoryChanged(it, view.canGoBack(), view.canGoForward()) }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let { events.onHistoryChanged(it, view.canGoBack(), view.canGoForward()) }
    }
}

private class RitmeChromeClient(private val events: RitmeWebEvents) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgress(newProgress)
    }

    /** Nothing is granted. Protected media (DRM) marks the page's finds as protected (spec 4.7 table). */
    override fun onPermissionRequest(request: PermissionRequest) {
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) events.onProtectedMedia()
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowFileChooser(view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean = false
}

/**
 * Service workers fetch for their pages outside the WebView's client: their requests are shown to
 * [events] too (detection layer 1), and never changed. Returns how to stop.
 */
@SuppressLint("RequiresFeature")
internal fun watchServiceWorkers(events: RitmeWebEvents): () -> Unit {
    val controller: ServiceWorkerController = if (ritmeProfileSupported()) {
        ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE).serviceWorkerController
    } else {
        ServiceWorkerController.getInstance()
    }
    controller.setServiceWorkerClient(object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            events.onRequest(request.url.toString(), request.method, request.requestHeaders.orEmpty())
            return null
        }
    })
    return { controller.setServiceWorkerClient(object : ServiceWorkerClient() {}) }
}

/**
 * "Hapus data browser Ritme": the profile's cookies and storage, and this view's cache, history and
 * form data. Only with a profile of its own (Decision 13): the default profile also holds the
 * app's other sign-ins.
 */
@SuppressLint("RequiresFeature")
internal fun clearRitmeBrowserData(view: WebView) {
    if (!ritmeProfileSupported()) return
    val profile = ProfileStore.getInstance().getOrCreateProfile(RITME_BROWSER_PROFILE)
    profile.cookieManager.removeAllCookies(null)
    profile.cookieManager.flush()
    profile.webStorage.deleteAllData()
    view.clearCache(true)
    view.clearHistory()
    view.clearFormData()
}
```

- [ ] **Step 3: The screen**

Create `RitmeBrowserScreen.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.musicfy.LocalPlayerAwareWindowInsets
import com.example.musicfy.R
import com.example.musicfy.ritme.browser.RitmeBrowserModel
import com.example.musicfy.ritme.browser.RitmeProbes
import com.example.musicfy.ritme.media.AddressInput
import com.example.musicfy.ritme.media.AddressRefusal
import com.example.musicfy.ritme.media.BrowserAddress
import com.example.musicfy.ritme.video.RitmeFileLength
import com.example.musicfy.ui.component.LocalZoomOutOverlayState
import com.example.musicfy.ui.component.Material3MenuGroup
import com.example.musicfy.ui.component.Material3MenuItemData
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.rememberRitme

/** The browser's route (spec 4.7), reachable only from Ritme's sheets. `url`: typed text, resolved like the address bar. */
const val RitmeBrowserRoutePattern = "ritme/browser?url={url}"

fun ritmeBrowserRoute(url: String? = null): String =
    if (url.isNullOrBlank()) "ritme/browser" else "ritme/browser?url=" + Uri.encode(url)

/**
 * The Ritme browser (spec 4.7, user decision 2026-11-23): a header like the sign-in pages'
 * (WebLoginHeader) holding back, the address field (URL, bare domain or DuckDuckGo keywords; shows
 * the page's address, editable and copyable), reload and a ⋯ menu; then the page. What it finds
 * goes to [RitmeBrowserModel]. Opened in a room it adds to that room and closes when the room is
 * gone; opened outside one (start mode) it starts a room, like "Tambah video".
 */
@Composable
fun RitmeBrowserScreen(navController: NavController, initialText: String?) {
    val ritme = rememberRitme()
    val context = LocalContext.current
    val popup = LocalZoomOutOverlayState.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by ritme.session.state.collectAsState()
    val roomMode = remember { ritme.session.state.value.inRoom }
    val browser = remember {
        val webOrigin = ritme.controller.webOrigin()
        RitmeBrowserModel(
            probe = { url, pageUrl -> RitmeProbes.probe(url, pageUrl, webOrigin) },
            fileLength = { url, referrer -> RitmeFileLength.read(url, referrer) },
            scope = scope,
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(initialText.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canForward by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<AddressRefusal?>(null) }

    // Opened to add to a room that has since ended (or was left): nothing left to add to.
    val roomGone = roomMode && !session.inRoom
    LaunchedEffect(roomGone) {
        if (roomGone) navController.navigateUp()
    }

    fun open(text: String) {
        when (val input = BrowserAddress.resolve(text)) {
            is AddressInput.Open -> {
                problem = null
                keyboard?.hide()
                focusManager.clearFocus()
                webView?.loadUrl(input.url)
            }
            is AddressInput.Refused -> problem = input.reason
        }
    }

    fun goBack() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else navController.navigateUp()
    }

    val events = remember {
        object : RitmeWebEvents {
            override fun onPageStarted(url: String) {
                browser.pageStarted(url)
                pageUrl = url
                if (!editing) address = url
            }

            override fun onHistoryChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) {
                if (url != pageUrl) browser.urlChanged(url)
                pageUrl = url
                canForward = canGoForward
                if (!editing) address = url
            }

            override fun onProgress(percent: Int) {
                progress = percent
            }

            override fun onRequest(url: String, method: String, headers: Map<String, String>) =
                browser.onRequest(url, method, headers)

            override fun onPageReport(message: String, mainFrame: Boolean) = browser.onReport(message, mainFrame)

            override fun onProtectedMedia() = browser.onProtectedMedia()

            override fun onLocalAddressRefused() {
                problem = AddressRefusal.LOCAL
            }

            override fun onExternalApp(url: String) {
                popup.showRitmeExternalAppSheet(context, url)
            }
        }
    }

    DisposableEffect(lifecycle) {
        val stopWatching = watchServiceWorkers(events)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stopWatching()
            webView?.let { view ->
                view.stopLoading()
                view.onPause()
                destroySoon(view)
            }
            webView = null
        }
    }

    BackHandler { goBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
    ) {
        RitmeBrowserHeader(
            address = address,
            onAddressChange = {
                address = it
                problem = null
            },
            onEditingChange = { focused ->
                editing = focused
                if (!focused && pageUrl.isNotEmpty()) address = pageUrl
            },
            onGo = { open(address) },
            onBack = { goBack() },
            onReload = { webView?.reload() },
            onMenu = {
                keyboard?.hide()
                popup.showRitmeBrowserMenu(
                    canGoForward = canForward,
                    canClear = ritmeProfileSupported(),
                    onForward = { webView?.goForward() },
                    onCopy = { copyLink(context, pageUrl) },
                    onOpenElsewhere = { openElsewhere(context, pageUrl) },
                    onClear = {
                        webView?.let(::clearRitmeBrowserData)
                        Toast.makeText(context, R.string.ritme_browser_data_cleared, Toast.LENGTH_SHORT).show()
                    },
                    onClose = { navController.navigateUp() },
                )
            },
        )
        problem?.let { reason ->
            RitmeSecondaryText(
                text = stringResource(addressProblemText(reason)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(modifier = Modifier.height(2.dp).fillMaxWidth()) {
            if (progress < 100) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    createRitmeWebView(viewContext, events).also { view ->
                        RitmeProbes.userAgent = view.settings.userAgentString
                        webView = view
                        when (val start = initialText?.let(BrowserAddress::resolve)) {
                            is AddressInput.Open -> view.loadUrl(start.url)
                            is AddressInput.Refused -> {
                                problem = start.reason
                                view.loadUrl(BrowserAddress.START_URL)
                            }
                            null -> view.loadUrl(BrowserAddress.START_URL)
                        }
                    }
                },
            )
            // RITME_FOUND_PILL (Task 14 replaces this line)
        }
    }
}

/** WebLoginHeader's row (8 × 4 dp, white IconButtons) with the address field in place of the title. */
@Composable
private fun RitmeBrowserHeader(
    address: String,
    onAddressChange: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.ritme_browser_back), tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        SheetTextField(
            value = address,
            onValueChange = onAddressChange,
            placeholder = stringResource(R.string.ritme_browser_address_hint),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onEditingChange(it.isFocused) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        IconButton(onClick = onReload) {
            Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.ritme_browser_reload), tint = Color.White)
        }
        IconButton(onClick = onMenu) {
            Icon(painterResource(R.drawable.more_vert), contentDescription = stringResource(R.string.ritme_browser_menu), tint = Color.White)
        }
    }
}

@StringRes
private fun addressProblemText(reason: AddressRefusal): Int = when (reason) {
    AddressRefusal.EMPTY -> R.string.ritme_browser_refused_empty
    AddressRefusal.SCHEME -> R.string.ritme_browser_refused_scheme
    AddressRefusal.LOCAL -> R.string.ritme_browser_refused_local
    AddressRefusal.CREDENTIALS -> R.string.ritme_browser_refused_credentials
    AddressRefusal.MALFORMED -> R.string.ritme_browser_refused_malformed
}

/** The ⋯ menu: the same menu rows as the room sheet (Material3MenuGroup). Forward is dimmed with nothing ahead. */
private fun PopupSheetState.showRitmeBrowserMenu(
    canGoForward: Boolean,
    canClear: Boolean,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle {
        fun then(action: () -> Unit): () -> Unit = {
            handle?.dismiss()
            action()
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Material3MenuGroup(
                items = listOfNotNull(
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.arrow_forward), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_forward)) },
                        onClick = if (canGoForward) then(onForward) else null,
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.content_copy), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_copy_link)) },
                        onClick = then(onCopy),
                    ),
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.globe), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_open_elsewhere)) },
                        onClick = then(onOpenElsewhere),
                    ),
                    if (canClear) {
                        Material3MenuItemData(
                            icon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                            title = { Text(stringResource(R.string.ritme_browser_clear_data)) },
                            onClick = then(onClear),
                        )
                    } else {
                        null
                    },
                    Material3MenuItemData(
                        icon = { Icon(painterResource(R.drawable.close), contentDescription = null) },
                        title = { Text(stringResource(R.string.ritme_browser_close)) },
                        onClick = then(onClose),
                    ),
                ),
            )
        }
    }
}

/** A page asked for another app (`intent:`, `market:`, …) after a tap: ask first (spec 4.7). */
private fun PopupSheetState.showRitmeExternalAppSheet(context: Context, url: String) {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPillButton(
                    text = stringResource(R.string.ritme_cancel),
                    onClick = { handle?.dismiss() },
                    modifier = Modifier.weight(1f),
                )
                SheetPillButton(
                    text = stringResource(R.string.ritme_browser_open_app),
                    onClick = {
                        handle?.dismiss()
                        openExternal(context, url)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            SheetLabel(stringResource(R.string.ritme_browser_open_app_title))
            Spacer(Modifier.height(8.dp))
            RitmeSecondaryText(stringResource(R.string.ritme_browser_open_app_body))
        }
    }
}

/** Never to a named component or selector, browsable apps only. */
private fun openExternal(context: Context, url: String) {
    val intent = runCatching {
        if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, url.toUri())
    }.getOrNull() ?: return
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun copyLink(context: Context, url: String) {
    if (url.isEmpty()) return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
    // Android 13+ confirms a copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.ritme_browser_link_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The page in the user's own browser, through the chooser (so Musicfy's own link handling is not picked by itself). */
private fun openElsewhere(context: Context, url: String) {
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return
    val view = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Lets Compose detach the view first; destroying an attached WebView logs errors (as CaptureWebView). */
private fun destroySoon(view: WebView) {
    Handler(Looper.getMainLooper()).postDelayed({ view.destroy() }, 500)
}
```

- [ ] **Step 4: The route**

In `NavigationBuilder.kt`, right after the `composable(com.example.musicfy.ui.screens.settings.importsync.YouTubeLoginRoute) { … }` block, add:

```kotlin
    composable(
        route = com.example.musicfy.ui.screens.ritme.browser.RitmeBrowserRoutePattern,
        arguments = listOf(
            navArgument("url") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        com.example.musicfy.ui.screens.ritme.browser.RitmeBrowserScreen(
            navController = navController,
            initialText = entry.arguments?.getString("url"),
        )
    }
```

- [ ] **Step 5: Compile**

Run: `g :app:compileUniversalGmsDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `request.requestHeaders.orEmpty()` is flagged as always non-null, keep it: the platform type can be null on old WebViews.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser app/src/main/kotlin/com/example/musicfy/ui/screens/NavigationBuilder.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml
git commit -m "feat(ritme-android): Ritme browser screen with its own profile, address bar and media detection" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: The found pill, the found list and the mandatory confirmation

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeFoundSheet.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/browser/RitmeBrowserScreen.kt` (the `// RITME_FOUND_PILL` line, imports)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt` (`AddVideoRow` → `internal`, `canAddVideos`)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`

**Interfaces:**
- Consumes: `RitmeBrowserModel`, `FoundItem`, `ProbeState`, `RitmeBrowserEntries`, `BrowserNote` (Task 12); `Refusal`, `ProbeVerdict`, `ExpiryHints`, `PageFindings`, `Candidate`; `RitmeController.addEntries`, `startOrAddEntries`, `openAddVideo`; `RitmeChip`, `AddVideoRow`, `RitmeSecondaryText`, `MenuRowSurface`.
- Produces: `internal fun canAddVideos(session: RitmeUiState, roomMode: Boolean): Boolean` (in `RitmeAddVideoSheet.kt`); `internal fun AddVideoRow(…)` (was private); `internal fun PopupSheetState.showRitmeFoundSheet(ritme: RitmeUi, browser: RitmeBrowserModel, roomMode: Boolean, onYouTube: (videoId: String) -> Unit, onStarted: () -> Unit)`.

- [ ] **Step 1: Share the row and the permission rule**

In `RitmeAddVideoSheet.kt`:
- change `private fun AddVideoRow(` to `internal fun AddVideoRow(`;
- add, before `@Stable internal class RitmeAddVideoForm`:

```kotlin
/**
 * Whether video-adding buttons may send ("Tambah video", the browser's confirmation): in room mode
 * only in that room with add rights; in start mode outside a room, or in a room joined since with
 * add rights (that room then gets the entry).
 */
internal fun canAddVideos(session: RitmeUiState, roomMode: Boolean): Boolean {
    if (!session.inRoom) return !roomMode
    val room = session.room ?: return false
    return RitmePermissions.canAdd(room, session.memberId)
}
```

- replace the body of `RitmeAddVideoForm.canSend` with `= canAddVideos(session, roomMode)`:

```kotlin
    fun canSend(session: RitmeUiState): Boolean = canAddVideos(session, roomMode)
```

- [ ] **Step 2: Strings**

English (before `</resources>`):

```xml
    <plurals name="ritme_browser_found">
        <item quantity="one">%d video found</item>
        <item quantity="other">%d videos found</item>
    </plurals>
    <string name="ritme_browser_blob_pill">Video can\'t be shared</string>
    <string name="ritme_browser_found_title">Videos on this page</string>
    <string name="ritme_browser_youtube_row">This YouTube video</string>
    <string name="ritme_browser_blob_only">The video on this page can\'t be shared</string>
    <string name="ritme_browser_none">No video found yet. Play the video on the page first.</string>
    <string name="ritme_browser_checking">Checking…</string>
    <string name="ritme_browser_badge_drm">Protected</string>
    <string name="ritme_browser_badge_login">Needs sign-in</string>
    <string name="ritme_browser_badge_failed">Check failed</string>
    <string name="ritme_browser_badge_length">Length unknown</string>
    <string name="ritme_browser_badge_app_only">App only</string>
    <string name="ritme_browser_badge_not_web">Can\'t play on the web</string>
    <string name="ritme_browser_confirm_title">Add this video?</string>
    <string name="ritme_browser_title_label">Title</string>
    <string name="ritme_browser_source">Source: %1$s</string>
    <string name="ritme_browser_visible_to_room">Everyone in the room will see this link.</string>
    <string name="ritme_browser_note_sensitive">This link may contain a private access key.</string>
    <plurals name="ritme_browser_note_expires">
        <item quantity="one">The link expires in about %d minute.</item>
        <item quantity="other">The link expires in about %d minutes.</item>
    </plurals>
    <string name="ritme_browser_note_expired">This link seems to have expired already.</string>
    <string name="ritme_browser_note_ip">It may only work on your network.</string>
    <string name="ritme_browser_note_app_only">Plays in the Android app only, not on the web.</string>
    <string name="ritme_browser_note_not_web">Can\'t play on the web.</string>
    <string name="ritme_browser_note_key">Its key server may need a sign-in.</string>
    <string name="ritme_browser_refusal_failed">This video couldn\'t be checked. Try again, or play it on the page first.</string>
    <string name="ritme_browser_refusal_not_media">This isn\'t a video file</string>
```

Indonesian (before `</resources>`):

```xml
    <plurals name="ritme_browser_found">
        <item quantity="other">%d video ditemukan</item>
    </plurals>
    <string name="ritme_browser_blob_pill">Video tidak bisa dibagikan</string>
    <string name="ritme_browser_found_title">Video di halaman ini</string>
    <string name="ritme_browser_youtube_row">Video YouTube ini</string>
    <string name="ritme_browser_blob_only">Video di halaman ini tidak bisa dibagikan</string>
    <string name="ritme_browser_none">Belum ada video yang ditemukan. Putar dulu videonya di halaman.</string>
    <string name="ritme_browser_checking">Memeriksa…</string>
    <string name="ritme_browser_badge_drm">Berproteksi</string>
    <string name="ritme_browser_badge_login">Butuh login</string>
    <string name="ritme_browser_badge_failed">Gagal diperiksa</string>
    <string name="ritme_browser_badge_length">Panjang tidak terbaca</string>
    <string name="ritme_browser_badge_app_only">Hanya di aplikasi</string>
    <string name="ritme_browser_badge_not_web">Tidak bisa diputar di web</string>
    <string name="ritme_browser_confirm_title">Tambahkan video ini?</string>
    <string name="ritme_browser_title_label">Judul</string>
    <string name="ritme_browser_source">Sumber: %1$s</string>
    <string name="ritme_browser_visible_to_room">Link ini akan terlihat oleh semua anggota room.</string>
    <string name="ritme_browser_note_sensitive">Link ini mungkin berisi kunci akses pribadi.</string>
    <plurals name="ritme_browser_note_expires">
        <item quantity="other">Link kedaluwarsa ±%d menit lagi.</item>
    </plurals>
    <string name="ritme_browser_note_expired">Link ini sepertinya sudah kedaluwarsa.</string>
    <string name="ritme_browser_note_ip">Mungkin terkunci ke jaringanmu.</string>
    <string name="ritme_browser_note_app_only">Hanya bisa diputar di aplikasi Android, tidak di web.</string>
    <string name="ritme_browser_note_not_web">Tidak bisa diputar di web.</string>
    <string name="ritme_browser_note_key">Server kuncinya mungkin butuh login.</string>
    <string name="ritme_browser_refusal_failed">Video ini tidak bisa diperiksa. Coba lagi, atau putar dulu videonya di halaman.</string>
    <string name="ritme_browser_refusal_not_media">Ini bukan file video</string>
```

- [ ] **Step 3: The sheets**

Create `RitmeFoundSheet.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme.browser

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.musicfy.R
import com.example.musicfy.ritme.browser.BrowserNote
import com.example.musicfy.ritme.browser.FoundItem
import com.example.musicfy.ritme.browser.ProbeState
import com.example.musicfy.ritme.browser.RitmeBrowserEntries
import com.example.musicfy.ritme.browser.RitmeBrowserModel
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.ExpiryHints
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ui.component.PopupSheetHandle
import com.example.musicfy.ui.component.PopupSheetState
import com.example.musicfy.ui.component.SheetLabel
import com.example.musicfy.ui.component.SheetPillButton
import com.example.musicfy.ui.component.SheetTextField
import com.example.musicfy.ui.screens.ritme.AddVideoRow
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.RitmeUi
import com.example.musicfy.ui.screens.ritme.canAddVideos
import com.example.musicfy.utils.makeTimeString

/** One found list at a time: tapping the pill twice must not stack copies. Main thread only. */
private var foundSheetOpen = false

/**
 * The found list (spec 4.7 step 5): the page's YouTube video (if any) first, then every find with
 * a 16:9 placeholder (no picture is loaded from the page), its domain, type, length or LIVE,
 * height and the check's verdict. The checks start when the list opens. A row opens the
 * mandatory confirmation; the YouTube row opens "Tambah video" with the watch link.
 */
internal fun PopupSheetState.showRitmeFoundSheet(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    roomMode: Boolean,
    onYouTube: (videoId: String) -> Unit,
    onStarted: () -> Unit,
) {
    if (foundSheetOpen) return
    foundSheetOpen = true
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(onClosed = { foundSheetOpen = false }) {
        RitmeFoundContent(
            browser = browser,
            onYouTube = { id ->
                handle?.dismiss()
                onYouTube(id)
            },
            onChoose = { url ->
                showRitmeConfirmSheet(
                    ritme = ritme,
                    browser = browser,
                    url = url,
                    roomMode = roomMode,
                    onAdded = { handle?.dismiss() },
                    onStarted = {
                        handle?.dismiss()
                        onStarted()
                    },
                )
            },
        )
    }
}

@Composable
private fun RitmeFoundContent(browser: RitmeBrowserModel, onYouTube: (String) -> Unit, onChoose: (String) -> Unit) {
    val findings by browser.findings.collectAsState()
    val probes by browser.probes.collectAsState()
    // Spec 4.7 step 4: checked when the list opens; finds that arrive while it is open are checked too.
    LaunchedEffect(findings) { browser.probeAll() }
    val items = remember(findings, probes) { browser.items(findings, probes) }
    val chooseLabel = stringResource(R.string.ritme_add_video_choose)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(R.string.ritme_browser_found_title))
        Spacer(Modifier.height(8.dp))
        findings.youTubeVideoId?.let { id ->
            AddVideoRow(
                title = stringResource(R.string.ritme_browser_youtube_row),
                subtitle = "YouTube",
                thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                isUrl = false,
                onClickLabel = chooseLabel,
                onClick = { onYouTube(id) },
            )
        }
        items.forEach { item ->
            AddVideoRow(
                title = RitmeBrowserEntries.rowTitle(item.candidate, findings),
                subtitle = foundSubtitle(item),
                // Never a picture from the page (spec 4.7, R8): the web_link placeholder.
                thumbnailUrl = null,
                isUrl = true,
                onClickLabel = chooseLabel,
                onClick = { onChoose(item.candidate.url) },
            )
        }
        if (items.isEmpty() && findings.youTubeVideoId == null) {
            RitmeSecondaryText(
                text = stringResource(if (findings.blobOnly) R.string.ritme_browser_blob_only else R.string.ritme_browser_none),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** "domain · HLS · 12:34 · 720p · Hanya di aplikasi": where from, what it is, and what the check said. */
@Composable
private fun foundSubtitle(item: FoundItem): String {
    val candidate = item.candidate
    val verdict = (item.state as? ProbeState.Done)?.verdict
    val parts = mutableListOf<String>()
    RitmeBrowserEntries.domainOf(candidate.url).takeIf { it.isNotEmpty() }?.let(parts::add)
    RitmeBrowserEntries.typeLabel(candidate.url, verdict?.streamType ?: candidate.streamType)?.let(parts::add)
    val live = verdict?.live ?: candidate.pageLive
    val length = verdict?.durationMs ?: candidate.pageDurationMs
    if (live) {
        parts += stringResource(R.string.ritme_tag_live)
    } else if (length != null && length > 0) {
        parts += makeTimeString(length)
    }
    (verdict?.height ?: candidate.height.takeIf { it > 0 })?.let { parts += "${it}p" }
    val status = when {
        item.state is ProbeState.Checking -> R.string.ritme_browser_checking
        verdict != null -> badgeText(verdict)
        else -> null
    }
    if (status != null) parts += stringResource(status)
    return parts.joinToString(" · ")
}

@StringRes
private fun badgeText(verdict: ProbeVerdict): Int? = when (verdict.refusal) {
    Refusal.DRM -> R.string.ritme_browser_badge_drm
    Refusal.NEEDS_LOGIN -> R.string.ritme_browser_badge_login
    Refusal.FAILED -> R.string.ritme_browser_badge_failed
    Refusal.LENGTH_UNKNOWN -> R.string.ritme_browser_badge_length
    Refusal.NOT_MEDIA -> null
    null -> when {
        verdict.needsReferrer -> R.string.ritme_browser_badge_app_only
        !verdict.webPlayable -> R.string.ritme_browser_badge_not_web
        else -> null
    }
}

@StringRes
private fun refusalText(refusal: Refusal): Int = when (refusal) {
    Refusal.DRM -> R.string.ritme_add_video_protected
    Refusal.NEEDS_LOGIN -> R.string.ritme_add_video_needs_login
    Refusal.FAILED -> R.string.ritme_browser_refusal_failed
    Refusal.LENGTH_UNKNOWN -> R.string.ritme_add_video_length_unreadable
    Refusal.NOT_MEDIA -> R.string.ritme_browser_refusal_not_media
}

/** The confirmation's editable title, shared by its content and its pinned buttons. */
@Stable
private class RitmeConfirmForm(initialTitle: String) {
    var title by mutableStateOf(initialTitle)
}

/**
 * The confirmation (spec 4.7 step 6, mandatory): the title (editable, default og:title →
 * document.title → file name), the source (og:site_name or the domain) sent as the artist, the
 * check's verdict, and the warnings, "Link ini akan terlihat oleh semua anggota room." always.
 * Adding happens only from its buttons.
 */
private fun PopupSheetState.showRitmeConfirmSheet(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    url: String,
    roomMode: Boolean,
    onAdded: () -> Unit,
    onStarted: () -> Unit,
) {
    val findings = browser.findings.value
    val candidate = findings.candidates.firstOrNull { it.url == url } ?: return
    val form = RitmeConfirmForm(RitmeBrowserEntries.defaultTitle(candidate, findings))
    val source = RitmeBrowserEntries.source(findings, candidate)
    val pageUrl = findings.pageUrl
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            RitmeConfirmButtons(
                ritme = ritme,
                browser = browser,
                candidate = candidate,
                form = form,
                source = source,
                pageUrl = pageUrl,
                roomMode = roomMode,
                onAdded = {
                    handle?.dismiss()
                    onAdded()
                },
                onStarted = {
                    handle?.dismiss()
                    onStarted()
                },
            )
        },
    ) {
        RitmeConfirmContent(browser = browser, candidate = candidate, form = form, source = source)
    }
}

@Composable
private fun RitmeConfirmContent(browser: RitmeBrowserModel, candidate: Candidate, form: RitmeConfirmForm, source: String) {
    val probes by browser.probes.collectAsState()
    val verdict = (probes[candidate.url] as? ProbeState.Done)?.verdict
    val keyboard = LocalSoftwareKeyboardController.current
    val now = remember { System.currentTimeMillis() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SheetLabel(stringResource(R.string.ritme_browser_confirm_title))
        Spacer(Modifier.height(12.dp))
        AddVideoRow(
            title = form.title.ifBlank { source },
            subtitle = foundSubtitle(FoundItem(candidate, probes[candidate.url])),
            thumbnailUrl = null,
            isUrl = true,
            onClickLabel = null,
            onClick = null,
        )
        Spacer(Modifier.height(18.dp))
        SheetLabel(stringResource(R.string.ritme_browser_title_label))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = form.title,
            onValueChange = { form.title = it.take(RitmeEntries.MAX_TEXT) },
            // What a blank title becomes: the source.
            placeholder = source,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        Spacer(Modifier.height(12.dp))
        RitmeSecondaryText(stringResource(R.string.ritme_browser_source, source))
        Spacer(Modifier.height(6.dp))
        RitmeSecondaryText(stringResource(R.string.ritme_browser_visible_to_room))
        when {
            verdict == null -> NoteLine(stringResource(R.string.ritme_browser_checking))
            verdict.refusal != null -> NoteLine(stringResource(refusalText(verdict.refusal)), error = true)
            else -> RitmeBrowserEntries.notes(candidate.url, verdict, now).forEach { NoteLine(noteText(it, candidate.url, now)) }
        }
    }
}

@Composable
private fun NoteLine(text: String, error: Boolean = false) {
    Spacer(Modifier.height(6.dp))
    val modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (error) {
        RitmeSecondaryText(text = text, color = MaterialTheme.colorScheme.error, modifier = modifier)
    } else {
        RitmeSecondaryText(text = text, modifier = modifier)
    }
}

@Composable
private fun noteText(note: BrowserNote, url: String, nowMs: Long): String = when (note) {
    BrowserNote.SENSITIVE -> stringResource(R.string.ritme_browser_note_sensitive)
    BrowserNote.EXPIRES -> {
        val minutes = (ExpiryHints.of(url, nowMs).expiresInMinutes ?: 1L).coerceIn(1L, 100_000L).toInt()
        pluralStringResource(R.plurals.ritme_browser_note_expires, minutes, minutes)
    }
    BrowserNote.EXPIRED -> stringResource(R.string.ritme_browser_note_expired)
    BrowserNote.IP_LOCKED -> stringResource(R.string.ritme_browser_note_ip)
    BrowserNote.APP_ONLY -> stringResource(R.string.ritme_browser_note_app_only)
    BrowserNote.NOT_ON_WEB -> stringResource(R.string.ritme_browser_note_not_web)
    BrowserNote.KEY_MAY_NEED_LOGIN -> stringResource(R.string.ritme_browser_note_key)
}

/**
 * The confirmation's pinned buttons, the sheets' pills. Not shown while the check runs (nothing to
 * tap yet) or where this device may not add. A failed check or an unreadable length: "Coba lagi".
 * DRM or a sign-in (U12): the add pills stay, dimmed, and the sheet says why. In a room "Tambah"
 * and "Putar berikutnya"; outside one "Mulai Ritme dengan video ini" (start mode, like "Tambah video").
 */
@Composable
private fun RitmeConfirmButtons(
    ritme: RitmeUi,
    browser: RitmeBrowserModel,
    candidate: Candidate,
    form: RitmeConfirmForm,
    source: String,
    pageUrl: String?,
    roomMode: Boolean,
    onAdded: () -> Unit,
    onStarted: () -> Unit,
) {
    val session by ritme.session.state.collectAsState()
    val probes by browser.probes.collectAsState()
    if (!canAddVideos(session, roomMode)) return
    val state = probes[candidate.url]
    val verdict = (state as? ProbeState.Done)?.verdict
    if (verdict == null) {
        // Not checked yet (opened before the list's checks reached it): check it now.
        if (state == null) LaunchedEffect(candidate.url) { browser.retry(candidate.url) }
        return
    }
    if (verdict.refusal == Refusal.FAILED || verdict.refusal == Refusal.LENGTH_UNKNOWN) {
        SheetPillButton(text = stringResource(R.string.ritme_retry), onClick = { browser.retry(candidate.url) })
        return
    }
    val entry = RitmeBrowserEntries.entryOf(candidate, verdict, form.title, source, pageUrl)
    val enabled = entry != null
    if (session.inRoom) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPillButton(
                text = stringResource(R.string.ritme_add),
                enabled = enabled,
                onClick = {
                    entry?.let {
                        ritme.controller.addEntries(listOf(it), playNext = false)
                        onAdded()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SheetPillButton(
                text = stringResource(R.string.ritme_play_next_button),
                enabled = enabled,
                onClick = {
                    entry?.let {
                        ritme.controller.addEntries(listOf(it), playNext = true)
                        onAdded()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        SheetPillButton(
            text = stringResource(R.string.ritme_start_with_video),
            enabled = enabled,
            onClick = {
                entry?.let {
                    // The start sheet goes in underneath while these leave (PopupSheetState).
                    onStarted()
                    ritme.controller.startOrAddEntries(listOf(it))
                }
            },
        )
    }
}
```

- [ ] **Step 4: The pill**

In `RitmeBrowserScreen.kt` replace the line `// RITME_FOUND_PILL (Task 14 replaces this line)` with:

```kotlin
            val findings by browser.findings.collectAsState()
            val probes by browser.probes.collectAsState()
            val offers = browser.offers(findings, probes)
            // The Ritme pill's chip on the floating pill's solid fill (MainActivity's RitmePill above the mini player).
            if (offers > 0 || findings.blobOnly) {
                RitmeChip(
                    text = if (offers > 0) {
                        pluralStringResource(R.plurals.ritme_browser_found, offers, offers)
                    } else {
                        stringResource(R.string.ritme_browser_blob_pill)
                    },
                    containerColor = MenuRowSurface,
                    icon = R.drawable.play,
                    onClick = {
                        keyboard?.hide()
                        popup.showRitmeFoundSheet(
                            ritme = ritme,
                            browser = browser,
                            roomMode = roomMode,
                            // Never a URL entry: the existing YOUTUBE_VIDEO path with its preview.
                            onYouTube = { id -> ritme.controller.openAddVideo("https://www.youtube.com/watch?v=$id") },
                            // Start mode: the start sheet takes over; the browser's job is done.
                            onStarted = { navController.navigateUp() },
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
```

and add the imports `androidx.compose.ui.res.pluralStringResource`, `com.example.musicfy.ui.player.menu.MenuRowSurface`, `com.example.musicfy.ui.screens.ritme.RitmeChip`.

- [ ] **Step 5: Compile and run the Ritme tests**

Run: `g :app:compileUniversalGmsDebugKotlin` then `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'`
Expected: BUILD SUCCESSFUL both times.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ui/screens/ritme app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml
git commit -m "feat(ritme-android): found pill, found list and the mandatory confirmation in the Ritme browser" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

# Phase E: Entry points and wrap-up

### Task 15: "Cari video di web", "Buka di browser Ritme", and the controller

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt` (`Kind.Browser`, `openBrowser`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt` (`RitmeSheetOpener`, `showRitmeRoomSheet`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt` (`RitmeRoomContent`, `RitmeQueuePage`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt` (`isPageLink`, the pinned button)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt`

**Interfaces:**
- Consumes: `ritmeBrowserRoute` (Task 13), `addAllowed` (private in the controller), `LocalPlayerBottomSheetState`.
- Produces: `RitmeSheetRequest.Kind.Browser(val url: String? = null)`; `RitmeController.openBrowser(url: String? = null)`; `RitmeRoomContent(…, onSearchWeb: () -> Unit, …)`; `RitmeAddVideoForm.isPageLink`.

- [ ] **Step 1: Write the failing tests**

In `RitmeControllerTest.kt` add:

```kotlin
    @Test
    fun aGuestWhoMayNotAddGetsAToastInsteadOfTheBrowser() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.openBrowser("https://example.com/film")
        assertNull(controller.sheetRequest.value)
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN, videos = true)), toasts.shown)
    }

    @Test
    fun theBrowserOpensInARoomAndInStartMode() {
        controller.openBrowser("https://example.com/film")
        assertEquals(RitmeSheetRequest.Kind.Browser("https://example.com/film"), controller.sheetRequest.value?.kind)
        inRoom(playing)
        controller.openBrowser()
        assertEquals(RitmeSheetRequest.Kind.Browser(), controller.sheetRequest.value?.kind)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.RitmeControllerTest'`
Expected: compilation FAILS (`Unresolved reference: openBrowser`, `Browser`).

- [ ] **Step 3: The controller**

In `RitmeSheetRequest.Kind` (in `RitmeController.kt`), after `data class AddVideo(…)` add:

```kotlin
        /** The Ritme browser (spec 4.7), opening [url] (a page link, or typed text) when given. */
        data class Browser(val url: String? = null) : Kind
```

After `openAddVideo` add:

```kotlin
    /**
     * The in-app browser (spec 4.7), opening [url] when given (a page link from "Tambah video" or a
     * share). The same rule as "Tambah video": in a room only for members who may add (else the
     * ADD_FORBIDDEN toast); outside one it opens in start mode (its confirmation starts a room).
     */
    fun openBrowser(url: String? = null) {
        val state = session.state.value
        val room = state.room
        if (state.inRoom && room != null && !addAllowed(room, state.memberId, videos = true)) return
        request(RitmeSheetRequest.Kind.Browser(url))
    }
```

- [ ] **Step 4: The opener navigates**

In `RitmeUi.kt`, in `RitmeSheetOpener`, after `val popup = LocalZoomOutOverlayState.current` add `val playerSheet = LocalPlayerBottomSheetState.current`, and in the `when (val kind = current.kind)` add:

```kotlin
            is RitmeSheetRequest.Kind.Browser -> {
                // The browser is a page under the player: an open player steps down first, as for "Add songs".
                playerSheet?.let { if (it.isExpanded) it.collapseSoft() }
                navController.navigate(com.example.musicfy.ui.screens.ritme.browser.ritmeBrowserRoute(kind.url)) {
                    launchSingleTop = true
                }
            }
```

In `showRitmeRoomSheet(ritme, openSearch)`, in the `RitmeRoomContent(…)` call, after the `onAddVideo = { … }` argument add:

```kotlin
            onSearchWeb = {
                // The browser opens under this sheet's exit, like "Add video".
                handle?.dismiss()
                ritme.controller.openBrowser()
            },
```

- [ ] **Step 5: The room sheet row**

In `RitmeRoomSheet.kt`:
- in `RitmeRoomContent`'s parameters, after `onAddVideo: () -> Unit,` add `onSearchWeb: () -> Unit,`;
- in the `if (RitmePermissions.canAdd(room, me))` group, after the "Add video" `Material3MenuItemData(…)`, add:

```kotlin
                            Material3MenuItemData(
                                icon = { Icon(painterResource(R.drawable.globe), contentDescription = null) },
                                title = { Text(stringResource(R.string.ritme_search_web)) },
                                onClick = onSearchWeb,
                            ),
```

- in `RitmeQueuePage`'s `RitmeRoomContent(…)` call, after `onAddVideo = { ritme.controller.openAddVideo() },` add:

```kotlin
                // The queue page closes; the opener lowers the player and opens the browser page.
                onSearchWeb = {
                    onClose()
                    ritme.controller.openBrowser()
                },
```

- [ ] **Step 6: "Buka di browser Ritme" in "Tambah video"**

In `RitmeAddVideoSheet.kt`, in `RitmeAddVideoForm`, after `val canRetry …` add:

```kotlin
    /** A page link, not a file (spec 4.6): the pinned button opens it in the Ritme browser. */
    val isPageLink: Boolean get() = (state as? AddVideoState.Problem)?.kind == AddVideoProblem.PAGE
```

In `RitmeAddVideoButtons`, right after the `if (form.canRetry) { … return }` block, add:

```kotlin
    if (form.isPageLink) {
        // The page's video may be found by the in-app browser (spec 4.6, 4.7); in start mode too.
        SheetPillButton(
            text = stringResource(R.string.ritme_open_in_browser),
            onClick = {
                onDone()
                ritme.controller.openBrowser(form.text.trim())
            },
        )
        return
    }
```

(This also covers a non-video link shared to "Tambah ke Ritme": the share opens this sheet with the link, it is a page, and this button offers the browser, spec 4.6.)

- [ ] **Step 7: Strings**

English: `<string name="ritme_search_web">Search the web for videos</string>` and `<string name="ritme_open_in_browser">Open in the Ritme browser</string>`.
Indonesian: `<string name="ritme_search_web">Cari video di web</string>` and `<string name="ritme_open_in_browser">Buka di browser Ritme</string>`.

- [ ] **Step 8: Run the tests and assemble the gms flavor**

Run: `g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'` then `g :app:assembleUniversalGmsDebug`
Expected: BUILD SUCCESSFUL both times.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt
git commit -m "feat(ritme-android): open the Ritme browser from the room sheet and from Add video page links" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Full verification and follow-ups

**Files:**
- Modify: `docs/superpowers/backlog.md`

- [ ] **Step 1: Run everything this plan touched (one Gradle at a time)**

```bash
g :modules:ritme-protocol:test
g :modules:ritme-media:test
g -p ritme-server test
g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'
g :app:assembleUniversalGmsDebug
w npm test && w npm run typecheck && w npm run build && w npm run size
```

Expected: every command succeeds; `npm run size` reports the budget held. Only the gms flavor is assembled (Global Constraints).

- [ ] **Step 2: Note the follow-ups**

Append to `docs/superpowers/backlog.md`:

```markdown
## 2026-11-24: Follow-up browser Ritme (#3)

- **Desktop (JCEF):** pakai `:modules:ritme-media` dan `page-media.js` yang sama (spec §5):
  `CefResourceRequestHandler.onBeforeResourceLoad` = lapis 1, `onResourceResponse` memberi
  Content-Type asli, `MediaProber` dengan klien HTTP desktop sendiri.
- **EME salah tolak:** kalau ada situs video tanpa DRM yang tertolak "Berproteksi" karena pemutarnya
  memanggil `requestMediaKeySystemAccess` hanya untuk cek dukungan, ubah aturan: tolak hanya kandidat
  yang tidak terbukti bersih oleh probe.
- **Hapus data tanpa profil WebView:** di WebView lama (tanpa `MULTI_PROFILE`) baris "Hapus data
  browser Ritme" disembunyikan. Kalau perlu, hapus cookie per origin yang pernah dibuka browser Ritme.
- **Request sub-resource ke alamat lokal:** browser tidak memblokir gambar/skrip halaman ke LAN
  (interception selalu `null`, D9). Pertimbangkan Private Network Access WebView kalau tersedia.
- **Sinkron live lewat PROGRAM-DATE-TIME** dan **PiP** tetap di daftar lanjutan spec §10.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/backlog.md
git commit -m "docs: Ritme browser follow-ups" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Daftar cek manual di perangkat (untuk pengguna)

Siapkan 2 HP Android dengan build ini dan 1 browser (web Ritme). Satu HP jadi host. Kalau bisa, satu HP memakai WebView terbaru dan satu lagi WebView yang lebih lama.

**Masuk ke browser**

- [ ] **Room sheet → "Cari video di web"** (ikon globe, tepat di bawah "Tambah video") membuka browser Ritme dengan halaman DuckDuckGo. Dari halaman antrean di pemutar juga bisa, dan pemutar turun dulu.
- [ ] **Tamu saat `guestsCanAdd` mati:** baris "Cari video di web" tidak tampil.
- [ ] **"Tambah video" → tempel link halaman biasa** (misalnya artikel berita berisi video): muncul "Link ini halaman web, bukan file video." dan tombol "Buka di browser Ritme" → browser membuka halaman itu.
- [ ] **Share dari Chrome sebuah halaman → "Tambah ke Ritme":** sheet "Tambah video" dengan pesan halaman web dan tombol "Buka di browser Ritme". Di luar room juga, lalu di akhir alurnya tombolnya "Mulai Ritme dengan video ini".
- [ ] **Room diakhiri host saat browser terbuka:** browser tertutup sendiri.

**Address bar**

- [ ] Ketik `https://…` lengkap → halaman terbuka. Ketik `example.com/video` → terbuka sebagai `https://example.com/video`. Ketik kata kunci (misalnya `film pendek indonesia`) → hasil DuckDuckGo.
- [ ] Address bar menampilkan URL halaman yang sedang dibuka, berubah saat pindah halaman (termasuk di situs seperti YouTube yang tidak memuat ulang halaman). Teksnya bisa disunting, dipilih, dan disalin. Menu ⋯ → "Salin link" menyalin URL.
- [ ] Tombol kembali (panah kiri dan tombol Back HP): mundur di riwayat halaman, lalu keluar dari browser. Tombol muat ulang bekerja. Menu ⋯ → "Maju" maju lagi, dan redup kalau tidak ada halaman berikutnya.
- [ ] **Ditolak dengan pesan jelas:** `javascript:alert(1)`, `file:///sdcard/`, `content://media/…`, `intent://…`, `192.168.1.1`, `localhost:8080`, `http://10.0.0.1`. Halaman yang sedang terbuka tidak berubah.
- [ ] **Link di halaman ke alamat lokal** (misalnya halaman uji yang menaut ke `http://192.168.1.1/`): tidak dibuka, muncul pesan alamat lokal.
- [ ] **Link ke aplikasi lain** (misalnya tombol "Buka di aplikasi" atau tautan Play Store): muncul sheet "Buka aplikasi lain?" dengan "Batal" dan "Buka". Tanpa ketukan (dibuka otomatis oleh skrip) tidak terjadi apa-apa.
- [ ] Menu ⋯ → "Buka di browser lain" menawarkan pilihan browser. "Tutup browser" keluar.
- [ ] Unduhan diabaikan. Popup iklan tidak membuka jendela baru.

**Menemukan video**

- [ ] **Situs dengan mp4 biasa:** putar videonya di halaman → pill "1 video ditemukan" muncul di bawah → daftar: "Memeriksa…" lalu "domain · MP4 · durasi · 720p" → ketuk → konfirmasi → "Tambah" → semua anggota (Android dan web) memutarnya, judulnya sama.
- [ ] **HLS VOD (hls.js):** daftar menunjukkan HLS dan durasi. Hanya master playlist yang muncul (bukan tiap resolusi). Di room, jeda lalu putar lagi: lanjut dari posisi terakhir, bukan dari awal.
- [ ] **HLS live:** tertulis LIVE. Semua HP mulai di siaran terkini.
- [ ] **HLS tanpa CORS:** lencana "Tidak bisa diputar di web". Web menampilkan state gagal, Android tetap memutar.
- [ ] **Situs yang mengunci Referer:** lencana "Hanya di aplikasi". HP anggota lain tetap memutar.
- [ ] **Situs DRM (Netflix, Disney+, Prime Video, Vidio premium):** "Berproteksi" dan tombol "Tambah" redup, dengan "Video berproteksi tidak bisa dibagikan".
- [ ] **Situs yang butuh login:** "Butuh login", tombol redup, dengan "Butuh login di situs ini, jadi tidak bisa diputar anggota lain".
- [ ] **Situs video biasa yang tidak ber-DRM tapi tertolak "Berproteksi":** catat nama situsnya (kemungkinan salah tolak dari cek EME, lihat backlog).
- [ ] **Link bertoken atau berwaktu** (`token=`, `expires=`): peringatan "Link ini mungkin berisi kunci akses pribadi." dan "Link kedaluwarsa ±N menit lagi.".
- [ ] **Halaman video YouTube** di browser Ritme: pill dan baris "Video YouTube ini" → sheet "Tambah video" dengan pratinjau → masuk sebagai video YouTube (bukan link URL).
- [ ] **Halaman berisi embed YouTube** (blog atau berita): juga muncul "Video YouTube ini".
- [ ] **Halaman yang hanya memutar `blob:`** tanpa manifest terlihat: pill "Video tidak bisa dibagikan", daftar menjelaskan "Video di halaman ini tidak bisa dibagikan".
- [ ] **Iklan pre-roll:** video iklan dari host iklan tidak muncul. Video utama yang besar ada di urutan pertama.
- [ ] **Konfirmasi:** judul bisa diubah dan dipakai di antrean. Sumber = nama situs (atau domain). "Link ini akan terlihat oleh semua anggota room." selalu ada. Antrean tidak menampilkan gambar dari situs itu.
- [ ] **Tidak pernah menambah otomatis:** menemukan, membuka daftar, dan membuka konfirmasi tidak menambah apa pun sampai tombol ditekan.
- [ ] **Gagal diperiksa** (misalnya matikan data sebentar): "Gagal diperiksa" dan tombol "Coba lagi" di konfirmasi.

**Privasi dan isolasi**

- [ ] Login ke sebuah situs di browser Ritme tidak memengaruhi login YouTube Musicfy, dan sebaliknya (WebView dengan profil terpisah).
- [ ] Menu ⋯ → "Hapus data browser Ritme" → login di situs tadi hilang, login YouTube Musicfy tetap. Di WebView lama baris ini tidak ada.
- [ ] **WebView lama** (tanpa document-start script): mp4/m3u8 tetap ditemukan lewat lapis jaringan.

**Pemutaran dan sinkron**

- [ ] Saat video di halaman diputar, suara room di HP itu mungkin berhenti sebentar. Setelah keluar dari browser, room menyinkronkan ulang sendiri.
- [ ] Keluar dari browser (Back sampai keluar, atau "Tutup browser"): suara halaman berhenti.
- [ ] Aplikasi ke background saat browser terbuka: video halaman berhenti, room tetap jalan.
- [ ] **Audio focus:** putar video di halaman browser saat room sedang memutar; pastikan pemutar room dan video halaman tidak saling merebut suara terus-menerus.
- [ ] **Web:** entri HLS tanpa ekstensi (`/manifest?token=`) yang ditambahkan dari browser Android diputar web lewat hls.js. Entri DASH tampil "tidak bisa diputar di web" di web, tapi Android memutarnya.

**"Tambah video" (perubahan #3)**

- [ ] Tempel link `.m3u8` VOD → pratinjau menunjukkan durasinya (bukan LIVE). Setelah ditambah, jeda lalu putar lanjut dari posisi terakhir.
- [ ] Tempel link `.m3u8` ber-DRM → "Video berproteksi tidak bisa dibagikan".
- [ ] Tempel link `.mp4` → tetap seperti #2b (durasi terbaca, atau "Panjang video tidak terbaca, coba lagi").

**Catatan rilis**

- [ ] Deploy server dan web lebih dulu sebelum membagikan APK ini (server lama membuang `streamType`/`referrer` diam-diam).

**Tampilan**

- [ ] Header browser, sheet daftar, sheet konfirmasi, pill, ikon, dan font sama dengan bagian aplikasi lain (tidak ada gaya baru). Pill tidak menutupi mini player atau pill Ritme.

---

## Controller decisions on the open questions (2026-11-24, user said "langsung eksekusi")

These override the earlier text where they differ.

1. **DRM from the page (overrides Decision 9's last two signals):** the page script's EME hook and WebView's protected-media request (still always denied) set a page-level `eme` flag. That flag does **not** refuse candidates on its own. A candidate on such a page is refused as DRM unless its probe proved it clean: the playlist/manifest/MP4 head was actually read and carried no DRM markers. Candidates whose probe could not read the content (failed, unknown) on an `eme` page are refused as DRM instead of "Coba lagi". Playlist keys, `<ContentProtection>` and CENC boxes still refuse as before. The backlog note about false EME refusals is dropped (Task 16 Step 2 omits that bullet).
2. **Unknown file length (Decision 10):** kept as written ("Coba lagi", never `durationMs = 0` for files).
3. **"Hapus data browser Ritme" without profiles (Decision 13):** kept (row hidden).
4. **Browser outside a room:** kept (start mode only, from a start-mode "Tambah video" sheet).
