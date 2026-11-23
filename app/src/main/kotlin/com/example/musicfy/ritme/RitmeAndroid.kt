package com.example.musicfy.ritme

import android.content.Context
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.RitmeServerUrlKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.ui.component.TopToaster
import com.example.musicfy.utils.dataStore
import com.example.musicfy.utils.get

/** Ritme toasts through the app's TopToaster pill. */
class TopToastRitmeNotifier(private val context: Context) : RitmeNotifier {
    override fun show(toast: RitmeToast) {
        val res = context.resources
        when (toast) {
            RitmeToast.QueueSaved -> TopToaster.show(res.getString(R.string.ritme_toast_queue_saved), iconRes = R.drawable.group)
            RitmeToast.NotAvailable -> TopToaster.show(res.getString(R.string.ritme_toast_unavailable), iconRes = R.drawable.group)
            RitmeToast.ControlsLocked -> TopToaster.error(res.getString(R.string.ritme_toast_locked))
            RitmeToast.PausedLocally -> TopToaster.show(res.getString(R.string.ritme_toast_paused_locally), iconRes = R.drawable.group)
            RitmeToast.Reconnecting -> TopToaster.show(res.getString(R.string.ritme_reconnecting), iconRes = R.drawable.group)
            RitmeToast.CastingBlocked -> TopToaster.error(res.getString(R.string.ritme_toast_casting))
            RitmeToast.CastingStopped -> TopToaster.show(res.getString(R.string.ritme_toast_casting_stopped), iconRes = R.drawable.group)
            is RitmeToast.Added -> TopToaster.show(
                res.getQuantityString(R.plurals.ritme_toast_added, toast.count, toast.count),
                iconRes = R.drawable.queue_music,
            )
            is RitmeToast.SkippedLocal -> TopToaster.show(
                res.getQuantityString(R.plurals.ritme_toast_skipped_local, toast.count, toast.count),
                iconRes = R.drawable.group,
            )
            is RitmeToast.Ended -> RitmeTexts.endedToast(toast.reason)?.let {
                TopToaster.show(res.getString(it), iconRes = R.drawable.group)
            }
            is RitmeToast.CommandFailed -> TopToaster.error(res.getString(RitmeTexts.notice(toast.key)))
        }
    }
}

/** Server URL (blank or invalid = official server) and display name (falls back to the profile name). */
class DataStoreRitmeSettings(private val context: Context) : RitmeSettings {
    override val serverUrl: String
        get() = context.dataStore[RitmeServerUrlKey]?.let(RitmeLinks::normalizeServerUrl) ?: RitmeConfig.DEFAULT_SERVER_URL

    override val displayName: String
        get() = context.dataStore[RitmeDisplayNameKey]?.takeIf { it.isNotBlank() } ?: context.dataStore[UsernameKey].orEmpty()
}
