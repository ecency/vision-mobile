package com.ecency.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the app's Baseline Profile (#3702): the code of a cold start, the home feed and its scroll, a post and the
 * Waves tab, so release installs run it compiled from the first launch. Run with
 * `./gradlew :androidApp:generateBaselineProfile` on a device or emulator (docs/release-builds.md, "Baseline Profile").
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        passFirstLaunch()
        scrollHomeFeed()
        openAPost()
        openWaves()
    }
}
