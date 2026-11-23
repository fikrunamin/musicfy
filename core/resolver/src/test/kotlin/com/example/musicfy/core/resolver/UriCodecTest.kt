package com.example.musicfy.core.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class UriCodecTest {
    @Test
    fun unreservedCharactersAreKept() =
        assertEquals("abcXYZ019-_.!~*'()", UriCodec.encode("abcXYZ019-_.!~*'()"))

    @Test
    fun everythingElseIsPercentEncodedAsUtf8() =
        assertEquals("a%20b%26c%3Dd%2F%2B%C3%A9%E2%82%AC", UriCodec.encode("a b&c=d/+é€"))

    @Test
    fun decodeLeavesPlusAlone() = assertEquals("a b+c+d", UriCodec.decode("a%20b%2Bc+d"))

    @Test
    fun decodeHandlesMultiByteSequences() = assertEquals("é€", UriCodec.decode("%C3%A9%e2%82%ac"))

    @Test
    fun truncatedEscapeBecomesReplacementCharacter() = assertEquals("100�", UriCodec.decode("100%"))

    @Test
    fun typicalNParamRoundTrips() = assertEquals("T2Xw3pWQ_Wk0xbOg", UriCodec.decode(UriCodec.encode("T2Xw3pWQ_Wk0xbOg")))
}
