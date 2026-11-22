package com.example.musicfy.core.resolver

import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.IpVersion
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

class YouTubeResolverBackend : ResolverBackend {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return when (YouTube.ipVersion) {
                    IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                    IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                    IpVersion.AUTO -> addresses
                }
            }
        })
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOfNotNull(YouTube.proxy ?: Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                ResolverLog.tag(TAG).e(ioe, "Proxy connection failed for URI: $uri")
            }
        })
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun player(
        videoId: String,
        playlistId: String?,
        client: YouTubeClient,
        signatureTimestamp: Int?,
        poToken: String?,
    ): Result<PlayerResponse> = YouTube.player(videoId, playlistId, client, signatureTimestamp, poToken)

    override suspend fun refreshVisitorData(): Result<String> = YouTube.refreshVisitorData()

    override fun signatureTimestamp(videoId: String): Result<Int> = NewPipeExtractor.getSignatureTimestamp(videoId)

    override fun newPipeStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? =
        NewPipeExtractor.getStreamUrl(format, videoId)

    override fun newPipeStreamUrls(videoId: String): List<Pair<Int, String>> = YouTube.getNewPipeStreamUrls(videoId)

    /**
     * The headers matter: googlevideo validates Origin/Referer against the client named in the
     * url, so probing without them can fail on a url that plays perfectly well. HEAD is not
     * usable here - googlevideo answers it with 403 even for good urls - so this is a ranged GET.
     */
    override fun validateStatus(url: String, headers: Map<String, String>): Boolean {
        ResolverLog.tag(TAG).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .get()
                .url(url)
                .header("Range", "bytes=0-0")

            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            if (headers["User-Agent"] == null) {
                requestBuilder.header("User-Agent", YouTubeClient.USER_AGENT_WEB)
            }

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            httpClient.newCall(requestBuilder.build()).execute().use {
                val isSuccessful = it.isSuccessful || it.code == 206
                ResolverLog.tag(TAG).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${it.code})")
                return isSuccessful
            }
        } catch (e: Exception) {
            ResolverLog.tag(TAG).e(e, "Stream URL validation failed with exception")
            e.printStackTrace()
        }
        return false
    }

    private companion object {
        const val TAG = "YTPlayerUtils"
    }
}
