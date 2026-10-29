package com.ecency.core.render.blocks

import com.ecency.core.render.LinearScaling
import org.junit.Test

/**
 * JVM only, so a regression that stops terminating fails on the timeout instead of hanging the run. The timeout is
 * only that hang guard: LinearScaling bills CPU time, so a loaded machine makes the run slower in wall time but no
 * less linear, and 120 s was short enough for load alone to trip it.
 */
class SpeakAudioPerfTest {
    @Test(timeout = 900_000)
    fun stripStaysFastOnPathologicalBodies() {
        // Shapes that make a naive scan of the two expressions quadratic or worse (V8 runs each in milliseconds).
        // Checked by growth from n to 4n (see LinearScaling), not by an absolute bound that fails under load.
        val shapes: List<Pair<String, (Int) -> String>> = listOf(
            "open hrefs" to { n -> "<a href=\"".repeat(n) },
            "unclosed webm" to { n -> "<a href=\"https://cdn.liketu.com/x.webm\"".repeat(n) },
            "open webm" to { n -> "<a href=\"https://cdn.liketu.com/x.webm\">".repeat(n) },
            "closed webm" to { n -> "<a href=\"https://cdn.liketu.com/x.webm\"></a>".repeat(n) },
            "audio" to { n -> "<audio".repeat(n) + "</audio>" },
            "bare hosts" to { n -> "<a cdn.liketu.com".repeat(n) },
        )
        for ((name, build) in shapes) {
            LinearScaling.assertLinear(name, 1_500, build) { SpeakAudio.stripBodyAudio(it) }
        }
    }
}
