package com.ecency.core.api.model

import com.ecency.core.api.json.SafeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Tolerant readers for chain JSON. Hive data is only loosely typed: numbers arrive as strings,
 * arrays as single values or comma/space separated strings, objects as JSON-encoded strings
 * (sometimes twice). None of these readers throw; a value of the wrong shape reads as absent.
 */

internal fun JsonElement?.obj(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> if (isString) parseNested(content) as? JsonObject else null
    else -> null
}

/** Decodes a JSON string that itself holds JSON, up to a few levels deep. */
internal fun parseNested(text: String, depth: Int = 0): JsonElement? {
    val trimmed = text.trim()
    if (depth > 3 || trimmed.isEmpty()) return null
    val parsed = SafeJson.parseOrNull(trimmed) ?: return null
    return if (parsed is JsonPrimitive && parsed.isString) parseNested(parsed.content, depth + 1) else parsed
}

internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    is JsonPrimitive -> if (v is JsonNull) null else v.content
    else -> null
}

internal fun JsonObject.string(key: String, default: String = ""): String = str(key) ?: default

internal fun JsonObject.long(key: String): Long? = this[key].asLong()

internal fun JsonObject.int(key: String): Int? = long(key)?.let {
    if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null
}

internal fun JsonObject.double(key: String): Double? = this[key].asDouble()

internal fun JsonObject.bool(key: String): Boolean? = when (val v = this[key]) {
    is JsonPrimitive -> when (v.content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    else -> null
}

internal fun JsonElement?.asLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val s = p.content.trim()
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }?.toLong()
}

internal fun JsonElement?.asDouble(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/**
 * A list of strings from whatever is there: an array (non-strings skipped), a JSON-encoded
 * array, or a single string split on [separators]. Blank entries are dropped.
 */
internal fun JsonElement?.stringList(separators: Regex = Regex("[\\s,]+")): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
    is JsonPrimitive -> when {
        this is JsonNull || !isString -> emptyList()
        content.trim().startsWith("[") -> (parseNested(content) as? JsonArray).stringList(separators)
        else -> content.split(separators)
    }
    else -> emptyList()
}.filter { it.isNotBlank() }

internal fun JsonElement?.arr(): JsonArray? = when (this) {
    is JsonArray -> this
    is JsonPrimitive -> if (isString) parseNested(content) as? JsonArray else null
    else -> null
}
