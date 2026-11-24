package com.example.musicfy.ritme

/**
 * What MainActivity does with an intent that may be a "Tambah ke Ritme" share (spec 4.6). Pure.
 *
 * Every intent reaches MainActivity's handler twice on a warm start (onNewIntent, then its
 * OnNewIntent listener get the same object), and a recreated activity (config change, process
 * death) sees its launch intent again. So the caller consumes the intent (clears its action and
 * text) on [Share] and [Ignore], and an alias intent never falls through to the generic deep-link
 * handler, which would play the shared link.
 */
sealed interface RitmeShareRoute {
    /** Not an intent for the share alias: the usual deep-link handling. */
    data object NotAlias : RitmeShareRoute

    /** An alias intent with nothing to do (already handled, a recreated activity, Ritme off). */
    data object Ignore : RitmeShareRoute

    /** Open it: the join form when [text] holds a room link, else "Add video" with [text]. */
    data class Share(val text: String?) : RitmeShareRoute

    companion object {
        const val ALIAS_CLASS_SUFFIX = ".RitmeShareAlias"
        const val ACTION_SEND = "android.intent.action.SEND"

        /**
         * [consumed]: the intent was handled before (the activity was restored from saved state).
         * Only a plain-text SEND carries text; the text is untrusted and only fills a field.
         */
        fun of(action: String?, componentClass: String?, type: String?, text: String?, consumed: Boolean, enabled: Boolean): RitmeShareRoute {
            if (componentClass?.endsWith(ALIAS_CLASS_SUFFIX) != true) return NotAlias
            if (consumed || !enabled || action != ACTION_SEND) return Ignore
            return Share(text.takeIf { type == "text/plain" })
        }
    }
}
