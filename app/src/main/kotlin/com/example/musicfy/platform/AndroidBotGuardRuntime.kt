package com.example.musicfy.platform

import android.content.Context
import android.webkit.CookieManager
import com.example.musicfy.core.resolver.BotGuardRuntime
import com.example.musicfy.core.resolver.BotGuardSession
import com.example.musicfy.utils.potoken.PoTokenWebView

class AndroidBotGuardRuntime(context: Context) : BotGuardRuntime {
    private val appContext = context.applicationContext

    // Same probe PoTokenGenerator used: no CookieManager means no usable WebView.
    override val isSupported: Boolean by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    override suspend fun newSession(): BotGuardSession = PoTokenWebView.getNewPoTokenGenerator(appContext)
}
