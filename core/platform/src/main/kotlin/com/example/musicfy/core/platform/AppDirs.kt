package com.example.musicfy.core.platform

import java.io.File

/** Where the app keeps its files. Android maps these to Context dirs; desktop to per-OS app dirs. */
interface AppDirs {
    /** Persistent app data (database, settings, cipher cache). */
    val data: File

    /** Disposable cache (images, stream cache). */
    val cache: File

    /** Downloaded songs. Android: filesDir/download. Desktop: DesktopAppDirs.localData/download. */
    val downloads: File
}
