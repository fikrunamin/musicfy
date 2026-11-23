package com.example.musicfy.desktop.player.vlc

/**
 * libVLC instance arguments (spec §2.3). [silent] adds `--aout=dummy` (tests, CI). The soxr resampler
 * falls back to libVLC's default when the plugin is absent.
 */
fun vlcArgs(silent: Boolean, verbose: Boolean = false): List<String> = buildList {
    add("--no-video")
    add("--intf=dummy")
    add("--no-lua")
    add("--network-caching=1500")
    add("--file-caching=300")
    add("--audio-resampler=soxr")
    if (silent) add("--aout=dummy")
    add(if (verbose) "--verbose=2" else "--verbose=0")
}
