package com.ecency.core.render.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebUrlTest {
    private fun actual(c: UrlCase): Pair<String?, List<String>> {
        val u = WebUrl.parse(c.input) ?: return null to emptyList()
        return u.href to listOf(u.protocol, u.host, u.hostname, u.pathname, u.search, u.hash)
    }

    @Test
    fun matchesNodeUrl() {
        val failures = UrlFixtures.cases.mapNotNull { c ->
            val (href, parts) = actual(c)
            if (href == c.href && (href == null || parts == c.parts)) null
            else "input=${c.input.debug()}\n  expected ${c.href?.debug()} ${c.parts}\n  actual   ${href?.debug()} $parts"
        }
        assertTrue(failures.isEmpty(), "${failures.size}/${UrlFixtures.cases.size} mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun searchParamsAppendReserializesLikeTheBrowser() {
        for (c in UrlFixtures.appends) {
            val u = WebUrl.parse(c.base)!!
            for ((k, v) in FormUrlEncoded.parse(c.query)) u.appendSearchParam(k, v)
            assertEquals(c.href, u.href, c.base + " + " + c.query)
        }
    }

    private fun String.debug() = buildString {
        for (ch in this@debug) if (ch.code in 0x20..0x7E) append(ch) else append("\\u" + ch.code.toString(16).padStart(4, '0'))
    }
}
