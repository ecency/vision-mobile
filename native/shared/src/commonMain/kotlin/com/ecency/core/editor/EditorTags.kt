package com.ecency.core.editor

import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js

/**
 * What typing into the tag field did: the tags now, what stays in the field, and RN's toasts. [consumed] is the
 * leading text that just became chips: the field may still show it for a moment (see [EditorTags.input]).
 */
data class TagInputResult(
    val tags: List<String>,
    val rest: String,
    val toasts: List<String> = emptyList(),
    val communitySelected: String? = null,
    val consumed: String = "",
)

/** RN TagInput (components/editorElements/tagInput): how typed text becomes tags, and the warning line under them. */
object EditorTags {
    const val MAX_TAGS = 10
    const val MAX_TAG_LENGTH = 24
    const val MAX_INPUT_LENGTH = 100
    val DUPLICATE: String get() = L.editor.tagDuplicate
    val COMMUNITY_SELECTED: String get() = L.editor.communitySelected

    private fun isSeparator(c: Char) = c == ',' || Js.isSpace(c)

    /** RN utils/communityValidation isCommunity: /hive-[1-3]\d{4,6}$/ (not anchored at the start). */
    fun isCommunity(text: String): Boolean {
        val at = text.lastIndexOf("hive-")
        if (at < 0) return false
        // the regex may match at any "hive-" whose digits run to the end
        var i = text.indexOf("hive-")
        while (i >= 0) {
            val digits = text.substring(i + 5)
            if (digits.length in 5..7 && digits[0] in '1'..'3' && digits.all { it in '0'..'9' }) return true
            i = text.indexOf("hive-", i + 1)
        }
        return false
    }

    /** One tag joins [tags]: `#` dropped, duplicates refused, a community tag goes first unless one is there already. */
    fun register(tags: List<String>, raw: String): TagInputResult {
        val tag = if (raw.startsWith("#")) raw.substring(1) else raw
        if (tag.isEmpty()) return TagInputResult(tags, "")
        if (tag in tags) return TagInputResult(tags, "", toasts = listOf(DUPLICATE))
        if (isCommunity(tag) && !(tags.isNotEmpty() && isCommunity(tags[0]))) {
            return TagInputResult(listOf(tag) + tags, "", toasts = listOf(COMMUNITY_SELECTED), communitySelected = tag)
        }
        return TagInputResult(tags + tag, "")
    }

    /**
     * A keystroke: lowercased; everything before the last separator (comma or whitespace) becomes tags.
     *
     * [consumed] is the previous result's [TagInputResult.consumed]. A text field applies the cleared text a frame
     * later than the keystroke that made the chip, so the next keystroke can still arrive on top of the old text
     * ("a " then "a b"): that leading text was parsed already and is dropped here, never parsed (and refused as a
     * duplicate) again.
     */
    fun input(tags: List<String>, raw: String, consumed: String = ""): TagInputResult {
        var text = Js.toLowerCase(raw).take(MAX_INPUT_LENGTH)
        val stale = if (consumed.isNotEmpty() && text.startsWith(consumed)) consumed else ""
        text = text.substring(stale.length)
        if (text.none(::isSeparator)) return TagInputResult(tags, text, consumed = stale)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (c in text) {
            if (isSeparator(c)) {
                pieces += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        val parsed = stale + text.substring(0, text.length - current.length)
        var result = TagInputResult(tags, current.toString(), consumed = parsed)
        for (piece in pieces) {
            val r = register(result.tags, piece)
            result = result.copy(tags = r.tags, toasts = result.toasts + r.toasts, communitySelected = r.communitySelected ?: result.communitySelected)
        }
        return result
    }

    /** The field lost focus: what is left becomes a tag when it is longer than one character. */
    fun endEditing(tags: List<String>, rest: String): TagInputResult =
        if (rest.length > 1) register(tags, rest) else TagInputResult(tags, rest)

    /** RN _verifyTagsUpdate: the first rule that fails, in RN's order; null when all pass or there are no tags. */
    fun warning(tags: List<String>): String? = when {
        tags.isEmpty() -> null
        tags.size > MAX_TAGS -> L.editor.limitedTags
        tags.any { it.length > MAX_TAG_LENGTH } -> L.editor.limitedLength
        tags.any { it.split('-').size > 2 } -> L.editor.limitedDash
        tags.any { it.contains(',') } -> L.editor.limitedSpace
        tags.any { t -> t.any { it in 'A'..'Z' } } -> L.editor.limitedLowercase
        tags.any { t -> t.isEmpty() || !t.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '#' } } -> L.editor.limitedCharacters
        tags.any { t -> t.last().let { it !in 'a'..'z' && it !in '0'..'9' } } -> L.editor.limitedLastchar
        else -> null
    }
}
