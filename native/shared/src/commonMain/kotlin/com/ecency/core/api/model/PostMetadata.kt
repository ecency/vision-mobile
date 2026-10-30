package com.ecency.core.api.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The useful parts of a post's json_metadata.
 *
 * json_metadata is free-form on chain: any app writes anything. It may be an object, a
 * JSON-encoded string (condenser_api), double-encoded, empty, or invalid; `tags` may be an array,
 * one string, a comma or space separated string; `app` may be "name/version" or an object.
 * Parsing never throws; what cannot be read is left empty.
 */
data class PostMetadata(
    val tags: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    /** "ecency/4.0.0-mobile" style; an object form is folded to "name/version". */
    val app: String? = null,
    val format: String? = null,
    val description: String? = null,
    val links: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    /** A root post's pinned reply, shown first in its discussion. */
    val pinnedReply: PostKey? = null,
    val originalAuthor: String? = null,
    val originalPermlink: String? = null,
    /** Liketu Speak voice post: set when `speak.audio_url` is a non-empty string. */
    val speak: SpeakMeta? = null,
    /** The metadata object as JSON text ("{}" when it was not an object). */
    val rawJson: String = "{}",
    /** RN: `ai_tools.media_generation || ai_tools.writing_edit` is JS-truthy (the author disclosed AI usage). */
    val aiDisclosed: Boolean = false,
) {
    companion object {
        fun parse(element: JsonElement?): PostMetadata {
            val o = element.obj() ?: return PostMetadata()
            return PostMetadata(
                tags = o["tags"].stringList().distinct(),
                images = o["image"].stringList(Regex("\\s+")).filter { it.startsWith("http") },
                app = parseApp(o["app"]),
                format = o.str("format")?.takeIf { it.isNotBlank() },
                description = o.str("description")?.takeIf { it.isNotBlank() },
                links = o["links"].stringList(Regex("\\s+")),
                users = o["users"].stringList(),
                pinnedReply = PostKey.parse(o.str("pinned_reply")),
                originalAuthor = o.str("original_author")?.takeIf { it.isNotBlank() },
                originalPermlink = o.str("original_permlink")?.takeIf { it.isNotBlank() },
                speak = SpeakMeta.parse(o["speak"]),
                rawJson = o.toString(),
                aiDisclosed = o["ai_tools"].obj()?.let { t -> truthy(t["media_generation"]) || truthy(t["writing_edit"]) } == true,
            )
        }

        /** JS truthiness of a JSON value. */
        private fun truthy(e: JsonElement?): Boolean = when (e) {
            null, is kotlinx.serialization.json.JsonNull -> false
            is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } != false
            else -> true
        }

        private fun parseApp(element: JsonElement?): String? = when (element) {
            is JsonObject -> {
                val name = element.str("name")
                val version = element.str("version")
                when {
                    name.isNullOrBlank() -> null
                    version.isNullOrBlank() -> name
                    else -> "$name/$version"
                }
            }
            is JsonPrimitive -> element.content.takeIf { element.isString && it.isNotBlank() }
            else -> null
        }
    }
}

/**
 * `json_metadata.speak` of a Liketu Speak voice post (RN `SpeakMeta`). Read as RN reads it: the player
 * shows when `speak.audio_url` is JS-truthy (RN never plays that URL; it streams the transcoded clip by
 * author and permlink), and `duration_ms` counts when `duration_ms > 0` in JS terms, numeric strings
 * included. One deliberate difference: non-numeric `waveform_peaks` entries are skipped (RN would draw
 * NaN bars).
 */
data class SpeakMeta(
    /** `audio_url` as text (a non-string truthy value keeps its JSON form). */
    val audioUrl: String,
    /** 0 when absent or not positive. */
    val durationMs: Long = 0,
    /** Numeric entries of `waveform_peaks`. */
    val waveformPeaks: List<Double> = emptyList(),
) {
    companion object {
        fun parse(element: JsonElement?): SpeakMeta? {
            val o = element as? JsonObject ?: return null
            val url = o["audio_url"]?.takeIf { truthy(it) } ?: return null
            val duration = (o["duration_ms"] as? JsonPrimitive)?.let { number(it) }
            val peaks = (o["waveform_peaks"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            return SpeakMeta(
                audioUrl = (url as? JsonPrimitive)?.takeIf { it.isString }?.content ?: url.toString(),
                durationMs = duration?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0,
                waveformPeaks = peaks,
            )
        }

        /** JS truthiness of a JSON value. */
        private fun truthy(e: JsonElement): Boolean = when (e) {
            is JsonPrimitive -> when {
                e is kotlinx.serialization.json.JsonNull -> false
                e.isString -> e.content.isNotEmpty()
                e.content == "true" -> true
                e.content == "false" -> false
                else -> e.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: true
            }
            else -> true
        }

        private val DECIMAL = Regex("""[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

        /**
         * A string as JS `Number()` reads it, decimal forms only: empty is 0, a plain decimal is its value,
         * anything else (hex, binary, `Infinity`, Kotlin's `9000d`) is treated as not a positive number.
         */
        private fun jsDecimal(raw: String): Double? {
            val t = com.ecency.core.render.js.Js.trim(raw)
            if (t.isEmpty()) return 0.0
            return if (DECIMAL.matches(t)) t.toDoubleOrNull() else null
        }

        /** `x > 0` / `x / 1000` operand: numbers, and decimal strings as JS `Number()` reads them. */
        private fun number(p: JsonPrimitive): Double? = when {
            p is kotlinx.serialization.json.JsonNull -> null
            p.isString -> jsDecimal(p.content)
            p.content == "true" -> 1.0
            p.content == "false" -> 0.0
            else -> p.content.toDoubleOrNull()
        }
    }
}
