# Ritme Web redesign: catatan implementasi (untuk developer)

Pendamping teknis untuk `ritme-web-redesign-brief.md`. Brief itu ditujukan ke desainer; file ini
untuk orang atau agen yang nanti mengubah desain menjadi kode.

- Kode ada di `ritme-web/` (Preact + Vite + TypeScript).
  - UI: `src/ui/*.tsx`, `styles.css` dan `index.html`.
  - Logika: `src/{protocol,sync,net,session,player}`.
  - **Hanya lapisan UI dan CSS yang diubah.** Logika dan tesnya tetap.
- Seluruh tes Vitest dan smoke test Playwright (`npm run e2e`) harus tetap hijau. Ubah tes UI hanya
  di tempat markup atau label berubah.
- Privasi dan CSP (`index.html`):
  - Tanpa CDN, analytics, atau font eksternal.
  - Inter di-self-host sebagai woff2 di repo.
  - Pihak ketiga yang boleh hanya YouTube IFrame API, embed `youtube-nocookie.com`, thumbnail
    (`referrerpolicy="no-referrer"`), dan media dari entri URL.
- Yang tetap dijaga:
  - mobile-first dengan gutter 16 px dan target sentuh ≥ 44 px;
  - fokus terlihat dan toast dengan `aria-live`;
  - `prefers-color-scheme` dan `prefers-reduced-motion`;
  - pemutar YouTube terlihat dengan tinggi ≥ 200 px;
  - semua copy di `src/ui/strings.ts`.
- Latar dari artwork: cover lagu saat ini di-blur ditambah overlay, dan crossfade saat lagu berganti.
  Pakai `img` thumbnail yang sudah dimuat; jangan menambah request pihak ketiga baru.
- Bundle tetap kecil: tanpa library UI berat.
- Selesai berarti:
  - ada screenshot sebelum dan sesudah (390 × 844 dan 1440 × 900, gelap dan terang);
  - sudah dicek manual dengan YouTube asli di Chrome Android dan Safari iPhone;
  - deploy ke `ritme.dubeli.id` setelah disetujui
    (`cd /opt/ritme && docker compose pull && docker compose up -d`).
