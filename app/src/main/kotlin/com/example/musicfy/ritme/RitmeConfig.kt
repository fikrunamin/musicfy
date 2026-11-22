package com.example.musicfy.ritme

import com.example.musicfy.BuildConfig

/** Build-time Ritme switches. Set RITME_ENABLED to false in app/build.gradle.kts to ship without Ritme. */
object RitmeConfig {
    val ENABLED: Boolean get() = BuildConfig.RITME_ENABLED
    val DEFAULT_SERVER_URL: String get() = BuildConfig.RITME_SERVER_URL
}
