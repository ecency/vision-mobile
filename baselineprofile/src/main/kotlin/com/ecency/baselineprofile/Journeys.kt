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
 * A first launch: the welcome screen's terms and the system's notification question (Android 13+) are answered, so the
 * home feed shows. Later launches go straight on: the welcome screen and the app's tabs never show together
 * (EcencyApp.kt). The app exposes its Compose test tags as resource ids.
 */
fun MacrobenchmarkScope.passFirstLaunch() {
    device.wait(Until.hasObject(By.res(Pattern.compile("welcome|feed")).pkg(PACKAGE)), WAIT_MILLIS) || error("neither the welcome screen nor the feed showed; on screen: ${onScreen()}")
    if (device.hasObject(By.res("welcome").pkg(PACKAGE))) {
        // after an update the terms come back already ticked: a tap then would untick them and leave Start disabled
        device.findObject(By.res("welcomeTerms"))?.takeIf { !it.isChecked }?.click()
        device.wait(Until.findObject(By.res("welcomeStart").enabled(true)), 5_000)?.click()
        device.wait(Until.findObject(By.res(DENY)), 3_000)?.click()
    } else {
        device.findObject(By.res(DENY))?.click()
    }
}

/** The system's notification permission dialog, in the device's language: its deny button by id. */
private val DENY = Pattern.compile(".*permission_deny_button")

/** The home feed with its first cards loaded: its container alone is also there with a skeleton or an error. */
fun MacrobenchmarkScope.awaitHomeFeed() {
    device.wait(Until.hasObject(By.res("feed")), WAIT_MILLIS) || error("the home feed never showed; on screen: ${onScreen()}")
    device.wait(Until.hasObject(By.res(Pattern.compile("card:.*"))), WAIT_MILLIS) || error("no feed card loaded; on screen: ${onScreen()}")
}

/** The loaded home feed scrolled down and back to where it was, so each benchmark iteration scrolls the same way. */
fun MacrobenchmarkScope.flingHomeFeed() {
    val feed = device.findObject(By.res("feed")) ?: error("no home feed; on screen: ${onScreen()}")
    feed.setGestureMargin(device.displayWidth / 5)
    repeat(3) { feed.fling(Direction.DOWN) }
    device.waitForIdle()
    repeat(3) { feed.fling(Direction.UP) }
    device.waitForIdle()
}

/** The home feed: its first cards load, then it is scrolled down and back. */
fun MacrobenchmarkScope.scrollHomeFeed() {
    awaitHomeFeed()
    flingHomeFeed()
}

/** A post from the feed: its body renders, it is scrolled, then back to the feed. */
fun MacrobenchmarkScope.openAPost() {
    // a card opens from its title and image (hidden from accessibility, so not findable); a tap in the middle of the
    // most visible card lands there (the first may sit half under the filter bar, its middle on another view)
    device.wait(Until.hasObject(By.res(Pattern.compile("card:.*"))), WAIT_MILLIS) || error("no feed card")
    device.findObjects(By.res(Pattern.compile("card:.*"))).maxBy { it.visibleBounds.height() }.click()
    val post = device.wait(Until.findObject(By.res("postList")), WAIT_MILLIS) ?: error("the post never opened; on screen: ${onScreen()}")
    // the list is there with the skeleton too. Once the header shows the post is read, and from then on the body's
    // skeleton shows only until the body rendered (PostScreen.kt); an empty spacer like bodyStart is not in the tree
    device.wait(Until.hasObject(By.res("postHeader")), WAIT_MILLIS) || error("the post was never read; on screen: ${onScreen()}")
    device.wait(Until.gone(By.res("skeleton:body")), WAIT_MILLIS) || error("the post body never rendered; on screen: ${onScreen()}")
    post.setGestureMargin(device.displayWidth / 5)
    post.fling(Direction.DOWN)
    device.waitForIdle()
    device.pressBack()
    device.wait(Until.hasObject(By.res("feed")), WAIT_MILLIS) || error("not back on the feed; on screen: ${onScreen()}")
}

/** The Waves tab, whose list loads and is scrolled. */
fun MacrobenchmarkScope.openWaves() {
    device.wait(Until.findObject(By.res("bottomTab:WAVES")), WAIT_MILLIS)?.click() ?: error("no Waves tab")
    device.waitForIdle()
    val list = device.wait(Until.findObject(By.res("wavesList")), WAIT_MILLIS) ?: error("the waves never showed")
    // the list is there while it loads, fails or is empty: a wave's header is there only for a loaded wave
    device.wait(Until.hasObject(By.res("wave:header")), WAIT_MILLIS) || error("no wave loaded; on screen: ${onScreen()}")
    list.setGestureMargin(device.displayWidth / 5)
    list.fling(Direction.DOWN)
    device.waitForIdle()
}
