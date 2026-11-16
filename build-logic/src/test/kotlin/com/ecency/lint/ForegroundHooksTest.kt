package com.ecency.lint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Both apps tell the same models that the app is back in front: every `env.<model>.onForeground()` Android calls on
 * ON_START (ui/EcencyApp.kt) is called by iOS on didBecomeActive (App/AppContainer.swift). The app lock is the one
 * exception: iOS drives it from LockView, which owns the lock's scene phase.
 */
class ForegroundHooksTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val hook = Regex("""env\.(\w+)\.onForeground\(\)""")

    private fun block(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        check(from >= 0) { "missing `$start`" }
        val to = text.indexOf(end, from + start.length)
        check(to >= 0) { "missing `$end` after `$start`" }
        return text.substring(from, to)
    }

    @Test
    fun iosCallsEveryForegroundHookAndroidCalls() {
        val android = block(root.resolve("androidApp/src/main/kotlin/com/ecency/app/ui/EcencyApp.kt").readText(), "Lifecycle.Event.ON_START ->", "Lifecycle.Event.")
        val ios = block(root.resolve("iosApp/EcencyApp/App/AppContainer.swift").readText(), "UIApplication.didBecomeActiveNotification", "UIApplication.")
        val missing = hook.findAll(android).map { it.groupValues[1] }.toSet() - "appLock" - hook.findAll(ios).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), missing, "iOS didBecomeActive misses these foreground hooks Android calls")
    }
}
