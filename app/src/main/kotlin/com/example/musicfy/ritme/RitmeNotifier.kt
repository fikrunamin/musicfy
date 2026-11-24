package com.example.musicfy.ritme

import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.session.NoticeKey

sealed interface RitmeToast {
    data object QueueSaved : RitmeToast
    data object NotAvailable : RitmeToast
    data object ControlsLocked : RitmeToast
    data object PausedLocally : RitmeToast
    data object Reconnecting : RitmeToast
    data object CastingBlocked : RitmeToast
    /** Casting started while in a room: this device left it. */
    data object CastingStopped : RitmeToast
    /** [videos]: entries from "Add video", said as videos rather than songs. */
    data class Added(val count: Int, val videos: Boolean = false) : RitmeToast
    /** A list add cut short by the room's 500-entry cap: [count] went in, the rest did not fit. */
    data class AddedRestFull(val count: Int) : RitmeToast
    data class SkippedLocal(val count: Int) : RitmeToast
    data class Ended(val reason: EndReason) : RitmeToast
    /** [videos]: refused while adding videos (ADD_FORBIDDEN says "adding videos"). */
    data class CommandFailed(val key: NoticeKey, val videos: Boolean = false) : RitmeToast
}

fun interface RitmeNotifier {
    fun show(toast: RitmeToast)
}
