package com.ecency.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The installed app the journeys drive (the release build's id, the store one). */
const val PACKAGE = "app.esteem.mobile.android"

/** Generous: an emulator renders a post body far slower than a phone. */
private const val WAIT_MILLIS = 60_000L

/** The app's tagged views on screen, for a failure message. */
private fun MacrobenchmarkScope.onScreen(): String {
    val out = java.io.ByteArrayOutputStream()
    device.dumpWindowHierarchy(out)
    return Regex("resource-id=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.filter { ":" !in it || "card:" in it || "Tab:" in it }.distinct().take(40).joinToString()
}

/**
 * A first launch: the system's notification question (Android 13+) and the welcome screen's terms are answered, so the
 * home feed shows. Nothing to do on later launches. The app exposes its Compose test tags as resource ids.
 */
fun MacrobenchmarkScope.passFirstLaunch() {
    device.wait(Until.hasObject(By.res("welcome").pkg(PACKAGE)), 8_000)
    // after an update the terms come back already ticked: a tap then would untick them and leave Start disabled
    device.findObject(By.res("welcomeTerms"))?.takeIf { !it.isChecked }?.click()
    device.wait(Until.findObject(By.res("welcomeStart").enabled(true)), 5_000)?.click()
    // the notification permission dialog belongs to the system, in the device's language: its deny button by id
    device.wait(Until.findObject(By.res(Pattern.compile(".*permission_deny_button"))), 3_000)?.click()
}

/** The home feed: its first cards load, then it is scrolled down and back. */
fun MacrobenchmarkScope.scrollHomeFeed() {
    val feed = device.wait(Until.findObject(By.res("feed")), WAIT_MILLIS) ?: error("the home feed never showed")
    device.wait(Until.hasObject(By.res(Pattern.compile("card:.*"))), WAIT_MILLIS)
    feed.setGestureMargin(device.displayWidth / 5)
    repeat(3) { feed.fling(Direction.DOWN) }
    device.waitForIdle()
    feed.fling(Direction.UP)
    device.waitForIdle()
}

/** A post from the feed: its body renders, it is scrolled, then back to the feed. */
fun MacrobenchmarkScope.openAPost() {
    // a card opens from its title and image (hidden from accessibility, so not findable); a tap in the middle of the
    // most visible card lands there (the first may sit half under the filter bar, its middle on another view)
    device.wait(Until.hasObject(By.res(Pattern.compile("card:.*"))), WAIT_MILLIS) || error("no feed card")
    device.findObjects(By.res(Pattern.compile("card:.*"))).maxBy { it.visibleBounds.height() }.click()
    val post = device.wait(Until.findObject(By.res("postList")), WAIT_MILLIS) ?: error("the post never opened; on screen: ${onScreen()}")
    post.setGestureMargin(device.displayWidth / 5)
    post.fling(Direction.DOWN)
    device.waitForIdle()
    device.pressBack()
    device.wait(Until.hasObject(By.res("feed")), WAIT_MILLIS)
}

/** The Waves tab, whose list loads and is scrolled. */
fun MacrobenchmarkScope.openWaves() {
    device.wait(Until.findObject(By.res("bottomTab:WAVES")), WAIT_MILLIS)?.click() ?: error("no Waves tab")
    device.waitForIdle()
    val list = device.wait(Until.findObject(By.res("wavesList")), WAIT_MILLIS) ?: error("the waves never showed")
    list.setGestureMargin(device.displayWidth / 5)
    list.fling(Direction.DOWN)
    device.waitForIdle()
}
