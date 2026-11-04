package com.ecency.core.app

import com.ecency.core.api.ecency.Spotlight
import com.ecency.core.l10n.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object SpotlightCopy {
    /** RN feature.spotlight.label */
    val LABEL: String get() = L.feature.spotlight.label

    /** RN feature.spotlight.dismiss */
    val DISMISS: String get() = L.feature.spotlight.dismiss
}

/** The card RN's FeatureSpotlightCard draws: texts in the app's language when the spotlight has them. */
data class SpotlightCard(val id: String, val title: String, val description: String, val buttonText: String)

data class SpotlightState(
    /** Null: nothing to show (none for this audience, all dismissed, not loaded, or the request failed). */
    val card: SpotlightCard? = null,
)

/** RN FeatureSpotlightCard's client-side rules, apart from the model so they can be read and tested on their own. */
object SpotlightRule {
    const val PLATFORM = "mobile"

    /**
     * Platform (mobile, or no list), audience (signed in unless `guestsOnly`, which only signed-out visitors see) and
     * per-account dismissal; then the highest weight, ties to the earliest start, ties again to the first in the
     * server's order (RN's reduce keeps the current best). `path` is web routing and ignored.
     */
    fun pick(all: List<Spotlight>, username: String?, dismissed: (String) -> Boolean): Spotlight? = all
        .asSequence()
        .filter { s -> s.platforms == null || PLATFORM in s.platforms }
        .filter { s -> if (s.guestsOnly) username == null else username != null }
        .filter { s -> !dismissed(metaId(s.id, username)) }
        .fold(null as Spotlight?) { best, s ->
            when {
                best == null -> s
                s.weight != best.weight -> if (s.weight > best.weight) s else best
                else -> if (s.startMillis < best.startMillis) s else best
            }
        }

    /** RN cache.spotlightMeta key: `<id>_<account or guest>`. */
    fun metaId(id: String, username: String?): String = "${id}_${username ?: "guest"}"
}

/**
 * RN FeatureSpotlightCard (top of the first home feed tab): one feature spotlight from `/private-api/spotlights`,
 * picked by [SpotlightRule] for the signed-in account (or a guest), until dismissed. Dismissal is remembered per
 * spotlight and account in the device preferences ([DISMISSED_PREFIX], cleared by a wipe with the other account
 * marks). The CTA counts as engagement and dismisses too (RN `_action`). Spotlights are cached for an hour (SDK
 * staleTime). One per app.
 */
class SpotlightModel internal constructor(private val env: AppEnvironment) : StateModel<SpotlightState>(SpotlightState(), env.dispatchers) {
    private var all: List<Spotlight>? = null
    private var fetchedAt = 0L
    private var job: Job? = null
    private var started = false
    private var shown: Spotlight? = null

    /** Once per app: loads now and picks again on every account change. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged || event is AppEvent.DataWiped) refresh()
            }
        }
        refresh()
    }

    /** Picks again; loads first when nothing is cached or the cache is over an hour old. */
    fun refresh() {
        if (job?.isActive == true) {
            // a session change during the fetch: picked again for the account signed in once it ends
            again = true
            return
        }
        again = false
        job = scope.launch {
            env.session.awaitLoaded()
            val cached = all?.takeIf { env.nowMillis() - fetchedAt in 0 until STALE_MILLIS }
            // the cache's age counts from the request: an answer that took long is not taken as fresher than it is
            val askedAt = env.nowMillis()
            val list = cached ?: try {
                env.onboardingApi.spotlights().also {
                    all = it
                    fetchedAt = askedAt
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN: a failed query shows no card
                all.orEmpty()
            }
            pick(list)
        }.also { j -> j.invokeOnCompletion { if (again) scope.launch { refresh() } } }
    }

    /** [refresh] was asked for while a fetch ran. */
    private var again = false

    private fun pick(list: List<Spotlight>) {
        val username = env.session.current.username
        val s = SpotlightRule.pick(list, username) { env.prefs.getString(DISMISSED_PREFIX + it) != null }
        shown = s
        setState { SpotlightState(s?.let { card(it) }) }
    }

    private fun card(s: Spotlight): SpotlightCard {
        val language = env.prefs.getString(SettingsModel.KEY_LANGUAGE) ?: SettingsOptions.DEFAULT_LANGUAGE
        // RN `spotlight.locales?.[intl.locale] ?? spotlight`
        val copy = s.locales[language]
        return SpotlightCard(s.id, copy?.title ?: s.title, copy?.description ?: s.description, copy?.buttonText ?: s.buttonText)
    }

    /** "Dismiss": never shown again to this account (or to guests); the next spotlight, if any, takes its place. */
    fun dismiss() {
        val s = shown ?: return
        env.prefs.putString(DISMISSED_PREFIX + SpotlightRule.metaId(s.id, env.session.current.username), "1")
        pick(all.orEmpty())
    }

    /**
     * The button: dismissed (RN counts it as engagement), then where its link leads, by the announcement's safe
     * rules ([AnnouncementsModel.target]: an https URL or an ecency.com path, opened in the app when it is content).
     */
    fun act(): AnnouncementTarget? {
        val s = shown ?: return null
        dismiss()
        return AnnouncementsModel.target(s.buttonLink)
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    companion object {
        /** `spotlight.dismissed.<id>_<account or guest>`: names an account, so a wipe removes it ([AccountPrefs]). */
        const val DISMISSED_PREFIX = "spotlight.dismissed."

        /** SDK getSpotlightsQueryOptions staleTime. */
        const val STALE_MILLIS = 3_600_000L
    }
}
