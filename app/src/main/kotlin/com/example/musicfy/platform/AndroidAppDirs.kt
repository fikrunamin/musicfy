package com.example.musicfy.platform

import android.content.Context
import com.example.musicfy.core.platform.AppDirs
import java.io.File

class AndroidAppDirs(
    override val data: File,
    override val cache: File,
) : AppDirs {
    override val downloads: File
        get() = data.resolve("download")

    companion object {
        fun from(context: Context): AndroidAppDirs =
            AndroidAppDirs(data = context.filesDir, cache = context.cacheDir)
    }
}
