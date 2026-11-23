package com.example.musicfy.di

import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.session.RitmeSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** For Ritme's sheets, pill and menu rows, which live outside any Hilt view model. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RitmeEntryPoint {
    fun ritmeSession(): RitmeSession
    fun ritmeController(): RitmeController
}
