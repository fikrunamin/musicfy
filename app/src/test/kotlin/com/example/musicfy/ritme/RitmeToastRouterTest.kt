package com.example.musicfy.ritme

import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeToastRouterTest {

    private val pill = mutableListOf<RitmeToastText>()
    private val system = mutableListOf<String>()
    private var started = true
    private val router = RitmeToastRouter({ started }, { pill += it }, { system += it })

    private val added = RitmeToastText("Added 1 song", isError = false, iconRes = 1)

    @Test
    fun withAStartedActivityTheToastIsThePill() {
        router.deliver(added)
        assertEquals(listOf(added), pill)
        assertEquals(emptyList<String>(), system)
    }

    @Test
    fun inTheBackgroundItIsASystemToastAndThePillIsNotSet() {
        started = false
        router.deliver(added)
        router.deliver(RitmeToastText("The host locked the controls", isError = true, iconRes = 2))
        assertEquals(emptyList<RitmeToastText>(), pill)
        assertEquals(listOf("Added 1 song", "The host locked the controls"), system)
    }

    @Test
    fun theSurfaceFollowsTheAppAsItComesAndGoes() {
        started = false
        router.deliver(added)
        started = true
        router.deliver(added)
        assertEquals(1, system.size)
        assertEquals(1, pill.size)
    }
}
