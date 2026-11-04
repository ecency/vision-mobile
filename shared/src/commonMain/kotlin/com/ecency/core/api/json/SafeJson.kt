package com.ecency.core.api.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON parsing that never throws. kotlinx-serialization parses recursively, so a few thousand
 * nested brackets (trivial to put in json_metadata on chain) overflow the stack, an Error that
 * no catch handles reliably, worse on iOS's 512 KB background stacks. Nesting is counted first
 * and anything deeper than [MAX_DEPTH] is refused without parsing.
 */
internal object SafeJson {
    /** Far beyond any legitimate Hive payload, far below the parser's stack limit. */
    const val MAX_DEPTH = 256

    fun parseOrNull(text: String, maxDepth: Int = MAX_DEPTH): JsonElement? {
        if (depthExceeds(text, maxDepth)) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
    }

    /** True when brackets outside strings nest deeper than [max]. Linear, no allocation. */
    fun depthExceeds(text: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) return true
                ']', '}' -> depth--
            }
        }
        return false
    }
}
