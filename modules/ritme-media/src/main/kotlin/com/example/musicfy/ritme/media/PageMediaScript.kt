package com.example.musicfy.ritme.media

/**
 * The in-app browser's page script (spec 4.7 step 2, D10): injected at document start into every
 * frame, it reports the frame's `<video>` elements, og:video, JSON-LD VideoObjects and title as one
 * JSON message to [BRIDGE_NAME] (Android: WebViewCompat.addWebMessageListener; desktop later: JCEF).
 * The page is untrusted: [PageReports.parse] checks everything again.
 * Tests: ritme-web/src/external/page-media.test.ts runs it in jsdom.
 */
object PageMediaScript {
    const val BRIDGE_NAME = "ritmeMedia"
    private const val RESOURCE = "/com/example/musicfy/ritme/media/page-media.js"

    val source: String by lazy {
        val stream = PageMediaScript::class.java.getResourceAsStream(RESOURCE) ?: error("missing $RESOURCE")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
