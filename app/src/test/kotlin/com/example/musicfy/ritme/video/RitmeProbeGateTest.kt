package com.example.musicfy.ritme.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** A blocked probe stands for MediaMetadataRetriever.setDataSource on a host that never answers. */
class RitmeProbeGateTest {

    private val stuck = CountDownLatch(1)
    private val gate = RitmeProbeGate(timeoutMs = 300, maxRunning = 2, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @After
    fun release() = stuck.countDown()

    @Test
    fun aProbeThatAnswersInTimeGivesItsValue() = runBlocking {
        assertEquals(90_000L, gate.run { 90_000L })
    }

    @Test
    fun aProbeThatNeverAnswersIsGivenUpAfterTheTimeout() = runBlocking {
        assertNull(gate.run { stuck.await(); 1L })
    }

    @Test
    fun oneStuckProbeDoesNotMakeTheNextPasteFail() = runBlocking {
        assertNull(gate.run { stuck.await(); 1L })
        assertEquals(2L, gate.run { 2L })
        assertEquals(3L, gate.run { 3L })
    }

    @Test
    fun atMostTwoRunAndTheNextOneRunsOnceASlotIsFree() = runBlocking {
        assertNull(gate.run { stuck.await(); 1L })
        assertNull(gate.run { stuck.await(); 2L })
        val ran = AtomicBoolean(false)
        assertNull(gate.run { ran.set(true); 3L })
        // waiting for a slot past the timeout, it never started
        assertFalse(ran.get())
        stuck.countDown()
        assertEquals(4L, gate.run { 4L })
    }
}
