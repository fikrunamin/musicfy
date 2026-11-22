package com.example.musicfy.platform

import com.example.musicfy.core.platform.Logger
import timber.log.Timber

object TimberLogger : Logger {
    override fun d(tag: String, msg: String) = Timber.tag(tag).d(msg)

    override fun i(tag: String, msg: String) = Timber.tag(tag).i(msg)

    override fun w(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).w(t, msg)

    override fun e(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).e(t, msg)
}
