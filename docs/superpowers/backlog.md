# Backlog (catatan, belum dijadwalkan)

## 2026-11-23 — Sumber update dan pengaturan server Ritme yang bisa diubah

Permintaan pengguna: repo ini adalah fork (`fikrunamin/musicfy`), tetapi updater masih mengarah ke
repo aslinya (`musicfy-app/musicfy`).

### 1. Pengaturan "Sumber update" (mobile dan desktop)

- Tambah halaman atau entri pengaturan berisi URL repo GitHub tempat mengambil installer update
  (default: `https://github.com/fikrunamin/musicfy`), dengan tombol "Kembalikan default".
- Tempat di app Android yang saat ini masih hardcode `musicfy-app/musicfy` (cek ulang sebelum
  dikerjakan):
  - `app/src/main/kotlin/com/example/musicfy/core/updater/GithubUpdates.kt:25`
    (`$GithubOwner/$GithubRepo`)
  - `app/src/main/kotlin/com/example/musicfy/core/updater/musicfyupdater.kt:329, 649, 661, 773`
    (APK release, daftar release, run workflow nightly, changelog.json)
  - `app/src/main/kotlin/com/example/musicfy/core/changelog/changelogscreen.kt:144, 217`
  - `app/src/main/kotlin/com/example/musicfy/core/commitscreen/commitscreen.kt:110`
- Semua URL di atas sebaiknya diturunkan dari satu nilai owner/repo, dengan key DataStore baru di
  `core:settings`. Golden test preference key harus ikut diperbarui.
- Nama aset release harus cocok dengan yang dihasilkan workflow fork ini (APK, dan nanti
  DMG/EXE/MSI/DEB dengan tag `desktop-vX.Y.Z`).
- Desktop: masuk ke D3 (layar Pengaturan) dan D4 (auto-update, §4.7 spec D2–D4). Pakai key yang
  sama di `core:settings` agar nilainya konsisten.

### 2. Pengaturan server Ritme: URL + key/token (mobile dan desktop)

- Rencana Ritme Android (Task 13) sudah memuat URL server dengan "Kembalikan default"
  (`https://ritme.dubeli.id`). Perlu ditambah field **key/token akses**, disimpan aman (jangan
  ikut ter-log, tampil tersamarkan).
- **Butuh perubahan server, karena saat ini server tidak punya autentikasi.** Usulan: env
  opsional `RITME_ACCESS_TOKEN`. Kalau di-set, `/ritme` (WebSocket) dan `/api/search` menolak
  klien tanpa token yang cocok. Token dikirim lewat header (`Authorization: Bearer …`) atau
  subprotocol, bukan query string, supaya tidak tercatat di log proxy. Perbandingan token harus
  constant-time. Kalau env kosong, perilaku tetap seperti sekarang (terbuka).
- Web pendamping: browser tidak bisa memasang header custom pada WebSocket. Pilihannya token
  lewat `Sec-WebSocket-Protocol`, atau web tetap terbuka untuk join via link sementara pembuatan
  room butuh token. Putuskan saat spec.
- Desktop: field yang sama di layar Pengaturan D3, saat Ritme hadir di desktop.
- Pakai ulang aturan cleanup dan kebijakan log privasi yang sudah ada di server.

## 2026-11-23: Nonton bareng (video) dan izin menambah ke antrean

Permintaan pengguna (catatan, belum dijadwalkan; Android Ritme diselesaikan dulu).

### 3. Tambah video dari klien (sub-proyek #2 Ritme)

- Kondisi sekarang:
  - Protokol, server, dan pemutar web sudah mendukung `YOUTUBE_VIDEO` dan `URL` (web memutar
    video YouTube di pemutar besar yang tersinkron, dan URL lewat `<video>` atau hls.js).
  - **Belum ada klien yang bisa menambahkan entri video.** Pencarian `/api/search` hanya lagu.
  - Android sub-proyek #1 memutar `YOUTUBE_VIDEO` sebagai audio saja.
  - Ritme belum ada di desktop.
- Usulan untuk web (sekitar 2–3 jam):
  - tombol "Tambah video": tempel link YouTube atau link mp4/m3u8;
  - endpoint server baru, misalnya `/api/video?id=`, untuk judul dan durasi dari ID YouTube, supaya
    browser tidak memanggil pihak ketiga baru;
  - tampilan room yang menyesuaikan saat entri video sedang diputar.
- Android: pemutar video tersinkron dan tombol tambah video (sub-proyek #2).
- Desktop: setelah D2–D3, Ritme masuk desktop (seam di spec D2–D4 §1.5).
- Browser in-app dengan deteksi video/stream (sub-proyek #3), khusus Android dan desktop.

### 4. Izin menambah ke antrean

- Kondisi sekarang: **semua anggota** boleh `Add` (lagu, video, atau URL). "Anggota boleh kontrol"
  hanya membatasi putar, jeda, lompat, seek, dan urutan.
- Usulan: setelan room baru "Anggota boleh menambah lagu" (`guestsCanAdd`, default true). Di server,
  `Add` dicek seperti `control{}` saat setelan mati. Protokol: field baru di `RoomSettings` dengan
  default true supaya klien lama tetap jalan (tolerant decoding). UI di web, Android, dan desktop
  ikut menampilkan sakelar dan menyembunyikan tombol tambah. Opsional: setelan terpisah khusus
  video.

## 2026-11-23: Installer desktop di GitHub Actions ditunda

Keputusan pengguna: job packaging DMG/MSI di `desktop.yml` **baru dibuat setelah** aplikasi desktop
bisa memutar lagu (D2) **dan** mendukung Ritme: dengar bareng musik, nonton video YouTube, dan
video URL. Sampai saat itu, DMG hanya dibuat manual untuk percobaan
(`./gradlew :desktopApp:packageDmg -Pcompose.desktop.packaging.checkJdkVendor=false`).
- Tambahan (2026-11-23): aplikasi desktop juga **wajib punya browser in-app untuk mencari video**,
  seperti #3 di Android: address bar dengan input URL manual, deteksi video/stream di halaman, dan
  konfirmasi sebelum "Tambah ke Ritme". Implementasinya memakai JCEF (sudah direncanakan di D2 untuk
  BotGuard) di atas `:modules:ritme-media` dari #3. Dikerjakan bersama Ritme desktop, sebelum
  installer CI.

## 2026-11-23: Follow-up kecil Ritme Android (setelah merge batch perbaikan)

- Kalau host mematikan "Anggota boleh menambah" saat daftar panjang milik tamu masih dikirim,
  batch-batch sisanya tetap terkirim lalu ditolak satu per satu. Akibatnya toast "Host menutup
  penambahan lagu" bisa muncul 2x. Usulan: buang sisa daftar setelah balasan FORBIDDEN pertama.
- Di room dengan "Anggota boleh menambah" mati, tombol Putar/Acak di halaman playlist/album tetap
  terlihat dan hanya memunculkan toast. Pertimbangkan menyembunyikan atau meredupkannya untuk tamu.
- Flaky test `AbuseLimitsTest.anInboundFrameFloodClosesTheSocket`: sudah diperbaiki di main lewat
  #2a (9743293). Pantau saja.
