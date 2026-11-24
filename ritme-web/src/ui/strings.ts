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
  reload: 'Muat ulang',

  // Connection states (handoff H): full screens for a session that cannot go on.
  lostTitle: 'Terputus dari Ritme',
  unreachableTitle: 'Tidak bisa terhubung ke server Ritme',
  checkConnection: 'Periksa koneksi internetmu.',
  closedTitle: 'Ritme sudah diakhiri',
  closedBody: 'Host mengakhiri Ritme untuk semua anggota.',
  kickedTitle: 'Kamu dikeluarkan dari Ritme',
  kickedBody: 'Host mengeluarkanmu dari room ini.',
  upgradeTitle: 'Perlu versi baru',
  upgradeBody: 'Room ini memakai versi Ritme yang lebih baru. Muat ulang halaman.',

  roomTitle: (code: string) => `Ritme · ${code}`,
  share: 'Bagikan',
  shareText: 'Yuk dengar bareng di Ritme',
  reconnecting: 'Menyambung ulang…',
  members: (n: number) => `${n} anggota`,
  /** Desktop room header: "Tersinkron · 3 anggota" (the count stands in for the avatar stack). */
  statusWithMembers: (status: string, n: number) => `${status} · ${n} anggota`,
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
  youtube: 'YouTube',
  back10: 'Mundur 10 detik',
  forward10: 'Maju 10 detik',
  fullscreen: 'Layar penuh',
  exitFullscreen: 'Keluar dari layar penuh',
  /** The theater header toggle that folds the right column away. */
  sidePanel: 'Antrean dan anggota',
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

  addTitle: 'Tambah ke antrean',
  addKindGroup: 'Yang ditambahkan',
  tabSongs: 'Lagu',
  tabVideo: 'Video',
  videoLinkLabel: 'Tempel link video',
  videoLinkPlaceholder: 'Link YouTube atau link video',
  pasteLink: 'Tempel dari papan klip',
  preview: 'Pratinjau',
  loadingPreview: 'Memuat…',
  titleOptional: 'Judul (opsional)',
  titlePlaceholder: 'Kosongkan untuk pakai nama file',
  videoLinkHint: 'Tempel link dari YouTube atau link video lain yang bisa diputar di browser.',
  /** Not in the handoff: reading the clipboard was refused (permission or a dismissed prompt). */
  pasteFailed: 'Papan klip tidak bisa dibaca. Tempel link langsung di kolom ini.',
  /** Not in the handoff: the clipboard held no text. */
  pasteEmpty: 'Papan klip kosong. Salin link videonya dulu.',
  unknownDuration: 'Durasi tidak diketahui',
  videoBadge: 'Video',
  fromDomain: (domain: string) => `dari ${domain}`,
  videoUnplayableWeb: 'Video ini tidak bisa diputar di web',
  videoTip: 'Link halaman situs film tidak bisa diputar langsung. Fitur deteksi video dari situs ada di aplikasi Musicfy.',

  membersTitle: 'Anggota',
  makeHostFor: (name: string) => `Jadikan host: ${name}`,
  kick: 'Keluarkan',
  kickFor: (name: string) => `Keluarkan: ${name}`,
  hostRoom: 'Host room',
  connected: 'Tersambung',
  guestsCanControl: 'Anggota boleh kontrol',
  guestsCanControlHint: 'Putar, jeda, dan lompat lagu',
  guestsCanAdd: 'Anggota boleh menambah',
  guestsCanAddHint: 'Tambah lagu dan video ke antrean',
  shareCode: 'Bagikan kode',
  copyLink: 'Salin link',
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

/** The banner on Home for a refused join or create (UPGRADE_REQUIRED has a screen of its own). */
export function refusalText(code: string): string {
  switch (code) {
    case 'ROOM_NOT_FOUND':
      return 'Room tidak ditemukan';
    case 'ROOM_FULL':
      return 'Room penuh';
    case 'RATE_LIMITED':
      return 'Terlalu banyak percobaan, coba lagi sebentar';
    case 'INVALID':
      return 'Nama atau kode tidak valid';
    default:
      return 'Tidak bisa bergabung, coba lagi';
  }
}

export const RELEASES_URL = 'https://github.com/realidkroo/musicfy/releases/latest';
