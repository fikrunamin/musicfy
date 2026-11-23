import type { EndReason } from '../net/connection';
import type { SearchFailure } from '../net/search';
import type { VideoInfoFailure } from '../net/video';
import type { VideoLinkProblem } from '../net/video-link';
import type { NoticeKey } from '../session/reducer';

/** All UI copy (Indonesian, matching the spec). */
export const t = {
  appName: 'Ritme',
  brand: 'Musicfy',
  brandRitme: 'Musicfy · Ritme',
  roomChip: (code: string) => `Room ${code}`,
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
  rowActions: (title: string) => `Aksi: ${title}`,
  controlsLocked: 'Host mengunci kontrol',
  unsupported: 'Tidak didukung di versi ini',
  unplayableWeb: 'Tidak bisa diputar di web',
  insecure: 'Tidak bisa diputar di halaman aman (http)',

  listenMode: 'Mode dengar',
  remoteMode: 'Mode remote',
  modeGroup: 'Mode perangkat ini',
  modeHint: 'Mode remote: kontrol saja, tanpa suara di perangkat ini.',
  tapToListen: 'Ketuk untuk ikut dengar',
  tapPlayer: 'Ketuk pemutar untuk mulai',
  tapReason: 'Browser butuh satu ketukan sebelum memutar suara',
  soundFromYouTube: 'Suara dari YouTube',
  directLinkSource: 'Link video langsung',
  unplayableReason: 'Format atau sumbernya tidak didukung browser.',
  skip: 'Lewati',
  waitingForHost: 'Menunggu host melewati video ini',
  queue: 'Antrean',
  synced: 'Tersinkron',
  keepScreenOn: 'Biarkan layar menyala supaya musik tidak berhenti.',

  searchLabel: 'Cari lagu',
  searchPlaceholder: 'Judul atau artis',
  search: 'Cari',
  searching: 'Mencari…',
  noResults: 'Tidak ada hasil.',
  addToQueue: 'Tambah',
  playNext: 'Putar berikutnya',
  close: 'Tutup',
  cancel: 'Batal',

  addVideo: 'Tambah video',
  addKindGroup: 'Yang ditambahkan',
  tabSongs: 'Lagu',
  tabVideo: 'Video',
  videoLinkLabel: 'Tempel link video',
  videoLinkPlaceholder: 'Link YouTube atau link video',
  pasteLink: 'Tempel dari papan klip',
  preview: 'Pratinjau',
  loadingPreview: 'Memuat…',
  titleOptional: 'Judul (opsional)',
  unknownDuration: 'Durasi tidak diketahui',
  videoBadge: 'Video',
  fromDomain: (domain: string) => `dari ${domain}`,
  videoUnplayableWeb: 'Video ini tidak bisa diputar di web',
  videoTip: 'Link halaman situs film tidak bisa diputar langsung. Fitur deteksi video dari situs ada di aplikasi Musicfy.',

  membersTitle: 'Anggota',
  makeHost: 'Jadikan host',
  kick: 'Keluarkan',
  guestsCanControl: 'Anggota boleh kontrol',
  guestsCanAdd: 'Anggota boleh menambah',
  leave: 'Keluar Ritme',
  confirmEnd: 'Akhiri Ritme untuk semua anggota?',
  endRoom: 'Akhiri Ritme untuk semua',
  confirmKick: (name: string) => `Keluarkan ${name} dari Ritme?`,
  endConfirm: 'Akhiri',
} as const;

export const noticeText: Record<NoticeKey, string> = {
  forbidden: 'Host mengunci kontrol',
  addForbidden: 'Host menutup penambahan lagu',
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

export const videoLinkProblemText: Record<VideoLinkProblem, string> = {
  unrecognized: 'Link tidak dikenali',
  page: 'Link ini halaman web, bukan file video. Tempel link video langsung (mp4, webm, atau m3u8).',
  dash: 'Video ini tidak bisa diputar di web',
  unsafe: 'Link ini tidak bisa ditambahkan',
  insecure: 'Halaman harus https untuk link http',
};

export const videoInfoFailureText: Record<VideoInfoFailure, string> = {
  invalid: 'Link tidak dikenali',
  'not-found': 'Video tidak ditemukan',
  'rate-limited': 'Terlalu banyak permintaan, coba lagi sebentar',
  busy: 'Server sibuk',
  failed: 'Info video tidak bisa diambil',
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
