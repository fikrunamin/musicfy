package com.example.musicfy.core.platform

/** Logging seam for shared code. Android forwards to Timber, desktop to stdout/stderr. */
interface Logger {
    fun d(tag: String, msg: String)

    fun i(tag: String, msg: String)

    fun w(tag: String, msg: String, t: Throwable? = null)

    fun e(tag: String, msg: String, t: Throwable? = null)
}
