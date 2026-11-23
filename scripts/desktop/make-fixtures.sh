#!/usr/bin/env bash
# Regenerates the 5-second silent fixtures for the libVLC integration tests (each well under 50 KB).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/desktop/player/src/test/resources/fixtures"
mkdir -p "$OUT"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i anullsrc=r=48000:cl=stereo -t 5 \
  -c:a libopus -b:a 24k -f webm "$OUT/silence-5s.webm"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i anullsrc=r=44100:cl=stereo -t 5 \
  -c:a aac -b:a 32k -movflags +faststart -f mp4 "$OUT/silence-5s.m4a"
ls -l "$OUT"
