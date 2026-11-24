package com.example.musicfy.ritme.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import java.net.Proxy

/**
 * The HTTP side of room URL entries (spec 4.4, 4.5): a fresh client that shares nothing with the
 * app's YouTube client (no cookies, no cache, no connection pool, no app interceptors), never uses
 * a proxy (with one, the guard would see the proxy's address instead of the real peer) and can
 * only connect to public addresses.
 */
object RitmeUrlHttp {

    /** [userAgent] is read for every request, so it can start as a fallback and be filled in later. */
    fun client(userAgent: () -> String = { RitmeItems.FALLBACK_USER_AGENT }): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(RitmeAddressGuard.socketFactory)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent()).build())
            }
            .addNetworkInterceptor(RitmeAddressGuard.interceptor)
            .build()

    /** ExoPlayer's default retries, except for a refused address: that fails at once, retrying cannot help. */
    val loadErrorPolicy: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (RitmeAddressGuard.isRefusal(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }
}
