package com.ecency.app

import android.os.SystemClock
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import com.ecency.core.app.SecurityConfig
import com.ecency.core.secure.monotonicMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Platform pieces of the app lock: the clock it times the background with, and the recents policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockPlatformTest {
    @Test
    fun theLockClockIsElapsedRealtime() {
        // Robolectric's clocks are frozen unless advanced: equal readings mean the same clock
        assertEquals(SystemClock.elapsedRealtime(), monotonicMillis())
        assertEquals(SystemClock.elapsedRealtime(), SecurityConfig.DEFAULT.monotonicMillis())
        val before = monotonicMillis()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        assertEquals(before + 300_000, monotonicMillis())
        assertEquals(SystemClock.elapsedRealtime(), SecurityConfig.DEFAULT.monotonicMillis())
    }

    private fun activity() = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private fun FragmentActivity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    @Config(sdk = [30])
    fun belowAndroid13TheLockSetsFlagSecure() {
        val activity = activity()
        activity.applyHideInRecents(true)
        assertTrue(activity.secure())
        activity.applyHideInRecents(false)
        assertFalse(activity.secure())
    }

    @Test
    fun fromAndroid13OnlyRecentsIsHidden() {
        val activity = activity()
        activity.applyHideInRecents(true)
        assertFalse("screenshots stay allowed on 13+", activity.secure())
        activity.applyHideInRecents(true, sdk = 30)
        assertTrue(activity.secure())
    }
}
