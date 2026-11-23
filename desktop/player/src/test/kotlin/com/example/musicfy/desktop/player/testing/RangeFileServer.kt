package com.example.musicfy.desktop.player.testing

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Serves byte arrays at /<name> with HTTP Range support, like googlevideo, and records every request. */
class RangeFileServer(
    private val files: Map<String, ByteArray>,
    private val mimeType: String = "application/octet-stream",
) : AutoCloseable {
    private val server = MockWebServer()

    /** The Range header of each request, in order (null = none). */
    val ranges: MutableList<String?> = CopyOnWriteArrayList()
    val requestHeaders: MutableList<Headers> = CopyOnWriteArrayList()

    /** Request number (0-based) → status to answer instead of the bytes, e.g. 403 for an expired url. */
    val failures: MutableMap<Int, Int> = ConcurrentHashMap()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = ranges.size
                ranges += request.headers["Range"]
                requestHeaders += request.headers
                failures[number]?.let { return MockResponse.Builder().code(it).build() }
                val bytes = files[request.url.encodedPath.removePrefix("/")]
                    ?: return MockResponse.Builder().code(404).build()
                val range = request.headers["Range"]?.let(::parseRange)
                    ?: return MockResponse.Builder().code(200).setHeader("Content-Type", mimeType).body(Buffer().write(bytes)).build()
                val start = range.first
                if (start >= bytes.size) {
                    return MockResponse.Builder().code(416).setHeader("Content-Range", "bytes */${bytes.size}").build()
                }
                val end = minOf(range.second ?: (bytes.size - 1L), bytes.size - 1L)
                return MockResponse.Builder()
                    .code(206)
                    .setHeader("Content-Type", mimeType)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .body(Buffer().write(bytes, start.toInt(), (end - start + 1).toInt()))
                    .build()
            }
        }
        server.start()
    }

    fun url(name: String): String = server.url("/$name").toString()

    override fun close() = server.close()

    private fun parseRange(header: String): Pair<Long, Long?>? {
        val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header.trim()) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()
    }
}
