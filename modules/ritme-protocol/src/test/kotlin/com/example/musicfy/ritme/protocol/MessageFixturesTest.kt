package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Golden JSON for every message. The web client's tests read the same files, so a shape change
 * on one side only fails CI. Regenerate deliberately with -PupdateGolden=true.
 */
class MessageFixturesTest {
    private val pretty = Json(from = RitmeJson) { prettyPrint = true }
    private val dir = File("fixtures")
    private val update = System.getProperty("updateGolden") == "true"

    @Test
    fun clientMessagesRoundTripAndMatchFixtures() {
        Samples.client.forEach { (name, message) ->
            assertEquals(name, message, decodeClientMessage(message.encode()))
            check(name, pretty.encodeToString(ClientMessage.serializer(), message))
        }
    }

    @Test
    fun serverMessagesRoundTripAndMatchFixtures() {
        Samples.server.forEach { (name, message) ->
            assertEquals(name, message, decodeServerMessage(message.encode()))
            check(name, pretty.encodeToString(ServerMessage.serializer(), message))
        }
    }

    @Test
    fun commandsUseTheCommandDiscriminator() {
        val json = RitmeJson.parseToJsonElement(Samples.client.getValue("cmd-seek").encode()).jsonObject
        assertEquals("Command", json.getValue("type").jsonPrimitive.content)
        assertEquals("Seek", json.getValue("cmd").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra = """{"type":"Ping","clientTime":5,"future":"field"}"""
        assertEquals(ClientMessage.Ping(5), decodeClientMessage(withExtra))
    }

    private fun check(name: String, json: String) {
        val file = File(dir, "$name.json")
        if (update) {
            dir.mkdirs()
            file.writeText(json + "\n")
            return
        }
        assertEquals("fixture $name", file.readText().trim(), json)
    }
}
