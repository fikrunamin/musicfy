package com.example.musicfy.core.settings

import androidx.datastore.preferences.core.edit
import com.example.musicfy.constants.DarkModeKey
import com.example.musicfy.constants.DensityScaleKey
import com.example.musicfy.constants.IsFirstRunKey
import com.example.musicfy.constants.SelectedThemeColorKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun usesTheSameFileNameAsAndroid() {
        assertEquals("settings", SETTINGS_DATASTORE_NAME)
    }

    @Test
    fun valuesSurviveReopening() = runBlocking {
        val dir = tmp.newFolder()

        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        createSettingsDataStore(dir, firstScope).edit {
            it[IsFirstRunKey] = false
            it[SelectedThemeColorKey] = 0x123456
            it[DensityScaleKey] = 1.25f
            it[DarkModeKey] = "ON"
        }
        firstScope.cancel()

        assertTrue(dir.resolve("settings.preferences_pb").isFile)

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = createSettingsDataStore(dir, secondScope).data.first()
        assertEquals(false, prefs[IsFirstRunKey])
        assertEquals(0x123456, prefs[SelectedThemeColorKey])
        assertEquals(1.25f, prefs[DensityScaleKey])
        assertEquals("ON", prefs[DarkModeKey])
        secondScope.cancel()
    }
}
