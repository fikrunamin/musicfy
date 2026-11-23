package com.example.musicfy.di

import com.example.musicfy.ritme.net.DefaultRitmeConnector
import com.example.musicfy.ritme.net.HandlerRitmeTimers
import com.example.musicfy.ritme.net.OkHttpRitmeSocketFactory
import com.example.musicfy.ritme.net.RitmeConnector
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.session.RitmeClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RitmeModule {

    @Provides
    @Singleton
    fun provideRitmeTimers(): RitmeTimers = HandlerRitmeTimers()

    @Provides
    @Singleton
    fun provideRitmeClock(timers: RitmeTimers): RitmeClock = RitmeClock { timers.now() }

    @Provides
    @Singleton
    fun provideRitmeConnector(timers: RitmeTimers): RitmeConnector {
        val client = OkHttpClient.Builder()
            // A dead link (airplane mode) fails within ~15 s, so the reconnect backoff starts in time.
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        return DefaultRitmeConnector(OkHttpRitmeSocketFactory(client, timers), timers)
    }
}
