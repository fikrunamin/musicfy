package com.example.musicfy.ritme.sync

/**
 * Server clock estimate (spec 2.4). Each Ping/Pong gives rtt = t1 - t0 and
 * offset = serverTime - (t0 + t1) / 2; the offset in use comes from the sample with the smallest
 * rtt among the last [window] samples. Local times come from SystemClock.elapsedRealtime().
 */
class ClockSync(private val window: Int = WINDOW) {
    private class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()
    private var seeded: Long? = null

    /** t0 = local time the Ping was sent (echoed back as clientTime), t1 = local time the Pong arrived. */
    fun addSample(t0: Long, t1: Long, serverTime: Long) {
        val rtt = t1 - t0
        if (rtt < 0) return
        samples.addLast(Sample(rtt, serverTime - (t0 + t1) / 2))
        while (samples.size > window) samples.removeFirst()
    }

    /** Rough offset from Welcome.serverTime, used only until the first Pong arrives. */
    fun seed(serverTime: Long, receivedAt: Long) {
        seeded = serverTime - receivedAt
    }

    val offset: Long? get() = samples.minByOrNull { it.rtt }?.offset ?: seeded

    val rtt: Long? get() = samples.minOfOrNull { it.rtt }

    fun serverNow(localNow: Long): Long = localNow + (offset ?: 0L)

    fun reset() {
        samples.clear()
        seeded = null
    }

    companion object {
        const val WINDOW = 8
    }
}
