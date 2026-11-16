package com.ecency.lint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Handled error reports are named by the exception's class (`e::class.simpleName`, CrashReports and ErrorReports in
 * shared). A handled report carries no frames, so the uploaded R8 mapping cannot restore a renamed class: without this
 * rule every release names the same error differently (`a77`, `n67`) and Sentry splits it into new issues.
 */
class ProguardRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }

    @Test
    fun ourThrowablesKeepTheirNames() {
        val rules = root.resolve("androidApp/proguard-rules.pro").readLines().map { it.trim() }.filterNot { it.startsWith("#") }
        assertTrue(
            rules.any { it.matches(Regex("""-keepnames\s+class\s+com\.ecency\.\*\*\s+extends\s+java\.lang\.Throwable\b.*""")) },
            "androidApp/proguard-rules.pro must keep the names of com.ecency Throwable subclasses",
        )
    }
}
