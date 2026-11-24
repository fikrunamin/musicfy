package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The cases in fixtures/rules/youtube-links.json; ritme-web's video-link.test.ts checks the same file. */
class YouTubeLinksTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/youtube-links.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVideoId() {
        assertTrue("fixture has cases", cases.size >= 40)
        val mismatches = cases.mapNotNull { case ->
            val input = case.getValue("input").jsonPrimitive.content
            val expectedId = case.getValue("videoId").jsonPrimitive.contentOrNull
            val expectedMusic = case["music"]?.jsonPrimitive?.boolean ?: false
            val actual = YouTubeLinks.parse(input)
            val ok = actual?.videoId == expectedId && (expectedId == null || actual?.music == expectedMusic)
            "'$input': expected $expectedId (music=$expectedMusic), was $actual".takeUnless { ok }
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
