package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.ProbeHttp
import com.example.musicfy.ritme.media.ProbeResponse
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [ProbeHttp] over OkHttp. Given the room's client (RitmeUrlHttp: no cookies, no cache, no proxy,
 * public addresses only), it sends exactly the headers the prober asks for, follows redirects
 * (each hop checked by RitmeAddressGuard) and reads at most `maxBytes` of the body. [onCall] gets
 * each call before it runs, so a caller that gives up can cancel it (blocking reads ignore interrupts).
 */
class RitmeProbeHttp(private val client: OkHttpClient, private val onCall: (Call) -> Unit = {}) : ProbeHttp {

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val call = client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            val source = response.body.source()
            source.request(maxBytes.toLong())
            val size = minOf(source.buffer.size, maxBytes.toLong())
            return ProbeResponse(
                status = response.code,
                contentType = response.header("Content-Type"),
                body = source.buffer.readByteArray(size),
                finalUrl = response.request.url.toString(),
                allowOrigin = response.header("Access-Control-Allow-Origin"),
            )
        }
    }
}
