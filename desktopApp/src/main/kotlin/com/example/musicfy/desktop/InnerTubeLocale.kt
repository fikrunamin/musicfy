package com.example.musicfy.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.musicfy.constants.AppLanguageKey
import com.example.musicfy.constants.ContentCountryKey
import com.example.musicfy.constants.ContentLanguageKey
import com.example.musicfy.constants.SYSTEM_DEFAULT
import com.example.musicfy.core.settings.ContentLocale
import com.example.musicfy.core.settings.resolveContentLocale
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeLocale
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Sets [YouTube.locale] from settings, using the same candidate order as Android's settings
 * observer in App.kt. If it is never called, InnerTube keeps its raw JVM default (for example
 * hl=en-ID), and every search fails with HTTP 400.
 */
suspend fun applyInnerTubeLocale(
    settings: DataStore<Preferences>,
    systemLocale: Locale = Locale.getDefault(),
): ContentLocale {
    val prefs = settings.data.first()
    val appLocale = prefs[AppLanguageKey]
        ?.takeUnless { it == SYSTEM_DEFAULT }
        ?.let(Locale::forLanguageTag)
        ?: systemLocale
    val locale = resolveContentLocale(
        contentCountry = prefs[ContentCountryKey],
        contentLanguage = prefs[ContentLanguageKey],
        countryCandidates = listOf(appLocale.country, systemLocale.country),
        languageCandidates = listOf(appLocale.toLanguageTag(), appLocale.language),
    )
    YouTube.locale = YouTubeLocale(gl = locale.gl, hl = locale.hl)
    return locale
}
