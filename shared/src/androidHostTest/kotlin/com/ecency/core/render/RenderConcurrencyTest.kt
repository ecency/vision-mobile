package com.ecency.core.render

import com.ecency.core.render.transform.RenderCases
import com.ecency.core.render.transform.TransformDeviations
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** One shared RenderHelper used from several threads must give the sequential (golden) output. */
class RenderConcurrencyTest {
    @Test
    fun sharedInstanceIsThreadSafe() {
        val helper = RenderHelper(RenderCases.PROXY_BASE)
        val cases = RenderCases.cases.filter {
            it.opts.authorReputation == null && !it.opts.embedVideosDirectly && it.name !in TransformDeviations.security
        }
        val pool = Executors.newFixedThreadPool(8)
        val futures = (0 until 4).flatMap { round ->
            cases.map { c -> pool.submit<Pair<String, String>> { c.html to helper.renderPostBody(c.markdown, c.forApp, c.parentDomain, null, RenderOptions(false, c.opts.inertAuthorAndTagChips, c.opts.externalProfileBase, c.opts.preserveImageDimensions)) } }
        }
        val mismatches = futures.count { val (e, a) = it.get(); e != a }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        assertEquals(0, mismatches, "outputs differ under concurrency")
    }
}
