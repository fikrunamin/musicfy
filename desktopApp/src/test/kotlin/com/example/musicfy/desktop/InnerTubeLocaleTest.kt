package com.example.musicfy.desktop

import androidx.datastore.preferences.core.edit
import com.example.musicfy.constants.ContentCountryKey
import com.example.musicfy.constants.ContentLanguageKey
import com.example.musicfy.core.settings.createSettingsDataStore
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class InnerTubeLocaleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val original = YouTube.locale

    @After
    fun tearDown() {
        YouTube.locale = original
        scope.cancel()
    }

    @Test
    fun enIdSystemLocaleWithNoSettingsGivesIdAndEnglish() = runBlocking {
        val settings = createSettingsDataStore(tmp.newFolder("settings"), scope)
        applyInnerTubeLocale(settings, systemLocale = Locale.forLanguageTag("en-ID"))
        assertEquals(YouTubeLocale(gl = "ID", hl = "en"), YouTube.locale)
    }

    @Test
    fun storedContentCountryAndLanguageWin() = runBlocking {
        val settings = createSettingsDataStore(tmp.newFolder("settings"), scope)
        settings.edit {
            it[ContentCountryKey] = "JP"
            it[ContentLanguageKey] = "ja"
        }
        applyInnerTubeLocale(settings, systemLocale = Locale.forLanguageTag("en-ID"))
        assertEquals(YouTubeLocale(gl = "JP", hl = "ja"), YouTube.locale)
    }
}
