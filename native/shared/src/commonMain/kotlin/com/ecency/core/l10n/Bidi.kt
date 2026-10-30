package com.ecency.core.l10n

/** Unicode bidi isolation for text placed inside a message (UAX #9 isolates, as ICU4J's BidiFormatter.unicodeWrap). */
object Bidi {
    /** FIRST STRONG ISOLATE: the inner text takes the direction of its first strong character. */
    const val FSI = '⁨'

    /** POP DIRECTIONAL ISOLATE: closes [FSI]. */
    const val PDI = '⁩'

    /**
     * [text] wrapped in FSI..PDI, so a Latin username, an amount or a URL inside an Arabic, Persian or Hebrew sentence
     * keeps its own direction and does not reorder the words around it. Empty text stays empty.
     */
    fun isolate(text: String): String = if (text.isEmpty()) text else "$FSI$text$PDI"

    /**
     * [text] isolated while the app language is right to left, as is otherwise: for a username, amount, id or URL the
     * UI puts next to catalog text outside a message ("From: @alice", "Balance 1.000 HIVE"), where the formatter
     * cannot isolate it. Without it the "@" or a trailing "?" lands on the wrong side in Arabic.
     */
    fun inUi(text: String): String = if (L10n.isRtl) isolate(text) else text

    /** Removes the isolates [isolate] added (for comparisons in tests and for copy to the clipboard). */
    fun strip(text: String): String = text.replace(FSI.toString(), "").replace(PDI.toString(), "")
}
