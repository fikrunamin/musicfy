package com.example.musicfy.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Android's `preferencesDataStore(name = ...)` and desktop both use this name. */
const val SETTINGS_DATASTORE_NAME = "settings"

/**
 * Desktop settings store at `dir/settings.preferences_pb`. Create exactly one per process for a
 * given dir; DataStore throws if two instances own the same file.
 */
fun createSettingsDataStore(
    dir: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    scope = scope,
    produceFile = { dir.resolve("$SETTINGS_DATASTORE_NAME.preferences_pb") },
)
