package com.example.musicfy.ritme.server

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
)

fun Application.ritmeModule(deps: ServerDeps) {
    routing {
        get("/healthz") { call.respondText("ok") }
    }
}
