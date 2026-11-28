package com.ecency.app

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.crash.SentryReporting
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ECENCY-MOBILE-3R8, 3R9: Play's pre-launch report runs the app in Firebase Test Lab, whose crawler opens exported
 * activities of libraries (Glance's trampoline, Billing's proxy) without the extras they need. Those runs send nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SentryTestLabTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clear() {
        Settings.System.putString(context.contentResolver, "firebase.test.lab", null)
    }

    @Test
    fun aTestLabDeviceIsToldByItsSystemSetting() {
        assertFalse(SentryReporting.inTestLab(context))
        Settings.System.putString(context.contentResolver, "firebase.test.lab", "false")
        assertFalse(SentryReporting.inTestLab(context))
        Settings.System.putString(context.contentResolver, "firebase.test.lab", "true")
        assertTrue(SentryReporting.inTestLab(context))
    }

    @Test
    fun sentryNeverStartsInTestLab() {
        Settings.System.putString(context.contentResolver, "firebase.test.lab", "true")
        SentryReporting.start(context, "https://abc@example.ingest.sentry.io/1", "app.esteem.mobile.android", "4.0.0", 57, debug = false, mappingId = "")
        assertFalse(SentryReporting.started)
    }
}
