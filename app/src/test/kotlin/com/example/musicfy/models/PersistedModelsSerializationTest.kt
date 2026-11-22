package com.example.musicfy.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.time.LocalDateTime

/**
 * MusicService saves the queue with Java serialization. If a class's stream identity changes (its
 * serialVersionUID, or its fields), every user's saved queue fails to restore after the update.
 * Run with -PupdateGolden=true only on code whose serialized form is known-good.
 */
class PersistedModelsSerializationTest {
    private val uidGolden = File("src/test/resources/serialization/serial-version-uids.golden.txt")
    private val queueGolden = File("src/test/resources/serialization/persist-queue.golden.bin")
    private val update = System.getProperty("updateGolden") == "true"

    private val serializedClasses = listOf(
        "com.example.musicfy.models.MediaMetadata",
        "com.example.musicfy.models.MediaMetadata\$Artist",
        "com.example.musicfy.models.MediaMetadata\$Album",
        "com.example.musicfy.models.PersistQueue",
        "com.example.musicfy.models.QueueType",
        "com.example.musicfy.models.QueueType\$LIST",
        "com.example.musicfy.models.QueueType\$YOUTUBE",
        "com.example.musicfy.models.QueueType\$YOUTUBE_ALBUM_RADIO",
        "com.example.musicfy.models.QueueType\$LOCAL_ALBUM_RADIO",
        "com.example.musicfy.models.QueueData",
        "com.example.musicfy.models.QueueData\$YouTubeData",
        "com.example.musicfy.models.QueueData\$YouTubeAlbumRadioData",
        "com.example.musicfy.models.QueueData\$LocalAlbumRadioData",
    )

    private fun sampleQueue() = PersistQueue(
        title = "Golden queue",
        items = listOf(
            MediaMetadata(
                id = "dQw4w9WgXcQ",
                title = "Never Gonna Give You Up",
                artists = listOf(MediaMetadata.Artist(id = "UCuAXFkgsw1L7xaCfnd5JJOw", name = "Rick Astley")),
                duration = 213,
                thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                album = MediaMetadata.Album(id = "MPREb_golden", title = "Whenever You Need Somebody"),
                setVideoId = "set-1",
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                explicit = false,
                liked = true,
                likedDate = LocalDateTime.of(2026, 11, 22, 10, 30, 0),
                inLibrary = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                libraryAddToken = "add-token",
                libraryRemoveToken = "remove-token",
                suggestedBy = "radio",
                localUri = null,
            ),
        ),
        mediaItemIndex = 0,
        position = 42_000L,
        queueType = QueueType.YOUTUBE_ALBUM_RADIO,
        queueData = QueueData.YouTubeAlbumRadioData(
            playlistId = "RDAMPLgolden",
            albumSongCount = 12,
            continuation = "cont",
            firstTimeLoaded = true,
        ),
    )

    @Test
    fun serialVersionUidsMatchGolden() {
        val actual = serializedClasses.joinToString("\n") {
            "$it=${ObjectStreamClass.lookup(Class.forName(it)).serialVersionUID}"
        }
        if (update) {
            uidGolden.parentFile.mkdirs()
            uidGolden.writeText(actual + "\n")
            return
        }
        assertEquals(uidGolden.readText().trim(), actual)
    }

    @Test
    fun goldenQueueStillDeserializes() {
        if (update) {
            queueGolden.parentFile.mkdirs()
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { it.writeObject(sampleQueue()) }
            queueGolden.writeBytes(bytes.toByteArray())
            return
        }
        val restored = ObjectInputStream(queueGolden.inputStream()).use { it.readObject() as PersistQueue }
        val expected = sampleQueue()
        assertEquals(expected.title, restored.title)
        assertEquals(expected.items, restored.items)
        assertEquals(expected.mediaItemIndex, restored.mediaItemIndex)
        assertEquals(expected.position, restored.position)
        assertEquals(expected.queueData, restored.queueData)
        // Plain Kotlin `object`s deserialize as fresh instances, so compare by type, not identity.
        assertTrue(restored.queueType is QueueType.YOUTUBE_ALBUM_RADIO)
    }
}
