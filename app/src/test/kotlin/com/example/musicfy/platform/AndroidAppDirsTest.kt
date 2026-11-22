package com.example.musicfy.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AndroidAppDirsTest {
    @Test
    fun downloadsIsTheSameFolderDownloadCacheUsesToday() {
        // di/AppModule.kt builds the download cache in context.filesDir.resolve("download").
        val dirs = AndroidAppDirs(data = File("/data/files"), cache = File("/data/cache"))
        assertEquals(File("/data/files/download"), dirs.downloads)
        assertEquals(File("/data/files"), dirs.data)
        assertEquals(File("/data/cache"), dirs.cache)
    }
}
