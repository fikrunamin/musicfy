# Musicfy Desktop — D1: Restrukturisasi Modul Inti (KMP)

- **Tanggal:** 2026-11-22
- **Status:** Desain disetujui
- **Cakupan:** Sub-proyek D1 dari port desktop (lihat [Peta sub-proyek](#peta-sub-proyek-desktop))

## Ringkasan

Musicfy akan punya aplikasi desktop (Windows, macOS, Linux) di samping aplikasi Android, dengan
memakai **Kotlin Multiplatform (KMP)**. D1 adalah fondasinya: memindahkan logika yang tidak
bergantung pada UI (providers, model, settings, database, resolver stream, dan token desain) ke
modul inti yang bisa di-compile untuk Android dan desktop (JVM).

**Aplikasi Android harus berperilaku persis sama** setelah D1. Layar, ViewModel, `MusicService`,
dan `PlayerConnection` tidak dipindah di D1.

## Latar belakang & keputusan

| Topik | Keputusan |
|---|---|
| Arah produk | Port desktop dulu. Fitur Jam ditunda (spec-nya ada di branch `feature/jam`). |
| Port web penuh | Ditolak. Pemutaran di browser butuh proxy stream YouTube lewat server (URL terkunci IP, CORS, risiko ToS/DMCA) atau IFrame (berhenti saat layar mati). |
| Teknologi desktop | Kotlin Multiplatform + Compose Multiplatform, satu codebase dengan Android |
| Target OS | Windows, macOS, Linux |
| Gaya UI desktop | Layout desktop (sidebar, konten lebar, player bar bawah, panel lirik/antrean kanan) dengan token desain dan komponen yang sama dengan Android |
| Isi desktop MVP | Inti (beranda, cari, putar, antrean, lirik, library lokal) + login akun YouTube + download offline + equalizer + Discord Rich Presence |
| Strategi D1 | Modul inti KMP bertahap. Kode inti tanpa framework DI. Hilt tetap di Android, Koin di desktop |

## Peta sub-proyek desktop

| # | Sub-proyek | Hasil |
|---|---|---|
| **D1** | **Restrukturisasi modul inti (spec ini)** | Android tetap sama. Inti bisa di-compile dan dijalankan di JVM desktop |
| D2 | Mesin pemutar desktop | `JsRuntime`/`BotGuardRuntime` berbasis Chromium tertanam (KCEF), pemutar audio desktop (kandidat: libVLC/vlcj, diputuskan di spec D2), antrean, media keys |
| D3 | UI desktop MVP | Beranda, cari, library, pemutar, lirik, antrean, login YouTube, download, EQ, Discord RPC |
| D4 | Paket & distribusi | Installer MSI/DMG/DEB/AppImage lewat GitHub Actions, mekanisme update |
| D5+ | Paritas fitur | Audio Lab/AutoEq, Crossmix, recap, import, dan lainnya |

Setiap sub-proyek punya spec dan rencana implementasi sendiri.

## Revisi setelah pemetaan kode (2026-11-22)

Temuan saat menyusun rencana implementasi yang mengubah sebagian desain di bawah. **Bagian ini
menang kalau bertentangan dengan bagian lain.**

1. **Modul JVM murni, bukan KMP, untuk logika inti.** Kotlin tidak resmi mendukung API JDK
   (`java.time`, `java.io.File`, dll., yang dipakai luas di kode ini) di `commonMain` untuk
   kombinasi target JVM + Android. Karena desktop juga JVM, modul inti yang tidak butuh kode khusus
   platform dibuat sebagai modul `kotlin("jvm")` (pola yang sudah dipakai `providers:canvas` dan
   `modules:api`). Android mengonsumsinya langsung. Implementasi khusus platform (Context, WebView)
   tinggal di `app/` dan `desktopApp/` di balik interface. KMP hanya dipakai untuk bagian yang harus
   di-compile per platform (UI Compose, dan Room kalau prototipe D1b membuktikannya perlu).
   Konsekuensinya:
   - `core:platform` dan `core:settings` adalah modul JVM. `AndroidAppDirs`/`TimberLogger` ada di `app/`.
   - Task `checkModuleBoundaries` tidak diperlukan untuk modul JVM, karena modul JVM memang tidak
     bisa meng-compile `android.*` atau bergantung pada `:app`.
   - `build-logic/` ditunda sampai ada beberapa modul KMP (D1b/D3). Konfigurasi modul JVM hanya 6 baris.
2. **D1 dipecah menjadi D1a dan D1b.**
   - D1a: providers → JVM, `core:platform`, `core:settings`, CI.
   - D1b: `core:model` + `core:database`, `core:resolver`, `core:designsystem`, `desktopApp`.
   - Rencana D1b ditulis setelah D1a selesai.
3. **`core:model` ikut D1b.** `MediaMetadata.toSongEntity()` dan `PersistQueue` bergantung pada
   entity database, jadi model tidak bisa dipisah dari database lebih dulu.
4. **Schema DB belum pernah diekspor** (`exportSchema = false`, `app/schemas/` kosong). D1b harus
   lebih dulu mengaktifkan ekspor di kode lama untuk menghasilkan baseline `39.json` sebelum
   memindahkan apa pun.
5. **`PreferencesCache`/`rememberPreference` tetap di `app/` di D1a.** Keduanya memakai state
   Compose. Yang pindah ke `core:settings` adalah key dan enum di `PreferenceKeys.kt`, ditambah factory
   DataStore desktop. `DefaultPlayerCoverStyle` (memakai `Build.VERSION`) tetap di `app/`.
6. **`paxsenixlyrics` butuh versi aplikasi, bukan `cacheDir`.** `Paxsenix.init(context)` diganti
   `Paxsenix.init(appVersion: String)`, dan Timber diganti `java.util.logging`.
7. **Composable `ArtistVideo` di `providers:artistvideo` tidak dipakai di mana pun** (hanya
   `ArtistVideoCanvasProvider` yang dipakai `ArtistViewModel`), jadi dihapus, bukan dipindah.
8. `AndroidAppDirsTest` (Robolectric) diganti review: `AndroidAppDirs.from(context)` hanya satu
   baris pemetaan ke `filesDir`/`cacheDir`/`filesDir/download`. Logika per OS (`DesktopAppDirs`) tetap di-unit-test.
9. **Room: `@Database` dan DAO Android tidak dibagi dengan desktop (spike D1b, 2026-11-22).**
   Room 2.8.4 menolak fungsi DAO yang bukan `suspend` dan bukan `Flow` di setiap kompilasi
   non-Android: "Only suspend functions are allowed in DAOs declared in source sets targeting
   non-Android platforms." Spike membuktikannya di modul JVM murni (opsi (b), sumber yang
   di-compile dua kali) dan di target `jvm("desktop")` modul KMP (opsi (a)). Target Android KMP
   menerimanya. `DatabaseDao` punya 99 fungsi blocking yang dipanggil sinkron di seluruh `app/`.
   Mengubah semuanya menjadi `suspend` adalah perubahan perilaku, jadi dipilih opsi (c):
   - Entity, view, `Converters`, `MediaMetadata`, `PersistQueue`, dan `String.resize` pindah ke
     modul JVM `core:model` (FQN tetap; `@Immutable` dari `androidx.compose.runtime:runtime-annotation`).
   - `InternalDatabase`, `DatabaseDao`, `SpeedDialDao`, `MusicDatabase`, dan semua migrasi tetap
     di `app/` (Android saja). `@RawQuery` dengan `SupportSQLiteQuery` tetap di sana.
   - Desktop punya `DesktopMusicDatabase` sendiri di modul JVM `core:database` (KSP JVM, entity
     yang sama, versi 39, DAO `suspend`/`Flow`, `BundledSQLiteDriver`). Test memastikan skema
     ekspornya identik dengan `app/schemas/com.example.musicfy.db.InternalDatabase/39.json`.
   - `MigrationTestHelper` diganti test identity hash: kode Room Android dan migrasinya tidak
     diubah, dan hash yang sama berarti Room membuka database lama tanpa migrasi.
   - Plugin KMP + `com.android.kotlin.multiplatform.library` + Compose Multiplatform 1.10.1
     berjalan di build ini; KMP dipakai hanya untuk `core:designsystem`.
   - (Info) `java.time` di `commonMain` ter-compile untuk kombinasi android + jvm, tapi tetap tidak
     didukung resmi. (`compileCommonMainKotlinMetadata` di-SKIP oleh KGP karena semua target
     berbasis JVM; `compileAndroidMain` dan `compileKotlinDesktop` sama-sama meng-compile-nya.)

## Non-goals D1

- Memindahkan layar, ViewModel, `MusicService`, `PlayerConnection`, atau komponen UI yang memakai
  blur/`RenderEffect`/haptic.
- Mengganti Hilt di aplikasi Android.
- Mengubah skema database (versi tetap **39**, tanpa migrasi baru).
- Implementasi nyata `JsRuntime`/`BotGuardRuntime` untuk desktop (D2).
- UI desktop yang bisa dipakai sehari-hari (D3).
- Target iOS atau web.

---

## 1. Struktur modul

### 1.1 Fakta kode yang membentuk desain

- AGP **9.0.0** dan Kotlin **2.3.10**. Di AGP 9, modul KMP dengan target Android memakai plugin
  `com.android.kotlin.multiplatform.library` (bukan `com.android.library` + KMP).
- Compose androidx **1.10.2**. Compose Multiplatform dipilih di versi yang memetakan ke androidx
  Compose 1.10 di Android.
- Room **2.8.4** (sudah mendukung KMP). Database versi 39, 45 entity, sekitar 33 migrasi manual
  memakai `SupportSQLiteDatabase` (khusus Android). Entity hanya memakai anotasi Room dan
  `androidx.compose.runtime.Immutable`.
- 299 preference key di `constants/PreferenceKeys.kt`, dibungkus di `utils/DataStore.kt`.
- Resolver stream: `utils/YTPlayerUtils.kt` (fallback multi-client InnerTube + NewPipeExtractor
  untuk signature). `utils/sabr/EjsNTransformSolver.kt` dan `utils/potoken/PoTokenWebView.kt`
  menjalankan JavaScript di WebView Android. `utils/cipher/CipherDeobfuscator.kt` memegang
  `Context` global.
- 13 modul `providers/*` adalah Android library. 11 modul tanpa API Android sama sekali.
  `artistvideo` berisi composable ExoPlayer/`TextureView`, dan `paxsenixlyrics` memakai `Context`.
- Variant Android: dimensi ABI (`universal`, …) × flavor (`foss`, `gms`, `fdroid`) × build type.

### 1.2 Tata letak

```
musicfy/
├─ build-logic/               convention plugins (baru)
├─ app/                       Android (tetap; layar & MusicService tidak dipindah)
├─ desktopApp/                kerangka Compose Desktop + Koin (baru)
├─ core/
│  ├─ platform/               KMP · AppDirs, Logger
│  ├─ model/                  KMP · model domain tanpa Room/Android
│  ├─ settings/               KMP · DataStore Preferences KMP + seluruh preference key
│  │   ├─ androidMain         file datastore yang sama dengan sekarang
│  │   └─ desktopMain         file di folder data aplikasi per OS
│  ├─ database/               KMP · Room KMP (versi 39)
│  │   ├─ commonMain          entity, DAO, TypeConverter, @Database
│  │   ├─ androidMain         builder dengan Context + migrasi lama (SupportSQLite)
│  │   └─ desktopMain         builder dengan BundledSQLiteDriver
│  ├─ resolver/               KMP · YTPlayerUtils, cipher, n-transform, PoToken
│  │   ├─ commonMain          logika + interface JsRuntime, BotGuardRuntime
│  │   ├─ androidMain         implementasi WebView (kode yang sekarang, logika tidak diubah)
│  │   └─ desktopMain         stub (UnsupportedOperationException) sampai D2
│  └─ designsystem/           KMP · Compose MP: warna, tipografi, shape, komponen dasar
├─ providers/*                Android library → modul Kotlin/JVM murni
└─ modules/*                  tetap
```

Target semua modul KMP: `androidTarget` (lewat `com.android.kotlin.multiplatform.library`) dan
`jvm("desktop")`.

### 1.3 Aturan antar-modul

1. Arah dependensi hanya **`app` / `desktopApp` → `core/*` → `providers/*`**. `core/*` dan
   `providers/*` tidak boleh bergantung pada `:app`. Dijaga oleh task `checkModuleBoundaries` (3.3).
2. `commonMain` tidak boleh mengimpor `android.*`, `androidx.media3.*`, atau `dagger.*`.
3. Kode di `core/*` **tanpa anotasi DI**, cukup constructor biasa. `app/di/` menyediakan instance
   lewat `@Provides` Hilt, dan `desktopApp` lewat modul Koin.
4. Nama paket dipertahankan (`com.example.musicfy.db…`, `com.example.musicfy.utils…`, dll.)
   supaya diff di `app/` sebatas perubahan dependensi dan import.
5. Logging lewat `Logger` (`core:platform`). Implementasi Android meneruskan ke Timber, desktop ke stdout/SLF4J.

### 1.4 Antarmuka baru

```kotlin
// core:platform
interface AppDirs {
    val data: java.io.File      // Android: context.filesDir
    val cache: java.io.File     // Android: context.cacheDir
    val downloads: java.io.File // Android: lokasi download yang dipakai sekarang
}
interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

// core:resolver
interface JsRuntime {                       // n-transform & cipher
    suspend fun evaluate(script: String): String
    fun close()
}
interface BotGuardRuntime {                 // PoToken
    suspend fun generatePoToken(identifier: String): PoTokenResult
    fun close()
}
```

Lokasi `AppDirs` desktop: Windows `%APPDATA%\Musicfy`, macOS `~/Library/Application Support/Musicfy`,
Linux `$XDG_DATA_HOME/musicfy` (default `~/.local/share/musicfy`). Cache mengikuti konvensi cache
masing-masing OS.

---

## 2. Langkah migrasi

**Prinsip:** setiap langkah = satu commit/PR kecil. Setelah setiap langkah, ketiga flavor Android
bisa di-build, test lulus, dan smoke test (2.2) lulus. Langkah yang gagal di-revert sendiri.

### 2.1 Urutan

| # | Langkah | Detail | Risiko → mitigasi |
|---|---|---|---|
| 0 | Baseline & infrastruktur build | Catat baseline (build 3 flavor, unit test, salinan `app/schemas/.../39.json`). Buat `build-logic/` dengan `musicfy.jvm.library`, `musicfy.kmp.library`, `musicfy.kmp.compose`. Tambahkan ke catalog: plugin Kotlin Multiplatform, `com.android.kotlin.multiplatform.library`, `org.jetbrains.compose`, Koin, `androidx.sqlite:sqlite-bundled`, DataStore KMP. | Versi Compose MP bentrok dengan androidx Compose → verifikasi `./gradlew :app:dependencies` tetap me-resolve androidx Compose 1.10.x. |
| 1 | providers → JVM | 11 modul bersih ganti ke `musicfy.jvm.library`. `artistvideo`: logika pencarian video ke JVM, composable ExoPlayer pindah ke `app/`. `paxsenixlyrics`: `Context` diganti `cacheDir: File` lewat constructor. | Modul JVM memakai API Java yang tidak ada di Android 26 → perubahan di providers wajib lolos `:app:lintUniversalFossDebug` (`NewApi`). Desugaring tetap aktif di `app`. |
| 2 | `core:platform` | `AppDirs` + `Logger`. Implementasi Android di `app/` memakai path persis sama dengan sekarang. | Path berubah → `AndroidAppDirsTest` (Robolectric). |
| 3 | `core:model` | Model domain bersama tanpa Room/Android. | Model memakai `Parcelable`/`Uri` → tetap di `app/` atau dibuat varian tanpa API Android. |
| 4 | `core:settings` | 299 key + wrapper DataStore ke KMP. Nama file datastore Android tidak berubah. `PreferenceKeys` di `app/` sementara meneruskan (forwarding) ke modul baru, supaya pemakainya tidak disentuh sekaligus. | Pengaturan ter-reset → test round-trip + uji upgrade (3.2). |
| 5 | `core:database` | Entity, DAO, converter, `@Database(version = 39)` → `commonMain`. Builder Android + migrasi lama → `androidMain`. Builder desktop dengan `BundledSQLiteDriver` → `desktopMain`. `room { schemaDirectory(...) }` diarahkan ke `app/schemas` yang ada. | **Risiko terbesar.** `39.json` hasil generate harus identik dengan baseline. `MigrationTestHelper` v38→39 dan v24→39. Uji upgrade. |
| 6 | `core:resolver` | `YTPlayerUtils`, cipher, n-transform, PoToken → `commonMain` dengan `JsRuntime`/`BotGuardRuntime`. Implementasi WebView → `androidMain` tanpa perubahan logika. `CipherDeobfuscator.initialize(context)` diganti injeksi `JsRuntime`. Desktop: stub. | Pemutaran rusak halus → test dengan `JsRuntime` palsu + smoke test lagu biasa, age-restricted, dan yang butuh PoToken. |
| 7 | `core:designsystem` | Token tema + komponen dasar tanpa API Android. Komponen dengan blur/`RenderEffect`/haptic tetap di `app/`. | Tampilan berubah → bandingkan screenshot 5 layar utama sebelum dan sesudah. |
| 8 | `desktopApp` kerangka | Compose Desktop + Koin: membuka DB, membaca settings, memanggil search InnerTube, menampilkan hasilnya di daftar sederhana. | – |

**Hilt selama migrasi:** setiap kelas yang pindah ke `core/*` dilepas anotasi `@Inject`/`@Singleton`-nya,
lalu satu `@Provides` ditambahkan di `app/di/` yang memanggil constructor-nya. Pemakai di `app/` tidak berubah.

### 2.2 Smoke test Android (setelah setiap langkah)

- [ ] Aplikasi terbuka dan beranda termuat
- [ ] Cari → putar lagu; play/pause/seek/next berjalan; lirik muncul
- [ ] Library (like, playlist, riwayat) masih berisi data lama
- [ ] Pengaturan sama seperti sebelum update
- [ ] Download lagu → putar tanpa internet
- [ ] Instal di atas versi lama tanpa uninstall → tidak crash, data tetap ada

---

## 3. Verifikasi, testing & CI

### 3.1 Test baru

| Modul | Test | Tujuan |
|---|---|---|
| `providers/*` | Test yang sudah ada tetap jalan sebagai test JVM | Konversi tidak merusak apa pun |
| `core:platform` | `AndroidAppDirsTest` (Robolectric) | Path data tidak berubah |
| `core:settings` | Round-trip semua tipe key; nama file Android = nama lama | Pengaturan tidak ter-reset |
| `core:database` | (1) `SchemaUnchangedTest`: `39.json` hasil generate identik dengan baseline. (2) `MigrationTestHelper` (instrumentasi) v38→39 dan v24→39. (3) Test DAO di `desktopTest` (JVM + `BundledSQLiteDriver`, in-memory) untuk song, playlist, history, like | Skema & migrasi tetap; DAO jalan di JVM |
| `core:resolver` | `JsRuntime`/`BotGuardRuntime` palsu: urutan fallback client, n-transform untuk client yang tepat, PoToken hanya dibuat saat dibutuhkan, error sama seperti sebelumnya | Logika resolve tidak berubah |
| `desktopApp` | Koin `verify()`/`checkModules`; DB terbuka di folder sementara | Graph DI desktop lengkap |

Test jaringan opsional (`-Pnetwork-tests`) me-resolve 3 videoId nyata. Tidak dijalankan di CI PR.

### 3.2 Uji upgrade (wajib untuk langkah 4, 5, 6)

Instal versi rilis terakhir → isi data (like, playlist, download, pengaturan) → instal build baru
di atasnya tanpa uninstall → semua data dan pengaturan masih ada, dan pemutaran berjalan.

### 3.3 CI (`build_pr.yml` dan `build.yml`)

1. Unit test: `./gradlew test` (modul JVM/KMP) + `:app:testUniversalFossDebugUnitTest`.
2. Build matrix 3 flavor: `assembleUniversalFossDebug`, `assembleUniversalGmsDebug`, `assembleUniversalFdroidDebug`.
   Lint tetap di `foss`.
3. Desktop: `./gradlew :desktopApp:compileKotlinDesktop :core:database:desktopTest` di Ubuntu.
4. `./gradlew checkModuleBoundaries`: gagal kalau `core/*`/`providers/*` bergantung pada `:app`,
   atau `commonMain` mengimpor `android.*`/`androidx.media3.*`/`dagger.*`.
5. `MigrationTestHelper` (butuh emulator) dijalankan manual di langkah 5 dan dicatat di checklist PR.

### 3.4 Definisi selesai

- Langkah 0–8 selesai; masing-masing lolos CI dan smoke test.
- APK dari branch ini terpasang di atas rilis terakhir tanpa kehilangan data dan tanpa perubahan perilaku.
- `./gradlew :desktopApp:run` di macOS membuka DB, membaca settings, dan menampilkan hasil search InnerTube.
- `core/*` dan `providers/*` tanpa dependensi ke `app/`, dijaga CI.
