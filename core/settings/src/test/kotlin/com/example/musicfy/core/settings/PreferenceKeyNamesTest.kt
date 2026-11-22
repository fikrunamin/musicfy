package com.example.musicfy.core.settings

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Renaming a key silently resets that setting for every user. This pins every
 * `val XKey = ...PreferencesKey("name")` in PreferenceKeys.kt to its stored name.
 */
class PreferenceKeyNamesTest {
    private val golden = File("src/test/resources/preference-keys.golden.txt")

    @Test
    fun keyNamesMatchGolden() {
        val actual = Class.forName("com.example.musicfy.constants.PreferenceKeysKt").declaredMethods
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    Preferences.Key::class.java.isAssignableFrom(it.returnType)
            }
            .map { "${it.name.removePrefix("get")}=${(it.invoke(null) as Preferences.Key<*>).name}" }
            .sorted()
            .joinToString("\n")

        if (System.getProperty("updateGolden") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(actual + "\n")
            return
        }
        assertEquals(golden.readText().trim(), actual)
    }
}
