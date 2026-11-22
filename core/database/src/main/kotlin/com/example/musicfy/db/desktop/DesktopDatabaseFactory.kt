package com.example.musicfy.db.desktop

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Same file name as Android's InternalDatabase.DB_NAME. */
const val DESKTOP_DB_NAME = "song.db"

/**
 * Opens (creating if needed) the desktop database at [file]. [driver] is BundledSQLiteDriver in the
 * app; it is a parameter so this module does not ship native SQLite to anyone who does not need it.
 */
fun createDesktopDatabase(
    file: File,
    driver: SQLiteDriver,
    queryContext: CoroutineContext = Dispatchers.IO,
): DesktopMusicDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<DesktopMusicDatabase>(name = file.absolutePath)
        .setDriver(driver)
        .setQueryCoroutineContext(queryContext)
        .build()
}
