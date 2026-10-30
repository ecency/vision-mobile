package com.ecency.core.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the app layer runs its work. [main] owns every state change (the UI thread on both
 * platforms); [render] parses and lays out post bodies; [io] is for blocking platform calls.
 * Tests pass one test dispatcher for all three.
 */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val render: CoroutineDispatcher,
    val io: CoroutineDispatcher,
) {
    companion object {
        /** Main thread, Default for rendering, IO for the vault. */
        fun platform(): AppDispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO)
    }
}

/** A running subscription; [close] stops it. Safe to call more than once. */
class Closeable internal constructor(private val job: Job) {
    fun close() = job.cancel()

    val isClosed: Boolean get() = !job.isActive
}

/**
 * Base of every view model: an immutable [S] in a [StateFlow], changed only on [AppDispatchers.main].
 *
 * Compose collects [state] (`collectAsState()`); Swift calls [watch], which delivers the current
 * state at once and then every change on the main thread, and keeps the returned [Closeable] for
 * as long as the view lives. [close] cancels everything the model started.
 */
abstract class StateModel<S : Any>(initial: S, protected val dispatchers: AppDispatchers) {
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.main)

    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<S> = mutableState.asStateFlow()

    /** The state right now. */
    val current: S get() = mutableState.value

    fun watch(onEach: (S) -> Unit): Closeable = Closeable(scope.launch { state.collect { onEach(it) } })

    protected fun setState(transform: (S) -> S) = mutableState.update(transform)

    /** Stops all work. The model must not be used afterwards. */
    open fun close() = scope.cancel()
}
