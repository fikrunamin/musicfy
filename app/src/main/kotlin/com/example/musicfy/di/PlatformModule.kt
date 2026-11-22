package com.example.musicfy.di

import android.content.Context
import com.example.musicfy.core.platform.AppDirs
import com.example.musicfy.core.platform.Logger
import com.example.musicfy.platform.AndroidAppDirs
import com.example.musicfy.platform.TimberLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides
    @Singleton
    fun provideAppDirs(@ApplicationContext context: Context): AppDirs = AndroidAppDirs.from(context)

    @Provides
    @Singleton
    fun provideLogger(): Logger = TimberLogger
}
