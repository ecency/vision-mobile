package com.ecency.l10n.gen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluralRuleCompilerTest {
    @Test
    fun compilesRelationsRangesAndPrecedence() {
        assertEquals("(inR(o.i, 1.0, 1.0) && inR(o.v, 0.0, 0.0))", PluralRuleCompiler.compile("i = 1 and v = 0"))
        assertEquals("inR(o.i, 0.0, 0.0) || inR(o.n, 1.0, 1.0)", PluralRuleCompiler.compile("i = 0 or n = 1"))
        assertEquals("(inR((o.n % 100.0), 3.0, 10.0))".removeSurrounding("(", ")"), PluralRuleCompiler.compile("n % 100 = 3..10"))
        assertEquals(
            "(inR(o.v, 0.0, 0.0) && !(inR((o.i % 10.0), 4.0, 4.0) || inR((o.i % 10.0), 6.0, 6.0) || inR((o.i % 10.0), 9.0, 9.0)))",
            PluralRuleCompiler.compile("v = 0 and i % 10 != 4,6,9"),
        )
        assertEquals("!inR(o.e, 0.0, 5.0)", PluralRuleCompiler.compile("e != 0..5"))
        assertEquals("inR(o.e, 0.0, 0.0)", PluralRuleCompiler.compile("c = 0"))
    }

    @Test
    fun rejectsUnknownSyntax() {
        listOf("", "x = 1", "n in 1..2", "n = 1.5", "n = a").forEach { r ->
            assertFailsWith<IllegalArgumentException>(r) { PluralRuleCompiler.compile(r) }
        }
    }
}
