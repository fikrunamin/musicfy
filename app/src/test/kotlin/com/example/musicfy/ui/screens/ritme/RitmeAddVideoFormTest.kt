package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.video.AddVideoState
import com.example.musicfy.ritme.video.RitmeAddVideoModel
import com.example.musicfy.ritme.video.VideoChoice
import com.example.musicfy.ritme.video.VideoLookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sheet's LaunchedEffect(text, searches) runs [RitmeAddVideoForm.resolve] and is cancelled and
 * restarted on every edit; these tests drive it the same way (with the real debounce).
 */
class RitmeAddVideoFormTest {

    private val abc = VideoChoice("dQw4w9WgXcQ", "abc", "Channel", 213, null, live = false)

    /** Searches answer at once, except [held], which waits until the test cancels it. */
    private class HeldLookup(val answers: Map<String, List<VideoChoice>>, val held: String) : VideoLookup {
        val searchCalls = mutableListOf<String>()
        val holding = CompletableDeferred<Unit>()

        override suspend fun info(videoId: String): VideoChoice? = null

        override suspend fun search(query: String): List<VideoChoice> {
            searchCalls += query
            if (query == held) {
                holding.complete(Unit)
                CompletableDeferred<Nothing>().await()
            }
            return answers[query].orEmpty()
        }

        override suspend fun probeDurationMs(url: String): Long? = null
    }

    @Test
    fun undoingAnEditWhoseSearchWasCancelledShowsTheEarlierResultAgain() = runBlocking {
        val lookup = HeldLookup(mapOf("abc" to listOf(abc)), held = "abcd")
        val model = RitmeAddVideoModel(lookup)
        val form = RitmeAddVideoForm(initialText = null, roomMode = false)

        form.text = "abc"
        form.resolve(model)
        assertEquals(AddVideoState.Results(listOf(abc)), form.state)

        // Typed "d" and paused: "Searching…" while the search is in flight...
        form.text = "abcd"
        val typing = launch { form.resolve(model) }
        lookup.holding.await()
        assertEquals(AddVideoState.Loading, form.state)
        // ...then deleted it: the edit cancels that effect mid-flight and starts one for "abc".
        typing.cancelAndJoin()
        form.text = "abc"
        form.resolve(model)

        assertEquals(AddVideoState.Results(listOf(abc)), form.state)
    }

    @Test
    fun comingBackToTheTextOnScreenDoesNotSearchAgain() = runBlocking {
        val lookup = HeldLookup(mapOf("abc" to listOf(abc)), held = "abcd")
        val model = RitmeAddVideoModel(lookup)
        val form = RitmeAddVideoForm(initialText = "abc", roomMode = false)

        form.resolve(model)
        // Back into composition (or an edit undone before its pause ended): the result stays.
        form.resolve(model)

        assertEquals(listOf("abc"), lookup.searchCalls)
        assertEquals(AddVideoState.Results(listOf(abc)), form.state)
    }
}
