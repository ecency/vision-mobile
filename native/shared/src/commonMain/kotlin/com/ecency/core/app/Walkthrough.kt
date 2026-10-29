package com.ecency.core.app

import com.ecency.core.l10n.L

object WalkthroughCopy {
    /** What screen readers hear for the red dot (RN draws it without a label). */
    val MARKER: String get() = L.home.new
}

/** RN redux/constants/walkthroughConstants.ts: the walkthrough steps, by RN's index (kept for the RN import). */
enum class WalkthroughStep(val index: Int) {
    /** The editor's drafts button (RN keeps its tooltip unused). */
    EDITOR_DRAFT_BTN(1),

    /** The "+" (customise filters) button of the home feed's filter bar. */
    CUSTOM_TABS_BTN(2),
}

data class WalkthroughState(val shown: Set<WalkthroughStep> = emptySet())

/**
 * RN WalkthroughMarker: a red dot on a control until the user first used it. The steps already used are device-wide
 * (RN's persisted walkthrough reducer, imported from the RN app), kept in the preferences as RN's indexes. One per app.
 */
class WalkthroughModel internal constructor(private val prefs: () -> Prefs, dispatchers: AppDispatchers) :
    StateModel<WalkthroughState>(WalkthroughState(), dispatchers) {
    init {
        setState { WalkthroughState(read()) }
    }

    /** The dot shows on [step]'s control: not used yet, and not [hidden] (RN `hidden`, e.g. signed out). */
    fun showsMarker(step: WalkthroughStep, hidden: Boolean = false): Boolean = !hidden && step !in current.shown

    /** RN `onIntercept`: the control was used, the dot goes for good (unless the dot was [hidden] then). */
    fun intercept(step: WalkthroughStep, hidden: Boolean = false) {
        if (hidden || step in current.shown) return
        val next = current.shown + step
        write(next)
        setState { WalkthroughState(next) }
    }

    /** What the RN app had already shown (its import), added to what this app knows. */
    internal fun importShown(indexes: Set<Int>) {
        val next = current.shown + WalkthroughStep.entries.filter { it.index in indexes }
        if (next == current.shown) return
        write(next)
        setState { WalkthroughState(next) }
    }


    private fun read(): Set<WalkthroughStep> {
        val raw = prefs().getString(KEY).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return WalkthroughStep.entries.filter { it.index in raw }.toSet()
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    private fun write(steps: Set<WalkthroughStep>) = prefs().putString(KEY, steps.map { it.index }.sorted().joinToString(","))

    companion object {
        const val KEY = "walkthrough.shown"
    }
}
