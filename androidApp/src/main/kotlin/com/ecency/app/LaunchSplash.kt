package com.ecency.app

import android.view.View
import android.view.animation.PathInterpolator
import com.ecency.core.app.MotionPolicy
import com.ecency.core.app.MotionTokens

/**
 * The launch screen's way out (README "Launch screen"): RN's bootsplash `hide({ fade: true })`, a short fade into the
 * first screen. Animations off (duration scale 0) removes it at once.
 */
object LaunchSplash {
    /** The fade's length at the system's animator [durationScale]: the token, or 0 with animations off. */
    fun fadeMillis(durationScale: Float): Int =
        MotionPolicy.fadeMillis(MotionPolicy.level(reduceMotion = false, durationScale = durationScale), MotionTokens.SPLASH_FADE_MS)

    /** Fades [view] (the splash) out, then [remove]s it; [remove] runs exactly once. */
    fun fadeOut(view: View, durationScale: Float, remove: () -> Unit) {
        val ms = fadeMillis(durationScale)
        if (ms == 0) {
            remove()
            return
        }
        val e = MotionTokens.STANDARD
        view.animate().alpha(0f).setDuration(ms.toLong()).setInterpolator(PathInterpolator(e.x1, e.y1, e.x2, e.y2))
            .withEndAction(remove).start()
    }
}
