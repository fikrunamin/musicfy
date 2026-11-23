package com.example.musicfy.core.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOfferTest {
    @Test
    fun releaseBuildOffersNewerVersion() {
        assertTrue(isNewerThanInstalled("8.1.0", "8.0.0", isDebug = false))
    }

    @Test
    fun releaseBuildDoesNotOfferSameVersion() {
        assertFalse(isNewerThanInstalled("8.0.0", "8.0.0", isDebug = false))
    }

    @Test
    fun debugBuildNeverOffersReleases() {
        assertFalse(isNewerThanInstalled("9.0.0", "8.0.0", isDebug = true))
    }
}
