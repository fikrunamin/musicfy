package com.example.musicfy.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.musicfy.core.platform.AppDirs
import com.example.musicfy.core.platform.Logger
import com.example.musicfy.core.platform.StdoutLogger
import com.example.musicfy.core.resolver.ResolverPlatform
import com.example.musicfy.core.resolver.StreamUnplayableException
import com.example.musicfy.core.resolver.UnsupportedBotGuardRuntime
import com.example.musicfy.core.resolver.UnsupportedJsRuntime
import com.example.musicfy.core.settings.createSettingsDataStore
import com.example.musicfy.db.desktop.DESKTOP_DB_NAME
import com.example.musicfy.db.desktop.DesktopMusicDatabase
import com.example.musicfy.db.desktop.createDesktopDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

fun desktopModule(dirs: AppDirs): Module = module {
    single<AppDirs> { dirs }
    single<Logger> { StdoutLogger }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single<DataStore<Preferences>> { createSettingsDataStore(get<AppDirs>().data, get()) }
    single { createDesktopDatabase(get<AppDirs>().data.resolve(DESKTOP_DB_NAME), BundledSQLiteDriver()) }
    single { get<DesktopMusicDatabase>().dao }
    single<SongSearch> { InnerTubeSongSearch }
    single { SearchController(get(), get(), get(), get()) }
    single { desktopResolverPlatform(get(), get()) }
}

/** No JavaScript or BotGuard engine on desktop until D2: the resolver uses the clients that need none. */
fun desktopResolverPlatform(logger: Logger, dirs: AppDirs): ResolverPlatform = ResolverPlatform(
    logger = logger,
    releaseLog = { tag, msg -> logger.i(tag, msg) },
    dataDir = dirs.data,
    jsRuntime = UnsupportedJsRuntime,
    botGuardRuntime = UnsupportedBotGuardRuntime,
    playbackError = { reason -> StreamUnplayableException(reason) },
    onVisitorDataRotated = { },
)
