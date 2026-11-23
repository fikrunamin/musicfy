package com.example.musicfy.core.resolver

import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.cipher.FunctionNameExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FunctionNameExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        TestResolver.install(tmp.root)
    }

    @Test
    fun findsSignatureFunctionAndConstantArg() = assertEquals(
        FunctionNameExtractor.SigFunctionInfo("Xy", 1),
        FunctionNameExtractor.extractSigFunctionInfo(TestResolver.PLAYER_JS),
    )

    @Test
    fun findsNFunctionWithArrayIndex() = assertEquals(
        FunctionNameExtractor.NFunctionInfo("Nf", 0),
        FunctionNameExtractor.extractNFunctionInfo(TestResolver.PLAYER_JS),
    )

    @Test
    fun returnsNullWhenNothingMatches() = assertNull(FunctionNameExtractor.extractSigFunctionInfo("var a = 1;"))
}
