// EjsNTransformSolver.kt

package com.example.musicfy.utils.sabr

import com.example.musicfy.core.resolver.NSolverSession
import com.example.musicfy.core.resolver.ResolverLog
import com.example.musicfy.core.resolver.ResolverRuntime
import com.example.musicfy.core.resolver.UriCodec
import com.example.musicfy.utils.cipher.PlayerJsFetcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object EjsNTransformSolver {
    private const val TAG = "musicfy_EjsNSolver"

    private var solverWebView: NSolverSession? = null

    suspend fun transformNParamInUrl(url: String): String {
        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            ResolverLog.tag(TAG).d("No 'n' parameter in SABR URL")
            return url
        }
        val nValue = UriCodec.decode(nMatch.groupValues[1])
        ResolverLog.tag(TAG).d("SABR n-param: $nValue")

        return withContext(NonCancellable) {
            val solver = getOrCreateSolver()
            if (solver == null) {
                return@withContext url
            }

            if (!solver.nFunctionAvailable) {
                ResolverLog.tag(TAG).e("EJS n-solver not available")
                return@withContext url
            }

            try {
                val transformed = solver.transformN(nValue)
                ResolverLog.tag(TAG).d("SABR n-param transformed: $nValue -> $transformed")
                url.replaceFirst(
                    Regex("([?&])n=[^&]+"),
                    "$1n=${UriCodec.encode(transformed)}"
                )
            } catch (e: Exception) {
                ResolverLog.tag(TAG).e(e, "SABR n-transform failed: ${e.message}")
                url
            }
        }
    }

    private suspend fun getOrCreateSolver(): NSolverSession? {
        solverWebView?.let { return it }

        return withContext(NonCancellable) {
            solverWebView?.let { return@withContext it }

            val result = PlayerJsFetcher.getPlayerJs(forceRefresh = false)
            if (result == null) {
                ResolverLog.tag(TAG).e("Failed to get player JS for EJS solver")
                return@withContext null
            }
            val (playerJs, hash) = result
            ResolverLog.tag(TAG).d("Creating EJS n-solver (player hash=$hash, ${playerJs.length} chars)")

            try {
                val sv = ResolverRuntime.platform.jsRuntime.loadNSolver(playerJs)
                solverWebView = sv
                sv
            } catch (e: Exception) {
                ResolverLog.tag(TAG).e(e, "Failed to create EJS solver: ${e.message}")
                null
            }
        }
    }

    suspend fun close() {
        solverWebView?.dispose()
        solverWebView = null
    }

    /** Forgets the loaded solver so the next call reloads it. Tests only. */
    internal fun resetForTests() {
        solverWebView = null
    }
}
