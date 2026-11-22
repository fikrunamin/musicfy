package com.music.paxsenix

import java.util.logging.Level
import java.util.logging.Logger

/** Plain-JVM stand-in for the Timber calls this module used to make. */
internal object PaxLog {
    private val logger: Logger = Logger.getLogger("Paxsenix")

    fun d(message: String) = logger.fine(message)

    fun w(message: String) = logger.warning(message)

    fun e(t: Throwable?, message: String) = logger.log(Level.SEVERE, message, t)
}
