package com.example.musicfy.ritme.server

import java.io.File

fun testDeps(
    webDir: File = File("build/no-web"),
    socketLimits: SocketLimits = SocketLimits(),
    peerStats: PeerStats = PeerStats(),
): ServerDeps = ServerDeps(
    ServerConfig(port = 0, publicUrl = "https://ritme.test", webDir = webDir),
    socketLimits = socketLimits,
    peerStats = peerStats,
)
