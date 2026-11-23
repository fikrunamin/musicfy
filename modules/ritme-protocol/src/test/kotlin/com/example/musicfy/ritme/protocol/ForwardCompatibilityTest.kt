package com.example.musicfy.ritme.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an older client must survive when talking to a newer server. */
class ForwardCompatibilityTest {
    private val entryJson =
        """{"entryId":"e1","videoId":"","title":"T","artist":"A","durationMs":1,"addedBy":"m1","kind":"PODCAST","url":null}"""

    @Test
    fun unknownMediaKindDecodesToUnknown() {
        assertEquals(MediaKind.UNKNOWN, RitmeJson.decodeFromString(QueueEntry.serializer(), entryJson).kind)
        val state = """{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","queue":[$entryJson]}}"""
        assertEquals(MediaKind.UNKNOWN, (decodeServerMessageOrNull(state) as ServerMessage.State).state.queue.single().kind)
    }

    @Test
    fun unknownErrorCodeDecodesToUnknown() {
        assertEquals(
            ServerMessage.Error("r1", ErrorCode.UNKNOWN),
            decodeServerMessageOrNull("""{"type":"Error","reqId":"r1","code":"SERVER_ON_FIRE"}"""),
        )
    }

    @Test
    fun knownEnumValuesKeepTheirNames() {
        MediaKind.entries.forEach {
            assertEquals("\"${it.name}\"", RitmeJson.encodeToString(MediaKind.serializer(), it))
            assertEquals(it, RitmeJson.decodeFromString(MediaKind.serializer(), "\"${it.name}\""))
        }
        ErrorCode.entries.forEach {
            assertEquals("\"${it.name}\"", RitmeJson.encodeToString(ErrorCode.serializer(), it))
            assertEquals(it, RitmeJson.decodeFromString(ErrorCode.serializer(), "\"${it.name}\""))
        }
    }

    @Test
    fun unknownServerMessageTypeOrMalformedJsonDecodesToNull() {
        assertNull(decodeServerMessageOrNull("""{"type":"Reaction","emoji":"x"}"""))
        assertNull(decodeServerMessageOrNull("""{"type":"""))
        assertNull(decodeServerMessageOrNull("not json"))
        assertEquals(ServerMessage.Pong(1, 2), decodeServerMessageOrNull("""{"type":"Pong","clientTime":1,"serverTime":2}"""))
    }

    @Test
    fun welcomeFromAnOlderServerWithoutProtocolVersionStillDecodes() {
        val welcome = """{"type":"Welcome","memberId":"m1","resumeToken":"t","serverTime":5,"state":{"code":"K7M2QX","version":1,"hostId":"m1"}}"""
        assertEquals(PROTOCOL_VERSION, (decodeServerMessageOrNull(welcome) as ServerMessage.Welcome).protocolVersion)
    }

    @Test
    fun settingsFromAnOlderPeerDefaultGuestsCanAddToTrue() {
        val state = """{"type":"State","state":{"code":"K7M2QX","version":1,"hostId":"m1","settings":{"guestsCanControl":false}}}"""
        assertEquals(
            RoomSettings(guestsCanControl = false, guestsCanAdd = true),
            (decodeServerMessageOrNull(state) as ServerMessage.State).state.settings,
        )
        val cmd = """{"type":"Command","reqId":"r1","cmd":{"type":"SetSettings","settings":{"guestsCanControl":true}}}"""
        assertEquals(
            Command.SetSettings(RoomSettings(guestsCanControl = true, guestsCanAdd = true)),
            (decodeClientMessage(cmd) as ClientMessage.Send).cmd,
        )
    }
}
