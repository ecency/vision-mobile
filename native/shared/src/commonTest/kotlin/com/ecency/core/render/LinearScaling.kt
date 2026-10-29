package com.ecency.core.render

import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Growth check for "this stays linear" tests that must hold on a loaded machine.
 *
 * Absolute bounds ("200 KB in under 1 s") fail whenever parallel Gradle and Xcode builds steal the
 * CPU, and a ratio of two single runs fails when load arrives between them. Three things keep this
 * check stable under load:
 * - Each run is billed the CPU time of the measuring thread ([threadCpuTime]), not wall time, so
 *   time the scheduler gives to other processes is not counted at all. Only cache and frequency
 *   effects of load remain.
 * - The input at size n and at size 4n run interleaved, after a warm-up, and the fastest run of
 *   each is kept: load only ever adds time, so the minimum is the closest to the real cost.
 * - The minima are kept across attempts: a failing attempt adds more rounds to the same minima
 *   instead of starting over, so every extra round can only bring both sizes closer to their real
 *   cost. A real quadratic cannot get under the cap this way, because its fastest large run is
 *   still about 16x its fastest small run.
 *
 * The growth is read as an exponent of the size parameter (4x the repeated unit, which is what the
 * work follows even where a fixed prefix or suffix keeps the character count from growing 4x):
 * linear work is 1, n log n about 1.1, quadratic 2. The default cap (1.6 on the JVM, about 9x for
 * 4x the input; 1.75 on Kotlin/Native, see its actual) sits between them with room for noise. The
 * absolute ceiling is only a hang guard and is checked on wall time.
 */
internal object LinearScaling {
    fun assertLinear(
        name: String,
        n: Int,
        build: (Int) -> String,
        maxExponent: Double = defaultExponentCap,
        rounds: Int = 3,
        attempts: Int = 5,
        // Below this the larger run is too short for its ratio to mean anything; a quadratic path at 4n is far above it.
        floor: Duration = 50.milliseconds,
        hangGuard: Duration = 600_000.milliseconds,
        work: (String) -> Unit,
    ) {
        val small = build(n)
        val large = build(4 * n)
        val sizeRatio = 4.0
        check(large.length > small.length) { "$name: the larger input must be larger" }
        val maxRatio = sizeRatio.pow(maxExponent)
        work(small) // warm-up: class loading, regex compilation, JIT
        val seen = ArrayList<String>()
        // minima carry over between attempts: more rounds only ever move them closer to the real cost
        var bestSmall = Duration.INFINITE
        var bestLarge = Duration.INFINITE
        repeat(attempts) {
            var round = 0
            // short runs are the noisiest and the cheapest: they get more rounds
            while (round < rounds || (round < 3 * rounds && bestLarge < CHEAP)) {
                bestSmall = minOf(bestSmall, timed(small, hangGuard, work))
                bestLarge = minOf(bestLarge, timed(large, hangGuard, work))
                round++
            }
            val ratio = bestLarge / maxOf(bestSmall, 1.microseconds)
            val exponent = ln(ratio) / ln(sizeRatio)
            println("$name: ${small.length} chars $bestSmall, ${large.length} chars $bestLarge, x${ratio.fmt()}, exponent ${exponent.fmt()}")
            if (bestLarge < floor || ratio <= maxRatio) return
            seen.add("$bestSmall -> $bestLarge (x${ratio.fmt()}, exponent ${exponent.fmt()})")
        }
        fail("$name scales super-linearly (size x${sizeRatio.fmt()}, exponent cap $maxExponent): ${seen.joinToString("; ")}")
    }

    private val CHEAP = 250.milliseconds

    /** CPU time the current thread spent in [work]; wall time is only checked against [hangGuard]. */
    private inline fun timed(input: String, hangGuard: Duration, work: (String) -> Unit): Duration {
        // garbage left by the previous run is not billed to this one
        collectGarbage()
        val mark = TimeSource.Monotonic.markNow()
        val cpuStart = threadCpuTime()
        work(input)
        val cpu = threadCpuTime() - cpuStart
        val wall = mark.elapsedNow()
        if (wall > hangGuard) fail("${input.length} chars took $wall")
        return cpu
    }

    private fun Double.fmt(): String = ((this * 100).toInt() / 100.0).toString()
}

/** A full collection where the platform allows one (JVM: a request), so timed runs start from a clean heap. */
internal expect fun collectGarbage()

/** CPU time consumed so far by the calling thread (not wall time: time other processes take is excluded). */
internal expect fun threadCpuTime(): Duration

/** Highest growth exponent [LinearScaling.assertLinear] accepts by default on this platform. */
internal expect val defaultExponentCap: Double
