package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExternalTargetTest {
    @Test
    fun opaqueUrisShowSchemeAndTheRestTruncated() {
        assertEquals("mailto:a@b.example", ExternalTarget.describe("mailto:a@b.example?subject=hi"))
        assertEquals("tel:+6281234", ExternalTarget.describe("tel:+6281234"))
        val long = "sms:" + "1".repeat(100)
        assertEquals("sms:" + "1".repeat(60), ExternalTarget.describe(long))
    }

    @Test
    fun intentShowsThePackageElseTheDataScheme() {
        assertEquals("com.example.app", ExternalTarget.describe("intent://open/x#Intent;scheme=exapp;package=com.example.app;end"))
        assertEquals("exapp://open", ExternalTarget.describe("intent://open/x#Intent;scheme=exapp;end"))
        assertEquals("exapp:", ExternalTarget.describe("intent:#Intent;scheme=exapp;end"))
        assertFalse(ExternalTarget.describe("intent:#Intent;end").contains("?://"))
    }

    @Test
    fun hierarchicalUrisShowSchemeAndHost() {
        assertEquals("market://details", ExternalTarget.describe("market://details?id=com.x"))
        assertEquals("https://play.google.com", ExternalTarget.describe("https://play.google.com/store/apps"))
    }
}
