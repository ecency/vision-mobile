package com.ecency.core.render.methods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [JsRegex] with `ignoreCase`: escapes that carry hex digits or a property name are copied as they stand. */
class JsRegexTest {
    @Test
    fun unicodeAndHexEscapesAreNotRewrittenByIgnoreCase() {
        // é has the hex letter "e": rewritten as a letter it would become \u00[eE]9 and stop compiling or matching
        assertEquals("\\u00e9", JsRegex.translate("\\u00e9", ignoreCase = true))
        assertEquals("[\\u00a1-\\uffff]", JsRegex.translate("[\\u00a1-\\uffff]", ignoreCase = true))
        assertEquals("\\x41", JsRegex.translate("\\x41", ignoreCase = true))
        assertEquals("[\\xAf]", JsRegex.translate("[\\xAf]", ignoreCase = true))
        assertEquals("\\p{L}[aA]", JsRegex.translate("\\p{L}a", ignoreCase = true))
        val accented = JsRegex.compile("^caf\\u00e9[a-f\\u00a1-\\uffff]+$", ignoreCase = true)
        assertTrue(accented.matches("CAFéüBE"))
        assertFalse(accented.matches("cafe"))
        assertFalse(accented.matches("caféz"))
        assertTrue(JsRegex.compile("^\\x41b$", ignoreCase = true).matches("AB"))
    }

    @Test
    fun plainLettersStillFoldAndIncompleteEscapesAreLeftAlone() {
        assertEquals("[aA][bB]", JsRegex.translate("ab", ignoreCase = true))
        assertEquals("[a-zA-Z]", JsRegex.translate("[a-z]", ignoreCase = true))
        // not four hex digits: only the escape itself is taken, the rest is ordinary pattern text
        assertEquals("\\u[aA][gG]", JsRegex.translate("\\uag", ignoreCase = true))
        assertEquals("\\u00e9", JsRegex.translate("\\u00e9", ignoreCase = false))
    }

    /** The 2.5.52 port's embed and start-time patterns read case as JS does, on every platform (review of #3711). */
    @Test
    fun thePortsPatternsDoNotFoldTheLongS() {
        // JS /i without u never folds U+017F to s: upstream drops this src, so must native
        val out = com.ecency.core.render.sanitize.HtmlSanitizer.sanitize(
            "<iframe src=\"http\u017f://www.youtube.com/embed/aaaaaaaaaaa\"></iframe>",
            com.ecency.core.render.sanitize.SanitizeOptions(),
        )
        assertFalse("youtube.com" in out, out)
        assertEquals("", Helper.extractYtStartTime("https://www.youtube.com/watch?v=aaaaaaaaaaa&t=90\u017f"))
        assertEquals("90", Helper.extractYtStartTime("https://www.youtube.com/watch?v=aaaaaaaaaaa&t=90S"))
    }
}
