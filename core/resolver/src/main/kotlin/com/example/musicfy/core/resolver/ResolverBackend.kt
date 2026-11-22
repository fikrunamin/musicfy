package com.example.musicfy.core.resolver

import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse

/** The network calls the resolver makes. Production: [YouTubeResolverBackend]. Tests: fakes. */
interface ResolverBackend {
    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
    ): Result<PlayerResponse>

    suspend fun refreshVisitorData(): Result<String>

    fun signatureTimestamp(videoId: String): Result<Int>

    fun newPipeStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String?

    fun newPipeStreamUrls(videoId: String): List<Pair<Int, String>>

    /** One-byte ranged GET: does googlevideo actually serve this url with these headers? */
    fun validateStatus(url: String, headers: Map<String, String>): Boolean
}
