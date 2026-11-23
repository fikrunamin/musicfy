package com.example.musicfy.core.resolver

/** Timber-shaped facade over [ResolverPlatform.logger], so moved code keeps its log calls verbatim. */
object ResolverLog {
    fun tag(tag: String): Tagged = Tagged(tag)

    /** For calls that used android.util.Log directly. */
    fun release(tag: String, msg: String) = ResolverRuntime.platform.releaseLog(tag, msg)

    class Tagged internal constructor(private val tag: String) {
        private val logger get() = ResolverRuntime.platform.logger

        fun d(msg: String) = logger.d(tag, msg)

        fun i(msg: String) = logger.i(tag, msg)

        fun w(msg: String) = logger.w(tag, msg)

        fun w(t: Throwable?, msg: String) = logger.w(tag, msg, t)

        fun e(msg: String) = logger.e(tag, msg)

        fun e(t: Throwable?, msg: String) = logger.e(tag, msg, t)
    }
}
