package com.example.musicfy.core.resolver

import com.example.musicfy.core.platform.Logger
import java.io.File

/** Everything the stream resolver needs from the platform it runs on. */
class ResolverPlatform(
    /** Replaces Timber in the moved code. */
    val logger: Logger,
    /** Replaces android.util.Log.i, which on Android also logs in release builds (Timber does not). */
    val releaseLog: (tag: String, msg: String) -> Unit,
    /** Persistent app data. The player.js cache lives in `dataDir/cipher_cache` (Android: filesDir). */
    val dataDir: File,
    val jsRuntime: JsRuntime,
    val botGuardRuntime: BotGuardRuntime,
    /** Thrown when every client reports a non-OK status. Android: media3 PlaybackException(ERROR_CODE_REMOTE_ERROR). */
    val playbackError: (reason: String?) -> Exception,
    /** Persists a visitorData minted by guest-session rotation. Android: writes VisitorDataKey. */
    val onVisitorDataRotated: suspend (String) -> Unit,
    val backend: ResolverBackend = YouTubeResolverBackend(),
)

/** The one installed [ResolverPlatform]. Android installs it in App.onCreate, desktop in main(). */
object ResolverRuntime {
    @Volatile
    private var installed: ResolverPlatform? = null

    val platform: ResolverPlatform
        get() = installed ?: error("ResolverRuntime.install(...) must run before the stream resolver is used")

    fun install(platform: ResolverPlatform) {
        installed = platform
    }
}
