package com.ecency.app

import com.ecency.core.EcencyCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun appAndCoreShareTheVersion() {
        assertEquals(BuildConfig.VERSION_NAME, EcencyCore.VERSION)
    }

    @Test
    fun theAppIsAnUpdateOfTheRnApp() {
        // README "In-place upgrade from RN 3.x": same id as the RN store app, so users update in place.
        assertEquals("app.esteem.mobile.android", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun versionCodeContinuesTheRnScheme() {
        // RN android/app/build.gradle: major * 10000 + minor * 100 + patch (RN 3.5.11 = 30511).
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.RN_VERSION_CODE)
        assertTrue("an update must be above the last RN version", BuildConfig.RN_VERSION_CODE > 30511)
        // docs/release-builds.md: store builds use the build number (CI: the Unix time, as the RN workflows),
        // local builds RN's formula; never below the formula.
        val expected = if (BuildConfig.BUILD_NUMBER == 0) BuildConfig.RN_VERSION_CODE else BuildConfig.BUILD_NUMBER
        assertEquals(expected, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_CODE >= BuildConfig.RN_VERSION_CODE)
        // Play's maximum versionCode.
        assertTrue(BuildConfig.VERSION_CODE <= 2_100_000_000)
    }
}
