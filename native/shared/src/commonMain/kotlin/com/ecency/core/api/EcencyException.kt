package com.ecency.core.api

import kotlin.coroutines.cancellation.CancellationException

/**
 * Base of every exception the public data-layer API throws. Every public entry point is
 * annotated `@Throws(EcencyException::class, CancellationException::class)`, so from Swift a
 * failure arrives as a thrown Error instead of terminating the app.
 */
open class EcencyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An argument the caller passed was rejected (blank body, vote weight out of range, bad key...). */
class InvalidInputException(message: String, cause: Throwable? = null) : EcencyException(message, cause)

/** A failure the data layer did not anticipate; [cause] holds the original. */
class UnexpectedException(cause: Throwable) : EcencyException("unexpected error: ${cause.message ?: cause::class.simpleName}", cause)

/**
 * Runs a public entry point so that only [EcencyException] or cancellation can escape:
 * IllegalArgumentException becomes [InvalidInputException], anything else [UnexpectedException].
 */
internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: EcencyException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    throw InvalidInputException(e.message ?: "invalid input", e)
} catch (e: Exception) {
    throw UnexpectedException(e)
}
