package com.example.musicfy.platform

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.media3.common.PlaybackException
import com.example.musicfy.constants.VisitorDataKey
import com.example.musicfy.core.resolver.ResolverPlatform
import com.example.musicfy.utils.dataStore

/** Wires core:resolver to Android exactly as the code behaved before it moved. */
fun androidResolverPlatform(context: Context): ResolverPlatform {
    val appContext = context.applicationContext
    return ResolverPlatform(
        logger = TimberLogger,
        releaseLog = { tag, msg -> Log.i(tag, msg) },
        dataDir = appContext.filesDir,
        jsRuntime = AndroidJsRuntime(appContext),
        botGuardRuntime = AndroidBotGuardRuntime(appContext),
        playbackError = { reason -> PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR) },
        onVisitorDataRotated = { data -> appContext.dataStore.edit { it[VisitorDataKey] = data } },
    )
}
