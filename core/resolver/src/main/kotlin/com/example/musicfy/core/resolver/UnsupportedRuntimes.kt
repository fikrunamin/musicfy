package com.example.musicfy.core.resolver

import com.example.musicfy.utils.cipher.FunctionNameExtractor

private const val NO_JS = "No JavaScript runtime on this platform yet (desktop gets one in D2)"

/** Desktop until D2: cipher and n-transform fail softly (URL left as is, NewPipe fallback used). */
object UnsupportedJsRuntime : JsRuntime {
    override suspend fun loadCipher(
        playerJs: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
        nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    ): CipherSession = throw UnsupportedOperationException(NO_JS)

    override suspend fun loadNSolver(playerJs: String): NSolverSession = throw UnsupportedOperationException(NO_JS)
}

/** Desktop until D2: no PoTokens, so the resolver relies on clients that need none (VISIONOS first). */
object UnsupportedBotGuardRuntime : BotGuardRuntime {
    override val isSupported: Boolean = false

    override suspend fun newSession(): BotGuardSession =
        throw UnsupportedOperationException("No BotGuard runtime on this platform yet (desktop gets one in D2)")
}

/** Desktop's [ResolverPlatform.playbackError]. */
class StreamUnplayableException(reason: String?) : Exception(reason)
