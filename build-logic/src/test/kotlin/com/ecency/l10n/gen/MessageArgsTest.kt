package com.ecency.l10n.gen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageArgsTest {
    private fun args(m: String) = MessageArgs.of(m).map { "${it.name}:${it.kind}" }

    @Test
    fun simpleNumberPluralSelect() {
        assertEquals(emptyList(), args("Plain text, with commas"))
        assertEquals(listOf("username:TEXT", "count:TEXT"), args("{username} has {count}"))
        assertEquals(listOf("n:NUMBER"), args("{n, number} and {n,number}"))
        assertEquals(listOf("count:PLURAL", "who:TEXT"), args("{count, plural, =0 {none} one {# by {who}} other {# by {who}}}"))
        assertEquals(listOf("g:SELECT", "n:PLURAL"), args("{g, select, male {{n, plural, one {#} other {#}}} other {x}}"))
        assertEquals(listOf("n:PLURAL"), args("{n, plural, offset:1 one {a} other {b}}"))
        assertEquals(listOf("n:PLURAL"), args("{n, plural, offset: 1 one {a} other {b}}"))
        assertEquals(listOf("n:PLURAL"), args("{n} {n, plural, other {#}}"))
    }

    @Test
    fun apostrophesQuoteOnlyBeforeSyntax() {
        assertEquals(emptyList(), args("It's '{literal}' and l'{x}'"))
        assertEquals(listOf("x:TEXT"), args("You're {x}, don''t"))
        assertEquals(emptyList(), args("'{unclosed"))
        // '| is not a quote (no ChoiceFormat), so the argument after it counts
        assertEquals(listOf("x:TEXT"), args("a '|{x}' b"))
    }

    @Test
    fun malformedThrows() {
        listOf(
            "{", "{x", "x}", "{x, plural, one {a}}", "{x, plural, other {a}", "{x, date}",
            "{x, plural, other {a} other {b}}", "{x, select, other {a}} {x, plural, other {b}}", "{}",
            // not rendered by the runtime, so refused at build time
            "{x, number, percent}", "{x, number, integer}", "{x, selectordinal, one {#st} other {#th}}",
        ).forEach { m -> assertFailsWith<IllegalArgumentException>(m) { MessageArgs.of(m) } }
    }
}
