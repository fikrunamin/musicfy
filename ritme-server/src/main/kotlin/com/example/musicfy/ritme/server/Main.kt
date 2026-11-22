package com.example.musicfy.ritme.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port) {
        ritmeModule(ServerDeps(config))
    }.start(wait = true)
}
