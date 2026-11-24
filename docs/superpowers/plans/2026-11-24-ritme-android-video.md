# Ritme Android Watch-Together (#2b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a Ritme room on Android, play `YOUTUBE_VIDEO` entries as a synced video (audio from `MusicService`, a muted follower player for the picture) and `URL` entries (mp4/HLS/DASH) straight through `MusicService`, with a full-screen mode, then let members add videos by link, by search and from the share sheet.

**Architecture:** The pure core under `app/src/main/kotlin/com/example/musicfy/ritme/` grows a little: `RitmeEntries` accepts `URL` entries, the planner loads live entries at their live edge, a new pure `VideoFollower` keeps a muted video player on the local audio clock, and `RitmeStageTarget` decides when the cover becomes a video stage. `MusicService` gets one new route, taken only for `RITME_URL:` media ids: a cache-free, cookie-free, proxy-free `DefaultMediaSourceFactory` whose connections to local addresses are refused. The Compose side adds `RitmeVideoStage` (in the flagship cover slot) and `RitmeVideoFullscreen` (a `RotatingFrame` overlay), built only from components the player already uses. Phase 2b-2 adds link/search parsing, `RitmeController.addEntries`, a `RitmeAddVideoSheet` and a "Tambah ke Ritme" share alias.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose + Material3, Hilt, Media3 1.7.1 (ExoPlayer, HLS, **DASH (new, same version)**, UI's `AspectRatioFrameLayout`), OkHttp 5.3.2, kotlinx.serialization via `:modules:ritme-protocol`, JUnit 4. AGP 9; flavors `foss`/`gms`/`fdroid` × ABI `universal`/….

## Global Constraints

- **Worktree:** `/Users/suryasoft/WebstormProjects/musicfy/.worktrees/ritme-android-video`, branch `feature/ritme-android-video` (from `main` 418c80e). Every command runs from that directory.
- **Source of truth:** `docs/superpowers/specs/2026-11-23-ritme-watch-together-design.md` (§4.1–4.6, §8, §9, §10 rows 2b-1/2b-2, §11, approved decisions U1–U12). Phase #3 (in-app browser, `streamType`/`referrer`, `:modules:ritme-media`) is **out of scope**.
- **Two known bugs this plan fixes** (named in spec §1.2 and §4.4):
  1. *Wrong video on the cover:* `RitmeEntries.toMediaMetadata` does not set `musicVideoType`, so a `YOUTUBE_VIDEO` entry never takes `MorphingCover`'s `self:` path, and with the "video background" setting on, the cover searches YouTube Music for an "official music video" by title and shows some other video (the canvas lookup by title does the same). Fixed in Task 1 (metadata) and Task 7 (the stage replaces the cover, no title search, no canvas).
  2. *Live entries start at 0:* `RitmeSyncPlanner` emits `Load(entry, 0)` for live entries, which is the start of the DVR window. Fixed in Task 2 (`Load(startMs = null)` → default position = live edge; a live entry played again after a pause jumps back to the edge).
- **Use the existing app theme, exactly.** All new Ritme UI uses the app's existing components, sizes, fonts, icons and transitions, copied from existing screens the way `ui/screens/ritme/RitmeComponents.kt` mirrors `QueueScreen`/`Menu.kt`: `RitmeChip` (QueueScreen's "Clear" chip: circle fill, 13 sp SemiBold, 12 × 5 dp padding, 16 dp glyph), `StyleCircle`/`TransportCircles`/`RotatingFrame`/`rememberStyleTones` from `ui/player/styles/StyleKit.kt`, the room sheet's row metrics (60 dp rows, 44 dp art, 8 dp art corners, 14 dp highlight shape), `RitmeSecondaryText` (13 sp on 17, 60 %), `SheetLabel`/`SheetTextField`/`SheetPillButton`/`Material3MenuGroup`, `MenuRow` in the player menu, `PlayingIndicatorBox`. No new colours, shapes, fonts or animation specs; the only new asset is the `fullscreen_exit` glyph from the same Material Icons set as the existing `fullscreen.xml` (24 dp viewport).
- **Popups:** sheets use `PopupSheet` (`LocalZoomOutOverlayState` / `showWithHandle`), never `Dialog`/`ModalBottomSheet`. The full-screen video is an in-window overlay, not a dialog.
- **Disabled controls (AppSwitch lesson):** a disabled `toggleable`/`clickable` still consumes pointer events. Hide a control that does not apply instead of disabling it when anything underneath must stay tappable; never put a disabled clickable over the video or the player.
- **Keep things working:** outside a room nothing changes (`RitmeGate.isActive == false`): every new path is behind a `RITME_URL:` media id or a non-null `RitmeStageTarget`, both of which only exist in a room. Host-only and guest rules (`RitmePermissions.canControl/canAdd`), `guestsCanAdd`, the `ADD_FORBIDDEN` toast, music Ritme (`YOUTUBE_MUSIC`), drift correction (`DriftCorrector`), `TrackEnded` once per entry, and "never auto-skip a failed stream in a room" all keep working. Existing unit tests stay green.
- **URL safety (spec 4.5):** a `URL` entry is played only if `MediaUrlRules.isAcceptableMediaUrl(url)`; only `http`/`https`; never cookies, never the YouTube proxy, never the song caches (`playerCache`/`downloadCache`); a connection whose remote address is loopback/private/link-local/CGNAT/multicast/ULA is refused. URL entries' thumbnails are never loaded or sent.
- **Strings:** English in `app/src/main/res/values/ritme_strings.xml`, Indonesian in `app/src/main/res/values-in/ritme_strings.xml`, all prefixed `ritme_`. Indonesian copy uses the spec wording.
- **Android facts:** AGP 9, Kotlin 2.3.10, Compose, Media3 1.7.1, Hilt, flavors `foss`/`gms`/`fdroid`. Variant names are `<abi><Variant>Debug`, e.g. `universalGmsDebug` (so the unit-test task is `:app:testUniversalGmsDebugUnitTest`, not `:app:testGmsDebugUnitTest`).
- **Low resource use (the user works on the same 16 GB Mac):**
  - JDK first, in the same shell call: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH`
  - **One Gradle build machine-wide.** Every Gradle command below starts with `pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY ||`. If it prints `BUSY` (process ids), nothing ran: wait (Monitor tool with an until-loop, or retry later) until `pgrep` prints nothing, then run it again.
  - Always the light flags, as separate args: `--console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g`
  - Prefer targeted tests (`--tests '…'`). Assemble at most one flavor per task (Task 15, the last, checks all three), flavors always in separate invocations.
  - Never edit `gradle.properties`. Never run `./gradlew --stop`, `pkill` or `kill` on Gradle while another build may be running.
- **Commits:** one per task, `git commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`. Do not push (the user pushes).

## Decisions made while planning (spec vs. code; read before reviewing)

1. **URL route (spec 4.4 vs. code).** The spec routes `RITME_URL:` through `deviceStream()` → `CustomStreamResult`. In the code that would still go through `DynamicResolvingMediaSource` → `createDataSourceFactory()`, whose `ResolvingDataSource` treats any `dataSpec.key` as a YouTube media id (cache lookup, `songUrlCache`, `YTPlayerUtils.playerResponseForPlayback`), and through `playerCache`. So the plan routes one level higher: `createMediaSourceFactory().createMediaSource(item)` hands `RITME_URL:` items to a dedicated `DefaultMediaSourceFactory(OkHttpDataSource.Factory(ritmeClient))`. Nothing about non-room items changes. `onPlayerError` gets an early branch for `RITME_URL:` so none of the YouTube recoveries (`forceFallBack`, `performAggressiveCacheClear`, expired-URL refresh) run for it.
2. **DNS guard.** OkHttp does not consult `Dns` for IP literals, and redirects can lead anywhere, so the guard is a **network interceptor** that checks the address the connection actually reached (`chain.connection()?.route()?.socketAddress?.address`). No proxy is used for room URLs (with a proxy, the connected address would be the proxy's).
3. **Stage size (spec 4.2 vs. code).** The spec sizes the stage to the video's ratio (9:16…21:9, ≤ 60 % of the screen). `MorphingCover`'s whole open/close morph (`endpoints`, `morphLayout`) is computed for the existing cover slot, so the stage **fits inside the existing cover slot** (letterboxed on black, never cropped) instead of resizing it. Open question for the user (see the end).
4. **"Lihat video" in other styles (U9 vs. code).** `PlayerMenuAction.SEE_VIDEO` exists only on the flagship (chevron menu/side button). The Poster/Timeline/Lyrics styles reach the player's "Other menu" (`PlayerActionMenu`) through `StyleMenu`, so the "See video / Lihat video" row goes there (shown only while a room video is current) and opens full screen. On the flagship, `SEE_VIDEO` opens full screen while a room video is current, else it keeps its old toggle.
5. **Full-screen button placement.** "Next to the Ritme pill" is realised as a chip in the open player's top-right corner, the slot `RitmeCornerButton` uses outside a room (in a room that slot is empty and the pill sits top-centre). Same `RitmeChip`, `fullscreen` glyph.
6. **Full screen is an overlay mounted in `MainActivity`**, inside `PopupSheetHost`'s content after the scaffold: above the player, below popup sheets (the pill in full screen can open the room sheet over it). Not a `Dialog` (popup rule).
7. **Video track switch is reference-counted in the bridge** (`videoShown()`/`videoHidden()`), because the cover stage and the full-screen stage hand over in the same frame; `MusicService` disables the video track on entering a room and re-enables it on leaving.
8. **`media3-exoplayer-dash`** (spec D11) is added to the app. Side effect outside rooms: a source module's DASH stream (`CustomStreamResult.isDash`), which already got `APPLICATION_MPD` but had no DASH module to play it, can now play. That only turns a failure into playback.
9. **Room URL items skip lyrics, listen history/YouTube play reports and scrobbling** (`RITME_URL:` is not a YouTube id). In the player's "Other menu" the add-to-playlist and download rows are disabled for them (like/library already no-op: the item is never in the database).
10. **`YouTubeLinks` lives in `:modules:ritme-protocol`** (package `com.example.musicfy.ritme.protocol`), next to `MediaUrlRules` and its fixture `fixtures/rules/youtube-links.json`; `:modules:ritme-media` does not exist until #3.
11. **Live rules for added YouTube videos** follow the server (2a Decision 10): a length that is missing, 0, or ≥ 12 h means live (`durationMs = 0`).
12. **Share outside a room** opens the "Add video" sheet in *start* mode (preview first), whose single button opens the start sheet with `startWithEntries` (two steps, but the user sees what they start with).
13. **Direct-link duration (2b-2).** Spec 4.6 asks for a "light probe". Without #3's `PlaylistSniffer`, progressive files (`.mp4/.webm/.m4v/.mov`) are probed with `MediaMetadataRetriever` (8 s timeout, on the adder's own device); `.m3u8`/`.mpd` are added with `durationMs = 0` (treated as live: no server fallback advance, Android `TrackEnded` still advances). Open question.
14. **Live speed control.** ExoPlayer's live speed adjustment (0.97–1.03 via `LiveConfiguration`) and the planner's `SetSpeed(1.0)` on room changes may meet on live URL entries; the planner never drift-corrects live entries, so they meet only on room changes. Checked on a device (manual list); if it fights, skip `SetSpeed` for live entries.

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt` | URL entries playable, `musicVideoType` for videos, `urlMediaId`/`isUrlMediaId`, `isVideo`; later `toVideoEntry`/`toUrlEntry`/`initialQueueOf` | 1, 11, 12 |
| `app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt` | `Load.startMs: Long?`, `SeekToLiveEdge` | 2 |
| `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt` | `loadRitmeItem(item, Long?)`, `setRitmeVideoEnabled` | 2, 4 |
| `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt` | executes new ops, URL items, video viewers | 2, 3, 4 |
| `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt` (new) | `RitmeUrlSpec` (pure) + `MediaItem` for an entry | 3 |
| `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuard.kt` (new) | public-address check + OkHttp network interceptor | 3 |
| `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` | `RITME_URL:` source factory, error branch, skips, host impl | 2, 3, 4 |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `media3-exoplayer-dash` | 3 |
| `app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt`, `ui/screens/ritme/RitmeUi.kt` | bridge for the UI | 4 |
| `app/src/main/kotlin/com/example/musicfy/ritme/video/VideoFollower.kt` (new) | pure follower policy | 5 |
| `core/resolver/src/main/kotlin/com/example/musicfy/utils/VideoFormatChooser.kt` (new), `YTPlayerUtils.kt` | codec/height-aware video format choice | 6 |
| `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeStageTarget.kt` (new) | pure: which entry gets a stage | 7 |
| `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoStreams.kt` (new) | 720p/480p + hardware codecs + cache | 7 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoStage.kt` (new) | the stage (follower or main-player surface) | 7 |
| `app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt` | stage in the cover slot, bug 1 | 7, 8 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt` | `RitmeChip` shared (icon param) | 7 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoFullscreen.kt` (new) | full-screen state, host, chip | 8 |
| `app/src/main/res/drawable/fullscreen_exit.xml` (new) | glyph | 8 |
| `MainActivity.kt`, `ui/player/BottomSheetPlayer.kt`, `ui/player/menu/PlayerActionMenu.kt` | mount, chip, SEE_VIDEO, menu row | 8 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActions.kt`, `RitmeRoomSheet.kt` | `entryTag`, 16:9 rows | 9 |
| `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinks.kt` (new) | link parser (shared fixture) | 10 |
| `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinks.kt` (new) | pasted text → YouTube / direct / rejected / search | 11 |
| `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt`, `RitmeEntrySheet.kt`, `RitmeUi.kt` | `addEntries`, start with entries, `AddVideo` request | 12, 14 |
| `providers/innertube/…/InnerTube.kt`, `YouTube.kt`, `pages/WebVideoSearchPage.kt` (new) | general YouTube video search (U3) | 13 |
| `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt` (new), `ritme/video/RitmeVideoLookup.kt` (new) | "Add video" sheet | 14 |
| `app/src/main/AndroidManifest.xml`, `MainActivity.kt` | "Tambah ke Ritme" alias | 15 |

---

# Phase 2b-1: Playback

### Task 1: URL entries are playable; video entries are marked as videos

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt`
- Modify: `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt`

**Interfaces:**
- Produces: `RitmeEntries.URL_MEDIA_ID_PREFIX = "RITME_URL:"`, `RitmeEntries.urlMediaId(entryId: String): String`, `RitmeEntries.isUrlMediaId(mediaId: String?): Boolean`, `RitmeEntries.isVideo(entry: QueueEntry): Boolean`; `isPlayable(entry)` now true for `URL` entries with `videoId == ""` and a `MediaUrlRules`-acceptable `url`; `toMediaMetadata(entry)` sets `musicVideoType = MUSIC_VIDEO_TYPE_UGC` for `YOUTUBE_VIDEO`, and for `URL` uses `id = urlMediaId(entryId)`, `thumbnailUrl = null`.
- Produces (tests): `entry(..., url: String? = null)` builder parameter.

- [ ] **Step 1: Add `url` to the test builder**

In `app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt` replace the `entry` builder with:

```kotlin
fun entry(
    id: String,
    addedBy: String = "m1",
    durationMs: Long = 200_000,
    kind: MediaKind = MediaKind.YOUTUBE_MUSIC,
    videoId: String = "vid$id",
    url: String? = null,
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
)
```

- [ ] **Step 2: Write the failing tests**

In `RitmeEntriesTest.kt` replace the test `onlyYouTubeKindsArePlayable` with the tests below (keep every other test):

```kotlin
    @Test
    fun youTubeKindsAndSafeUrlEntriesArePlayable() {
        assertTrue(RitmeEntries.isPlayable(entry("a")))
        assertTrue(RitmeEntries.isPlayable(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertTrue(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/v.mp4")))
        // a URL entry needs its url, an empty videoId, and a url the server's rules accept
        assertFalse(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "")))
        assertFalse(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "abc", url = "https://cdn.example.com/v.mp4")))
        assertFalse(RitmeEntries.isPlayable(entry("u", kind = MediaKind.URL, videoId = "", url = "http://192.168.1.10/v.mp4")))
        assertFalse(RitmeEntries.isPlayable(entry("x", kind = MediaKind.UNKNOWN)))
    }

    @Test
    fun videoAndUrlEntriesAreVideos() {
        assertFalse(RitmeEntries.isVideo(entry("a")))
        assertTrue(RitmeEntries.isVideo(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertTrue(RitmeEntries.isVideo(entry("u", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/v.mp4")))
        assertFalse(RitmeEntries.isVideo(entry("x", kind = MediaKind.UNKNOWN)))
    }

    @Test
    fun aVideoEntryIsMarkedAsAVideoSoTheCoverPlaysItsOwnVideo() {
        // Bug 1 (spec 1.2): without musicVideoType the cover searched for "the official video" by title.
        val video = RitmeEntries.toMediaMetadata(entry("v", kind = MediaKind.YOUTUBE_VIDEO))
        assertEquals("vidv", video.id)
        assertTrue(video.isVideoSong)
        assertFalse(RitmeEntries.toMediaMetadata(entry("a")).isVideoSong)
    }

    @Test
    fun aUrlEntryPlaysUnderItsOwnIdAndNeverLoadsItsPicture() {
        val link = entry("u1", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/v.mp4")
            .copy(thumbnailUrl = "https://cdn.example.com/t.jpg")
        val m = RitmeEntries.toMediaMetadata(link)
        assertEquals("RITME_URL:u1", m.id)
        assertNull(m.thumbnailUrl)
        assertFalse(m.isVideoSong)
        assertEquals("Artist u1", m.artists.single().name)
    }

    @Test
    fun urlMediaIdsAreRecognised() {
        assertEquals("RITME_URL:e9", RitmeEntries.urlMediaId("e9"))
        assertTrue(RitmeEntries.isUrlMediaId(RitmeEntries.urlMediaId("e9")))
        assertFalse(RitmeEntries.isUrlMediaId("dQw4w9WgXcQ"))
        assertFalse(RitmeEntries.isUrlMediaId(null))
    }
```

- [ ] **Step 3: Run the tests to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.entries.RitmeEntriesTest'
```

Expected: compilation FAILS (`Unresolved reference: isVideo`, `urlMediaId`, `isUrlMediaId`).

- [ ] **Step 4: Implement**

In `RitmeEntries.kt` add the imports:

```kotlin
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
```

Add inside `object RitmeEntries`, right after `private const val MAX_THUMBNAIL_LENGTH = 512`:

```kotlin
    /**
     * The player's media id for a URL entry: never a YouTube id, so MusicService sends it down its
     * own route (no resolver, no song cache) and skips lyrics, history and scrobbling for it.
     */
    const val URL_MEDIA_ID_PREFIX = "RITME_URL:"

    fun urlMediaId(entryId: String): String = URL_MEDIA_ID_PREFIX + entryId

    fun isUrlMediaId(mediaId: String?): Boolean = mediaId?.startsWith(URL_MEDIA_ID_PREFIX) == true
```

Replace `isPlayable` and `toMediaMetadata` (the last two members of the object) with:

```kotlin
    /**
     * YouTube kinds play by videoId (YOUTUBE_VIDEO: audio here, picture from the video stage);
     * URL entries play their url when it passes the server's MediaUrlRules (spec 4.5). UNKNOWN never.
     */
    fun isPlayable(entry: QueueEntry): Boolean = when (entry.kind) {
        MediaKind.YOUTUBE_MUSIC, MediaKind.YOUTUBE_VIDEO -> VIDEO_ID.matches(entry.videoId)
        MediaKind.URL -> entry.videoId.isEmpty() && entry.url?.let(MediaUrlRules::isAcceptableMediaUrl) == true
        MediaKind.UNKNOWN -> false
    }

    /** Entries watched rather than listened to: they get the video stage and 16:9 rows. */
    fun isVideo(entry: QueueEntry): Boolean = entry.kind == MediaKind.YOUTUBE_VIDEO || entry.kind == MediaKind.URL

    fun toMediaMetadata(entry: QueueEntry): MediaMetadata = MediaMetadata(
        id = if (entry.kind == MediaKind.URL) urlMediaId(entry.entryId) else entry.videoId,
        title = entry.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = entry.artist)),
        duration = (entry.durationMs / 1000).toInt(),
        // A URL entry's picture could come from any host: it is never loaded (spec 4.7, R8).
        thumbnailUrl = if (entry.kind == MediaKind.URL) null else entry.thumbnailUrl,
        // Marks the item as a video (isVideoSong), so the cover never goes looking for "its official
        // video" by title, which found some other video for non-music entries (spec 1.2).
        musicVideoType = if (entry.kind == MediaKind.YOUTUBE_VIDEO) MUSIC_VIDEO_TYPE_UGC else null,
    )
```

- [ ] **Step 5: Run the tests to see them pass**

Run the command from Step 3 again, then the planner and room-action tests (they use URL entries without a url, which stay unplayable):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*' --tests 'com.example.musicfy.ui.screens.ritme.*'
```

Expected: PASS (all Ritme tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt app/src/test/kotlin/com/example/musicfy/ritme/testing/RitmeBuilders.kt app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt
git commit -m "feat(ritme-android): URL entries are playable; video entries carry musicVideoType (wrong cover video)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Live entries start at the live edge

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` (`ritmeHost.loadRitmeItem`)
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlannerTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridgeTest.kt`

**Interfaces:**
- Produces: `PlayerOp.Load(entry: QueueEntry, startMs: Long?)` (`null` = the item's default position, the live edge for live streams); `PlayerOp.SeekToLiveEdge`; `RitmePlayerHost.loadRitmeItem(item: MediaItem, startPositionMs: Long?)`.

- [ ] **Step 1: Write the failing planner tests**

In `RitmeSyncPlannerTest.kt` change the first assertion of `liveEntriesNeverSeek` to:

```kotlin
        assertEquals(listOf(PlayerOp.Load(live, null), PlayerOp.Play), plan(room, 5_000, local(loaded = null)).ops)
```

and add:

```kotlin
    @Test
    fun aLiveEntryPlayedAgainAfterAPauseJumpsToTheLiveEdge() {
        // Bug 2 (spec 4.4): position 0 of a live stream is the start of its DVR window, not "now".
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(listOf(PlayerOp.SeekToLiveEdge, PlayerOp.Play), plan(room, 5_000, local(loaded = "l", pos = 30_000)).ops)
    }

    @Test
    fun aLiveEntryStillPlayingIsNotPulledToTheEdgeByARoomChange() {
        val live = entry("l", durationMs = 0)
        val room = room(queue = listOf(live), currentEntryId = "l", playback = Playback(true, 0, 1_000))
        assertEquals(emptyList<PlayerOp>(), plan(room, 5_000, local(loaded = "l", pos = 30_000, advancing = true, pwr = true)).ops)
    }
```

- [ ] **Step 2: Write the failing bridge test**

In `RitmePlaybackBridgeTest.kt`:

1. In `FakePlayer`'s proxy `when (method.name)`, add before `"addListener"`:

```kotlin
            "seekToDefaultPosition" -> {
                ops += "live edge"
                null
            }
```

2. In `FakeHost`, replace `loadRitmeItem` with:

```kotlin
    override fun loadRitmeItem(item: MediaItem, startPositionMs: Long?) {
        calls += "load ${item.mediaId}@$startPositionMs"
        fake.positionMs = startPositionMs ?: 0
        fake.itemCount = 1
        fake.state = Player.STATE_READY
        fake.playWhenReady = false
    }
```

3. Add the test:

```kotlin
    @Test
    fun aLiveEntryLoadsAtItsLiveEdgeAndJumpsBackThereAfterAPause() {
        val live = room(
            queue = listOf(entry("l", durationMs = 0)),
            currentEntryId = "l",
            playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = 0),
        )
        join(live)
        assertEquals("load l@null", host.calls.last())
        assertTrue(fake.playWhenReady)

        update(live.copy(version = 2, playback = Playback(isPlaying = false, positionMs = 0, anchorServerTimeMs = timers.time)))
        assertFalse(fake.playWhenReady)
        fake.ops.clear()
        update(live.copy(version = 3, playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = timers.time)))
        assertEquals(listOf("live edge", "play"), fake.ops)
    }
```

- [ ] **Step 3: Run the tests to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.sync.RitmeSyncPlannerTest' --tests 'com.example.musicfy.ritme.playback.RitmePlaybackBridgeTest'
```

Expected: compilation FAILS (`SeekToLiveEdge` unresolved; `loadRitmeItem` overrides nothing).

- [ ] **Step 4: Planner**

In `RitmeSyncPlanner.kt` replace the `Load` line of `PlayerOp` and add the new op:

```kotlin
    /** Replace the player's item with [entry] at [startMs], paused; null = its default position (a live stream's live edge). */
    data class Load(val entry: QueueEntry, val startMs: Long?) : PlayerOp
    /** Back to the live edge: a live entry played again after a pause (spec 4.4). */
    data object SeekToLiveEdge : PlayerOp
```

In `plan(...)` replace

```kotlin
        if (justLoaded) ops += PlayerOp.Load(entry, if (live) 0 else expected)
```

with

```kotlin
        // A live stream's position 0 is the start of its DVR window: it loads at its live edge.
        if (justLoaded) ops += PlayerOp.Load(entry, if (live) null else expected)
```

and in the `changed ->` branch put the new first line:

```kotlin
            changed -> {
                // Every device rejoins the live edge together (same target offset), never where it paused.
                if (live && (!local.playWhenReady || local.ended)) ops += PlayerOp.SeekToLiveEdge
                if (!live && (local.ended || abs(drift) > RESYNC_ON_CHANGE_MS)) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                if (!local.playWhenReady || local.ended) ops += PlayerOp.Play
            }
```

- [ ] **Step 5: Host and bridge**

In `RitmePlayerHost.kt` replace the `loadRitmeItem` declaration with:

```kotlin
    /** Replaces the player's item with [item] at [startPositionMs] (null: its default position, a live stream's live edge), prepared and paused. */
    fun loadRitmeItem(item: MediaItem, startPositionMs: Long?)
```

In `RitmePlaybackBridge.execute(...)` add a branch after `is PlayerOp.SeekTo -> { … }`:

```kotlin
            PlayerOp.SeekToLiveEdge -> {
                player.seekToDefaultPosition()
                lastSeekAt = localNow
                endedEntryId = null
                reportedEndFor = null
            }
```

- [ ] **Step 6: MusicService**

In `MusicService.kt`, inside `private val ritmeHost = object : …RitmePlayerHost {`, replace `override fun loadRitmeItem(item: MediaItem, startPositionMs: Long) {` … `}` with:

```kotlin
        override fun loadRitmeItem(item: MediaItem, startPositionMs: Long?) {
            val p = this@MusicService.player
            clearStreamFailure()
            // Whatever set them since Enter: REPEAT_ONE would loop the room's item and never report its end.
            if (p.repeatMode != REPEAT_MODE_OFF) p.repeatMode = REPEAT_MODE_OFF
            if (p.shuffleModeEnabled) p.shuffleModeEnabled = false
            // null: the item's default position, which for a live stream is its live edge (spec 4.4)
            if (startPositionMs == null) p.setMediaItem(item) else p.setMediaItem(item, startPositionMs)
            p.prepare()
            p.playWhenReady = false
        }
```

- [ ] **Step 7: Run the tests to see them pass**

Run the command from Step 3. Expected: PASS.

- [ ] **Step 8: Compile one flavor (MusicService changed)**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:compileUniversalFossDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlanner.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt app/src/test/kotlin/com/example/musicfy/ritme/sync/RitmeSyncPlannerTest.kt app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridgeTest.kt
git commit -m "fix(ritme-android): live entries load at the live edge and rejoin it after a pause" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: URL entries play through their own route in MusicService

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuard.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt` (default `itemFor`)
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeItemsTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuardTest.kt`

**Interfaces:**
- Consumes: `RitmeEntries.urlMediaId`, `isUrlMediaId`, `isPlayable`, `toMediaMetadata` (Task 1).
- Produces: `data class RitmeUrlSpec(mediaId: String, url: String, mimeType: String?, live: Boolean)`; `RitmeItems.urlSpec(entry: QueueEntry): RitmeUrlSpec?`; `RitmeItems.mediaItemFor(entry: QueueEntry): MediaItem`; `RitmeItems.FALLBACK_USER_AGENT`; `RitmeAddressGuard.isPublic(address: InetAddress): Boolean`; `RitmeAddressGuard.interceptor: okhttp3.Interceptor`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeItemsTest.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import androidx.media3.common.MimeTypes
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.testing.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeItemsTest {

    private fun link(id: String, url: String, durationMs: Long = 0) =
        entry(id, kind = MediaKind.URL, videoId = "", durationMs = durationMs, url = url)

    @Test
    fun anHlsLinkWithoutALengthIsALiveHlsItem() {
        assertEquals(
            RitmeUrlSpec("RITME_URL:u1", "https://cdn.example.com/live/master.m3u8?token=1", MimeTypes.APPLICATION_M3U8, live = true),
            RitmeItems.urlSpec(link("u1", "https://cdn.example.com/live/master.m3u8?token=1")),
        )
    }

    @Test
    fun aDashManifestIsADashItem() {
        assertEquals(MimeTypes.APPLICATION_MPD, RitmeItems.urlSpec(link("u2", "https://cdn.example.com/v/Manifest.MPD"))?.mimeType)
    }

    @Test
    fun aFileWithALengthLeavesItsTypeToTheExtractors() {
        assertEquals(
            RitmeUrlSpec("RITME_URL:u3", "https://cdn.example.com/films/a.mp4", null, live = false),
            RitmeItems.urlSpec(link("u3", "https://cdn.example.com/films/a.mp4", durationMs = 5_400_000)),
        )
    }

    @Test
    fun youTubeEntriesAndUnsafeLinksHaveNoUrlSpec() {
        assertNull(RitmeItems.urlSpec(entry("a")))
        assertNull(RitmeItems.urlSpec(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertNull(RitmeItems.urlSpec(link("u", "http://192.168.1.1/v.mp4")))
    }
}
```

Create `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuardTest.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RitmeAddressGuardTest {

    private fun v4(vararg b: Int) = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())

    // getByName parses literals without any DNS lookup
    private fun ip(literal: String) = InetAddress.getByName(literal)

    @Test
    fun publicAddressesPass() {
        assertTrue(RitmeAddressGuard.isPublic(v4(93, 184, 216, 34)))
        assertTrue(RitmeAddressGuard.isPublic(v4(8, 8, 8, 8)))
        assertTrue(RitmeAddressGuard.isPublic(ip("2606:4700::1111")))
        assertTrue(RitmeAddressGuard.isPublic(ip("64:ff9b::5db8:d822"))) // NAT64 of 93.184.216.34
    }

    @Test
    fun localAndPrivateAddressesAreRefused() {
        listOf(
            v4(127, 0, 0, 1), v4(10, 1, 2, 3), v4(172, 16, 0, 1), v4(172, 31, 255, 255), v4(192, 168, 1, 1),
            v4(169, 254, 169, 254), v4(100, 64, 0, 1), v4(0, 0, 0, 0), v4(224, 0, 0, 1), v4(198, 18, 0, 1), v4(192, 0, 0, 8),
            ip("::1"), ip("fe80::1"), ip("fd12:3456::1"), ip("fec0::1"), ip("::ffff:10.0.0.1"), ip("64:ff9b::a00:1"),
        ).forEach { assertFalse("$it", RitmeAddressGuard.isPublic(it)) }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.playback.RitmeItemsTest' --tests 'com.example.musicfy.ritme.playback.RitmeAddressGuardTest'
```

Expected: compilation FAILS (`RitmeItems`, `RitmeUrlSpec`, `RitmeAddressGuard` unresolved).

- [ ] **Step 3: `RitmeItems`**

Create `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.musicfy.extensions.toMediaItem
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.isLive

/** How a URL entry plays, worked out without Android types (unit-tested). */
data class RitmeUrlSpec(val mediaId: String, val url: String, val mimeType: String?, val live: Boolean)

/** The player item for a room entry (spec 4.4): YouTube kinds as songs, URL entries as their own stream. */
object RitmeItems {
    /** Every device keeps the same distance behind the live edge, so they stay a few seconds apart at most. */
    const val LIVE_TARGET_OFFSET_MS = 10_000L
    const val LIVE_MIN_SPEED = 0.97f
    const val LIVE_MAX_SPEED = 1.03f

    /** Used when the system WebView's user agent cannot be read (no WebView installed). */
    const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun urlSpec(entry: QueueEntry): RitmeUrlSpec? {
        if (entry.kind != MediaKind.URL || !RitmeEntries.isPlayable(entry)) return null
        val url = entry.url ?: return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val mimeType = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            // a plain file: the extractors find out what it is
            else -> null
        }
        return RitmeUrlSpec(RitmeEntries.urlMediaId(entry.entryId), url, mimeType, entry.isLive)
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
}
```

- [ ] **Step 4: `RitmeAddressGuard`**

Create `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuard.kt`:

```kotlin
package com.example.musicfy.ritme.playback

import okhttp3.Interceptor
import java.io.IOException
import java.net.InetAddress

/**
 * Keeps a room's URL entries off this device's own network (spec 4.5): a member must not be able to
 * make other members' phones reach their router or LAN. MediaUrlRules already refuses local hosts
 * by name; this checks the address a connection actually reached, which also covers DNS rebinding,
 * IP literals (OkHttp does not ask Dns about those) and redirects.
 */
object RitmeAddressGuard {

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address
        return when (b.size) {
            4 -> isPublicV4(b)
            16 -> isPublicV6(b)
            else -> false
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun isPublicV4(b: ByteArray): Boolean {
        val a0 = u(b[0])
        val a1 = u(b[1])
        val local = a0 == 0 || a0 == 10 || a0 == 127 ||
            (a0 == 100 && a1 in 64..127) || // CGNAT
            (a0 == 169 && a1 == 254) ||
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 192 && a1 == 0 && u(b[2]) == 0) ||
            (a0 == 198 && a1 in 18..19) ||
            a0 >= 224 // multicast and 240/4
        return !local
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        val first = u(b[0])
        if (first and 0xFE == 0xFC) return false // fc00::/7
        if (first == 0xFE && (u(b[1]) and 0xC0) == 0xC0) return false // fec0::/10
        // ::ffff:a.b.c.d and NAT64 64:ff9b::/96 are judged by the IPv4 address inside them
        val mapped = (0..9).all { b[it] == 0.toByte() } && u(b[10]) == 0xFF && u(b[11]) == 0xFF
        val nat64 = u(b[0]) == 0x00 && u(b[1]) == 0x64 && u(b[2]) == 0xFF && u(b[3]) == 0x9B && (4..11).all { b[it] == 0.toByte() }
        if (mapped || nat64) return isPublicV4(b.copyOfRange(12, 16))
        return true
    }

    /** A network interceptor: runs once the connection is up, before the request goes out. */
    val interceptor = Interceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address != null && !isPublic(address)) throw IOException("Ritme: refused a local address")
        chain.proceed(chain.request())
    }
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 6: DASH module**

In `gradle/libs.versions.toml`, under `media3-hls = …` add:

```toml
media3-dash = { module = "androidx.media3:media3-exoplayer-dash", version.ref = "media3" }
```

In `app/build.gradle.kts`, after `implementation(libs.media3.hls)` add:

```kotlin
    implementation(libs.media3.dash)
```

- [ ] **Step 7: The bridge builds URL items**

In `RitmePlaybackBridge.kt`'s `@Inject constructor`, replace the last argument

```kotlin
        { entry -> RitmeEntries.toMediaMetadata(entry).toMediaItem() },
```

with

```kotlin
        RitmeItems::mediaItemFor,
```

and delete the two imports that are now unused: `com.example.musicfy.extensions.toMediaItem` and `com.example.musicfy.ritme.entries.RitmeEntries` (the lambda was their only use in this file).

- [ ] **Step 8: MusicService — the route**

In `MusicService.kt`:

1. Add this property right above `private fun createMediaSourceFactory()`:

```kotlin
    /**
     * Ritme URL entries (spec 4.4, 4.5): straight from the host a member chose, never through the
     * YouTube resolver, the song caches or the proxy; no cookies; a connection that lands on a local
     * address is refused. OkHttpDataSource only speaks http(s), so file:/content: never play from a room.
     */
    private val ritmeUrlSourceFactory by lazy {
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .addNetworkInterceptor(com.example.musicfy.ritme.playback.RitmeAddressGuard.interceptor)
            .build()
        val userAgent = runCatching {
            com.example.musicfy.ui.screens.settings.importsync.chromeUserAgent(android.webkit.WebSettings.getDefaultUserAgent(this))
        }.getOrDefault(com.example.musicfy.ritme.playback.RitmeItems.FALLBACK_USER_AGENT)
        DefaultMediaSourceFactory(OkHttpDataSource.Factory(client).setUserAgent(userAgent))
    }
```

2. In `createMediaSourceFactory()`, make `createMediaSource` start with the room route:

```kotlin
            override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
                if (com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(mediaItem.mediaId)) {
                    return ritmeUrlSourceFactory.createMediaSource(mediaItem)
                }
                return com.example.musicfy.playback.custom.DynamicResolvingMediaSource(
```

(the rest of the existing `DynamicResolvingMediaSource(...)` call stays as it is).

3. In `onPlayerError`, directly after `reportException(error)`:

```kotlin
        // A Ritme URL entry (spec 8): none of the YouTube recoveries below apply to it. In a room
        // failStream keeps retrying it every few seconds while the planner follows the room.
        if (mediaId != null && com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(mediaId)) {
            failStream()
            return
        }
```

4. Lyrics: in the collector that starts `}.collectLatest(scope) { (mediaMetadata, showLyrics) ->`, make its first line:

```kotlin
            // A Ritme URL entry is a video from some website: there are no lyrics to look up.
            if (com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(mediaMetadata?.id)) return@collectLatest
```

5. History and YouTube play reports: in `onPlaybackStatsReady`, right after `val mediaItem = …`:

```kotlin
        // Not a YouTube id and not a song in the library: no history, no play report (spec 4.4).
        if (com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(mediaItem.mediaId)) return
```

6. Scrobbling: replace `scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)` (in `onMediaItemTransition`) with

```kotlin
            scrobbleManager?.onSongStart(
                player.currentMetadata?.takeUnless { com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(it.id) },
                duration = player.duration,
            )
```

and replace `scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)` with

```kotlin
            scrobbleManager?.onPlayerStateChanged(
                player.isPlaying,
                player.currentMetadata?.takeUnless { com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(it.id) },
                duration = player.duration,
            )
```

- [ ] **Step 9: Re-run the Ritme tests, then assemble one flavor**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*'
```

Expected: PASS. Then (after `pgrep` is clear again):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalFossDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeItems.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuard.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeItemsTest.kt app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmeAddressGuardTest.kt
git commit -m "feat(ritme-android): play URL entries (file/HLS/DASH) on their own cache-free, cookie-free route with a local-address guard" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The video track decodes only while a stage shows it

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt` (`ritmeHost`)
- Modify: `app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt`, `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridgeTest.kt`

**Interfaces:**
- Produces: `RitmePlayerHost.setRitmeVideoEnabled(enabled: Boolean)`; `RitmePlaybackBridge.videoShown()`, `RitmePlaybackBridge.videoHidden()`; `RitmeUi.bridge: RitmePlaybackBridge?`; `RitmeEntryPoint.ritmeBridge()`.

- [ ] **Step 1: Write the failing test**

In `RitmePlaybackBridgeTest.kt`'s `FakeHost` add:

```kotlin
    val video = mutableListOf<Boolean>()

    override fun setRitmeVideoEnabled(enabled: Boolean) {
        video += enabled
    }
```

and add the test:

```kotlin
    @Test
    fun theVideoTrackIsOffInARoomWhileNoStageShowsItAndBackOnAfterLeaving() {
        join(starting)
        assertEquals(listOf(false), host.video)

        bridge.videoShown() // the cover's stage
        bridge.videoShown() // the full-screen stage takes over in the same frame
        bridge.videoHidden()
        assertEquals(listOf(false, true), host.video)
        bridge.videoHidden()
        assertEquals(listOf(false, true, false), host.video)

        session.leave()
        assertEquals(listOf(false, true, false, true), host.video)
        bridge.videoShown() // outside a room: nothing
        assertEquals(listOf(false, true, false, true), host.video)
        bridge.videoHidden()
    }
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.playback.RitmePlaybackBridgeTest'
```

Expected: compilation FAILS (`setRitmeVideoEnabled` overrides nothing, `videoShown` unresolved).

- [ ] **Step 3: Host interface**

In `RitmePlayerHost.kt`, add after `loadRitmeItem`:

```kotlin
    /** Decode the item's video track (true) or audio only (false); a URL entry's picture is only decoded while shown. */
    fun setRitmeVideoEnabled(enabled: Boolean)
```

- [ ] **Step 4: Bridge**

In `RitmePlaybackBridge.kt`:

1. Add fields after `private var leavingForCast = false`:

```kotlin
    /** RitmeVideoStages showing the room's video right now (the cover and full screen can overlap for a frame). */
    private var videoViewers = 0
    /** What the host was last told, while in a room; null = not told since entering. */
    private var videoApplied: Boolean? = null
```

2. In `enter()`, right after the line `inRitme = true` (it follows `snapshot = h.enterRitmeMode()`; `applyVideo()` only acts while `inRitme`):

```kotlin
        videoApplied = null
        applyVideo() // no stage yet: the room's items start without video decoding
```

3. In `exit(...)`, right before `host?.exitRitmeMode(snapshot ?: LocalQueueSnapshot.EMPTY)`:

```kotlin
        // The local player decodes video again, as it did before the room (it plays no video itself).
        host?.setRitmeVideoEnabled(true)
        videoApplied = null
```

4. Add the public functions after `pauseLocally()`:

```kotlin
    /**
     * A RitmeVideoStage started showing the room's video (spec 4.4): a URL entry decodes its video
     * track only while at least one stage shows it, so a screen that is off or a collapsed player
     * costs no video decoding or data. Main thread.
     */
    fun videoShown() {
        videoViewers++
        applyVideo()
    }

    /** The matching end of [videoShown]. */
    fun videoHidden() {
        videoViewers = (videoViewers - 1).coerceAtLeast(0)
        applyVideo()
    }

    private fun applyVideo() {
        if (!inRitme) return
        val want = videoViewers > 0
        if (want == videoApplied) return
        videoApplied = want
        host?.setRitmeVideoEnabled(want)
    }
```

- [ ] **Step 5: MusicService**

In `ritmeHost` add:

```kotlin
        override fun setRitmeVideoEnabled(enabled: Boolean) {
            val p = this@MusicService.player
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
```

- [ ] **Step 6: The UI can reach the bridge**

`app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt`: add `import com.example.musicfy.ritme.playback.RitmePlaybackBridge` and the member `fun ritmeBridge(): RitmePlaybackBridge`.

`app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt`: change the class and its construction:

```kotlin
/** The singletons Ritme's UI talks to. [bridge] is null only in tests. */
@Stable
class RitmeUi(
    val session: RitmeSession,
    val controller: RitmeController,
    val bridge: com.example.musicfy.ritme.playback.RitmePlaybackBridge? = null,
)
```

```kotlin
        RitmeUi(entry.ritmeSession(), entry.ritmeController(), entry.ritmeBridge())
```

- [ ] **Step 7: Run the tests, then assemble one flavor**

Run the command from Step 2. Expected: PASS. Then:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalGmsDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlayerHost.kt app/src/main/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridge.kt app/src/main/kotlin/com/example/musicfy/playback/MusicService.kt app/src/main/kotlin/com/example/musicfy/di/RitmeEntryPoint.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt app/src/test/kotlin/com/example/musicfy/ritme/playback/RitmePlaybackBridgeTest.kt
git commit -m "feat(ritme-android): decode a URL entry's video only while a stage shows it; restore on leaving" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `VideoFollower`, the pure policy for the muted video player

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/VideoFollower.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/video/VideoFollowerTest.kt`

**Interfaces:**
- Produces: `sealed interface FollowAction { Ignore; SetSpeed(speed: Float); Seek(positionMs: Long) }`; `VideoFollower.evaluate(audioMs: Long, videoMs: Long, audioSpeed: Float, videoSpeed: Float, audioAdvancing: Boolean, nowMs: Long, lastSeekAtMs: Long?): FollowAction`; constants `TICK_MS = 100`, `IN_SYNC_MS = 40`, `SETTLED_MS = 20`, `SEEK_ABOVE_MS = 400`, `MIN_SEEK_INTERVAL_MS = 2000`, `SLOW_FACTOR = 0.9f`, `FAST_FACTOR = 1.1f`, `SEEK_LEAD_MS = 150`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/example/musicfy/ritme/video/VideoFollowerTest.kt`:

```kotlin
package com.example.musicfy.ritme.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFollowerTest {

    private fun eval(
        audio: Long,
        video: Long,
        audioSpeed: Float = 1f,
        videoSpeed: Float = 1f,
        advancing: Boolean = true,
        now: Long = 10_000,
        lastSeek: Long? = null,
    ) = VideoFollower.evaluate(audio, video, audioSpeed, videoSpeed, advancing, now, lastSeek)

    @Test
    fun inSyncIsLeftAlone() {
        assertEquals(FollowAction.Ignore, eval(5_000, 5_030))
        assertEquals(FollowAction.Ignore, eval(5_000, 4_970))
    }

    @Test
    fun inSyncTheVideoTakesTheAudiosOwnSpeed() {
        // the room's DriftCorrector runs the audio at 0.97: the picture goes along
        assertEquals(FollowAction.SetSpeed(0.97f), eval(5_000, 5_010, audioSpeed = 0.97f))
    }

    @Test
    fun aVideoBehindSpeedsUpAndOneAheadSlowsDown() {
        assertEquals(FollowAction.SetSpeed(1f * VideoFollower.FAST_FACTOR), eval(5_000, 4_900))
        assertEquals(FollowAction.SetSpeed(1f * VideoFollower.SLOW_FACTOR), eval(5_000, 5_100))
        assertEquals(FollowAction.Ignore, eval(5_000, 5_100, videoSpeed = 1f * VideoFollower.SLOW_FACTOR))
    }

    @Test
    fun theCorrectionRidesOnTheAudiosCorrection() {
        assertEquals(FollowAction.SetSpeed(1.03f * VideoFollower.FAST_FACTOR), eval(5_000, 4_900, audioSpeed = 1.03f))
    }

    @Test
    fun aRunningCorrectionContinuesUntilUnder20Ms() {
        assertEquals(FollowAction.Ignore, eval(5_000, 4_970, videoSpeed = VideoFollower.FAST_FACTOR))
        assertEquals(FollowAction.SetSpeed(1f), eval(5_000, 4_990, videoSpeed = VideoFollower.FAST_FACTOR))
    }

    @Test
    fun anOvershootStopsTheCorrection() {
        assertEquals(FollowAction.SetSpeed(1f), eval(5_000, 5_030, videoSpeed = VideoFollower.FAST_FACTOR))
    }

    @Test
    fun farOffSeeksALittleAheadOfTheAudio() {
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 3_000))
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 5_401))
    }

    @Test
    fun seeksAreAtMostOnceEveryTwoSeconds() {
        assertEquals(FollowAction.Ignore, eval(5_000, 3_000, now = 10_000, lastSeek = 8_500))
        assertEquals(FollowAction.Seek(5_000 + VideoFollower.SEEK_LEAD_MS), eval(5_000, 3_000, now = 10_000, lastSeek = 8_000))
    }

    @Test
    fun aPausedOrBufferingAudioGetsTheExactFrame() {
        assertEquals(FollowAction.Seek(5_000), eval(5_000, 5_200, advancing = false, lastSeek = 9_900))
        assertEquals(FollowAction.Ignore, eval(5_000, 5_020, advancing = false))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.video.VideoFollowerTest'
```

Expected: compilation FAILS (`VideoFollower` unresolved).

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/example/musicfy/ritme/video/VideoFollower.kt`:

```kotlin
package com.example.musicfy.ritme.video

import kotlin.math.abs

sealed interface FollowAction {
    data object Ignore : FollowAction
    data class SetSpeed(val speed: Float) : FollowAction
    data class Seek(val positionMs: Long) : FollowAction
}

/**
 * How the muted video player follows this device's audio player (spec 4.3, D3). It never looks at
 * the room: the planner and DriftCorrector keep the audio on the room, this keeps the picture on
 * the audio. Evaluated every [TICK_MS]:
 * - under 40 ms: the video runs at the audio's own speed (so it rides along with DriftCorrector's
 *   0.97/1.03); a running correction carries on until under 20 ms, and an overshoot ends it;
 * - 40..400 ms: audio speed × 0.9 when the video is ahead, × 1.1 when behind;
 * - over 400 ms: seek to the audio plus [SEEK_LEAD_MS] (what the video needs to resume), at most
 *   once per [MIN_SEEK_INTERVAL_MS];
 * - audio not advancing (paused, buffering): the exact frame, by seeking whenever over 40 ms.
 * Target: lips within 80 ms of the sound (ITU-R BT.1359 allows about +45/-125 ms).
 */
object VideoFollower {
    const val TICK_MS = 100L
    const val IN_SYNC_MS = 40L
    const val SETTLED_MS = 20L
    const val SEEK_ABOVE_MS = 400L
    const val MIN_SEEK_INTERVAL_MS = 2_000L
    const val SLOW_FACTOR = 0.9f
    const val FAST_FACTOR = 1.1f
    const val SEEK_LEAD_MS = 150L

    fun evaluate(
        audioMs: Long,
        videoMs: Long,
        audioSpeed: Float,
        videoSpeed: Float,
        audioAdvancing: Boolean,
        nowMs: Long,
        lastSeekAtMs: Long?,
    ): FollowAction {
        val drift = videoMs - audioMs // > 0: the picture is ahead of the sound
        val size = abs(drift)
        if (!audioAdvancing) {
            return if (size > IN_SYNC_MS) FollowAction.Seek(audioMs) else FollowAction.Ignore
        }
        if (size > SEEK_ABOVE_MS) {
            val canSeek = lastSeekAtMs == null || nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS
            return if (canSeek) FollowAction.Seek(audioMs + (SEEK_LEAD_MS * audioSpeed).toLong()) else FollowAction.Ignore
        }
        val slower = audioSpeed * SLOW_FACTOR
        val faster = audioSpeed * FAST_FACTOR
        val correcting = same(videoSpeed, slower) || same(videoSpeed, faster)
        if (size < IN_SYNC_MS) {
            if (same(videoSpeed, audioSpeed)) return FollowAction.Ignore
            if (!correcting || size < SETTLED_MS) return FollowAction.SetSpeed(audioSpeed)
            val opposes = same(videoSpeed, slower) != (drift > 0)
            return if (opposes) FollowAction.SetSpeed(audioSpeed) else FollowAction.Ignore
        }
        val wanted = if (drift > 0) slower else faster
        return if (same(videoSpeed, wanted)) FollowAction.Ignore else FollowAction.SetSpeed(wanted)
    }

    private fun same(a: Float, b: Float): Boolean = abs(a - b) < 0.001f
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/video/VideoFollower.kt app/src/test/kotlin/com/example/musicfy/ritme/video/VideoFollowerTest.kt
git commit -m "feat(ritme-android): VideoFollower keeps a muted video on the local audio clock" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Video format choice by height cap and hardware codec

**Files:**
- Create: `core/resolver/src/main/kotlin/com/example/musicfy/utils/VideoFormatChooser.kt`
- Modify: `core/resolver/src/main/kotlin/com/example/musicfy/utils/YTPlayerUtils.kt` (`resolveVideoStreamUrl`)
- Test: `core/resolver/src/test/kotlin/com/example/musicfy/core/resolver/VideoFormatChooserTest.kt`

**Interfaces:**
- Produces: `enum class VideoCodec { AVC, VP9, AV1 }` with `VideoCodec.of(mimeType: String): VideoCodec?`; `data class VideoFormatPolicy(maxHeight: Int, codecs: List<VideoCodec>)`; `VideoFormatChooser.choose(adaptive, muxed, policy): PlayerResponse.StreamingData.Format?`; `YTPlayerUtils.resolveVideoStreamUrl(videoId: String, policy: VideoFormatPolicy? = null): Result<String>` (null = the old choice, so the cover's video background is unchanged).

- [ ] **Step 1: Write the failing tests**

Create `core/resolver/src/test/kotlin/com/example/musicfy/core/resolver/VideoFormatChooserTest.kt`:

```kotlin
package com.example.musicfy.core.resolver

import com.example.musicfy.utils.VideoCodec
import com.example.musicfy.utils.VideoFormatChooser
import com.example.musicfy.utils.VideoFormatPolicy
import com.music.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFormatChooserTest {

    private fun video(itag: Int, height: Int, codecs: String, bitrate: Int = 1_000_000) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://rr1.googlevideo.com/videoplayback?itag=$itag",
        mimeType = "video/${if (codecs.startsWith("vp9")) "webm" else "mp4"}; codecs=\"$codecs\"",
        bitrate = bitrate,
        width = height * 16 / 9,
        height = height,
        contentLength = null,
        quality = "hd",
        fps = 30,
        qualityLabel = "${height}p",
        averageBitrate = bitrate,
        audioQuality = null,
        approxDurationMs = "200000",
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        cipher = null,
        audioTrack = null,
    )

    private val all = listOf(
        video(134, 360, "avc1.4d401e"), video(135, 480, "avc1.4d401f"), video(136, 720, "avc1.4d401f"), video(137, 1080, "avc1.640028"),
        video(247, 720, "vp9"), video(398, 720, "av01.0.05M.08"),
    )
    private val everyCodec = listOf(VideoCodec.AVC, VideoCodec.VP9, VideoCodec.AV1)

    @Test
    fun theFirstCodecAtTheTallestHeightUnderTheCap() {
        assertEquals(136, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, everyCodec))?.itag)
    }

    @Test
    fun aMeteredNetworkIsCappedAt480() {
        assertEquals(135, VideoFormatChooser.choose(all, null, VideoFormatPolicy(480, everyCodec))?.itag)
    }

    @Test
    fun codecsWithoutAHardwareDecoderAreSkipped() {
        assertEquals(247, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, listOf(VideoCodec.VP9)))?.itag)
        assertEquals(398, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, listOf(VideoCodec.AV1, VideoCodec.AVC)))?.itag)
    }

    @Test
    fun nothingUnderTheCapTakesTheSmallestThereIs() {
        val tall = listOf(video(137, 1080, "avc1.640028"), video(264, 1440, "avc1.640032"))
        assertEquals(137, VideoFormatChooser.choose(tall, null, VideoFormatPolicy(720, everyCodec))?.itag)
    }

    @Test
    fun noAdaptiveVideoFallsBackToAMuxedFormat() {
        val muxed = video(18, 360, "avc1.42001E, mp4a.40.2")
        assertEquals(18, VideoFormatChooser.choose(emptyList(), listOf(muxed), VideoFormatPolicy(720, everyCodec))?.itag)
        assertNull(VideoFormatChooser.choose(null, null, VideoFormatPolicy(720, everyCodec)))
    }

    @Test
    fun codecsAreReadFromTheMimeType() {
        assertEquals(VideoCodec.AVC, VideoCodec.of("video/mp4; codecs=\"avc1.4d401f\""))
        assertEquals(VideoCodec.VP9, VideoCodec.of("video/webm; codecs=\"vp9\""))
        assertEquals(VideoCodec.VP9, VideoCodec.of("video/mp4; codecs=\"vp09.00.40.08\""))
        assertEquals(VideoCodec.AV1, VideoCodec.of("video/mp4; codecs=\"av01.0.08M.08\""))
        assertNull(VideoCodec.of("audio/webm; codecs=\"opus\""))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :core:resolver:test --tests 'com.example.musicfy.core.resolver.VideoFormatChooserTest'
```

Expected: compilation FAILS (`VideoFormatChooser` unresolved).

- [ ] **Step 3: Implement the chooser**

Create `core/resolver/src/main/kotlin/com/example/musicfy/utils/VideoFormatChooser.kt`:

```kotlin
package com.example.musicfy.utils

import com.music.innertube.models.response.PlayerResponse

private typealias Format = PlayerResponse.StreamingData.Format

enum class VideoCodec {
    AVC, VP9, AV1;

    companion object {
        /** The codec of a YouTube format's mimeType, e.g. `video/mp4; codecs="avc1.4d401f"`; null for audio. */
        fun of(mimeType: String): VideoCodec? {
            val codecs = mimeType.substringAfter("codecs=\"", "").lowercase()
            return when {
                codecs.startsWith("avc1") -> AVC
                codecs.startsWith("vp9") || codecs.startsWith("vp09") -> VP9
                codecs.startsWith("av01") -> AV1
                else -> null
            }
        }
    }
}

/** Ritme's video follower (spec 4.3, U10): at most [maxHeight], in the first of [codecs] that has a stream. */
data class VideoFormatPolicy(val maxHeight: Int, val codecs: List<VideoCodec>)

object VideoFormatChooser {
    /**
     * The tallest adaptive video-only format up to the cap, in the first codec of the policy that has
     * one (the caller lists codecs this device decodes in hardware, best first); else the tallest up
     * to the cap in any codec; else the smallest there is; else a muxed format.
     */
    fun choose(adaptive: List<Format>?, muxed: List<Format>?, policy: VideoFormatPolicy): Format? {
        val video = adaptive.orEmpty().filter { !it.isAudio && it.height != null }
        val fitting = video.filter { it.height!! <= policy.maxHeight }
        val best = compareBy<Format>({ it.height }, { -it.bitrate })
        for (codec in policy.codecs) {
            fitting.filter { VideoCodec.of(it.mimeType) == codec }.maxWithOrNull(best)?.let { return it }
        }
        return fitting.maxWithOrNull(best)
            ?: video.minByOrNull { it.height!! }
            ?: muxed.orEmpty().firstOrNull { !it.isAudio }
    }
}
```

- [ ] **Step 4: Let `resolveVideoStreamUrl` take a policy**

In `YTPlayerUtils.kt` change the signature and the format choice of `resolveVideoStreamUrl`:

```kotlin
    /**
     * A video-only stream for [videoId]. Without a [policy] the closest to 720p in any codec (the
     * cover's video background, unchanged); with one, [VideoFormatChooser]'s choice (Ritme's stage).
     */
    suspend fun resolveVideoStreamUrl(videoId: String, policy: VideoFormatPolicy? = null): Result<String> = runCatching {
```

and replace the `val format = …` expression inside the loop with:

```kotlin
            val format = if (policy != null) {
                VideoFormatChooser.choose(response.streamingData?.adaptiveFormats, response.streamingData?.formats, policy)
            } else {
                response.streamingData?.adaptiveFormats
                    ?.filter { !it.isAudio && it.height != null }
                    ?.minByOrNull { kotlin.math.abs((it.height ?: 0) - 720) }
                    ?: response.streamingData?.formats?.firstOrNull { !it.isAudio }
            }
```

- [ ] **Step 5: Run the resolver tests**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :core:resolver:test
```

Expected: PASS (the new tests and every existing resolver test).

- [ ] **Step 6: Commit**

```bash
git add core/resolver/src/main/kotlin/com/example/musicfy/utils/VideoFormatChooser.kt core/resolver/src/main/kotlin/com/example/musicfy/utils/YTPlayerUtils.kt core/resolver/src/test/kotlin/com/example/musicfy/core/resolver/VideoFormatChooserTest.kt
git commit -m "feat(resolver): choose a video stream by height cap and hardware codec for new callers" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The video stage takes the cover's place (wrong-video bug, part 2)

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeStageTarget.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoStreams.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoStage.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt` (share `RitmeChip`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt`
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeStageTargetTest.kt`

**Interfaces:**
- Consumes: `RitmeEntries.isPlayable/urlMediaId` (Task 1), `RitmeUi.bridge.videoShown/videoHidden` (Task 4), `VideoFollower` (Task 5), `VideoFormatPolicy`/`VideoCodec`/`resolveVideoStreamUrl(videoId, policy)` (Task 6).
- Produces: `sealed interface RitmeStageTarget { entryId; YouTube(entryId, videoId, thumbnailUrl: String?); Url(entryId) }` with `RitmeStageTarget.of(room: RoomState?, loadedMediaId: String): RitmeStageTarget?`; `@Composable fun rememberRitmeStageTarget(loadedMediaId: String): RitmeStageTarget?`; `@Composable internal fun RitmeVideoStage(target: RitmeStageTarget, modifier: Modifier = Modifier)`; `internal fun RitmeChip(text, containerColor, onClick, modifier, icon = R.drawable.group)` and `internal val RitmeChipFill`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeStageTargetTest.kt`:

```kotlin
package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeStageTargetTest {

    private val video = entry("v1", kind = MediaKind.YOUTUBE_VIDEO, videoId = "dQw4w9WgXcQ")
    private val link = entry("u1", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/film.mp4")

    private fun current(e: QueueEntry) = room(queue = listOf(e, entry("s")), currentEntryId = e.entryId)

    @Test
    fun aVideoEntryThePlayerHoldsGetsAStage() {
        assertEquals(
            RitmeStageTarget.YouTube("v1", "dQw4w9WgXcQ", null),
            RitmeStageTarget.of(current(video), loadedMediaId = "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun notWhileThePlayerStillHoldsThePreviousItem() {
        assertNull(RitmeStageTarget.of(current(video), loadedMediaId = "vids"))
        assertNull(RitmeStageTarget.of(current(link), loadedMediaId = "vids"))
    }

    @Test
    fun aUrlEntryThePlayerHoldsGetsAStage() {
        assertEquals(RitmeStageTarget.Url("u1"), RitmeStageTarget.of(current(link), loadedMediaId = "RITME_URL:u1"))
    }

    @Test
    fun songsUnsafeLinksAndNoRoomGetNone() {
        assertNull(RitmeStageTarget.of(current(entry("s")), loadedMediaId = "vids"))
        assertNull(RitmeStageTarget.of(current(link.copy(url = "http://10.0.0.2/x.mp4")), loadedMediaId = "RITME_URL:u1"))
        assertNull(RitmeStageTarget.of(null, loadedMediaId = "dQw4w9WgXcQ"))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.video.RitmeStageTargetTest'
```

Expected: compilation FAILS (`RitmeStageTarget` unresolved).

- [ ] **Step 3: `RitmeStageTarget`**

Create `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeStageTarget.kt`:

```kotlin
package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry

/** What the video stage shows (spec 4.2): only for the room's current video entry, once the player holds it. */
sealed interface RitmeStageTarget {
    val entryId: String

    /** Picture from a muted follower player; the sound is MusicService's (spec D1). */
    data class YouTube(override val entryId: String, val videoId: String, val thumbnailUrl: String?) : RitmeStageTarget

    /** MusicService's own player renders it (spec D2). */
    data class Url(override val entryId: String) : RitmeStageTarget

    companion object {
        /**
         * The stage for [room]'s current entry, or null: songs, unplayable entries, and while the
         * player ([loadedMediaId]) still holds the previous item, so a new video never shows over
         * the old item's sound.
         */
        fun of(room: RoomState?, loadedMediaId: String): RitmeStageTarget? {
            val entry = room?.currentEntry()?.takeIf(RitmeEntries::isPlayable) ?: return null
            return when (entry.kind) {
                MediaKind.YOUTUBE_VIDEO ->
                    if (loadedMediaId == entry.videoId) YouTube(entry.entryId, entry.videoId, entry.thumbnailUrl) else null
                MediaKind.URL ->
                    if (loadedMediaId == RitmeEntries.urlMediaId(entry.entryId)) Url(entry.entryId) else null
                MediaKind.YOUTUBE_MUSIC, MediaKind.UNKNOWN -> null
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: `RitmeVideoStreams`**

Create `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoStreams.kt`:

```kotlin
package com.example.musicfy.ritme.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.getSystemService
import androidx.media3.common.MimeTypes
import com.example.musicfy.ui.player.OfficialMusicVideo
import com.example.musicfy.ui.player.YouTubeVideoUrlCache
import com.example.musicfy.utils.VideoCodec
import com.example.musicfy.utils.VideoFormatPolicy
import com.example.musicfy.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The follower's stream (spec 4.3, U10, R2): 720p on Wi-Fi, 480p metered, a codec this device decodes in hardware. */
object RitmeVideoStreams {

    fun maxHeight(context: Context): Int =
        if (context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered == true) 480 else 720

    /** avc → vp9 → av1, keeping those with a hardware decoder; AVC alone when none can be read. */
    val hardwareCodecs: List<VideoCodec> by lazy {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && isHardware(it) }
        }.getOrDefault(emptyList())
        val types = decoders.flatMap { info -> info.supportedTypes.map { it.lowercase() } }.toSet()
        listOfNotNull(
            VideoCodec.AVC.takeIf { MimeTypes.VIDEO_H264 in types },
            VideoCodec.VP9.takeIf { MimeTypes.VIDEO_VP9 in types },
            VideoCodec.AV1.takeIf { MimeTypes.VIDEO_AV1 in types },
        ).ifEmpty { listOf(VideoCodec.AVC) }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }

    /** The video-only stream URL, or null when no client gives one (the audio plays on regardless). */
    suspend fun resolve(context: Context, videoId: String): String? {
        val maxHeight = maxHeight(context)
        // its own key: the cover's video background caches its own (any codec, near 720p) choice
        val key = "ritme:$videoId:$maxHeight"
        YouTubeVideoUrlCache.get(key)?.let { return it.streamUrl }
        val url = withContext(Dispatchers.IO) {
            YTPlayerUtils.resolveVideoStreamUrl(videoId, VideoFormatPolicy(maxHeight, hardwareCodecs)).getOrNull()
        } ?: return null
        YouTubeVideoUrlCache.put(key, OfficialMusicVideo(videoId, url))
        return url
    }
}
```

- [ ] **Step 6: Share the chip**

In `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt`:
- change `private val RitmeChipFill` to `internal val RitmeChipFill`;
- change `private fun RitmeChip(` to `internal fun RitmeChip(` and give it an icon parameter, used in place of `R.drawable.group`:

```kotlin
@Composable
internal fun RitmeChip(
    text: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.DrawableRes icon: Int = R.drawable.group,
) {
```

and inside it `Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))`.

- [ ] **Step 7: Strings**

`app/src/main/res/values/ritme_strings.xml`, before `</resources>`:

```xml
    <string name="ritme_video_unavailable">Video unavailable, audio keeps playing</string>
    <string name="ritme_video_cannot_play">This video can\'t play on this device</string>
    <string name="ritme_video_skip">Skip</string>
```

`app/src/main/res/values-in/ritme_strings.xml`, before `</resources>`:

```xml
    <string name="ritme_video_unavailable">Video tidak tersedia, audio tetap diputar</string>
    <string name="ritme_video_cannot_play">Video ini tidak bisa diputar di perangkat ini</string>
    <string name="ritme_video_skip">Lewati</string>
```

- [ ] **Step 8: The stage**

Create `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoStage.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme.video

import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import com.example.musicfy.LocalPlayerConnection
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.RitmeLocalAction
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ritme.video.FollowAction
import com.example.musicfy.ritme.video.RitmeStageTarget
import com.example.musicfy.ritme.video.RitmeVideoStreams
import com.example.musicfy.ritme.video.VideoFollower
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeChipFill
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryAlpha
import com.example.musicfy.ui.screens.ritme.RitmeSecondaryText
import com.example.musicfy.ui.screens.ritme.rememberRitme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The room's current video entry once the player holds it (see [RitmeStageTarget.of]), else null. */
@Composable
fun rememberRitmeStageTarget(loadedMediaId: String): RitmeStageTarget? {
    if (!RitmeConfig.ENABLED) return null
    val ritme = rememberRitme()
    val state by ritme.session.state.collectAsState()
    val room = state.room
    val inRoom = state.inRoom
    return remember(room, inRoom, loadedMediaId) { if (inRoom) RitmeStageTarget.of(room, loadedMediaId) else null }
}

/**
 * The video stage (spec 4.2–4.4): the whole video, fit and never cropped, on black. Nothing is drawn
 * over the picture; only when there is no picture does a short note say why. It runs only while
 * the app is on screen (started): with the screen off or the app in the background, the follower is
 * released and a URL entry stops decoding video, while the sound plays on (watch-together turns into
 * listen-together); coming back prepares the follower again at the audio's position.
 */
@Composable
internal fun RitmeVideoStage(target: RitmeStageTarget, modifier: Modifier = Modifier) {
    val connection = LocalPlayerConnection.current ?: return
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val transport by connection.uiState.transportState.collectAsState()
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            if (transport.isPlaying) KeepScreenOn()
            when (target) {
                is RitmeStageTarget.Url -> UrlVideo(connection.player)
                is RitmeStageTarget.YouTube -> FollowedVideo(target, connection.player)
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A URL entry: MusicService's player renders it here, decoding video only while this is shown. */
@Composable
private fun BoxScope.UrlVideo(player: ExoPlayer) {
    val ritme = rememberRitme()
    DisposableEffect(ritme) {
        ritme.bridge?.videoShown()
        onDispose { ritme.bridge?.videoHidden() }
    }
    VideoSurface(player)
    if (rememberPlayerFailed(player)) {
        val state by ritme.session.state.collectAsState()
        val room = state.room
        val canSkip = room != null && RitmePermissions.canControl(room, state.memberId)
        StageNote(stringResource(R.string.ritme_video_cannot_play)) {
            // The room does not wait for anyone (spec 8): whoever may control it can move it on.
            if (canSkip) {
                RitmeChip(
                    text = stringResource(R.string.ritme_video_skip),
                    containerColor = RitmeChipFill,
                    icon = R.drawable.skip_next,
                    onClick = { ritme.controller.handle(RitmeLocalAction.Next) },
                )
            }
        }
    }
}

/** True from a player error until the player plays or renders again. */
@Composable
private fun rememberPlayerFailed(player: Player): Boolean {
    var failed by remember(player) { mutableStateOf(player.playerError != null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) failed = false
            }

            override fun onRenderedFirstFrame() {
                failed = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return failed
}

/** A YouTube video: a muted follower player for the picture, kept on the audio by [VideoFollower]. */
@Composable
private fun BoxScope.FollowedVideo(target: RitmeStageTarget.YouTube, audio: ExoPlayer) {
    val context = LocalContext.current
    var streamUrl by remember(target.videoId) { mutableStateOf<String?>(null) }
    var unavailable by remember(target.videoId) { mutableStateOf(false) }
    LaunchedEffect(target.videoId) {
        val url = RitmeVideoStreams.resolve(context, target.videoId)
        if (url == null) unavailable = true else streamUrl = url
    }
    StageThumbnail(target.thumbnailUrl)
    val url = streamUrl
    if (url == null) {
        if (unavailable) StageNote(stringResource(R.string.ritme_video_unavailable))
        return
    }
    val follower = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setSeekParameters(SeekParameters.EXACT)
            prepare()
            seekTo(audio.currentPosition)
        }
    }
    var shown by remember(follower) { mutableStateOf(false) }
    var broken by remember(follower) { mutableStateOf(false) }
    DisposableEffect(follower) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                shown = true
            }

            override fun onPlayerError(error: PlaybackException) {
                broken = true
            }
        }
        follower.addListener(listener)
        onDispose {
            follower.removeListener(listener)
            follower.release()
        }
    }
    LaunchedEffect(follower) {
        var lastSeekAt: Long? = null
        while (isActive) {
            // Buffering or paused audio pauses the picture; buffering video never holds the audio.
            val advancing = audio.isPlaying
            if (follower.playWhenReady != advancing) follower.playWhenReady = advancing
            val now = SystemClock.elapsedRealtime()
            val action = VideoFollower.evaluate(
                audioMs = audio.currentPosition,
                videoMs = follower.currentPosition,
                audioSpeed = audio.playbackParameters.speed,
                videoSpeed = follower.playbackParameters.speed,
                audioAdvancing = advancing,
                nowMs = now,
                lastSeekAtMs = lastSeekAt,
            )
            when (action) {
                is FollowAction.Seek -> {
                    follower.seekTo(action.positionMs)
                    lastSeekAt = now
                }
                is FollowAction.SetSpeed -> follower.playbackParameters = PlaybackParameters(action.speed)
                FollowAction.Ignore -> Unit
            }
            delay(VideoFollower.TICK_MS)
        }
    }
    // Fades in on its first frame, as the cover's video background does (isVideoReady).
    val alpha by animateFloatAsState(if (shown && !broken) 1f else 0f, tween(400), label = "ritmeVideoAlpha")
    VideoSurface(follower, Modifier.graphicsLayer { this.alpha = alpha })
    if (broken) StageNote(stringResource(R.string.ritme_video_unavailable))
}

/** A TextureView (it turns with RotatingFrame, a SurfaceView would not) letterboxed to the video's own shape. */
@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val textureView = remember(player) { TextureView(context) }
    val aspect = remember(player) {
        AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(textureView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
    val frame = remember(player) {
        FrameLayout(context).apply {
            // Display only: taps belong to the player around it (double tap to like, swipes).
            isEnabled = false
            isClickable = false
            isFocusable = false
            addView(aspect, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        }
    }
    DisposableEffect(player, textureView) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = aspect.fit(videoSize)
        }
        aspect.fit(player.videoSize)
        player.addListener(listener)
        player.setVideoTextureView(textureView)
        onDispose {
            player.removeListener(listener)
            // only clears it if this view is still the player's
            player.clearVideoTextureView(textureView)
        }
    }
    AndroidView(factory = { frame }, modifier = modifier.fillMaxSize())
}

private fun AspectRatioFrameLayout.fit(size: VideoSize) {
    if (size.width > 0 && size.height > 0) setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
}

@Composable
private fun StageThumbnail(url: String?) {
    if (url == null) return
    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/** Why there is no picture: the sheets' secondary line, white at 60 %, with an optional chip under it. */
@Composable
private fun BoxScope.StageNote(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RitmeSecondaryText(
            text = text,
            color = Color.White.copy(alpha = RitmeSecondaryAlpha),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        action?.invoke()
    }
}
```

- [ ] **Step 9: Put the stage in the cover slot (MorphingCover)**

In `app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt`:

1. Replace `    val isDiscStyle = coverStyle.isDisc && !alternateStyle` with:

```kotlin
    // Ritme watch-together (spec 4.2): the room's current entry is a video and the player holds it.
    // Its stage takes the cover's place: no disc, no canvas, and no search for "the official music
    // video" by title, which showed some other video for non-music entries (spec 1.2).
    val ritmeStage = com.example.musicfy.ui.screens.ritme.video.rememberRitmeStageTarget(trackInfo.mediaId)

    val isDiscStyle = coverStyle.isDisc && !alternateStyle && ritmeStage == null
```

2. Replace `    val playVideoBackground = !alternateStyle && (forceVideoBackground || playVideoPref || trackIsVideo)` with:

```kotlin
    val playVideoBackground = ritmeStage == null && !alternateStyle && (forceVideoBackground || playVideoPref || trackIsVideo)
```

3. Replace `    val isVideoActive = !isDiscStyle && playVideoBackground && videoInfo != null` with:

```kotlin
    // the mini player's thumbnail widens to 16:9 for a room video too
    val isVideoActive = ritmeStage != null || (!isDiscStyle && playVideoBackground && videoInfo != null)
```

4. Replace `                val canvasEnabled = rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true).value && !alternateStyle` with:

```kotlin
                val canvasEnabled = rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true).value &&
                    !alternateStyle && ritmeStage == null
```

5. Directly after the block

```kotlin
                if (!isDiscStyle && playVideoBackground && videoInfo != null) {
                    YouTubeVideoBackground(
                        …
                    )
                }
```

(the closing `}` of that `if`), add:

```kotlin
                // Only on the open player: the mini player keeps the (16:9) thumbnail.
                if (ritmeStage != null && !alternateStyle) {
                    val stageShown by remember { derivedStateOf { progressProvider() > 0.5f } }
                    if (stageShown) {
                        com.example.musicfy.ui.screens.ritme.video.RitmeVideoStage(
                            target = ritmeStage,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = (1f - lyricsProgressProvider()).coerceIn(0f, 1f) },
                        )
                    }
                }
```

(`derivedStateOf`, `remember`, `graphicsLayer`, `fillMaxSize` are already imported in this file.)

- [ ] **Step 10: Run the Ritme tests, then assemble one flavor**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*' --tests 'com.example.musicfy.ui.screens.ritme.*'
```

Expected: PASS. Then:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalFossDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeStageTarget.kt app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoStreams.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoStage.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmePill.kt app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeStageTargetTest.kt
git commit -m "feat(ritme-android): synced video stage in the cover slot for room videos; no title search or canvas for them" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Full screen (`RotatingFrame`), its button, and "See video"

**Files:**
- Create: `app/src/main/res/drawable/fullscreen_exit.xml`
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoFullscreen.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/MainActivity.kt` (mount)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/BottomSheetPlayer.kt` (chip, `SEE_VIDEO`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt` (no cover stage while full screen)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/player/menu/PlayerActionMenu.kt` ("See video" row; library rows off for URL items)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`

**Interfaces:**
- Consumes: `RitmeStageTarget`, `rememberRitmeStageTarget`, `RitmeVideoStage`, `RitmeChip`, `RitmeChipFill` (Task 7); `RotatingFrame`, `StyleCircle`, `TransportCircles`, `rememberStyleTones` (StyleKit); `RitmePill`.
- Produces: `object RitmeFullscreen { var open: Boolean }`; `@Composable fun RitmeVideoFullscreenHost(window: android.view.Window, playerBarsBehavior: () -> Int)`; `@Composable fun RitmeFullscreenButton(loadedMediaId: String, alphaProvider: () -> Float, modifier: Modifier = Modifier)`.

No new pure logic here (it is all composition over Task 7's tested target); the check is the assemble plus the device list.

- [ ] **Step 1: The glyph**

Create `app/src/main/res/drawable/fullscreen_exit.xml` (Material Icons `fullscreen_exit`, the same 24 dp set as `fullscreen.xml`):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24.0"
    android:viewportHeight="24.0"
    android:tint="?attr/colorControlNormal">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,8L16,5h-2v5h5L19,8h-3z"/>
</vector>
```

- [ ] **Step 2: Strings**

English (`values/ritme_strings.xml`):

```xml
    <string name="ritme_video_fullscreen">Full screen</string>
    <string name="ritme_video_exit_fullscreen">Exit full screen</string>
    <string name="ritme_see_video">See video</string>
```

Indonesian (`values-in/ritme_strings.xml`):

```xml
    <string name="ritme_video_fullscreen">Layar penuh</string>
    <string name="ritme_video_exit_fullscreen">Keluar layar penuh</string>
    <string name="ritme_see_video">Lihat video</string>
```

- [ ] **Step 3: Full screen**

Create `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoFullscreen.kt`:

```kotlin
package com.example.musicfy.ui.screens.ritme.video

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.musicfy.LocalPlayerConnection
import com.example.musicfy.R
import com.example.musicfy.ritme.RitmeConfig
import com.example.musicfy.ritme.video.RitmeStageTarget
import com.example.musicfy.ui.player.styles.RotatingFrame
import com.example.musicfy.ui.player.styles.StyleCircle
import com.example.musicfy.ui.player.styles.TransportCircles
import com.example.musicfy.ui.player.styles.rememberStyleTones
import com.example.musicfy.ui.screens.ritme.RitmeChip
import com.example.musicfy.ui.screens.ritme.RitmeChipFill
import com.example.musicfy.ui.screens.ritme.RitmePill
import com.example.musicfy.ui.theme.InterFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether the room's video is shown full screen (spec 4.2, flow F). Main thread; one per app. */
object RitmeFullscreen {
    var open by mutableStateOf(false)
}

/** The quarter turn's length (RotatingFrame's spring settles in about this). */
private const val TURN_MS = 450L
/** The side bar: wide enough for the "Ritme · N" chip. */
private val BarWidth = 132.dp
private val BarButtonSize = 36.dp

/**
 * Mounted once in MainActivity, above the player and under the popup sheets. Shows the room's video
 * full screen while [RitmeFullscreen.open] and a stage target exists; closes itself when the room
 * moves on to a song or this device leaves.
 */
@Composable
fun RitmeVideoFullscreenHost(window: Window, playerBarsBehavior: () -> Int) {
    if (!RitmeConfig.ENABLED) return
    val connection = LocalPlayerConnection.current ?: return
    val track by connection.uiState.trackInfo.collectAsState()
    val target = rememberRitmeStageTarget(track.mediaId)
    LaunchedEffect(target == null) { if (target == null) RitmeFullscreen.open = false }
    if (!RitmeFullscreen.open || target == null) return
    RitmeVideoFullscreen(target, track.title, window, playerBarsBehavior)
}

/**
 * The video turned a quarter (RotatingFrame, the activity stays portrait: spec D4/U2), system bars
 * hidden (transient by swipe), the room's controls as a thin bar beside the video, never over it.
 * A tap anywhere but a button hides or shows the bar. Back or the exit button turns it upright.
 */
@Composable
private fun RitmeVideoFullscreen(target: RitmeStageTarget, title: String, window: Window, playerBarsBehavior: () -> Int) {
    val scope = rememberCoroutineScope()
    // Starts upright and turns, so opening plays RotatingFrame's quarter turn.
    var rotated by remember { mutableStateOf(false) }
    var barShown by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { rotated = true }
    val close: () -> Unit = {
        if (rotated) {
            rotated = false
            scope.launch {
                delay(TURN_MS)
                RitmeFullscreen.open = false
            }
        }
    }
    BackHandler(onBack = close)
    val barsBehavior by rememberUpdatedState(playerBarsBehavior)
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // The open player keeps the navigation bar hidden itself (MainActivity): only the status bar returns.
            controller.systemBarsBehavior = barsBehavior()
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    val tones = rememberStyleTones()
    RotatingFrame(
        rotated = rotated,
        tones = tones,
        background = { Box(Modifier.fillMaxSize().background(Color.Black)) },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { barShown = !barShown }) },
    ) { _ ->
        Row(modifier = Modifier.fillMaxSize()) {
            RitmeVideoStage(target = target, modifier = Modifier.weight(1f).fillMaxHeight())
            AnimatedVisibility(
                visible = barShown,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(BarWidth)
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    val exitLabel = stringResource(R.string.ritme_video_exit_fullscreen)
                    StyleCircle(
                        icon = R.drawable.fullscreen_exit,
                        tones = tones,
                        size = BarButtonSize,
                        onClick = close,
                        modifier = Modifier.semantics {
                            contentDescription = exitLabel
                            role = Role.Button
                        },
                    )
                    Text(
                        text = title,
                        color = tones.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    TransportCircles(tones = tones, size = BarButtonSize, vertical = true)
                    RitmePill(alphaProvider = { 1f })
                }
            }
        }
    }
}

/**
 * "Full screen" on the open flagship player, in the top-right slot RitmeCornerButton uses outside a
 * room: the same chip, with the existing fullscreen glyph. Only while a room video is current.
 */
@Composable
fun RitmeFullscreenButton(loadedMediaId: String, alphaProvider: () -> Float, modifier: Modifier = Modifier) {
    if (rememberRitmeStageTarget(loadedMediaId) == null) return
    val alpha by rememberUpdatedState(alphaProvider)
    val visible by remember { derivedStateOf { alpha() > 0.05f } }
    // Not there while invisible, so an invisible chip never takes a tap.
    if (!visible || RitmeFullscreen.open) return
    RitmeChip(
        text = stringResource(R.string.ritme_video_fullscreen),
        containerColor = RitmeChipFill,
        icon = R.drawable.fullscreen,
        onClick = { RitmeFullscreen.open = true },
        modifier = modifier.graphicsLayer { this.alpha = alpha() },
    )
}
```

- [ ] **Step 4: Mount it in MainActivity**

In `MainActivity.kt`, find the call `com.example.musicfy.ui.component.PopupSheetHost(` (inside `setContent`). Its content lambda ends with the `}` right above the blank line before the comment `// action feedback ("Added to Queue", "Undo"...) drops in over the sheets too`. Insert directly above that `}` (so it is the last child of the `PopupSheetHost` content):

```kotlin
                    // Ritme watch-together: the room's video full screen, over the player, under the sheets.
                    com.example.musicfy.ui.screens.ritme.video.RitmeVideoFullscreenHost(
                        window = window,
                        playerBarsBehavior = { window.playerNavBarBehavior() },
                    )
```

(`playerNavBarBehavior` is the private top-level function at the end of `MainActivity.kt`; it is visible in that file.)

- [ ] **Step 5: BottomSheetPlayer**

In `BottomSheetPlayer.kt`:

1. After `val ritme = com.example.musicfy.ui.screens.ritme.rememberRitme()` add:

```kotlin
        // The room's current video, once the player holds it: "See video" becomes full screen.
        val ritmeStage = com.example.musicfy.ui.screens.ritme.video.rememberRitmeStageTarget(trackInfo.mediaId)
```

2. In `menuActionActive`, replace the `SEE_VIDEO` line with:

```kotlin
            com.example.musicfy.ui.player.studio.PlayerMenuAction.SEE_VIDEO ->
                seeVideo || com.example.musicfy.ui.screens.ritme.video.RitmeFullscreen.open
```

3. In `onMenuAction`, replace the `SEE_VIDEO` line with:

```kotlin
                // In a Ritme room with a video current, "See video" opens it full screen (spec U9).
                com.example.musicfy.ui.player.studio.PlayerMenuAction.SEE_VIDEO -> {
                    if (ritmeStage != null) com.example.musicfy.ui.screens.ritme.video.RitmeFullscreen.open = true
                    else seeVideo = !seeVideo
                }
```

4. Right after the `RitmeCornerButton(...)` call (inside `sharedContent`), add:

```kotlin
                // In a room the corner is free (the pill is top-centre): "Full screen" while a room video is current.
                if (playerStyle.flagshipFamily && !studioEngaged) com.example.musicfy.ui.screens.ritme.video.RitmeFullscreenButton(
                    loadedMediaId = trackInfo.mediaId,
                    alphaProvider = { ((progressProvider() - 0.5f) * 2f).coerceIn(0f, 1f) * (1f - lyricsProgress) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = statusBarTopInset + 4.dp, end = PlayerHorizontalPadding),
                )
```

- [ ] **Step 6: MorphingCover hands the video over**

In the stage block added in Task 7 Step 9.5, change the condition to:

```kotlin
                if (ritmeStage != null && !alternateStyle && !com.example.musicfy.ui.screens.ritme.video.RitmeFullscreen.open) {
```

so only one stage (one follower, one texture) exists at a time.

- [ ] **Step 7: PlayerActionMenu ("Other menu", used by every style)**

In `PlayerActionMenu.kt`:

1. After `val songId = currentMetadata?.id` add:

```kotlin
    // A Ritme URL entry is no YouTube song: it cannot go into a playlist or be downloaded.
    val ritmeUrl = com.example.musicfy.ritme.entries.RitmeEntries.isUrlMediaId(songId)
```

2. In the "add to playlist" and "download music" `MenuRow`s change `enabled = currentMetadata != null,` to `enabled = currentMetadata != null && !ritmeUrl,`.

3. Inside `if (com.example.musicfy.ritme.RitmeConfig.ENABLED) { … }`, after the Ritme `MenuRow(...)`, add:

```kotlin
                    // The other player styles have no video stage: "See video" opens it full screen (spec U9).
                    val ritmeStage = com.example.musicfy.ui.screens.ritme.video.rememberRitmeStageTarget(trackInfo.mediaId)
                    if (ritmeStage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        MenuRow(
                            icon = R.drawable.slow_motion_video,
                            title = stringResource(R.string.ritme_see_video),
                            onClick = {
                                onDismiss()
                                com.example.musicfy.ui.screens.ritme.video.RitmeFullscreen.open = true
                            },
                        )
                    }
```

- [ ] **Step 8: Assemble one flavor**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalGmsDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/drawable/fullscreen_exit.xml app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/video/RitmeVideoFullscreen.kt app/src/main/kotlin/com/example/musicfy/MainActivity.kt app/src/main/kotlin/com/example/musicfy/ui/player/BottomSheetPlayer.kt app/src/main/kotlin/com/example/musicfy/ui/player/MorphingCover.kt app/src/main/kotlin/com/example/musicfy/ui/player/menu/PlayerActionMenu.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml
git commit -m "feat(ritme-android): full-screen room video on RotatingFrame; full-screen chip; See video in every style" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 16:9 queue rows with "Video"/"LIVE"

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActions.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt` (`EntryRow`)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`
- Test: `app/src/test/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActionsTest.kt`

**Interfaces:**
- Consumes: `RitmeEntries.isVideo/isPlayable` (Task 1).
- Produces: `internal enum class EntryTag { NONE, VIDEO, LIVE }`, `internal fun entryTag(entry: QueueEntry): EntryTag`.

- [ ] **Step 1: Write the failing test**

Add to `RitmeRoomActionsTest.kt`:

```kotlin
    @Test
    fun videoRowsAreTaggedVideoOrLive() {
        assertEquals(EntryTag.NONE, entryTag(entry("a")))
        assertEquals(EntryTag.NONE, entryTag(entry("m", durationMs = 0))) // a song of unknown length is not a live video
        assertEquals(EntryTag.VIDEO, entryTag(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertEquals(EntryTag.LIVE, entryTag(entry("l", kind = MediaKind.YOUTUBE_VIDEO, durationMs = 0)))
        assertEquals(
            EntryTag.LIVE,
            entryTag(entry("u", kind = MediaKind.URL, videoId = "", url = "https://cdn.example.com/live.m3u8", durationMs = 0)),
        )
    }
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ui.screens.ritme.RitmeRoomActionsTest'
```

Expected: compilation FAILS (`EntryTag` unresolved).

- [ ] **Step 3: Implement the tag**

At the end of `RitmeRoomActions.kt` add (and `import com.example.musicfy.ritme.protocol.isLive`):

```kotlin
/** The short word in front of a row's subtitle (spec 4.2): videos say "Video", live videos "LIVE". */
internal enum class EntryTag { NONE, VIDEO, LIVE }

internal fun entryTag(entry: QueueEntry): EntryTag = when {
    !RitmeEntries.isVideo(entry) -> EntryTag.NONE
    entry.isLive -> EntryTag.LIVE
    else -> EntryTag.VIDEO
}
```

- [ ] **Step 4: Run the test to see it pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Strings**

English:

```xml
    <string name="ritme_tag_video">Video</string>
    <string name="ritme_tag_live">LIVE</string>
    <string name="ritme_tagged" translatable="false">%1$s · %2$s</string>
```

Indonesian:

```xml
    <string name="ritme_tag_video">Video</string>
    <string name="ritme_tag_live">LIVE</string>
```

- [ ] **Step 6: The row**

In `RitmeRoomSheet.kt` add near the other row constants:

```kotlin
/** A video row's art: 16:9 at the same 44 dp height (SubpageVideoCell's shape, the row's corners). */
private val RowVideoArtWidth = RowArtSize * 16f / 9f
```

In `EntryRow`, replace

```kotlin
    val artwork = remember(entry.thumbnailUrl) { entry.thumbnailUrl?.resize(RowArtPx, RowArtPx) }
```

with

```kotlin
    val tag = entryTag(entry)
    val wide = tag != EntryTag.NONE
    // URL entries' pictures are never loaded (any host could track who looks): a placeholder instead.
    val artwork = remember(entry.thumbnailUrl, entry.kind) {
        when {
            entry.kind == MediaKind.URL -> null
            wide -> entry.thumbnailUrl
            else -> entry.thumbnailUrl?.resize(RowArtPx, RowArtPx)
        }
    }
```

replace `        Box(modifier = Modifier.size(RowArtSize)) {` with

```kotlin
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = if (wide) RowVideoArtWidth else RowArtSize, height = RowArtSize),
        ) {
```

and directly after the `AsyncImage(...)` call inside that box add:

```kotlin
            if (entry.kind == MediaKind.URL) {
                Icon(
                    painter = painterResource(R.drawable.web_link),
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.5f),
                    modifier = Modifier.size(ActionIconSize),
                )
            }
```

Replace

```kotlin
            val what = if (playable) entry.artist else stringResource(R.string.ritme_unsupported)
```

with

```kotlin
            val what = when {
                !playable -> stringResource(R.string.ritme_unsupported) // UNKNOWN (or a link this app refuses)
                tag == EntryTag.VIDEO -> stringResource(R.string.ritme_tagged, stringResource(R.string.ritme_tag_video), entry.artist)
                tag == EntryTag.LIVE -> stringResource(R.string.ritme_tagged, stringResource(R.string.ritme_tag_live), entry.artist)
                else -> entry.artist
            }
```

Add the import `com.example.musicfy.ritme.protocol.MediaKind` if missing (`Icon`, `painterResource`, `Alignment` are already imported in this file).

- [ ] **Step 7: Run the Ritme UI tests, then assemble one flavor**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ui.screens.ritme.*'
```

Expected: PASS. Then:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalFdroidDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActions.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomSheet.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml app/src/test/kotlin/com/example/musicfy/ui/screens/ritme/RitmeRoomActionsTest.kt
git commit -m "feat(ritme-android): 16:9 queue rows for videos with Video/LIVE; Not supported only for unknown kinds" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

**End of phase 2b-1.** Run the full Ritme suite once (`--tests 'com.example.musicfy.ritme.*' --tests 'com.example.musicfy.ui.screens.ritme.*'`), then the device check list items marked **2b-1** below.

---

# Phase 2b-2: Adding videos

### Task 10: `YouTubeLinks` (shared fixture)

**Files:**
- Create: `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinks.kt`
- Test: `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinksTest.kt`

**Interfaces:**
- Produces: `data class YouTubeLink(videoId: String, music: Boolean)`; `YouTubeLinks.parse(raw: String): YouTubeLink?` — same verdicts as `ritme-web/src/net/video-link.ts` `parseYouTubeLink` on `fixtures/rules/youtube-links.json`.

- [ ] **Step 1: Write the failing test**

Create `modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinksTest.kt`:

```kotlin
package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The cases in fixtures/rules/youtube-links.json; ritme-web's video-link.test.ts checks the same file. */
class YouTubeLinksTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/youtube-links.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVideoId() {
        assertTrue("fixture has cases", cases.size >= 40)
        val mismatches = cases.mapNotNull { case ->
            val input = case.getValue("input").jsonPrimitive.content
            val expectedId = case.getValue("videoId").jsonPrimitive.contentOrNull
            val expectedMusic = case["music"]?.jsonPrimitive?.boolean ?: false
            val actual = YouTubeLinks.parse(input)
            val ok = actual?.videoId == expectedId && (expectedId == null || actual?.music == expectedMusic)
            "'$input': expected $expectedId (music=$expectedMusic), was $actual".takeUnless { ok }
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :modules:ritme-protocol:test --tests 'com.example.musicfy.ritme.protocol.YouTubeLinksTest'
```

Expected: compilation FAILS (`YouTubeLinks` unresolved).

- [ ] **Step 3: Implement**

Create `modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinks.kt`:

```kotlin
package com.example.musicfy.ritme.protocol

import java.net.URI
import java.net.URLDecoder

/** A YouTube video named by a pasted link; [music] for a music.youtube.com link (added as a song). */
data class YouTubeLink(val videoId: String, val music: Boolean)

/**
 * The video in a YouTube link (spec 4.1): watch?v=, youtu.be/, /shorts/, /live/, /embed/ on
 * youtube.com (www., m., music.) and youtube-nocookie.com, and vnd.youtube:. A host may end in one
 * dot, path keywords match in any case, the id is case-sensitive; t= and list= are ignored and
 * /embed/videoseries is a playlist. Kotlin twin of ritme-web's parseYouTubeLink, checked against the
 * same fixture (fixtures/rules/youtube-links.json).
 */
object YouTubeLinks {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val VND = Regex("^vnd\\.youtube:(?://)?([A-Za-z0-9_-]{11})(?:[?&#].*)?$", RegexOption.IGNORE_CASE)
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be",
    )
    private val PATH_ID_PREFIXES = setOf("shorts", "live", "embed")
    private const val PLAYLIST_EMBED = "videoseries"

    fun parse(raw: String): YouTubeLink? {
        val text = raw.trim()
        VND.matchEntire(text)?.let { return YouTubeLink(it.groupValues[1], music = false) }
        if (text.isEmpty()) return null
        val uri = runCatching { URI(if (HAS_SCHEME.containsMatchIn(text)) text else "https://$text") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (host !in HOSTS) return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val keyword = segments.firstOrNull()?.lowercase()
        val id = when {
            host == "youtu.be" -> segments.singleOrNull()
            segments.size == 1 && keyword == "watch" -> queryParam(uri.rawQuery, "v")
            segments.size == 2 && keyword in PATH_ID_PREFIXES ->
                segments[1].takeUnless { keyword == "embed" && it.lowercase() == PLAYLIST_EMBED }
            else -> null
        }
        return id?.takeIf(VIDEO_ID::matches)?.let { YouTubeLink(it, music = host == "music.youtube.com") }
    }

    private fun queryParam(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            if (part.substringBefore('=') == name) URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8) else null
        }
}
```

- [ ] **Step 4: Run the module's tests**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :modules:ritme-protocol:test
```

Expected: PASS (if a fixture case mismatches, the assertion lists it; fix the parser, never the fixture, unless the web test disagrees too).

- [ ] **Step 5: Commit**

```bash
git add modules/ritme-protocol/src/main/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinks.kt modules/ritme-protocol/src/test/kotlin/com/example/musicfy/ritme/protocol/YouTubeLinksTest.kt
git commit -m "feat(ritme-protocol): YouTubeLinks parser on the shared youtube-links fixture" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: What pasted text is, and the entries it becomes

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinks.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinksTest.kt`, `app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt`

**Interfaces:**
- Consumes: `YouTubeLinks.parse` (Task 10), `MediaUrlRules.check`.
- Produces: `sealed interface VideoLink { YouTube(videoId, music); Direct(url, fileName, domain, streaming: Boolean); Rejected(problem: VideoLink.Problem) }`, `enum class VideoLink.Problem { UNRECOGNIZED, PAGE, UNSAFE }`; `RitmeVideoLinks.classify(raw: String): VideoLink?` (null = not a link: search for it); `RitmeEntries.LIVE_FROM_SECONDS = 43_200`; `RitmeEntries.toVideoEntry(videoId: String, title: String, channel: String, thumbnailUrl: String?, durationSeconds: Int?, music: Boolean = false): NewEntry?`; `RitmeEntries.toUrlEntry(url: String, title: String, domain: String, durationMs: Long = 0): NewEntry?`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinksTest.kt`:

```kotlin
package com.example.musicfy.ritme.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeVideoLinksTest {

    @Test
    fun youTubeLinks() {
        assertEquals(VideoLink.YouTube("dQw4w9WgXcQ", music = false), RitmeVideoLinks.classify(" https://youtu.be/dQw4w9WgXcQ?t=3 "))
        assertEquals(VideoLink.YouTube("dQw4w9WgXcQ", music = true), RitmeVideoLinks.classify("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun directMediaLinksLoseTheirFragment() {
        assertEquals(
            VideoLink.Direct("https://cdn.example.com/films/My%20Movie.mp4", "My Movie.mp4", "cdn.example.com", streaming = false),
            RitmeVideoLinks.classify("https://cdn.example.com/films/My%20Movie.mp4#t=10"),
        )
        assertEquals(
            VideoLink.Direct("https://www.example.com/live/master.m3u8?token=abc", "master.m3u8", "example.com", streaming = true),
            RitmeVideoLinks.classify("www.example.com/live/master.m3u8?token=abc"),
        )
        assertEquals(true, (RitmeVideoLinks.classify("https://cdn.example.com/v/manifest.mpd") as VideoLink.Direct).streaming)
    }

    @Test
    fun pagesUnsafeLinksAndOtherSchemesAreRefused() {
        assertEquals(VideoLink.Rejected(VideoLink.Problem.PAGE), RitmeVideoLinks.classify("https://example.com/watch/123"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNSAFE), RitmeVideoLinks.classify("http://192.168.1.1/v.mp4"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNSAFE), RitmeVideoLinks.classify("https://user:pw@cdn.example.com/v.mp4"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED), RitmeVideoLinks.classify("ftp://example.com/v.mp4"))
    }

    @Test
    fun wordsAreASearchNotALink() {
        assertNull(RitmeVideoLinks.classify("never gonna give you up"))
        assertNull(RitmeVideoLinks.classify("lofi"))
        assertNull(RitmeVideoLinks.classify("   "))
    }
}
```

Add to `RitmeEntriesTest.kt`:

```kotlin
    @Test
    fun aYouTubeVideoBecomesAVideoEntry() {
        assertEquals(
            NewEntry(
                videoId = "dQw4w9WgXcQ",
                title = "Never Gonna",
                artist = "Rick Astley",
                thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
                durationMs = 213_000,
                kind = MediaKind.YOUTUBE_VIDEO,
            ),
            RitmeEntries.toVideoEntry("dQw4w9WgXcQ", " Never Gonna ", "Rick Astley", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", 213),
        )
        assertEquals(MediaKind.YOUTUBE_MUSIC, RitmeEntries.toVideoEntry("dQw4w9WgXcQ", "T", "A", null, 213, music = true)?.kind)
    }

    @Test
    fun aMissingZeroOrTwelveHourLengthIsLive() {
        assertEquals(0L, RitmeEntries.toVideoEntry("jfKfPfyJRdk", "Lofi", "Lofi Girl", null, null)?.durationMs)
        assertEquals(0L, RitmeEntries.toVideoEntry("jfKfPfyJRdk", "Lofi", "Lofi Girl", null, 0)?.durationMs)
        assertEquals(0L, RitmeEntries.toVideoEntry("jfKfPfyJRdk", "Lofi", "Lofi Girl", null, 12 * 3600)?.durationMs)
        assertEquals((12 * 3600 - 1) * 1000L, RitmeEntries.toVideoEntry("jfKfPfyJRdk", "Lofi", "Lofi Girl", null, 12 * 3600 - 1)?.durationMs)
    }

    @Test
    fun videoEntriesTheServerWouldRefuseAreNotMade() {
        assertNull(RitmeEntries.toVideoEntry("", "T", "A", null, 10))
        assertNull(RitmeEntries.toVideoEntry("dQw4w9WgXcQ", "​ ", "A", null, 10))
        // a picture from any other host is dropped, not sent
        assertNull(RitmeEntries.toVideoEntry("dQw4w9WgXcQ", "T", "A", "https://evil.example/p.jpg", 10)?.thumbnailUrl)
    }

    @Test
    fun aDirectLinkBecomesAUrlEntryWithoutAPicture() {
        assertEquals(
            NewEntry(
                videoId = "",
                title = "My Movie.mp4",
                artist = "cdn.example.com",
                thumbnailUrl = null,
                durationMs = 0,
                kind = MediaKind.URL,
                url = "https://cdn.example.com/films/My%20Movie.mp4",
            ),
            RitmeEntries.toUrlEntry("https://cdn.example.com/films/My%20Movie.mp4", "My Movie.mp4", "cdn.example.com"),
        )
        assertEquals("cdn.example.com", RitmeEntries.toUrlEntry("https://cdn.example.com/v.mp4", "  ", "cdn.example.com")?.title)
        assertNull(RitmeEntries.toUrlEntry("http://10.0.0.1/v.mp4", "T", "10.0.0.1"))
    }
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.video.RitmeVideoLinksTest' --tests 'com.example.musicfy.ritme.entries.RitmeEntriesTest'
```

Expected: compilation FAILS.

- [ ] **Step 3: `RitmeVideoLinks`**

Create `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinks.kt`:

```kotlin
package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.YouTubeLinks
import java.net.URI
import java.net.URLDecoder

/** What "Add video" makes of pasted text (spec 4.6). */
sealed interface VideoLink {
    data class YouTube(val videoId: String, val music: Boolean) : VideoLink

    /** A media file or stream; [streaming] for HLS/DASH (no file length to read). */
    data class Direct(val url: String, val fileName: String, val domain: String, val streaming: Boolean) : VideoLink

    enum class Problem { UNRECOGNIZED, PAGE, UNSAFE }

    data class Rejected(val problem: Problem) : VideoLink
}

object RitmeVideoLinks {
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val FILE = Regex("\\.(mp4|webm|m4v|mov)$", RegexOption.IGNORE_CASE)
    private val STREAM = Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE)

    /** The link in [raw], or null when it is words (then the sheet searches for them). */
    fun classify(raw: String): VideoLink? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        YouTubeLinks.parse(text)?.let { return VideoLink.YouTube(it.videoId, it.music) }
        val hasScheme = HAS_SCHEME.containsMatchIn(text)
        // "lofi" or "never gonna give you up" are a search; "example.com/v.mp4" is a link
        val looksLikeLink = text.none(Char::isWhitespace) && (hasScheme || text.substringBefore('/').contains('.'))
        if (!looksLikeLink) return null
        val candidate = (if (hasScheme) text else "https://$text").substringBefore('#')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED)
        val path = uri.rawPath.orEmpty()
        val streaming = STREAM.containsMatchIn(path)
        if (!streaming && !FILE.containsMatchIn(path)) return VideoLink.Rejected(VideoLink.Problem.PAGE)
        if (MediaUrlRules.check(candidate) != null) return VideoLink.Rejected(VideoLink.Problem.UNSAFE)
        val domain = uri.host.orEmpty().lowercase().removeSuffix(".").removePrefix("www.")
        val last = path.substringAfterLast('/')
        val fileName = runCatching { URLDecoder.decode(last, Charsets.UTF_8) }.getOrDefault(last).trim().take(RitmeEntries.MAX_TEXT)
        return VideoLink.Direct(candidate, fileName, domain, streaming)
    }
}
```

Note: `URLDecoder` turns `+` into a space; file names rarely contain `+`, and the title is editable.

- [ ] **Step 4: Entries**

In `RitmeEntries.kt` add after `MAX_DURATION_MS`:

```kotlin
    /** Same rule as the server's /api/video (2a Decision 10): a length of 12 h or more is a live stream's age. */
    const val LIVE_FROM_SECONDS = 12 * 3600
```

and add these functions after `toNewEntry`:

```kotlin
    /**
     * A YOUTUBE_VIDEO entry from a video's info ("Add video", spec 4.6), or YOUTUBE_MUSIC for a
     * music.youtube.com link; null if the server would refuse it. A length that is missing, 0 or at
     * least [LIVE_FROM_SECONDS] means live (durationMs = 0).
     */
    fun toVideoEntry(
        videoId: String,
        title: String,
        channel: String,
        thumbnailUrl: String?,
        durationSeconds: Int?,
        music: Boolean = false,
    ): NewEntry? {
        if (!VIDEO_ID.matches(videoId)) return null
        val cleanTitle = truncate(title.trim())
        if (!hasVisibleText(cleanTitle)) return null
        val seconds = durationSeconds?.takeIf { it in 1 until LIVE_FROM_SECONDS }
        return NewEntry(
            videoId = videoId,
            title = cleanTitle,
            artist = truncate(channel.trim()),
            thumbnailUrl = safeThumbnail(thumbnailUrl),
            durationMs = seconds?.times(1000L) ?: 0,
            kind = if (music) MediaKind.YOUTUBE_MUSIC else MediaKind.YOUTUBE_VIDEO,
        )
    }

    /**
     * A URL entry (spec 4.6): artist = the site's domain, no picture (spec 4.7), the title falls back
     * to the domain; null when MediaUrlRules refuses the url.
     */
    fun toUrlEntry(url: String, title: String, domain: String, durationMs: Long = 0): NewEntry? {
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
        )
    }
```

- [ ] **Step 5: Run the tests to see them pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinks.kt app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeVideoLinksTest.kt app/src/test/kotlin/com/example/musicfy/ritme/entries/RitmeEntriesTest.kt
git commit -m "feat(ritme-android): classify pasted links; video and URL entries that pass the server's rules" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `RitmeController.addEntries` and starting a room with entries

**Files:**
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt`
- Modify: `app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt` (`initialQueueOf`)
- Modify: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeEntrySheet.kt`, `RitmeUi.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt`

**Interfaces:**
- Produces: `RitmeController.addEntries(entries: List<NewEntry>, playNext: Boolean)`; `RitmeController.openStartWithEntries(entries: List<NewEntry>)`; `RitmeController.startRoom(name: String, startWith: List<MediaMetadata?>? = null, startWithEntries: List<NewEntry>? = null)`; `RitmeSheetRequest.Kind.Entry(code, startWith, startWithEntries: List<NewEntry>? = null)`; `RitmeEntries.initialQueueOf(entries: List<NewEntry>): InitialQueue`; `PopupSheetState.showRitmeEntrySheet(ritme, code, startWith, startWithEntries: List<NewEntry>? = null)`.

- [ ] **Step 1: Write the failing tests**

Add to `RitmeControllerTest.kt` (imports: `com.example.musicfy.ritme.protocol.MediaKind`, `com.example.musicfy.ritme.protocol.NewEntry`):

```kotlin
    private val clip = NewEntry(videoId = "dQw4w9WgXcQ", title = "Clip", artist = "Channel", durationMs = 60_000, kind = MediaKind.YOUTUBE_VIDEO)

    @Test
    fun addEntriesSendsOneAddAndSaysSo() {
        inRoom(playing)
        controller.addEntries(listOf(clip), playNext = true)
        assertEquals(listOf<Command>(Command.Add(listOf(clip), playNext = true)), commands)
        assertEquals(listOf<RitmeToast>(RitmeToast.Added(1)), toasts.shown)
    }

    @Test
    fun addEntriesIsRefusedUpFrontWhenGuestsCannotAdd() {
        inRoom(playing.copy(settings = RoomSettings(guestsCanAdd = false)), memberId = "m2")
        controller.addEntries(listOf(clip), playNext = false)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<RitmeToast>(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN)), toasts.shown)
    }

    @Test
    fun startingWithEntriesPutsThemInTheCreate() {
        controller.startRoom("Budi", startWithEntries = listOf(clip))
        assertEquals(JoinIntent.Create("Budi", listOf(clip), 0, 0), connector.last.intent)
    }

    @Test
    fun openStartWithEntriesAsksForTheStartSheetOutsideARoomAndTheRoomSheetInside() {
        controller.openStartWithEntries(listOf(clip))
        assertEquals(RitmeSheetRequest.Kind.Entry(startWithEntries = listOf(clip)), controller.sheetRequest.value?.kind)
        inRoom(playing)
        controller.openStartWithEntries(listOf(clip))
        assertEquals(RitmeSheetRequest.Kind.Room, controller.sheetRequest.value?.kind)
    }
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.RitmeControllerTest'
```

Expected: compilation FAILS.

- [ ] **Step 3: Implement**

`RitmeEntries.kt`, after `buildInitialQueue`:

```kotlin
    /** A room's starting queue of ready-made entries (a shared video, spec 4.6): at most [MAX_QUEUE], from the start. */
    fun initialQueueOf(entries: List<NewEntry>): InitialQueue {
        val kept = entries.take(MAX_QUEUE)
        return InitialQueue(kept.take(MAX_BATCH), 0, 0, kept.drop(MAX_BATCH).chunked(MAX_BATCH), 0)
    }
```

`RitmeController.kt`:
- add `import com.example.musicfy.ritme.protocol.NewEntry`;
- `Kind.Entry` becomes `data class Entry(val code: String? = null, val startWith: List<MediaMetadata?>? = null, val startWithEntries: List<NewEntry>? = null) : Kind`;
- add after `openStartWith`:

```kotlin
    /** The start sheet with ready-made [entries] (a shared video, spec 4.6); in a room, the room sheet. */
    fun openStartWithEntries(entries: List<NewEntry>) {
        if (session.state.value.inRoom) return request(RitmeSheetRequest.Kind.Room)
        if (entries.isEmpty()) return notifier.show(RitmeToast.NotAvailable)
        request(RitmeSheetRequest.Kind.Entry(startWithEntries = entries))
    }

    /**
     * Entries from "Add video" (spec 4.6): only ever from its buttons, never automatically. The same
     * permission check and toasts as songs.
     */
    fun addEntries(entries: List<NewEntry>, playNext: Boolean) {
        val state = session.state.value
        val room = state.room ?: return
        if (!addAllowed(room, state.memberId)) return
        if (entries.isEmpty()) return notifier.show(RitmeToast.NotAvailable)
        notifier.show(if (session.add(entries, playNext)) RitmeToast.Added(entries.size) else RitmeToast.Reconnecting)
    }
```

- `startRoom` gains the parameter and a first branch after the casting check:

```kotlin
    fun startRoom(name: String, startWith: List<MediaMetadata?>? = null, startWithEntries: List<NewEntry>? = null) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        if (startWithEntries != null) {
            session.create(settings.serverUrl, name, RitmeEntries.initialQueueOf(startWithEntries))
            return
        }
        // … the existing body from `val order = …` on, unchanged
```

`RitmeEntrySheet.kt`:
- `RitmeEntryForm(initialCode: String?, val startWith: List<MediaMetadata?>?, val startWithEntries: List<NewEntry>? = null)`;
- in `submit`, `ritme.controller.startRoom(name, startWith, startWithEntries)`;
- in `RitmeEntryContent`, both `form.startWith != null` / `form.startWith == null` checks become `(form.startWith != null || form.startWithEntries != null)` / `(form.startWith == null && form.startWithEntries == null)`.

`RitmeUi.kt`:
- `fun PopupSheetState.showRitmeEntrySheet(ritme: RitmeUi, code: String?, startWith: List<MediaMetadata?>?, startWithEntries: List<NewEntry>? = null)` and `RitmeEntryForm(code, startWith, startWithEntries)`;
- the opener: `is RitmeSheetRequest.Kind.Entry -> popup.showRitmeEntrySheet(ritme, kind.code, kind.startWith, kind.startWithEntries)`.

- [ ] **Step 4: Run the Ritme tests**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:testUniversalGmsDebugUnitTest --tests 'com.example.musicfy.ritme.*' --tests 'com.example.musicfy.ui.screens.ritme.*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/musicfy/ritme/RitmeController.kt app/src/main/kotlin/com/example/musicfy/ritme/entries/RitmeEntries.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeEntrySheet.kt app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeUi.kt app/src/test/kotlin/com/example/musicfy/ritme/RitmeControllerTest.kt
git commit -m "feat(ritme-android): add ready-made entries to a room; start a room with them" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: General YouTube video search (U3)

**Files:**
- Create: `providers/innertube/src/main/kotlin/com/music/innertube/pages/WebVideoSearchPage.kt`
- Modify: `providers/innertube/src/main/kotlin/com/music/innertube/InnerTube.kt` (`searchWeb`)
- Modify: `providers/innertube/src/main/kotlin/com/music/innertube/YouTube.kt` (`searchWebVideos`)
- Test: `providers/innertube/src/test/kotlin/com/music/innertube/WebVideoSearchPageTest.kt` (first test in this module; `testImplementation(libs.junit)` is already declared, kotlinx-serialization-json comes with `ktor.serialization.json`)

**Interfaces:**
- Produces: `data class WebVideoItem(id: String, title: String, channel: String, durationSeconds: Int?, thumbnailUrl: String?, live: Boolean)`; `WebVideoSearchPage.parse(root: JsonObject): List<WebVideoItem>`; `WebVideoSearchPage.parseLength(text: String?): Int?`; `InnerTube.searchWeb(query: String, params: String?)`; `YouTube.searchWebVideos(query: String): Result<List<WebVideoItem>>`.

- [ ] **Step 1: Write the failing parser test**

Create `providers/innertube/src/test/kotlin/com/music/innertube/WebVideoSearchPageTest.kt`:

```kotlin
package com.music.innertube

import com.music.innertube.pages.WebVideoItem
import com.music.innertube.pages.WebVideoSearchPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebVideoSearchPageTest {

    // Trimmed from a real WEB search response (sp=EgIQAQ%3D%3D): a shelf, an ad slot, a video, a live stream.
    private val response = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"adSlotRenderer":{}},
            {"videoRenderer":{"videoId":"dQw4w9WgXcQ",
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc","width":480,"height":270}]},
              "title":{"runs":[{"text":"Rick Astley - Never Gonna Give You Up"}]},
              "ownerText":{"runs":[{"text":"Rick Astley"}]},
              "lengthText":{"simpleText":"3:33"}}},
            {"videoRenderer":{"videoId":"jfKfPfyJRdk",
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/jfKfPfyJRdk/hq720_live.jpg"}]},
              "title":{"runs":[{"text":"lofi hip hop radio"}]},
              "longBylineText":{"runs":[{"text":"Lofi Girl"}]},
              "badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW","label":"LIVE"}}]}}
          ]}},
          {"continuationItemRenderer":{}}
        ]}}}}}
    """.trimIndent()

    @Test
    fun videosAndLiveStreamsAreRead() {
        val items = WebVideoSearchPage.parse(Json.parseToJsonElement(response).jsonObject)
        assertEquals(
            listOf(
                WebVideoItem("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "Rick Astley", 213, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false),
                WebVideoItem("jfKfPfyJRdk", "lofi hip hop radio", "Lofi Girl", null, "https://i.ytimg.com/vi/jfKfPfyJRdk/hq720_live.jpg", live = true),
            ),
            items,
        )
    }

    @Test
    fun lengths() {
        assertEquals(213, WebVideoSearchPage.parseLength("3:33"))
        assertEquals(3_723, WebVideoSearchPage.parseLength("1:02:03"))
        assertNull(WebVideoSearchPage.parseLength("LIVE"))
        assertNull(WebVideoSearchPage.parseLength(null))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :providers:innertube:test --tests 'com.music.innertube.WebVideoSearchPageTest'
```

Expected: compilation FAILS.

- [ ] **Step 3: The parser**

Create `providers/innertube/src/main/kotlin/com/music/innertube/pages/WebVideoSearchPage.kt`:

```kotlin
package com.music.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A video from YouTube's general search (client WEB), for Ritme's "Add video" (spec U3). */
data class WebVideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val live: Boolean,
)

/** Reads `twoColumnSearchResultsRenderer` → `itemSectionRenderer` → `videoRenderer`; everything else (ads, shelves) is skipped. */
object WebVideoSearchPage {

    fun parse(root: JsonObject): List<WebVideoItem> {
        val sections = root.obj("contents")?.obj("twoColumnSearchResultsRenderer")?.obj("primaryContents")
            ?.obj("sectionListRenderer")?.arr("contents").orEmpty()
        return sections
            .flatMap { (it as? JsonObject)?.obj("itemSectionRenderer")?.arr("contents").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.obj("videoRenderer")?.let(::video) }
    }

    private fun video(r: JsonObject): WebVideoItem? {
        val id = r.str("videoId") ?: return null
        val title = r.obj("title")?.firstRun() ?: r.obj("title")?.str("simpleText") ?: return null
        val channel = (r.obj("ownerText") ?: r.obj("longBylineText"))?.firstRun().orEmpty()
        val length = r.obj("lengthText")?.str("simpleText")
        val liveBadge = r.arr("badges").orEmpty().any {
            (it as? JsonObject)?.obj("metadataBadgeRenderer")?.str("style") == "BADGE_STYLE_TYPE_LIVE_NOW"
        }
        val seconds = parseLength(length)
        val live = liveBadge || seconds == null
        val thumbnail = (r.obj("thumbnail")?.arr("thumbnails")?.lastOrNull() as? JsonObject)?.str("url")?.substringBefore('?')
        return WebVideoItem(id, title, channel, if (live) null else seconds, thumbnail, live)
    }

    /** "3:33" → 213, "1:02:03" → 3723; anything else null. */
    fun parseLength(text: String?): Int? {
        val parts = text?.split(':') ?: return null
        if (parts.size !in 2..3) return null
        return parts.fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: return null) }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.firstRun(): String? = (arr("runs")?.firstOrNull() as? JsonObject)?.str("text")
}
```

- [ ] **Step 4: Run the test to see it pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: The request**

In `InnerTube.kt`, after `search(...)`, add a WEB search to www.youtube.com. It never sends the music.youtube.com login cookie or its origin:

```kotlin
    /** YouTube's general search as the WEB client (Ritme "Add video", spec U3): no login, www.youtube.com origin. */
    suspend fun searchWeb(query: String, params: String?) = withRetry {
        httpClient.post("https://www.youtube.com/youtubei/v1/search") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", YouTubeClient.WEB.clientId)
                append("X-YouTube-Client-Version", YouTubeClient.WEB.clientVersion)
                append("X-Origin", "https://www.youtube.com")
                append("Referer", "https://www.youtube.com/")
                visitorData?.let { append("X-Goog-Visitor-Id", it) }
            }
            userAgent(YouTubeClient.WEB.userAgent)
            setBody(SearchBody(context = YouTubeClient.WEB.toContext(locale, visitorData, null), query = query, params = params))
        }
    }
```

(Check the imports `contentType`, `ContentType`, `userAgent`, `headers` already exist in `InnerTube.kt`; `ytClient` uses the same ones.)

In `YouTube.kt`, after `search(...)`:

```kotlin
    /** Videos for [query] from YouTube's general search (videos only: sp=EgIQAQ%3D%3D). */
    suspend fun searchWebVideos(query: String): Result<List<com.music.innertube.pages.WebVideoItem>> = runCatching {
        val root = innerTube.searchWeb(query, params = "EgIQAQ%3D%3D").body<kotlinx.serialization.json.JsonObject>()
        com.music.innertube.pages.WebVideoSearchPage.parse(root)
    }
```

- [ ] **Step 6: Verify against the live endpoint once (manual, not a test)**

Add a throwaway `main` in your scratchpad or run from a debug build: `YouTube.searchWebVideos("lofi")` must return a non-empty list. If YouTube answers 400 for the percent-encoded params, pass `"EgIQAQ=="` instead and record which one works in the KDoc. The sheet (Task 14) falls back to the YouTube Music "Videos" filter whenever this fails or is empty, so a later YouTube change degrades, it does not break.

- [ ] **Step 7: Run the module tests and commit**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :providers:innertube:test
```

Expected: PASS.

```bash
git add providers/innertube/src/main/kotlin/com/music/innertube/pages/WebVideoSearchPage.kt providers/innertube/src/main/kotlin/com/music/innertube/InnerTube.kt providers/innertube/src/main/kotlin/com/music/innertube/YouTube.kt providers/innertube/src/test/kotlin/com/music/innertube/WebVideoSearchPageTest.kt
git commit -m "feat(innertube): general YouTube video search (WEB client) for Ritme Add video" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: The "Add video" sheet (link, search, preview)

This task is given as a detailed outline: its pure part (the model) is complete; the composable follows `RitmeEntrySheet` line by line and should be written by copying that file's structure. Expand it into steps the same way as Tasks 1–12 when executing.

**Files:**
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModel.kt` (pure, tested)
- Create: `app/src/main/kotlin/com/example/musicfy/ritme/video/RitmeVideoLookup.kt` (Android: YouTube + probe)
- Create: `app/src/main/kotlin/com/example/musicfy/ui/screens/ritme/RitmeAddVideoSheet.kt`
- Modify: `RitmeController.kt` (`openAddVideo`, `Kind.AddVideo`), `RitmeUi.kt` (opener), `RitmeRoomSheet.kt` (row "Tambah video"), strings
- Test: `app/src/test/kotlin/com/example/musicfy/ritme/video/RitmeAddVideoModelTest.kt`, `RitmeControllerTest.kt`

**Interfaces:**
- Consumes: `RitmeVideoLinks.classify`, `RitmeEntries.toVideoEntry/toUrlEntry` (Task 11), `addEntries`/`openStartWithEntries` (Task 12), `YouTube.searchWebVideos` (Task 13).
- Produces:

```kotlin
data class VideoChoice(val videoId: String, val title: String, val channel: String, val durationSeconds: Int?, val thumbnailUrl: String?, val live: Boolean)

interface VideoLookup {
    /** YouTube.queue(listOf(videoId)) → title, channel, length (null = live/unknown), picture; null if not found. */
    suspend fun info(videoId: String): VideoChoice?
    /** U3: YouTube.searchWebVideos, falling back to YouTube.search(q, FILTER_VIDEO) when it fails or is empty. */
    suspend fun search(query: String): List<VideoChoice>
    /** A progressive file's length via MediaMetadataRetriever (8 s timeout); null for streams or on failure. */
    suspend fun probeDurationMs(url: String): Long?
}

sealed interface AddVideoState {
    data object Empty : AddVideoState
    data object Loading : AddVideoState
    /** [editableTitle]: URL entries only (default: the file name), sent as the entry's title. */
    data class Preview(val entry: NewEntry, val editableTitle: Boolean) : AddVideoState
    data class Results(val items: List<VideoChoice>) : AddVideoState
    data class Problem(val kind: AddVideoProblem) : AddVideoState
}
enum class AddVideoProblem { PAGE, UNSAFE, UNRECOGNIZED, NOT_FOUND, NO_RESULTS }

class RitmeAddVideoModel(private val lookup: VideoLookup) {
    suspend fun resolve(text: String): AddVideoState
    fun choose(choice: VideoChoice): AddVideoState   // a search result tapped → Preview
}
```

- [ ] **Model rules (write the tests first, `RitmeAddVideoModelTest` with a fake `VideoLookup`):**
  - blank text → `Empty`;
  - `RitmeVideoLinks.classify` null (words) → `lookup.search(text.trim())` → `Results` (empty → `Problem(NO_RESULTS)`);
  - `VideoLink.YouTube` → `lookup.info(id)` → `toVideoEntry(..., music = link.music)` → `Preview(entry, editableTitle = false)`; null info or null entry → `Problem(NOT_FOUND)` (nothing is added on failure, no fake title: spec 8);
  - `VideoLink.Direct` → `durationMs = if (streaming) 0 else lookup.probeDurationMs(url) ?: 0` → `toUrlEntry(url, fileName, domain, durationMs)` → `Preview(entry, editableTitle = true)`; null → `Problem(UNSAFE)`;
  - `VideoLink.Rejected(PAGE/UNSAFE/UNRECOGNIZED)` → `Problem(PAGE/UNSAFE/UNRECOGNIZED)`;
  - `choose(choice)` → `toVideoEntry(choice.videoId, choice.title, choice.channel, choice.thumbnailUrl, choice.durationSeconds)` → `Preview(…, false)` or `Problem(NOT_FOUND)`.
  - Test each rule with one assertion; test that `search` is never called for a link and `info` never for words.

- [ ] **`RitmeVideoLookup` (Android)** maps `SongItem` (`YouTube.queue`, and the fallback search's `filterIsInstance<SongItem>()`) to `VideoChoice(id, title, artists.joinToString { it.name }, duration, thumbnail, live = duration == null)` and `WebVideoItem` to `VideoChoice` field by field. `probeDurationMs`: `withTimeoutOrNull(8_000) { withContext(Dispatchers.IO) { MediaMetadataRetriever().run { try { setDataSource(url, emptyMap()); extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() } } } }`, wrapped in `runCatching`. No cookies are involved (`setDataSource(url, headers)` sends none).

- [ ] **Controller:** `RitmeSheetRequest.Kind.AddVideo(val text: String? = null)`; `fun openAddVideo(text: String? = null)`: in a room without `canAdd` → `CommandFailed(ADD_FORBIDDEN)` toast and no request; otherwise `request(Kind.AddVideo(text))` (outside a room too: start mode). Tests: guest with `guestsCanAdd = false` gets the toast and no request; host gets `Kind.AddVideo("https://youtu.be/dQw4w9WgXcQ")`.

- [ ] **Sheet** (`RitmeAddVideoSheet.kt`), structured exactly like `RitmeEntrySheet.kt`:
  - `internal class RitmeAddVideoForm(initialText: String?)` (`@Stable`): `text`, `state: AddVideoState`, `title` (for `editableTitle`), `fun entry(): NewEntry?` (Preview's entry with `title` applied when editable, re-checked through `RitmeEntries.toUrlEntry`).
  - `fun PopupSheetState.showRitmeAddVideoSheet(ritme: RitmeUi, text: String?)` with a one-at-a-time guard like `openEntry`/`roomSheetOpen` (a second request fills the open sheet's field instead).
  - Content column: `SheetLabel(stringResource(R.string.ritme_add_video))`, `Spacer(8.dp)`, one `SheetTextField(value = form.text, placeholder = stringResource(R.string.ritme_add_video_hint), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { … resolve now }))`; a `LaunchedEffect(form.text)` that waits 600 ms (debounce) and sets `form.state = Loading` then `model.resolve(text)`; for a pasted link (initial text) resolve at once.
  - Preview: one row in the room-sheet row metrics (`RowMinHeight` 60 dp, 16:9 art 78 × 44 dp with `RoundedCornerShape(8.dp)`, `ink.copy(alpha = 0.10f)` background, `web_link` placeholder for URL), title 15 sp SemiBold, `RitmeSecondaryText("channel · 3:33")` or `"channel · LIVE"`/domain. For `editableTitle`: `SheetLabel(stringResource(R.string.ritme_add_video_title_optional))` + `SheetTextField`.
  - Results: the same row per `VideoChoice`, clickable (`indication = ripple(color = ink)`, 14 dp highlight shape) → `form.state = model.choose(it)`.
  - Problems: `RitmeSecondaryText` in `MaterialTheme.colorScheme.error` with `liveRegion = Polite` (as `RitmeEntrySheet`'s error).
  - Button bar (`buttonBar` of `showWithHandle`): in a room, a `Row(horizontalArrangement = spacedBy(10.dp))` with two `SheetPillButton(Modifier.weight(1f))`: "Tambah" → `ritme.controller.addEntries(listOf(entry), playNext = false)`, "Putar berikutnya" → `playNext = true`; both dismiss the sheet after sending. Outside a room: one `SheetPillButton("Mulai Ritme dengan video ini")` → `ritme.controller.openStartWithEntries(listOf(entry))` then dismiss. While there is no preview the buttons are **not shown** (a disabled clickable still eats taps; nothing must look tappable).
  - Opener branch in `RitmeSheetOpener`: `is RitmeSheetRequest.Kind.AddVideo -> popup.showRitmeAddVideoSheet(ritme, kind.text)`.
- [ ] **Room sheet row:** in `RitmeRoomContent`'s actions, inside the existing `if (RitmePermissions.canAdd(room, me))` group, a second `Material3MenuItemData` right below "Add songs": icon `R.drawable.link`, title `R.string.ritme_add_video`, `onClick = onAddVideo`. `RitmeRoomContent` gets `onAddVideo: () -> Unit`; both callers (`showRitmeRoomSheet` and `RitmeQueuePage`) pass `{ handle?.dismiss(); ritme.controller.openAddVideo() }` (the queue page: just `ritme.controller.openAddVideo()`).
- [ ] **Strings** (English / Indonesian): `ritme_add_video` "Add video" / "Tambah video"; `ritme_add_video_hint` "Paste a link or search videos" / "Tempel link atau cari video"; `ritme_add_video_title_optional` "Title (optional)" / "Judul (opsional)"; `ritme_add` "Add" / "Tambah"; `ritme_play_next_button` "Play next" / "Putar berikutnya"; `ritme_start_with_video` "Start Ritme with this video" / "Mulai Ritme dengan video ini"; `ritme_add_video_page` "This link is a web page, not a video file." / "Link ini halaman web, bukan file video."; `ritme_add_video_unsafe` "This link can't be added" / "Link ini tidak bisa ditambahkan"; `ritme_add_video_unrecognized` "That isn't a link we can use" / "Link ini tidak dikenali"; `ritme_add_video_not_found` "Video not found" / "Video tidak ditemukan"; `ritme_add_video_no_results` "No videos found" / "Tidak ada video yang cocok"; `ritme_live` "LIVE" (`translatable="false"`).
- [ ] **Checks:** `RitmeAddVideoModelTest`, `RitmeControllerTest`, all Ritme tests green; `:app:assembleUniversalGmsDebug`. Commit: `feat(ritme-android): Add video sheet with link, search and preview`.

---

### Task 15: "Tambah ke Ritme" in the share sheet

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/example/musicfy/MainActivity.kt` (`handleDeepLinkIntent`)
- Modify: `app/src/main/res/values/ritme_strings.xml`, `app/src/main/res/values-in/ritme_strings.xml`

**Interfaces:**
- Consumes: `RitmeController.openJoinLink` (unchanged, first), `RitmeController.openAddVideo(text)` (Task 14).

- [ ] **Step 1: Strings**

English: `<string name="ritme_share_target">Add to Ritme</string>`; Indonesian: `<string name="ritme_share_target">Tambah ke Ritme</string>`.

- [ ] **Step 2: The alias**

In `AndroidManifest.xml`, after the existing `.MainActivityStatic` `<activity-alias … />` (an alias must come after its target activity):

```xml
        <!-- Ritme (spec 4.6, U5): always offered; outside a room it offers to start one with the video. -->
        <activity-alias
            android:name=".RitmeShareAlias"
            android:exported="true"
            android:icon="@mipmap/ic_launcher"
            android:roundIcon="@mipmap/ic_launcher_round"
            android:label="@string/ritme_share_target"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity-alias>
```

The existing "Musicfy" `SEND text/plain` filter on `MainActivity` stays unchanged.

- [ ] **Step 3: Route it**

In `MainActivity.handleDeepLinkIntent`, inside `if (ritmeLinks && com.example.musicfy.ritme.RitmeConfig.ENABLED) { … }`, after the `openJoinLink` block (a room link in the text still wins, spec 4.6.3):

```kotlin
            // "Add to Ritme" from the share sheet: the Add video sheet with the link in it (spec 4.6).
            if (intent.component?.className?.endsWith(".RitmeShareAlias") == true) {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                intent.data = null
                intent.removeExtra(Intent.EXTRA_TEXT)
                ritmeController.openAddVideo(shared)
                return
            }
```

Shared text such as "Watch this! https://youtu.be/…" holds more than the link: before calling, take the first `http(s)://\S+` match if there is one (`Regex("https?://\\S+").find(shared)?.value ?: shared`), so the sheet's field holds just the link.

- [ ] **Step 4: Assemble each flavor once (separate invocations, `pgrep` before each)**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
pgrep -f '[j]ava.*gradle-wrapper\.jar' && echo BUSY || ./gradlew --console=plain --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dkotlin.daemon.jvm.options=-Xmx2g" -Pkotlin.daemon.jvmargs=-Xmx2g :app:assembleUniversalFossDebug
```

then the same with `:app:assembleUniversalGmsDebug`, then `:app:assembleUniversalFdroidDebug`. Expected: BUILD SUCCESSFUL each.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/com/example/musicfy/MainActivity.kt app/src/main/res/values/ritme_strings.xml app/src/main/res/values-in/ritme_strings.xml
git commit -m "feat(ritme-android): Tambah ke Ritme share target" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Daftar cek manual di perangkat (untuk pengguna)

Siapkan 2 HP Android dengan build ini dan 1 browser (web Ritme). Satu HP jadi host. Rekam dengan HP ketiga kalau perlu menilai sinkron bibir dan suara.

**Fase 2b-1 (pemutaran)**

- [ ] **Video YouTube (entri dari web):** di web, tambahkan link video YouTube lewat tab Video. Di kedua HP, panggung video menggantikan cover, video utuh (tidak terpotong), dan bibir selaras dengan suara. Selisih antar-HP terdengar < 100 ms.
- [ ] **Bug cover salah video:** aktifkan setelan "video background", lalu putar video non-musik (misalnya vlog) di room. Yang tampil harus video itu sendiri, bukan "video musik resmi" lain. Tidak ada animasi canvas di belakangnya.
- [ ] **Shorts (9:16), video 21:9, video 4:3:** panggung menampilkan video utuh dengan bar hitam, tidak terpotong.
- [ ] **Live YouTube:** live stream mulai di siaran terkini, bukan di awal. Jeda room 30 detik lalu putar lagi: kedua HP kembali ke siaran terkini (selisih antar-HP 1–3 detik wajar).
- [ ] **Bug live mulai dari 0 (entri URL):** dari web, tambahkan link `.m3u8` live. Android mulai di siaran terkini, bukan di awal jendela DVR.
- [ ] **Entri URL mp4 dan m3u8 (VOD):** dari web, tambahkan link `.mp4` dan `.m3u8`. Android memutar gambar dan suara, sinkron dengan HP lain. Maju/mundur oleh host diikuti semua.
- [ ] **Entri DASH (`.mpd`):** Android bisa memutarnya (web memang menolak `.mpd`).
- [ ] **Link URL gagal (403/404):** muncul "Video ini tidak bisa diputar di perangkat ini". Host melihat tombol "Lewati", tamu tanpa izin kontrol tidak melihatnya. Room tidak menunggu.
- [ ] **Link ke alamat lokal** (misalnya server di `192.168.x.x` lewat nama domain publik): tidak diputar.
- [ ] **Layar mati 1 menit saat video:** suara tetap jalan. Layar hidup lagi → video muncul kembali dan menyusul dalam < 2 detik.
- [ ] **Aplikasi ke background lalu kembali:** sama seperti di atas. Layar tidak mati sendiri selama video diputar dan panggung terlihat.
- [ ] **Mini player:** saat video aktif, thumbnail mini player melebar ke 16:9. Kolapskan pemutar → video berhenti didekode, suara tetap jalan.
- [ ] **Layar penuh:** chip "Layar penuh" di pojok kanan atas pemutar. Animasi putar seperempat, bar sistem tersembunyi, bar kontrol tipis di samping video (tombol keluar, judul, sebelumnya/putar/berikutnya, pill "Ritme · N"). Ketuk di luar tombol → bar sembunyi/tampil. Tombol keluar dan tombol Back → kembali tegak dengan animasi. Status bar kembali, navigation bar tetap tersembunyi selama pemutar terbuka.
- [ ] **Pill di layar penuh:** ketuk pill → room sheet muncul di atas video. Saat koneksi putus, pill menampilkan "Menyambung ulang…".
- [ ] **Room pindah ke lagu saat layar penuh:** layar penuh tertutup sendiri.
- [ ] **Gaya pemutar lain (Poster/Timeline/Lyrics):** tidak ada panggung. Menu ••• → "Other menu" → "Lihat video" membuka layar penuh. Di flagship, tombol "See video"/"Lihat video" juga membuka layar penuh saat video room aktif.
- [ ] **Gaya cover piringan (disc):** saat video room aktif, piringan tidak tampil, panggung tampil.
- [ ] **Baris antrean room:** entri video memakai thumbnail 16:9 dengan "Video · channel · oleh …" atau "LIVE · …". Entri URL tanpa gambar (ikon link). "Belum didukung" hanya untuk jenis yang tidak dikenal.
- [ ] **Menu pemutar untuk entri URL:** "add to playlist" dan "download" redup.
- [ ] **Data seluler:** video YouTube turun ke 480p. Di Wi-Fi 720p.
- [ ] **Live URL + koreksi kecepatan:** setelah jeda/putar beberapa kali, pemutaran live tidak tersendat atau berganti-ganti kecepatan.
- [ ] **Keluar room:** antrean lokal kembali normal, lagu lokal biasa bisa diputar, cache lagu utuh. Lagu lokal yang berupa video musik tetap diputar seperti sebelumnya.
- [ ] **Musik Ritme tidak berubah:** room berisi lagu saja tetap sinkron seperti sebelumnya. Tamu tanpa izin kontrol tetap hanya bisa jeda di perangkat sendiri. Saat `guestsCanAdd` mati, tamu tidak bisa menambah.

**Fase 2b-2 (menambah video)**

- [ ] **Room sheet → "Tambah video" → tempel link YouTube:** pratinjau (thumbnail, judul, channel, durasi/LIVE) → "Tambah" → masuk antrean, toast "ditambahkan". "Putar berikutnya" menaruhnya setelah entri yang sedang diputar.
- [ ] **Link `music.youtube.com`:** masuk sebagai lagu (bukan video).
- [ ] **Link `.mp4` langsung:** pratinjau dengan ikon link dan domain, kolom "Judul (opsional)" berisi nama file, durasi terbaca. Judul yang diubah dipakai di antrean.
- [ ] **Link `.m3u8`:** bisa ditambahkan (durasi tidak diketahui, diperlakukan seperti live).
- [ ] **Link halaman web biasa:** pesan "Link ini halaman web, bukan file video." dan tidak ada tombol tambah.
- [ ] **Link `http://192.168.1.1/v.mp4`:** "Link ini tidak bisa ditambahkan".
- [ ] **Ketik kata kunci** (misalnya "lofi"): hasil video YouTube umum dengan thumbnail 16:9. Ketuk hasil → pratinjau → "Tambah".
- [ ] **Tidak pernah menambah otomatis:** menempel link atau memilih hasil saja tidak menambah apa pun sampai tombol ditekan.
- [ ] **Tamu saat `guestsCanAdd` mati:** baris "Tambah video" tidak tampil. Share ke Ritme → toast "Host menutup penambahan video".
- [ ] **Share dari aplikasi YouTube → "Tambah ke Ritme" (di room):** Musicfy terbuka dengan sheet "Tambah video", link terisi, pratinjau termuat.
- [ ] **Share dari aplikasi YouTube → "Tambah ke Ritme" (di luar room):** sheet pratinjau dengan tombol "Mulai Ritme dengan video ini" → form mulai Ritme → room baru dimulai dengan video itu.
- [ ] **Share teks berisi link room Ritme ke "Tambah ke Ritme":** tetap membuka form gabung room (perilaku #1).
- [ ] **Target share "Musicfy" yang lama:** tetap bekerja seperti sebelumnya.
- [ ] **Tampilan:** semua sheet, baris, tombol, chip, ikon, dan font sama dengan bagian aplikasi lain (tidak ada gaya baru).
- [ ] **VOD .m3u8:** jeda lalu putar, cek apakah mulai dari awal (keterbatasan sampai #3).

**Perbaikan review akhir**

- [ ] **Share saat Musicfy sudah terbuka (warm), di dalam room:** share dari YouTube → "Tambah ke Ritme" → sheet "Tambah video" terbuka dengan link terisi dan pratinjau termuat (tidak kosong).
- [ ] **Share saat Musicfy sudah terbuka (warm), di luar room:** sama, sheet dalam mode mulai dengan link terisi.
- [ ] **Share link room Ritme saat Musicfy sudah terbuka (warm) ke "Tambah ke Ritme":** form gabung room terbuka dengan kode terisi, tidak digantikan sheet "Tambah video" kosong.
- [ ] **Ganti mode gelap saat sheet "Tambah video" terbuka setelah share:** tidak ada yang diputar atau ditambahkan dengan sendirinya (di dalam room dan di luar room).
- [ ] **`.mp4` lambat di data seluler:** pratinjau menunjukkan durasi, atau pesan "Panjang video tidak terbaca, coba lagi" dengan tombol "Coba lagi". Tidak pernah LIVE.
- [ ] **Dua `.mp4` ditempel cepat berturut-turut, yang pertama dari host yang tidak merespons:** link kedua tetap terbaca durasinya (tidak ikut gagal).
- [ ] **Saat mengisi URL:** ciutkan dan buka lagi pemutar, matikan lalu nyalakan layar. Audio paling banyak tersendat sekali per perpindahan.
- [ ] **Geser sheet pemutar sampai kira-kira setengah saat video YouTube diputar:** video dan audio tetap normal.
- [ ] **`.mp4` mati (404) selama satu menit:** hitung toast-nya. Harusnya satu toast saja, lalu dicoba lagi makin jarang (5 → 15 → 60 detik).
- [ ] **YouTube live selama 2 menit:** lancar, atau "Video tidak tersedia".
- [ ] **HLS live dijeda 5 menit, lalu diputar:** kembali ke titik live bersama.
- [ ] **Wi-Fi mati 10 detik saat video YouTube diputar:** pemutaran pulih sendiri setelah Wi-Fi kembali.
- [ ] **Back dari layar penuh saat sheet room terbuka di atasnya:** Back menutup sheet dulu, lalu layar penuh.
- [ ] **Teks toast saat menambah video:** "1 video ditambahkan ke Ritme" (bukan "lagu"). Saat tamu tidak boleh menambah: "Host menutup penambahan video".

---

## Open questions for the user

1. **Stage size** (Decision 3): the video fits inside the existing cover slot (letterboxed). Is that acceptable, or should a later task resize the slot to the video's own ratio (9:16…21:9, ≤ 60 % of the screen) as the spec describes? Resizing touches `MorphingCover`'s morph geometry.
2. **Share icon** (Task 15): there is no Ritme icon asset, so the "Tambah ke Ritme" share target uses the app icon. Should a Ritme icon be made?
3. **Direct-link duration** (Decision 13): `.m3u8`/`.mpd` links added from Android get `durationMs = 0` until #3's playlist sniffer exists (no server fallback advance for them). OK for now?

## Decisions taken at execution start (2026-11-24, controller defaults; the user may revise)

1. Stage size: the video fits inside the existing cover slot with black bars. Resizing the slot to the video's own aspect ratio (spec §4.2) moves to the backlog.
2. Share target icon: use the app icon. No new Ritme icon.
3. Durations for links added on Android: `.mp4`/`.webm`/`.mov` are read with `MediaMetadataRetriever`. `.m3u8`/`.mpd` get duration 0 until the #3 probe exists.
4. Sharing outside a room takes two steps: preview sheet, then start sheet.
