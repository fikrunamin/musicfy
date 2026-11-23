# Ritme Web: brief redesign

Status: catatan untuk nanti, belum dikerjakan (2026-11-23). Web yang sekarang sudah live di
https://ritme.dubeli.id. Fungsinya lengkap, tapi tampilannya hambar: satu kartu form di tengah
layar, tanpa identitas Musicfy.

## 1. Konteks singkat

- **Ritme** adalah fitur dengar bareng di Musicfy, mirip Spotify Jam. Teman membuka link
  `ritme.dubeli.id/r/{kode}` di browser HP tanpa instal apa pun. Di sana mereka bisa melihat dan
  mengedit antrean bersama, mengontrol putaran (sesuai izin host), dan ikut mendengar secara
  tersinkron lewat pemutar YouTube. Mode lain: jadi remote saja.
- Pengguna utamanya membuka di **HP**, sering sambil nongkrong atau di mobil. Desktop nomor dua.
- Kode: `ritme-web/` (Preact + Vite + TypeScript). UI ada di `ritme-web/src/ui/*`, logika di
  `src/{protocol,sync,net,session,player}`. **Redesign hanya menyentuh lapisan UI dan CSS**;
  logika dan 248 tesnya tidak diubah.

## 2. Identitas visual Musicfy yang harus diikuti

Diambil dari tema aplikasi Android (`core/designsystem`):

| Token | Nilai |
|---|---|
| Palet | **Monokrom**. Gelap: background `#000000`, surfaceVariant `#1A1A1A`, primary `#E0E0E0`, container `#333333` / `#2C2C2C`. Terang: background `#FFFFFF`, surfaceVariant `#F0F0F0`, primary `#000000`, container `#E0E0E0` / `#CCCCCC`. |
| Warna "hidup" | Warna aksen **datang dari artwork lagu** (cover besar, blur, rona), bukan dari warna brand statis. |
| Tipografi | **Inter, SemiBold di semua teks**, letter-spacing rapat (−0.05em). Hierarki dibentuk lewat ukuran, bukan ketebalan. |
| Bentuk | Tombol pill, kartu radius besar, sheet dari bawah (gaya PopupSheet di aplikasi). |
| Motif khas aplikasi | Cover yang "morphing", carousel piringan (disc), lengan turntable. Bisa jadi elemen visual kecil yang dikenali. |
| Baris antrean (acuan QueueSongRow) | Tinggi baris 60 dp, art 44 dp dengan radius 8 dp, judul 15/19 SemiBold, subjudul 13/17 dengan opacity sekitar 0.6. |

Artinya, abu-abu yang ada sekarang sebenarnya sudah sesuai palet. Yang kurang adalah **komposisi,
skala, artwork, dan rasa "aplikasi musik"**.

## 3. Batasan teknis (wajib)

- **Privasi dan CSP:** tanpa CDN, tanpa analytics, tanpa web font dari luar. **Inter harus
  di-self-host** (file woff2 di repo). Pihak ketiga yang boleh hanya YouTube IFrame API, embed
  `youtube-nocookie.com`, thumbnail (dengan `referrerpolicy="no-referrer"`), dan media dari entri URL.
- Pemutar YouTube **harus terlihat** (aturan YouTube); tinggi minimal 200 px untuk pemutar kecil.
- Mobile-first, gutter 16 px, target sentuh ≥ 44 px, fokus terlihat, toast pakai `aria-live`,
  terang/gelap mengikuti `prefers-color-scheme`. Semua copy berbahasa Indonesia di `src/ui/strings.ts`.
- Bundle tetap kecil: tanpa library UI berat, cukup CSS sendiri.

## 4. Layar yang perlu didesain

1. **Beranda (`/`)**: hero singkat (apa itu Ritme), input nama, tombol "Mulai Ritme", atau gabung
   dengan kode 6 karakter.
2. **Undangan (`/r/{kode}`)**: "Kamu diajak dengar bareng", nama dan kode sudah terisi, tombol
   Gabung, plus "Buka di aplikasi Musicfy" dan link unduh.
3. **Room, mode Dengar**:
   - gerbang "Ketuk untuk ikut dengar";
   - pemutar YouTube yang terlihat;
   - now playing dengan artwork besar, judul, artis dan progres;
   - kontrol play/pause, prev/next dan seek (redup atau terkunci kalau tidak punya izin).
4. **Room, mode Remote**: versi ringkas tanpa pemutar, fokus pada kontrol dan antrean.
5. **Antrean**: sedang diputar, berikutnya, sudah diputar. Pakai aksi per baris (hapus, naik/turun,
   putar sekarang) yang muncul sesuai izin. Entri yang tidak didukung atau tidak bisa diputar di web
   diberi tanda.
6. **Cari dan tambah lagu**: kotak cari (submit-only), hasil dengan thumbnail, tombol Tambah, status
   429/503.
7. **Anggota dan host**: daftar anggota (online/terputus, host), aksi host (kunci kontrol, kick,
   pindah host, akhiri room), bagikan link (Web Share/clipboard) dan kode besar.
8. **Status**: menghubungkan, menyambung ulang, terputus ("Terputus dari Ritme"), room berakhir,
   dikeluarkan, butuh versi baru, antrean penuh, dan toast.

Breakpoint:
- **≤ 480 px**: satu kolom. Now playing di atas, antrean di bawah, anggota dan cari lewat sheet.
- **≥ 1024 px**: dua atau tiga kolom. Now playing dengan artwork besar di kiri, antrean di tengah atau
  kanan, anggota di samping.

## 5. Arah desain

- **Artwork-first.** Cover lagu yang sedang diputar jadi pusat. Latar ikut berubah: blur besar dari
  cover dengan overlay gelap, transisi halus antar lagu.
- **Monokrom elegan.** Hitam pekat atau putih bersih, teks SemiBold rapat. Warna hanya dari artwork.
- **Rasa sosial.** Avatar inisial anggota yang menumpuk, tanda siapa yang menambahkan lagu, indikator
  "sinkron" yang tenang (misalnya titik berdenyut kecil).
- **Motion halus.** Sheet naik dari bawah, crossfade cover, bar equalizer kecil di lagu yang sedang
  diputar. Hormati `prefers-reduced-motion`.
- **Elemen kecil yang khas Musicfy.** Piringan atau turntable sebagai ilustrasi di beranda dan saat
  menunggu.
- **Hindari:** kartu form generik di tengah layar, gradien neon acak, glassmorphism berlebihan,
  ilustrasi stok.

## 6. Prompt siap pakai

### 6a. Untuk AI pembuat gambar dan konsep UI (Midjourney, v0, Galileo, Stitch, dll.)

```
Design a mobile-first web app UI called "Ritme" — a "listen together" companion for the music app
Musicfy (like Spotify Jam). Screens: (1) landing with a short hero, a name field, a primary pill
button "Mulai Ritme" and a 6-character room-code join field; (2) invite page "Kamu diajak dengar
bareng"; (3) room "listen" screen with a large album artwork, song title/artist, progress bar,
play/pause/prev/next controls, a visible small YouTube player area, stacked member avatars and a
sync indicator; (4) shared queue list (now playing, up next, played) with per-row actions;
(5) search & add songs; (6) members & host controls in a bottom sheet; (7) connection states.
Visual language: strict monochrome (pure black #000 dark theme, white #FFF light theme, greys
#1A1A1A/#2C2C2C/#333/#E0E0E0), colour comes ONLY from album artwork (blurred artwork backdrop with
dark overlay), Inter SemiBold for all text with tight letter-spacing (-0.05em), hierarchy by size
not weight, pill buttons, large corner radii, bottom sheets, 60px queue rows with 44px rounded
thumbnails. Subtle vinyl/turntable motif as a small brand element. Calm, premium, social, music-first.
Indonesian UI copy. Show both dark and light, iPhone 390×844 and desktop 1440×900 (three-column:
artwork/now playing, queue, members). Avoid generic centered form cards, neon gradients, heavy
glassmorphism and stock illustrations.
```

### 6b. Untuk agen coding saat implementasi nanti

```
Redesign ONLY the UI layer of ritme-web (src/ui/*.tsx, styles.css, index.html) to match the
Musicfy visual language described in docs/design/ritme-web-redesign-brief.md (§2, §5). Do not
change src/{protocol,sync,net,session,player} or their tests; keep all 248 Vitest tests and the
Playwright smoke test green, updating UI tests only where markup/labels change. Self-host Inter
(woff2, SemiBold + Regular fallback) and keep the CSP: no external fonts/CDNs. Keep: mobile-first,
16px gutters, ≥44px targets, visible focus, aria-live toasts, prefers-color-scheme and
prefers-reduced-motion, YouTube player visible (≥200px). Implement the artwork-driven backdrop
(blurred current cover + overlay, crossfade on track change), the ≥1024px multi-column room layout,
and bottom sheets for members/search on mobile. Provide before/after screenshots (390×844 and
1440×900, dark and light) of home, invite, room-listen, room-remote and queue.
```

## 7. Kata kunci Pinterest

**Inti (Inggris):**
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
- glassmorphism music player (sebagai referensi, jangan berlebihan)
- apple music now playing ui
- monochrome dashboard ui

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

**Bahasa Indonesia (tambahan):**
- desain aplikasi musik minimalis
- ui pemutar musik gelap
- desain aplikasi dengar bareng

## 8. Kriteria selesai (saat dikerjakan nanti)

- Screenshot sebelum dan sesudah untuk semua layar di §4, terang dan gelap, HP dan desktop.
- Semua tes dan smoke test hijau, CSP tidak dilonggarkan, ukuran bundle tidak naik signifikan
  (selain font).
- Dicek manual dengan YouTube asli di Chrome Android dan Safari iPhone.
- Deploy ke `ritme.dubeli.id` setelah disetujui pemilik.
