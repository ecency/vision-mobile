package com.ecency.app

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * RN `requestInAppReview` (expo-store-review) on Android: Google Play's In-App Review flow. When it is asked for
 * is decided by the shared rules ([com.ecency.core.app.AppRating]: once, after a publish or vote that went out,
 * 3 days and 5 app starts); this only runs the flow for the activity that is in front.
 *
 * True when the flow ran to its end. Play does not say whether a dialog was shown (it has its own quota), so, as
 * in RN, a completed flow counts as the one request. False when there is no activity in front, no Play Store, or
 * the flow failed: the request is then not spent.
 */
class PlayReview(private val managers: (Activity) -> ReviewManager = { ReviewManagerFactory.create(it) }) {
    private var activity = WeakReference<Activity>(null)

    /** The activity the prompt may show over; null when it goes away. */
    fun attach(activity: Activity?) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (this.activity.get() === activity) this.activity = WeakReference(null)
    }

    private fun inFront(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed && (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false

    suspend fun request(): Boolean {
        val shown = activity.get()?.takeIf(::inFront) ?: return false
        return suspendCancellableCoroutine { done ->
            val manager = try {
                managers(shown)
            } catch (e: Exception) {
                done.resume(false)
                return@suspendCancellableCoroutine
            }
            manager.requestReviewFlow().addOnCompleteListener { info ->
                // the app may have left the foreground (or locked) while Play prepared the flow: nothing shows then
                if (!info.isSuccessful || !inFront(shown) || !done.isActive) {
                    if (done.isActive) done.resume(false)
                    return@addOnCompleteListener
                }
                try {
                    manager.launchReviewFlow(shown, info.result).addOnCompleteListener { flow -> if (done.isActive) done.resume(flow.isSuccessful) }
                } catch (e: Exception) {
                    if (done.isActive) done.resume(false)
                }
            }
        }
    }
}
