package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The verdicts in fixtures/rules/media-urls.json; ritme-web's media-url-rules.test.ts checks the same file. */
class MediaUrlRulesTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/media-urls.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVerdict() {
        assertEquals("fixture case count", 120, cases.size)
        val mismatches = cases.mapNotNull { case ->
            val base = case.getValue("url").jsonPrimitive.content
            val url = case["padTo"]?.jsonPrimitive?.int?.let { base.padEnd(it, 'a') } ?: base
            val expected = case.getValue("reject").jsonPrimitive.contentOrNull
            val actual = MediaUrlRules.check(url)?.name
            "${base.take(80)} (length ${url.length}): expected $expected, was $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun theLengthIsCappedAt2048() {
        val base = "https://cdn.example.com/"
        assertNull(MediaUrlRules.check(base + "a".repeat(MediaUrlRules.MAX_URL_LENGTH - base.length)))
        assertEquals(
            MediaUrlRules.Rejection.TOO_LONG,
            MediaUrlRules.check(base + "a".repeat(MediaUrlRules.MAX_URL_LENGTH - base.length + 1)),
        )
    }

    @Test
    fun isAcceptableIsCheckWithoutTheReason() {
        assertTrue(MediaUrlRules.isAcceptableMediaUrl("https://cdn.example.com/v.mp4"))
        assertFalse(MediaUrlRules.isAcceptableMediaUrl("http://10.0.0.1/v.mp4"))
    }
}
