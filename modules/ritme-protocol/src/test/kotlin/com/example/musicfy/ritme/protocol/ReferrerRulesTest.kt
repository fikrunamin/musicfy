package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The verdicts in fixtures/rules/referrers.json; ritme-web's media-url-rules.test.ts checks the same file. */
class ReferrerRulesTest {
    private val cases = RitmeJson.parseToJsonElement(File("fixtures/rules/referrers.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun everySharedCaseGetsItsVerdict() {
        assertEquals("fixture case count", 20, cases.size)
        val mismatches = cases.mapNotNull { case ->
            val base = case.getValue("referrer").jsonPrimitive.content
            val referrer = case["padTo"]?.jsonPrimitive?.int?.let { base.padEnd(it, 'a') } ?: base
            val expected = case.getValue("reject").jsonPrimitive.contentOrNull
            val actual = MediaUrlRules.checkReferrer(referrer)?.name
            "$base: expected $expected, was $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun theLengthIsCappedAt200() {
        val longest = "https://" + "a".repeat(187) + ".com/"
        assertEquals(200, longest.length)
        assertNull(MediaUrlRules.checkReferrer(longest))
        assertEquals(MediaUrlRules.Rejection.TOO_LONG, MediaUrlRules.checkReferrer("https://" + "a".repeat(188) + ".com/"))
    }

    @Test
    fun aPagesOriginBecomesItsReferrer() {
        assertEquals("https://video.example.com/", MediaUrlRules.referrerOf("https://Video.Example.com/watch?v=1#t=3"))
        assertEquals("http://example.com:8080/", MediaUrlRules.referrerOf("http://example.com:8080"))
        assertNull(MediaUrlRules.referrerOf("http://192.168.1.1/admin"))
        assertNull(MediaUrlRules.referrerOf("https://user:pw@example.com/"))
        assertNull(MediaUrlRules.referrerOf("about:blank"))
    }

    @Test
    fun isAcceptableReferrerIsCheckReferrerWithoutTheReason() {
        assertEquals(true, MediaUrlRules.isAcceptableReferrer("https://example.com/"))
        assertEquals(false, MediaUrlRules.isAcceptableReferrer("https://example.com/page"))
    }
}
