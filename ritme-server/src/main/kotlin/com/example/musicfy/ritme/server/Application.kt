package com.example.musicfy.ritme.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
    val ids: IdGen = SecureIdGen(),
)

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids)

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Limits.MAX_FRAME_BYTES
        masking = false
    }

    launch {
        while (isActive) {
            delay(Limits.SWEEP_INTERVAL_MS)
            runCatching { registry.sweep() }.onFailure { log.error("room sweep failed", it) }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/ritme") { ritmeSession(registry, deps.clock) }
    }
}
