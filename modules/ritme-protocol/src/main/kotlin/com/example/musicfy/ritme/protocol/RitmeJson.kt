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
