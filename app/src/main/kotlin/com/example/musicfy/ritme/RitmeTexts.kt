package com.example.musicfy.ritme

import androidx.annotation.StringRes
import com.example.musicfy.R
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.session.NoticeKey

/** Which string describes a Ritme rejection or ending (spec 4.1). */
object RitmeTexts {
    @StringRes
    fun notice(key: NoticeKey): Int = when (key) {
        NoticeKey.FORBIDDEN -> R.string.ritme_toast_forbidden
        NoticeKey.RATE_LIMITED -> R.string.ritme_error_rate_limited
        NoticeKey.QUEUE_FULL -> R.string.ritme_toast_queue_full
        NoticeKey.INVALID -> R.string.ritme_error_invalid
        NoticeKey.FAILED -> R.string.ritme_error_generic
    }

    /** Toast after leaving a room for [reason]; null for a plain "Leave". */
    @StringRes
    fun endedToast(reason: EndReason): Int? = when (reason) {
        EndReason.Kicked -> R.string.ritme_toast_kicked
        EndReason.Closed -> R.string.ritme_toast_closed
        EndReason.Lost -> R.string.ritme_toast_lost
        else -> null
    }

    /** Inline error in the start/join sheet. */
    @StringRes
    fun entryError(reason: EndReason?): Int? = when (reason) {
        EndReason.Unreachable -> R.string.ritme_error_unreachable
        EndReason.Lost -> R.string.ritme_toast_lost
        is EndReason.Rejected -> when (reason.code) {
            ErrorCode.ROOM_NOT_FOUND -> R.string.ritme_error_not_found
            ErrorCode.ROOM_FULL -> R.string.ritme_error_full
            ErrorCode.UPGRADE_REQUIRED -> R.string.ritme_error_upgrade
            ErrorCode.RATE_LIMITED -> R.string.ritme_error_rate_limited
            ErrorCode.INVALID -> R.string.ritme_error_invalid
            else -> R.string.ritme_error_generic
        }
        else -> null
    }
}
