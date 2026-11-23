package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeSettings
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.FakeNotifier
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ui.component.PopupSheetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeSheetStackTest {

    private val session = RitmeSession(FakeConnector(), FakeTimers())
    private val settings = object : RitmeSettings {
        override val serverUrl = "https://ritme.dubeli.id"
        override val displayName = "Budi"
    }
    private val ritme = RitmeUi(session, RitmeController(session, RitmeGate(), FakeNotifier(), settings))

    @Test
    fun theRoomSheetReplacesAnOpenEntrySheetInsteadOfPushingOverIt() {
        val popup = PopupSheetState()
        popup.showRitmeEntrySheet(ritme, code = null, startWith = null)
        val entry = popup.frames.single()

        // The host of a new room: the room sheet is requested while the entry sheet is still up.
        popup.showRitmeRoomSheet(ritme) {}

        try {
            assertEquals(2, popup.frames.size)
            assertTrue(entry.closing)
            // the room sheet goes in underneath the leaving entry sheet, not pushed over it
            assertSame(entry, popup.frames.last())
            assertFalse(popup.frames.first().closing)
        } finally {
            // the exits finish (the sheets' own "open" bookkeeping is reset by onClosed)
            popup.frames.toList().forEach(popup::remove)
        }
    }
}
