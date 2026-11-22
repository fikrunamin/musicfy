package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.seconds

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
    val ids: IdGen = SecureIdGen(),
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock),
)

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids)
    val searchLimiter = IpRateLimiter(deps.clock)

    // Only Caddy can reach this server, so its X-Forwarded-For is the real client IP.
    install(XForwardedHeaders)
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

        get("/api/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty() || query.length > Limits.SEARCH_MAX_QUERY_LENGTH) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (!searchLimiter.tryAcquire(call.request.origin.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            val results = try {
                deps.search.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log the query: it is user content.
                call.application.log.warn("search backend failed: ${e::class.simpleName}")
                call.respond(HttpStatusCode.BadGateway)
                return@get
            }
            call.respondText(RitmeJson.encodeToString(ListSerializer(SearchResult.serializer()), results), ContentType.Application.Json)
        }
    }
}
