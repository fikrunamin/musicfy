package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.FakeJsRuntime
import com.example.musicfy.core.resolver.testing.FakeNSolverSession
import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.sabr.EjsNTransformSolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EjsNTransformSolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        EjsNTransformSolver.resetForTests()
        TestResolver.writePlayerJsCache(tmp.root)
    }

    @Test
    fun urlWithoutNIsUntouchedAndNoSolverIsLoaded() = runBlocking {
        val js = FakeJsRuntime()
        TestResolver.install(tmp.root, jsRuntime = js)
        assertEquals("https://h/v?id=1", EjsNTransformSolver.transformNParamInUrl("https://h/v?id=1"))
        assertEquals(0, js.nSolverLoads)
    }

    @Test
    fun nIsDecodedTransformedAndReEncoded() = runBlocking {
        val js = FakeJsRuntime(nSolver = { FakeNSolverSession(mapOf("a+b" to "c d")) })
        TestResolver.install(tmp.root, jsRuntime = js)
        assertEquals(
            "https://h/v?id=1&n=c%20d&c=WEB_REMIX",
            EjsNTransformSolver.transformNParamInUrl("https://h/v?id=1&n=a%2Bb&c=WEB_REMIX"),
        )
    }

    @Test
    fun solverIsLoadedOnceAndReused() = runBlocking {
        val js = FakeJsRuntime(nSolver = { FakeNSolverSession(mapOf("abc" to "xyz")) })
        TestResolver.install(tmp.root, jsRuntime = js)
        EjsNTransformSolver.transformNParamInUrl("https://h/v?n=abc")
        EjsNTransformSolver.transformNParamInUrl("https://h/v?n=abc")
        assertEquals(1, js.nSolverLoads)
    }

    @Test
    fun unavailableNFunctionLeavesUrlAlone() = runBlocking {
        val js = FakeJsRuntime(nSolver = { FakeNSolverSession(nFunctionAvailable = false) })
        TestResolver.install(tmp.root, jsRuntime = js)
        assertEquals("https://h/v?n=abc", EjsNTransformSolver.transformNParamInUrl("https://h/v?n=abc"))
    }

    @Test
    fun unsupportedRuntimeLeavesUrlAlone() = runBlocking {
        TestResolver.install(tmp.root, jsRuntime = UnsupportedJsRuntime)
        assertEquals("https://h/v?n=abc", EjsNTransformSolver.transformNParamInUrl("https://h/v?n=abc"))
    }
}
