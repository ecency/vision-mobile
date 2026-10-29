package com.ecency.core.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RN TagInput rules, and text that already became chips never parsed twice. */
class EditorTagsTest {
    /** Feeds field values one by one, as the platform field reports them, carrying the consumed text along. */
    private class Field {
        var tags = emptyList<String>()
        var rest = ""
        var consumed = ""
        val toasts = mutableListOf<String>()
        fun type(value: String) {
            val r = EditorTags.input(tags, value, consumed)
            tags = r.tags
            rest = r.rest
            consumed = r.consumed
            toasts += r.toasts
        }
    }

    @Test
    fun typingWordsWithSpacesMakesOneChipEachAndClearsTheField() {
        val f = Field()
        // the field shows what the model returned after every keystroke
        for (key in "a b c ") f.type(f.rest + key)
        assertEquals(listOf("a", "b", "c"), f.tags)
        assertEquals("", f.rest)
        assertEquals(emptyList(), f.toasts)
    }

    @Test
    fun aKeystrokeOnTopOfTextThatAlreadyBecameAChipIsNotParsedAgain() {
        val f = Field()
        f.type("a ")
        assertEquals(listOf("a"), f.tags)
        assertEquals("a ", f.consumed)
        // the field had not cleared yet when "b" arrived
        f.type("a b")
        assertEquals("b", f.rest)
        f.type("a b ")
        assertEquals(listOf("a", "b"), f.tags)
        assertEquals("a b ", f.consumed)
        // the field caught up: fresh text no longer starts with the consumed text, which is forgotten
        f.type("c")
        assertEquals("", f.consumed)
        f.type("c,")
        assertEquals(listOf("a", "b", "c"), f.tags)
        assertEquals(emptyList(), f.toasts, "no duplicate toast for any of it")
    }

    @Test
    fun pastingCommaSeparatedTagsKeepsTheLastFragmentAndARealDuplicateToastsOnce() {
        val f = Field()
        f.type("a, b, a")
        assertEquals(listOf("a", "b"), f.tags)
        assertEquals("a", f.rest, "RN skipLast: the fragment after the last separator stays in the field")
        assertEquals(emptyList(), f.toasts)
        f.type("a ")
        assertEquals(listOf("a", "b"), f.tags)
        assertEquals("", f.rest)
        assertEquals(listOf(EditorTags.DUPLICATE), f.toasts)
        // pasting a duplicate in the middle: one toast for it, the rest still added
        f.type("x a y ")
        assertEquals(listOf("a", "b", "x", "y"), f.tags)
        assertEquals(listOf(EditorTags.DUPLICATE, EditorTags.DUPLICATE), f.toasts)
    }

    @Test
    fun backspaceEditsOnlyTheFieldNeverAChip() {
        val f = Field()
        f.type("a b ")
        f.type("c")
        f.type("")
        assertEquals(listOf("a", "b"), f.tags)
        assertEquals("", f.rest)
        // backspace in an empty field reports no change at all on either platform; an empty value is a no-op too
        f.type("")
        assertEquals(listOf("a", "b"), f.tags)
        assertEquals(emptyList(), f.toasts)
    }

    @Test
    fun moreThanTenTagsAreKeptWithRnsWarning() {
        val f = Field()
        for (i in 1..11) f.type("t$i ")
        assertEquals(11, f.tags.size)
        assertEquals("", f.rest)
        assertEquals("Only 10 tags allowed, remove some", EditorTags.warning(f.tags))
        assertNull(EditorTags.warning(f.tags.take(10)))
        assertEquals(emptyList(), f.toasts)
    }

    @Test
    fun uppercaseAndHashAreNormalised() {
        val f = Field()
        f.type("#Photo ")
        assertEquals(listOf("photo"), f.tags)
        f.type("PHOTO ")
        assertEquals(listOf(EditorTags.DUPLICATE), f.toasts)
    }
}
