package com.example.musicfy.core.resolver

import com.example.musicfy.utils.cipher.FunctionNameExtractor

/**
 * Runs YouTube's player JavaScript. Android: WebViews (CipherWebView, EjsSolverWebView in app/).
 * Desktop: [UnsupportedJsRuntime] until D2 embeds Chromium.
 */
interface JsRuntime {
    /** Loads player.js with its signature function (and the n function when found) exported. */
    suspend fun loadCipher(
        playerJs: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
        nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    ): CipherSession

    /** Loads the EJS n-solver (meriyah + astring + yt.solver.core) over player.js. */
    suspend fun loadNSolver(playerJs: String): NSolverSession
}

interface NSolverSession {
    val nFunctionAvailable: Boolean

    suspend fun transformN(nValue: String): String

    /** Releases the engine. Android hops to the main thread for the WebView. */
    suspend fun dispose()
}

interface CipherSession : NSolverSession {
    suspend fun deobfuscateSignature(obfuscatedSig: String): String
}

/** BotGuard, which mints PoTokens. Android: PoTokenWebView. */
interface BotGuardRuntime {
    /** False when the platform has no usable engine (Android: CookieManager.getInstance() throws). */
    val isSupported: Boolean

    suspend fun newSession(): BotGuardSession
}

interface BotGuardSession {
    val isExpired: Boolean

    suspend fun generatePoToken(identifier: String): String

    suspend fun dispose()
}
