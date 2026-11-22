package com.example.musicfy.ritme.protocol

import kotlinx.serialization.json.Json

val RitmeJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun ClientMessage.encode(): String = RitmeJson.encodeToString(ClientMessage.serializer(), this)

fun ServerMessage.encode(): String = RitmeJson.encodeToString(ServerMessage.serializer(), this)

fun decodeClientMessage(text: String): ClientMessage = RitmeJson.decodeFromString(ClientMessage.serializer(), text)

fun decodeServerMessage(text: String): ServerMessage = RitmeJson.decodeFromString(ServerMessage.serializer(), text)

/**
 * The client-side entry point: null for a message type this client does not know (sent by a newer
 * server) or for malformed JSON, so clients skip it instead of dropping the connection. Unknown
 * fields are ignored and unknown [MediaKind] / [ErrorCode] values decode to `UNKNOWN`.
 */
fun decodeServerMessageOrNull(text: String): ServerMessage? = try {
    decodeServerMessage(text)
} catch (_: IllegalArgumentException) {
    // SerializationException (unknown type, malformed JSON) is an IllegalArgumentException.
    null
}
