package com.example.musicfy.ritme

import com.example.musicfy.extensions.metadata
import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.playback.queues.ListQueue
import com.example.musicfy.playback.queues.Queue

/**
 * The songs a queue would start with, for RitmeLocalAction.PlayQueue: the tapped song for radio
 * queues (their preloadItem), a list from its start index, otherwise nothing (it would need a
 * network load first; the controller then says "not available").
 */
fun Queue.ritmeSongs(): List<MediaMetadata?> =
    preloadItem?.let { listOf(it) }
        ?: (this as? ListQueue)?.let { queue -> queue.items.drop(queue.startIndex.coerceAtLeast(0)).map { it.metadata } }
        ?: emptyList()
