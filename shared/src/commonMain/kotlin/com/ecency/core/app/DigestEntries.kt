package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `newsletter.*` used by the digest entry points (the sheet's own copy is [DigestCopy]). */
object DigestEntryCopy {
    val MANAGE: String get() = L.newsletter.manage // newsletter.manage
    val COMMUNITY_BUTTON: String get() = L.newsletter.communityButton // newsletter.community_button
    val PROFILE_OPTION: String get() = L.newsletter.profileOption // newsletter.profile_option
    val COPIED: String get() = L.alert.copied // alert.copied
    val CLOSE: String get() = L.transfer.close

    /** newsletter.subscriber_count */
    fun subscriberCount(weekly: Int, monthly: Int) = L.newsletter.subscriberCount(weekly = weekly.toString(), monthly = monthly.toString())

    /** RN's subscribe link of a creator's list. */
    fun subscribeLink(username: String) = "https://ecency.com/@$username?subscribe=digest"
}

/** The digest an end-of-post card offers (RN postDigestTarget). */
data class DigestTarget(val type: String, val target: String) {
    /** RN newsletter.body_creator / body_community with the list's label. */
    val body: String get() = DigestCopy.body(type, target)

    companion object {
        /** Anchored on purpose (RN): only a canonical community id names a digest list. */
        private val COMMUNITY = Regex("^hive-[1-3]\\d{4,6}$")

        /**
         * RN pickPostDigestTarget: a reader is offered the author's creator digest; the author on their own community
         * post the community's; the author's own other posts, comments and signed-out readers nothing.
         */
        fun of(post: Post?, viewer: String?): DigestTarget? {
            if (post == null || post.author.isEmpty() || viewer.isNullOrEmpty()) return null
            if (!post.parentAuthor.isNullOrEmpty() || post.depth > 0) return null
            val community = post.category.takeIf { COMMUNITY.matches(it) }
            if (viewer == post.author) return community?.let { DigestTarget("community", it) }
            return DigestTarget("creator", post.author)
        }

        /** Per viewer AND list (RN postPromptStorageKey), so dismissing one author's card never hides another's. */
        fun dismissKey(viewer: String, target: DigestTarget) = "${PROMPT_PREFIX}$viewer.${target.type}.${target.target}"

        const val PROMPT_PREFIX = "digest.postPrompt."
    }
}

data class DigestPromptState(val target: DigestTarget? = null, val visible: Boolean = false)

/**
 * RN NewsletterPostPrompt: the end-of-post subscribe card. Shown only once both answers are in: the dismissal flag
 * (per viewer and list) and a successful subscriptions lookup without a row for this list; a failed lookup shows
 * nothing (the sheet could only say the service is unavailable). Subscribe opens the shared digest sheet.
 */
class DigestPromptModel internal constructor(private val env: AppEnvironment) :
    StateModel<DigestPromptState>(DigestPromptState(), env.dispatchers) {
    private var job: Job? = null
    private var key: Pair<String?, DigestTarget?>? = null

    fun start(post: Post, viewer: String?) {
        val target = DigestTarget.of(post, viewer)
        if (key == viewer to target) return
        key = viewer to target
        job?.cancel()
        setState { DigestPromptState(target = target) }
        if (target == null || viewer == null) return
        if (env.prefs.getString(DigestTarget.dismissKey(viewer, target)) != null) return
        // the session's cached lookup; asked again whenever a subscription changed (RN invalidates its query)
        job = scope.launch {
            env.digest.revision.collect {
                // another account signed in since (the switch itself bumps the revision): nothing is asked for this one
                if (env.session.current.username != viewer || key?.first != viewer) {
                    setState { it.copy(visible = false) }
                    return@collect
                }
                val rows = try {
                    env.digest.subscriptionsOf(viewer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setState { it.copy(visible = false) }
                    return@collect
                }
                if (env.session.current.username != viewer || key?.first != viewer) return@collect
                if (env.prefs.getString(DigestTarget.dismissKey(viewer, target)) != null) return@collect
                val subscribed = com.ecency.core.api.ecency.NewsletterApi.find(rows, target.type, target.target) != null
                setState { it.copy(visible = !subscribed) }
            }
        }
    }

    fun subscribe() {
        val t = current.target ?: return
        env.digest.show(t.type, t.target)
    }

    /** The X: hidden for this viewer and list from now on. */
    fun dismiss() {
        val t = current.target ?: return
        val viewer = key?.first ?: return
        env.prefs.putString(DigestTarget.dismissKey(viewer, t), env.nowMillis().toString())
        job?.cancel()
        setState { it.copy(visible = false) }
    }
}

data class DigestSenderState(val weekly: Int = 0, val monthly: Int = 0, val loaded: Boolean = false) {
    val text: String get() = DigestEntryCopy.subscriberCount(weekly, monthly)
}

/**
 * RN NewsletterSenderInfo on the own profile: the creator list's mailable subscribers per cadence, the copyable
 * subscribe link and "Manage". Silent (nothing drawn) while the lookup is unresolved or refused.
 */
class DigestSenderModel internal constructor(private val env: AppEnvironment, private val username: String) :
    StateModel<DigestSenderState>(DigestSenderState(), env.dispatchers) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (env.session.current.username != username) return
        scope.launch {
            val counts = try {
                env.digest.api.senderSubscribers(username, "creator", username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            if (env.session.current.username == username) setState { DigestSenderState(counts.first, counts.second, loaded = true) }
        }
    }

    val link: String get() = DigestEntryCopy.subscribeLink(username)
}
