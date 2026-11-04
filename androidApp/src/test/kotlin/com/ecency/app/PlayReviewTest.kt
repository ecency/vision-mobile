package com.ecency.app

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Android rating prompt over Google Play's own fake review manager: nothing reaches Play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayReviewTest {
    /** Counts what the app asks of Play; [fails] makes the first step fail (no Play Store). */
    private class Recording(private val real: ReviewManager, private val fails: Boolean = false) : ReviewManager {
        var requested = 0
        var launched = 0

        override fun requestReviewFlow(): Task<ReviewInfo> {
            requested++
            return if (fails) Tasks.forException(IllegalStateException("no Play Store")) else real.requestReviewFlow()
        }

        override fun launchReviewFlow(activity: Activity, info: ReviewInfo): Task<Void> {
            launched++
            return real.launchReviewFlow(activity, info)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ask(review: PlayReview): Boolean {
        val result = CompletableDeferred<Boolean>()
        CoroutineScope(Dispatchers.Unconfined).launch { result.complete(review.request()) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("the flow answered", result.isCompleted)
        return result.getCompleted()
    }

    @Test
    fun theFlowRunsOverTheActivityInFront() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val manager = Recording(FakeReviewManager(activity))
                val review = PlayReview { manager }
                // no activity yet: nothing is asked of Play, the one request is not spent
                assertFalse(ask(review))
                assertEquals(0, manager.requested)
                review.attach(activity)
                assertTrue(ask(review))
                assertEquals(1, manager.requested)
                assertEquals(1, manager.launched)
                // the activity goes: nothing shows over a dead one
                review.detach(activity)
                assertFalse(ask(review))
                assertEquals(1, manager.requested)
            }
        }
    }

    @Test
    fun nothingShowsWhileTheAppIsNotInFrontAndAFailedFlowIsNotARequest() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var manager: Recording? = null
            val review = PlayReview { manager!! }
            scenario.onActivity { activity ->
                manager = Recording(FakeReviewManager(activity), fails = true)
                review.attach(activity)
                assertFalse("no Play Store: RN's requestInAppReview answers false", ask(review))
                assertEquals(1, manager!!.requested)
                assertEquals(0, manager!!.launched)
                manager = Recording(FakeReviewManager(activity))
            }
            // in the background (or under another activity): the prompt is not asked for at all
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                assertFalse(ask(review))
                assertEquals(0, manager!!.requested)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue(ask(review)) }
        }
    }
}
