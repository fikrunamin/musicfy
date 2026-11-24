package com.example.musicfy.ritme.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Walks InnerTube's nested JSON: String keys index objects, Int keys arrays; null when the path is missing. */
internal fun JsonElement?.at(vararg path: Any): JsonElement? = path.fold(this) { node, key ->
    when (key) {
        is String -> (node as? JsonObject)?.get(key)
        is Int -> (node as? JsonArray)?.getOrNull(key)
        else -> null
    }
}

internal fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON true or false (not the string "true"); else null. */
internal fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
