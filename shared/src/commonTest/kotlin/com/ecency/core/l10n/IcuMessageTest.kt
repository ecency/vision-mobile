package com.ecency.core.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IcuMessageTest {
    private fun fmt(m: String, vararg args: Pair<String, Any>, locale: String = "en-US", isolate: Boolean = false): String =
        IcuMessage.parse(m).format(
            args.toMap(),
            plural = { L10nPluralRules.select(locale, it) },
            isolate = if (isolate) Bidi::isolate else { s -> s },
        )

    @Test
    fun simpleArgumentsAndNumbers() {
        assertEquals("Plain", fmt("Plain"))
        assertEquals("by alice in hive-1", fmt("by {username} in {community}", "username" to "alice", "community" to "hive-1"))
        assertEquals("3 and 3", fmt("{n} and {n, number}", "n" to 3))
        assertEquals("1.5 / 2 / 0.25", fmt("{a, number} / {b, number} / {c}", "a" to 1.5, "b" to 2.0, "c" to 0.25))
        assertEquals("a # b", fmt("a # b"))
        assertEquals("", fmt(""))
    }

    @Test
    fun pluralWithPoundExactMatchesAndOffset() {
        val m = "{count, plural, =0 {no votes} one {# vote} other {# votes}}"
        assertEquals("no votes", fmt(m, "count" to 0))
        assertEquals("1 vote", fmt(m, "count" to 1))
        assertEquals("2 votes", fmt(m, "count" to 2))
        assertEquals("1.5 votes", fmt(m, "count" to 1.5))
        val ru = "{n, plural, one {# голос} few {# голоса} many {# голосов} other {# голоса}}"
        assertEquals(
            listOf("1 голос", "3 голоса", "5 голосов", "11 голосов", "21 голос", "1.5 голоса"),
            listOf<Number>(1, 3, 5, 11, 21, 1.5).map { fmt(ru, "n" to it, locale = "ru-RU") },
        )
        val offset = "{n, plural, offset:1 =0 {nobody} =1 {you} one {you and # other} other {you and # others}}"
        assertEquals(listOf("nobody", "you", "you and 1 other", "you and 4 others"), listOf(0, 1, 2, 5).map { fmt(offset, "n" to it) })
        assertEquals("you and 2 others", fmt("{n, plural, offset: 1 other {you and # others}}", "n" to 3), "a space after offset:")
        // a missing category falls to other
        assertEquals("2 x", fmt("{n, plural, one {# y} other {# x}}", "n" to 2, locale = "ar-SA"))
    }

    @Test
    fun selectAndNesting() {
        val m = "{g, select, female {She has {n, plural, one {# post} other {# posts}}} other {They have # {n}}}"
        assertEquals("She has 1 post", fmt(m, "g" to "female", "n" to 1))
        assertEquals("They have # 2", fmt(m, "g" to "x", "n" to 2))
        // # is replaced only in the plural's own sub-messages, not inside a nested select (ICU4J, formatjs)
        assertEquals("# x 2", fmt("{n, plural, other {{g, select, other {# x}} #}}", "n" to 2, "g" to "a"))
    }

    @Test
    fun apostrophesFollowIcuDoubleOptional() {
        assertEquals("You're all set", fmt("You're all set"))
        assertEquals("l'utilisateur alice", fmt("l'utilisateur {u}", "u" to "alice"))
        assertEquals("don't", fmt("don''t"))
        assertEquals("{literal} and }", fmt("'{literal}' and '}'"))
        assertEquals("it's {x}", fmt("it''s '{x}'"))
        assertEquals("# 2 '#'", fmt("{n, plural, other {'#' # '''#'''}}", "n" to 2))
        assertEquals("{open to the end", fmt("'{open to the end"))
        // '| is not a quote (no ChoiceFormat): the apostrophe stays and the argument after it is substituted
        assertEquals("a '|bob' b", fmt("a '|{u}' b", "u" to "bob"))
    }

    @Test
    fun placeholdersAreIsolatedWhenAsked() {
        assertEquals("مرحبا ⁨alice⁩!", fmt("مرحبا {u}!", "u" to "alice", isolate = true))
        assertEquals("⁨3⁩ / 3", fmt("{n} / {n, plural, other {#}}", "n" to 3, isolate = true))
    }

    @Test
    fun argNamesIncludeNestedOnes() {
        assertEquals(setOf("g", "n", "who"), IcuMessage.parse("{g, select, a {{n, plural, other {# {who}}}} other {x}}").argNames)
        assertEquals(emptySet(), IcuMessage.parse("'{not}' an arg").argNames)
    }

    @Test
    fun malformedMessagesThrowSyntaxErrors() {
        listOf(
            "{", "{x", "x}", "{}", "{x, plural, one {a}}", "{x, plural, other {a}", "{x, date}", "{x, plural, =a {b} other {c}}",
            "{x, plural, other {a} other {b}}", "{x, select, other {a}", "{x,}", "{x, plural, one {a} offset:1 other {b}}",
            // refused rather than rendered wrong: no ordinal rules, no number styles
            "{x, selectordinal, one {#st} other {#th}}", "{x, number, percent}", "{x, number, integer}", "{x, plural, offset: other {a}}",
        ).forEach { m -> assertFailsWith<IcuSyntaxException>(m) { IcuMessage.parse(m) } }
    }

    @Test
    fun missingOrWrongArgumentsThrowFormatErrors() {
        assertFailsWith<IcuFormatException> { fmt("{x}") }
        assertFailsWith<IcuFormatException> { fmt("{x, plural, other {#}}", "x" to "three") }
    }

    @Test
    fun numberFormattingIsPlain() {
        assertEquals("0", IcuMessage.formatNumber(0.0))
        assertEquals("-12", IcuMessage.formatNumber(-12.0))
        assertEquals("1234567", IcuMessage.formatNumber(1234567.0))
        assertEquals("0.125", IcuMessage.formatNumber(0.125))
        assertEquals("-2.05", IcuMessage.formatNumber(-2.05))
        assertEquals("1", IcuMessage.formatNumber(1.0000001))
    }
}
