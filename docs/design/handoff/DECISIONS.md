# Keputusan atas hal "Belum dibuat / perlu keputusan" di handoff Ritme web

Disetujui pengguna pada 2026-11-23. Berlaku untuk implementasi redesign (#2c).

1. **Layar Terang yang belum ada** (teater "Tidak bisa diputar" (O), HLS LIVE layar penuh (N),
   desktop "Sebelum mulai" dan "Remote" (C+E)): dibuat dari layar Gelap yang sama dengan token
   tema Terang. Tidak perlu desain baru.
2. **Anggota tanpa kontrol saat video tidak bisa diputar:** pakai layar yang sama tanpa tombol
   "Lewati". Sebagai gantinya ada teks kecil (`$muted`, 13): "Menunggu host melewati video ini".
3. **Marquee meta:** hanya berjalan di baris **"Sedang diputar"**, plus saat **hover/fokus** pada
   baris lain. Dengan `prefers-reduced-motion`, marquee tidak pernah berjalan.
4. **Cover 16:9:** hanya di antrean campuran video. Antrean yang isinya lagu saja tetap memakai
   cover persegi 44×44.

Penyesuaian teknis: Inter **di-self-host** (woff2 di repo), tidak dimuat dari Google Fonts, karena
CSP dan privasi tidak mengizinkan CDN. Tampilannya sama.
