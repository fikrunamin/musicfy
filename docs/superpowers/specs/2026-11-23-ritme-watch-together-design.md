# Ritme — Nonton Bareng YouTube (#2) & Browse Video di Internet (#3)

- **Tanggal:** 2026-11-23
- **Status:** Draf desain, menunggu keputusan pengguna (lihat [Bagian 12](#12-keputusan-yang-butuh-persetujuan-pengguna))
- **Cakupan:** Sub-proyek #2 dan #3 dari peta di
  [spec #1](2026-11-22-ritme-listen-together-design.md#peta-sub-proyek)
- **Dasar:** spec #1 (terutama Revisi 7), protokol `:modules:ritme-protocol`, `ritme-server/`,
  `ritme-web/`, Ritme Android di `app/.../ritme/`, brief desain web §12–13
  (`docs/design/ritme-web-redesign-brief.md`) dan backlog item 3–4 (`docs/superpowers/backlog.md`;
  kedua file ini ada di `main`).

## Ringkasan

Room Ritme yang sama dipakai untuk **menonton video bareng**. Antrean sudah bisa berisi tiga jenis
entri (`MediaKind`): `YOUTUBE_MUSIC`, `YOUTUBE_VIDEO`, dan `URL`. Server dan web sudah memutar
ketiganya. Yang belum ada:

1. **Cara menambah video.** Belum ada klien yang bisa membuat entri `YOUTUBE_VIDEO` atau `URL`.
2. **Video di Android.** Saat ini Android memutar `YOUTUBE_VIDEO` sebagai audio saja, dan entri
   `URL` tampil "Belum didukung".
3. **Browser di dalam aplikasi** (khusus Android, desktop menyusul) yang menemukan video atau stream
   di halaman web, lalu menambahkannya sebagai entri `URL`.

Prinsip dari #1 tetap berlaku. Server hanya menyimpan state dan metadata, dan **tidak pernah
mengambil atau meneruskan stream**. Setiap perangkat memutar stream-nya sendiri, dan sinkronisasi
memakai planner dan drift corrector yang sama.

**Jawaban riset utama:** aplikasi sudah bisa memutar stream **video** YouTube, tapi hanya sebagai
*latar cover* yang bisu, terpotong ke tengah, dan disinkronkan longgar (selisih sampai 750 ms) ke
pemutar audio. Belum ada panggung video yang utuh (tidak terpotong), sinkronisasi A/V yang ketat,
layar penuh, atau pemutaran URL/HLS/DASH di pemutar utama. Rinciannya di
[Bagian 1](#1-kondisi-sekarang-hasil-riset).

## Peta sub-proyek (diperbarui)

| # | Isi | Platform |
|---|---|---|
| 1 | Fondasi room + Dengar Bareng | Android + web (hampir selesai di Android, sudah live di web) |
| **2a** | `GET /api/video`, pengerasan validasi URL di server, setelan `guestsCanAdd`, sheet "Tambah video" di web | Server + web |
| **2b** | Panggung video tersinkron + layar penuh, pemutaran entri `URL`, "Tambah video" (link, cari, share sheet) | Android |
| **3** | Browser in-app + deteksi video/stream + konfirmasi → entri `URL`. Ekstensi protokol opsional `streamType`/`referrer` | Android (desktop: seam saja) |

## Bukan bagian dari spec ini (non-goals)

- Proxy, transcode, atau relay stream di server, untuk alasan apa pun.
- Membuka DRM (Widevine/FairPlay/PlayReady). Konten berproteksi **ditolak**, tidak dicoba.
- Picture-in-Picture. Butuh perubahan `MainActivity` (`supportsPictureInPicture`, saat ini dikunci
  portrait). Bisa jadi tindak lanjut.
- Pencarian video di web (`/api/search` tetap khusus lagu). Web menambah video lewat link saja.
- Ritme di desktop. Spec ini hanya mencatat seam-nya ([Bagian 5](#5-seam-desktop)).
- Chat, reaksi, atau subtitle bersama.
- Mengubah perilaku pemutar **di luar room**. Semua jalur baru hanya aktif saat ada room.

---

## 1. Kondisi sekarang (hasil riset)

### 1.1 Video YouTube di aplikasi

| Bagian | Yang ada | Lokasi |
|---|---|---|
| Resolver video | `YTPlayerUtils.resolveVideoStreamUrl(videoId)` mencoba 5 client (`MAIN_CLIENT`, `WEB_REMIX`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`, `ANDROID_VR_1_61_48`, `WEB`), lalu memilih format adaptif **video-only** dengan tinggi terdekat ke 720p (codec apa saja). Kalau tidak ada, dipakai format muxed (`formats`). | `core/resolver/.../utils/YTPlayerUtils.kt:553-601` |
| Pemutar video | `YouTubeVideoBackground`: **ExoPlayer kedua** di composable, `volume = 0`, `REPEAT_MODE_ALL`, `TextureView`, crop ke tengah, dan zoom melewati black bar (`VideoLetterbox`). Setiap 500 ms ia seek ke posisi pemutar audio kalau selisihnya > 750 ms. Bisa diselaraskan ke lirik (`YouTubeLyricsVideoSync`). | `ui/player/YouTubeVideoBackground.kt` |
| Kapan dipakai | `MorphingCover` memakainya kalau (a) lagu yang diputar memang video (`MediaMetadata.isVideoSong`, kecuali `DisableVideoAutoplayKey`), dengan kunci cache `self:<id>`; (b) pref `PlayVideoBackgroundKey` aktif, lalu dicari video musik resmi berdasarkan judul + artis (`findOfficialMusicVideo`); atau (c) aksi menu `SEE_VIDEO`. Hanya di keluarga flagship, dan tidak di gaya cover piringan. Mini player melebar ke 16:9. | `ui/player/MorphingCover.kt:408-455, 1023` |
| Cache URL | `YouTubeVideoUrlCache` (LRU 64) | `ui/player/YouTubeVideoLookup.kt` |
| Audio | Selalu dari ExoPlayer `MusicService` (format audio saja, `selectAudioFormatCandidates`). Pemutar ini tidak pernah merender video. | `playback/MusicService.kt` |
| Media3 | `exoplayer`, `hls`, `ui`, `okhttp`, `session` (1.7.1). **Tidak ada `exoplayer-dash`.** | `gradle/libs.versions.toml` |
| Orientasi | `MainActivity` `screenOrientation="portrait"` tanpa `configChanges`. Sudah ada `RotatingFrame` yang memutar konten seperempat putaran tanpa mengubah orientasi activity (dipakai gaya Poster/Timeline/Lyrics). | `AndroidManifest.xml:73`, `ui/player/styles/StyleKit.kt:489` |
| Thumbnail 16:9 | `SubpageVideoCell` (16:9, sudut 14 dp, `PlayingIndicatorBox`) | `ui/screens/artist/ArtistSubpageKit.kt:246` |
| Pencarian video | Tab "Video" di pencarian memakai `FILTER_VIDEO` YouTube Music (`WEB_REMIX`). Belum ada pencarian YouTube umum (client `WEB`). | `ui/screens/search/OnlineSearchResult.kt:111` |
| Share | Filter `SEND text/plain`. Link YouTube dibuka lewat `YouTube.queue(...)` lalu diputar lokal (di dalam room, ini dicegat dan menjadi `Add` `YOUTUBE_MUSIC`). | `MainActivity.kt:1373-1450` |
| WebView | Pola yang sudah ada: `shouldInterceptRequest`, `addDocumentStartJavaScript`, `JavascriptInterface`, `hideAppIdentity()` (header `X-Requested-With`), UA Chrome. Dependensi `androidx.webkit` 1.12.1. | `ui/screens/settings/importsync/CaptureWebView.kt` |

### 1.2 Ritme Android hari ini

- `RitmePlaybackBridge` memuat **satu entri saja** (entri aktif room) ke ExoPlayer `MusicService`
  lewat `RitmePlayerHost.loadRitmeItem(itemFor(entry), startMs)`. Isinya
  `RitmeEntries.toMediaMetadata(entry).toMediaItem()`, yaitu `mediaId = videoId`, lalu di-resolve
  seperti lagu biasa.
- `RitmeEntries.isPlayable`: hanya `YOUTUBE_MUSIC` dan `YOUTUBE_VIDEO` dengan videoId valid.
  `YOUTUBE_VIDEO` diputar audionya saja. `URL`/`UNKNOWN` → planner `Halt`, dan baris antrean tampil
  "Belum didukung".
- `toMediaMetadata` tidak mengisi `musicVideoType`. Akibatnya entri `YOUTUBE_VIDEO` di room **tidak**
  memicu jalur `self:` di `MorphingCover`. Lebih buruk lagi, kalau pref latar video aktif, cover
  mencari "video musik resmi" berdasarkan judul, sehingga untuk video non-musik yang tampil bisa
  video lain. **Bug kecil, diperbaiki di #2b.**
- Planner memuat entri live dengan `Load(entry, 0)`. Untuk stream live HLS/DASH, posisi 0 adalah
  awal jendela DVR, bukan live edge. **Diperbaiki di #2b** (lihat 4.4).

### 1.3 Server dan web

- `RoomLogic.isValid`: entri `URL` wajib `videoId = ""` dan `url` yang diawali `http://`/`https://`,
  maksimal 2048 karakter. Thumbnail entri `URL` boleh https dari host mana saja (maks. 512).
  **Belum ada** penolakan userinfo (`user:pass@`), host privat/loopback, atau karakter kontrol.
- `GET /api/search`: InnerTube `WEB_REMIX`, cache LRU 10 menit, single-flight, `IpRateLimiter`
  30/menit per IP, budget upstream global 300/menit, query tidak pernah di-log.
- Web: `playable.ts` memetakan `YOUTUBE_VIDEO` → IFrame besar, dan `URL` → `<video>` (hls.js untuk
  `.m3u8`, native HLS di Safari). `http://` di halaman https → `insecure`. CSP mengizinkan
  `media-src`/`connect-src` `https: http:`, `img-src https:`, `frame-src` YouTube.

---

## 2. Keputusan desain

| # | Topik | Keputusan | Alternatif yang ditimbang | Alasan |
|---|---|---|---|---|
| D1 | Video YouTube di Android | **Dua pemutar.** Audio tetap dari ExoPlayer `MusicService` (resolver, cache, notifikasi tidak berubah). Video diputar oleh **pemutar pengikut** bisu (video-only) di UI, yang mengikuti posisi pemutar audio lokal secara ketat. | (a) Satu ExoPlayer dengan `MergingMediaSource` (video + audio) di `MusicService`. (b) IFrame YouTube di WebView. | (a) A/V-nya sempurna, tapi harus membongkar `DynamicResolvingMediaSource`/`routeStream`/cache, yang dipakai semua pemutaran di luar room. Audio di-resolve secara malas, dan URL video-only bisa datang dari client lain. Risiko regresinya besar. (b) Tidak bisa diatur kecepatannya, mati saat layar mati, iklan tidak bisa diprediksi, dan stack-nya ganda. Dua pemutar sudah terbukti (`YouTubeVideoBackground`), dan saat layar mati video berhenti sementara audio tetap jalan. |
| D2 | Entri `URL` di Android | **Satu pemutar:** ExoPlayer `MusicService` memutar URL-nya langsung (audio + video dalam satu stream), dan UI memasang `TextureView` ke pemutar itu. | Pemutar pengikut seperti D1 | Stream URL sudah muxed. Dua pemutar berarti mengunduh dua kali. A/V dari satu jam pemutar. |
| D3 | Sinkronisasi | Planner dan `DriftCorrector` **tidak berubah** dan tetap menggerakkan pemutar utama (audio YouTube, atau URL). Pengikut video hanya mengejar pemutar lokal (`VideoFollower`, fungsi murni baru). | Pengikut juga mengejar posisi room | Satu sumber kebenaran per perangkat. Pengikut tidak perlu tahu room. |
| D4 | Layar penuh | **`RotatingFrame` yang sudah ada:** panggung video diputar seperempat putaran, activity tetap portrait, system bar disembunyikan (immersive). | `requestedOrientation = SENSOR_LANDSCAPE` | Tanpa rekreasi activity, tanpa mengubah manifest. Pola dan animasinya sudah ada di aplikasi. Kekurangan: volume/notifikasi sistem tetap tegak. |
| D5 | Metadata video untuk web | **`GET /api/video?id=`** di server, dengan InnerTube `music/get_queue` (`WEB_REMIX`, client yang sama dengan search). Gaya cache, single-flight, rate limit, dan privasinya sama dengan `/api/search`. | oEmbed YouTube dari browser; endpoint `player` dari server | oEmbed = pihak ketiga baru dari browser dan tanpa durasi. `player` dari IP datacenter sering kena "confirm you're not a bot". `get_queue` sudah di-parse Android (`NextPage.fromPlaylistPanelVideoRenderer`) dan memberi judul, channel, `lengthText` (tidak ada → live), dan thumbnail. |
| D6 | Server dan URL | Server **tidak pernah** mengambil URL entri (tidak ada HEAD/probe), dan hanya memvalidasi bentuknya (diperketat, lihat 3.2). | Server memeriksa playability URL | SSRF dan privasi. Server akan mengunjungi host pilihan pengguna dari IP-nya sendiri. |
| D7 | Protokol #2 | **Tanpa perubahan protokol**, kecuali `RoomSettings.guestsCanAdd` (backlog item 4) kalau disetujui. | – | `kind`, `url`, dan `durationMs = 0` sudah ada sejak Revisi 7. |
| D8 | Protokol #3 | Field opsional di `NewEntry`/`QueueEntry`: `streamType` (`HLS`/`DASH`/`FILE`/`UNKNOWN`, enum lenient) dan `referrer` (hanya origin). Default `null`, tanpa menaikkan `PROTOCOL_VERSION`. | Setiap klien mem-probe ulang URL; mengirim header lengkap | Tanpa `streamType`, URL HLS tanpa ekstensi (`/manifest?token=`) gagal di ExoPlayer dan `playable.ts`. Probe ulang menambah 0,2–0,5 detik di dalam anchor 1,5 detik. `referrer` (origin saja) menaikkan tingkat keberhasilan di Android tanpa membocorkan path atau cookie. |
| D9 | Deteksi di browser | Tiga lapis: (1) `shouldInterceptRequest` (+ client service worker) untuk mengendus URL, (2) JS document-start di semua frame untuk `<video>`/`og:video`/JSON-LD, (3) probe dari aplikasi **tanpa cookie** saat daftar dibuka (content-type, magic bytes, parse playlist). | Mencegat lalu menjalankan request sendiri (`WebResourceResponse`) | Mencegat merusak halaman (body POST, CORS, streaming). Probe tanpa cookie justru meniru keadaan anggota lain. |
| D10 | Jembatan JS | `WebViewCompat.addWebMessageListener` dengan origin rules. | `addJavascriptInterface` (dipakai `CaptureWebView`) | `JavascriptInterface` terpasang di semua frame, termasuk iframe iklan lintas origin. Web message listener memberi `sourceOrigin` dan `isMainFrame`. |
| D11 | Library baru | Hanya **`androidx.media3:media3-exoplayer-dash` 1.7.1** (keluarga dan versi yang sama dengan media3 yang ada), untuk entri DASH di Android. Web tidak menambah library (DASH tidak didukung di web). | dash.js/shaka di web | Hanya modul resmi pasangan ExoPlayer. Web tetap kecil. `.mpd` di web → "tidak bisa diputar di web". |
| D12 | Modul bersama | Aturan URL yang menjadi kontrak (dipakai server dan klien) masuk `:modules:ritme-protocol` (`MediaUrlRules`). Heuristik deteksi dan parser playlist masuk modul JVM murni baru `:modules:ritme-media`, supaya desktop bisa memakai ulang. Kasus uji dibagi lewat fixture JSON ke Vitest (seperti golden files #1). | Semua di `app/` | Server, Android, web, dan desktop memberi putusan yang sama. |

---

## 3. Server (#2a, ekstensi kecil di #3)

### 3.1 `GET /api/video?id=<videoId>`

| Aspek | Aturan |
|---|---|
| Validasi | `id` cocok `^[A-Za-z0-9_-]{11}$`. Kalau tidak → 400. |
| Rate limit | `IpRateLimiter` **terpisah** dengan angka yang sama (30/menit per IP), supaya menambah video tidak memakan jatah search. Budget upstream **bersama** search (300/menit global) → 503 kalau habis. |
| Upstream | Satu `POST https://music.youtube.com/youtubei/v1/music/get_queue` (`WEB_REMIX`, header sama dengan `InnerTubeSearchBackend`), body `{"videoIds":[id]}`. Hanya metadata, tanpa URL stream. |
| Parse | `queueDatas[0].content.playlistPanelVideoRenderer` (bisa juga dibungkus `playlistPanelVideoWrapperRenderer.primaryRenderer`) → `title.runs[0]`, `longBylineText.runs` (run pertama = channel/artis), `lengthText.runs[0]` (`m:ss`/`h:mm:ss` → ms; tidak ada → `durationMs = 0`, `live = true`), `thumbnail.thumbnails.last`. |
| Respons 200 | `{videoId, title, channel, durationMs, thumbnailUrl, live}`. `thumbnailUrl` hanya kalau lolos aturan host YouTube (`i.ytimg.com`, `*.ytimg.com`, `*.googleusercontent.com`, `*.ggpht.com`). Kalau tidak, `null`. |
| Respons lain | 404 kalau tidak ada renderer (privat, dihapus, tidak tersedia), 400, 429, 503, 502 (backend gagal). |
| Cache | LRU 1000 entri. TTL 6 jam untuk video biasa, 5 menit untuk live (status live bisa berubah). Single-flight per id. |
| Privasi | id tidak pernah di-log. Log hanya `video backend failed: <ExceptionClass>`, sama seperti search. |
| Refactor | `SearchService` dipecah menjadi `SingleFlightCache<K, V>` (cache + in-flight + budget upstream) yang dipakai `SearchService` dan `VideoInfoService`. Perilaku search tidak berubah (test yang ada harus tetap hijau). |

### 3.2 Pengerasan validasi entri `URL` (#2a, karena web mulai menambah URL)

`MediaUrlRules.isAcceptableMediaUrl(url)` di `:modules:ritme-protocol`, dipakai `RoomLogic.isValid`:

1. Parse dengan parser yang ditulis tangan (bukan `java.net.URI`), supaya twin TypeScript
   (`ritme-web/src/net/media-url-rules.ts`) memberi hasil yang persis sama. Skema `http`/`https`.
   Host wajib ada. Panjang ≤ 2048.
2. **Tolak userinfo** (`https://user:pass@host/...`). Kredensial tidak boleh ikut tersiar ke room.
3. Tolak spasi (termasuk spasi Unicode dan U+FEFF) dan karakter kontrol (`\p{Cc}`), backslash, dan
   fragment `#...` (dibuang oleh klien sebelum mengirim. Server menolak kalau masih ada).
4. **Tolak host lokal:** `localhost`, `*.localhost`, `*.local`, `*.internal`, literal IPv4/IPv6
   loopback, privat (10/8, 172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, fe80::/10),
   CGNAT 100.64/10, `0.0.0.0`, multicast. Tujuannya: anggota tidak bisa menyuruh perangkat anggota lain
   mengakses router atau LAN-nya. Server **tidak** me-resolve DNS (lihat D6). Penjagaan setelah
   resolusi DNS ada di Android (4.5). Implementasi juga menolak `*.lan`, `*.home.arpa`,
   `*.localdomain` (Linux me-resolve `localhost.localdomain` ke loopback), nama satu label,
   192.0.0/24, 198.18/15, 240/4, `fec0::/10`, IPv6 yang menyematkan IPv4 lokal (`::ffff:`,
   `::ffff:0:`, NAT64, 6to4), `64:ff9b:1::/48`, dan host numerik yang bukan dotted quad. Daftar
   lengkap: README server dan fixture `fixtures/rules/media-urls.json`.
5. Dengan `streamType`/`referrer` (#3): `streamType` lenient (nilai tak dikenal → `UNKNOWN`, tetap
   diterima). `referrer` null, atau origin murni `https?://host[:port]/` tanpa path/query/userinfo,
   ≤ 200 karakter, dengan aturan host yang sama.

Klien Android dan web menerapkan aturan yang sama sebelum mengirim, supaya satu entri buruk tidak
membuat seluruh `Add` gagal dengan `INVALID` (pola yang sama dengan `RitmeEntries`).

### 3.3 `guestsCanAdd` (kalau disetujui, #2a)

`RoomSettings(guestsCanControl = true, guestsCanAdd = true)`. Kalau `false`, `Add` dari non-host
dibalas `FORBIDDEN`. Field baru dengan default `true` → klien lama tetap jalan (`ignoreUnknownKeys`).
Golden fixtures diregenerasi, dan parser TS ditambah. Lihat risiko R9 (host lama me-reset setelan).

---

## 4. Android (#2b dan #3)

### 4.1 Unit baru dan yang berubah

| Unit | Tugas |
|---|---|
| `ritme/entries/RitmeEntries` (ubah) | `isPlayable` menerima `URL` (yang lolos `MediaUrlRules`). `toMediaMetadata` mengisi `musicVideoType` untuk `YOUTUBE_VIDEO` (memperbaiki bug 1.2). `toVideoEntry(...)` dan `toUrlEntry(...)` membuat `NewEntry` yang lolos validasi server. |
| `ritme/video/YouTubeLinks` (baru, di `:modules:ritme-media`) | Parse link: `youtube.com/watch?v=`, `youtu.be/ID`, `/shorts/ID`, `/live/ID`, `/embed/ID`, `m.`/`www.`/`music.`, `vnd.youtube:`. `t=` dan `list=` diabaikan. Fixture dibagi dengan web. |
| `ritme/video/VideoFollower` (baru, murni) | `(audioMs, videoMs, audioSpeed, videoSpeed, nowMs, lastSeekAt) → FollowAction(Ignore / SetSpeed / Seek)`. Lihat 4.3. |
| `ritme/video/RitmeVideoStage` (baru, Compose) | Panggung video. Untuk `YOUTUBE_VIDEO` ia memegang pemutar pengikut. Untuk `URL` ia memasang `TextureView` ke pemutar `MusicService`. Dipakai `MorphingCover` dan layar penuh. |
| `ritme/playback/RitmePlaybackBridge` (ubah) | `itemFor(entry)` per jenis (4.4). `PlayerOp.Load.startMs: Long?` (`null` = posisi default/live edge). `setVideoTrackEnabled(Boolean)` lewat host. |
| `RitmePlayerHost` + `MusicService` (ubah, hanya jalur room) | `loadRitmeItem` menerima item `URL`. Rute `RITME_URL:` di `deviceStream`/fetcher tanpa resolusi YouTube dan **tanpa cache lagu**. `setRitmeVideoEnabled(enabled)` = `trackSelectionParameters.setTrackTypeDisabled(VIDEO, !enabled)`. Saat `exitRitmeMode` dikembalikan ke default. |
| `ui/screens/ritme/RitmeAddVideoSheet` (baru) | Sheet "Tambah video" (4.6). |
| `ui/screens/ritme/browser/*` (baru, #3) | Layar browser, daftar temuan, sheet konfirmasi (4.7). |
| `:modules:ritme-media` (baru, JVM murni) | `YouTubeLinks`, `MediaUrlClassifier`, `PlaylistSniffer` (m3u8/MPD minimal), `SensitiveParams`, `ExpiryHints`. |

### 4.2 Panggung video (UI)

Aturan wajib: **komponen dan tema aplikasi yang ada, persis sama.** Tidak ada gaya baru.

- **Pemutar flagship (Default/Turntable).** Saat entri aktif room adalah `YOUTUBE_VIDEO` atau `URL`,
  slot cover `MorphingCover` merender `RitmeVideoStage`: lebar penuh dengan tepi seperti gaya cover
  `SQUARED`, rasio dari `VideoSize` video, dibatasi antara 9:16 dan 21:9, dan tingginya maksimal
  ~60% layar. Video tampil **fit** (letterbox), tidak dicrop seperti latar cover, supaya teks atau
  subtitle di video tidak terpotong. Bentuk sudut dan latar blur backdrop sama seperti cover. Gaya
  cover piringan diabaikan selama entri video aktif (hanya di room). `MorphingSongInfo`, progres,
  dan kontrol tidak berubah.
- **Tidak ada apa pun di atas video**, sama seperti aturan web. Tombol **Layar penuh**
  (`R.drawable.fullscreen`, sudah ada) diletakkan di baris aksi pemutar, di samping pill Ritme.
- **Mini player:** perilaku yang ada dipakai ulang, yaitu thumbnail melebar ke 16:9
  (`targetMiniArtWidth`).
- **Gaya lain (Poster/Timeline/Lyrics):** tidak ada panggung. Aksi menu `SEE_VIDEO` yang sudah ada
  ("Lihat video") membuka layar penuh. Lihat keputusan U9.
- **Layar penuh:** route overlay di atas pemutar. `RotatingFrame(rotated = true)` dengan latar
  hitam, system bar disembunyikan (`WindowInsetsControllerCompat`, perilaku *transient by swipe*).
  Kontrol room tampil sebagai bar tipis di sisi atau bawah video (bukan di atasnya), memakai
  `PlayerControls` yang sama dalam ukuran kecil. Ketuk di luar video → bar disembunyikan atau
  ditampilkan. Pill "Ritme · N" + "Menyambung ulang…" tetap tampil di tepi. Tombol keluar memakai
  ikon `fullscreen_exit` baru dari set Material Symbols yang sama dengan ikon lain (bobot dan gaya
  sama). Back = keluar layar penuh.
- **Layar tetap menyala** (`keepScreenOn` di view panggung) selama video diputar dan panggung terlihat.
- **Baris antrean Ritme:** untuk entri video, thumbnail 16:9 pada tinggi baris yang sama
  (`RowArtShape`, latar `ink.copy(alpha = 0.10f)`), mengikuti pola `SubpageVideoCell`. Subjudul:
  channel atau domain · "oleh …". Lencana kecil "Video"/"LIVE" memakai komponen badge yang sudah
  dipakai di baris lagu (misalnya penanda explicit/kualitas). Kalau tidak ada yang cocok, teks
  sekunder `RitmeSecondaryText`. "Belum didukung" hanya tersisa untuk `UNKNOWN`.
- **Entri `URL` tanpa thumbnail:** kotak gelap 16:9 dengan ikon `web_link` (sudah ada) dan domain,
  sesuai placeholder di brief §13.

### 4.3 Pemutar pengikut (YouTube video)

```
MusicService ExoPlayer (audio, diatur planner + DriftCorrector)
        │ currentPosition, playbackParameters.speed, isPlaying, state
        ▼
RitmeVideoStage: ExoPlayer pengikut (video-only, volume 0, REPEAT_MODE_OFF)
        │ setiap 100 ms: VideoFollower.evaluate(...)
        ▼
SetSpeed / Seek / Ignore
```

- URL stream dari `YTPlayerUtils.resolveVideoStreamUrl` dengan kunci `self:<videoId>`
  (`YouTubeVideoUrlCache`). Pemilihan format diperbaiki **hanya untuk pemanggil baru**
  (parameter opsional, tanpa mengubah perilaku latar cover): codec yang punya decoder hardware
  (`MediaCodecList`: avc1 → vp9 → av01), dan tinggi maksimal 720p di Wi-Fi atau 480p di jaringan
  berbayar (U10).
- `VideoFollower`:

  | `|video − audio|` | Tindakan |
  |---|---|
  | < 40 ms | Kecepatan pengikut = kecepatan audio (mengikuti koreksi 0,97/1,03 dari `DriftCorrector`) |
  | 40–400 ms | Kecepatan audio × 0,9 (kalau video di depan) atau × 1,1 (kalau tertinggal), sampai < 20 ms (histeresis seperti `DriftCorrector`) |
  | > 400 ms | `seekTo(audio + perkiraan latensi seek)` dengan `SeekParameters.EXACT`, maksimal 1× per 2 detik |

  Target: selisih bibir dan suara < 80 ms (batas ITU-R BT.1359 kira-kira +45/−125 ms).
- Play/pause/ended pengikut mengikuti `isPlaying` dan state pemutar audio. Buffering audio →
  pengikut dijeda. Buffering video → audio **tidak** ditunggu (room tidak menunggu siapa pun),
  dan frame terakhir tetap tampil.
- Pengikut hanya hidup selama panggung terlihat. Kalau panggung keluar dari layar, layar mati, atau
  aplikasi ke background, pengikut di-release dan audio tetap jalan (nonton bareng turun menjadi
  dengar bareng). Saat panggung kembali, pengikut di-prepare ulang di posisi audio, lalu muncul
  dengan fade (pola `isVideoReady` yang ada).
- Gagal resolve video (semua client gagal, 403) → panggung menampilkan thumbnail dengan keterangan
  kecil "Video tidak tersedia, audio tetap diputar". Room tidak terganggu.

### 4.4 Memuat entri per jenis (bridge → `MusicService`)

| Jenis | `MediaItem` | Catatan |
|---|---|---|
| `YOUTUBE_MUSIC` | Tidak berubah | – |
| `YOUTUBE_VIDEO` | Sama seperti lagu (audio via resolver), dengan `musicVideoType` terisi | Video oleh pengikut (4.3). |
| `URL` | `mediaId = "RITME_URL:<entryId>"`, `uri = url`, `mimeType` dari `streamType` atau ekstensi (`APPLICATION_M3U8`/`APPLICATION_MPD`/null), `RequestMetadata`/extras berisi `referrer`. Untuk live: `LiveConfiguration(targetOffsetMs = 10_000, minPlaybackSpeed = 0.97, maxPlaybackSpeed = 1.03)`. | `deviceStream` mengenali prefiks dan mengembalikan `CustomStreamResult(streamUrl, isHls/isDash, source = <domain>)` **tanpa** `routeStream`/YouTube. Data source: lewati `SimpleCache` lagu (video besar tidak boleh mengusir lagu dari cache). Header: UA Chrome (`chromeUserAgent`), `Referer`/`Origin` dari `referrer` kalau ada. **Tanpa cookie, selamanya.** |

- `PlayerOp.Load(entry, startMs = null)` untuk entri live → `setMediaItem(item)` tanpa posisi, sehingga
  pemutar mulai di live edge. Juga berlaku untuk live YouTube.
- **Live tersinkron:** setiap perangkat mengejar live edge dengan target offset yang sama (10 detik).
  Selisih antarperangkat biasanya 1–3 detik. Room `Play` setelah `Pause` pada entri live →
  `seekToDefaultPosition()` + play, jadi semua kembali ke live edge bersamaan. Penyelarasan memakai
  `EXT-X-PROGRAM-DATE-TIME` dicatat sebagai tindak lanjut.
- **Video track:** `RitmeVideoStage` memanggil `setRitmeVideoEnabled(true)` saat terlihat, dan `false`
  saat hilang. Saat layar mati, hanya audio entri `URL` yang didekode (hemat baterai dan data).
- Planner, `DriftCorrector`, `TrackEnded`, anchor 1,5 detik: **tidak berubah.** Perangkat yang lambat
  memuat HLS menyusul lewat seek > 1 detik.

### 4.5 Keamanan pemutaran `URL` di Android

- Sebelum memuat: `MediaUrlRules` (sama dengan server). Lalu host di-resolve dan alamat
  privat/loopback/link-local ditolak (perlindungan DNS rebinding, best effort).
- Hanya `http`/`https`. Tidak ada `file:`, `content:`, `data:`, atau `intent:` dari room.
- Cookie WebView dan cookie YouTube login (`CookieManager` default) **tidak pernah** dipasang ke
  request pemutaran atau probe.

### 4.6 Menambah video (#2b)

**Titik masuk:**

1. **Room sheet:** baris baru "Tambah video" di `Material3MenuGroup` aksi, tepat di bawah
   "Tambah lagu" (ikon `link` atau Material Symbols `smart_display` dengan gaya yang sama). Ikut
   tersembunyi kalau `guestsCanAdd` mati.
2. **Share sheet:** `activity-alias` baru dengan label **"Tambah ke Ritme"** (target
   `MainActivity`, filter `SEND text/plain`, ikon Ritme). Target share "Musicfy" yang ada tidak
   berubah. Di room → sheet "Tambah video" dengan link terisi dan pratinjau termuat. Di luar room →
   `RitmeEntrySheet` "Mulai Ritme dengan video ini" (`Kind.Entry` mendapat
   `startWithEntries: List<NewEntry>`). Kalau teksnya bukan link YouTube atau media langsung →
   tawarkan "Buka di browser Ritme" (#3).
3. **Link Ritme di teks share** tetap ditangani `openJoinLink` lebih dulu (perilaku #1).

**`RitmeAddVideoSheet`** (PopupSheet lewat `LocalBottomSheetPageState`, tata letak seperti
`RitmeEntrySheet`: `SheetLabel`, `SheetTextField`, `SheetPillButton`):

```
┌──────────────────────────────────┐
│  Tambah video                      │
│  [ Tempel link atau cari video  ] │  ← satu SheetTextField
├──────────────────────────────────┤
│  (link) Pratinjau:                 │
│   ▭▭▭ 16:9  Judul                  │
│             Channel · 12:34 / LIVE │
│  (URL)  Judul (opsional) [______]  │
│  (cari) daftar hasil (baris 16:9)  │
├──────────────────────────────────┤
│  [ Tambah ]   [ Putar berikutnya ] │
└──────────────────────────────────┘
```

- Teks yang lolos parse sebagai URL:
  - **Link YouTube** → `YouTube.queue(listOf(id))` (Android memakai InnerTube-nya sendiri, bukan
    `/api/video`) → pratinjau → `NewEntry(kind = YOUTUBE_VIDEO, durationMs = lengthText atau 0)`.
    Link `music.youtube.com` → `YOUTUBE_MUSIC`.
  - **Link media langsung** (`.mp4/.webm/.m4v/.mov/.m3u8/.mpd`) → probe ringan (seperti 4.7 langkah
    3) → pratinjau dengan placeholder dan domain, kolom "Judul (opsional)" (default: nama file) →
    `NewEntry(kind = URL, videoId = "", artist = domain, thumbnailUrl = null)`.
  - **Link halaman lain** → "Link ini halaman web, bukan file video." + tombol "Buka di browser
    Ritme" (#3).
- Teks biasa → pencarian video (U3). Baris hasil memakai komponen baris yang sama dengan pencarian,
  dengan thumbnail 16:9.
- **Tidak pernah menambah otomatis.** Selalu lewat tombol "Tambah"/"Putar berikutnya" →
  `session.add(listOf(entry), playNext)`, toast `RitmeToast.Added` yang sudah ada.
- Ketukan lagu di mana pun selama di room tetap menambah `YOUTUBE_MUSIC` (perilaku #1, U4).

### 4.7 Browser in-app (#3)

**Titik masuk:** baris "Cari video di web" (ikon `globe`, sudah ada) di room sheet, dan tombol
"Buka di browser Ritme" dari 4.6. Layar penuh di `NavHost` (route `ritme/browser?url=`), dengan header
bergaya `WebLoginHeader` dari `CaptureWebView` (kembali, kolom alamat `SheetTextField`, menu ⋯).

```
┌──────────────────────────────────┐
│ ←  [ example.com/film/123     ] ⋯ │
├──────────────────────────────────┤
│                                  │
│            WebView               │
│                                  │
│             ╭──────────────────╮ │
│             │ ▶ 2 video ditemukan│ │  ← pill mengambang (gaya pill Ritme)
│             ╰──────────────────╯ │
└──────────────────────────────────┘
```

**WebView:**
- Profil terpisah `"ritme-browser"` (`androidx.webkit` Profile, `WebViewFeature.MULTI_PROFILE`), jadi
  cookie login YouTube tidak terbawa. Kalau fitur itu tidak didukung, pakai profil default dengan
  aturan "probe tanpa cookie" yang tetap berlaku.
- JavaScript aktif. `allowFileAccess`/`allowContentAccess` = false. Safe Browsing aktif.
  `setSupportMultipleWindows(false)` (popup iklan dibuka di tab yang sama atau diblokir).
  `intent://`/skema app lain hanya dibuka setelah konfirmasi. Unduhan diabaikan.
  `hideAppIdentity()`.
- Kolom alamat: URL, atau kata kunci → mesin pencari (U11).
- Menu ⋯: Muat ulang, Salin link, Buka di browser lain, Hapus data browser Ritme.

**Pipeline deteksi:**

1. **Endus jaringan** (`WebViewClient.shouldInterceptRequest`, ditambah
   `ServiceWorkerClientCompat.shouldInterceptRequest`). Selalu `return null` (halaman tidak pernah
   diubah). Hanya GET. Kandidat kalau:
   - ekstensi path `.m3u8` → HLS, `.mpd` → DASH, `.mp4/.m4v/.webm/.mov/.ogv/.mkv` → FILE;
   - petunjuk di query (`format=m3u8`, `type=hls`, `mime=video%2F…`), pola `master.m3u8`,
     `/manifest`, `.urlset/`;
   - tanda tangan request elemen media: header `Range: bytes=0-` dan
     `Accept-Encoding: identity;q=1, *;q=0` pada URL tanpa ekstensi.
   - Segmen (`.ts/.m4s/.aac/.key/.vtt`) tidak menjadi kandidat, tapi menandai "halaman ini memutar
     stream".
   - Host googlevideo/YouTube **tidak** dijadikan entri `URL`. Kalau halamannya video YouTube →
     tawarkan `YOUTUBE_VIDEO` dengan videoId.
2. **JS document-start** (`addDocumentStartJavaScript`, semua frame) yang lapor lewat
   `addWebMessageListener("ritmeMedia")`:
   - `<video>`/`<source>` (MutationObserver) dan event `loadedmetadata`/`play` (capture) →
     `currentSrc` (yang bukan `blob:`), `duration` (`Infinity` = live), `videoWidth/Height`,
     `poster`, ukuran tampil;
   - meta `og:video`/`og:video:secure_url`/`og:title`/`og:site_name`, JSON-LD
     `VideoObject.contentUrl/duration`, dan `document.title`.
   - Data dari halaman dianggap tidak tepercaya: maksimal 50 kandidat per halaman, URL ≤ 2048,
     semuanya divalidasi ulang di native.
3. **Gabung dan urutkan:** master playlist HLS mengalahkan media playlist-nya. Duplikat dibuang.
   Host iklan yang dikenal (daftar kecil statis: `doubleclick`, `googlesyndication`, `imasdk`, …)
   dibuang. Urutan: tersambung ke `<video>` terbesar yang sedang diputar → durasi terpanjang → terbaru.
4. **Probe** (saat daftar dibuka, maksimal 4 sekaligus, timeout 8 detik), OkHttp aplikasi, **tanpa
   cookie dan tanpa Referer**, seperti anggota lain akan mengambilnya:
   - GET `Range: bytes=0-4095` → status, `Content-Type`, dan magic bytes (`#EXTM3U`, `<MPD`, box
     `ftyp`, EBML `1A45DFA3`);
   - HLS: master → varian. `#EXT-X-ENDLIST` tidak ada → live. Jumlah `#EXTINF` → durasi.
     `#EXT-X-KEY METHOD=SAMPLE-AES` atau `KEYFORMAT` Widevine/FairPlay → **DRM**. `AES-128` boleh,
     dengan catatan kunci bisa butuh cookie.
   - DASH: `type="dynamic"` → live, `mediaPresentationDuration` → durasi, `<ContentProtection>` → **DRM**;
   - gagal tanpa header tapi berhasil dengan Referer origin halaman → "Hanya di aplikasi" (butuh
     `referrer`, D8);
   - gagal tanpa cookie (401/403, tapi WebView memutarnya) → "Butuh login di situs ini";
   - ulangi GET dengan `Origin: <server Ritme>` → tidak ada `Access-Control-Allow-Origin` yang cocok
     untuk HLS → lencana "Tidak bisa diputar di web".
   - Petunjuk dari query: `expires=/exp=/Expires=` → "Link kedaluwarsa ±N menit". `ip=` → "Mungkin
     terkunci ke jaringanmu".
5. **Daftar temuan** (PopupSheet, baris dengan placeholder 16:9): judul, domain, jenis
   (HLS/DASH/MP4/WebM), durasi atau LIVE, resolusi, dan lencana putusan.
6. **Konfirmasi** (PopupSheet, wajib): `SheetTextField` judul (default `og:title` → `document.title` →
   nama file), sumber = domain halaman (`og:site_name` kalau ada) sebagai `artist`, lencana, dan
   peringatan tetap **"Link ini akan terlihat oleh semua anggota room."** Kalau ada parameter
   sensitif (`token`, `auth`, `key`, `session`, `jwt`, `sig`, …): **"Link ini mungkin berisi kunci
   akses pribadi."** Tombol "Tambah" / "Putar berikutnya".
   - **Ditolak** (tombol nonaktif, U12): DRM, butuh cookie/login, gagal total.
   - Fragment dibuang. `thumbnailUrl` tidak dikirim (mencegah pelacakan lewat gambar dari host
     sembarang). `durationMs` = durasi probe atau 0 (live/tidak diketahui).

**Yang tidak akan berhasil (dan pesannya):**

| Kasus | Deteksi | Perilaku |
|---|---|---|
| DRM (Netflix, Disney+, Prime, sebagian OTT lokal) | Kunci di playlist, `ContentProtection`, atau EME terpakai (`requestMediaKeySystemAccess` di-hook JS) | Ditolak: "Video berproteksi tidak bisa dibagikan" |
| Butuh cookie/sesi | Probe tanpa cookie gagal | Ditolak: "Butuh login di situs ini, jadi tidak bisa diputar anggota lain" |
| Terkunci Referer | Probe berhasil hanya dengan Referer | Boleh, dengan lencana "Hanya di aplikasi" (web gagal) |
| Terkunci IP / token kedaluwarsa | Petunjuk di query, atau gagal di perangkat lain | Boleh, dengan peringatan. Gagal per perangkat → state "tidak bisa diputar" |
| CORS (HLS di web) | Probe dengan `Origin` | Boleh, lencana "Tidak bisa diputar di web" |
| `blob:`/MSE tanpa manifest yang terlihat | Hanya `blob:` yang ditemukan | Tidak ditawarkan ("Video di halaman ini tidak bisa dibagikan") |

---

## 5. Seam desktop

Tidak dikerjakan sekarang, tapi desainnya dijaga supaya bisa dipakai ulang:

- `:modules:ritme-media` dan `MediaUrlRules` = JVM murni, langsung bisa dipakai desktop.
- `RitmeSyncPlanner`, `DriftCorrector`, dan `VideoFollower` tetap murni. Saat Ritme masuk desktop
  (seam K18 di spec D2–D4: `PlayerController.dispatch`, `QueueSource`, `PlaybackClock`), ketiganya
  dipindah ke modul JVM `:modules:ritme-client`.
- **Pemutaran:** libVLC (vlcj) memutar mp4/HLS/DASH dan video YouTube. Untuk entri `URL` dipakai
  opsi `:http-referrer=` dari `referrer`. Googlevideo tetap lewat `StreamProxy` (K2), karena butuh
  `Origin`. Video dirender lewat `CallbackVideoSurface` → Compose.
- **Browser:** JCEF (`desktop:web`). `CefResourceRequestHandler.onBeforeResourceLoad` setara lapis 1,
  dan `onResourceResponse` memberi `Content-Type` asli (lebih akurat dari Android). JS lapis 2 sama.

---

## 6. Web (#2a)

### 6.1 "Tambah video"

- Sheet "Tambah lagu" mendapat tab **Lagu / Video** (brief §12 L). Tab Video: kolom "Tempel link
  video" + pratinjau + "Tambah"/"Putar berikutnya".
- **Link YouTube** → parser TS (fixture yang sama dengan `YouTubeLinks`) → `GET /api/video?id=` →
  pratinjau (thumbnail `i.ytimg.com`, judul, channel, durasi/LIVE) →
  `NewEntry(kind: 'YOUTUBE_VIDEO')`. Gagal (502/503/404) → "Info video tidak bisa diambil" dan
  **tidak ditambah** (tanpa fallback judul palsu).
- **Link langsung** `.mp4/.webm/.m4v/.mov/.m3u8` → pratinjau memakai `<video preload="metadata" muted>`
  tersembunyi (hls.js untuk `.m3u8` tanpa HLS native). Hasilnya durasi (`Infinity` → live →
  `durationMs = 0`), dan sekaligus pengujian bisa-tidaknya diputar. Ini request ke host media yang
  memang akan diputar, dan sudah tercakup CSP `media-src`/`connect-src https:`, jadi tidak ada pihak
  ketiga baru. Timeout 10 detik → "Durasi tidak diketahui" (boleh ditambah, `durationMs = 0`).
  Error metadata → "Video ini tidak bisa diputar di web", tapi tetap boleh ditambah (anggota Android
  mungkin bisa). Kolom "Judul (opsional)", default nama file. `artist` = domain.
- Ditolak: `http://` saat halaman https ("Halaman harus https untuk link http"), `.mpd` ("Video ini
  tidak bisa diputar di web", sampai ada keputusan dash.js), dan link halaman ("Link ini halaman web,
  bukan file video…" + tips browser di aplikasi, brief §13).
- Validasi `MediaUrlRules` versi TS sebelum mengirim (fixture bersama).

### 6.2 Pemutaran

- `playable.ts`: `streamType === 'HLS'` (#3) atau `.m3u8` → hls.js. `streamType === 'DASH'` → tidak
  didukung.
- Opsional, dan **perlu diverifikasi:** hls.js `fetchSetup` dengan `referrerPolicy: 'no-referrer'`
  (banyak hotlink protection mengizinkan Referer kosong). Jangan memakai `<meta name="referrer">`
  global, karena embed YouTube sekarang membutuhkan Referer (error 153).
- Opsional: koreksi drift `<video>` memakai `playbackRate` 0,97/1,03 (`preservesPitch` default),
  bukan hanya seek. IFrame YouTube tetap seek saja.
- Tata letak video (brief §12 I–K, §13): mengikuti desain yang dikirim desainer. Aturan **IFrame
  YouTube terlihat** (min. 200 px, tidak tertutup) tetap berlaku.

---

## 7. Alur pengguna

**A. Tempel link YouTube (Android, di room).** Room sheet → "Tambah video" → tempel → pratinjau →
"Tambah" → `Add(YOUTUBE_VIDEO)` → semua anggota: anchor +1,5 detik → Android memutar audio +
pengikut video, web memutar IFrame besar.

**B. Share dari aplikasi YouTube.** Video → Bagikan → "Tambah ke Ritme" → Musicfy terbuka dengan sheet
"Tambah video" (pratinjau) → "Tambah". Kalau tidak di room → "Mulai Ritme dengan video ini".

**C. Cari video (Android).** "Tambah video" → ketik kata kunci → hasil 16:9 → ketuk → pratinjau →
"Tambah".

**D. Web tempel link mp4.** Tab Video → tempel → pratinjau `<video>` → "Tambah" → Android memutarnya
lewat `MusicService` (#2b) dan web lewat `<video>`.

**E. Browser (#3).** Room sheet → "Cari video di web" → buka situs → putar videonya di halaman → pill
"1 video ditemukan" → daftar (probe) → konfirmasi (judul, peringatan) → "Tambah".

**F. Layar penuh.** Panggung → tombol layar penuh → video berputar ke samping (animasi `RotatingFrame`),
bar kontrol tipis di sisi, ketuk di luar video untuk menyembunyikan bar → kembali/tombol keluar.

---

## 8. Penanganan error

| Situasi | Perilaku |
|---|---|
| `/api/video` 404/429/503/502 | Web: "Video tidak ditemukan" / "Terlalu banyak permintaan, coba lagi sebentar" / "Server sibuk" / "Info video tidak bisa diambil". Tidak ada yang ditambahkan. |
| Video YouTube gagal di-resolve di Android | Audio tetap. Panggung menampilkan thumbnail + "Video tidak tersedia, audio tetap diputar". Tanpa auto-skip. |
| Entri `URL` gagal di satu perangkat (403, CORS, mixed content, codec) | Perangkat itu: `StreamFailureUi` yang ada (Android) atau state "Video ini tidak bisa diputar" di panggung (web), ditambah tombol "Lewati" untuk yang punya izin kontrol. Room tidak menunggu. |
| Entri gagal di semua perangkat, durasi > 0 | Timer cadangan server (#1 2.6) memajukan room. |
| Entri gagal di semua perangkat, live (`durationMs = 0`) | Tidak ada fallback otomatis (sesuai Revisi 7). Panggung menampilkan "Lewati" untuk yang punya kontrol. Android mengirim `TrackEnded` hanya saat `STATE_ENDED`, **tidak** saat error fatal (error bukan berarti selesai), jadi room dilewati manual. |
| `INVALID` dari server saat `Add` | Mestinya tidak terjadi (validasi di klien sama). Kalau terjadi: toast "Link ini tidak bisa ditambahkan". |
| `FORBIDDEN` (`guestsCanAdd` mati) | Toast `ritme_toast_forbidden` yang ada. Tombol tambah disembunyikan. |
| Link kedaluwarsa di tengah putar | Gagal per perangkat seperti di atas. Anggota yang menambah bisa menambah ulang lewat browser. |
| Layar mati saat video | Audio jalan terus (dengar bareng). Video berhenti didekode. Saat kembali, video menyusul. |
| Rotasi/rekreasi activity | Tidak terjadi di HP (portrait + `RotatingFrame`). Di layar besar (sistem mengabaikan kunci orientasi), state panggung `rememberSaveable` dan pemutar tetap di service. |
| WebView tidak menangkap request media (versi WebView lama) | Lapis JS menutupinya untuk `<video src>`. Kalau hanya `blob:` → "Video di halaman ini tidak bisa dibagikan". |

**Privasi:** server tidak menyimpan dan tidak me-log id video, URL, judul, atau query. README
diperbarui dengan penjelasan: entri `URL` terlihat semua anggota, dan perangkat anggota akan
menghubungi host video tersebut secara langsung.

---

## 9. Testing

**`:modules:ritme-protocol` / `:modules:ritme-media` (JUnit)**
- `MediaUrlRules`: fixture `modules/ritme-protocol/fixtures/rules/media-urls.json` (input → diterima/alasan
  ditolak). Mencakup userinfo, IP privat v4/v6, `localhost`, karakter kontrol, fragment, panjang,
  dan origin `referrer`.
- `YouTubeLinks`: fixture `youtube-links.json` (semua bentuk link, `t=`/`list=`, id tidak valid).
- `PlaylistSniffer`: m3u8 master/media, VOD/live, AES-128, SAMPLE-AES/Widevine. MPD static/dynamic,
  `ContentProtection`.
- `MediaUrlClassifier`: ekstensi, petunjuk query, tanda tangan header media, segmen diabaikan,
  googlevideo dikecualikan.
- Golden files regenerasi untuk `guestsCanAdd`, `streamType`, `referrer` (kalau disetujui).

**`ritme-server` (JUnit)**
- Parser `get_queue` dengan respons yang direkam: video biasa, live (tanpa `lengthText`), wrapper
  renderer, kosong → 404.
- `/api/video`: 400/429/503/502, cache TTL (biasa vs live), single-flight, budget upstream bersama
  search. Test search yang ada tetap hijau setelah refactor `SingleFlightCache`.
- `RoomLogic.isValid` memakai `MediaUrlRules`. `guestsCanAdd` di matriks izin.

**Web (Vitest)**
- Parser link YouTube dan `MediaUrlRules` TS membaca fixture yang sama.
- Sheet Tambah video: link YouTube (fetch palsu), mp4 (elemen video palsu: metadata, `Infinity`,
  error, timeout), penolakan http/.mpd/halaman.
- `playable.ts` dengan `streamType`.

**Android (JUnit `app/src/test`)**
- `VideoFollower`: tiga zona, histeresis, mengikuti kecepatan audio, batas seek.
- `RitmeSyncPlanner`: entri `URL` dapat dimainkan, live → `Load(startMs = null)`, live Play setelah
  Pause → seek default.
- `RitmeEntries`: `toVideoEntry`/`toUrlEntry` lolos validasi, `musicVideoType` untuk `YOUTUBE_VIDEO`.
- Bridge dengan host palsu: `setRitmeVideoEnabled` dipulihkan saat keluar room.
  `RITME_URL:` tidak pernah mencapai `routeStream`.
- **Perilaku di luar room:** test bahwa `createMediaSourceFactory`/`deviceStream` untuk `mediaId`
  biasa tidak berubah.

**Uji manual (checklist)**
- [ ] 2 HP + web: video YouTube, selisih antar-HP < 100 ms, bibir dan suara di tiap HP tampak
      selaras (rekam dengan HP ketiga).
- [ ] Shorts (9:16), video 21:9, dan live YouTube.
- [ ] Layar penuh: animasi putar, bar kontrol, sembunyi/tampil, kembali.
- [ ] Layar mati 1 menit saat video → audio jalan terus. Layar hidup → video menyusul < 2 detik.
- [ ] Share dari aplikasi YouTube → "Tambah ke Ritme" (di room dan di luar room).
- [ ] Web tempel mp4 dan m3u8 (dengan dan tanpa CORS) → Android dan web memutar atau menampilkan
      state gagal.
- [ ] Browser: situs dengan mp4 polos, HLS (hls.js), HLS live, Referer-locked, situs DRM (ditolak),
      situs yang butuh login (ditolak), URL `http://192.168.1.1/…` (ditolak).
- [ ] Data seluler: kualitas video turun ke 480p.
- [ ] Keluar room → pemutar lokal kembali normal (track video aktif, cache lagu utuh).

---

## 10. Rencana bertahap

| Fase | Isi | Prasyarat | Perkiraan |
|---|---|---|---|
| **2a** | `MediaUrlRules` + fixture, pengerasan `RoomLogic`. `SingleFlightCache` + `/api/video`. `guestsCanAdd` (U6). Web: tab Video, pratinjau, parser + fixture, dan UI video sesuai brief kalau desain sudah ada (kalau belum, minimal: lencana dan baris 16:9). | – | 1,5–2 hari |
| **2b-1** | Android playback: `isPlayable` URL, `RITME_URL:` di `MusicService` (tanpa cache, header, DNS guard), `media3-exoplayer-dash`, live edge, `setRitmeVideoEnabled`, perbaikan `musicVideoType`, `RitmeVideoStage` + `VideoFollower`, baris antrean 16:9, layar penuh `RotatingFrame`. | 2a (URL dari web untuk diuji) | 3–4 hari |
| **2b-2** | Android menambah: `RitmeAddVideoSheet` (link, pencarian U3), alias share "Tambah ke Ritme", `startWithEntries`. | 2b-1 | 1,5–2 hari |
| **3** | `:modules:ritme-media` (classifier, sniffer), protokol `streamType`/`referrer` (U7) + validasi server + web `playable.ts`, layar browser (profil, WebView aman), deteksi 3 lapis, probe, daftar, konfirmasi. | 2b | 4–5 hari |
| **Lanjutan** | Sinkron live via PROGRAM-DATE-TIME, PiP, pencarian video di web, Ritme desktop (seam di Bagian 5). | – | – |

---

## 11. Risiko

| # | Risiko | Mitigasi |
|---|---|---|
| R1 | YouTube memperketat stream video-only (PoToken/SABR, 403 setelah beberapa detik). Resolver video tidak memakai PoToken. | Client fallback yang ada. Gagal → audio tetap jalan (degradasi halus). Pantau lewat `PlaybackLogManager`. |
| R2 | Codec AV1/VP9 tanpa decoder hardware → patah-patah dan panas. | Pemilihan codec sadar decoder (4.3). |
| R3 | Data dan baterai: 720p ≈ 1–1,5 GB/jam. | 480p di jaringan berbayar, video berhenti saat layar mati (U10). |
| R4 | `MusicService` sangat besar (±4000 baris), jadi perubahan bisa meregresi pemutaran biasa. | Semua jalur baru di balik prefiks `RITME_URL:` dan hanya di room. Test "di luar room tidak berubah". `trackSelectionParameters` dipulihkan di `exitRitmeMode`. |
| R5 | Perilaku `shouldInterceptRequest` untuk request media berbeda antarversi WebView. | Lapis JS + tanda tangan header + client service worker. Uji di WebView stabil dan versi lama. |
| R6 | Hukum dan kebijakan: browser memudahkan berbagi konten bajakan. | DRM tidak pernah dibuka. Konten yang butuh login ditolak. Peringatan di konfirmasi. Tidak ada daftar situs bawaan. Catatan di README. |
| R7 | ToS YouTube: Android memutar stream di luar pemutar resmi, tanpa iklan, dan di latar. | Ini **bukan postur baru**: seluruh aplikasi sudah begini untuk audio. Server tetap metadata-only (`/api/video` = panggilan InnerTube yang sama dengan search). Web tetap patuh (IFrame terlihat ≥ 200 px, tidak ditutup). Jangan memasarkan Ritme sebagai "tanpa iklan". |
| R8 | Penyalahgunaan: tamu menambah video tidak pantas atau link pelacak. | `guestsCanAdd` (U6). Host bisa hapus/kick. Klien tidak mengirim thumbnail `URL`. Host privat ditolak. |
| R9 | `guestsCanAdd`: host dengan Android lama mengirim `SetSettings` tanpa field itu → reset ke `true`. | Diterima (web selalu terbaru karena satu image dengan server). Dicatat di README. |
| R10 | Live tidak benar-benar sinkron (1–3 detik). | Disampaikan di UI ("LIVE", tanpa progres). PDT sebagai tindak lanjut. |
| R11 | `get_queue` dari IP VPS diblokir atau berubah bentuk. | Fixture test + log kelas error. Cadangan: `next` (`WEB_REMIX`) dengan parser renderer yang sama. |
| R12 | Iklan pre-roll terdeteksi sebagai kandidat. | Daftar host iklan + urutan berdasarkan `<video>` terbesar dan durasi. Pengguna tetap memilih. |

---

## 12. Keputusan yang butuh persetujuan pengguna

| # | Keputusan | Rekomendasi |
|---|---|---|
| U1 | Cara Android memutar video YouTube | **Dua pemutar** (audio `MusicService` + pengikut video bisu). Bukan satu pemutar gabungan (risiko regresi besar) dan bukan IFrame di WebView. |
| U2 | Layar penuh lanskap | **`RotatingFrame` yang sudah ada** (activity tetap portrait, tanpa rekreasi). Bukan memutar orientasi activity. |
| U3 | Sumber pencarian video di Android | **Pencarian YouTube umum** (client `WEB`, parser `videoRenderer` baru di `:providers:innertube`), karena nonton bareng kebanyakan konten non-musik. Fallback ke tab "Video" YouTube Music yang sudah ada. |
| U4 | Ketukan lagu di room | **Tetap `YOUTUBE_MUSIC`** seperti #1 (termasuk video musik). Hanya jalur "Tambah video" yang membuat `YOUTUBE_VIDEO`. |
| U5 | Target share "Tambah ke Ritme" | **Selalu terlihat.** Di luar room ia menawarkan "Mulai Ritme dengan video ini". Bukan hanya muncul saat di room (mengubah komponen secara dinamis itu rapuh). |
| U6 | Setelan room `guestsCanAdd` (backlog item 4) | **Ya, masuk 2a.** Default `true`. Penting karena video dan URL memperluas potensi penyalahgunaan. |
| U7 | Ekstensi protokol #3: `streamType` + `referrer` (origin saja), opsional | **Ya.** Tanpa naik versi protokol. Tanpa ini, HLS tanpa ekstensi dan situs yang mengecek Referer gagal di perangkat anggota. |
| U8 | Pengerasan validasi URL di server (userinfo, host privat/lokal, karakter kontrol, fragment) | **Ya, di 2a**, sebelum web mulai menambah URL. |
| U9 | Video di gaya pemutar non-flagship (Poster/Timeline/Lyrics) | **Hanya layar penuh** lewat menu "Lihat video". Gaya pilihan pengguna tidak diganti otomatis. |
| U10 | Batas kualitas dan data | **720p di Wi-Fi, 480p di seluler**, dan video berhenti saat layar mati (audio tetap). Tanpa setelan baru dulu. |
| U11 | Browser: isolasi dan mesin pencari | **Profil WebView terpisah** + **DuckDuckGo** untuk kata kunci di kolom alamat. |
| U12 | Entri yang butuh cookie/login atau ber-DRM | **Ditolak** (tidak bisa ditambahkan), bukan sekadar diberi peringatan. |

---

## Keputusan pengguna (2026-11-23)

Pengguna menyetujui **semua rekomendasi U1–U12** seperti tertulis di atas. Ada satu tambahan wajib:

- **Browser in-app (#3): address bar harus bisa diisi URL secara manual.** Pengguna bisa mengetik
  atau menempel alamat lengkap (`https://…`), domain tanpa skema (`example.com/video`, otomatis
  menjadi `https://`), atau kata kunci (diteruskan ke DuckDuckGo, sesuai U11). Address bar juga
  menampilkan URL halaman yang sedang dibuka, bisa disalin, dan bisa disunting. Tombol muat ulang,
  kembali, dan maju tersedia. Skema berbahaya (`javascript:`, `file:`, `content:`, `intent:`) dan
  alamat lokal atau privat ditolak dengan pesan yang jelas, mengikuti `MediaUrlRules`.
