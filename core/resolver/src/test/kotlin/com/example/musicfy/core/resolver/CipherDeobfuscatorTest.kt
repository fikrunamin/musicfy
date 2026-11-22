package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.FakeCipherSession
import com.example.musicfy.core.resolver.testing.FakeJsRuntime
import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.cipher.CipherDeobfuscator
import com.example.musicfy.utils.cipher.FunctionNameExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CipherDeobfuscatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        CipherDeobfuscator.resetForTests()
        TestResolver.writePlayerJsCache(tmp.root)
    }

    @Test
    fun signatureIsDeobfuscatedIntoTheSpParam() = runBlocking {
        val js = FakeJsRuntime(cipher = { _, _, _ -> FakeCipherSession(signatures = mapOf("ABC" to "CBA")) })
        TestResolver.install(tmp.root, jsRuntime = js)
        val url = CipherDeobfuscator.deobfuscateStreamUrl(
            "s=ABC&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3D1",
            "vid",
        )
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=1&sig=CBA", url)
        assertEquals(FunctionNameExtractor.SigFunctionInfo("Xy", 1), js.lastSigInfo)
        assertEquals(FunctionNameExtractor.NFunctionInfo("Nf", 0), js.lastNFuncInfo)
    }

    @Test
    fun nTransformReusesTheLoadedPlayer() = runBlocking {
        val js = FakeJsRuntime(cipher = { _, _, _ -> FakeCipherSession(nValues = mapOf("abc" to "x y")) })
        TestResolver.install(tmp.root, jsRuntime = js)
        assertEquals("https://h/v?n=x%20y&k=1", CipherDeobfuscator.transformNParamInUrl("https://h/v?n=abc&k=1"))
        assertEquals("https://h/v?k=1&n=x%20y", CipherDeobfuscator.transformNParamInUrl("https://h/v?k=1&n=abc"))
        assertEquals(1, js.cipherLoads)
    }

    @Test
    fun unsupportedRuntimeLeavesTheUrlAlone() = runBlocking {
        TestResolver.install(tmp.root, jsRuntime = UnsupportedJsRuntime)
        assertEquals("https://h/v?n=abc", CipherDeobfuscator.transformNParamInUrl("https://h/v?n=abc"))
    }
}
