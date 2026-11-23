// CipherDeobfuscator.kt

package com.example.musicfy.utils.cipher

import com.example.musicfy.core.resolver.CipherSession
import com.example.musicfy.core.resolver.ResolverLog
import com.example.musicfy.core.resolver.ResolverRuntime
import com.example.musicfy.core.resolver.UriCodec

object CipherDeobfuscator {
    private const val TAG = "musicfy_CipherDeobfusc"

    private var cipherWebView: CipherSession? = null
    private var currentPlayerHash: String? = null

    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? {
        return try {
            deobfuscateInternal(signatureCipher, videoId, isRetry = false)
        } catch (e: Exception) {
            ResolverLog.tag(TAG).e(e, "Cipher deobfuscation failed, retrying with fresh JS: ${e.message}")

            try {
                PlayerJsFetcher.invalidateCache()
                closeWebView()
                deobfuscateInternal(signatureCipher, videoId, isRetry = true)
            } catch (retryE: Exception) {
                ResolverLog.tag(TAG).e(retryE, "Cipher deobfuscation retry also failed: ${retryE.message}")
                null
            }
        }
    }

    private suspend fun deobfuscateInternal(signatureCipher: String, videoId: String, isRetry: Boolean): String? {

        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]

        if (obfuscatedSig == null || baseUrl == null) {
            ResolverLog.tag(TAG).e("Could not parse signatureCipher params: s=${obfuscatedSig != null}, url=${baseUrl != null}")
            return null
        }

        ResolverLog.tag(TAG).d("Deobfuscating cipher for $videoId: sig=${obfuscatedSig.take(20)}..., sp=$sigParam")

        val webView = getOrCreateWebView(forceRefresh = isRetry)
            ?: return null

        val deobfuscatedSig = webView.deobfuscateSignature(obfuscatedSig)

        val separator = if ("?" in baseUrl) "&" else "?"
        val finalUrl = "$baseUrl${separator}${sigParam}=${UriCodec.encode(deobfuscatedSig)}"

        ResolverLog.tag(TAG).d("Custom cipher deobfuscation succeeded for $videoId")
        return finalUrl
    }

    suspend fun transformNParamInUrl(url: String): String {
        return try {
            transformNInternal(url)
        } catch (e: Exception) {
            ResolverLog.tag(TAG).e(e, "N-transform failed, returning original URL: ${e.message}")
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {

        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            ResolverLog.tag(TAG).d("No 'n' parameter found in URL, skipping transform")
            return url
        }
        val nValue = UriCodec.decode(nMatch.groupValues[1])
        ResolverLog.tag(TAG).d("N-param found: $nValue")

        val webView = getOrCreateWebView(forceRefresh = false) ?: return url

        if (!webView.nFunctionAvailable) {
            ResolverLog.tag(TAG).e("N-transform function was not discovered at init time")
            return url
        }

        val transformedN = webView.transformN(nValue)
        ResolverLog.tag(TAG).d("N-param transformed: $nValue -> $transformedN")

        return url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${UriCodec.encode(transformedN)}"
        )
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherSession? {
        if (!forceRefresh && cipherWebView != null) {
            return cipherWebView
        }

        if (cipherWebView != null) {
            closeWebView()
        }

        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            ResolverLog.tag(TAG).e("Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result

        val sigInfo = FunctionNameExtractor.extractSigFunctionInfo(playerJs)

        if (sigInfo == null) {
            ResolverLog.tag(TAG).e("Could not extract signature function info from player JS")
            return null
        }

        val nFuncInfo = FunctionNameExtractor.extractNFunctionInfo(playerJs)
        if (nFuncInfo == null) {
            ResolverLog.tag(TAG).e("Could not extract n-function info from player JS (will try brute-force)")
        }

        ResolverLog.tag(TAG).d("Creating CipherWebView with sig=${sigInfo.name}, constantArg=${sigInfo.constantArg}, nFunc=${nFuncInfo?.name}[${nFuncInfo?.arrayIndex}]")

        val webView = ResolverRuntime.platform.jsRuntime.loadCipher(
            playerJs = playerJs,
            sigInfo = sigInfo,
            nFuncInfo = nFuncInfo,
        )

        cipherWebView = webView
        currentPlayerHash = hash
        return webView
    }

    private suspend fun closeWebView() {
        cipherWebView?.dispose()
        cipherWebView = null
        currentPlayerHash = null
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = UriCodec.decode(pair.substring(0, idx))
                val value = UriCodec.decode(pair.substring(idx + 1))
                result[key] = value
            }
        }
        return result
    }

    /** Forgets the loaded player so the next call reloads it. Tests only. */
    internal fun resetForTests() {
        cipherWebView = null
        currentPlayerHash = null
    }
}
