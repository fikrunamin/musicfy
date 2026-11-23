package com.example.musicfy.core.resolver.testing

import com.example.musicfy.core.platform.Logger
import com.example.musicfy.core.resolver.BotGuardRuntime
import com.example.musicfy.core.resolver.BotGuardSession
import com.example.musicfy.core.resolver.CipherSession
import com.example.musicfy.core.resolver.JsRuntime
import com.example.musicfy.core.resolver.NSolverSession
import com.example.musicfy.core.resolver.ResolverBackend
import com.example.musicfy.core.resolver.ResolverPlatform
import com.example.musicfy.core.resolver.ResolverRuntime
import com.example.musicfy.utils.cipher.FunctionNameExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import java.io.File
import java.io.IOException

class RecordingLogger : Logger {
    val lines = mutableListOf<String>()

    override fun d(tag: String, msg: String) { lines += "D/$tag: $msg" }

    override fun i(tag: String, msg: String) { lines += "I/$tag: $msg" }

    override fun w(tag: String, msg: String, t: Throwable?) { lines += "W/$tag: $msg" + (t?.let { " [${it.message}]" } ?: "") }

    override fun e(tag: String, msg: String, t: Throwable?) { lines += "E/$tag: $msg" + (t?.let { " [${it.message}]" } ?: "") }
}

class TestPlaybackError(val reason: String?) : Exception(reason)

object TestResolver {
    /** Matches FunctionNameExtractor: sig = Xy (constant arg 1), n = Nf[0]. */
    const val PLAYER_JS = "var a=b&&(c=Xy(1,decodeURIComponent(d)));q.get(\"n\"))&&(b=Nf[0](a));"

    fun install(
        dataDir: File,
        jsRuntime: JsRuntime = FakeJsRuntime(),
        botGuard: BotGuardRuntime = FakeBotGuardRuntime(),
        backend: ResolverBackend = FakeBackend(),
        logger: RecordingLogger = RecordingLogger(),
        rotated: MutableList<String> = mutableListOf(),
    ): ResolverPlatform = ResolverPlatform(
        logger = logger,
        releaseLog = { tag, msg -> logger.lines += "R/$tag: $msg" },
        dataDir = dataDir,
        jsRuntime = jsRuntime,
        botGuardRuntime = botGuard,
        playbackError = { TestPlaybackError(it) },
        onVisitorDataRotated = { rotated += it },
        backend = backend,
    ).also(ResolverRuntime::install)

    /** A fresh player.js cache, so PlayerJsFetcher never touches the network. */
    fun writePlayerJsCache(dataDir: File, js: String = PLAYER_JS, hash: String = "testhash") {
        val dir = File(dataDir, "cipher_cache").apply { mkdirs() }
        File(dir, "player_$hash.js").writeText(js)
        File(dir, "current_hash.txt").writeText("$hash\n${System.currentTimeMillis()}")
    }
}

class FakeCipherSession(
    private val signatures: Map<String, String> = emptyMap(),
    private val nValues: Map<String, String> = emptyMap(),
    override val nFunctionAvailable: Boolean = true,
) : CipherSession {
    var disposed = false
        private set

    override suspend fun deobfuscateSignature(obfuscatedSig: String): String =
        signatures[obfuscatedSig] ?: error("unexpected signature $obfuscatedSig")

    override suspend fun transformN(nValue: String): String = nValues[nValue] ?: error("unexpected n $nValue")

    override suspend fun dispose() { disposed = true }
}

class FakeNSolverSession(
    private val nValues: Map<String, String> = emptyMap(),
    override val nFunctionAvailable: Boolean = true,
) : NSolverSession {
    override suspend fun transformN(nValue: String): String = nValues[nValue] ?: error("unexpected n $nValue")

    override suspend fun dispose() = Unit
}

class FakeJsRuntime(
    private val cipher: suspend (String, FunctionNameExtractor.SigFunctionInfo, FunctionNameExtractor.NFunctionInfo?) -> CipherSession =
        { _, _, _ -> error("cipher session not configured") },
    private val nSolver: suspend (String) -> NSolverSession = { error("n-solver not configured") },
) : JsRuntime {
    var cipherLoads = 0
        private set
    var nSolverLoads = 0
        private set
    var lastSigInfo: FunctionNameExtractor.SigFunctionInfo? = null
        private set
    var lastNFuncInfo: FunctionNameExtractor.NFunctionInfo? = null
        private set

    override suspend fun loadCipher(
        playerJs: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
        nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    ): CipherSession {
        cipherLoads++
        lastSigInfo = sigInfo
        lastNFuncInfo = nFuncInfo
        return cipher(playerJs, sigInfo, nFuncInfo)
    }

    override suspend fun loadNSolver(playerJs: String): NSolverSession {
        nSolverLoads++
        return nSolver(playerJs)
    }
}

class FakeBotGuardSession(private val minted: MutableList<String>) : BotGuardSession {
    var expired = false
    var disposed = false
        private set

    override val isExpired: Boolean get() = expired

    override suspend fun generatePoToken(identifier: String): String {
        minted += identifier
        return "pot-$identifier"
    }

    override suspend fun dispose() { disposed = true }
}

class FakeBotGuardRuntime(
    override val isSupported: Boolean = true,
    private val failWith: Exception? = null,
) : BotGuardRuntime {
    var sessionsCreated = 0
        private set
    val minted = mutableListOf<String>()
    val sessions = mutableListOf<FakeBotGuardSession>()

    override suspend fun newSession(): BotGuardSession {
        sessionsCreated++
        failWith?.let { throw it }
        return FakeBotGuardSession(minted).also { sessions += it }
    }
}

data class PlayerCall(val client: YouTubeClient, val signatureTimestamp: Int?, val poToken: String?)

class FakeBackend : ResolverBackend {
    /** What each client answers; null means the request itself fails. */
    var respond: (YouTubeClient) -> PlayerResponse? = { null }
    var validUrl: (String) -> Boolean = { true }
    val playerCalls = mutableListOf<PlayerCall>()
    val validated = mutableListOf<String>()
    var visitorDataRefreshes = 0
        private set

    override suspend fun player(
        videoId: String,
        playlistId: String?,
        client: YouTubeClient,
        signatureTimestamp: Int?,
        poToken: String?,
    ): Result<PlayerResponse> {
        playerCalls += PlayerCall(client, signatureTimestamp, poToken)
        return respond(client)?.let { Result.success(it) }
            ?: Result.failure(IOException("no response configured for ${client.clientName}"))
    }

    override suspend fun refreshVisitorData(): Result<String> {
        visitorDataRefreshes++
        val fresh = "visitor-$visitorDataRefreshes"
        YouTube.visitorData = fresh
        return Result.success(fresh)
    }

    override fun signatureTimestamp(videoId: String): Result<Int> = Result.success(20_000)

    override fun newPipeStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? = null

    override fun newPipeStreamUrls(videoId: String): List<Pair<Int, String>> = emptyList()

    override fun validateStatus(url: String, headers: Map<String, String>): Boolean {
        validated += url
        return validUrl(url)
    }
}
