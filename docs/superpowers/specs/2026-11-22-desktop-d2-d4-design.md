# Musicfy Desktop — D2–D4: Mesin Pemutar, UI MVP, Paket & Distribusi

- **Tanggal:** 2026-11-22
- **Status:** Draf desain, menunggu review
- **Cakupan:** Sub-proyek D2 (mesin pemutar desktop), D3 (UI desktop MVP), D4 (paket & distribusi GitHub)
- **Bergantung pada:** D1a + D1b ([spec D1](2026-11-22-desktop-d1-core-modules-design.md)). Tidak ada pekerjaan D2 yang dimulai sebelum `./gradlew :desktopApp:run` dari D1b lulus.

## Ringkasan

Setelah D2–D4, Musicfy desktop **bisa diinstal dan dipakai sehari-hari di macOS (arm64 + x64) dan
Windows 10/11 x64**. Installer DMG dan EXE/MSI dibangun dan dirilis oleh GitHub Actions. Linux
(DEB) ikut dibangun sebagai nice-to-have.

Isi MVP sesuai keputusan pengguna: beranda, cari, library (like, playlist, riwayat), pemutar,
antrean, lirik, login akun YouTube, download offline, equalizer, dan Discord Rich Presence.
Layoutnya layout desktop (sidebar kiri, konten lebar, player bar bawah, panel lirik/antrean kanan)
dengan token desain yang sama dengan Android.

Inti teknisnya:

- **libVLC 3.0.23 lewat vlcj 4.8.x** sebagai mesin audio, di belakang interface `PlaybackEngine`.
- **`StreamProxy` lokal** (127.0.0.1) di antara libVLC dan googlevideo. libVLC tidak bisa mengirim
  header `Origin`. Proxy menangani header per client, range chunk, dan refresh URL kedaluwarsa.
- **QuickJS** untuk cipher/n-transform (`JsRuntime`). **JCEF (jcefmaven)** yang diunduh saat pertama
  kali dibutuhkan, untuk BotGuard/PoToken dan login YouTube.
- Paket memakai `nativeDistributions` dari plugin Compose (jpackage), JRE di-bundle, dan rilis
  berbasis tag `desktop-v*`.

## Keputusan

| # | Topik | Keputusan | Alasan singkat |
|---|---|---|---|
| K1 | Mesin audio | **vlcj 4.8.x + libVLC 3.0.23 di-bundle** (Windows x64, macOS arm64/x64). Linux memakai libVLC sistem. Dibungkus interface `PlaybackEngine`. Cadangan: libmpv lewat binding JNA sendiri | Hanya opsi ini yang lolos semua kriteria (opus/webm + m4a, EQ 10-band bawaan, binary resmi per OS, GPL-3 kompatibel) dengan binding JVM yang matang. Lihat §2.1 |
| K2 | Jalur jaringan stream | **`StreamProxy` HTTP lokal** (`jdk.httpserver`, 127.0.0.1, port acak + token acak). libVLC hanya membaca `http://127.0.0.1/...` atau `file://` | libVLC hanya mendukung `http-user-agent`/`http-referrer`, sedangkan googlevideo memeriksa `Origin`. Proxy juga memberi kontrol chunk, refresh URL, dan retry, sama seperti `ResolvingDataSource` di Android |
| K3 | `JsRuntime` (cipher, n-transform) | **QuickJS** lewat `quickjs-kt` (JVM, Apache-2.0). Cadangan 1: GraalJS mode interpreter. Cadangan 2: `JsRuntime` berbasis JCEF | Solver EJS yt-dlp resmi mendukung QuickJS. Ukurannya kecil (~2 MB), tanpa proses browser |
| K4 | `BotGuardRuntime` (PoToken) + login | **JCEF lewat `me.friwi:jcefmaven` (CEF/Chromium 146)**, natives diunduh saat pertama kali dibutuhkan ke `localData/cef/<versi>`. **KCEF ditolak** (diarsipkan Okt 2025). Cadangan: JetBrains Runtime + JCEF sebagai runtime bawaan | BotGuard butuh DOM sungguhan. Unduhan ~100 MB tidak ikut installer, karena jalur utama (client VISIONOS) tidak butuh PoToken |
| K5 | Login YouTube | Jendela JCEF ke `accounts.google.com` lalu tangkap cookie `music.youtube.com` (SAPISID wajib). Cadangan selalu tersedia: **"Login manual"** dengan menempel header Cookie | Google memblokir login dari sebagian embedded browser CEF. Cara tempel cookie terbukti di ytmusicapi |
| K6 | Arsitektur | `desktopApp` (shell + Koin) + modul `desktop:player`, `desktop:web`, `desktop:js`, `desktop:downloads`, `desktop:integration`. Modul inti baru: `core:playback`, `core:lyrics`, `core:data` (JVM) dan `core:ui` (KMP Compose) | Logika bisnis bisa dipakai bersama Android. Kode khusus desktop terisolasi per kemampuan |
| K7 | State & navigasi | ViewModel androidx KMP (`lifecycle-viewmodel-compose`, lifecycle 2.10.0) + `koinViewModel()`. **Navigation 3 Compose Multiplatform** dengan back stack milik aplikasi. Pemutar adalah singleton `PlayerController` (StateFlow), bukan ViewModel | Pola sama dengan Android. Back stack berupa list cocok dengan sidebar desktop |
| K8 | Integrasi OS | Windows: **SMTC** (JMTC, cadangan DLL C++/WinRT sendiri). macOS: **MPNowPlayingInfoCenter/MPRemoteCommandCenter** lewat dylib Swift kecil + JNA. Linux: MPRIS (dbus-java). Single-instance lewat Unix domain socket. `musicfy://` didaftarkan per OS (Info.plist / HKCU / `.desktop`). Tray opsional (default mati) | Tombol media dan kontrol "Now Playing" OS dianggap wajib untuk pemutar musik |
| K9 | Download | File apa adanya (webm/opus atau m4a, tanpa remux) di `localData/download/audio/<videoId>.<itag>.<ext>`. Status di `SongEntity.isDownloaded`/`dateDownload` + `FormatEntity`. **Skema DB tetap v39** | Tanpa FFmpeg dan tanpa migrasi. Path bisa diturunkan dari data yang sudah ada |
| K10 | Equalizer | **EQ 10-band bawaan libVLC** + preamp + 18 preset bawaan + preset custom. Normalisasi volume (dari `loudnessDb`) memakai preamp | Native di engine, tanpa DSP sendiri. Paritas EQ parametrik/AutoEq ditunda ke D5 |
| K11 | Discord RPC | Default **IPC lokal ke aplikasi Discord** (resmi, tanpa token). **Kizzy (gateway + token akun)** tetap tersedia sebagai mode opt-in dengan peringatan ToS, untuk paritas dengan Android | Self-bot melanggar ToS Discord. Di desktop, klien Discord hampir selalu berjalan |
| K12 | Paket | Plugin Compose `nativeDistributions`: **DMG** (arm64 & x64 terpisah), **EXE + MSI** (x64, per-user install), **DEB** (Linux). JDK 21 Temurin di-jlink. ProGuard untuk release. Tidak ada universal binary macOS | jpackage tidak bisa cross-compile dan tidak bisa membuat JRE universal |
| K13 | CI | `desktop.yml`: test di 3 OS pada PR. Packaging di `macos-15` (arm64), `macos-15-intel` (x64), `windows-latest`, `ubuntu-22.04`. Artifact diunggah di setiap push ke `main`/`feature/desktop-*`. Rilis otomatis dari tag `desktop-vX.Y.Z` | `macos-13` sudah dipensiunkan (Des 2025). `macos-15-intel` adalah image Intel terakhir (sampai Agu 2027) |
| K14 | Versi | SemVer numerik `X.Y.Z`, mulai **1.0.0** (DMG menolak MAJOR 0, MSI membatasi 255.255.65535). Sumbernya `musicfy.desktop.version` di `gradle.properties`. Nomor build = `GITHUB_RUN_NUMBER` (hanya tampil di About) | Satu sumber versi, memenuhi batasan semua format installer |
| K15 | Signing | Workflow **siap sign jika secret ada**: Apple Developer ID + notarytool, Windows lewat **SignPath Foundation** (gratis untuk OSS). Tanpa secret: rilis unsigned (macOS ad-hoc) + instruksi Gatekeeper/SmartScreen di catatan rilis | Rilis tidak diblokir oleh proses pengadaan sertifikat |
| K16 | Auto-update | Pemeriksa sendiri ke GitHub Releases API (maksimal 1x/24 jam), verifikasi SHA-256, lalu jalankan installer (Windows) atau buka DMG (macOS). Tanpa framework update | Sederhana, tanpa server, tanpa dependensi baru |
| K17 | Kebijakan terhadap `app/` | D2–D4 **tidak mengubah perilaku Android**. Kode dipindah ke `core/*` hanya jika perubahan di `app/` mekanis (import/konstruktor). Selain itu disalin dan dicatat di tabel utang D5 (§8) | Prinsip D1: Android tetap identik |
| K18 | Seam Ritme | Semua kontrol pemutar lewat `PlayerController.dispatch(PlayerCommand)`. Ada `QueueSource` (lokal/remote) dan `PlaybackClock`. Rute `musicfy://ritme/...` dicadangkan | Klien Ritme nanti cukup menjadi sumber perintah/antrean baru, tanpa membongkar pemutar |

## Koreksi terhadap spec D1

**Bagian ini menang jika bertentangan dengan spec D1.**

1. **KCEF tidak dipakai.** Peta sub-proyek D1 menyebut "`JsRuntime`/`BotGuardRuntime` berbasis
   Chromium tertanam (KCEF)". Repositori KCEF diarsipkan pemiliknya pada 28 Okt 2025, dan README-nya
   tidak lagi merekomendasikan pemakaiannya [R7]. Penggantinya: QuickJS untuk `JsRuntime` dan
   jcefmaven untuk `BotGuardRuntime`/login (K3, K4).
2. **Format D4:** MSI/DMG/DEB dari D1 menjadi DMG + EXE + MSI + DEB. AppImage ditunda ke D5,
   karena plugin Compose tidak membuat `.AppImage` asli.
3. **Lokasi data besar di Windows.** `DesktopAppDirs.downloads` (D1a) berada di `%APPDATA%`
   (Roaming). File besar di profil roaming membebani sinkronisasi domain. D2 menambahkan properti
   desktop-only `DesktopAppDirs.localData` (Windows: `%LOCALAPPDATA%\Musicfy`, OS lain: sama dengan
   `data`). `downloads` dan folder CEF pindah ke bawah `localData`. Interface `AppDirs` tidak berubah.
   Belum ada data pengguna desktop, jadi tidak perlu migrasi.

## Non-goals D2–D4

- Fitur di luar MVP: Audio Lab/AutoEq parametrik, Crossmix/crossfade, recap, import (Spotify dll.),
  modul sumber (`modules/*`), canvas/video background, recognition (Shazam), widget, Cast.
- Mengubah perilaku atau UI aplikasi Android. Android baru memakai `core:ui`/`core:data` di D5.
- Implementasi klien Ritme (hanya seam, K18).
- Windows arm64 native. Build x64 berjalan lewat emulasi Prism.
- Sinkronisasi DB antara Android dan desktop.
- Skema DB baru. Versi tetap **39**.

---

## 0. Yang diasumsikan sudah ada dari D1

| Dari | Isi yang dipakai D2–D4 |
|---|---|
| D1a | `providers/*` sebagai jar JVM (termasuk `innertube`, `kizzy`, lirik), `core:platform` (`AppDirs`, `DesktopAppDirs`, `Logger`), `core:settings` (key + factory DataStore desktop), CI test modul JVM |
| D1b | `core:model` + `core:database` (Room 2.8.x, `BundledSQLiteDriver`, v39), `core:resolver` (`YTPlayerUtils` + interface `JsRuntime`/`BotGuardRuntime`, stub desktop), `core:designsystem` (tema + komponen dasar Compose MP), `desktopApp` (Compose Desktop + Koin, buka DB, baca settings, search InnerTube) |

Fakta resolver yang membentuk desain (dari `utils/YTPlayerUtils.kt`):

- `MAIN_CLIENT = VISIONOS` menghasilkan URL audio **langsung tanpa cipher dan tanpa PoToken**,
  dan URL itu melayani file utuh. Syaratnya ada `visitorData`.
- PoToken hanya di-mint secara lazy ketika fallback jatuh ke client web (`WEB_REMIX`, `WEB`,
  `WEB_CREATOR`). n-transform juga hanya untuk client web.
- `PlaybackData.streamHeaders` membawa `User-Agent`, `Origin`, dan `Referer` per client. googlevideo
  menolak URL WEB_REMIX tanpa `Origin` YouTube Music.
- Android membatasi setiap request upstream dengan `CHUNK_LENGTH = 512 KiB`
  (`dataSpec.subrange`), dan menyimpan URL per media dengan waktu kedaluwarsa.

Akibatnya: **pemutaran dasar desktop (D2.1) tidak butuh JavaScript maupun browser.** JS dan BotGuard
menambah ketahanan untuk lagu yang tidak bisa diputar VISIONOS.

---

## 1. Arsitektur desktop

### 1.1 Tata letak modul

```
musicfy/
├─ desktopApp/                 main(), Window/shell, Koin graph, navigasi, layar (feature/*),
│                              DesktopPreferenceKeys, UpdateChecker, nativeDistributions
├─ desktop/
│  ├─ player/                  JVM · PlaybackEngine, VlcPlaybackEngine, StreamProxy,
│  │                           PlayerController, EQ, normalisasi, PersistQueue desktop
│  ├─ web/                     JVM · CefInstaller, CefBotGuardRuntime, LoginWindow (JCEF)
│  ├─ js/                      JVM · QuickJsRuntime (JsRuntime)
│  ├─ downloads/               JVM · DownloadManager, DownloadStore (file + DB)
│  └─ integration/             JVM · MediaSession (SMTC/NowPlaying/MPRIS), SingleInstance,
│                              DeepLinks, Tray, DiscordPresence (IPC + Kizzy)
│      └─ native/              sumber helper native: macos/NowPlaying.swift, windows/smtc/*.cpp
├─ core/
│  ├─ playback/   (baru, JVM)  Queue di atas MediaMetadata (YouTubeQueue, ListQueue, radio, playlist),
│  │                           shuffle/repeat murni, rumus gain normalisasi
│  ├─ lyrics/     (baru, JVM)  LyricsProvider + provider online + LyricsHelper (dipindah dari app/lyrics)
│  ├─ data/       (baru, JVM)  repository tanpa Context/Hilt: HomeFeed, Search, Album, Artist,
│  │                           Playlist, Library, Account
│  ├─ ui/         (baru, KMP)  composable domain musik bersama (§3.3); dipakai desktop dulu
│  └─ (D1) platform, settings, model, database, resolver, designsystem
└─ app/                        Android (tidak berubah perilakunya)
```

### 1.2 Aturan dependensi

1. `desktopApp` → `desktop/*` → `core/*` → `providers/*`. `desktop/*` tidak saling bergantung,
   kecuali `desktop:downloads` → `desktop:player` (memakai `StreamFetcher`) dan
   `desktop:integration` → `desktop:player` (mengamati `PlayerState`).
2. `core/*` tetap tanpa anotasi DI dan tanpa API desktop/Android (aturan D1 1.3).
3. Hanya `desktop:player` yang boleh mengimpor `uk.co.caprica.vlcj.*`. Hanya `desktop:web` yang
   boleh mengimpor `org.cef.*`. Ini dijaga test arsitektur sederhana di CI (grep import per modul).
4. `app/` tidak boleh bergantung pada `desktop/*`.

### 1.3 DI (Koin)

Versi Koin mengikuti katalog D1b. Satu modul Koin per modul Gradle, dirakit di `desktopApp`:

| Modul Koin | Isi singleton utama |
|---|---|
| `platformModule` | `DesktopAppDirs`, `Logger`, `DataStore<Preferences>`, `OkHttpClient` bersama |
| `databaseModule` | `MusicDatabase`, DAO |
| `resolverModule` | `JsRuntime` (QuickJs, lazy), `BotGuardRuntime` (`CefBotGuardRuntime`, atau `UnavailableBotGuardRuntime` jika CEF belum terpasang), `YTPlayerUtils` |
| `playerModule` | `StreamProxy`, `PlaybackEngine`, `PlayerController`, `EqualizerController` |
| `downloadsModule` | `DownloadManager`, `DownloadStore` |
| `integrationModule` | `MediaSessionBridge` (implementasi per OS), `DiscordPresence`, `DeepLinkRouter`, `SingleInstance` |
| `dataModule` | repository `core:data`, `LyricsHelper` |
| `viewModelModule` | ViewModel layar (`viewModelOf(::HomeViewModel)` dst.) |

Test: `koinApplication { modules(all) }.verify()` di `desktopApp:test` (lanjutan dari D1).

### 1.4 State management & navigasi

- **ViewModel** memakai `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` yang
  memetakan ke lifecycle **2.10.0** (sama dengan Android), dengan `viewModelScope` dan
  `StateFlow<UiState>`. Diambil di composable lewat `koinViewModel()` (`koin-compose-viewmodel`).
- **Pemutar bukan ViewModel.** `PlayerController` adalah singleton aplikasi yang mengekspos
  `StateFlow<PlayerState>` (lagu, posisi, durasi, status, antrean, shuffle/repeat, volume, error).
  Layar membacanya langsung. Posisi dipancarkan maksimal 4x per detik.
- **Navigasi: Navigation 3 Compose Multiplatform** (`org.jetbrains.androidx.navigation3:navigation3-ui`,
  versi yang dipasangkan dengan versi CMP di katalog D1b). CMP 1.10 adalah rilis pertama yang
  mendukung Navigation 3 di desktop [R12].
  - Back stack adalah `SnapshotStateList<Route>` milik `AppNavigator`. `Route` berupa sealed class
    `@Serializable` (`Home`, `Search(query)`, `Album(id)`, `Artist(id)`, `Playlist(id, local)`,
    `Library(tab)`, `History`, `Downloads`, `Equalizer`, `Settings(section)`).
  - Klik item sidebar mengganti seluruh back stack dengan root item itu. Tombol back/forward di top
    bar, `Alt+←`/`Alt+→` (Windows/Linux), dan `Cmd+[`/`Cmd+]` (macOS) memanipulasi stack plus forward
    stack.
  - **Default jika artefak Navigation 3 KMP bermasalah** (crash atau API tidak stabil di versi CMP
    terpilih): ganti `NavDisplay` dengan `when (route)` di atas back stack yang sama. Back stack
    memang milik aplikasi, jadi penggantiannya hanya menyentuh satu file.

### 1.5 Seam Ritme (hanya disiapkan)

```kotlin
// desktop:player
sealed interface PlayerCommand {
    data class SetQueue(val source: QueueSource, val startIndex: Int, val startMs: Long = 0) : PlayerCommand
    data object Play : PlayerCommand; data object Pause : PlayerCommand
    data class SeekTo(val ms: Long) : PlayerCommand
    data object Next : PlayerCommand; data object Previous : PlayerCommand
    data class PlayAt(val index: Int) : PlayerCommand
    data class Enqueue(val items: List<MediaMetadata>, val next: Boolean) : PlayerCommand
    data class Move(val from: Int, val to: Int) : PlayerCommand
    data class Remove(val index: Int) : PlayerCommand
}
interface QueueSource            // LocalQueueSource(core:playback Queue) sekarang; RitmeQueueSource nanti
interface PlaybackClock { fun positionAt(nowNanos: Long): Long }   // untuk sinkronisasi posisi
```

UI, tombol media OS, deep link, dan Discord **hanya** memanggil `dispatch()`. Klien Ritme nanti
menjadi pemanggil `dispatch()` lain dan `QueueSource` remote, tanpa mengubah engine.

### 1.6 Settings desktop

- Key bersama (cookie InnerTube, `visitorData`, kualitas audio, tema, lirik, token/aktif Discord,
  filter explicit/video) dipakai dari `core:settings` apa adanya.
- Key khusus desktop **tidak** masuk `core:settings`, supaya golden file Android tidak berubah.
  Key ini tinggal di `desktopApp/.../DesktopPreferenceKeys.kt` di DataStore yang sama:
  `WindowStateKey`, `RightPanelKey`, `EqEnabledKey`, `EqPresetKey`, `EqBandsKey` (CSV 10 angka),
  `EqPreampKey`, `NormalizeVolumeKey`, `MinimizeToTrayKey`, `DiscordModeKey` (`off|ipc|kizzy`),
  `CefVersionKey`, `UpdateCheckKey`, `UpdateChannelKey` (`stable|beta`), `LastUpdateCheckKey`,
  `SkippedUpdateKey`, `VolumeKey`.
- Cookie disimpan seperti di Android (DataStore plaintext di folder data pengguna). File DataStore
  di-`chmod 600` di macOS/Linux. Penyimpanan di keychain OS ditunda ke D5.

---

## 2. D2 — Mesin pemutar desktop

### 2.1 Pemilihan mesin audio

Kriteria: (a) memutar opus/webm (itag 249/250/251) dan m4a/aac (139/140) dari googlevideo lewat
HTTPS dengan header khusus dan range request, (b) next track yang hampir gapless, (c) seek dan
volume, (d) EQ 10-band, (e) macOS arm64 + x64 dan Windows x64, (f) bisa di-bundle tanpa instalasi
oleh pengguna, (g) lisensi kompatibel dengan GPL-3.

| Kriteria | **vlcj + libVLC 3.0.23** | libmpv via JNA | JavaFX Media | GStreamer (gst1-java-core) |
|---|---|---|---|---|
| opus/webm + m4a/aac | Ya (demux mkv/mp4, decoder opus/faad/avcodec) | Ya (FFmpeg) | **Tidak**: Opus/WebM tidak didukung (JDK-8246299 masih terbuka), AAC tergantung platform [R4] | Ya, jika plugin `good/bad/libav` ikut |
| Header khusus (`Origin`) | **Tidak**: hanya `:http-user-agent`/`:http-referrer`, dan header arbitrer ditolak maintainer [R3]. Diatasi `StreamProxy` (K2) | Ya (`http-header-fields`) | Tidak | Ya (`souphttpsrc extra-headers`) |
| Gapless | Tidak native di VLC 3. Hampir gapless dengan dua `MediaPlayer` + preload (§2.3) | **Ya** (`gapless-audio`, `prefetch-playlist`) | Tidak | Ya (`about-to-finish` di playbin) |
| Seek/volume | Ya | Ya | Ya | Ya |
| EQ 10-band | **Bawaan** (`libvlc_audio_equalizer_*`, 10 band, preamp, 18 preset) | Lewat filter lavfi (`equalizer`/`superequalizer`), bahkan parametrik | Ada `AudioEqualizer` (tidak relevan karena codec gagal) | Ya (`equalizer-10bands`) |
| Binding JVM | **Matang**: vlcj 4.8.x (JDK 11+, JPMS), JNA lewat `vlcj-natives` [R1] | Tidak ada binding JNA matang. Yang ada KMP/JNI muda (mpv_kmp, kmpv 0.1.0) [R5], jadi harus menulis sendiri | Bawaan OpenJFX | gst1-java-core 1.4.0, update besar terakhir 2023 [R6] |
| Bundle tanpa instalasi | **Ya**: binary resmi VideoLAN Win64 (zip) dan macOS universal. Plugin bisa dipangkas | Windows: DLL tunggal komunitas (shinchiro). **macOS: tidak ada dylib resmi**, harus build sendiri | Ya (jar OpenJFX) | Sulit: README mengarahkan ke installer GStreamer sistem |
| Lisensi | vlcj **GPL-3.0-or-later** [R1], libVLC LGPL-2.1+, plugin LGPL/GPL-2+ | GPL/LGPL | GPL-2 + CPE | LGPL-3 |
| Status | VLC 3.0.23 (Jan 2026, banyak fix keamanan) [R2]. VLC 4 masih beta, vlcj 5 masih milestone | Aktif | Aktif, tapi codec tertinggal | Kurang aktif |

**Rekomendasi: vlcj 4.8.x + libVLC 3.0.23.** Hanya opsi ini yang memenuhi kriteria (a), (d), (e),
(f), dan (g) dengan binding JVM yang sudah lama dipakai produksi. Dua kekurangannya ditutup desain:
header khusus lewat `StreamProxy`, dan gapless lewat preload dua player (target jeda ≤ 300 ms).
JavaFX gugur karena tidak bisa memutar opus. GStreamer gugur karena bundling di macOS/Windows dan
aktivitas proyeknya. libmpv lebih unggul untuk gapless, tetapi butuh binding sendiri dan build
libmpv macOS sendiri, sehingga hanya menjadi **cadangan**.

**Kapan pindah ke cadangan libmpv:** jika eksperimen E1 (§5.0) gagal di salah satu platform target
dan tidak bisa diperbaiki dalam 2 hari kerja. Interface `PlaybackEngine` memastikan penggantian
hanya menyentuh `desktop:player`. Upgrade ke vlcj 5/VLC 4 dievaluasi di D5 setelah VLC 4 stabil.

**Estimasi ukuran libVLC (diukur di D4.1):**

| Platform | Isi yang di-bundle | Estimasi |
|---|---|---|
| Windows x64 | `libvlc.dll`, `libvlccore.dll`, plugin: `access/http`, `access/filesystem`, `demux/mkv`, `demux/mp4`, `demux/es`, `codec/opus`, `codec/faad`, `codec/avcodec` (cadangan decoder), `audio_output/mmdevice` + `directsound`, `audio_filter/equalizer` + resampler + mixer, `misc/logger`. **Tanpa** gnutls (proxy memakai http polos), video, interface, dan lua | 20–30 MB |
| macOS (per arsitektur) | `libvlc.dylib`, `libvlccore.dylib`, set plugin setara (`auhal` untuk output), di-*thin* dengan `lipo` ke satu arsitektur dari build universal resmi | 20–30 MB |
| Linux | Tidak di-bundle. DEB `Depends: libvlc5 (>= 3.0), vlc-plugin-base` | 0 |

Daftar plugin dikunci di `desktop/player/vlc-plugins.txt`. Skrip `scripts/desktop/fetch-libvlc.(sh|ps1)`
mengunduh arsip resmi `vlc-3.0.23` dengan checksum SHA-256 yang di-pin, mengekstrak, memangkas,
dan meletakkannya di `desktopApp/resources/<os-arch>/vlc/` (masuk `appResourcesRootDir`, tidak
di-commit). vlcj diarahkan ke folder itu lewat `NativeDiscoveryStrategy` sendiri yang membaca
`System.getProperty("compose.application.resources.dir")`, plus `VLC_PLUGIN_PATH`.

### 2.2 `StreamProxy`

```
PlayerController ──load("http://127.0.0.1:{port}/{token}/s/{videoId}?q=high")──▶ libVLC
libVLC ──GET + Range──▶ StreamProxy ──resolve (cache)──▶ core:resolver (YTPlayerUtils)
                                    └─GET googlevideo + Range chunk + streamHeaders──▶ OkHttp
```

- Server memakai `com.sun.net.httpserver.HttpServer` (modul `jdk.httpserver`, ikut jlink) dan hanya
  bind ke `127.0.0.1`. Port acak, plus token acak 128-bit di path. Request tanpa token dibalas 404.
- **Resolve & cache URL:** `StreamUrlCache` menyimpan `videoId → (PlaybackData, expiresAt)`, dengan
  semantik sama seperti `songUrlCache` + `songStreamHeaders` di `MusicService`. Kedaluwarsa dianggap
  `expiresAt - 60 s`.
- **Range:** request libVLC `Range: bytes=a-` atau `a-b` diterjemahkan menjadi serangkaian request
  upstream `bytes=x-(x+CHUNK-1)` dengan **CHUNK = 512 KiB** (nilai Android yang sudah terbukti).
  Hasilnya di-stream berurutan ke respons yang sama. `Content-Length`/`Content-Range` total diambil
  dari `FormatEntity.contentLength`, atau dari `Content-Range` respons upstream pertama.
- **Pemulihan:** upstream 403/410/416-dengan-URL-kedaluwarsa memicu satu kali re-resolve dengan
  `bypassCache`, lalu chunk dilanjutkan dari offset yang sama. Jika gagal lagi, respons diputus,
  libVLC melempar error, dan `PlayerController` menjalankan logika retry (§2.8).
- **Header:** `PlaybackData.streamHeaders` dikirim apa adanya. Itu satu-satunya alasan proxy ini ada
  (K2).
- File lokal (lagu yang sudah di-download) **tidak** lewat proxy. Engine menerima `file://` langsung.
- Satu `StreamFetcher` (OkHttp + chunking + re-resolve) dipakai bersama oleh proxy dan
  `DownloadManager` (chunk download 4 MiB, tetap di bawah ambang throttle ~10 MB yang tercatat di
  yt-dlp [R10]).

### 2.3 `PlaybackEngine` dan `VlcPlaybackEngine`

```kotlin
interface PlaybackEngine {
    val events: SharedFlow<EngineEvent>   // Loading, Playing, Paused, Buffering(pct), Position(ms),
                                          // Duration(ms), Ended, Error(cause)
    fun load(uri: String, startMs: Long = 0, playWhenReady: Boolean = true)
    fun preloadNext(uri: String?)          // null = batalkan preload
    fun play(); fun pause(); fun seekTo(ms: Long); fun stop()
    fun setVolume(percent: Int)            // 0..100, linear di UI, kurva di engine
    fun setEqualizer(eq: EqSettings?)      // null = bypass
    fun release()
}
```

- **Hampir gapless:** dua instance `MediaPlayer` (A/B) dari satu `MediaPlayerFactory`. Saat posisi
  A ≥ durasi − 15 s, `PlayerController` me-resolve lagu berikutnya lalu memanggil `preloadNext`.
  B menyiapkan media (`media().prepare(uri)` + parse). Pada event `finished` di A, B langsung `play()`
  dan peran A/B ditukar. Jika preload belum siap, engine jatuh ke `load()` biasa. Target: jeda
  `Ended(A)`→`Playing(B)` **≤ 300 ms** (diukur dari log dan dicatat di PR D2.2).
- Opsi libVLC global: `--no-video --intf=dummy --no-lua --network-caching=1500 --file-caching=300
  --audio-resampler=soxr` (resampler jatuh ke default jika soxr tidak ada). Untuk test dan
  self-test: `--aout=dummy`.
- **Volume:** `audio().setVolume()` 0–100, dengan kurva kuadrat dari slider UI. EQ dan volume
  diterapkan ke A dan B.
- **EQ (K10):** `Equalizer` vlcj dengan 10 band tetap (60, 170, 310, 600 Hz, 1, 3, 6, 12, 14,
  16 kHz), amp −20…+20 dB, preamp −20…+20 dB. Preset diambil dari `libvlc_audio_equalizer_get_preset_*`.
  Preset custom disimpan di `EqBandsKey`.
- **Normalisasi volume:** jika `NormalizeVolumeKey` aktif dan `FormatEntity.loudnessDb` ada,
  `gain = clamp(-loudnessDb, -12, +6)` dB ditambahkan ke preamp EQ (EQ diaktifkan dalam mode flat
  bila pengguna mematikan EQ). Rumus gain dipindah sebagai fungsi murni ke `core:playback` (lihat
  `eq/audio/ReplayGain.kt`) dan di-unit-test.
- Thread: semua panggilan vlcj lewat satu `ExecutorService` single-thread milik engine. Event vlcj
  di-forward ke `events` tanpa memanggil balik libVLC dari callback (aturan vlcj).

### 2.4 `PlayerController` dan antrean

- **`core:playback` (JVM, baru):** interface `Queue` versi desktop di atas `MediaMetadata`
  (`core:model`), bukan `MediaItem`. Implementasinya **disalin** dari `app/.../playback/queues/*`
  (`YouTubeQueue`, `ListQueue`, `YouTubePlaylistQueue`, `YouTubeAlbumRadio`, `LocalAlbumRadio`,
  `EmptyQueue`) dengan `MediaItem` diganti `MediaMetadata`. Android tetap memakai salinannya
  sendiri (K17, utang D5 #1).
- `PlayerController` memegang antrean, indeks, shuffle (urutan acak yang menjaga lagu saat ini,
  setara Android), repeat (off/all/one), autoplay radio saat antrean habis (`Queue.nextPage()`),
  dan filter explicit/video dari settings.
- **Persistensi:** antrean + indeks + posisi ditulis ke `localData/queue.json` (format
  `PersistQueue` dari `core:model`, kotlinx.serialization) setiap pergantian lagu dan setiap 10 s
  saat diputar. Dibaca ulang saat start dalam keadaan pause.
- **Riwayat & statistik:** setelah lagu diputar ≥ 30 s atau ≥ 50% durasi, tulis `Event` dan naikkan
  `PlayCountEntity`, lewat DAO yang sama dengan Android (ambang sama dengan Android, dicek di kode
  `MusicService` saat implementasi).
- Like/unlike dari player bar memperbarui `SongEntity.liked`. Jika login, panggil endpoint like
  innertube seperti Android.
- `PlayerController` menjadi sumber kebenaran untuk `MediaSessionBridge`, `DiscordPresence`, dan UI.

### 2.5 JS runtime, BotGuard, dan browser

| Opsi | Untuk cipher/n-transform | Untuk BotGuard | Ukuran | Platform | Status |
|---|---|---|---|---|---|
| **QuickJS (`quickjs-kt` JVM)** | **Ya**. Runtime yang didukung resmi oleh solver EJS yt-dlp [R9] | Tidak ada DOM | ~2 MB native | mac/win/linux (artefak JVM, rilis 2026) [R13] | Aktif |
| GraalJS (`org.graalvm.polyglot:js`) | Ya, tapi di OpenJDK biasa hanya interpreter ("jauh lebih lambat", tidak didukung resmi) [R14] | Tidak ada DOM | puluhan MB jar | Semua JVM | Aktif |
| Rhino | Berisiko: cakupan ES modern tidak lengkap untuk player.js/EJS | Tidak | ~1.5 MB | Semua JVM | Aktif |
| KCEF | Ya | Ya | ~100 MB+ | – | **Diarsipkan Okt 2025** [R7] |
| **JCEF (jcefmaven 146.x)** | Ya | **Ya, DOM sungguhan** | Natives ~100 MB per bundle, diunduh saat pertama kali dijalankan [R8] | win x64/arm64, mac x64/arm64, linux | Aktif (CEF 146) |
| JBR + JCEF (JetBrains Runtime) | Ya | Ya | +~100 MB di installer | Semua | Aktif, tapi JBR tidak berisi jpackage/jlink, jadi packaging lebih rumit |

**Apa yang sebenarnya dibutuhkan BotGuard:** VM BotGuard mengharapkan objek `document` dan
lingkungan mirip browser. BgUtils menyatakan BotGuard bisa jalan di Node/Deno dengan jsdom, tetapi
memperingatkan bahwa perubahan YouTube bisa mewajibkan browser sungguhan [R11]. Android memakai
WebView sungguhan (`PoTokenWebView` memuat `po_token.html` dengan base URL `https://www.youtube.com`,
`blockNetworkLoads = true`, dan request jaringan BotGuard dilakukan dari Kotlin). Port paling setia
dan paling tahan perubahan karena itu adalah **browser sungguhan (JCEF)**, bukan QuickJS + shim DOM.

**Keputusan:**

1. **`QuickJsRuntime` (`desktop:js`)** mengimplementasikan `JsRuntime`. Isinya memuat player.js
   (dari `PlayerJsFetcher`, cache di `AppDirs.cache/playerjs/<hash>.js`) dan skrip solver EJS yang
   sama dengan yang dipakai `EjsNTransformSolver`/`CipherWebView`, lalu mengevaluasi
   `transformN`/`deobfuscateSignature`. Satu konteks per hash player, dibuat ulang saat hash berubah.
2. **`CefBotGuardRuntime` (`desktop:web`)** mengimplementasikan `BotGuardRuntime` dengan browser
   JCEF **off-screen** (`isOffscreenRendered = true`) yang tidak pernah ditampilkan:
   - `po_token.html` dilayani lewat `CefRequestHandler`/`CefResourceRequestHandler` di origin
     `https://www.youtube.com/` (setara `loadDataWithBaseURL`). Request lain diblokir (setara
     `blockNetworkLoads`).
   - `addJavascriptInterface` diganti `CefMessageRouter` (`window.cefQuery`). Logika
     `downloadAndRunBotguard`/`onRunBotguardResult`/mint token di-port dari `PoTokenWebView` tanpa
     mengubah alurnya.
   - Browser ditutup setelah idle 10 menit. Token sesi disimpan sampai `expirationInstant`, seperti
     di Android.
3. **Instalasi CEF on-demand (`CefInstaller`):** artefak natives jcefmaven untuk OS/arch berjalan
   diunduh dari Maven Central dengan versi dan **SHA-256 yang di-pin di kode**, diekstrak ke
   `localData/cef/<versi>/`, dan `CefVersionKey` ditulis. Progres tampil di dialog Compose.
   Pemicunya:
   - pengguna menekan **Login YouTube**, atau
   - resolver jatuh ke client web, `UnavailableBotGuardRuntime` melempar `BotGuardUnavailable`,
     resolver melanjutkan ke client berikutnya (perilaku fallback yang sudah ada), dan UI menampilkan
     banner sekali: "Pasang komponen kompatibilitas (~100 MB) agar lebih banyak lagu bisa diputar".
   - Tidak ada unduhan otomatis tanpa persetujuan.
4. **Cadangan bila jcefmaven gagal di suatu platform (E3):** JetBrains Runtime with JCEF
   (`jbrsdk_jcef` 21) menjadi runtime bawaan untuk semua platform. Installer bertambah ~100 MB dan
   `CefInstaller` menjadi no-op. Jika itu juga gagal, desktop berjalan **tanpa PoToken**: client web
   dilewati, dan batasan ini dicatat di catatan rilis.

**Lisensi:** JCEF/CEF BSD-3, Chromium BSD + komponen pihak ketiga, QuickJS MIT, quickjs-kt Apache-2.0.
Semuanya kompatibel dengan GPL-3. CEF tidak didistribusikan di dalam installer kita.

### 2.6 Integrasi OS

| Kemampuan | Windows 10/11 | macOS | Linux |
|---|---|---|---|
| Tombol media + overlay/Now Playing | **SMTC.** Default: **JMTC** (Java, SMTC + MPRIS, Java 21) [R15]. Jika E4 gagal: `musicfy_smtc.dll` sendiri (C++/WinRT, `ISystemMediaTransportControlsInterop::GetForWindow(hwnd)`, ABI C, dipanggil JNA, dibangun MSVC di CI) | **`libMusicfyNowPlaying.dylib`** (Swift, ~200 baris): `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`, ABI C (`mf_np_init(callback)`, `mf_np_update(...)`, `mf_np_set_state(...)`), dipanggil JNA. `playbackState` **wajib di-set eksplisit**, karena macOS tidak menebaknya [R16]. AWT sudah menjalankan `NSApplication`, jadi event diterima | MPRIS2 lewat JMTC atau `dbus-java` |
| Single instance | Unix domain socket `localData/ipc.sock` (didukung JDK 16+ di Windows 10 1803+), instance kedua mengirim argumen lalu keluar. Lock file `instance.lock` (`FileChannel.tryLock`) untuk mendeteksi socket basi | sama | sama |
| Deep link `musicfy://` | Didaftarkan saat start di `HKCU\Software\Classes\musicfy` (`URL Protocol`, `shell\open\command = "<exe>" "%1"`) lewat `jna-platform` `Advapi32Util`, tanpa admin. Diperbarui jika path exe berubah | `CFBundleURLTypes` lewat `macOS { infoPlist { extraKeysRawXml } }` + `Desktop.getDesktop().setOpenURIHandler` dipasang **sebelum** window pertama | `~/.local/share/applications/musicfy.desktop` dengan `MimeType=x-scheme-handler/musicfy;` + `xdg-mime default` saat start |
| Tray | `Tray` Compose Desktop. Aktif hanya jika `MinimizeToTrayKey` (default **mati**) | Dock menu standar, tanpa tray | sama seperti Windows |

Rute deep link (`DeepLinkRouter`, di-unit-test): `musicfy://watch?v=<id>[&list=<id>]`,
`musicfy://playlist?list=<id>`, `musicfy://album/<browseId>`, `musicfy://artist/<channelId>`,
dan **dicadangkan** `musicfy://ritme/...` (saat ini menampilkan "belum didukung"). Tautan
`https://music.youtube.com/...`/`youtu.be/...` yang ditempel di kotak cari memakai parser yang sama.

Helper native disimpan di `desktop/integration/native/`. Task Gradle `buildNativeHelpers` hanya
berjalan di OS-nya masing-masing (Swift: `swiftc -emit-library -target arm64-apple-macos11` dan
`x86_64-apple-macos11`; DLL: `cl`/`msbuild` di runner Windows). Hasilnya masuk resources per
OS/arch. Jika helper tidak ada (misalnya build dev di Linux), `MediaSessionBridge` memakai
implementasi no-op dan aplikasi tetap berjalan.

### 2.7 Download & offline (mesin)

**Tata letak file** (`DesktopAppDirs.localData/download/`):

```
download/
├─ audio/<videoId>.<itag>.<webm|m4a>         selesai
├─ audio/<videoId>.<itag>.<ext>.part          sedang diunduh (offset resume = ukuran file)
└─ artwork/<videoId>.jpg                      thumbnail 544px untuk tampilan offline
```

- **DB tanpa perubahan skema:** `SongEntity.isDownloaded = true`, `dateDownload = now`, dan baris
  `FormatEntity` (itag, mimeType, codecs, bitrate, sampleRate, contentLength, loudnessDb). Path
  diturunkan deterministik dari `videoId` + `FormatEntity.itag` + mimeType. Metadata lagu dan
  album/artis di-upsert seperti saat diputar, supaya Library offline lengkap.
- **`DownloadManager`:** antrean persisten (`localData/downloads.json`), maksimal 2 unduhan paralel,
  memakai `StreamFetcher` (chunk 4 MiB, re-resolve saat URL kedaluwarsa), resume dari ukuran `.part`,
  verifikasi `ukuran == contentLength`, lalu `ATOMIC_MOVE` ke nama final. Status di-ekspos sebagai
  `StateFlow<Map<String, DownloadState>>` (Queued, Downloading(pct), Completed, Failed(reason)).
- Kualitas download = kualitas pemutaran yang diset (default tinggi: 251 opus, cadangan 140 m4a).
- **Pemeriksaan saat start:** setiap `isDownloaded` tanpa file, atau dengan ukuran salah, di-reset
  ke `false` dan dicatat di log. `.part` yatim yang lebih tua dari 7 hari dihapus.
- **Offline playback:** `PlayerController` memeriksa `DownloadStore.fileFor(videoId)` sebelum
  resolver. Jika ada, engine memutar `file://...` dan resolver tidak dipanggil. Saat tanpa jaringan,
  lagu yang tidak di-download dilewati dengan pesan "Tidak tersedia offline".
- Hapus download: hapus file + artwork, lalu `isDownloaded = false`.

### 2.8 Ketahanan pemutaran

Logika retry mengikuti `MusicService` (maksimal 3 retry per lagu dengan jeda 1 s, daftar
`recentlyFailedSongs` 5 menit, skip ke lagu berikutnya setelah gagal), dipindah ke `PlayerController`
sebagai state machine murni yang di-unit-test dengan `FakePlaybackEngine`. Error ditampilkan di
player bar (`StreamFailureUi` versi desktop).

---

## 3. D3 — UI desktop MVP

### 3.1 Shell

```
┌──────────────┬───────────────────────────────────────────────┬──────────────────┐
│  Sidebar     │  TopBar: ◀ ▶  [ Cari lagu, artis, link… ]  👤  │  Panel kanan     │
│  ─ Beranda   ├───────────────────────────────────────────────┤  [Antrean|Lirik] │
│  ─ Cari      │                                               │                  │
│  Library     │           Konten (NavDisplay)                 │  (bisa ditutup;  │
│  ─ Disukai   │                                               │   otomatis       │
│  ─ Playlist  │                                               │   tersembunyi    │
│  ─ Riwayat   │                                               │   < 1200 px)     │
│  ─ Download  │                                               │                  │
│  ─ Pengaturan│                                               │                  │
├──────────────┴───────────────────────────────────────────────┴──────────────────┤
│ PlayerBar: art · judul/artis · ♥ │ ⇄ ⏮ ⏯ ⏭ ↻  ──●────── 1:23/3:45 │ EQ 🔊── ☰ 🎤 │
└────────────────────────────────────────────────────────────────────────────────┘
```

- Ukuran minimum jendela 960×600. Posisi, ukuran, dan panel kanan disimpan di `WindowStateKey`/`RightPanelKey`.
- Dekorasi jendela native. Di macOS: `apple.awt.transparentTitleBar` + `fullWindowContent` supaya
  sidebar menyatu dengan title bar.
- Tema dari `core:designsystem`: gelap default, mengikuti `ThemeKey`. Warna dinamis dari artwork
  memakai materialKolor (sudah KMP, sama dengan Android). Gambar memakai Coil 3 KMP.
- "Now Playing" layar penuh (klik artwork di player bar): artwork besar + lirik. Ini mode tampilan
  shell, bukan rute.

### 3.2 Daftar layar MVP

| # | Layar | Isi | Sumber data | Padanan Android |
|---|---|---|---|---|
| 1 | Beranda | Quick picks, section feed beranda (chip mood), lanjutkan mendengarkan | `HomeFeedRepository` (`YouTube.home()`, filter explicit/video/AI/shorts), DB | `HomeScreen`/`HomeViewModel` |
| 2 | Cari | Saran saat mengetik (dropdown), hasil per filter (Lagu, Video, Album, Artis, Playlist), riwayat pencarian, dan tempel link → buka langsung | `SearchRepository`, `SearchHistory` DAO | `search/*`, `OnlineSearch*ViewModel` |
| 3 | Album | Header, daftar lagu, putar/acak/tambah ke antrean/download album | `AlbumRepository` | `AlbumScreen` |
| 4 | Artis | Header, lagu populer, album, single, video, artis serupa | `ArtistRepository` (tanpa canvas/video) | `artist/*` |
| 5 | Playlist | Online (YouTube) dan lokal. Lokal: ganti nama, hapus, tambah/hapus lagu, urut ulang dengan drag | `PlaylistRepository`, DAO playlist | `playlist/*` |
| 6 | Library: Disukai | Lagu yang di-like (lokal + sinkron YouTube jika login), urutkan, cari lokal | DAO | `library/*` |
| 7 | Library: Playlist | Playlist lokal + playlist akun YouTube (jika login), buat playlist | DAO + `AccountRepository` | `library/*` |
| 8 | Riwayat | Dikelompokkan per hari, hapus item | DAO `Event` | `HistoryScreen` |
| 9 | Download | Daftar unduhan + progres, hapus, putar semua offline | `DownloadManager`/`DownloadStore` | download di library |
| 10 | Panel Antrean | Daftar antrean, drag urut ulang, hapus, "putar berikutnya", simpan sebagai playlist | `PlayerController` | `QueueScreen` |
| 11 | Panel Lirik | Lirik sinkron (baris aktif, klik untuk seek, auto-scroll), pilih provider, lirik biasa sebagai cadangan | `core:lyrics` `LyricsHelper` | `LyricsScreen` (tanpa glow/blur) |
| 12 | Equalizer | Toggle, 10 slider vertikal, preamp, preset, simpan preset custom, toggle normalisasi | `EqualizerController` | `equalizer/*` (versi sederhana) |
| 13 | Pengaturan | Akun (login/logout/manual), Pemutaran (kualitas, normalisasi), Tampilan (tema), Download (folder, hapus semua), Lirik (provider), Discord (mode, status), Sistem (tray, deep link), Pembaruan (cek, kanal beta), Tentang (versi, lisensi pihak ketiga, folder log) | settings | `settings/*` (subset) |
| 14 | Jendela Login | Jendela JCEF terpisah + tautan "Login manual" | `desktop:web` | `YouTubeLoginScreen` |
| 15 | Dialog | Instal komponen browser (progres), tambah ke playlist, konfirmasi hapus, pembaruan tersedia | – | – |

Menu konteks (klik kanan) untuk item lagu: Putar berikutnya, Tambah ke antrean, Tambah ke playlist,
Suka, Download/Hapus download, Buka album, Buka artis, Mulai radio, Salin link.

**Pintasan keyboard:** `Space` play/pause (selain saat fokus di field teks), `Ctrl/Cmd+→/←` next/prev,
`Shift+→/←` seek ±10 s, `Ctrl/Cmd+↑/↓` volume, `Ctrl/Cmd+F` atau `/` ke kotak cari, `Ctrl/Cmd+L`
like, `Ctrl/Cmd+,` pengaturan, `Ctrl/Cmd+Shift+Q` panel antrean, `Ctrl/Cmd+Shift+L` panel lirik.
Menu bar macOS (`MenuBar` Compose) berisi Playback dan View dengan pintasan yang sama.

### 3.3 Komponen: bersama vs khusus desktop

| Komponen | Modul | Catatan |
|---|---|---|
| Token warna/tipografi/shape, tombol, chip, slider, dialog dasar | `core:designsystem` (D1b) | Sudah ada |
| `Thumbnail` (Coil, placeholder shimmer), `SongListItem`, `SongGridItem`, `AlbumCard`, `ArtistCard`, `PlaylistCard`, `SectionHeader`, `ShimmerList`, `EmptyState`, `ErrorState` | **`core:ui`** (baru, KMP) | Tanpa blur/`RenderEffect`/haptic. Parameter murni (model + lambda), tanpa ViewModel |
| `SyncedLyricsView` (render baris + highlight + klik seek) | `core:ui` | Logika parsing tetap di `core:lyrics` |
| `ProgressSlider`, `PlayPauseButton`, `LikeButton`, `DownloadStateIcon` | `core:ui` | |
| Sidebar, TopBar, PlayerBar, RightPanel, NowPlayingOverlay, menu konteks, `MenuBar`, `Tray` | `desktopApp` | Hanya untuk desktop |
| `SwingPanel` JCEF, dialog instal CEF | `desktop:web` + `desktopApp` | |
| Slider EQ vertikal | `desktopApp` | |

Android tidak mengganti komponennya dengan `core:ui` di D3. Itu masuk D5 (utang #4), dengan
perbandingan screenshot seperti di D1.

### 3.4 ViewModel: dipakai bersama vs ditulis ulang

ViewModel Android memakai `@HiltViewModel`, `Context`, `dataStore` ekstensi, dan `PlayerConnection`,
jadi **semua ViewModel desktop ditulis ulang** sebagai kelas tipis di `desktopApp/feature/*`.
Logika yang tidak terikat UI diekstrak ke repository `core:data` (JVM) supaya nanti bisa dipakai
Android.

| ViewModel Android | ViewModel desktop | Logika yang diekstrak ke `core:*` | Android di D3 |
|---|---|---|---|
| `HomeViewModel` (+`HomeFeedCache`, `HomeRecommender`) | `HomeViewModel` | `HomeFeedRepository` (home + filter + cache) | Tidak berubah (salin, utang #2) |
| `OnlineSearchViewModel`, `OnlineSearchSuggestionViewModel` | `SearchViewModel` | `SearchRepository` | Tidak berubah (salin) |
| `AlbumViewModel` | `AlbumViewModel` | `AlbumRepository` | Tidak berubah (salin) |
| `ArtistViewModel`, `ArtistItemsViewModel`, `ArtistAlbumsViewModel` | `ArtistViewModel` | `ArtistRepository` | Tidak berubah (salin) |
| `OnlinePlaylistViewModel`, `LocalPlaylistViewModel` | `PlaylistViewModel` | `PlaylistRepository` | Tidak berubah (salin) |
| `LibraryViewModels`, `PlaylistsViewModel` | `LibraryViewModel` | DAO langsung + `LibraryRepository` | Tidak berubah |
| `HistoryViewModel` | `HistoryViewModel` | DAO langsung | Tidak berubah |
| `LyricsScreenViewModel`, `LyricsMenuViewModel` | `LyricsViewModel` | **`core:lyrics` dipindah** (bukan disalin): `isEnabled(context)` → `isEnabled(settings: Preferences)`, `LruCache` → LRU `LinkedHashMap`, Timber → `Logger`. `EmbeddedLyrics*` (ParcelFileDescriptor) tetap di `app/` | Berubah mekanis (import + `@Provides`) + smoke test lirik Android |
| `AccountViewModel`, `AccountSettingsViewModel` | `AccountViewModel` | `AccountRepository` (status login dari settings, `accountInfo`, playlist akun) | Tidak berubah (salin) |
| `ThemeViewModel` | `ThemeState` (bukan ViewModel) | – | Tidak berubah |
| Lainnya (AudioLab, Charts, MoodAndGenres, NewRelease, Explore, Import, Modules, Recap, Onboarding, BackupRestore…) | – | – | Di luar MVP (D5) |

Repository dibuat dengan cara **menyalin** logika dari ViewModel Android ke kelas tanpa Context,
lalu menambahkan unit test dengan `YouTube` palsu atau respons rekaman. Salinan dicatat di utang D5
(§8) supaya Android pindah ke repository yang sama setelah desktop stabil.

### 3.5 Login YouTube

1. Tombol **Login** memastikan CEF terpasang (§2.5), lalu membuka `LoginWindow`: `Window` Compose
   yang hanya berisi `SwingPanel` browser JCEF **windowed** (bukan OSR, supaya input dan IME normal).
   Tidak ada UI Compose yang menimpa browser, karena `SwingPanel` selalu di atas lapisan Compose.
2. URL awal: `https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com`
   (sama dengan Android). User-Agent memakai default CEF (UA Chrome jujur), tanpa spoofing.
3. `CefDisplayHandler.onAddressChange` mendeteksi URL yang diawali `https://music.youtube.com`.
   Setelah itu `CefCookieManager.visitUrlCookies("https://music.youtube.com", true, …)` membangun
   string cookie, yang wajib berisi `SAPISID`. `READ_CONFIG_JS` dijalankan lewat
   `executeJavaScript` + message router untuk `visitorData`/`dataSyncId`. Alur penyimpanannya sama
   dengan `YouTubeLoginScreen.finishLogin`: `InnerTubeCookieKey`, `VisitorDataKey`, `DataSyncIdKey`,
   `AccountNameKey`, `AccountEmailKey`, `AccountChannelHandleKey`.
4. Profil CEF untuk login persisten di `localData/cef-profile/`. **Logout** menghapus folder ini
   dan mengosongkan key di atas. BotGuard memakai `CefRequestContext` terpisah tanpa profil
   persisten.
5. **Login manual (selalu tersedia):** dialog berisi langkah singkat (buka music.youtube.com di
   browser → DevTools → Network → request `browse` → salin header `cookie`), field tempel, lalu
   validasi `SAPISID`/`__Secure-3PAPISID` ada dan `YouTube.accountInfo()` berhasil. Hasilnya
   disimpan dengan key yang sama. Ini pola "browser auth" ytmusicapi [R18].
6. Google memblokir login dari framework embedded seperti CEF sejak 2021, tetapi penerapannya
   heuristik [R17]. Eksperimen E5 menentukan apakah login JCEF menjadi jalur utama. Jika E5 gagal,
   tombol utama di UI menjadi **Login manual**, dan login JCEF tetap ada sebagai "Coba login lewat
   browser bawaan".

### 3.6 Discord Rich Presence

| Mode | Cara kerja | Syarat | Default |
|---|---|---|---|
| **IPC** | Klien IPC (`DiscordIpcClient` sendiri, ~250 baris: framing opcode + JSON di named pipe `\\.\pipe\discord-ipc-N` / socket `$TMPDIR/discord-ipc-N`) mengirim `SET_ACTIVITY` | Aplikasi Discord berjalan. Application ID "Musicfy" dibuat sekali di Discord Developer Portal dan disimpan sebagai konstanta | **Aktif** jika `EnableDiscordRPCKey` aktif |
| **Kizzy** | `providers:kizzy` (JVM, Ktor + OkHttp WebSocket ke gateway). **Layak dari JVM**: tidak ada API Android, sudah dikompilasi sebagai jar di D1a | Token akun Discord (`DiscordTokenKey`) ditempel pengguna | Opt-in, dengan peringatan bahwa self-bot melanggar ToS Discord dan berisiko akun diblokir [R19] |

- Klien IPC ditulis sendiri karena library JVM yang ada sudah diarsipkan atau tidak aktif sejak
  2023–2025 [R20], sedangkan protokol IPC-nya kecil dan stabil.
- Isi activity: judul (details), artis (state), artwork (`large_image` = URL https thumbnail, cadangan
  aset `musicfy`), timestamp mulai/selesai, tombol "Dengarkan di YouTube Music". `type = 2`
  (Listening) dicoba lebih dulu. Jika Discord mengabaikannya, activity tetap dikirim sebagai Playing
  (diverifikasi di E6).
- Update di-*debounce* 2 s. Saat pause, activity dihapus (`SET_ACTIVITY` null), sama seperti perilaku
  Android saat layar mati dan tidak memutar. Saat Discord tidak berjalan, reconnect dicoba setiap
  30 s tanpa log berisik.

### 3.7 Equalizer UI

Layar EQ (§3.2 #12) mengikat `EqualizerController` di `desktop:player`. Perubahan slider langsung
diterapkan dan disimpan dengan debounce 500 ms. Tombol "EQ" di player bar membuka layar ini.

---

## 4. D4 — Paket & distribusi

### 4.1 Target

| OS | Arsitektur | Format | Runner build | Wajib? |
|---|---|---|---|---|
| macOS 12+ | arm64 | DMG | `macos-15` | Ya |
| macOS 12+ | x64 | DMG | `macos-15-intel` | Ya |
| Windows 10 1809+ / 11 | x64 | **EXE** (installer jpackage) + MSI | `windows-latest` | Ya |
| Linux (Ubuntu 22.04+/Debian 12) | x64 | DEB | `ubuntu-22.04` | Nice-to-have |

Plugin Compose tidak mendukung cross-compilation. Setiap format dibangun di OS-nya. Universal
binary macOS ditolak, karena jpackage membundel JRE satu arsitektur.

### 4.2 Isi bundle

| Komponen | Sumber | Catatan |
|---|---|---|
| JRE | jlink dari **Temurin JDK 21** (sama dengan `jvmToolchain(21)`) | Modul dikunci lewat `modules(...)`: `java.desktop`, `java.logging`, `java.net.http`, `java.sql`, `jdk.httpserver`, `jdk.unsupported`, `jdk.crypto.ec`, `java.management`, `jdk.accessibility`. Divalidasi dengan task `suggestRuntimeModules` |
| Aplikasi + Compose/Skiko | Gradle | **ProGuard** release aktif (`buildTypes.release.proguard`), dengan keep rules untuk kotlinx.serialization, Room, JNA/vlcj, Koin, Ktor. Default jika ProGuard merusak: matikan ProGuard dan **ganti `material-icons-extended` dengan salinan ikon yang dipakai** di `core:designsystem` |
| libVLC + plugin | `fetch-libvlc` (§2.1) → `appResources/<os-arch>/vlc` | macOS: di-thin per arsitektur |
| Helper native | `buildNativeHelpers` → `appResources/<os-arch>/` | dylib Swift / DLL SMTC |
| Native di dalam jar (JNA `jnidispatch`, sqlite-bundled, quickjs, skiko) | Saat build untuk macOS, diekstrak ke `appResources/macos-*/natives` dan di-sign. Path diarahkan lewat system property (`jna.boot.library.path`, dll.) | Notarization menolak Mach-O tanpa tanda tangan di dalam jar |
| CEF | **Tidak** di-bundle (on-demand, K4) | |

**Estimasi ukuran (target diverifikasi di D4.1):**

| | macOS arm64 | Windows x64 |
|---|---|---|
| JRE jlink | ~55 MB | ~50 MB |
| App + Compose/Skiko + dependensi (setelah ProGuard) | ~60 MB | ~55 MB |
| libVLC audio-only | ~25 MB | ~25 MB |
| **Terpasang** | **~140 MB** | **~130 MB** |
| **Installer terkompresi** | **~70–85 MB** | **~60–75 MB** |
| CEF on-demand | unduhan ~100 MB, terpasang ~250–300 MB | sama |

**Anggaran:** installer ≤ 120 MB. Jika terlampaui di D4.1, urutan pemangkasannya: plugin `avcodec`
libVLC (opus/faad cukup), ikon extended, lalu modul jlink.

### 4.3 Konfigurasi `nativeDistributions` (sketsa)

```kotlin
compose.desktop.application {
    mainClass = "com.example.musicfy.desktop.MainKt"
    jvmArgs += listOf("-Xmx768m", "-Dcompose.application.configure.swing.globals=true")
    buildTypes.release.proguard { isEnabled = true; configurationFiles.from("proguard-rules.pro") }
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
        packageName = "Musicfy"
        packageVersion = providers.gradleProperty("musicfy.desktop.version").get()  // X.Y.Z
        description = "Musicfy — YouTube Music client"
        vendor = "Musicfy"
        licenseFile = rootProject.file("LICENSE")
        appResourcesRootDir = layout.projectDirectory.dir("resources")   // <os-arch>/vlc, natives
        modules("java.desktop", "java.logging", "java.net.http", "java.sql", "jdk.httpserver",
                "jdk.unsupported", "jdk.crypto.ec", "java.management", "jdk.accessibility")
        macOS {
            bundleID = "com.example.musicfy.desktop"      // tetap selamanya
            dmgPackageVersion = packageVersion
            iconFile = file("icons/musicfy.icns")
            // kedua file entitlements: allow-jit, allow-unsigned-executable-memory, disable-library-validation
            entitlementsFile = file("macos/entitlements.plist")
            runtimeEntitlementsFile = file("macos/runtime-entitlements.plist")
            infoPlist { extraKeysRawXml = MUSICFY_URL_SCHEME_PLIST }     // CFBundleURLTypes musicfy://
            signing { sign = signingEnabled; identity = "Developer ID Application: …" }
            notarization { appleID = …; password = …; teamID = … }       // dari env, hanya bila ada
        }
        windows {
            iconFile = file("icons/musicfy.ico")
            upgradeUuid = "<UUID tetap, dibuat sekali, jangan diubah>"   // syarat upgrade MSI/EXE
            perUserInstall = true; dirChooser = true; menu = true; shortcut = true
            menuGroup = "Musicfy"
        }
        linux { iconFile = file("icons/musicfy.png"); debMaintainer = "…"; appCategory = "AudioVideo" }
    }
}
```

- WiX untuk MSI/EXE diunduh oleh plugin Compose sendiri. D4.1 memverifikasi ini di `windows-latest`.
  Jika tidak, workflow memasang WiX 3.14 lewat `dotnet tool`/choco sebelum packaging.
- Dependensi DEB (`libvlc5`, `vlc-plugin-base`) ditambahkan lewat override `control` setelah
  `packageDeb`.

### 4.4 GitHub Actions — `.github/workflows/desktop.yml`

| Job | Pemicu | Runner | Langkah | Output |
|---|---|---|---|---|
| `desktop-test` | PR + push yang menyentuh `core/**`, `desktop/**`, `desktopApp/**`, `providers/**` | matriks `ubuntu-latest`, `macos-15`, `windows-latest` | `./gradlew :desktopApp:check :desktop:player:test :desktop:downloads:test …` (termasuk test engine headless §6) | Laporan test |
| `package` | push ke `main` dan `feature/desktop-*`, `workflow_dispatch`, tag `desktop-v*` | matriks: `macos-15` (arm64), `macos-15-intel` (x64), `windows-latest` (x64), `ubuntu-22.04` (DEB, `continue-on-error`) | setup Temurin 21 → cache Gradle → `fetch-libvlc` (cache per versi) → `buildNativeHelpers` → `packageReleaseDmg` / `packageReleaseExe packageReleaseMsi` / `packageReleaseDeb` → **smoke test installer** (§6.3) → rename → upload artifact | `Musicfy-X.Y.Z-macos-arm64.dmg`, `Musicfy-X.Y.Z-macos-x64.dmg`, `Musicfy-X.Y.Z-windows-x64.exe`, `Musicfy-X.Y.Z-windows-x64.msi`, `musicfy_X.Y.Z_amd64.deb` (retensi 14 hari) |
| `release` | hanya tag `desktop-vX.Y.Z` | `ubuntu-latest` | verifikasi tag == `musicfy.desktop.version` → unduh semua artifact → `SHA256SUMS.txt` → `gh release create` (draft=false, prerelease jika input/tag berisi label `beta` di pesan tag) dengan catatan rilis + instruksi unsigned | GitHub Release |

- `build.yml` dan `build_pr.yml` Android yang sudah ada tidak diubah, selain langkah desktop dari D1
  (`compileKotlinDesktop`), yang dipindah ke `desktop-test`.
- Concurrency group per ref, membatalkan run lama.
- `macos-15-intel` adalah image Intel terakhir GitHub (didukung sampai Agustus 2027) [R21]. Rencana
  setelahnya ada di risiko R-7.

### 4.5 Rilis & versi

- Sumber versi: `musicfy.desktop.version=1.0.0` di `gradle.properties`. Versi desktop **terpisah**
  dari `versionName` Android ("8.0.0 build#1150"), karena format itu tidak lolos aturan MSI/DMG.
- Aturan: numerik `X.Y.Z`, `1 ≤ X ≤ 255`, `Y ≤ 255`, `Z ≤ 65535` (batas MSI, DMG menolak MAJOR 0).
  Setiap rilis menaikkan setidaknya `Z`. Beta adalah versi biasa yang ditandai *prerelease* di
  GitHub, tanpa sufiks di `packageVersion`, supaya upgrade MSI selalu ke versi yang lebih tinggi.
- Tag: `desktop-vX.Y.Z`. Tag Android tetap dengan skemanya sendiri. Nomor build (`GITHUB_RUN_NUMBER`)
  dan commit SHA ditulis ke `BuildInfo` (resource) dan tampil di Tentang.
- Rilis publik pertama: **1.0.0** di akhir D4.

### 4.6 Signing, notarization, dan cadangan unsigned

| | macOS | Windows |
|---|---|---|
| Yang dibutuhkan | Apple Developer Program (US$99/tahun) → sertifikat **Developer ID Application** + notarization (`notarytool`) dengan Apple ID + app-specific password, atau API key App Store Connect, + Team ID | **SignPath Foundation** (gratis untuk proyek OSS berlisensi OSI. GPL-3 memenuhi syarat. Sertifikat atas nama SignPath Foundation, wajib halaman kebijakan code signing dan MFA) [R22]. Alternatif berbayar: Azure Artifact Signing atau sertifikat OV |
| Secret GitHub | `MACOS_CERT_P12_BASE64`, `MACOS_CERT_PASSWORD`, `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_PASSWORD`, `MACOS_TEAM_ID` | `SIGNPATH_API_TOKEN`, `SIGNPATH_ORGANIZATION_ID` (+ project/policy slug) |
| Perilaku workflow | Ada secret: import ke keychain sementara → sign semua Mach-O (libVLC, plugin, helper, natives) dengan hardened runtime → `notarizeReleaseDmg` → `stapler staple` | Ada secret: unggah EXE/MSI ke SignPath lewat GitHub Action resminya → unduh hasil tanda tangan |
| **Tanpa secret (default sampai tersedia)** | `codesign --force --deep -s -` (ad-hoc, wajib agar arm64 bisa jalan). Catatan rilis: buka app → diblokir → **System Settings → Privacy & Security → "Open Anyway"** → konfirmasi (macOS 15 menghapus jalan pintas Control-klik [R23]). Jika muncul "rusak": `xattr -dr com.apple.quarantine /Applications/Musicfy.app` | Catatan rilis: SmartScreen → **"More info" → "Run anyway"**. **Smart App Control** (Windows 11 instalasi bersih) memblokir file unsigned tanpa opsi bypass [R24], jadi pengguna harus mematikan Smart App Control atau menunggu build bertanda tangan. Ini dinyatakan jelas |

Pengajuan SignPath dan pembelian Apple Developer ID adalah **tugas pengguna**, bukan blocker D4.
Workflow mendeteksi ketiadaan secret dan memilih jalur unsigned secara otomatis.

### 4.7 Auto-update

- `UpdateChecker` (desktopApp) berjalan saat start jika `UpdateCheckKey` aktif (default aktif) dan
  sudah ≥ 24 jam sejak `LastUpdateCheckKey`. Ada juga tombol manual di Pengaturan.
- `GET https://api.github.com/repos/fikrunamin/musicfy/releases?per_page=30` tanpa autentikasi
  (batas 60 request/jam/IP, jauh di atas kebutuhan). Saring `tag_name` berawalan `desktop-v`,
  `draft == false`, dan `prerelease` hanya jika `UpdateChannelKey == beta`. Bandingkan SemVer, lalu
  pilih aset sesuai OS/arsitektur dari pola nama §4.4.
- Banner non-blocking: "Musicfy X.Y.Z tersedia" → **Unduh & pasang** / **Lewati versi ini**
  (`SkippedUpdateKey`) / **Nanti**.
- Unduh ke `AppDirs.cache/update/`, verifikasi SHA-256 terhadap `SHA256SUMS.txt` dari rilis yang
  sama, lalu:
  - Windows: jalankan installer (`msiexec /i … ` atau `.exe`) dan keluar dari aplikasi.
  - macOS: `open` DMG, tampilkan instruksi seret ke Applications, dan keluar.
  - Linux: buka halaman rilis di browser.
- Jika repo privat atau API gagal (404/403/jaringan), pemeriksa diam saja (hanya log).
- Ditolak: Nucleus updater (plugin muda dengan satu maintainer, dievaluasi ulang di D5), Conveyor
  (toolchain proprietary), dan update in-place macOS (butuh signing, ditunda sampai Developer ID ada).

### 4.8 Lisensi & kepatuhan GPL-3

- Installer menyertakan `LICENSE` (GPL-3.0) dan `THIRD_PARTY_NOTICES.txt` yang dihasilkan plugin
  AboutLibraries (KMP), dan juga ditampilkan di Pengaturan → Tentang.
- vlcj berlisensi GPL-3.0-or-later sehingga kompatibel. libVLC LGPL-2.1+, plugin libVLC LGPL-2.1+
  atau GPL-2.0-or-later. **D4.1 mengaudit lisensi setiap plugin di `vlc-plugins.txt`.** Plugin
  GPL-2.0-only dikeluarkan.
- Penawaran sumber: catatan rilis menautkan tag sumber Musicfy dan tarball resmi `vlc-3.0.23.tar.xz`.
  JRE Temurin berlisensi GPL-2 + Classpath Exception.

---

## 5. Fase, milestone, dan kriteria terima

Setiap milestone berakhir dengan sesuatu yang bisa dijalankan, dikerjakan sebagai satu PR (atau
beberapa PR kecil), lolos CI, dan checklist-nya ditempel di deskripsi PR.

### 5.0 Eksperimen penentu (awal D2, maksimal 3 hari total)

| ID | Pertanyaan | Metode | Lulus jika | Default jika gagal |
|---|---|---|---|---|
| E1 | vlcj + libVLC 3.0.23 yang di-bundle memutar opus/webm dan m4a lewat proxy di macOS arm64, macOS x64, dan Windows x64? | Program `desktop:player` `spike` + test headless (§6.2) di 3 runner CI + manual di Mac dev | Fixture memutar sampai `Ended`, seek ke 50% berhasil, tanpa VLC terpasang di sistem | libmpv + binding JNA sendiri (K1 cadangan) |
| E2 | QuickJS menjalankan solver EJS + player.js saat ini dengan benar? | Bandingkan 20 pasang n/sig dengan output WebView Android (fixture direkam dari perangkat) | 20/20 sama. Inisialisasi ≤ 3 s, panggilan berikutnya ≤ 50 ms | GraalJS interpreter. Jika > 10 s: `JsRuntime` berbasis JCEF |
| E3 | jcefmaven (natives diunduh) bisa init OSR dan mint PoToken dari app jpackage di 3 platform? | `CefBotGuardRuntime` + paksa client `WEB_REMIX` (`-Dmusicfy.resolver.forceClient=WEB_REMIX`) | Token terbentuk, dan stream WEB_REMIX terunduh > 1 MB (melewati jendela ~786 KB tanpa token) | JBR + JCEF bundled. Jika itu juga gagal: tanpa PoToken (dicatat) |
| E4 | JMTC menampilkan overlay SMTC dan menerima tombol media dari app jpackage? | Build EXE, putar lagu, tekan tombol media/overlay | Judul/artis/artwork tampil. Play/pause/next bekerja | DLL C++/WinRT sendiri |
| E5 | Login Google di JCEF lolos (akun pribadi baru dan akun lama, macOS dan Windows)? | `LoginWindow` | Sampai ke `music.youtube.com` dengan cookie `SAPISID` di ≥ 3 dari 4 percobaan | Login manual menjadi tombol utama (§3.5) |
| E6 | Discord IPC menerima `type=2` dan `large_image` berupa URL https? | `DiscordIpcClient` di Mac dev | Profil menampilkan "Listening to Musicfy" dengan artwork | Activity Playing + aset `musicfy` |

E1–E3 dikerjakan di D2.0. E4 di D2.4. E5 di D3.5. E6 di D3.6.

### 5.1 D2 — Mesin pemutar

| Milestone | Isi | Bisa dijalankan | Kriteria terima |
|---|---|---|---|
| **D2.0** | Eksperimen E1–E3, `fetch-libvlc`, `DesktopAppDirs.localData` | Spike CLI | Hasil E1–E3 dicatat di PR, termasuk default yang dipilih |
| **D2.1** | `StreamProxy`, `VlcPlaybackEngine` (satu player), jalur resolver VISIONOS tanpa JS | `./gradlew :desktopApp:run --args="--play <videoId>"` memutar lagu | Lagu diputar sampai habis di macOS. Seek ke 2:00 berhasil (range lewat proxy). Log menampilkan client + itag. Unit test proxy hijau |
| **D2.2** | `core:playback` (salinan queue), `PlayerController` + `dispatch`, preload gapless, persistensi antrean, riwayat/play count | UI debug minimal di `desktopApp`: cari → putar → next/prev | Antrean pulih setelah restart. Jeda antar-lagu ≤ 300 ms (log). Riwayat tercatat di DB. Test `PlayerController` hijau |
| **D2.3** | EQ 10-band, preset, preamp, volume, normalisasi | Slider debug | EQ terdengar berubah (manual). Nilai tersimpan. Test rumus gain dan pemetaan preset |
| **D2.4** | `MediaSessionBridge` (E4, dylib Swift, MPRIS), single instance, `DeepLinkRouter`, registrasi skema | Tombol media OS mengontrol pemutaran | Checklist manual §6.4 bagian OS lulus di macOS. Test router dan single-instance hijau. Windows diverifikasi lewat E4 di build CI |
| **D2.5** | `QuickJsRuntime` (E2), cipher + n-transform aktif | `--force-client=WEB` memutar | Lagu yang memaksa client web terputar penuh |
| **D2.6** | `CefInstaller`, `CefBotGuardRuntime` (E3), banner instal | `--force-client=WEB_REMIX` setelah instal CEF | Kriteria E3. Tanpa CEF, resolver tetap jatuh ke client berikutnya tanpa crash |
| **D2.7** | `DownloadManager`, `DownloadStore`, offline playback | Download dari menu debug, matikan Wi-Fi, lalu putar | File muncul di layout §2.7. Resume setelah kill proses. Lagu terunduh terputar offline. Test resume dan atomic move hijau |

### 5.2 D3 — UI MVP

| Milestone | Isi | Kriteria terima |
|---|---|---|
| **D3.0** | `core:ui`, shell (sidebar, top bar, player bar, panel kanan), navigasi, tema, state jendela, pintasan dasar | Aplikasi terbuka ke shell. Player bar terhubung ke `PlayerController`. Back/forward bekerja. Test Compose UI shell hijau |
| **D3.1** | Beranda + Cari (saran, filter, riwayat, tempel link) + menu konteks | Dari beranda atau cari, lagu bisa diputar dan ditambahkan ke antrean. Test repository + test UI dengan repository palsu |
| **D3.2** | Album, Artis, Playlist online | Semua bisa dibuka dari hasil cari/beranda. Putar/acak/antrekan bekerja |
| **D3.3** | Library: Disukai, Playlist (CRUD + urut ulang), Riwayat | Data persisten setelah restart. Like dari player bar muncul di Disukai |
| **D3.4** | Panel Antrean + Panel Lirik (`core:lyrics` dipindah) + Now Playing | Lirik sinkron mengikuti posisi dan klik baris melakukan seek. Drag antrean bekerja. **Smoke test lirik Android** (D1 §2.2) lulus |
| **D3.5** | Login JCEF (E5) + Login manual + logout + fitur akun (beranda personal, playlist akun, sinkron like) | Setelah login, beranda menampilkan konten personal. Logout membersihkan profil |
| **D3.6** | Layar Download, Equalizer, Discord (IPC + Kizzy opt-in, E6), Pengaturan | Semua item §3.2 #9, #12, #13 berfungsi. Discord menampilkan lagu |
| **D3.7** | Polish: state kosong/error/offline, pintasan lengkap, `MenuBar` macOS, tray opsional, log file + crash log | Checklist UI §6.4 lulus penuh di macOS |

### 5.3 D4 — Paket & distribusi

| Milestone | Isi | Kriteria terima |
|---|---|---|
| **D4.1** | `nativeDistributions`, bundling libVLC/helper/natives, ProGuard, jlink modules, ikon, audit lisensi plugin | `packageReleaseDmg` lokal menghasilkan app yang berjalan dari `/Applications` di Mac tanpa VLC/JDK. Ukuran dicatat dan ≤ anggaran |
| **D4.2** | `desktop.yml` job `desktop-test` + `package` + smoke test installer | Artifact DMG arm64, DMG x64, EXE, MSI (dan DEB) muncul di run Actions. Smoke test §6.3 hijau di setiap runner |
| **D4.3** | Job `release` dari tag, `SHA256SUMS`, catatan rilis dengan instruksi unsigned | Tag uji `desktop-v1.0.0` di fork atau branch menghasilkan Release lengkap (dihapus setelah verifikasi) |
| **D4.4** | Jalur signing kondisional (macOS + SignPath) | Tanpa secret: jalur unsigned berjalan. Dengan secret (saat tersedia): `spctl -a -vv` "accepted" dan `signtool verify /pa` lulus |
| **D4.5** | `UpdateChecker` | Test unit perbandingan versi + pemilihan aset + verifikasi hash. Manual: build 1.0.0 mendeteksi rilis uji 1.0.1 |
| **D4.6** | DEB Linux (libVLC sistem, MPRIS) | DEB terpasang di Ubuntu 22.04 (CI `dpkg -i` + smoke test). Kegagalan tidak memblokir rilis |

**Definisi selesai D2–D4:** rilis `desktop-v1.0.0` berisi DMG arm64 + x64 dan EXE + MSI. Setiap
installer lolos smoke test otomatis di CI. Checklist manual §6.4 lulus di macOS arm64. Checklist
bagian Windows lulus pada build CI melalui self-test dan E4.

---

## 6. Strategi test

Tidak ada tester manusia. Karena itu yang bisa diotomasi wajib diotomasi, dan sisanya berupa
checklist yang dijalankan agen implementasi di Mac dev (arm64), dengan hasil ditempel di PR.

### 6.1 Unit test (JVM, semua OS di CI)

| Modul | Test |
|---|---|
| `desktop:player` | `StreamProxy`: pemetaan Range → chunk 512 KiB, `Content-Range` benar, re-resolve sekali pada 403, token salah → 404 (upstream = OkHttp `MockWebServer`). `PlayerController` dengan `FakePlaybackEngine`: next/prev/shuffle/repeat/autoplay, retry 3x lalu skip, persistensi antrean round-trip, ambang riwayat. Gain normalisasi, pemetaan preset EQ |
| `core:playback` | Paginasi queue dengan `YouTube` palsu, filter explicit/video |
| `core:lyrics` | Test yang sudah ada + test `isEnabled(settings)` |
| `core:data` | Repository dengan respons InnerTube rekaman (fixture JSON) |
| `desktop:downloads` | Resume dari `.part`, verifikasi ukuran, atomic move, reset `isDownloaded` saat file hilang (DB in-memory `BundledSQLiteDriver`) |
| `desktop:integration` | `DeepLinkRouter`, single-instance handoff (dua instance dalam proses, folder sementara), pemetaan activity Discord, framing IPC Discord (socket palsu) |
| `desktop:js` | QuickJs dengan fixture n/sig dari E2 (player.js versi rekaman, di-commit) |
| `desktopApp` | `Koin verify()`, `UpdateChecker` (versi, aset, hash), ViewModel dengan repository palsu |

### 6.2 Test integrasi headless

- **Engine:** `VlcPlaybackEngineTest` (tag `integration`, berjalan di matriks 3 OS) memakai
  libVLC hasil `fetch-libvlc` dengan `--aout=dummy`. Fixture `silence-5s.webm` (opus) dan
  `silence-5s.m4a` (aac, keduanya dibuat sendiri, < 50 KB, di-commit) dilayani lewat `StreamProxy`
  dari `MockWebServer`. Assert: `Playing` → posisi naik → seek 2.5 s → `Ended`, dan jeda preload
  ≤ 300 ms. Linux CI memasang `libvlc-dev vlc-plugin-base` lewat apt.
- **Compose UI:** test `runComposeUiTest` untuk shell, player bar, daftar lagu, dan layar EQ dengan
  data palsu. Berjalan di Ubuntu CI dengan `xvfb-run` jika diperlukan.
- **Jaringan (opsional, `-Pnetwork-tests`, tidak di CI PR):** resolve + putar 10 detik untuk 3
  videoId nyata (biasa, age-restricted, butuh PoToken), memakai `--aout=dummy`.

### 6.3 Smoke test installer di CI (job `package`)

Aplikasi punya flag tersembunyi `--self-test`: inisialisasi Koin, buka DB dan settings di folder
sementara, muat libVLC dari bundle, putar dua fixture lewat proxy dengan `--aout=dummy`, muat helper
native (memastikan tidak gagal load), lalu keluar dengan kode 0. Jika ada langkah gagal, keluar ≠ 0
dan log dicetak.

- macOS: `hdiutil attach` DMG → salin `.app` ke folder sementara → `Musicfy.app/Contents/MacOS/Musicfy --self-test`.
- Windows: `msiexec /i Musicfy-*.msi /qn` → jalankan `%LOCALAPPDATA%\Musicfy\Musicfy.exe --self-test` → uninstall.
- Linux: `sudo apt install ./musicfy_*.deb` → `/opt/musicfy/bin/Musicfy --self-test`.

### 6.4 Checklist manual (di Mac dev, per milestone yang relevan)

- [ ] Install DMG bersih (tanpa VLC/JDK). Aplikasi terbuka ≤ 3 s ke beranda
- [ ] Cari → putar. Pause/seek/next/prev/shuffle/repeat bekerja. Jeda antar-lagu tidak mengganggu
- [ ] Tombol media keyboard + Control Center "Now Playing" menampilkan dan mengontrol lagu
- [ ] Tutup lalu buka lagi: antrean dan posisi pulih, pengaturan tetap
- [ ] `open musicfy://watch?v=<id>` saat aplikasi berjalan dan saat tertutup → lagu diputar, tanpa instance kedua
- [ ] Library: like, buat playlist, tambah/urut ulang/hapus, riwayat berisi lagu yang diputar
- [ ] Lirik sinkron mengikuti lagu, dan klik baris melakukan seek
- [ ] Login JCEF (atau manual) → beranda personal → logout bersih
- [ ] Download 3 lagu → matikan jaringan → putar ketiganya. Lagu lain menampilkan "Tidak tersedia offline"
- [ ] EQ preset "Rock" dan custom terdengar. Normalisasi menyamakan volume dua lagu berbeda
- [ ] Discord IPC menampilkan lagu, dan activity hilang saat pause
- [ ] Instal komponen browser: progres tampil, dan bisa dibatalkan dan diulang
- [ ] Pembaruan: build lama mendeteksi rilis uji
- [ ] Memori idle tanpa CEF ≤ 400 MB (Activity Monitor)

Bagian Windows yang tidak bisa dijalankan di Mac dev ditutup oleh smoke test §6.3 dan E4. Item
visual Windows (overlay SMTC, SmartScreen) dicatat sebagai "perlu verifikasi pengguna" di catatan
rilis pertama.

---

## 7. Risiko

| # | Risiko | Dampak | Kemungkinan | Mitigasi |
|---|---|---|---|---|
| R-1 | **YouTube mengubah anti-bot** (PoToken, n-challenge, perilaku VISIONOS). Google memblokir login dari CEF | Lagu tidak bisa diputar atau login gagal | Tinggi (berulang) | Jalur utama VISIONOS tanpa JS. Resolver dan solver EJS dipakai bersama dengan Android, jadi satu perbaikan untuk dua platform. Player.js di-cache per hash. Login manual selalu ada (K5). Test jaringan opsional untuk deteksi dini |
| R-2 | **Native + signing macOS**: libVLC/plugin, natives di dalam jar, dan helper harus di-sign dan cocok dengan hardened runtime. Tanpa Developer ID, Gatekeeper menyulitkan pengguna (macOS 15 tanpa Control-klik). Windows Smart App Control memblokir unsigned | Aplikasi tidak bisa dibuka atau pengguna menyerah | Tinggi | Smoke test installer di CI setiap build. Ekstraksi natives ke resources yang di-sign. Instruksi unsigned di setiap rilis. SignPath (gratis) + Developer ID dijadwalkan sebagai tugas pengguna |
| R-3 | **Keterbatasan libVLC 3**: tanpa header arbitrer, tanpa gapless sejati, EQ hanya grafis 10-band. VLC 3 menua sementara VLC 4 masih beta | Pengalaman di bawah Android (crossfade/AutoEq) | Sedang | `StreamProxy` (header, chunk, refresh). Preload dua player. Interface `PlaybackEngine` membuka jalan ke libmpv atau vlcj 5. Paritas AutoEq di D5 (callback audio libVLC + DSP Kotlin dari `eq/audio`) |
| R-4 | jcefmaven berhenti dirawat seperti KCEF, atau artefak natives hilang dari Maven Central | PoToken/login rusak | Sedang | Versi + hash di-pin. Natives bisa di-mirror ke aset GitHub Release kita (URL cadangan di `CefInstaller`). Cadangan JBR + JCEF |
| R-5 | Helper native (Swift/C++/JMTC) rapuh atau JMTC tidak aktif | Tombol media mati di satu OS | Sedang | Helper opsional: no-op jika gagal load, tidak pernah crash. E4 dengan cadangan DLL sendiri |
| R-6 | Lingkup D3 besar (15 layar + akun + download + Discord) | Jadwal molor | Sedang | Milestone kecil yang masing-masing bisa dijalankan. Repository disalin, bukan refactor Android. Fitur non-MVP dikunci di D5 |
| R-7 | `macos-15-intel` berakhir Agustus 2027 | Tidak bisa membangun DMG x64 | Pasti (terjadwal) | Sebelum Juli 2027: bangun x64 di runner arm64 dengan JDK x64 di bawah Rosetta 2 (jpackage mengikuti arsitektur JDK) + libVLC x64. Diuji sebagai job eksperimen di D5. Jika gagal, x64 dihentikan dengan pengumuman |
| R-8 | Ukuran installer melebihi anggaran | Unduhan berat | Rendah | Urutan pemangkasan di §4.2. CEF sudah on-demand |
| R-9 | Duplikasi logika (queue, repository) antara Android dan desktop | Perbaikan bug harus dilakukan dua kali | Pasti | Tabel utang D5 (§8) dengan pemilik dan urutan. Test repository menjadi jaring saat Android pindah |
| R-10 | Mode Kizzy membuat akun pengguna diblokir Discord | Keluhan pengguna | Rendah | Default IPC. Kizzy opt-in dengan peringatan eksplisit |

---

## 8. Utang yang sengaja dibuat (dibayar di D5)

| # | Utang | Pembayaran |
|---|---|---|
| 1 | Queue Android (`app/.../queues`) dan `core:playback` adalah salinan | Android memakai `core:playback` + adapter `MediaMetadata` → `MediaItem` |
| 2 | Logika ViewModel Android dan repository `core:data` adalah salinan | ViewModel Android pindah ke repository satu per satu, dengan smoke test D1 |
| 3 | EQ desktop grafis 10-band, Android parametrik/AutoEq | DSP Kotlin bersama (`BiquadFilter`/`ParametricEQ` ke `core:audio`) via callback audio libVLC, atau migrasi engine |
| 4 | Android belum memakai `core:ui` | Migrasi per komponen dengan perbandingan screenshot |
| 5 | Cookie plaintext di DataStore | Keychain OS (macOS Keychain, Windows Credential Manager, libsecret) |
| 6 | AppImage, winget, update in-place macOS | Setelah signing tersedia |

---

## Referensi

- [R1] vlcj README (versi 4.8.x vs 5.x, lisensi GPL-3.0-or-later, vlcj-natives): https://github.com/caprica/vlcj
- [R2] VLC 3.0.23 (Jan 2026) & 3.0.22: https://images.videolan.org/vlc/releases/3.0.23.html, https://jbkempf.com/blog/2026/vlc-3.0.23/
- [R3] libVLC tidak mendukung header HTTP arbitrer: https://forum.videolan.org/viewtopic.php?p=434963, https://code.videolan.org/videolan/vlc/-/issues/4789
- [R4] JavaFX Media tanpa Opus/WebM: https://bugs.openjdk.org/browse/JDK-8246299, https://openjfx.io/javadoc/23/javafx.media/javafx/scene/media/package-summary.html
- [R5] Binding libmpv KMP: https://github.com/guyuuan/mpv_kmp/wiki, https://central.sonatype.com/artifact/dev.brahmkshatriya.kmpv/kmpv
- [R6] gst1-java-core: https://github.com/gstreamer-java/gst1-java-core
- [R7] KCEF diarsipkan (28 Okt 2025): https://github.com/DATL4G/KCEF
- [R8] jcefmaven (146.0.10, natives diunduh saat run pertama, ~100 MB per bundle): https://central.sonatype.com/artifact/me.friwi/jcefmaven, https://github.com/jcefmaven/jcefmaven
- [R9] yt-dlp EJS: runtime yang didukung (Deno, Node, QuickJS/quickjs-ng): https://github.com/yt-dlp/ejs, https://github.com/yt-dlp/yt-dlp/issues/15012
- [R10] Throttling chunk > 10 MB (`--http-chunk-size`): https://mintlify.com/yt-dlp/yt-dlp/cli/download-options, https://codeberg.org/ilapro53/yt-dlp/wiki/FAQ
- [R11] BgUtils (BotGuard butuh `document`, jsdom bisa rusak): https://github.com/LuanRT/BgUtils, https://cdn.jsdelivr.net/npm/bgutils-js@4.0.3/README.md
- [R12] Compose Multiplatform 1.10 (Navigation 3 di desktop, lifecycle 2.10.0): https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html, https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
- [R13] quickjs-kt: https://github.com/dokar3/quickjs-kt, https://mvnrepository.com/artifact/io.github.dokar3/quickjs-kt-jvm
- [R14] GraalJS di JDK biasa (interpreter, tidak didukung resmi): https://github.com/oracle/graaljs/blob/master/docs/user/RunOnJDK.md
- [R15] JMTC (SMTC + MPRIS dari Java): https://github.com/Selemba1000/JavaMediaTransportControls
- [R16] MPNowPlayingInfoCenter di macOS butuh `playbackState` eksplisit: https://developer.apple.com/forums/thread/685333, https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter
- [R17] Google memblokir login dari embedded browser (CEF): https://developers.googleblog.com/2020/08/guidance-for-our-effort-to-block-less-secure-browser-and-apps.html
- [R18] ytmusicapi browser authentication: https://ytmusicapi.readthedocs.io/en/latest/setup/browser.html
- [R19] Discord: self-bot dilarang: https://support.discord.com/hc/articles/115002192352
- [R20] Library Discord IPC JVM (status): https://github.com/jagrosh/DiscordIPC, https://github.com/vyfor/KPresence, https://github.com/MinnDevelopment/Java-DiscordRPC
- [R21] Runner macOS 13 pensiun, `macos-15-intel`: https://github.blog/changelog/2025-09-19-github-actions-macos-13-runner-image-is-closing-down/, https://core.trac.wordpress.org/changeset/61241
- [R22] SignPath Foundation (code signing gratis OSS): https://signpath.org/, https://about.signpath.io/product/open-source
- [R23] macOS Sequoia menghapus bypass Control-klik Gatekeeper: https://developer.apple.com/news/?id=saqachfa
- [R24] SmartScreen & Smart App Control: https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation
- [R25] Compose native distributions (format, versi, notarization): https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
