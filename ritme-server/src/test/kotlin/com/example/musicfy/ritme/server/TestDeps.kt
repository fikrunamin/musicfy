package com.example.musicfy.ritme.server

import java.io.File

fun testDeps(webDir: File = File("build/no-web")): ServerDeps =
    ServerDeps(ServerConfig(port = 0, publicUrl = "https://ritme.test", webDir = webDir))
