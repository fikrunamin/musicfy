# Desktop D1a: Providers JVM + core:platform + core:settings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Musicfy's providers and the first core modules (platform dirs/logging, settings keys) plain Kotlin/JVM modules that both the Android app and a future desktop app can use, without changing Android behaviour.

**Architecture:** Every module in this plan is a `kotlin("jvm")` module (applied as `id("kotlin")`, the pattern `providers:canvas` and `modules:api` already use). The Android app consumes JVM modules directly. Anything that needs `Context`/Timber stays in `app/` behind an interface defined in the JVM module. Spec: `docs/superpowers/specs/2026-11-22-desktop-d1-core-modules-design.md`, especially "Revisi setelah pemetaan kode".

**Tech Stack:** Gradle 9.3.1 (wrapper), AGP 9.0.0, Kotlin 2.3.10, JDK 21, Ktor 3.4.0, DataStore 1.2.0 (`datastore-preferences-core`), JUnit 4.13.2, Hilt 2.59.1 (app only).

## Global Constraints

- Android behaviour must not change: same file locations, same preference key names, same datastore file name (`settings`).
- Package names of moved code stay the same (e.g. `com.example.musicfy.constants`) so no import changes are needed in `app/`.
- JVM modules: `kotlin { jvmToolchain(21) }`. No `android.*`, no Timber, no Hilt annotations.
- After every task, all three of these must succeed:
  `./gradlew assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug`
- Never edit `app/schemas/`, the Room database, or the stream resolver in this plan (that is D1b).
- Commit messages end with: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Prerequisites (once per machine)

- JDK 21 on `PATH` / `JAVA_HOME`. On this Mac none is installed. Install with
  `brew install openjdk@21` and export
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Android SDK at `~/Library/Android/sdk`. Create `local.properties` (gitignored) with
  `sdk.dir=/Users/<you>/Library/Android/sdk`, or export `ANDROID_HOME`.

## File Structure

| Path | Responsibility |
|---|---|
| `providers/{betterlyrics,innertube,kizzy,kugou,lastfm,lrclib,shazamkit,simpmusic,youlyplus}/build.gradle.kts` | Rewritten as JVM modules |
| `providers/*/src/main/AndroidManifest.xml` | Deleted (JVM modules have no manifest) |
| `providers/paxsenixlyrics/build.gradle.kts`, `.../Paxsenix.kt`, new `.../PaxLog.kt` | JVM module; `init(appVersion)`; java.util.logging |
| `app/.../lyrics/PaxSenixLyricsProvider.kt` | Calls `Paxsenix.init(BuildConfig.VERSION_NAME)` |
| `providers/artistvideo/build.gradle.kts`; delete `.../ArtistVideo.kt` | JVM module, unused composable removed |
| `core/platform/` (new) | `AppDirs`, `Logger`, `DesktopAppDirs`, `StdoutLogger` |
| `app/.../platform/AndroidAppDirs.kt`, `TimberLogger.kt`, `app/.../di/PlatformModule.kt` (new) | Android implementations + Hilt bindings |
| `core/settings/` (new) | `PreferenceKeys.kt` (moved), `SettingsDataStore.kt`, key-name golden test |
| `app/.../constants/PlatformDefaults.kt` (new) | `DefaultPlayerCoverStyle` (needs `Build.VERSION`) |
| `build.gradle.kts` (root) | `jvmTests` aggregate task |
| `.github/workflows/build_pr.yml`, `build.yml` | Unit tests + 3-flavor build |

---

### Task 1: Baseline

**Files:** none (verification only)

- [ ] **Step 1: Confirm toolchain**

Run: `java -version && ./gradlew --version`
Expected: `openjdk version "21...` and `Gradle 9.3.1`.

- [ ] **Step 2: Build all three flavors on the untouched branch**

Run: `./gradlew assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug`
Expected: `BUILD SUCCESSFUL`. If it fails, stop and report: D1a must start from a green baseline.

- [ ] **Step 3: Run existing unit tests**

Run: `./gradlew :app:testUniversalFossDebugUnitTest :providers:canvas:test`
Expected: `BUILD SUCCESSFUL`. Note any pre-existing failures in the PR description; do not fix them here.

- [ ] **Step 4: Install the baseline APK on a device/emulator and run the smoke checklist**

Run: `adb install -r app/build/outputs/apk/universalFoss/debug/*.apk`
Then: open app → home loads → search and play a song → lyrics show → library has data → settings intact.
Keep this install: later tasks are installed over it to test upgrades.

---

### Task 2: Convert nine providers to JVM modules

**Files:**
- Modify: `providers/{betterlyrics,innertube,kizzy,kugou,lastfm,lrclib,shazamkit,simpmusic,youlyplus}/build.gradle.kts`
- Delete: `providers/{betterlyrics,innertube,kizzy,kugou,lastfm,lrclib,shazamkit,simpmusic,youlyplus}/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing new.
- Produces: the same public Kotlin APIs as before, now as JVM jars. `:providers:paxsenixlyrics` (Task 3) depends on `:providers:betterlyrics` being JVM.

- [ ] **Step 1: Prove none of them touch Android APIs**

Run: `grep -rln "import android\|import androidx" providers/{betterlyrics,innertube,kizzy,kugou,lastfm,lrclib,shazamkit,simpmusic,youlyplus}/src`
Expected: no output.

- [ ] **Step 2: Rewrite each build file**

`providers/betterlyrics/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
```

`providers/innertube/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(libs.newpipeextractor)
    testImplementation(libs.junit)
}
```

`providers/kizzy/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.json)
    testImplementation(libs.junit)
}
```

`providers/kugou/build.gradle.kts` and `providers/lastfm/build.gradle.kts` (identical):
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    testImplementation(libs.junit)
}
```

`providers/lrclib/build.gradle.kts` and `providers/shazamkit/build.gradle.kts` (identical):
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    testImplementation(libs.junit)
}
```

`providers/simpmusic/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
```

`providers/youlyplus/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    testImplementation(libs.junit)
}
```

- [ ] **Step 3: Delete the manifests**

```bash
git rm providers/{betterlyrics,innertube,kizzy,kugou,lastfm,lrclib,shazamkit,simpmusic,youlyplus}/src/main/AndroidManifest.xml
```

- [ ] **Step 4: Compile the JVM modules**

Run: `./gradlew :providers:betterlyrics:build :providers:innertube:build :providers:kizzy:build :providers:kugou:build :providers:lastfm:build :providers:lrclib:build :providers:shazamkit:build :providers:simpmusic:build :providers:youlyplus:build`
Expected: `BUILD SUCCESSFUL`.
If a module fails on a `java.*` API, it was relying on Android desugaring classes. Report the exact error instead of working around it.

- [ ] **Step 5: Build the app (all flavors) and lint for API-level problems**

Run: `./gradlew assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug :app:lintUniversalFossDebug`
Expected: `BUILD SUCCESSFUL`, and no new `NewApi` errors in `app/build/reports/lint-results-universalFossDebug.html` compared to the baseline.

- [ ] **Step 6: Smoke test over the baseline install**

Run: `adb install -r app/build/outputs/apk/universalFoss/debug/*.apk`, then repeat the Task 1 Step 4 checklist. Check especially: search results load (innertube), lyrics load (lrclib/kugou/betterlyrics/simpmusic/youlyplus), and Discord RPC connects if enabled (kizzy).

- [ ] **Step 7: Commit**

```bash
git add providers
git commit -m "build: convert nine providers to plain Kotlin/JVM modules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: paxsenixlyrics → JVM (`init(appVersion)`, no Timber)

**Files:**
- Modify: `providers/paxsenixlyrics/build.gradle.kts`
- Modify: `providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/Paxsenix.kt` (header lines 1–75 and every `Timber.` call)
- Create: `providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/PaxLog.kt`
- Create: `providers/paxsenixlyrics/src/test/kotlin/com/music/paxsenix/PaxsenixInitTest.kt`
- Delete: `providers/paxsenixlyrics/src/main/AndroidManifest.xml` (if present)
- Modify: `app/src/main/kotlin/com/example/musicfy/lyrics/PaxSenixLyricsProvider.kt:21`

**Interfaces:**
- Consumes: `:providers:betterlyrics` as a JVM module (Task 2), `com.example.musicfy.betterlyrics.TTMLParser`.
- Produces: `object Paxsenix { fun init(appVersion: String); internal val userAgent: String; suspend fun getLyrics(...); suspend fun getAllLyrics(...) }`. Only `init`'s signature changes.

- [ ] **Step 1: Write the failing test**

`providers/paxsenixlyrics/src/test/kotlin/com/music/paxsenix/PaxsenixInitTest.kt`:
```kotlin
package com.music.paxsenix

import org.junit.Assert.assertEquals
import org.junit.Test

class PaxsenixInitTest {
    @Test
    fun userAgentCarriesTheVersionPassedToInit() {
        Paxsenix.init("8.0.0 build#1150")
        assertEquals("Musicfy/8.0.0 build#1150", Paxsenix.userAgent)
    }
}
```

- [ ] **Step 2: Convert the build file**

`providers/paxsenixlyrics/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(project(":providers:betterlyrics"))
    testImplementation(libs.junit)
}
```
Then: `git rm -f --ignore-unmatch providers/paxsenixlyrics/src/main/AndroidManifest.xml`

- [ ] **Step 3: Run the test and confirm it fails to compile**

Run: `./gradlew :providers:paxsenixlyrics:test`
Expected: FAIL. Compilation errors on `android.content.Context`, `timber.log.Timber`, and `userAgent`.

- [ ] **Step 4: Add the logger**

`providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/PaxLog.kt`:
```kotlin
package com.music.paxsenix

import java.util.logging.Level
import java.util.logging.Logger

/** Plain-JVM stand-in for the Timber calls this module used to make. */
internal object PaxLog {
    private val logger: Logger = Logger.getLogger("Paxsenix")

    fun d(message: String) = logger.fine(message)

    fun w(message: String) = logger.warning(message)

    fun e(t: Throwable?, message: String) = logger.log(Level.SEVERE, message, t)
}
```

- [ ] **Step 5: Replace `init(context)` in `Paxsenix.kt`**

Delete the imports `import android.content.Context` and `import timber.log.Timber`. Replace the block from `private var appVersion: String = "Unknown"` through the end of `fun init(...)` with:
```kotlin
    private var appVersion: String = "Unknown"

    internal val userAgent: String
        get() = "Musicfy/$appVersion"

    fun init(appVersion: String) {
        if (client != null) return

        synchronized(this) {
            if (client != null) return

            this.appVersion = appVersion.ifBlank { "Unknown" }
            PaxLog.d("Initializing Paxsenix with version: ${this.appVersion}")

            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 5_000
                    connectTimeoutMillis = 4_000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", userAgent)
                }

                expectSuccess = true
            }

            PaxLog.d("Paxsenix HTTP client initialized")
        }
    }
```

- [ ] **Step 6: Replace the remaining Timber calls**

Run: `sed -i '' 's/Timber\.d(/PaxLog.d(/g; s/Timber\.w(/PaxLog.w(/g; s/Timber\.e(/PaxLog.e(/g' providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/Paxsenix.kt`
Then check: `grep -n "Timber" providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/*.kt providers/paxsenixlyrics/src/main/kotlin/com/music/paxsenix/models/*.kt`
Expected: no output. Every former `Timber.e(e, "...")` call is now `PaxLog.e(e, "...")`, which matches the `(Throwable?, String)` signature.

- [ ] **Step 7: Run the test**

Run: `./gradlew :providers:paxsenixlyrics:test`
Expected: PASS (1 test).

- [ ] **Step 8: Update the app call site**

In `app/src/main/kotlin/com/example/musicfy/lyrics/PaxSenixLyricsProvider.kt`, replace `Paxsenix.init(context)` with `Paxsenix.init(BuildConfig.VERSION_NAME)`, and add `import com.example.musicfy.BuildConfig` if the file does not already import it. (The old code read `packageManager.getPackageInfo(...).versionName`, which equals `BuildConfig.VERSION_NAME`.)

- [ ] **Step 9: Build all flavors**

Run: `./gradlew assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Smoke test**

Install over the previous build. Settings → lyrics → enable Paxsenix → play a song with Apple Music lyrics → lyrics appear.

- [ ] **Step 11: Commit**

```bash
git add providers/paxsenixlyrics app/src/main/kotlin/com/example/musicfy/lyrics/PaxSenixLyricsProvider.kt
git commit -m "build: make paxsenixlyrics a JVM module (init takes app version, java.util.logging)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: artistvideo → JVM (remove the unused composable)

**Files:**
- Modify: `providers/artistvideo/build.gradle.kts`
- Delete: `providers/artistvideo/src/main/kotlin/com/music/vivi/artistvideo/ArtistVideo.kt`
- Delete: `providers/artistvideo/src/main/AndroidManifest.xml` (if present)

**Interfaces:**
- Consumes: nothing new.
- Produces: `object ArtistVideoCanvasProvider` unchanged (used by `app/.../viewmodels/ArtistViewModel.kt`).

- [ ] **Step 1: Prove the composable is unused**

Run: `grep -rn "artistvideo.ArtistVideo\b\|ArtistVideo(" app/src modules | grep -v "ArtistVideoCanvasProvider\|ArtistVideos\|ShowArtistVideoKey"`
Expected: no output. If anything prints, stop. The composable must then move to `app/` instead of being deleted.

- [ ] **Step 2: Delete it and convert the build file**

```bash
git rm providers/artistvideo/src/main/kotlin/com/music/vivi/artistvideo/ArtistVideo.kt
git rm -f --ignore-unmatch providers/artistvideo/src/main/AndroidManifest.xml
```

`providers/artistvideo/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
}
```

- [ ] **Step 3: Compile module and app**

Run: `./gradlew :providers:artistvideo:build assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Smoke test**

Install over the previous build. Open an artist page that has an artist video/canvas → it still shows.

- [ ] **Step 5: Commit**

```bash
git add providers/artistvideo
git commit -m "build: make artistvideo a JVM module and drop its unused composable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `core:platform` (AppDirs + Logger)

**Files:**
- Modify: `settings.gradle.kts` (add `include(":core:platform")`)
- Create: `core/platform/build.gradle.kts`
- Create: `core/platform/src/main/kotlin/com/example/musicfy/core/platform/AppDirs.kt`
- Create: `core/platform/src/main/kotlin/com/example/musicfy/core/platform/Logger.kt`
- Create: `core/platform/src/main/kotlin/com/example/musicfy/core/platform/DesktopAppDirs.kt`
- Create: `core/platform/src/main/kotlin/com/example/musicfy/core/platform/StdoutLogger.kt`
- Test: `core/platform/src/test/kotlin/com/example/musicfy/core/platform/DesktopAppDirsTest.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/platform/AndroidAppDirs.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/platform/TimberLogger.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/di/PlatformModule.kt`
- Test: `app/src/test/kotlin/com/example/musicfy/platform/AndroidAppDirsTest.kt`
- Modify: `app/build.gradle.kts` (add `implementation(project(":core:platform"))` next to the provider dependencies, around line 375)

**Interfaces:**
- Produces (used by D1b's database/resolver and the desktop app):
  - `interface AppDirs { val data: File; val cache: File; val downloads: File }`
  - `interface Logger { fun d(tag: String, msg: String); fun i(tag: String, msg: String); fun w(tag: String, msg: String, t: Throwable? = null); fun e(tag: String, msg: String, t: Throwable? = null) }`
  - `class DesktopAppDirs : AppDirs` with `companion fun forCurrentOs(): DesktopAppDirs` and `fun resolve(osName: String, env: Map<String, String>, home: File): DesktopAppDirs`
  - `object StdoutLogger : Logger`
  - App: `class AndroidAppDirs(data: File, cache: File) : AppDirs` with `companion fun from(context: Context)`; `object TimberLogger : Logger`; Hilt bindings for `AppDirs` and `Logger`.

- [ ] **Step 1: Register the module and its build file**

Append to `settings.gradle.kts` after the `include(":modules:monochrome")` line:
```kotlin

// shared core: plain JVM, used by the Android app and the desktop app
include(":core:platform")
```

`core/platform/build.gradle.kts`:
```kotlin
plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
```

- [ ] **Step 2: Write the interfaces**

`core/platform/src/main/kotlin/com/example/musicfy/core/platform/AppDirs.kt`:
```kotlin
package com.example.musicfy.core.platform

import java.io.File

/** Where the app keeps its files. Android maps these to Context dirs; desktop to per-OS app dirs. */
interface AppDirs {
    /** Persistent app data (database, settings, cipher cache). */
    val data: File

    /** Disposable cache (images, stream cache). */
    val cache: File

    /** Downloaded songs. Always `data/download` so both platforms agree. */
    val downloads: File
}
```

`core/platform/src/main/kotlin/com/example/musicfy/core/platform/Logger.kt`:
```kotlin
package com.example.musicfy.core.platform

/** Logging seam for shared code. Android forwards to Timber, desktop to stdout/stderr. */
interface Logger {
    fun d(tag: String, msg: String)

    fun i(tag: String, msg: String)

    fun w(tag: String, msg: String, t: Throwable? = null)

    fun e(tag: String, msg: String, t: Throwable? = null)
}
```

- [ ] **Step 3: Write the failing desktop-dirs test**

`core/platform/src/test/kotlin/com/example/musicfy/core/platform/DesktopAppDirsTest.kt`:
```kotlin
package com.example.musicfy.core.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DesktopAppDirsTest {
    private val home = File("/home/u")

    @Test
    fun windowsUsesAppDataAndLocalAppData() {
        val dirs = DesktopAppDirs.resolve(
            osName = "Windows 11",
            env = mapOf("APPDATA" to "C:/Users/u/AppData/Roaming", "LOCALAPPDATA" to "C:/Users/u/AppData/Local"),
            home = home,
        )
        assertEquals(File("C:/Users/u/AppData/Roaming/Musicfy"), dirs.data)
        assertEquals(File("C:/Users/u/AppData/Local/Musicfy/Cache"), dirs.cache)
        assertEquals(File("C:/Users/u/AppData/Roaming/Musicfy/download"), dirs.downloads)
    }

    @Test
    fun windowsFallsBackToHomeWhenEnvMissing() {
        val dirs = DesktopAppDirs.resolve("Windows 10", emptyMap(), home)
        assertEquals(File("/home/u/AppData/Roaming/Musicfy"), dirs.data)
        assertEquals(File("/home/u/AppData/Local/Musicfy/Cache"), dirs.cache)
    }

    @Test
    fun macUsesLibraryFolders() {
        val dirs = DesktopAppDirs.resolve("Mac OS X", emptyMap(), File("/Users/u"))
        assertEquals(File("/Users/u/Library/Application Support/Musicfy"), dirs.data)
        assertEquals(File("/Users/u/Library/Caches/Musicfy"), dirs.cache)
    }

    @Test
    fun linuxDefaultsToXdgFallbacks() {
        val dirs = DesktopAppDirs.resolve("Linux", emptyMap(), home)
        assertEquals(File("/home/u/.local/share/musicfy"), dirs.data)
        assertEquals(File("/home/u/.cache/musicfy"), dirs.cache)
    }

    @Test
    fun linuxHonoursXdgVariablesButIgnoresBlankOnes() {
        val dirs = DesktopAppDirs.resolve(
            osName = "Linux",
            env = mapOf("XDG_DATA_HOME" to "/data", "XDG_CACHE_HOME" to "  "),
            home = home,
        )
        assertEquals(File("/data/musicfy"), dirs.data)
        assertEquals(File("/home/u/.cache/musicfy"), dirs.cache)
    }
}
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `./gradlew :core:platform:test`
Expected: FAIL. `Unresolved reference: DesktopAppDirs`.

- [ ] **Step 5: Implement desktop dirs and logger**

`core/platform/src/main/kotlin/com/example/musicfy/core/platform/DesktopAppDirs.kt`:
```kotlin
package com.example.musicfy.core.platform

import java.io.File

class DesktopAppDirs private constructor(
    override val data: File,
    override val cache: File,
) : AppDirs {
    override val downloads: File
        get() = data.resolve("download")

    companion object {
        private const val APP_NAME = "Musicfy"
        private const val LINUX_DIR = "musicfy"

        fun forCurrentOs(): DesktopAppDirs = resolve(
            osName = System.getProperty("os.name").orEmpty(),
            env = System.getenv(),
            home = File(System.getProperty("user.home")),
        )

        fun resolve(osName: String, env: Map<String, String>, home: File): DesktopAppDirs {
            fun envDir(name: String): File? = env[name]?.takeIf { it.isNotBlank() }?.let(::File)
            val os = osName.lowercase()
            return when {
                os.startsWith("windows") -> DesktopAppDirs(
                    data = (envDir("APPDATA") ?: home.resolve("AppData/Roaming")).resolve(APP_NAME),
                    cache = (envDir("LOCALAPPDATA") ?: home.resolve("AppData/Local")).resolve(APP_NAME).resolve("Cache"),
                )
                os.startsWith("mac") -> DesktopAppDirs(
                    data = home.resolve("Library/Application Support").resolve(APP_NAME),
                    cache = home.resolve("Library/Caches").resolve(APP_NAME),
                )
                else -> DesktopAppDirs(
                    data = (envDir("XDG_DATA_HOME") ?: home.resolve(".local/share")).resolve(LINUX_DIR),
                    cache = (envDir("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve(LINUX_DIR),
                )
            }
        }
    }
}
```

`core/platform/src/main/kotlin/com/example/musicfy/core/platform/StdoutLogger.kt`:
```kotlin
package com.example.musicfy.core.platform

object StdoutLogger : Logger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")

    override fun i(tag: String, msg: String) = println("I/$tag: $msg")

    override fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("W/$tag: $msg")
        t?.printStackTrace()
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:platform:test`
Expected: PASS (5 tests).

- [ ] **Step 7: Write the failing Android dirs test**

`app/src/test/kotlin/com/example/musicfy/platform/AndroidAppDirsTest.kt`:
```kotlin
package com.example.musicfy.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AndroidAppDirsTest {
    @Test
    fun downloadsIsTheSameFolderDownloadCacheUsesToday() {
        // di/AppModule.kt builds the download cache in context.filesDir.resolve("download").
        val dirs = AndroidAppDirs(data = File("/data/files"), cache = File("/data/cache"))
        assertEquals(File("/data/files/download"), dirs.downloads)
        assertEquals(File("/data/files"), dirs.data)
        assertEquals(File("/data/cache"), dirs.cache)
    }
}
```

Add to `app/build.gradle.kts` dependencies, right after `implementation(project(":modules:monochrome"))`:
```kotlin
    implementation(project(":core:platform"))
```

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.platform.AndroidAppDirsTest"`
Expected: FAIL. `Unresolved reference: AndroidAppDirs`.

- [ ] **Step 8: Implement the Android side and Hilt bindings**

`app/src/main/kotlin/com/example/musicfy/platform/AndroidAppDirs.kt`:
```kotlin
package com.example.musicfy.platform

import android.content.Context
import com.example.musicfy.core.platform.AppDirs
import java.io.File

class AndroidAppDirs(
    override val data: File,
    override val cache: File,
) : AppDirs {
    override val downloads: File
        get() = data.resolve("download")

    companion object {
        fun from(context: Context): AndroidAppDirs =
            AndroidAppDirs(data = context.filesDir, cache = context.cacheDir)
    }
}
```

`app/src/main/kotlin/com/example/musicfy/platform/TimberLogger.kt`:
```kotlin
package com.example.musicfy.platform

import com.example.musicfy.core.platform.Logger
import timber.log.Timber

object TimberLogger : Logger {
    override fun d(tag: String, msg: String) = Timber.tag(tag).d(msg)

    override fun i(tag: String, msg: String) = Timber.tag(tag).i(msg)

    override fun w(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).w(t, msg)

    override fun e(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).e(t, msg)
}
```

`app/src/main/kotlin/com/example/musicfy/di/PlatformModule.kt`:
```kotlin
package com.example.musicfy.di

import android.content.Context
import com.example.musicfy.core.platform.AppDirs
import com.example.musicfy.core.platform.Logger
import com.example.musicfy.platform.AndroidAppDirs
import com.example.musicfy.platform.TimberLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides
    @Singleton
    fun provideAppDirs(@ApplicationContext context: Context): AppDirs = AndroidAppDirs.from(context)

    @Provides
    @Singleton
    fun provideLogger(): Logger = TimberLogger
}
```

- [ ] **Step 9: Run tests and build all flavors**

Run: `./gradlew :app:testUniversalFossDebugUnitTest --tests "com.example.musicfy.platform.AndroidAppDirsTest" assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug`
Expected: test PASS, `BUILD SUCCESSFUL`. Hilt compiles the new module. Nothing consumes it yet, which is intended: D1b's resolver/database will.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts core/platform app/build.gradle.kts app/src/main/kotlin/com/example/musicfy/platform app/src/main/kotlin/com/example/musicfy/di/PlatformModule.kt app/src/test/kotlin/com/example/musicfy/platform
git commit -m "feat(core): add core:platform with AppDirs/Logger and Android bindings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `core:settings` (preference keys + desktop DataStore factory)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `datastore-preferences-core`)
- Modify: `settings.gradle.kts` (add `include(":core:settings")`)
- Create: `core/settings/build.gradle.kts`
- Move: `app/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt` → `core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt`
- Create: `app/src/main/kotlin/com/example/musicfy/constants/PlatformDefaults.kt`
- Create: `core/settings/src/main/kotlin/com/example/musicfy/core/settings/SettingsDataStore.kt`
- Test: `core/settings/src/test/kotlin/com/example/musicfy/core/settings/SettingsDataStoreTest.kt`
- Test: `core/settings/src/test/kotlin/com/example/musicfy/core/settings/PreferenceKeyNamesTest.kt`
- Create (generated): `core/settings/src/test/resources/preference-keys.golden.txt`
- Modify: `app/src/main/kotlin/com/example/musicfy/utils/DataStore.kt:28` (datastore name constant)
- Modify: `app/build.gradle.kts` (add `implementation(project(":core:settings"))`)

**Interfaces:**
- Consumes: `:providers:innertube` (`com.music.innertube.models.IpVersion`).
- Produces:
  - All existing top-level keys and enums in package `com.example.musicfy.constants` (e.g. `DarkModeKey`, `SongSortType`, `AudioQuality`), unchanged.
  - `const val SETTINGS_DATASTORE_NAME = "settings"`
  - `fun createSettingsDataStore(dir: File, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences>` (desktop; writes `dir/settings.preferences_pb`)
  - App keeps `val DefaultPlayerCoverStyle: PlayerCoverStyle` in package `com.example.musicfy.constants`.

- [ ] **Step 1: Catalog entry, module registration, build file**

In `gradle/libs.versions.toml` `[libraries]`, under the existing `datastore = ...` line, add:
```toml
datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
```

In `settings.gradle.kts`, under `include(":core:platform")`:
```kotlin
include(":core:settings")
```

`core/settings/build.gradle.kts`:
```kotlin
plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.datastore.preferences.core)
    api(project(":providers:innertube"))
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :core:settings:test -PupdateGolden=true rewrites the key-name golden file
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
```

- [ ] **Step 2: Move the keys file and split out the Android-only default**

```bash
mkdir -p core/settings/src/main/kotlin/com/example/musicfy/constants
git mv app/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt
```

In the moved file, delete the line `import android.os.Build`, and delete this whole block (KDoc included):
```kotlin
/**
 * Cover style to use when the user has not picked one.
 *
 * Edge-to-edge art leans on the backdrop blur behind it to separate the cover from the controls,
 * and that blur needs API 31. Below that the full-bleed art runs straight under the chrome with
 * nothing softening it, so squared reads far better there. This is only the *default* - an
 * explicit choice in Player customise still wins on every device.
 */
val DefaultPlayerCoverStyle: PlayerCoverStyle
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PlayerCoverStyle.EDGE_TO_EDGE
    } else {
        PlayerCoverStyle.SQUARED
    }
```

`app/src/main/kotlin/com/example/musicfy/constants/PlatformDefaults.kt`:
```kotlin
package com.example.musicfy.constants

import android.os.Build

/**
 * Cover style to use when the user has not picked one.
 *
 * Edge-to-edge art leans on the backdrop blur behind it to separate the cover from the controls,
 * and that blur needs API 31. Below that the full-bleed art runs straight under the chrome with
 * nothing softening it, so squared reads far better there. This is only the *default* - an
 * explicit choice in Player customise still wins on every device.
 */
val DefaultPlayerCoverStyle: PlayerCoverStyle
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PlayerCoverStyle.EDGE_TO_EDGE
    } else {
        PlayerCoverStyle.SQUARED
    }
```

Check nothing Android is left: `grep -n "import android\|import androidx\.\(compose\|core\)" core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt`
Expected: no output.

- [ ] **Step 3: Write the failing tests**

`core/settings/src/test/kotlin/com/example/musicfy/core/settings/SettingsDataStoreTest.kt`:
```kotlin
package com.example.musicfy.core.settings

import androidx.datastore.preferences.core.edit
import com.example.musicfy.constants.DarkModeKey
import com.example.musicfy.constants.DensityScaleKey
import com.example.musicfy.constants.IsFirstRunKey
import com.example.musicfy.constants.SelectedThemeColorKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun usesTheSameFileNameAsAndroid() {
        assertEquals("settings", SETTINGS_DATASTORE_NAME)
    }

    @Test
    fun valuesSurviveReopening() = runBlocking {
        val dir = tmp.newFolder()

        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        createSettingsDataStore(dir, firstScope).edit {
            it[IsFirstRunKey] = false
            it[SelectedThemeColorKey] = 0x123456
            it[DensityScaleKey] = 1.25f
            it[DarkModeKey] = "ON"
        }
        firstScope.cancel()

        assertTrue(dir.resolve("settings.preferences_pb").isFile)

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = createSettingsDataStore(dir, secondScope).data.first()
        assertEquals(false, prefs[IsFirstRunKey])
        assertEquals(0x123456, prefs[SelectedThemeColorKey])
        assertEquals(1.25f, prefs[DensityScaleKey])
        assertEquals("ON", prefs[DarkModeKey])
        secondScope.cancel()
    }
}
```

`core/settings/src/test/kotlin/com/example/musicfy/core/settings/PreferenceKeyNamesTest.kt`:
```kotlin
package com.example.musicfy.core.settings

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Renaming a key silently resets that setting for every user. This pins every
 * `val XKey = ...PreferencesKey("name")` in PreferenceKeys.kt to its stored name.
 */
class PreferenceKeyNamesTest {
    private val golden = File("src/test/resources/preference-keys.golden.txt")

    @Test
    fun keyNamesMatchGolden() {
        val actual = Class.forName("com.example.musicfy.constants.PreferenceKeysKt").declaredMethods
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    Preferences.Key::class.java.isAssignableFrom(it.returnType)
            }
            .map { "${it.name.removePrefix("get")}=${(it.invoke(null) as Preferences.Key<*>).name}" }
            .sorted()
            .joinToString("\n")

        if (System.getProperty("updateGolden") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(actual + "\n")
            return
        }
        assertEquals(golden.readText().trim(), actual)
    }
}
```

Run: `./gradlew :core:settings:test`
Expected: FAIL. `Unresolved reference: SETTINGS_DATASTORE_NAME` / `createSettingsDataStore`.

- [ ] **Step 4: Implement the factory**

`core/settings/src/main/kotlin/com/example/musicfy/core/settings/SettingsDataStore.kt`:
```kotlin
package com.example.musicfy.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Android's `preferencesDataStore(name = ...)` and desktop both use this name. */
const val SETTINGS_DATASTORE_NAME = "settings"

/**
 * Desktop settings store at `dir/settings.preferences_pb`. Create exactly one per process for a
 * given dir; DataStore throws if two instances own the same file.
 */
fun createSettingsDataStore(
    dir: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    scope = scope,
    produceFile = { dir.resolve("$SETTINGS_DATASTORE_NAME.preferences_pb") },
)
```

- [ ] **Step 5: Generate the golden file, inspect it, run the tests**

Run: `./gradlew :core:settings:test -PupdateGolden=true && wc -l core/settings/src/test/resources/preference-keys.golden.txt && head -5 core/settings/src/test/resources/preference-keys.golden.txt`
Expected: about 299 lines, in the form `AppleMusicDarkChromeKey=appleMusicDarkChrome`.
Cross-check the count: `grep -cE "PreferencesKey\(" core/settings/src/main/kotlin/com/example/musicfy/constants/PreferenceKeys.kt`. It should be close to the golden line count. Differences can only come from keys built inside functions; look at any gap before continuing.

Run: `./gradlew :core:settings:test`
Expected: PASS (3 tests).

- [ ] **Step 6: Point the app at the module and the shared name**

Add to `app/build.gradle.kts` after `implementation(project(":core:platform"))`:
```kotlin
    implementation(project(":core:settings"))
```

In `app/src/main/kotlin/com/example/musicfy/utils/DataStore.kt`, replace
```kotlin
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
```
with
```kotlin
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_DATASTORE_NAME)
```
and add `import com.example.musicfy.core.settings.SETTINGS_DATASTORE_NAME`.

- [ ] **Step 7: Build all flavors and run app unit tests**

Run: `./gradlew assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug :app:testUniversalFossDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. If `DefaultPlayerCoverStyle` is unresolved somewhere, that file is outside `app/`. Grep for it; every user must be in `app/`.

- [ ] **Step 8: Upgrade test (required for this task)**

Install over the Task 1 baseline (no uninstall): `adb install -r app/build/outputs/apk/universalFoss/debug/*.apk`.
Check that dark mode, theme colour, density, lyrics provider, and audio quality settings are unchanged, and that the player cover style default is unchanged on this device.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core/settings app/build.gradle.kts app/src/main/kotlin/com/example/musicfy/constants app/src/main/kotlin/com/example/musicfy/utils/DataStore.kt
git commit -m "feat(core): move preference keys into core:settings with desktop DataStore factory

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: CI runs JVM tests and builds all three flavors

**Files:**
- Modify: `build.gradle.kts` (root): add `jvmTests` task
- Modify: `.github/workflows/build_pr.yml`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Produces: `./gradlew jvmTests`, which runs `test` in every `:core:*` and `:providers:*` project.

- [ ] **Step 1: Add the aggregate task**

Append to the root `build.gradle.kts`:
```kotlin
// Unit tests of every plain-JVM shared module (core + providers). The app's own unit tests run
// per variant via :app:test<Variant>UnitTest.
tasks.register("jvmTests") {
    group = "verification"
    description = "Runs unit tests for all :core and :providers modules."
    dependsOn(
        subprojects
            .filter { it.path.startsWith(":core:") || it.path.startsWith(":providers:") }
            .map { "${it.path}:test" },
    )
}
```

Run: `./gradlew jvmTests`
Expected: `BUILD SUCCESSFUL`. Output includes `:core:platform:test`, `:core:settings:test`, `:providers:paxsenixlyrics:test`, and `:providers:canvas:test`.

- [ ] **Step 2: PR workflow: tests + flavor matrix**

In `.github/workflows/build_pr.yml`, replace the `jobs.build` header with a matrix, and replace the single build step with the three steps below. Keep checkout, JDK, Gradle, chmod, and keystore steps as they are.

Header:
```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        flavor: [Foss, Gms, Fdroid]
```

Replace the step `Build and Lint Universal FOSS Debug APK` with:
```yaml
      - name: Unit tests (shared JVM modules + app)
        if: matrix.flavor == 'Foss'
        run: ./gradlew --console=plain jvmTests :app:testUniversalFossDebugUnitTest

      - name: Build Universal ${{ matrix.flavor }} Debug APK
        run: ./gradlew --console=plain assembleUniversal${{ matrix.flavor }}Debug --warning-mode summary
        env:
          GITHUB_EVENT_NAME: ${{ github.event_name }}
          PULL_REQUEST: "true"
          LASTFM_API_KEY: ${{ secrets.LASTFM_API_KEY }}
          LASTFM_SECRET: ${{ secrets.LASTFM_SECRET }}

      - name: Lint
        if: matrix.flavor == 'Foss'
        run: ./gradlew --console=plain :app:lintUniversalFossDebug
```

Change the upload step so only Foss uploads, keeping the artifact name and path:
```yaml
      - name: Upload APK
        if: matrix.flavor == 'Foss'
        uses: actions/upload-artifact@v6
        with:
          name: app-universal-debug-pr-${{ github.event.number }}
          path: app/build/outputs/apk/universalFoss/debug/*.apk
```

- [ ] **Step 3: Main workflow: run tests before building**

Open `.github/workflows/build.yml`, find the first step that runs `./gradlew ... assemble...`, and insert this step right before it:
```yaml
      - name: Unit tests (shared JVM modules + app)
        run: ./gradlew --console=plain jvmTests :app:testUniversalFossDebugUnitTest
```

- [ ] **Step 4: Validate YAML locally**

Run: `ruby -ryaml -e 'YAML.load_file(".github/workflows/build_pr.yml"); YAML.load_file(".github/workflows/build.yml"); puts "ok"'`
Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts .github/workflows/build_pr.yml .github/workflows/build.yml
git commit -m "ci: run shared-module and app unit tests; build all three flavors on PRs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Done when

- Tasks 1–7 committed on `feature/desktop-d1`.
- `./gradlew jvmTests :app:testUniversalFossDebugUnitTest assembleUniversalFossDebug assembleUniversalGmsDebug assembleUniversalFdroidDebug` is green.
- The final APK, installed over the Task 1 baseline install, passes the smoke checklist with all data and settings intact.
- Next: write the D1b plan (core:model + core:database incl. baseline schema export, core:resolver, core:designsystem, desktopApp skeleton).
