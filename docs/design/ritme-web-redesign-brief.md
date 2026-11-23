# Brief desain: Ritme Web

Brief ini untuk desainer atau agen desain. Kamu tidak perlu paham kodenya. Semua yang dibutuhkan
untuk mendesain ada di sini: produknya apa, siapa penggunanya, layar apa saja, isi teks, gaya
visual, dan aturan yang tidak boleh dilanggar.

---

## 1. Produknya apa

**Musicfy** adalah aplikasi pemutar musik (Android, nanti juga desktop). **Ritme** adalah fitur
**dengar musik bareng**, mirip Spotify Jam:

- Satu orang membuat **room** dan mendapat **kode 6 karakter** (contoh: `K7M2QX`) serta link
  undangan.
- Teman-temannya bergabung lewat link itu dan bisa melihat **antrean lagu bersama**, menambah lagu,
  dan (kalau diizinkan host) mengatur putar/jeda/lompat lagu.
- Semua orang mendengar lagu yang sama **di detik yang sama** di HP masing-masing.

**Ritme Web** adalah versi browser dari fitur ini. Teman yang tidak punya aplikasi Musicfy tinggal
membuka link di browser HP, tanpa instal apa pun. Web sudah berjalan dan semua fiturnya sudah
berfungsi. **Yang dicari sekarang adalah tampilan baru.** Tampilan sekarang terlalu polos: hanya satu
kartu form abu-abu di tengah layar, dan tidak terasa seperti aplikasi musik.

## 2. Siapa penggunanya dan dalam situasi apa

- Kebanyakan membuka dari **HP**, sering mendadak: dapat link di grup chat, lalu ketuk dan
  langsung masuk. Desktop atau laptop jadi prioritas kedua.
- Situasinya sosial: nongkrong, di mobil, kerja bareng, pesta kecil, atau teman yang tinggal jauh.
- Mereka ingin cepat: isi nama, gabung, lihat lagu apa yang sedang diputar, tambah lagu favorit.
- Bahasa antarmuka: **Indonesia**.

## 3. Gaya visual (wajib mengikuti aplikasi Musicfy)

Ritme Web harus terasa satu keluarga dengan aplikasi Musicfy.

**Warna: monokrom.** Aplikasi Musicfy hitam-putih. Warna yang "hidup" hanya datang dari **gambar
cover/artwork lagu**.

| Peran | Tema gelap (utama) | Tema terang |
|---|---|---|
| Latar | `#000000` | `#FFFFFF` |
| Permukaan/kartu | `#1A1A1A` | `#F0F0F0` |
| Permukaan kedua | `#2C2C2C` / `#333333` | `#E0E0E0` / `#CCCCCC` |
| Teks utama | `#FFFFFF` | `#000000` |
| Teks sekunder | putih 60% | hitam 60% |
| Aksen/tombol utama | `#E0E0E0` (teks hitam) | `#000000` (teks putih) |

- Desain tema **gelap dan terang**, karena web mengikuti pengaturan HP.
- Untuk suasana: pakai **cover lagu yang sedang diputar sebagai latar yang di-blur dan digelapkan**,
  supaya warna layar ikut berganti setiap lagu berganti. Jangan menambah warna brand baru.

**Huruf:** **Inter, SemiBold untuk semua teks**, jarak antarhuruf sedikit rapat (sekitar −0.05em).
Bedakan judul dan teks biasa lewat **ukuran**, bukan ketebalan.

**Bentuk dan komponen:**
- **Tombol:** bentuk pill (sudut membulat penuh). Tombol utama terang di tema gelap.
- **Kartu dan panel:** sudut membulat besar.
- **Panel tambahan di HP** (anggota, cari lagu, menu): muncul sebagai **sheet dari bawah**.
- **Baris lagu di antrean**: tinggi sekitar 60 px. Gambar cover 44 × 44 px dengan sudut 8 px. Judul
  15 px, nama artis 13 px dengan warna 60%.
- **Ikon:** gaya Material Symbols (garis membulat, sederhana).

**Motif khas Musicfy (opsional, sebagai aksen kecil):** piringan hitam (vinyl), lengan turntable,
cover yang berganti dengan halus. Cocok untuk ilustrasi di halaman depan atau saat menunggu.

**Suasana yang dituju:** tenang, premium, sosial, dan musik jadi fokus.

**Hindari:**
- kartu form generik di tengah layar kosong;
- gradien neon warna-warni;
- efek kaca (glassmorphism) yang berlebihan;
- ilustrasi stok;
- warna brand acak.

## 4. Aturan yang tidak boleh dilanggar

1. **Pemutar video YouTube harus terlihat** di layar saat mode Dengar aktif. Ini aturan YouTube:
   lagu diputar lewat pemutar YouTube resmi. Ukurannya boleh kecil, tapi tinggi minimal 200 px,
   dan tidak boleh disembunyikan atau ditutup elemen lain. Jadikan bagian desain yang rapi,
   misalnya kartu video di bawah atau di samping cover.
2. **Mudah disentuh:** semua tombol dan elemen yang bisa diketuk minimal 44 × 44 px. Jarak tepi
   layar HP 16 px.
3. **Kontras cukup** untuk teks di atas latar blur. Tambahkan overlay gelap agar teks tetap
   terbaca di atas cover yang terang.
4. **Font hanya Inter** (sudah tersedia). Jangan memakai font lain.
5. **Status terkunci harus jelas tapi sopan:** kalau host mengunci kontrol, tombol putar/lompat
   tampil redup, dan saat diketuk muncul pesan "Host mengunci kontrol".
6. Dukung layar **HP 360–430 px** (desain di 390 × 844) dan **desktop 1440 × 900**.

## 5. Layar yang perlu didesain

Gunakan teks Indonesia persis seperti di bawah. Tanda kutip berisi teks asli aplikasi.

### A. Halaman depan
Untuk orang yang membuka `ritme.dubeli.id` langsung.
- Nama "Ritme" dan kalimat: "Dengar musik bareng teman, sinkron di semua perangkat."
- Kolom "Nama tampilan" (contoh isi: "Misalnya Budi").
- Tombol utama **"Mulai Ritme"**.
- Pemisah "atau".
- Kolom "Kode room" (6 huruf/angka, contoh "K7M2QX") dan tombol **"Gabung"**.
- Pesan salah: "Nama harus 1–32 karakter." dan "Kode room terdiri dari 6 huruf/angka."
- Saran: area visual atau ilustrasi bertema piringan atau musik supaya tidak terasa seperti form.

### B. Halaman undangan
Untuk orang yang mengetuk link `ritme.dubeli.id/r/K7M2QX`.
- Judul **"Kamu diajak dengar bareng"**, kode room sudah terisi.
- Kolom nama dan tombol **"Gabung"**.
- Bagian kedua: tombol **"Buka di aplikasi Musicfy"** dan teks "Belum punya Musicfy?
  **Unduh di GitHub**".

### C. Room: mode Dengar (layar utama)
- **Header:** "Ritme · K7M2QX", jumlah anggota ("3 anggota") sebagai tumpukan avatar inisial,
  tombol **"Bagikan"**. Ada indikator kecil bahwa semua tersinkron.
- **Pilihan mode:** "Mode dengar" / "Mode remote", dengan keterangan "Mode remote: kontrol saja,
  tanpa suara di perangkat ini."
- **Sebelum mulai:** tombol besar **"Ketuk untuk ikut dengar"**. Browser butuh satu ketukan
  sebelum boleh memutar suara.
- **Sedang diputar:** label "Sedang diputar", **cover besar**, judul lagu, nama artis, "oleh Budi"
  (siapa yang menambahkan), bar progres dengan waktu, dan tombol Sebelumnya / Putar–Jeda /
  Berikutnya.
- **Pemutar YouTube** yang terlihat (lihat aturan 1).
- Tips kecil: "Biarkan layar menyala supaya musik tidak berhenti."
- **Kosong:** "Belum ada lagu. Tambahkan lagu untuk mulai."
- **Siaran langsung:** lencana "LIVE" tanpa bar progres.

### D. Room: mode Remote
Sama seperti C, tapi **tanpa pemutar video dan tanpa suara**. Lebih ringkas, fokus ke kontrol dan
antrean, cocok untuk orang yang hanya mengatur lagu dari HP lain.

### E. Antrean
- Bagian **"Sedang diputar"** (disorot), **"Berikutnya"**, dan **"Sudah diputar (5)"** (bisa dilipat).
- Setiap baris: cover, judul, "Artis · oleh Nama".
- Aksi per baris (muncul sesuai izin): **"Putar sekarang"**, **"Naikkan"**, **"Turunkan"**,
  **"Hapus"**.
- Lagu yang tidak bisa diputar diberi tanda: "Tidak bisa diputar di web" atau "Tidak didukung di
  versi ini".
- Kosong: "Antrean kosong."
- Tombol **"Tambah lagu"**.

### F. Cari dan tambah lagu
Di HP berupa sheet dari bawah; di desktop berupa panel.
- Kolom "Cari lagu" (placeholder "Judul atau artis") dan tombol "Cari".
- Status: "Mencari…", "Tidak ada hasil.", "Server pencarian sedang sibuk, coba lagi nanti."
- Hasil: cover kecil, judul, artis, durasi (atau "LIVE"), tombol **"Tambah"** dan opsi
  **"Putar berikutnya"**.

### G. Anggota dan pengaturan host
- Judul "Anggota". Daftar nama dengan avatar inisial, label "Host", "(kamu)", dan status
  "terputus" untuk yang sedang offline.
- Khusus host:
  - sakelar **"Anggota boleh kontrol"**;
  - per anggota: **"Jadikan host"** dan **"Keluarkan"** (dengan konfirmasi "Keluarkan Budi dari
    Ritme?");
  - **"Akhiri Ritme untuk semua"** (dengan konfirmasi "Akhiri Ritme untuk semua anggota?"). Tombol
    ini merah atau berbahaya.
- Semua orang: **"Keluar Ritme"**.
- Bagikan: kode room besar plus link. Notifikasi "Link disalin".

### H. Status dan pesan
Buat versi kecil untuk:
- "Menghubungkan…" (layar penuh, sebelum masuk room);
- "Menyambung ulang…" (banner tipis di atas, room masih terlihat);
- "Terputus dari Ritme" + tombol "Coba lagi" / "Kembali";
- room diakhiri host, dikeluarkan dari room, dan "perlu versi baru";
- toast singkat di atas layar: "Host mengunci kontrol", "Antrean penuh", "Terlalu cepat, coba lagi
  sebentar", "Terjadi kesalahan, coba lagi".

## 6. Tata letak

- **HP (390 × 844):** satu kolom. Header, lalu "Sedang diputar" (cover besar dan kontrol), lalu
  pemutar YouTube, lalu antrean di bawah (bisa digulir). Anggota dan Cari dibuka sebagai sheet dari
  bawah.
- **Desktop (1440 × 900):** manfaatkan lebar layar, jangan satu kartu kecil di tengah. Usulan tiga
  area: **kiri** cover besar, kontrol dan pemutar YouTube; **tengah** antrean; **kanan** anggota dan
  cari. Latar penuh dengan blur cover.

## 7. Gerak dan animasi (untuk catatan prototipe)

- Saat lagu berganti: cover dan latar blur **crossfade** dengan halus.
- Sheet naik dari bawah dengan lembut. Panel desktop muncul tanpa efek berlebihan.
- Lagu yang sedang diputar ditandai **bar equalizer kecil** yang bergerak.
- Indikator sinkron berupa titik kecil yang berdenyut pelan.
- Sediakan versi tanpa animasi untuk pengguna yang mematikan animasi di HP.

## 8. Yang diharapkan dikirim

1. Desain layar **A–H**, tema **gelap dan terang**, ukuran **HP 390 × 844**. Untuk layar A, C dan E
   juga ukuran **desktop 1440 × 900**.
2. Variasi status penting: kontrol terkunci, antrean kosong, menyambung ulang, terputus, lagu LIVE,
   lagu tidak bisa diputar.
3. Komponen yang dipakai ulang: tombol (utama, kedua, bahaya), kolom input, baris lagu, avatar
   anggota, sheet, toast, lencana (Host, LIVE).
4. Catatan singkat: warna, ukuran huruf, jarak, dan sudut yang dipakai, supaya mudah dibuat jadi
   kode.

## 9. Prompt siap tempel (untuk AI desain)

```
Design a mobile-first web app UI called "Ritme" — a "listen together" companion for the music app
Musicfy (like Spotify Jam). Friends open an invite link in their phone browser, join a room with a
6-character code, see a shared song queue, add songs, and listen in sync.

Screens (Indonesian UI copy): (A) landing — tagline "Dengar musik bareng teman, sinkron di semua
perangkat.", field "Nama tampilan", primary pill button "Mulai Ritme", divider "atau", field
"Kode room" and button "Gabung"; (B) invite page "Kamu diajak dengar bareng" with "Buka di aplikasi
Musicfy"; (C) room listen screen — header "Ritme · K7M2QX" with stacked member avatars, share
button and a calm sync indicator, a big "Ketuk untuk ikut dengar" button before starting, large
album artwork, title/artist "oleh Budi", progress bar, previous/play-pause/next, and a VISIBLE small
YouTube video player (min 200px tall, never hidden); (D) remote mode without the video; (E) shared
queue with sections "Sedang diputar", "Berikutnya", "Sudah diputar" and per-row actions;
(F) search & add songs in a bottom sheet; (G) members & host controls in a bottom sheet (toggle
"Anggota boleh kontrol", "Jadikan host", "Keluarkan", red "Akhiri Ritme untuk semua");
(H) connection states and toasts.

Visual language: strict monochrome — dark theme pure black #000 with greys #1A1A1A/#2C2C2C/#333
and #E0E0E0 accents, light theme white #FFF with #F0F0F0/#E0E0E0; colour comes ONLY from album
artwork (blurred artwork backdrop with dark overlay that changes per song). Inter SemiBold for all
text with tight letter-spacing (-0.05em); hierarchy by size, not weight. Pill buttons, large corner
radii, bottom sheets, 60px queue rows with 44px rounded (8px) thumbnails, Material Symbols rounded
icons. Subtle vinyl/turntable motif as a small brand accent. Calm, premium, social, music-first.
Min 44px touch targets. Show dark and light, iPhone 390×844, and desktop 1440×900 with three areas
(artwork + controls + video | queue | members & search). Avoid generic centered form cards, neon
gradients, heavy glassmorphism and stock illustrations.
```

## 10. Kata kunci Pinterest

**Inti:**
- music player app ui dark
- monochrome music app design
- listen together app ui
- spotify jam ui
- shared queue ui
- now playing screen design
- album art blurred background ui
- minimal music web app
- black and white app ui
- inter font ui design
- bottom sheet mobile ui
- music app mobile dribbble

**Gaya dan suasana:**
- premium minimal ui dark
- editorial music interface
- vinyl turntable ui
- record player app design
- apple music now playing ui
- monochrome dashboard ui
- glassmorphism music player (untuk referensi saja, jangan berlebihan)

**Komponen spesifik:**
- queue list ui mobile
- music search results ui
- avatar stack ui
- room code input ui
- join room screen design
- invite link landing page
- party mode music app
- collaborative playlist ui
- empty state music app
- reconnecting state ui
- toast notification mobile ui

**Bahasa Indonesia:**
- desain aplikasi musik minimalis
- ui pemutar musik gelap
- desain aplikasi dengar bareng
