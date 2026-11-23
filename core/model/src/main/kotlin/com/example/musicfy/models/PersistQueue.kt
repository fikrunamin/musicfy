// PersistQueue.kt

package com.example.musicfy.models

import java.io.Serializable

// Every serialVersionUID below is the value these classes had while compiled inside app/
// (recorded in core/model/src/test/resources/serialization/serial-version-uids.golden.txt).
// MusicService restores the saved queue with ObjectInputStream, so they must never change.

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val queueType: QueueType = QueueType.LIST,
    val queueData: QueueData? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 6429623122233171446L
    }
}

sealed class QueueType : Serializable {
    object LIST : QueueType() {
        private const val serialVersionUID: Long = 1911115016473477586L
    }

    object YOUTUBE : QueueType() {
        private const val serialVersionUID: Long = 3755530007162349819L
    }

    object YOUTUBE_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID: Long = 1392381929295877222L
    }

    object LOCAL_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID: Long = 5328212382352355839L
    }

    private companion object {
        private const val serialVersionUID: Long = 8325808744034862222L
    }
}

sealed class QueueData : Serializable {
    data class YouTubeData(
        val endpoint: String,
        val continuation: String? = null
    ) : QueueData() {
        private companion object {
            private const val serialVersionUID: Long = 4769908787355566342L
        }
    }

    data class YouTubeAlbumRadioData(
        val playlistId: String,
        val albumSongCount: Int = 0,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false
    ) : QueueData() {
        private companion object {
            private const val serialVersionUID: Long = -5168524426105764017L
        }
    }

    data class LocalAlbumRadioData(
        val albumId: String,
        val startIndex: Int = 0,
        val playlistId: String? = null,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false
    ) : QueueData() {
        private companion object {
            private const val serialVersionUID: Long = -1023867307323175778L
        }
    }

    private companion object {
        private const val serialVersionUID: Long = -8582473634277206487L
    }
}
