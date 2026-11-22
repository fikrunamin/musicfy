package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticFiles
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
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
import java.io.File
import kotlin.time.Duration.Companion.seconds

class ServerDeps(
    val config: ServerConfig,
    val clock: Clock = SystemClock,
    val ids: IdGen = SecureIdGen(),
    val search: SearchService = SearchService(InnerTubeSearchBackend(defaultHttpClient()), clock),
    val socketLimits: SocketLimits = SocketLimits(),
)

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids, deps.socketLimits.maxRooms)
    val guard = ConnectionGuard(deps.socketLimits, deps.clock)
    val searchLimiter = IpRateLimiter(deps.clock)

    // Only Caddy can reach this server, so X-Forwarded-For is trustworthy. Caddy (no
    // trusted_proxies) replaces any client-sent value; the last entry is always the address Caddy
    // itself saw, which stays correct even if a CDN / trusted_proxies is added in front later.
    install(XForwardedHeaders) { useLastProxy() }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Limits.MAX_FRAME_BYTES
        masking = false
        // Ktor's default is UNLIMITED in both directions: a client that stops reading would grow
        // the outgoing buffer forever (WebSocketPeer's send queue would never fill), and a flood
        // would pile up inbound frames faster than the frame limiter sees them. Bounded + SUSPEND
        // turns both into TCP backpressure.
        channels {
            incoming = bounded(Limits.WS_CHANNEL_CAPACITY, ChannelOverflow.SUSPEND)
            outgoing = bounded(Limits.WS_CHANNEL_CAPACITY, ChannelOverflow.SUSPEND)
        }
    }

    launch {
        while (isActive) {
            delay(Limits.SWEEP_INTERVAL_MS)
            try {
                registry.sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("room sweep failed", e)
            }
        }
    }

    // Counts only: no codes, names or addresses.
    launch {
        while (isActive) {
            delay(Limits.METRICS_INTERVAL_MS)
            log.info("rooms={} connections={}", registry.size, guard.connections)
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/ritme") {
            val remoteHost = call.request.origin.remoteHost
            if (!guard.tryOpen(remoteHost)) {
                refuse(ErrorCode.RATE_LIMITED, CloseReason.Codes.TRY_AGAIN_LATER)
                return@webSocket
            }
            try {
                ritmeSession(registry, deps.clock, guard, remoteHost)
            } finally {
                guard.release(remoteHost)
            }
        }

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

        val webIndex = File(deps.config.webDir, "index.html")
        if (webIndex.isFile) {
            staticFiles("/", deps.config.webDir) { default("index.html") }
            get("/r/{code}") { call.respondFile(webIndex) }
        } else {
            get("/") { call.respondText(Landing.page(null), ContentType.Text.Html) }
            get("/r/{code}") { call.respondText(Landing.page(call.parameters["code"]), ContentType.Text.Html) }
        }
    }
}
