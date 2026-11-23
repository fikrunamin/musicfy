#!/usr/bin/env bash
# Downloads the official VLC 3.0.23 build for one platform, checks its pinned SHA-256, and copies
# libVLC plus the plugins listed in desktop/player/vlc-plugins.txt into
# desktopApp/resources/<os-arch>/vlc (git-ignored; Compose packages it via appResourcesRootDir).
# Usage: scripts/desktop/fetch-libvlc.sh [macos-arm64|macos-x64|windows-x64]   (default: this machine)
set -euo pipefail

VLC_VERSION=3.0.23
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLUGINS_FILE="$ROOT/desktop/player/vlc-plugins.txt"
CACHE="$ROOT/build/libvlc-cache"

detect() {
  case "$(uname -s)" in
    Darwin) if [ "$(uname -m)" = "arm64" ]; then echo macos-arm64; else echo macos-x64; fi ;;
    MINGW*|MSYS*|CYGWIN*) echo windows-x64 ;;
    *) echo linux ;;
  esac
}
TARGET="${1:-$(detect)}"

case "$TARGET" in
  macos-arm64) FILE="vlc-$VLC_VERSION-arm64.dmg";    DIR=macosx; OSKEY=mac; SHA=fc6fac08d87f538517d44aca0c5e7a244b67c8c4cb589bf478363a7315fd5e0d ;;
  macos-x64)   FILE="vlc-$VLC_VERSION-intel64.dmg";  DIR=macosx; OSKEY=mac; SHA=ec01530ce69d849dd057fba8876e68ac39bf279dc28de4e9c04e4aec11fc98db ;;
  windows-x64) FILE="vlc-$VLC_VERSION-win64.zip";    DIR=win64;  OSKEY=win; SHA=992d19dbd0b8a7cde9167d2f7780b1ef6f92acc8a71acfa736101a21f35181e1 ;;
  *) echo "fetch-libvlc: nothing to bundle for '$TARGET' (Linux uses the system libvlc5 package)"; exit 0 ;;
esac

OUT="$ROOT/desktopApp/resources/$TARGET/vlc"
mkdir -p "$CACHE"
ARCHIVE="$CACHE/$FILE"
if [ ! -f "$ARCHIVE" ]; then
  curl -fL --retry 3 -o "$ARCHIVE.tmp" "https://get.videolan.org/vlc/$VLC_VERSION/$DIR/$FILE"
  mv "$ARCHIVE.tmp" "$ARCHIVE"
fi

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
ACTUAL="$(sha256 "$ARCHIVE")"
if [ "$ACTUAL" != "$SHA" ]; then
  echo "fetch-libvlc: SHA-256 mismatch for $FILE: expected $SHA, got $ACTUAL" >&2
  rm -f "$ARCHIVE"
  exit 1
fi

WORK="$(mktemp -d)"
MOUNT=""
cleanup() { if [ -n "$MOUNT" ]; then hdiutil detach -quiet "$MOUNT" || true; fi; rm -rf "$WORK"; }
trap cleanup EXIT

rm -rf "$OUT"
mkdir -p "$OUT/plugins"

if [ "$OSKEY" = mac ]; then
  MOUNT="$WORK/mnt"
  mkdir -p "$MOUNT"
  hdiutil attach -quiet -nobrowse -readonly -mountpoint "$MOUNT" "$ARCHIVE"
  SRC="$MOUNT/VLC.app/Contents/MacOS"
  mkdir -p "$OUT/lib"
  cp -L "$SRC/lib/libvlc.dylib" "$SRC/lib/libvlccore.dylib" "$OUT/lib/"
  plugin_src() { echo "$SRC/plugins/lib$2_plugin.dylib"; }
  plugin_dst() { echo "$OUT/plugins/lib$2_plugin.dylib"; }
else
  if command -v unzip >/dev/null 2>&1; then unzip -q "$ARCHIVE" -d "$WORK"
  elif command -v 7z >/dev/null 2>&1; then 7z x -y -o"$WORK" "$ARCHIVE" >/dev/null
  else echo "error: need unzip or 7z to extract $ARCHIVE" >&2; exit 1; fi
  SRC="$WORK/vlc-$VLC_VERSION"
  cp "$SRC/libvlc.dll" "$SRC/libvlccore.dll" "$OUT/"
  plugin_src() { echo "$SRC/plugins/$1/lib$2_plugin.dll"; }
  plugin_dst() { echo "$OUT/plugins/$1/lib$2_plugin.dll"; }
fi

missing=0
while IFS= read -r raw || [ -n "$raw" ]; do
  line="${raw%%#*}"
  line="$(printf '%s' "$line" | tr -d '[:space:]')"
  [ -z "$line" ] && continue
  only=""
  case "$line" in mac:*) only=mac; line="${line#mac:}" ;; win:*) only=win; line="${line#win:}" ;; esac
  if [ -n "$only" ] && [ "$only" != "$OSKEY" ]; then continue; fi
  optional=0
  case "$line" in *\?) optional=1; line="${line%\?}" ;; esac
  category="${line%%/*}"
  name="${line#*/}"
  src="$(plugin_src "$category" "$name")"
  dst="$(plugin_dst "$category" "$name")"
  if [ -f "$src" ]; then
    mkdir -p "$(dirname "$dst")"
    cp -L "$src" "$dst"
  elif [ "$optional" = 1 ]; then
    echo "fetch-libvlc: optional plugin $category/$name is not in this build; skipped"
  else
    echo "fetch-libvlc: required plugin $category/$name is missing from $FILE ($src)" >&2
    missing=1
  fi
done < "$PLUGINS_FILE"
[ "$missing" = 0 ] || exit 1

echo "$VLC_VERSION" > "$OUT/VERSION"
echo "fetch-libvlc: libVLC $VLC_VERSION for $TARGET in $OUT ($(du -sh "$OUT" | cut -f1))"
