package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.FakeBotGuardRuntime
import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.potoken.BadWebViewException
import com.example.musicfy.utils.potoken.PoTokenGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PoTokenGeneratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        TestResolver.install(tmp.root)
    }

    @Test
    fun unsupportedRuntimeYieldsNoTokenAndStartsNoSession() = runBlocking {
        val rt = FakeBotGuardRuntime(isSupported = false)
        assertNull(PoTokenGenerator { rt }.poTokenFor("vid", "visitor"))
        assertEquals(0, rt.sessionsCreated)
    }

    @Test
    fun sessionTokenGoesInThePlayerRequestAndVideoTokenOnTheUrl() = runBlocking {
        val rt = FakeBotGuardRuntime()
        val result = PoTokenGenerator { rt }.poTokenFor("vid", "visitor")!!
        assertEquals("pot-visitor", result.playerRequestPoToken)
        assertEquals("pot-vid", result.streamingDataPoToken)
    }

    @Test
    fun sameSessionIdReusesTheEngine() = runBlocking {
        val rt = FakeBotGuardRuntime()
        val gen = PoTokenGenerator { rt }
        gen.poTokenFor("v1", "s")
        gen.poTokenFor("v2", "s")
        assertEquals(1, rt.sessionsCreated)
        assertEquals(listOf("s", "v1", "v2"), rt.minted)
    }

    @Test
    fun newSessionIdRecreatesAndDisposesTheOldEngine() = runBlocking {
        val rt = FakeBotGuardRuntime()
        val gen = PoTokenGenerator { rt }
        gen.poTokenFor("v1", "s1")
        gen.poTokenFor("v2", "s2")
        assertEquals(2, rt.sessionsCreated)
        assertTrue(rt.sessions[0].disposed)
    }

    @Test
    fun expiredEngineIsRecreated() = runBlocking {
        val rt = FakeBotGuardRuntime()
        val gen = PoTokenGenerator { rt }
        gen.poTokenFor("v1", "s")
        rt.sessions[0].expired = true
        gen.poTokenFor("v2", "s")
        assertEquals(2, rt.sessionsCreated)
    }

    @Test
    fun failureStartsACooldown() = runBlocking {
        val rt = FakeBotGuardRuntime(failWith = IllegalStateException("boom"))
        val gen = PoTokenGenerator { rt }
        assertNull(gen.poTokenFor("v1", "s"))
        assertNull(gen.poTokenFor("v2", "s"))
        assertEquals(1, rt.sessionsCreated)
    }

    @Test
    fun brokenWebViewDisablesMinting() = runBlocking {
        val rt = FakeBotGuardRuntime(failWith = BadWebViewException("SyntaxError"))
        val gen = PoTokenGenerator { rt }
        assertNull(gen.poTokenFor("v1", "s"))
        assertNull(gen.poTokenFor("v2", "s"))
        assertEquals(1, rt.sessionsCreated)
    }
}
