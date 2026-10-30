package com.ecency.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden comparison against the JS diff-match-patch (native/tools/golden/generate-dmp.mjs).
 * No deviations are allowed: every case must match exactly.
 */
class DiffMatchPatchTest {
    private class Reader(private val s: String) {
        var pos = 0
        fun tok(): String {
            val colon = s.indexOf(':', pos)
            val len = s.substring(pos, colon).toInt()
            val out = s.substring(colon + 1, colon + 1 + len)
            pos = colon + 1 + len
            return out
        }
        fun int(): Int = tok().toInt()
        fun script(): List<Triple<Int, Int, String>> = List(int()) { Triple(int(), int(), tok()) }
        fun done() = pos == s.length
    }

    private fun applyScript(text: String, script: List<Triple<Int, Int, String>>): String {
        var s = text
        for ((p, d, ins) in script) s = DiffMatchPatch.sub(s, 0, p) + ins + DiffMatchPatch.sub(s, p + d)
        return s
    }

    private fun fnv(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    private fun digest(s: String) = if (s.length <= 200) "=$s" else "#${s.length}/${fnv(s)}"

    private fun shape(diffs: List<Diff>) = diffs.joinToString(",") {
        val sign = when (it.operation) {
            Operation.DELETE -> "-"
            Operation.INSERT -> "+"
            Operation.EQUAL -> "="
        }
        sign + it.text.length
    }

    private fun flags(f: List<Boolean>) = f.joinToString("") { if (it) "1" else "0" }

    private fun dmp(mode: String) = DiffMatchPatch().apply { diffTimeout = if (mode[0] == 'L') 1e6 else 0.0 }

    private fun checkCases(data: String): Int {
        val r = Reader(data)
        val count = r.int()
        val failures = mutableListOf<String>()
        repeat(count) {
            val name = r.tok()
            val mode = r.tok()
            val a = r.tok()
            val b = applyScript(a, r.script())
            val rawShape = r.tok()
            val semShape = r.tok()
            val html = r.tok()
            val patchText = r.tok()
            val applyA = r.tok()
            val a2 = applyScript(a, r.script())
            val applyA2 = r.tok()

            fun check(what: String, expected: String, actual: String) {
                if (expected != actual) failures += "$name/$mode $what\n  expected: ${expected.take(300)}\n  actual:   ${actual.take(300)}"
            }

            val d = dmp(mode)
            val diffs = d.diffMain(a, b, mode[1] == '1')
            check("text1", a, d.diffText1(diffs))
            check("text2", b, d.diffText2(diffs))
            check("diff_main", rawShape, shape(diffs))
            d.diffCleanupSemantic(diffs)
            check("cleanupSemantic", semShape, shape(diffs))
            check("prettyHtml", html, digest(d.diffPrettyHtml(diffs)))

            if (patchText == "!") {
                // JS encodeURI threw (a diff split a surrogate pair): the port must refuse too.
                val threw = runCatching { d.patchToText(d.patchMake(a, b)) }.isFailure
                if (!threw) failures += "$name/$mode patch_toText should throw"
            } else {
                check("patch_make", patchText, d.patchToText(d.patchMake(a, b)))
                val patches = d.patchFromText(patchText)
                check("patch_fromText", patchText, d.patchToText(patches))
                val (t1, f1) = d.patchApply(patches, a)
                check("patch_apply(a)", applyA, flags(f1) + "|" + digest(t1))
                val (t2, f2) = d.patchApply(patches, a2)
                check("patch_apply(a2)", applyA2, flags(f2) + "|" + digest(t2))
            }
        }
        assertTrue(r.done(), "fixture stream not fully consumed")
        if (failures.isNotEmpty()) {
            fail("${failures.size} mismatches of $count cases:\n" + failures.take(15).joinToString("\n"))
        }
        return count
    }

    @Test fun goldenCases0() { assertTrue(checkCases(DmpFixtures0.data) > 0) }
    @Test fun goldenCases1() { assertTrue(checkCases(DmpFixtures1.data) > 0) }
    @Test fun goldenCases2() { assertTrue(checkCases(DmpFixtures2.data) > 0) }

    @Test
    fun patchTextCases() {
        val r = Reader(DmpPatchFixtures.data)
        val count = r.int()
        val failures = mutableListOf<String>()
        repeat(count) { i ->
            val text = r.tok()
            val target = r.tok()
            val parsed = r.tok()
            val applied = r.tok()
            val d = dmp("L1")
            val result = runCatching {
                val patches = d.patchFromText(text)
                val back = d.patchToText(patches)
                val (t, f) = d.patchApply(patches, target)
                back to flags(f) + "|" + digest(t)
            }
            if (parsed == "!") {
                if (result.isSuccess) failures += "#$i should throw: $text"
            } else {
                val (back, app) = result.getOrElse { failures += "#$i threw $it: $text"; return@repeat }
                if (back != parsed) failures += "#$i patch_toText expected <$parsed> got <$back>"
                if (app != applied) failures += "#$i patch_apply expected <$applied> got <$app>"
            }
        }
        assertTrue(r.done())
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun prettyHtmlEscapes() {
        val d = DiffMatchPatch()
        val html = d.diffPrettyHtml(
            listOf(Diff(Operation.EQUAL, "a\n"), Diff(Operation.DELETE, "<B>b</B>"), Diff(Operation.INSERT, "c&d")),
        )
        assertEquals(
            "<span>a&para;<br></span><del style=\"background:#ffe6e6;\">&lt;B&gt;b&lt;/B&gt;</del>" +
                "<ins style=\"background:#e6ffe6;\">c&amp;d</ins>",
            html,
        )
    }

    @Test
    fun uriCodec() {
        assertEquals("%3B", DiffMatchPatch.decodeUri("%3B"))
        assertEquals("%", DiffMatchPatch.decodeUri("%25"))
        assertEquals("é🐝", DiffMatchPatch.decodeUri("%c3%A9%F0%9F%90%9D"))
        assertEquals("%C3%A9%F0%9F%90%9D;,/?:@&=+\$#", DiffMatchPatch.encodeUri("é🐝;,/?:@&=+\$#"))
        assertFailsWith<IllegalArgumentException> { DiffMatchPatch.decodeUri("%E9") }
        assertFailsWith<IllegalArgumentException> { DiffMatchPatch.encodeUri("\uD83D") }
    }

    @Test
    fun defaultTimeoutStillProducesAValidDiff() {
        // With the default 1 s deadline the result may be coarser, but must always rebuild both texts.
        val a = buildString { repeat(4000) { append(('a' + (it * 7) % 26)) } }
        val b = buildString { repeat(4000) { append(('a' + (it * 11) % 26)) } }
        val d = DiffMatchPatch().apply { diffTimeout = 0.001 }
        val diffs = d.diffMain(a, b)
        assertEquals(a, d.diffText1(diffs))
        assertEquals(b, d.diffText2(diffs))
    }
}
