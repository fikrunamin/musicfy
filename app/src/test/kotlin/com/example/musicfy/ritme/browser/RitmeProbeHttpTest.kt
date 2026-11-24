package com.example.musicfy.ritme.browser

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The probe's HTTP side, against an interceptor standing in for the server (no network). */
class RitmeProbeHttpTest {
    private val seen = mutableListOf<Request>()

    private fun client(body: String, type: String = "application/vnd.apple.mpegurl", allowOrigin: String? = "*") =
        OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            val response = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Type", type)
                .body(body.toResponseBody(type.toMediaType()))
            if (allowOrigin != null) response.header("Access-Control-Allow-Origin", allowOrigin)
            response.build()
        }.build()

    @Test
    fun sendsExactlyTheProbesHeaders() {
        RitmeProbeHttp(client("#EXTM3U\n")).get("https://cdn.example.com/v.m3u8", mapOf("Range" to "bytes=0-4095"), 4096)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("bytes=0-4095", request.header("Range"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Referer"))
        assertNull(request.header("Origin"))
    }

    @Test
    fun theAnswerIsCutAtMaxBytes() {
        val answer = RitmeProbeHttp(client("#EXTM3U\n" + "x".repeat(10_000))).get("https://cdn.example.com/v.m3u8", emptyMap(), 4096)
        assertEquals(4096, answer.body.size)
        assertEquals(206, answer.status)
        assertEquals("application/vnd.apple.mpegurl", answer.contentType)
        assertEquals("*", answer.allowOrigin)
        assertEquals("https://cdn.example.com/v.m3u8", answer.finalUrl)
    }

    @Test
    fun aShortAnswerComesWhole() {
        assertEquals(8, RitmeProbeHttp(client("#EXTM3U\n", allowOrigin = null)).get("https://cdn.example.com/v.m3u8", emptyMap(), 4096).body.size)
    }
}
