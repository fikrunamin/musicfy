package com.example.musicfy.core.settings

import com.example.musicfy.constants.SYSTEM_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ContentLocaleTest {
    private fun forSystem(locale: Locale, country: String? = null, language: String? = null) = resolveContentLocale(
        contentCountry = country,
        contentLanguage = language,
        countryCandidates = listOf(locale.country),
        languageCandidates = listOf(locale.toLanguageTag(), locale.language),
    )

    @Test
    fun enIdSystemLocaleKeepsTheCountryAndFallsBackToPlainEnglish() {
        // en_ID: "en-ID" is not an InnerTube language, so hl must drop to "en"; "ID" is a valid country.
        assertEquals(ContentLocale(gl = "ID", hl = "en"), forSystem(Locale.forLanguageTag("en-ID")))
    }

    @Test
    fun unknownCountryAndLanguageFallBackToUsEnglish() {
        assertEquals(ContentLocale(gl = "US", hl = "en"), forSystem(Locale.forLanguageTag("xx-QQ")))
        assertEquals(ContentLocale(gl = "US", hl = "en"), forSystem(Locale.ROOT))
    }

    @Test
    fun aListedFullTagWinsOverItsLanguage() {
        assertEquals(ContentLocale(gl = "TW", hl = "zh-TW"), forSystem(Locale.forLanguageTag("zh-TW")))
    }

    @Test
    fun storedSettingsWinUnlessSystemDefault() {
        val enId = Locale.forLanguageTag("en-ID")
        assertEquals(ContentLocale(gl = "JP", hl = "ja"), forSystem(enId, country = "JP", language = "ja"))
        assertEquals(ContentLocale(gl = "ID", hl = "en"), forSystem(enId, country = SYSTEM_DEFAULT, language = SYSTEM_DEFAULT))
    }

    @Test
    fun candidatesAreTriedInOrder() {
        val locale = resolveContentLocale(null, null, listOf("", "QQ", "DE", "FR"), listOf("en-ID", "de", "fr"))
        assertEquals(ContentLocale(gl = "DE", hl = "de"), locale)
    }
}
