package com.example.musicfy.core.settings

import com.example.musicfy.constants.CountryCodeToName
import com.example.musicfy.constants.LanguageCodeToName
import com.example.musicfy.constants.SYSTEM_DEFAULT

/** The InnerTube content locale: `gl` (country) and `hl` (language). Mirrors innertube's YouTubeLocale. */
data class ContentLocale(val gl: String, val hl: String)

/**
 * Picks the InnerTube content locale. A stored [contentCountry] / [contentLanguage] wins unless it is
 * [SYSTEM_DEFAULT]. Otherwise the first candidate that appears in [CountryCodeToName] /
 * [LanguageCodeToName] is used, and failing that "US" / "en".
 *
 * Callers pass candidates in their own priority order, which is what lets each Android call
 * site keep its exact fallback chain. Without the table check, a JVM locale such as en_ID
 * would send `hl=en-ID`, and InnerTube rejects that with HTTP 400.
 */
fun resolveContentLocale(
    contentCountry: String?,
    contentLanguage: String?,
    countryCandidates: List<String>,
    languageCandidates: List<String>,
): ContentLocale = ContentLocale(
    gl = contentCountry?.takeIf { it != SYSTEM_DEFAULT }
        ?: countryCandidates.firstOrNull { it in CountryCodeToName }
        ?: "US",
    hl = contentLanguage?.takeIf { it != SYSTEM_DEFAULT }
        ?: languageCandidates.firstOrNull { it in LanguageCodeToName }
        ?: "en",
)
