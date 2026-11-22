# Jam — Dengar Bareng (Android + Web Pendamping)

- **Tanggal:** 2026-11-22
- **Status:** Desain disetujui, menunggu review spec
- **Cakupan:** Sub-proyek #1 dari tiga fitur "bareng" (lihat [Peta sub-proyek](#peta-sub-proyek))

## Ringkasan

Fitur seperti Spotify Jam: satu orang membuat *room*, teman bergabung lewat kode, link, atau QR,
lalu semua anggota mendengar antrean yang sama secara sinkron dan bisa menambah lagu. Anggota bisa
memakai aplikasi Android Musicfy atau **web pendamping** di browser tanpa perlu instal apa pun.

Sinkronisasi diatur oleh **server relay milik sendiri** (Ktor, WebSocket) yang menyimpan state room
di memori. Setiap perangkat memutar stream-nya sendiri. Server tidak pernah meneruskan audio.

## Peta sub-proyek

| # | Sub-proyek | Status |
|---|---|---|
| 1 | Fondasi room + Jam (Dengar Bareng), Android + web pendamping | **Spec ini** |
| 2 | Nonton YouTube bareng: memakai ulang room, dengan jenis media video | Spec terpisah, setelah #1 |
| 3 | Browser in-app + deteksi video + nonton bareng (khusus Android, karena mustahil di web app) | Spec terpisah, setelah #2 |

## Keputusan yang sudah diambil

| Topik | Keputusan |
|---|---|
| Koneksi antar-perangkat | Server relay sendiri (Ktor + WebSocket), di-hosting oleh pemilik proyek |
| Arsitektur sinkronisasi | State room dipegang server; klien mengirim perintah, server menyiarkan snapshot |
| Kontrol | Semua anggota boleh menambah lagu; host punya toggle "anggota boleh kontrol", bisa mengeluarkan anggota, dan bisa memindahkan host |
| Identitas & gabung | Tanpa akun: kode 6 karakter / link / QR + nama tampilan. Room hanya di memori dan hilang saat kosong |
| Sumber lagu | Hanya lagu yang punya videoId YouTube/InnerTube. File lokal dan download pribadi tidak bisa dibagikan |
| Platform | Android tetap aplikasi utama. Web = klien pendamping untuk Jam (bukan port penuh aplikasi) |

## Bukan bagian dari spec ini (non-goals)

- Port aplikasi Android menjadi web app penuh. Sudah dievaluasi dan ditolak: butuh proxy stream
  audio (biaya bandwidth, pemblokiran IP oleh Google, risiko ToS/DMCA) atau IFrame yang berhenti
  saat layar mati.
- Akun pengguna, room yang tersimpan permanen, atau riwayat room.
- Chat atau reaksi di dalam room.
- Scanner QR di dalam aplikasi (kamera bawaan HP sudah cukup membuka link https).
- App Links terverifikasi (`assetlinks.json`). Opsional, baru disiapkan kalau ada domain tetap.
- Mengubah `:providers:innertube` menjadi modul JVM murni.
- Nonton video bareng dan browser in-app (sub-proyek #2 dan #3).

---

## 1. Arsitektur & komponen

```
┌──────────── HP Anggota (app Android) ────┐        ┌──────── server/ (Ktor) ─────────┐
│ UI Jam (PopupSheet + pill)               │        │ WebSocket /jam                  │
│      │                                   │        │  RoomRegistry (in-memory)       │
│ JamViewModel                             │        │   └ Room: state, members,       │
│      │                                   │  WSS   │           permissions           │
│ JamSession ──── JamClient (Ktor WS) ─────┼───────▶│  CommandHandler (cek izin →     │
│      │              ClockSync            │◀───────┼──  ubah state → broadcast)      │
│ JamPlaybackBridge + DriftCorrector       │        │  ClockService (Pong)            │
│      │                                   │        │  Reaper (hapus room kosong)     │
│ PlayerConnection / MusicService (lama)   │        │ GET /api/search (metadata)      │
└──────────────────────────────────────────┘        │ GET /, /j/{code} → web statis   │
┌──────────── Browser (web/) ──────────────┐        │                                 │
│ Preact UI · JamClient · ClockSync ·      │───────▶│                                 │
│ YouTube IFrame Player                    │◀───────│                                 │
└──────────────────────────────────────────┘        └─────────────────────────────────┘
```

### 1.1 Unit baru

| Unit | Tugas | Bergantung pada |
|---|---|---|
| `:modules:jam-protocol` | Tipe `RoomState`, `QueueEntry`, `Member`, `RoomSettings`, `Playback`, sealed `ClientMessage`/`ServerMessage`, `Command`, `ErrorCode`, konstanta `PROTOCOL_VERSION`, dan fungsi murni `RoomState.positionAt(serverNow)`. Modul Kotlin/JVM murni (tanpa Android), memakai kotlinx.serialization. Test-nya menghasilkan golden files JSON. | – |
| `server/` | Aplikasi Ktor (JVM) + `Dockerfile`: `RoomRegistry`, `Room`, `CommandHandler`, `ClockService`, `Reaper`, `RateLimiter`, `SearchService` (klien search InnerTube kecil), dan penyaji file statis web. | jam-protocol |
| `web/` | Web pendamping, TypeScript + Vite + Preact. Lihat Bagian 5. | (protokol ditulis ulang di TS dan dijaga oleh golden files) |
| `app/.../jam/JamClient` | Buka/tutup WebSocket, reconnect dengan backoff, kirim/terima pesan. | Ktor client (sudah ada) |
| `app/.../jam/ClockSync` | Ping berkala, menghitung `offset` dan RTT terhadap jam server. | JamClient |
| `app/.../jam/JamSession` | Singleton Hilt. `StateFlow<JamUiState>` (state room, status koneksi, memberId) + aksi (`create`, `join`, `leave`, `addToQueue`, `play`, `pause`, `seek`, …). | JamClient, ClockSync |
| `app/.../jam/JamPlaybackBridge` | Menerapkan `RoomState` ke ExoPlayer (ganti lagu, play/pause, koreksi drift). Menangkap aksi lokal (notifikasi media, headset, tombol UI) dan mengubahnya menjadi perintah ke room. | JamSession, PlayerConnection |
| `app/.../jam/DriftCorrector` | Fungsi murni `(expectedMs, actualMs) → DriftAction(Ignore / SetSpeed(x) / Seek(ms))`. | – |
| `app/.../playback/queues/JamQueue` | Implementasi `Queue` yang isinya mengikuti antrean room. | jam-protocol |
| `app/.../ui/screens/jam/` | `JamEntrySheet` (buat/gabung), `JamRoomSheet` (room), `JamPill`, `JamViewModel`, bagian Pengaturan Jam. | JamSession |

### 1.2 Integrasi dengan pemutar yang sudah ada (Android)

- Saat bergabung, antrean lokal dan posisinya disimpan (snapshot di memori), lalu diganti `JamQueue`.
  Saat keluar, snapshot itu dikembalikan dalam keadaan pause.
- Selama di room, fitur yang mengubah antrean atau kecepatan secara lokal **dimatikan**:
  Crossmix/automix, shuffle, repeat, radio tak terbatas, dan pengaturan kecepatan/pitch (dikunci 1,0×).
- Deep link baru di `AndroidManifest.xml`: `musicfy://jam/{code}` dan `https://<host-server>/j/{code}`.
  Host server dibaca dari konfigurasi build (`BuildConfig.JAM_SERVER_HOST`).
- URL server disimpan di `PreferenceKeys` (`jamServerUrl`). Default-nya server resmi, dan bisa diganti
  di Pengaturan untuk self-host. Nama tampilan disimpan di `jamDisplayName`.
- Fitur ini tersedia di semua flavor (`gms`, `foss`, `fdroid`) karena hanya memakai server sendiri.

---

## 2. Protokol & algoritma sinkronisasi

### 2.1 State room (milik server)

```kotlin
@Serializable data class RoomState(
    val code: String,               // 6 karakter dari "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    val version: Long,              // naik 1 setiap kali state berubah
    val hostId: String,
    val settings: RoomSettings,     // guestsCanControl: Boolean = true
    val members: List<Member>,      // id, name, isHost, connected, joinedAt
    val queue: List<QueueEntry>,    // entryId, videoId, title, artist, thumbnailUrl, durationMs, addedBy
    val currentEntryId: String?,
    val playback: Playback,         // isPlaying, positionMs, anchorServerTimeMs
)
```

- Posisi saat ini: `if (isPlaying) positionMs + (serverNow − anchorServerTimeMs) else positionMs`,
  dibatasi ke `[0, durationMs]`. Diimplementasikan sekali sebagai `RoomState.positionAt(serverNow)`.
- `entryId` adalah ID unik per entri antrean (UUID pendek), karena satu videoId boleh muncul lebih dari sekali.
- `queue` berisi seluruh antrean, termasuk lagu yang sedang diputar. Lagu yang sudah lewat tetap ada,
  supaya `Previous` bisa bekerja. Indeks lagu aktif = posisi `currentEntryId`.

### 2.2 Pesan

Semua pesan berupa JSON dengan diskriminator `"type"`.

**Klien → Server**

| Pesan | Isi |
|---|---|
| `Create` | `protocolVersion`, `name`, `initialQueue: List<QueueEntry>?`, `startEntryId?`, `startPositionMs?` |
| `Join` | `protocolVersion`, `code`, `name`, `resumeToken?` |
| `Leave` | – |
| `Ping` | `clientTime` |
| `Command` | `reqId`, `cmd` |
| `TrackEnded` | `entryId` |

**`Command.cmd`:** `Play`, `Pause`, `Seek(positionMs)`, `Next`, `Previous`, `PlayEntry(entryId)`,
`Add(entries, playNext: Boolean)`, `Remove(entryId)`, `Move(entryId, afterEntryId?)`,
`SetSettings(settings)`, `Kick(memberId)`, `TransferHost(memberId)`, `EndRoom`.

**Server → Klien**

| Pesan | Isi |
|---|---|
| `Welcome` | `memberId`, `resumeToken`, `state`, `serverTime` |
| `State` | `state` (snapshot penuh) |
| `Pong` | `clientTime`, `serverTime` |
| `Error` | `reqId?`, `code` (`ROOM_NOT_FOUND`, `ROOM_FULL`, `FORBIDDEN`, `RATE_LIMITED`, `UPGRADE_REQUIRED`, `INVALID`, `QUEUE_FULL`) |
| `Kicked` | – |
| `RoomClosed` | – |

- Server **selalu mengirim snapshot penuh** setelah setiap perubahan, bukan delta.
- Klien mengabaikan `State` yang `version`-nya ≤ versi yang sudah diterapkan.
- Klien tidak melakukan update optimistis untuk playback. UI selalu mengikuti snapshot server.

### 2.3 Izin (dicek di server, di `CommandHandler`)

| Perintah | Host | Anggota (`guestsCanControl=true`) | Anggota (`false`) |
|---|---|---|---|
| `Add` | ✅ | ✅ | ✅ |
| `Remove` entri sendiri | ✅ | ✅ | ✅ |
| `Remove` entri orang lain, `Move` | ✅ | ✅ | ❌ |
| `Play`, `Pause`, `Seek`, `Next`, `Previous`, `PlayEntry` | ✅ | ✅ | ❌ |
| `SetSettings`, `Kick`, `TransferHost`, `EndRoom` | ✅ | ❌ | ❌ |

Perintah yang ditolak dibalas `Error(reqId, FORBIDDEN)` dan state tidak berubah.

### 2.3a Semantik perintah antrean

- `Add(entries, playNext=false)` menambahkan ke akhir antrean. `playNext=true` menyisipkan tepat
  setelah lagu aktif. Kalau antrean belum punya lagu aktif, entri pertama langsung diputar (2.6).
- `Move(entryId, afterEntryId)` memindahkan entri ke tepat setelah `afterEntryId`. Kalau
  `afterEntryId = null`, entri dipindah ke tepat setelah lagu aktif. Lagu aktif tidak bisa dipindah.
- `Remove` pada lagu aktif sama dengan `Next`, lalu entri itu dihapus.
- `Previous`: kalau `positionAt(now) > 3000` ms, seek ke 0. Kalau tidak, putar entri sebelumnya
  (kalau tidak ada, seek ke 0).
- `Seek(positionMs)` dibatasi ke `[0, durationMs]` dan mempertahankan `isPlaying`.
- `Create` dengan `initialQueue` yang tidak kosong langsung memutar `startEntryId` (atau entri
  pertama) dari `startPositionMs` (default 0), dengan anchor `now + 1500`.

### 2.4 Sinkronisasi jam

- Saat terhubung, klien mengirim 5 `Ping` dengan jeda 200 ms. Untuk setiap `Pong`:
  `rtt = t1 − t0`, `offset = serverTime − (t0 + t1) / 2`.
- Offset yang dipakai berasal dari sampel dengan RTT terkecil.
- Setelah itu ping diulang setiap 30 detik. Offset dihitung dari sampel RTT terkecil di 8 sampel terakhir.
- Jam lokal: Android memakai `SystemClock.elapsedRealtime()`, web memakai `performance.now()` (keduanya monoton).
  `serverNow = localNow + offset`.

### 2.5 Koreksi drift

**Android (`DriftCorrector`, dievaluasi setiap 1 detik selama play):**

| `|expected − actual|` | Tindakan |
|---|---|
| < 40 ms | Abaikan (dan kembalikan kecepatan ke 1,0× kalau sedang dikoreksi) |
| 40 ms – 1000 ms | `SetSpeed` 0,97× (kalau terlalu maju) atau 1,03× (kalau tertinggal), pitch tetap, sampai selisih < 20 ms |
| > 1000 ms | `Seek(expected)` |

**Web:** hanya memakai seek, karena IFrame tidak mendukung kecepatan halus. Seek dilakukan kalau
selisih > 300 ms, dengan jeda minimal 5 detik antar-seek.

Target sinkron: Android < 100 ms antar-perangkat, web < 300 ms.

### 2.6 Ganti lagu & akhir lagu

- Setiap kali lagu aktif berganti (`Next`, `Previous`, `PlayEntry`, lagu selesai, atau `Add` ke antrean
  kosong), server menetapkan `positionMs = 0`, `isPlaying = true`, dan
  `anchorServerTimeMs = now + 1500`. Semua klien buffer lalu mulai bersamaan. Klien yang terlambat
  menyusul lewat koreksi drift, dan room tidak menunggu klien paling lambat.
- Selama `serverNow < anchorServerTimeMs`, klien menyiapkan lagu dalam keadaan pause di posisi 0,
  lalu mulai memutar tepat pada anchor.
- Server pindah ke lagu berikutnya saat menerima `TrackEnded(entryId)` pertama yang cocok dengan
  `currentEntryId`. Laporan duplikat atau basi diabaikan.
- **Timer cadangan:** kalau tidak ada `TrackEnded` saat `positionAt(now) ≥ durationMs + 5000`, server
  tetap pindah lagu.
- Klien web **tidak pernah** mengirim `TrackEnded` (iklan dan embed yang diblokir membuatnya tidak andal).
- Di akhir antrean: `isPlaying = false`, `currentEntryId` tetap pada lagu terakhir, dan posisi = durasi.

### 2.7 Putus koneksi & siklus hidup room

- Koneksi WebSocket putus → member ditandai `connected = false`, dan berlaku **masa tenggang 30 detik**.
  `Join` dengan `resumeToken` yang valid mengembalikan `memberId` dan status host yang sama.
- Host tidak kembali dalam 30 detik → host pindah ke member terhubung yang `joinedAt`-nya paling awal.
  Kalau host mengirim `Leave`, host langsung pindah dengan aturan yang sama (tanpa masa tenggang).
- Member yang lewat masa tenggang dihapus dari `members`.
- Room tanpa member terhubung selama 60 detik dihapus oleh `Reaper` (berjalan setiap 10 detik).
- `EndRoom` oleh host → semua klien menerima `RoomClosed`, lalu room dihapus.
- `Kick` → klien yang dikeluarkan menerima `Kicked`, koneksinya ditutup, dan `resumeToken`-nya dibatalkan.
- `resumeToken`: 128-bit acak (base64url), hanya berlaku untuk room itu.

### 2.8 Batasan

| Batas | Nilai | Error |
|---|---|---|
| Member per room | 20 | `ROOM_FULL` |
| Entri antrean | 500 | `QUEUE_FULL` |
| Perintah per member | 10 per detik (token bucket) | `RATE_LIMITED` |
| Ukuran pesan | 64 KB | koneksi ditutup |
| Nama tampilan | 1–32 karakter (di-trim) | `INVALID` |
| Search | 30 per menit per IP | HTTP 429 |

### 2.9 Konfigurasi server

Endpoint WebSocket: `/jam`. Health check: `GET /healthz`.
Lewat environment variable: `PORT` (default 8080), `PUBLIC_URL` (dipakai untuk link bagikan),
`WEB_DIR` (lokasi build web, default `/app/web`). Server tidak menulis apa pun ke disk.

---

## 3. UI & alur pengguna (Android)

Semua popup memakai `PopupSheet` sesuai `docs/popup-sheet.skill.md`. Tidak ada `Dialog` atau
`ModalBottomSheet` baru.

### 3.1 Titik masuk

1. **Tombol Jam** (ikon grup) di baris aksi pemutar (`PlayerControls`/`BottomSheetPlayer`), dekat tombol antrean.
2. **Menu lagu/playlist**, dengan item "Mulai Jam dengan ini" (room dibuat dengan item itu sebagai antrean awal).
3. **Deep link** `musicfy://jam/{code}` atau `https://<server>/j/{code}`. Langsung membuka alur gabung
   dengan kode yang sudah terisi.

### 3.2 Alur buat & gabung

```
[Tombol Jam] → JamEntrySheet
   ├─ "Mulai Jam" → (nama tampilan, diisi sekali lalu diingat) → Create
   │                 antrean + lagu aktif + posisi saat ini dibawa sebagai antrean awal
   │                 (hanya entri yang punya videoId; entri lokal dilewati, dengan toast jumlahnya)
   └─ "Gabung"    → kolom kode 6 karakter (otomatis huruf besar) → (nama) → Join
```

Setelah `Welcome` diterima, `JamRoomSheet` terbuka. Kalau sebelumnya ada lagu yang diputar, muncul
toast "Antrean kamu disimpan, dan akan kembali setelah keluar dari Jam".

### 3.3 `JamRoomSheet`

```
┌──────────────────────────────────┐
│  Jam · K7M2QX      [Bagikan] [QR] │
│  ●●●○ 4 anggota  (avatar inisial) │
├──────────────────────────────────┤
│  ▶ Sedang diputar                │
│    Judul — Artis   · oleh Budi   │
├──────────────────────────────────┤
│  Berikutnya                      │
│    Lagu A — oleh Sari      ≡  ✕  │
│    Lagu B — oleh Kamu      ≡  ✕  │
│  [+ Tambah lagu]                 │
├──────────────────────────────────┤
│  (host) Anggota boleh kontrol [⏽]│
│  [Keluar Jam] / (host) [Akhiri]  │
└──────────────────────────────────┘
```

- **Bagikan** membuka share sheet Android dengan teks + `https://<server>/j/{code}`.
- **QR** menampilkan QR berisi link https yang sama (dependensi baru: `com.google.zxing:core`).
- Ketuk anggota (khusus host) membuka menu *Jadikan host* / *Keluarkan*.
- Tombol ≡ dan ✕ disembunyikan kalau tidak diizinkan (aturan di 2.3).
- **+ Tambah lagu** membuka layar pencarian yang ada dalam mode "Tambah ke Jam".
- Selama di room, aksi "Tambah ke antrean" / "Putar berikutnya" di menu lagu mana pun dikirim
  sebagai `Add` ke room.
- "Keluar Jam" mengirim `Leave`. "Akhiri" (khusus host, dengan konfirmasi) mengirim `EndRoom`.

### 3.4 Indikator & kontrol yang dibatasi

- Mini player dan pemutar penuh menampilkan **pill "Jam · N"**. Mengetuknya membuka `JamRoomSheet`.
  Saat menyambung ulang, pill menampilkan "Menyambung ulang…".
- `QueueScreen` diganti tampilan antrean Jam selama di room.
- Shuffle, repeat, crossmix, dan kecepatan tampil redup. Kalau diketuk, muncul toast "Tidak tersedia saat Jam".
- Tanpa izin kontrol, play/pause/seek/next/prev tampil redup dan menampilkan toast "Host mengunci kontrol".
  Aturan yang sama berlaku untuk notifikasi media dan tombol headset (dicegat di `JamPlaybackBridge`).

### 3.5 Pengaturan

Bagian baru "Jam" di Pengaturan: **URL server** (dengan tombol "Kembalikan default") dan **nama tampilan**.

---

## 4. Penanganan error & testing

### 4.1 Error dan kasus tepi

| Situasi | Perilaku |
|---|---|
| Server tidak bisa dihubungi saat buat/gabung | Pesan di sheet "Tidak bisa terhubung ke server Jam" + *Coba lagi*. Antrean lokal tidak disentuh. |
| `ROOM_NOT_FOUND` / `ROOM_FULL` / `UPGRADE_REQUIRED` | "Room tidak ditemukan" / "Room penuh" / "Perbarui aplikasi untuk bergabung" |
| Koneksi putus di tengah sesi | Musik tetap jalan dari state terakhir. Pill "Menyambung ulang…". Backoff 1→2→4→8→15 detik dengan `resumeToken`. Setelah tersambung, state terbaru diterapkan dan drift dikoreksi. |
| Gagal reconnect dalam 30 detik | Keluar otomatis, antrean lama dikembalikan, toast "Terputus dari Jam". |
| `Kicked` / `RoomClosed` | Keluar, antrean lama dikembalikan, toast "Kamu dikeluarkan dari Jam" / "Jam diakhiri host". |
| Lagu gagal diputar di satu perangkat | Perangkat itu menampilkan `StreamFailureUi` yang ada, tanpa auto-skip untuk room. Perangkat itu ikut pindah saat room pindah lagu. |
| Lagu gagal di semua perangkat | Timer cadangan server (2.6) memastikan room tetap maju. |
| Perintah ditolak (`FORBIDDEN`, `RATE_LIMITED`, `QUEUE_FULL`) | Toast singkat. Tidak ada yang perlu di-rollback. |
| Proses dimatikan sistem | Selama musik berjalan, `MusicService` adalah foreground service dan WebSocket ikut hidup. Kalau tetap mati, berlaku masa tenggang lalu dikeluarkan. |

**Privasi:** server tidak menyimpan apa pun ke disk, dan tidak mencatat nama, videoId, query search,
atau IP ke log aplikasi. Yang dicatat hanya jumlah room dan koneksi aktif. README diperbarui untuk
menjelaskan data apa saja yang melewati server Jam.

### 4.2 Testing

**`:modules:jam-protocol` (JUnit)**
- Round-trip serialisasi untuk setiap jenis pesan dan perintah.
- `positionAt`: play, pause, anchor di masa depan, batas `[0, durationMs]`.
- Menulis golden files ke `modules/jam-protocol/fixtures/*.json`. Test gagal kalau file yang ada
  berbeda dari hasil serialisasi (perubahan harus dilakukan dengan sengaja lewat flag regenerate).

**`server/` (JUnit + `ktor-server-test-host`)**
- `CommandHandler` sebagai fungsi murni `(state, memberId, cmd, now) → Result<RoomState>`:
  matriks izin (2.3), Add/Remove/Move/playNext, Next di akhir antrean, Previous, anchor +1500 ms,
  `TrackEnded` duplikat atau basi.
- `Room` dengan jam palsu: masa tenggang, pindah host, resume token, Kick membatalkan token,
  timer cadangan, rate limit, batas member dan antrean.
- `Reaper`: room kosong dihapus setelah 60 detik.
- Integrasi WebSocket: dua klien bergabung, A mengirim `Play`, keduanya menerima `State` dengan versi yang sama.
- `SearchService` dengan respons InnerTube yang direkam (fixture), tanpa jaringan.

**App Android (JUnit di `app/src/test`)**
- `ClockSync` dengan sampel sintetis: offset dari RTT terkecil, jendela 8 sampel.
- `DriftCorrector`: tiga zona dan reset kecepatan.
- `JamSession` dengan `JamClient` palsu: versi lama diabaikan, alur reconnect, `Kicked` → keluar
  dan snapshot antrean dikembalikan.

**Uji manual (checklist)**
- [ ] 2–3 HP di jaringan berbeda (Wi-Fi + seluler): selisih audio < 100 ms (direkam dengan HP ketiga).
- [ ] 1 HP + 1 browser desktop + 1 browser HP: web < 300 ms.
- [ ] Mode pesawat 10 detik saat lagu berjalan → menyambung kembali dan sinkron.
- [ ] Host force stop → host pindah setelah 30 detik.
- [ ] Kunci kontrol → anggota menekan tombol headset/notifikasi → ditolak dengan toast.
- [ ] Keluar Jam → antrean pribadi kembali.

**CI:** test `jam-protocol` dan `server`, plus `npm ci && npm test && npm run build` untuk web,
ditambahkan ke `.github/workflows/build.yml` dan `build_pr.yml`. Job baru membangun image Docker
server (termasuk build web) tanpa push otomatis.

---

## 5. Web pendamping (`web/`)

### 5.1 Bentuk & hosting

- TypeScript + Vite + Preact, mobile-first.
- Hasil build disajikan oleh server Ktor dari origin yang sama (`/`, `/j/{code}`, aset statis),
  jadi WebSocket tanpa CORS dan cukup satu image Docker.
- `/j/{code}` membuka web app langsung di alur gabung room itu, dengan tombol
  "Buka di aplikasi Musicfy" (`musicfy://jam/{code}`) dan link ke halaman Releases GitHub.

### 5.2 Fitur

- Buat room, gabung lewat kode atau link, isi nama tampilan (diingat di `localStorage`).
- Melihat antrean dan anggota. Tambah, hapus, dan pindahkan lagu, serta kontrol playback, mengikuti izin 2.3.
- Fitur host: toggle kontrol anggota, keluarkan anggota, pindah host, akhiri room.
- **Mode dengar** (IFrame Player) atau **mode remote** (tanpa audio). Bisa diganti kapan saja.
- Pencarian lagu lewat `GET /api/search`.

### 5.3 Pencarian (`GET /api/search?q=`)

- `SearchService` di server melakukan satu panggilan `search` InnerTube (client `WEB_REMIX`,
  filter lagu) dan mengembalikan maksimal 20 item `{videoId, title, artist, durationMs, thumbnailUrl}`.
- Hanya metadata. Server tidak pernah mengambil atau meneruskan stream.
- Cache in-memory 10 menit per query (LRU 1000 entri), rate limit 30/menit per IP.
- Klien Android tetap memakai pencarian InnerTube miliknya sendiri. Endpoint ini khusus untuk web.

### 5.4 Pemutaran & sinkronisasi

- YouTube IFrame Player API: `loadVideoById`, `seekTo`, `playVideo`, `pauseVideo`, `getCurrentTime`.
  Player tampil kecil (sesuai ToS YouTube, tidak disembunyikan).
- `ClockSync` sama dengan 2.4. Koreksi drift dengan seek saja (2.5).
- **Autoplay:** setelah gabung, tampil tombol "Ketuk untuk ikut dengar". IFrame baru dibuat setelah ketukan itu.
- **Iklan:** ketika player kembali ke konten setelah iklan, web langsung seek ke posisi room.
- **Embed ditolak** (error 101/150): entri ditandai "Tidak bisa diputar di web". Web tetap mengikuti
  state dan tidak mengirim apa pun.
- Web tidak pernah mengirim `TrackEnded` (2.6).
- **Tab di background / layar mati:** audio bisa berhenti (batasan browser). Tampil tips
  "Biarkan layar menyala". Pada `visibilitychange` → terlihat, web menyinkronkan ulang posisi.
- Reconnect sama seperti Android (backoff + `resumeToken` di `sessionStorage`).

### 5.5 Kesamaan protokol Kotlin ↔ TypeScript

- Tipe ditulis ulang di `web/src/protocol.ts`, dengan fungsi `parseServerMessage` yang memvalidasi bentuk pesan.
- Test Vitest membaca `modules/jam-protocol/fixtures/*.json` (golden files dari 4.2). Setiap file
  harus lolos parse, dan serialisasi pesan klien di TS harus sama dengan fixture-nya.
  Perubahan protokol yang hanya dilakukan di satu sisi membuat CI gagal.

### 5.6 Testing web

- Vitest: parser protokol (golden files), `ClockSync`, aturan seek drift, reducer state (versi lama diabaikan).
- Playwright (1 skenario): server berjalan di test, 2 tab gabung room yang sama, tab A menambah lagu,
  lagu itu muncul di tab B. IFrame Player di-mock.

---

## 6. Urutan pengerjaan

1. `:modules:jam-protocol` + golden files
2. `server/`: room, WebSocket, CommandHandler, Reaper, search, Docker
3. `web/`: web pendamping (bisa diuji end-to-end dengan beberapa tab, tanpa build Android)
4. Integrasi Android: JamClient/Session/Bridge, JamQueue, UI, deep link, Pengaturan
5. CI + README (privasi & cara self-host server)
