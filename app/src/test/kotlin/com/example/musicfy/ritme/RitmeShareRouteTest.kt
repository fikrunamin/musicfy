package com.example.musicfy.ritme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MainActivity hands every intent to [RitmeShareRoute.of]; Share and Ignore both consume the intent
 * (action and text cleared), so a second dispatch of the same intent object sees it consumed.
 */
class RitmeShareRouteTest {

    private val alias = "com.example.musicfy.RitmeShareAlias"
    private val main = "com.example.musicfy.MainActivity"
    private val send = "android.intent.action.SEND"
    private val link = "Watch this https://youtu.be/dQw4w9WgXcQ"

    /** The intent object Android hands to onNewIntent and then again to its listeners. */
    private class FakeIntent(var action: String?, val component: String?, val type: String?, var text: String?)

    /** What MainActivity does: route, then consume unless the route is NotAlias. */
    private fun dispatch(intent: FakeIntent, restored: Boolean = false, enabled: Boolean = true): RitmeShareRoute {
        val route = RitmeShareRoute.of(intent.action, intent.component, intent.type, intent.text, consumed = restored, enabled = enabled)
        if (route != RitmeShareRoute.NotAlias) {
            intent.action = null
            intent.text = null
        }
        return route
    }

    @Test
    fun aPlainTextShareToTheAliasIsShared() {
        assertEquals(RitmeShareRoute.Share(link), RitmeShareRoute.of(send, alias, "text/plain", link, consumed = false, enabled = true))
    }

    @Test
    fun aWarmShareDispatchedTwiceOpensOnceAndTheSecondPassIsIgnored() {
        // onNewIntent and the OnNewIntent listener both get the same object.
        val intent = FakeIntent(send, alias, "text/plain", link)
        assertEquals(RitmeShareRoute.Share(link), dispatch(intent))
        assertEquals(RitmeShareRoute.Ignore, dispatch(intent))
    }

    @Test
    fun aRoomLinkSharedWarmKeepsItsTextOnTheFirstPassOnly() {
        val roomLink = "Join me https://ritme.dubeli.id/r/K7M2QX"
        val intent = FakeIntent(send, alias, "text/plain", roomLink)
        assertEquals(RitmeShareRoute.Share(roomLink), dispatch(intent))
        // never an empty "Add video" sheet replacing the join form
        assertEquals(RitmeShareRoute.Ignore, dispatch(intent))
    }

    @Test
    fun aRecreatedActivityNeverReopensNorFallsThroughToPlayback() {
        // Process death redelivers the launch intent with its text; a config change keeps it.
        val intent = FakeIntent(send, alias, "text/plain", link)
        assertEquals(RitmeShareRoute.Ignore, dispatch(intent, restored = true))
    }

    @Test
    fun anAliasIntentWithRitmeOffIsIgnoredNotPlayed() {
        assertEquals(RitmeShareRoute.Ignore, RitmeShareRoute.of(send, alias, "text/plain", link, consumed = false, enabled = false))
    }

    @Test
    fun anAliasIntentThatIsNotASendIsIgnored() {
        assertEquals(RitmeShareRoute.Ignore, RitmeShareRoute.of(null, alias, "text/plain", link, consumed = false, enabled = true))
        assertEquals(RitmeShareRoute.Ignore, RitmeShareRoute.of("android.intent.action.VIEW", alias, null, null, consumed = false, enabled = true))
    }

    @Test
    fun aShareThatIsNotPlainTextOpensTheSheetEmpty() {
        assertEquals(RitmeShareRoute.Share(null), RitmeShareRoute.of(send, alias, "image/png", link, consumed = false, enabled = true))
        assertEquals(RitmeShareRoute.Share(null), RitmeShareRoute.of(send, alias, "text/plain", null, consumed = false, enabled = true))
    }

    @Test
    fun intentsForTheMainActivityAreNotOurs() {
        assertEquals(RitmeShareRoute.NotAlias, RitmeShareRoute.of(send, main, "text/plain", link, consumed = false, enabled = true))
        assertEquals(RitmeShareRoute.NotAlias, RitmeShareRoute.of(send, null, "text/plain", link, consumed = true, enabled = true))
    }
}
