// PersistPlayerState.kt

package com.example.musicfy.models

import java.io.Serializable

data class PersistPlayerState(
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val volume: Float,
    val currentPosition: Long,
    val currentMediaItemIndex: Int,
    val playbackState: Int,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {
    // The value this class had inside app/ (see PersistQueue.kt). MusicService restores the saved
    // player state with ObjectInputStream, so it must never change.
    private companion object {
        private const val serialVersionUID: Long = 1216235762309407826L
    }
}
