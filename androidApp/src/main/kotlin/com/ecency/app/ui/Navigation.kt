package com.ecency.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.ecency.core.app.PageMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.NavEntry
import com.ecency.core.app.NavState
import com.ecency.core.app.StateModel

/**
 * The models and list positions of one back stack entry (or of the tabs, [screen] null). They live here
 * rather than in composition, so a screen under another keeps its data and scroll position; they are closed
 * when the entry is popped.
 */
class StackEntry internal constructor(val id: Long, val screen: AppScreen?) {
    private val bag = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, create: () -> T): T = bag.getOrPut(key, create) as T

    /** What is held under [key], if it was created (tests). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(key: String): T? = bag[key] as T?

    internal fun close() {
        bag.values.forEach { (it as? StateModel<*>)?.close() ?: (it as? AutoCloseable)?.close() }
        bag.clear()
    }
}

/**
 * Compose side of the shared [NavState]: holds the state as snapshot state, one [StackEntry] per pushed
 * screen (plus [root] for the tabs), and reports every change to [onChange], which [NavigationViewModel]
 * writes to the saved state.
 */
@Stable
class Navigator(
    initial: NavState = NavState(),
    private val onChange: (NavState) -> Unit = {},
    /** The account signed in when [initial] was saved (null: none, or not recorded). */
    savedOwner: String? = null,
    private val onOwner: (String?) -> Unit = {},
    /** [initial] came back from saved state after process death (not built by this run of the app). */
    private var restored: Boolean = false,
) {
    var state: NavState by mutableStateOf(initial)
        private set

    private var owner = savedOwner
    private var accountKnown = false

    /** What an account change adds to the navigation it leaves (the reply editor's account tile, ReplyAccountHandoff). */
    var reopenAfterChange: (NavState, String?) -> NavState = { s, _ -> s }

    /**
     * The accounts were read, or the account changed: [current] is signed in now (null: nobody). A restored
     * navigation keeps its gated screens only for the account that had them open; any later change (sign-out,
     * switch, a logout that moved to the next account, a link login as someone else) drops them.
     */
    fun accountIs(current: String?) {
        if (!accountKnown) {
            accountKnown = true
            if (restored) update(state.restoredFor(owner, current))
        } else if (current != owner) {
            update(reopenAfterChange(state.afterAccountChange(), current))
        }
        restored = false
        owner = current
        onOwner(current)
    }

    /** The tabs' models (home feeds and their list positions). */
    val root = StackEntry(0, null)
    private val entries = mutableMapOf<Long, StackEntry>()

    /** The entry on top: the last pushed screen, or [root]. */
    val top: StackEntry get() = state.stack.lastOrNull()?.let { entry(it.id, it.screen) } ?: root

    val screens: List<AppScreen> get() = state.screens

    private fun entry(id: Long, screen: AppScreen) = entries.getOrPut(id) { StackEntry(id, screen) }

    private fun update(next: NavState) {
        if (next == state) return
        // only the screen that was on top is drawn leaving; entries dropped below it (an account change taking
        // gated screens out under an unchanged top, a pop of several) are never shown again and close now
        // (known gap: such an entry may still be drawn as the start of a push transition that is running; rare, left)
        val leaving = state.stack.lastOrNull()?.id
        state = next
        val live = next.stack.map { it.id }.toSet()
        entries.keys.filter { it !in live }.forEach { id ->
            entries.remove(id)?.let { if (holdsPopped && id == leaving) popped += it else it.close() }
        }
        onChange(next)
    }

    /**
     * Set by the animated host while it is composed: the popped top entry keeps its models until the page transition
     * that shows it leaving has finished ([settle]), so the outgoing screen never draws from closed models.
     */
    internal var holdsPopped = false
    private val popped = mutableListOf<StackEntry>()

    /** The page transition finished: closes the models of the entries that left the stack. */
    internal fun settle() {
        if (popped.isEmpty()) return
        val gone = popped.toList()
        popped.clear()
        gone.forEach { it.close() }
    }

    /**
     * The entry a back would show by popping a screen (what a predictive back gesture previews); null when back
     * closes the side menu, returns to the Feed tab or leaves the app.
     */
    fun backTarget(): StackEntry? {
        if (state.drawerOpen || state.stack.isEmpty()) return null
        val below = state.stack.dropLast(1).lastOrNull() ?: return root
        return entry(below.id, below.screen)
    }

    fun push(screen: AppScreen) = update(state.push(screen))

    fun pushGated(screen: AppScreen, loggedIn: Boolean) = update(state.pushGated(screen, loggedIn))

    /** The system back action; false when nothing is left (the system then leaves the app). */
    fun back(): Boolean {
        val next = state.back() ?: return false
        update(next)
        return true
    }

    /** Pops one screen (a screen's own back arrow). */
    fun pop(): Boolean {
        if (state.stack.isEmpty()) return false
        update(state.popTo(state.stack.size - 1))
        return true
    }

    fun selectTab(tab: AppTab) = update(state.selectTab(tab))

    fun openDrawer() = update(state.openDrawer())

    fun closeDrawer() = update(state.closeDrawer())

    fun open(entry: NavEntry, loggedIn: Boolean) = update(state.open(entry, loggedIn))

    /** Another account (or none): same tab, gated screens leave the stack (their models close). */
    fun afterAccountChange() = update(state.afterAccountChange())

    /** Drops every pushed entry's models (after a wipe) and returns to the Feed tab. */
    fun reset() {
        root.close()
        update(state.reset())
    }

    /** Closes every entry's models; nothing is pushed afterwards. */
    internal fun closeAll() {
        settle()
        entries.values.forEach { it.close() }
        entries.clear()
        root.close()
        state = state.reset()
    }
}

/**
 * Keeps the [Navigator] (and with it every screen's models and list state) across configuration changes,
 * closes all models when the activity finishes for good, and saves the navigation so a process killed in the
 * background comes back on the same tab and screens (models are created again).
 */
class NavigationViewModel(handle: SavedStateHandle) : ViewModel() {
    val navigator = Navigator(
        initial = NavState.decode(handle.get<ArrayList<String>>(KEY).orEmpty()),
        onChange = { state -> handle[KEY] = ArrayList(state.encode()) },
        savedOwner = handle.get<String>(KEY_OWNER),
        onOwner = { handle[KEY_OWNER] = it },
        restored = handle.contains(KEY),
    )

    public override fun onCleared() = navigator.closeAll()

    companion object {
        const val KEY = "ecency.navigation"
        const val KEY_OWNER = "ecency.navigation.owner"
    }
}

/** Whether the screen composed here is the navigator's top entry (true outside an [AnimatedNavHost]). */
val LocalOnTop = compositionLocalOf { true }

/**
 * A screen's own back handler (a guard while a send runs, the editor's leave check, a page's own history): on only
 * while the screen is the top entry, so a screen still drawn in its 300 ms exit never takes the next back.
 */
@Composable
fun ScreenBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled && LocalOnTop.current, onBack = onBack)
}

/**
 * The stack's screens with Material motion: shared axis X on push and pop ([Motion.pageTransform]), a crossfade
 * under reduce motion, a plain swap when animations are off. Back that pops a screen is predictive: the gesture
 * seeks the pop transition (the screen below shows as the user drags), letting go finishes it, cancelling springs
 * back. Other backs (closing the side menu, returning to the Feed tab) stay with the app's plain back handler.
 *
 * Input is never held: the new screen takes taps as soon as it is composed, and a push during a running transition
 * retargets it.
 */
@Composable
fun AnimatedNavHost(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable (StackEntry) -> Unit,
) {
    val motion = LocalMotion.current
    val density = LocalDensity.current
    val top = navigator.top
    val state = remember(navigator) { SeekableTransitionState(top) }
    val scope = rememberCoroutineScope()
    DisposableEffect(navigator) {
        navigator.holdsPopped = true
        onDispose {
            navigator.holdsPopped = false
            navigator.settle()
        }
    }
    LaunchedEffect(top, motion) {
        if (state.currentState === top && state.targetState === top) return@LaunchedEffect
        if (motion.page == PageMotion.NONE) state.snapTo(top) else state.animateTo(top)
    }
    val transition = rememberTransition(state, label = "nav")
    // the outgoing screen is gone: its models close now
    LaunchedEffect(transition.currentState, transition.isRunning) {
        if (!transition.isRunning && transition.currentState === transition.targetState) navigator.settle()
    }
    PredictiveBackHandler(enabled = backEnabled && navigator.backTarget() != null) { progress ->
        val target = navigator.backTarget()
        try {
            if (target != null && motion.page != PageMotion.NONE) {
                progress.collect { event -> state.seekTo(event.progress.coerceIn(0f, 1f), target) }
            } else {
                progress.collect {}
            }
            navigator.back()
        } catch (e: CancellationException) {
            // the gesture was cancelled: back to the screen on top
            scope.launch { state.animateTo(navigator.top) }
            throw e
        }
    }
    transition.AnimatedContent(
        modifier = modifier,
        contentKey = { it.id },
        transitionSpec = { motion.pageTransform(forward = targetState.id > initialState.id, density) },
    ) { entry ->
        // a screen drawn leaving (or entering under a retargeted push) is not on top: its own back handlers are off
        CompositionLocalProvider(LocalOnTop provides (entry === navigator.top)) { content(entry) }
    }
}
