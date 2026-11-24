# Ritme web redesign (#2c): daftar cek manual

Daftar ini untuk dicek sendiri sebelum redesign dirilis. Tes otomatis (Vitest, e2e, screenshot)
sudah dijalankan, tapi tidak bisa memakai YouTube asli atau HP sungguhan. Centang setiap butir.
Kalau ada yang gagal, catat perangkat, browser, langkah, dan screenshot-nya.

## Persiapan

- [ ] Pakai build dari branch `feature/ritme-web-redesign`: `cd ritme-web && npm ci && npm run build`,
  lalu jalankan server seperti di `ritme-web/README.md`. Atau pakai server uji yang disiapkan controller.
- [ ] Dari HP, buka server itu lewat jaringan yang sama (alamat IP Mac, bukan `localhost`).
  Di halaman `http://` tombol "Tempel dari papan klip" memang tidak muncul, karena browser hanya
  mengizinkan baca papan klip di halaman aman (https). Itu bukan bug.
- [ ] Siapkan minimal tiga peserta: host (desktop), tamu Chrome Android, tamu Safari iPhone.
- [ ] Antrean berisi beberapa lagu YouTube, satu video YouTube, dan satu link mp4.

## 1. YouTube asli di Chrome Android

- [ ] Mode dengar: sebelum ketukan pertama, slot artwork menampilkan cover dengan tulisan
  "Ketuk untuk ikut dengar".
- [ ] Setelah diketuk, pemutar YouTube resmi muncul di slot artwork dan suara terdengar sinkron
  dengan host.
- [ ] Pemutar terlihat penuh dengan lebar dan tinggi minimal 200 px.
- [ ] Tidak ada apa pun yang menempel di atas pemutar: tidak ada label, ikon, gradien, gerbang
  ketuk, atau toast yang menetap di sana. Sheet atau toast yang kamu buka sendiri boleh menutupi
  sebentar, lalu hilang.
- [ ] Putar, jeda, maju/mundur 10 detik, dan "Berikutnya" bekerja (kalau host mengizinkan kontrol).
- [ ] Ganti ke mode remote: slot hanya menampilkan cover atau thumbnail, tanpa pemutar dan tanpa
  suara di HP ini. Kembali ke mode dengar: pemutar muncul lagi dan menyusul posisi room (ketuk lagi
  kalau browser memintanya).
- [ ] Putar HP ke landscape lalu kembali ke portrait: pemutar tetap jalan.
- [ ] Untuk video di mode dengar, tombol layar penuh ada di samping kontrol dan berfungsi. Di layar penuh, toast dan petunjuk ketuk
  muncul di bilah samping, bukan di atas video.

## 2. YouTube asli di Safari iPhone

- [ ] Sebelum ketukan pertama tampil cover dengan "Ketuk untuk ikut dengar". Setelah diketuk,
  suara terdengar (Safari butuh ketukan ini).
- [ ] Pemutar terlihat, minimal 200 px, dan tidak tertutup apa pun (seperti di Android).
- [ ] Tombol layar penuh **tidak ada** di iPhone (Safari iPhone tidak mendukung layar penuh untuk
  elemen halaman). Tidak ada tombol mati yang tersisa di tempatnya.
- [ ] Kunci layar sebentar lalu buka lagi: pemutar menyusul posisi room.
- [ ] Teks tidak terpotong oleh notch atau home indicator (safe area).

## 3. Gabung di tengah lagu dan muat ulang (web)

Dulu rusak: semua entri jadi "Tidak bisa diputar di web". Sudah diperbaiki, cek bahwa tetap benar.

- [ ] Saat video YouTube sedang di tengah, muat ulang browser tamu. Tamu kembali ke room yang sama,
  lalu lanjut di dekat posisi room.
- [ ] Saat lagu sedang di tengah, gabung dari browser ketiga. Lagu mulai di dekat posisi room.
- [ ] Di kedua kasus tidak ada entri yang ditandai "Tidak bisa diputar di web".
- [ ] Setelah itu jeda, lanjutkan, dan "Berikutnya" ke video lain tetap sinkron. Entri LIVE
  (misalnya `jfKfPfyJRdk`) tampil sebagai LIVE dan ikut putar/jeda.

## 4. Desktop 1366 × 768 dan 1440 × 900

- [ ] 1440 × 900: tiga kolom (panggung, antrean lengkap, tambah dan anggota). Tombol putar/jeda dan
  bilah posisi terlihat tanpa menggulir.
- [ ] 1366 × 768: sama, kontrol pemutaran tetap terlihat tanpa menggulir. Pemutar tetap minimal 200 px.
- [ ] Dengan video yang sedang diputar, tampilan teater muncul (panggung lebar 16:9 dan kolom kanan
  340 px). Tombol lipat menyembunyikan dan memunculkan kolom kanan.
- [ ] Ubah ukuran jendela melewati 1024 px bolak-balik saat YouTube diputar. Pemutar tidak hilang
  dan suara tidak terputus.

## 5. Mode terang dan gelap

- [ ] Ganti tema sistem (terang/gelap) saat room terbuka. Semua layar mengikuti, termasuk home,
  undangan, room, sheet, dialog, toast, dan layar status.
- [ ] Teks terbaca di kedua tema. Tidak ada teks putih di atas putih atau hitam di atas hitam.

## 6. Gerak dikurangi (reduced motion)

Aktifkan: macOS Pengaturan Sistem › Aksesibilitas › Tampilan › Kurangi gerakan; iPhone
Aksesibilitas › Gerakan › Kurangi Gerakan; Android Aksesibilitas › Hapus animasi.

- [ ] Tidak ada yang bergerak: sheet langsung muncul, crossfade cover tidak ada, equalizer diam,
  titik sinkron tidak berdenyut, cincin "Menghubungkan…" diam, dan teks marquee tidak berjalan.
- [ ] Semua fungsi tetap jalan tanpa animasi.

## 7. Hanya keyboard

- [ ] Dengan Tab saja, semua kontrol bisa dicapai di home, room, sheet tambah, dan sheet anggota.
  Cincin fokus selalu terlihat.
- [ ] Tab Lagu/Video: panah kiri/kanan, Home, dan End berpindah tab. Alt/Ctrl/Cmd + panah tetap
  dipakai browser (misalnya kembali ke halaman sebelumnya).
- [ ] Escape menutup sheet atau dialog, lalu fokus kembali ke tombol yang membukanya. Tab tidak bisa
  keluar dari sheet yang terbuka.
- [ ] Saat menghubungkan, saat terputus, dan setelah "Kembali", fokus pindah ke judul layar yang baru
  (pembaca layar langsung membacakannya).
- [ ] Setelah server tidak bisa dihubungi, "Kembali" membawa ke home dengan kode room yang tadi
  diketik masih terisi.

## 8. Tambah video: link YouTube dan link mp4

- [ ] Tab Video: tempel link YouTube (misalnya `https://youtu.be/aqz-KE-bpKQ`), tekan "Pratinjau".
  Kartu pratinjau menampilkan thumbnail 16:9, lencana Video, durasi, dan "YouTube · channel · durasi".
  Tambahkan; video masuk antrean.
- [ ] Tempel link mp4 https (misalnya `https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4`),
  tekan "Pratinjau". Kartu menampilkan domain, "MP4 · durasi", dan kolom "Judul (opsional)".
  Tambahkan; video bisa diputar di web dan di Android.
- [ ] Link yang tidak dikenali menampilkan pesan error di bawah kolom dan petunjuk.
- [ ] (https saja) Tombol tempel dengan papan klip kosong menampilkan "Papan klip kosong. Salin link
  videonya dulu."; kalau izin ditolak, muncul "Papan klip tidak bisa dibaca…".
- [ ] Tidak ada pratinjau yang dimulai sendiri saat mengetik atau menempel, hanya lewat "Pratinjau"
  atau Enter.

## 9. Keluarkan anggota, akhiri Ritme, pindah host

- [ ] Host membuka Anggota lalu menekan "Keluarkan" pada seorang tamu, kemudian konfirmasi. Tamu itu
  melihat layar "Kamu dikeluarkan dari Ritme", dan "Kembali" membawanya ke home.
- [ ] Host memindahkan host (ikon bintang) ke tamu yang tersambung. Lencana Host pindah, dan kontrol
  host (sakelar, Keluarkan, Akhiri) sekarang muncul di tamu itu. Bintang mati untuk anggota yang
  "terputus".
- [ ] Host baru menekan "Akhiri Ritme untuk semua", lalu "Akhiri". Semua anggota melihat
  "Ritme sudah diakhiri".
- [ ] Sakelar "Anggota boleh kontrol" dan "Anggota boleh menambah" langsung berlaku di tamu
  (kontrol terkunci memunculkan toast "Host mengunci kontrol"; "Tambah lagu" hilang).

## 10. Setelah disetujui: deploy

- [ ] Kamu merge `feature/ritme-web-redesign` ke `main` dan push. Workflow "Ritme server" lalu
  menerbitkan image `ghcr.io/fikrunamin/ritme-server` (web ikut di dalam image server).
- [ ] Deploy ke `ritme.dubeli.id` dikerjakan controller, dengan tag image yang dipin ke commit yang
  disetujui (bukan sekadar `latest`). Perintah dasarnya:

  ```bash
  cd /opt/ritme && docker compose pull && docker compose up -d
  ```

  Controller menjalankannya dengan `RITME_TAG=<sha commit>`, mencatat image yang sedang jalan untuk
  rollback, dan memastikan tidak ada room aktif sebelum restart.
- [ ] Setelah deploy, buka `https://ritme.dubeli.id` dan ulangi cepat butir 1, 2, dan 8 dengan
  https (termasuk tombol tempel dari papan klip).
