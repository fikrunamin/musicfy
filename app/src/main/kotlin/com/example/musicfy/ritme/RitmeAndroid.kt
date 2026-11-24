package com.example.musicfy.ritme

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.musicfy.R
import com.example.musicfy.constants.RitmeDisplayNameKey
import com.example.musicfy.constants.RitmeServerUrlKey
import com.example.musicfy.constants.UsernameKey
import com.example.musicfy.ui.component.TopToaster
import com.example.musicfy.utils.dataStore
import com.example.musicfy.utils.get

/** A Ritme toast's text, ready for either surface. */
data class RitmeToastText(val text: String, val isError: Boolean, val iconRes: Int)

/**
 * Where a toast goes: TopToaster's pill only while an activity is started (its host draws it);
 * otherwise, e.g. a notification or headset control while the app is in the background, a system
 * toast, so it is neither lost nor replayed late when the app comes back.
 */
class RitmeToastRouter(
    private val appStarted: () -> Boolean,
    private val pill: (RitmeToastText) -> Unit,
    private val system: (String) -> Unit,
) {
    fun deliver(toast: RitmeToastText) {
        if (appStarted()) pill(toast) else system(toast.text)
    }
}

/** Ritme toasts through the app's TopToaster pill, or a system toast while the app is in the background. */
class TopToastRitmeNotifier(
    private val context: Context,
    private val router: RitmeToastRouter = RitmeToastRouter(
        appStarted = {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        },
        pill = { t ->
            if (t.isError) TopToaster.error(t.text) else TopToaster.show(t.text, iconRes = t.iconRes)
        },
        system = { text -> Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() },
    ),
) : RitmeNotifier {
    override fun show(toast: RitmeToast) {
        textFor(toast)?.let(router::deliver)
    }

    private fun textFor(toast: RitmeToast): RitmeToastText? {
        val res = context.resources
        fun info(text: String, icon: Int = R.drawable.group) = RitmeToastText(text, isError = false, iconRes = icon)
        fun error(text: String) = RitmeToastText(text, isError = true, iconRes = R.drawable.close)
        return when (toast) {
            RitmeToast.QueueSaved -> info(res.getString(R.string.ritme_toast_queue_saved))
            RitmeToast.NotAvailable -> info(res.getString(R.string.ritme_toast_unavailable))
            RitmeToast.ControlsLocked -> error(res.getString(R.string.ritme_toast_locked))
            RitmeToast.PausedLocally -> info(res.getString(R.string.ritme_toast_paused_locally))
            RitmeToast.Reconnecting -> info(res.getString(R.string.ritme_reconnecting))
            RitmeToast.CastingBlocked -> error(res.getString(R.string.ritme_toast_casting))
            RitmeToast.CastingStopped -> info(res.getString(R.string.ritme_toast_casting_stopped))
            is RitmeToast.Added -> info(
                res.getQuantityString(if (toast.videos) R.plurals.ritme_toast_added_video else R.plurals.ritme_toast_added, toast.count, toast.count),
                R.drawable.queue_music,
            )
            is RitmeToast.AddedRestFull ->
                info(res.getQuantityString(R.plurals.ritme_toast_added_rest_full, toast.count, toast.count), R.drawable.queue_music)
            is RitmeToast.SkippedLocal ->
                info(res.getQuantityString(R.plurals.ritme_toast_skipped_local, toast.count, toast.count))
            is RitmeToast.Ended -> RitmeTexts.endedToast(toast.reason)?.let { info(res.getString(it)) }
            is RitmeToast.CommandFailed -> error(res.getString(RitmeTexts.notice(toast.key, toast.videos)))
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
