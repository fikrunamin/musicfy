package com.example.musicfy.ritme.server

/** Server time in epoch milliseconds. Clients sync to this clock. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
