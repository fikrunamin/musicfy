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
