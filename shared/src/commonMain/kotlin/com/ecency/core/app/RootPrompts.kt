package com.ecency.core.app

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The prompts that open by themselves over the app's root, in the order they get their turn: a notice about an
 * account's keys, the update prompt, an announcement, the email digest offer after a first post. The store
 * rating prompt is the system's own and is asked for last ([RootPromptsModel.mayAskForRating]).
 */
enum class RootPrompt { ACCOUNT_NOTICE, UPDATE, ANNOUNCEMENT, DIGEST }

data class RootPromptState(
    /** The one prompt the apps draw now; null: none (also while locked and before the terms are accepted). */
    val prompt: RootPrompt? = null,
)

/** The one ordering rule of the root prompts, apart from the model so it can be read and tested on its own. */
object RootPromptRule {
    /**
     * - [blocked] (the app is locked, the welcome screen's terms are not accepted yet, a PIN pad or the keys screen
     *   is up, see [RootPromptsModel]): nothing shows;
     * - the prompt on screen ([showing]) stays until it is answered: a later one never replaces it, whatever its rank;
     * - otherwise the first of [pending] in [RootPrompt]'s order.
     */
    fun pick(showing: RootPrompt?, blocked: Boolean, pending: Set<RootPrompt>): RootPrompt? = when {
        blocked -> null
        showing != null && showing in pending -> showing
        else -> RootPrompt.entries.firstOrNull { it in pending }
    }

    /** The system's rating prompt only goes over an app that is in front, unlocked, past its terms and shows no other prompt. */
    fun mayAskForRating(blocked: Boolean, foreground: Boolean, pending: Set<RootPrompt>): Boolean = !blocked && foreground && pending.isEmpty()
}

/**
 * Which root prompt shows. The models of the prompts keep deciding whether they have something to say; this one
 * decides whose turn it is ([RootPromptRule]), so two never show at once and none shows over the lock screen, a PIN pad,
 * the keys screen or the welcome screen (closed until the app's root reports it done, [AppEnvironment.setWelcomeDone]). Both apps draw
 * only [RootPromptState.prompt]. One per app.
 */
class RootPromptsModel internal constructor(private val env: AppEnvironment) : StateModel<RootPromptState>(RootPromptState(), env.dispatchers) {
    init {
        scope.launch {
            // two at a time on purpose: a 5-flow combine's lambda (Function6) crashes the Kotlin/Native 2.3.10 compiler
            // for every arm64 Apple target ("IrClassSymbolImpl is already bound"); the release archive cannot link
            env.appLock.state.combine(env.accountNotices.state) { _, _ -> }
                .combine(env.updates.state) { _, _ -> }
                .combine(env.announcements.state) { _, _ -> }
                .combine(env.digest.state) { _, _ -> }
                .combine(env.welcomeDone) { _, _ -> }
                .combine(env.storageReset.state) { _, _ -> }
                .combine(env.keysScreens) { _, _ -> }
                .collect { evaluate() }
        }
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    /**
     * No prompt opens by itself: while locked or before the lock state is read, before the welcome screen is done,
     * over any PIN pad (the confirmation before the keys or a link, the settings flows), over the keys screen
     * (private keys may be on it) and under the "sign in again" prompt of a reset secure storage.
     */
    private val blocked: Boolean
        get() = env.appLock.current.let { it.hidesContent || it.prompt != null } || !env.welcomeDone.value ||
            env.keysScreens.value > 0 || env.storageReset.current.needed

    private fun pending(): Set<RootPrompt> = buildSet {
        if (env.accountNotices.current.notice != null) add(RootPrompt.ACCOUNT_NOTICE)
        if (env.updates.current.prompt != null) add(RootPrompt.UPDATE)
        if (env.announcements.current.prompt != null) add(RootPrompt.ANNOUNCEMENT)
        if (env.digest.current.open) add(RootPrompt.DIGEST)
    }

    private fun evaluate() = setState { RootPromptState(RootPromptRule.pick(it.prompt, blocked, pending())) }

    /** Asked at the moment the rating prompt would open; false leaves the one request unspent for a later time. */
    fun mayAskForRating(): Boolean = RootPromptRule.mayAskForRating(blocked, env.isForeground, pending())
}
