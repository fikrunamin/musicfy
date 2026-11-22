import type { EndReason } from '../net/connection';
import type { SearchFailure } from '../net/search';
import type { NoticeKey } from '../session/reducer';

/** All UI copy (Indonesian, matching the spec). */
export const t = {
  appName: 'Ritme',
  tagline: 'Dengar musik bareng teman, sinkron di semua perangkat.',
  nameLabel: 'Nama tampilan',
  namePlaceholder: 'Misalnya Budi',
  nameInvalid: 'Nama harus 1–32 karakter.',
  start: 'Mulai Ritme',
  or: 'atau',
  codeLabel: 'Kode room',
  codePlaceholder: 'K7M2QX',
  codeInvalid: 'Kode room terdiri dari 6 huruf/angka.',
  join: 'Gabung',
  invited: 'Kamu diajak dengar bareng',
  openInApp: 'Buka di aplikasi Musicfy',
  noApp: 'Belum punya Musicfy?',
  download: 'Unduh di GitHub',
  connecting: 'Menghubungkan…',
  retry: 'Coba lagi',
  back: 'Kembali',

  roomTitle: (code: string) => `Ritme · ${code}`,
  share: 'Bagikan',
  shareText: 'Yuk dengar bareng di Ritme',
  reconnecting: 'Menyambung ulang…',
  members: (n: number) => `${n} anggota`,
  nowPlaying: 'Sedang diputar',
  nothingPlaying: 'Belum ada lagu. Tambahkan lagu untuk mulai.',
  addedBy: (name: string) => `oleh ${name}`,
  you: 'Kamu',
  youSuffix: '(kamu)',
  host: 'Host',
  disconnected: 'terputus',
  live: 'LIVE',
  upNext: 'Berikutnya',
  queueEmpty: 'Antrean kosong.',
  played: (n: number) => `Sudah diputar (${n})`,
  addSong: 'Tambah lagu',
  play: 'Putar',
  pause: 'Jeda',
  next: 'Berikutnya',
  previous: 'Sebelumnya',
  seek: 'Posisi lagu',
  playNow: 'Putar sekarang',
  moveUp: 'Naikkan',
  moveDown: 'Turunkan',
  remove: 'Hapus',
  controlsLocked: 'Host mengunci kontrol',
  unsupported: 'Tidak didukung di versi ini',
  unplayableWeb: 'Tidak bisa diputar di web',
  insecure: 'Tidak bisa diputar di halaman aman (http)',

  listenMode: 'Mode dengar',
  remoteMode: 'Mode remote',
  modeHint: 'Mode remote: kontrol saja, tanpa suara di perangkat ini.',
  tapToListen: 'Ketuk untuk ikut dengar',
  tapPlayer: 'Ketuk pemutar untuk mulai',
  keepScreenOn: 'Biarkan layar menyala supaya musik tidak berhenti.',

  searchLabel: 'Cari lagu',
  searchPlaceholder: 'Judul atau artis',
  search: 'Cari',
  searching: 'Mencari…',
  noResults: 'Tidak ada hasil.',
  addToQueue: 'Tambah',
  playNext: 'Putar berikutnya',
  close: 'Tutup',

  membersTitle: 'Anggota',
  makeHost: 'Jadikan host',
  kick: 'Keluarkan',
  guestsCanControl: 'Anggota boleh kontrol',
  leave: 'Keluar Ritme',
  end: 'Akhiri',
  confirmEnd: 'Akhiri Ritme untuk semua anggota?',
} as const;

export const noticeText: Record<NoticeKey, string> = {
  forbidden: 'Host mengunci kontrol',
  rateLimited: 'Terlalu cepat, coba lagi sebentar',
  queueFull: 'Antrean penuh',
  invalid: 'Permintaan tidak valid',
  failed: 'Terjadi kesalahan, coba lagi',
  linkCopied: 'Link disalin',
};

export const searchFailureText: Record<SearchFailure, string> = {
  invalid: 'Ketik judul atau artis (maksimal 200 karakter).',
  'rate-limited': 'Terlalu banyak pencarian, tunggu sebentar.',
  busy: 'Server pencarian sedang sibuk, coba lagi nanti.',
  failed: 'Pencarian gagal, coba lagi.',
};

export function endText(reason: EndReason): string | null {
  switch (reason.kind) {
    case 'left':
      return null;
    case 'kicked':
      return 'Kamu dikeluarkan dari Ritme';
    case 'closed':
      return 'Ritme diakhiri host';
    case 'lost':
      return 'Terputus dari Ritme';
    case 'unreachable':
      return 'Tidak bisa terhubung ke server Ritme';
    case 'rejected':
      switch (reason.code) {
        case 'ROOM_NOT_FOUND':
          return 'Room tidak ditemukan';
        case 'ROOM_FULL':
          return 'Room penuh';
        case 'UPGRADE_REQUIRED':
          return 'Versi web ini sudah lama. Muat ulang halaman.';
        case 'RATE_LIMITED':
          return 'Terlalu banyak percobaan, coba lagi sebentar';
        case 'INVALID':
          return 'Nama atau kode tidak valid';
        default:
          return 'Tidak bisa bergabung, coba lagi';
      }
  }
}

export const RELEASES_URL = 'https://github.com/realidkroo/musicfy/releases/latest';
