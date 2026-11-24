// YTPlayerUtils.kt

package com.example.musicfy.utils

import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.IPADOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.YouTubeClient.Companion.WEB
import com.music.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.response.PlayerResponse
import com.example.musicfy.constants.AudioQuality
import com.example.musicfy.core.resolver.ResolverBackend
import com.example.musicfy.core.resolver.ResolverLog
import com.example.musicfy.core.resolver.ResolverRuntime
import com.example.musicfy.core.resolver.UriCodec
import com.example.musicfy.utils.cipher.CipherDeobfuscator
import com.example.musicfy.utils.potoken.PoTokenGenerator
import com.example.musicfy.utils.potoken.PoTokenResult
import com.example.musicfy.utils.sabr.EjsNTransformSolver

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private var poTokenGenerator = PoTokenGenerator()

    private val backend: ResolverBackend
        get() = ResolverRuntime.platform.backend

    /** Fresh BotGuard state for tests; production never calls this. */
    internal fun resetForTests() {
        poTokenGenerator = PoTokenGenerator()
    }

    /** True once the BotGuard engine holds a usable session token. Surfaced in diagnostics. */
    val hasPoToken: Boolean
        get() = poTokenGenerator.hasSessionToken

    /**
     * Boot the BotGuard engine before the first track needs it. Safe to call more than once.
     */
    suspend fun preWarmPoToken() {
        val sessionId = (if (YouTube.cookie != null) YouTube.dataSyncId else null)
            ?: YouTube.visitorData
            ?: return
        poTokenGenerator.preWarm(sessionId)
    }

    /**
     * VISIONOS, which Metrolist also puts first for streams.
     *
     * Verified against live googlevideo: with `visitorData` set it returns `OK`, every audio
     * itag (139/140/249/250/251) with a **direct, non-ciphered** url, full `audioConfig`
     * loudness data and `playbackTracking` - and its urls serve the *whole* file (206 at 1 MB,
     * at the midpoint and at the final byte). It needs **no PoToken**.
     *
     * That last point is why it belongs here rather than WEB_REMIX. An untokened url is served
     * only up to roughly its first 786 KB, which is ~32 seconds at 128 kbps and ~28 at higher
     * bitrates - the fixed *byte* window is the giveaway. WEB_REMIX can only clear that window
     * with a working PoToken, and minting one blocks the first play for as long as BotGuard
     * takes. VISIONOS sidesteps both problems, so PoToken minting now only happens if playback
     * actually falls through to a web client (see the lazy mint in the loop below).
     *
     * Requires `visitorData`: without it VISIONOS answers `UNPLAYABLE`. App.kt fetches one on
     * startup when absent, so this holds for guests too.
     */
    private val MAIN_CLIENT: YouTubeClient = VISIONOS

    /**
     * Playability statuses that mean "this client may not serve the stream, but another one
     * can". `LOGIN_REQUIRED` belongs here - it is what YouTube returns for "Sign in to confirm
     * you're not a bot" - and leaving it out meant those tracks never reached the fallback
     * clients that can play them. Matches Metrolist.
     */
    private val RESTRICTED_STATUSES = listOf(
        "AGE_CHECK_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        "LOGIN_REQUIRED",
        "CONTENT_CHECK_REQUIRED",
    )

    private val METADATA_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * VISIONOS first - it is the only client measured to serve a complete track without a
     * PoToken. The PoToken-capable web clients follow for anything it cannot play, then the
     * remaining clients for restricted content. IOS / IPADOS / ANDROID_VR are last: they return
     * clean urls that look healthy and then stop dead at the ~786 KB untokened window.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        VISIONOS,
        WEB_REMIX,
        WEB,
        WEB_CREATOR,
        IPADOS,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_65_10,
        ANDROID_VR_NO_AUTH,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        TVHTML5,
        ANDROID_CREATOR,
        MOBILE,
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String = "unknown",
        val streamHeaders: Map<String, String> = emptyMap(),
    )

    /**
     * Headers googlevideo expects alongside a stream url. It checks these against the client
     * named in the url's own `c=` parameter, so a WEB_REMIX url fetched without the YouTube
     * Music origin is refused. musicfy previously sent none of this.
     */
    internal fun YouTubeClient.streamHeaders(): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9")

            when (clientName) {
                "WEB_REMIX" -> {
                    put("Referer", "https://music.youtube.com/")
                    put("Origin", "https://music.youtube.com")
                }

                "WEB_CREATOR" -> {
                    put("Referer", "https://studio.youtube.com/")
                    put("Origin", "https://studio.youtube.com")
                }

                else -> {
                    put("Referer", "https://www.youtube.com/")
                    put("Origin", "https://www.youtube.com")
                }
            }
        }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        networkMetered: () -> Boolean,
    ): Result<PlaybackData> {
        val firstAttempt = resolvePlaybackData(videoId, playlistId, audioQuality, networkMetered)

        if (firstAttempt.isFailure && YouTube.cookie == null) {
            ResolverLog.tag(TAG).w("Playback failed for guest. Rotating session and retrying...")
            PlaybackLogManager.log(PlaybackLogLevel.BOT, "Playback failed for guest", "Triggering bot detection mitigation (rotating guest session)")
            BotDetectionMitigator.rotateGuestSession()
            val retryResult = resolvePlaybackData(videoId, playlistId, audioQuality, networkMetered)
            retryResult.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
            return retryResult
        }

        firstAttempt.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
        return firstAttempt
    }

    private suspend fun resolvePlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        networkMetered: () -> Boolean,
    ): Result<PlaybackData> = runCatching {
        ResolverLog.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        PlaybackLogManager.log(PlaybackLogLevel.INFO, "Resolving playback data", "Video: $videoId")

        println("[PLAYBACK_DEBUG] playerResponseForPlayback called: videoId=$videoId, playlistId=$playlistId")

        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null
        ResolverLog.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // VISIONOS answers UNPLAYABLE without a visitorData, and App.kt only fetches one
        // asynchronously at startup - so a track played early would silently fall through to a
        // client that truncates at ~786 KB. Make sure we have one before asking for a stream.
        if (!isLoggedIn && YouTube.visitorData.isNullOrEmpty()) {
            ResolverLog.tag(logTag).d("No visitorData yet - fetching one before resolving playback")
            backend.refreshVisitorData()
                .onFailure { ResolverLog.tag(logTag).w(it, "Could not fetch visitorData: ${it.message}") }
        }

        // NewPipe's signature-timestamp lookup is a blocking network round trip, and it is only
        // meaningful for clients that hand back ciphered urls. VISIONOS does not, so resolving
        // this eagerly made every play wait for a request it had no use for. Resolved lazily:
        // an ordinary play never touches it, and only a ciphered web client pays the cost.
        val signatureTimestamp: SignatureTimestampResult by lazy { getSignatureTimestampOrNull(videoId) }

        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            ResolverLog.tag(logTag).d("Generating PoToken for MAIN_CLIENT with sessionId")
            try {
                poToken = poTokenGenerator.poTokenFor(videoId, sessionId)
                if (poToken != null) {
                    ResolverLog.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                ResolverLog.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        ResolverLog.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying ${MAIN_CLIENT.clientName} (Main)")
        val mainSigTimestamp = if (MAIN_CLIENT.useSignatureTimestamp) signatureTimestamp.timestamp else null
        var mainPlayerResponse = backend.player(videoId, playlistId, MAIN_CLIENT, mainSigTimestamp, poToken?.playerRequestPoToken).getOrThrow()

        var metadataResponse: PlayerResponse? = null
        if (isLoggedIn) {
            ResolverLog.tag(logTag).d("Fetching metadata from METADATA_CLIENT (WEB_REMIX) for authenticated tracking")
            try {

                var metaPoToken: PoTokenResult? = null
                val metaSessionId = YouTube.dataSyncId
                if (METADATA_CLIENT.useWebPoTokens && metaSessionId != null) {
                    try {
                        metaPoToken = poTokenGenerator.poTokenFor(videoId, metaSessionId)
                    } catch (e: Exception) {
                        ResolverLog.tag(logTag).e(e, "Metadata PoToken generation failed")
                    }
                }
                metadataResponse = backend.player(
                    videoId, playlistId, METADATA_CLIENT,
                    if (METADATA_CLIENT.useSignatureTimestamp) signatureTimestamp.timestamp else null,
                    metaPoToken?.playerRequestPoToken
                ).getOrNull()
                ResolverLog.tag(logTag).d("Metadata response obtained: ${metadataResponse?.playabilityStatus?.status}")
            } catch (e: Exception) {
                ResolverLog.tag(logTag).e(e, "Failed to fetch metadata from METADATA_CLIENT")
            }
        }

        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in RESTRICTED_STATUSES
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {

            ResolverLog.tag(logTag).d("Restricted content detected, trying WEB_CREATOR")
            ResolverLog.release(TAG, "Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = backend.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                ResolverLog.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        if (mainPlayerResponse == null) {
            throw Exception("Failed to get player response")
        }

        val audioConfig = metadataResponse?.playerConfig?.audioConfig ?: mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = metadataResponse?.videoDetails ?: mainPlayerResponse.videoDetails
        val playbackTracking = metadataResponse?.playbackTracking ?: mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var successClient: YouTubeClient? = null
        var streamPlayerResponse: PlayerResponse? = null
        var retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        val currentStatus = mainPlayerResponse.playabilityStatus.status
        var isAgeRestricted = currentStatus in RESTRICTED_STATUSES

        if (isAgeRestricted) {
            ResolverLog.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            ResolverLog.release(TAG, "Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        val startIndex = when {
            isPrivateTrack -> 1
            isAgeRestricted -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {

            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client: YouTubeClient
            if (clientIndex == -1) {

                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                ResolverLog.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {

                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                ResolverLog.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying fallback [${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}]", client.clientName)

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {

                    ResolverLog.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                if (client.useWebPoTokens && poToken == null && sessionId != null) {
                    ResolverLog.tag(logTag).d("Lazily generating PoToken for fallback web client: ${client.clientName}")
                    try {
                        poToken = poTokenGenerator.poTokenFor(videoId, sessionId)
                    } catch (e: Exception) {
                        ResolverLog.tag(logTag).e(e, "Lazy PoToken generation failed")
                    }
                }

                ResolverLog.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")

                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null

                val clientSigTimestamp =
                    if (wasOriginallyAgeRestricted || !client.useSignatureTimestamp) {
                        null
                    } else {
                        signatureTimestamp.timestamp
                    }
                streamPlayerResponse =
                    backend.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                ResolverLog.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.INFO, "Player response OK", if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName)

                val hasDirectUrls = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.url.isNullOrEmpty() } == true
                val hasSignatureCipher = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() } == true

                ResolverLog.tag(logTag).d("URL check: hasDirectUrls=$hasDirectUrls, hasSignatureCipher=$hasSignatureCipher")

                val responseToUse = streamPlayerResponse

                val clientLabel =
                    if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName

                val formatCandidates = selectAudioFormatCandidates(
                    responseToUse,
                    audioQuality,
                    networkMetered(),
                )

                if (formatCandidates.isEmpty()) {
                    ResolverLog.tag(logTag).d("No suitable format found for client: $clientLabel")
                    continue
                }

                // Walk the candidates so a format we cannot deobfuscate falls back to the
                // next best one instead of throwing away an otherwise healthy client.
                for (candidate in formatCandidates) {
                    val candidateUrl =
                        findUrlOrNull(candidate, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                    if (candidateUrl != null) {
                        format = candidate
                        streamUrl = candidateUrl
                        break
                    }
                    ResolverLog.tag(logTag).d("Could not resolve URL for ${candidate.mimeType}@${candidate.bitrate}, trying next candidate")
                }

                if (format == null || streamUrl == null) {
                    ResolverLog.tag(logTag).d("Stream URL not found for any candidate format on client: $clientLabel")
                    continue
                }

                ResolverLog.tag(logTag).d("Format selected: ${format.mimeType}, bitrate: ${format.bitrate} ($clientLabel)")

                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (currentClient.useWebPoTokens) {
                    try {
                        ResolverLog.tag(logTag).d("Applying n-transform to stream URL for ${currentClient.clientName}")
                        val transformed = EjsNTransformSolver.transformNParamInUrl(streamUrl!!)
                        if (transformed != streamUrl) {
                            streamUrl = transformed
                            ResolverLog.tag(logTag).d("N-transform applied successfully")
                        }
                    } catch (e: Exception) {
                        ResolverLog.tag(logTag).e(e, "N-transform failed: ${e.message}")
                    }
                }

                if (currentClient.useWebPoTokens && poToken?.streamingDataPoToken != null) {
                    ResolverLog.tag(logTag).d("Appending pot= parameter to stream URL")
                    val separator = if ("?" in streamUrl!!) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${UriCodec.encode(poToken.streamingDataPoToken)}"
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    ResolverLog.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                ResolverLog.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                val urlHost = try { java.net.URL(streamUrl).host } catch (e: Exception) { "unknown" }
                ResolverLog.tag(logTag).d("Stream URL host: $urlHost, pot length: ${poToken?.streamingDataPoToken?.length ?: 0}")

                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (isPrivatelyOwned || clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    ResolverLog.tag(logTag).d("Using stream without validation: ${currentClient.clientName}")
                    successClient = currentClient
                    ResolverLog.release(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (validateStatus(streamUrl!!, currentClient.streamHeaders())) {

                    ResolverLog.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    successClient = currentClient
                    PlaybackLogManager.log(
                        PlaybackLogLevel.INFO,
                        "Stream validated",
                        "client=${currentClient.clientName} itag=${format.itag} " +
                            "pot=${poToken?.streamingDataPoToken?.length ?: 0} " +
                            "bytes=${format.contentLength ?: 0}",
                    )

                    ResolverLog.release(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    ResolverLog.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")

                    if (currentClient.useWebPoTokens) {
                        var nTransformWorked = false

                        try {
                            val nTransformed = CipherDeobfuscator.transformNParamInUrl(streamUrl!!)
                            if (nTransformed != streamUrl) {
                                ResolverLog.tag(logTag).d("CipherDeobfuscator n-transform applied, re-validating...")
                                if (validateStatus(nTransformed, currentClient.streamHeaders())) {
                                    ResolverLog.tag(logTag).d("N-transformed URL VALIDATED OK!")
                                    streamUrl = nTransformed
                                    nTransformWorked = true
                                    successClient = currentClient
                                    ResolverLog.release(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId (cipher n-transform)")
                                }
                            }
                        } catch (e: Exception) {
                            ResolverLog.tag(logTag).e(e, "CipherDeobfuscator n-transform error")
                        }

                        if (nTransformWorked) break
                    }
                }
            } else {
                val status = streamPlayerResponse?.playabilityStatus?.status ?: "Unknown"
                val reason = streamPlayerResponse?.playabilityStatus?.reason ?: "No reason"
                ResolverLog.tag(logTag).d("Player response status not OK: $status, reason: $reason")
                PlaybackLogManager.log(PlaybackLogLevel.WARNING, "Client failed: ${client.clientName}", "$status: $reason")

                ResolverLog.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            ResolverLog.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            ResolverLog.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: Playability not OK for uploaded track - status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            throw ResolverRuntime.platform.playbackError(errorReason)
        }

        if (streamExpiresInSeconds == null) {
            ResolverLog.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            ResolverLog.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            ResolverLog.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        ResolverLog.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl?.take(100)}...")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamClient = successClient?.clientName ?: "unknown",
            streamHeaders = successClient?.streamHeaders().orEmpty(),
        )
    }.onFailure { e ->
        ResolverLog.tag(logTag).e(e, "Playback resolution failed")
        PlaybackLogManager.log(PlaybackLogLevel.ERROR, "Playback failed", "${e::class.simpleName}: ${e.message}")

        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }

    private val VIDEO_BACKGROUND_CLIENTS: Array<YouTubeClient> = arrayOf(
        MAIN_CLIENT,
        WEB_REMIX,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_VR_1_61_48,
        WEB,
    )

    /**
     * A video-only stream for [videoId]. Without a [policy] the closest to 720p in any codec (the
     * cover's video background, unchanged); with one, [VideoFormatChooser]'s choice (Ritme's stage).
     */
    suspend fun resolveVideoStreamUrl(videoId: String, policy: VideoFormatPolicy? = null): Result<String> = runCatching {
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        for (client in VIDEO_BACKGROUND_CLIENTS) {
            val response = try {
                backend.player(videoId, null, client, signatureTimestamp.timestamp, null).getOrNull()
            } catch (e: Exception) {
                ResolverLog.tag(logTag).d("resolveVideoStreamUrl: client ${client.clientName} threw: ${e.message}")
                null
            } ?: continue

            if (response.playabilityStatus.status != "OK") {
                ResolverLog.tag(logTag).d("resolveVideoStreamUrl: client ${client.clientName} not OK: ${response.playabilityStatus.status}")
                continue
            }

            val format = if (policy != null) {
                VideoFormatChooser.choose(response.streamingData?.adaptiveFormats, response.streamingData?.formats, policy)
            } else {
                response.streamingData?.adaptiveFormats
                    ?.filter { !it.isAudio && it.height != null }
                    ?.minByOrNull { kotlin.math.abs((it.height ?: 0) - 720) }
                    ?: response.streamingData?.formats?.firstOrNull { !it.isAudio }
            }

            if (format == null) {
                ResolverLog.tag(logTag).d("resolveVideoStreamUrl: client ${client.clientName} had no video format")
                continue
            }

            val url = try {
                findUrlOrNull(format, videoId, response, skipNewPipe = false)
            } catch (e: Exception) {
                ResolverLog.tag(logTag).d("resolveVideoStreamUrl: client ${client.clientName} URL resolution threw: ${e.message}")
                null
            }

            if (url != null) {
                ResolverLog.tag(logTag).d("resolveVideoStreamUrl: resolved via ${client.clientName}")
                return@runCatching url
            }
        }

        throw Exception("Could not resolve a playable video stream for videoId=$videoId after trying ${VIDEO_BACKGROUND_CLIENTS.size} clients")
    }

    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        ResolverLog.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return backend.player(videoId, playlistId, client = WEB_REMIX)
            .onSuccess { ResolverLog.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { ResolverLog.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: () -> Boolean,
    ): PlayerResponse.StreamingData.Format? =
        selectAudioFormatCandidates(
            playerResponse,
            audioQuality,
            networkMetered(),
        ).firstOrNull()

    /**
     * Returns every usable audio format ordered best-first for [audioQuality].
     *
     * Callers walk this list so that a format whose URL cannot be resolved (ciphered
     * without a working deobfuscator, for example) falls back to the next best one
     * instead of discarding the whole client.
     */
    internal fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
    ): List<PlayerResponse.StreamingData.Format> {
        ResolverLog.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: $networkMetered")

        val audioFormats = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.bitrate > 0 }
            ?.filter { !it.url.isNullOrEmpty() || !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() }
            .orEmpty()

        if (audioFormats.isEmpty()) {
            ResolverLog.tag(logTag).d("No suitable audio format found")
            return emptyList()
        }

        // Prefer the original (non auto-dubbed) track when the response offers both.
        val originalFormats = audioFormats.filter { it.isOriginal }
        val usableFormats = originalFormats.ifEmpty { audioFormats }

        val effectiveQuality = when (audioQuality) {
            AudioQuality.AUTO -> if (networkMetered) AudioQuality.MEDIUM else AudioQuality.HIGH
            // Lossless and Atmos tiers were Monochrome's (a source module now); when
            // YouTube is the source we still want its best available stream.
            AudioQuality.LOSSLESS, AudioQuality.HI_RES_LOSSLESS, AudioQuality.DOLBY_ATMOS -> AudioQuality.HIGH
            else -> audioQuality
        }

        // YouTube's audio ladder is roughly: ~50k (itag 139/249), ~70k (250),
        // ~128k AAC (140), ~160k Opus (251). The MEDIUM ceiling sits just under the
        // Opus rung so it lands on 128k AAC while HIGH keeps the best stream.
        val targetBitrateBps = when (effectiveQuality) {
            AudioQuality.LOW -> 70_000
            AudioQuality.MEDIUM -> 135_000
            else -> null // HIGH: take the best available
        }

        // YouTube's own AUDIO_QUALITY_* label outranks raw bitrate: a track can carry a
        // higher-bitrate stream that YouTube still labels MEDIUM, and picking it is what made
        // the HIGH setting feel like it did nothing.
        fun qualityRank(format: PlayerResponse.StreamingData.Format): Int = when (format.audioQuality) {
            "AUDIO_QUALITY_HIGH" -> 3
            "AUDIO_QUALITY_MEDIUM" -> 2
            "AUDIO_QUALITY_LOW" -> 1
            else -> 0
        }

        val preferHigher = compareByDescending<PlayerResponse.StreamingData.Format> { !it.url.isNullOrEmpty() }
            .thenByDescending { qualityRank(it) }
            .thenByDescending { it.audioChannels ?: 2 }
            .thenByDescending { it.bitrate }
            .thenByDescending { codecRank(extractCodec(it.mimeType)) }
            .thenByDescending { it.audioSampleRate ?: 0 }

        val preferLowerAboveTarget = compareByDescending<PlayerResponse.StreamingData.Format> { !it.url.isNullOrEmpty() }
            .thenBy { it.bitrate }
            .thenByDescending { codecRank(extractCodec(it.mimeType)) }
            .thenByDescending { it.audioSampleRate ?: 0 }

        val candidates = if (targetBitrateBps == null) {
            usableFormats.sortedWith(preferHigher)
        } else {
            // Best stream at or below the target, then the cheapest one above it.
            usableFormats.filter { it.bitrate <= targetBitrateBps }.sortedWith(preferHigher) +
                usableFormats.filter { it.bitrate > targetBitrateBps }.sortedWith(preferLowerAboveTarget)
        }

        ResolverLog.tag(logTag).d(
            "Format candidates ($effectiveQuality): " + candidates.take(8).joinToString {
                "${it.mimeType}@${it.bitrate}${if (it.url.isNullOrEmpty()) " (cipher)" else ""}"
            }
        )

        return candidates
    }

    private fun extractCodec(mimeType: String): String? =
        Regex("""codecs="([^"]+)"""").find(mimeType)
            ?.groupValues?.getOrNull(1)
            ?.split(",")?.firstOrNull()?.trim()

    private fun codecRank(codec: String?): Int = when {
        codec.isNullOrBlank() -> 0
        codec.contains("opus", ignoreCase = true) -> 3
        codec.contains("mp4a", ignoreCase = true) -> 2
        else -> 1
    }

    /**
     * Checks a resolved stream URL is actually fetchable.
     *
     * Uses a one-byte ranged GET rather than HEAD: googlevideo answers HEAD with 403
     * on URLs issued by the iOS/iPadOS clients even when the URL is perfectly good,
     * which previously caused every working stream to be thrown away.
     */
    /**
     * Checks whether a stream url actually serves bytes.
     *
     * The headers matter: googlevideo validates Origin/Referer against the client named in the
     * url, so probing without them can fail on a url that plays perfectly well. HEAD is not
     * usable here - googlevideo answers it with 403 even for good urls - so this is a ranged GET.
     */
    private fun validateStatus(url: String, headers: Map<String, String> = emptyMap()): Boolean =
        backend.validateStatus(url, headers)

    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        ResolverLog.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = backend.signatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                ResolverLog.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    ResolverLog.tag(logTag).d("Age-restricted content detected from NewPipe")
                    ResolverLog.release(TAG, "Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    ResolverLog.tag(logTag).e(error, "Failed to get signature timestamp")
                    error.printStackTrace()
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        ResolverLog.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        if (!format.url.isNullOrEmpty()) {
            ResolverLog.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            ResolverLog.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                ResolverLog.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            ResolverLog.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        if (skipNewPipe) {
            ResolverLog.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        val deobfuscatedUrl = backend.newPipeStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            ResolverLog.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        ResolverLog.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = backend.newPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                ResolverLog.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                ResolverLog.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        ResolverLog.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        ResolverLog.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
