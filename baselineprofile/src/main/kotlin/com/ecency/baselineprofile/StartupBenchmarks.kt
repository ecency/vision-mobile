package com.ecency.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start and home feed scroll without and with the Baseline Profile (#3702). Numbers are only meaningful on a real
 * phone: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` (docs/release-builds.md, "Baseline Profile").
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutProfile() = start(CompilationMode.None())

    @Test
    fun coldStartWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun feedScrollWithoutProfile() = scroll(CompilationMode.None())

    @Test
    fun feedScrollWithProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            // the welcome screen is answered once, outside the measured launch
            pressHome()
            startActivityAndWait()
            passFirstLaunch()
            pressHome()
        },
    ) {
        startActivityAndWait()
    }

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            passFirstLaunch()
        },
    ) {
        scrollHomeFeed()
    }
}
