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
    data class Added(val count: Int) : RitmeToast
    data class SkippedLocal(val count: Int) : RitmeToast
    data class Ended(val reason: EndReason) : RitmeToast
    data class CommandFailed(val key: NoticeKey) : RitmeToast
}

fun interface RitmeNotifier {
    fun show(toast: RitmeToast)
}
