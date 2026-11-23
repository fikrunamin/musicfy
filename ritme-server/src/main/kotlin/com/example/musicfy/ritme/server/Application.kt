package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.websocket.ChannelOverflow
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticFiles
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
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
    /** One client for every InnerTube call. */
    http: HttpClient = defaultHttpClient(),
    /** Uncached InnerTube calls of every endpoint together (300/min). */
    val upstreamBudget: UpstreamBudget = UpstreamBudget(clock = clock),
    val search: SearchService = SearchService(InnerTubeSearchBackend(http), clock, budget = upstreamBudget),
    val video: VideoInfoService = VideoInfoService(InnerTubeVideoBackend(http), clock, upstreamBudget),
    val socketLimits: SocketLimits = SocketLimits(),
    val peerStats: PeerStats = PeerStats(),
    val stateBudget: StateBudget = StateBudget(),
)

/**
 * Clickjacking protection for the pages and static files (the web client and the landing page):
 * nobody may frame them. Installed only on those routes; the WebSocket, search and health routes
 * are not documents, so they do without. The CSP header carries only frame-ancestors (which a
 * `<meta>` CSP cannot express); the rest of the policy stays in index.html.
 */
private val NoFraming = createRouteScopedPlugin("NoFraming") {
    onCall { call ->
        call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
        call.response.header("X-Frame-Options", "DENY")
    }
}

fun Application.ritmeModule(deps: ServerDeps) {
    val registry = RoomRegistry(deps.clock, deps.ids, deps.socketLimits.maxRooms, deps.stateBudget)
    val guard = ConnectionGuard(deps.socketLimits, deps.clock)
    val searchLimiter = IpRateLimiter(deps.clock)
    val videoLimiter = IpRateLimiter(deps.clock, perMinute = Limits.VIDEO_PER_MINUTE)

    // Only Caddy can reach this server, so X-Forwarded-For is trustworthy. Caddy (no
    // trusted_proxies) replaces any client-sent value with the address it saw, so the last (and
    // only) entry is the client. If a CDN is ever put in front with Caddy's trusted_proxies, Caddy
    // appends the CDN's address after the client's: switch to skipLastProxies(1) then.
    install(XForwardedHeaders) { useLastProxy() }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Limits.MAX_FRAME_BYTES
        masking = false
        // Ktor's default is UNLIMITED in both directions: a client that stops reading would grow
        // the outgoing buffer forever, and a flood would pile up inbound frames faster than the
        // frame limiter sees them. Bounded + SUSPEND turns both into TCP backpressure; outgoing
        // holds a single frame, everything else waits (coalesced, byte-bounded) in PeerOutbox.
        channels {
            incoming = bounded(Limits.WS_INCOMING_CAPACITY, ChannelOverflow.SUSPEND)
            outgoing = bounded(Limits.WS_OUTGOING_CAPACITY, ChannelOverflow.SUSPEND)
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
            log.info(
                "rooms={} roomStateBytes={} connections={} pendingBytes={} dropped={}",
                registry.size,
                deps.stateBudget.usedBytes,
                guard.connections,
                deps.peerStats.pendingBytes.get(),
                deps.peerStats.aborted.get(),
            )
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/ritme") { ritmeConnection(registry, deps.clock, guard, deps.peerStats) }

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
            } catch (_: UpstreamOverloadedException) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            } catch (e: Exception) {
                // Never log the query: it is user content.
                call.application.log.warn("search backend failed: ${e::class.simpleName}")
                call.respond(HttpStatusCode.BadGateway)
                return@get
            }
            call.respondText(RitmeJson.encodeToString(ListSerializer(SearchResult.serializer()), results), ContentType.Application.Json)
        }

        get("/api/video") {
            val id = call.request.queryParameters["id"].orEmpty()
            if (!VideoInfoService.isVideoId(id)) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (!videoLimiter.tryAcquire(call.request.origin.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            val info = try {
                deps.video.lookup(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: UpstreamOverloadedException) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            } catch (e: Exception) {
                // Never log the id: what people watch is user content.
                call.application.log.warn("video backend failed: ${e::class.simpleName}")
                call.respond(HttpStatusCode.BadGateway)
                return@get
            }
            if (info == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(RitmeJson.encodeToString(VideoInfo.serializer(), info), ContentType.Application.Json)
        }

        val webIndex = File(deps.config.webDir, "index.html")
        val pages = if (webIndex.isFile) {
            listOf(
                staticFiles("/", deps.config.webDir) { default("index.html") },
                get("/r/{code}") { call.respondFile(webIndex) },
            )
        } else {
            listOf(
                get("/") { call.respondText(Landing.page(null), ContentType.Text.Html) },
                get("/r/{code}") { call.respondText(Landing.page(call.parameters["code"]), ContentType.Text.Html) },
            )
        }
        pages.forEach { it.install(NoFraming) }
    }
}
