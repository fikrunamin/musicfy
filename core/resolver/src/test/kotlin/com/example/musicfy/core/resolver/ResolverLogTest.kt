package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.RecordingLogger
import com.example.musicfy.core.resolver.testing.TestResolver
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolverLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun timberShapedCallsReachTheInstalledLogger() {
        val logger = RecordingLogger()
        TestResolver.install(tmp.root, logger = logger)
        ResolverLog.tag("T").d("debug")
        ResolverLog.tag("T").e(IllegalStateException("boom"), "failed")
        ResolverLog.release("T", "always")
        assertEquals(listOf("D/T: debug", "E/T: failed [boom]", "R/T: always"), logger.lines)
    }
}
