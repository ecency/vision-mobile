package com.ecency.core.app

import com.ecency.core.api.ecency.Announcement
import com.ecency.core.api.json.SafeJson
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

// ---------------------------------------------------------------------------------------------------------
// Delete account (RN settingsContainer `_handleDeleteAccount`)
// ---------------------------------------------------------------------------------------------------------

/** RN en-US `delete.*`, `settings.delete_account`, `alert.*`. */
object DeleteAccountCopy {
    val SETTINGS_ROW: String get() = L.settings.deleteAccount
    val CONFIRM_TITLE: String get() = L.delete.confirmDeleteTitle
    val CONFIRM_BODY: String get() = L.delete.confirmDeleteBody
    val CANCEL: String get() = L.alert.cancel
    val DELETE: String get() = L.alert.delete
    val REQUEST_SENT: String get() = L.delete.requestSent
    val FAIL: String get() = L.alert.fail

    /** The request went out but the sign-out afterwards failed (storage). */
    val SIGN_OUT_FAILED: String get() = L.native.deleteAccount.signOutFailed

    /** Not RN, which says "sent" and signs out even when the request failed. */
    val NOT_SENT: String get() = L.native.deleteAccount.notSent
}

data class DeleteAccountState(
    /** The account RN's "Confirm Delete" sheet is open for; null: closed. */
    val asking: String? = null,
    val isSending: Boolean = false,
    /** The request did not go through: shown under "Fail!", the account stays signed in. */
    val error: String? = null,
)

/**
 * RN's "Delete Account" (Settings, signed in): one confirmation ("Confirm Delete", Cancel / Delete), then
 * `POST /private-api/request-delete {username, data: "", code}`, the toast "Your request for deletion is sent"
 * and a sign-out of that account. The request asks Ecency to delete what it stores for the account; nothing is
 * broadcast and the Hive account itself cannot be deleted.
 *
 * The request names the account that opened the confirmation and is sent with that account's code; it is
 * refused when another account is signed in by then, sent once (never retried), and the sign-out afterwards is
 * of that account by name, through [SessionModel.logout] and so through the one teardown
 * ([AppEnvironment.accountsLeft]). The confirmation closes on any account change.
 */
class DeleteAccountModel internal constructor(private val env: AppEnvironment) :
    StateModel<DeleteAccountState>(DeleteAccountState(), env.dispatchers) {

    init {
        scope.launch {
            env.events.collect { event ->
                val closes = event is AppEvent.DataWiped || (event is AppEvent.SessionChanged && event.username != current.asking)
                // a request already on its way finishes; only the question closes
                if (closes && !current.isSending) setState { DeleteAccountState() }
            }
        }
    }

    /** The settings row: opens the confirmation for the signed-in account. */
    fun ask() {
        val user = env.session.current.username ?: return
        if (current.isSending || env.appLock.current.hidesContent) return
        setState { DeleteAccountState(asking = user) }
    }

    fun cancel() {
        if (current.isSending) return
        setState { DeleteAccountState() }
    }

    fun clearError() = setState { it.copy(error = null) }

    /** "Delete" on the confirmation. */
    fun confirm() {
        val user = current.asking ?: return
        if (current.isSending) return
        if (env.session.current.username != user || env.appLock.current.hidesContent) {
            setState { DeleteAccountState() }
            return
        }
        setState { it.copy(isSending = true, error = null) }
        scope.launch {
            val sent = try {
                env.onboardingApi.requestDelete(user)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!sent) {
                setState { DeleteAccountState(error = DeleteAccountCopy.NOT_SENT) }
                return@launch
            }
            // RN dispatch(logout()): that account leaves the device, whoever is current by now
            val signedOut = try {
                env.session.logout(user)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            setState { DeleteAccountState() }
            // after the sign-out, which clears the messages of the account that left
            env.ui.toast(DeleteAccountCopy.REQUEST_SENT)
            if (!signedOut) setState { it.copy(error = DeleteAccountCopy.SIGN_OUT_FAILED) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Update prompt (RN applicationContainer `_compareAndPromptForUpdate`)
// ---------------------------------------------------------------------------------------------------------

/** RN en-US `alert.update_available_*`, `alert.remind_later`, `alert.update`. */
data class UpdatePrompt(val version: String) {
    val title: String get() = L.alert.updateAvailableTitle(version = version)
    val body: String get() = BODY
    val later: String get() = LATER
    val update: String get() = UPDATE

    companion object {
        val BODY: String get() = L.alert.updateAvailableBody
        val LATER: String get() = L.alert.remindLater
        val UPDATE: String get() = L.alert.update
    }
}

/** Where "Update Now" goes (RN picks by `DeviceInfo.getInstallerPackageName`). */
object UpdateLinks {
    /** The app's ids, the RN app's (README "In-place upgrade from RN 3.x"). */
    const val ANDROID_APP_ID = "app.esteem.mobile.android"
    const val IOS_BUNDLE_ID = "app.esteem.mobile.ios"

    /** RN's default for any other installer (sideloaded, TestFlight, debug). */
    const val RELEASES_PAGE = "https://github.com/ecency/vision-mobile/releases"
    const val PLAY_INSTALLER = "com.android.vending"
    const val APP_STORE_INSTALLER = "AppStore"

    /** Android: the Play listing for a Play install, else the releases page. */
    fun android(installer: String?): String = if (installer == PLAY_INSTALLER) "market://details?id=$ANDROID_APP_ID" else RELEASES_PAGE

    /** The Play listing on the web, when no app takes `market://`. */
    const val PLAY_WEB = "https://play.google.com/store/apps/details?id=$ANDROID_APP_ID"

    /** Apple's lookup of the store page by bundle id (the numeric store id is not known to the build). */
    const val APP_STORE_LOOKUP = "https://itunes.apple.com/lookup?bundleId=$IOS_BUNDLE_ID"

    /** A store page URL from the lookup counts only on Apple's store host over https. */
    fun isAppStorePage(url: String?): Boolean = url != null && url.length <= 512 && url.startsWith("https://apps.apple.com/") && SafeTarget.httpUrl(url) != null
}

data class UpdateState(val prompt: UpdatePrompt? = null)

/**
 * Where the update check looks, a build setting ([AppEnvironment.updateSource]): the "latest release" endpoint
 * of a GitHub repository and the prefix this app's release tags carry before `X.Y.Z`. The default is RN's
 * repository with plain tags. Before the native app ships, either its releases get their own repository or
 * their own tag prefix, so an RN release never prompts native users (and the other way round).
 */
data class UpdateSource(val releasesUrl: String = com.ecency.core.api.ecency.OnboardingApi.RELEASES_URL, val tagPrefix: String = "") {
    companion object {
        /** From build settings; blank values mean the default. A URL that is not https on api.github.com is ignored. */
        fun of(releasesUrl: String?, tagPrefix: String?): UpdateSource {
            val url = releasesUrl?.trim().orEmpty()
            val okUrl = url.takeIf { it.startsWith("https://api.github.com/repos/") && SafeTarget.httpUrl(it) != null }
            val prefix = tagPrefix?.trim().orEmpty().takeIf { p -> p.length <= 32 && p.all { it.isLetterOrDigit() || it in "-_./" } }.orEmpty()
            return UpdateSource(okUrl ?: com.ecency.core.api.ecency.OnboardingApi.RELEASES_URL, prefix)
        }
    }
}

/**
 * RN's update check, same source and rule: at app start, unless "Remind Later" was tapped within the last 48
 * hours, read the latest release tag of the app's GitHub repository; when its version is higher than the
 * running one ([AppVersions.parse]), ask. "Remind Later" stores the time; "Update Now" opens the store (the UI
 * picks the link with [UpdateLinks]) and stores nothing, so the next start asks again until the app is updated.
 * Any failure is silent, as RN.
 */
class UpdateModel internal constructor(private val env: AppEnvironment) : StateModel<UpdateState>(UpdateState(), env.dispatchers) {
    private var job: Job? = null

    /**
     * The version a release tag names, or null when the tag is not this app's: [UpdateSource.tagPrefix] followed
     * by plain `X.Y.Z` and nothing else. `v4.1.0`, `4.1`, `4.1.0-beta` or another app's tags never prompt.
     */
    internal fun versionOf(tag: String): String? {
        val prefix = env.updateSource.tagPrefix
        if (!tag.startsWith(prefix)) return null
        return tag.removePrefix(prefix).takeIf { PLAIN_VERSION.matches(it) }
    }

    fun check() {
        if (job?.isActive == true || current.prompt != null) return
        val last = env.prefs.getString(LAST_CHECK)?.toLongOrNull()
        if (last != null && env.nowMillis() - last < RECHECK_MILLIS) return
        job = scope.launch {
            try {
                val remote = versionOf(env.onboardingApi.latestReleaseTag(env.updateSource.releasesUrl)) ?: return@launch
                if (AppVersions.parse(remote) > AppVersions.parse(env.appVersion)) setState { UpdateState(UpdatePrompt(remote)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN reports to Sentry and shows nothing
            }
        }
    }

    fun later() {
        if (current.prompt == null) return
        env.prefs.putString(LAST_CHECK, env.nowMillis().toString())
        setState { UpdateState() }
    }

    /** "Update Now", or the sheet closed without a choice: nothing is remembered. */
    fun dismiss() = setState { UpdateState() }

    companion object {
        const val LAST_CHECK = "update.lastCheck"

        /** RN `recheckInterval`: 2 days. */
        const val RECHECK_MILLIS = 48L * 3600 * 1000

        private val PLAIN_VERSION = Regex("^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}$")
    }
}

// ---------------------------------------------------------------------------------------------------------
// Announcements (RN providers/queries/announcementsQueries.ts, utils/announcementAction.ts)
// ---------------------------------------------------------------------------------------------------------

/** What the primary button opens. Built from untrusted text: only an in-app screen or an https page. */
sealed interface AnnouncementTarget {
    /** An ecency.com post, profile, tag or community, opened in the app. */
    data class InApp(val entry: NavEntry) : AnnouncementTarget

    /** Any other https page, for the in-app browser. */
    data class Web(val url: String) : AnnouncementTarget
}

data class AnnouncementPrompt(
    val id: String,
    /** The account it was chosen for; null: a guest. */
    val account: String?,
    val title: String,
    val body: String,
    /** RN `alert.later`. */
    val later: String,
    val action: String,
    val target: AnnouncementTarget?,
)

data class AnnouncementsState(val prompt: AnnouncementPrompt? = null)

/**
 * RN useAnnouncementsQuery: the first announcement that is not a proposal prompt, shown 3 s after it was chosen
 * unless this is the first launch of a new version (the welcome screen's turn), it needs a signed-in user and
 * there is none, it was acted on before, or it was last seen under 48 hours ago. The memory is per announcement
 * and account (`<id>_<account or guest>`: `processed`, `lastSeen`), kept in the device preferences.
 *
 * The payload is untrusted. Texts are cleaned and capped ([com.ecency.core.api.ecency.OnboardingApi]); the
 * server's `ops` signing blob is never read (as RN); the button's link is either an absolute `https://` URL (any
 * host, as RN) or a path starting with one `/`, which gets `https://ecency.com` (RN prefixes any text, which lets
 * `.evil.example/x` name another host: such a link is dropped here); it must pass [SafeTarget.httpUrl], and then
 * only opens an in-app content screen
 * ([ContentLinks]) or a web page. It never reaches the auth link handler, so an announcement cannot start a
 * sign-in, a signature or a signup.
 *
 * A prompt belongs to the account it was chosen for: an account change closes it without marking it and the
 * choice runs again for the new account.
 */
class AnnouncementsModel internal constructor(private val env: AppEnvironment) :
    StateModel<AnnouncementsState>(AnnouncementsState(), env.dispatchers) {

    private var job: Job? = null
    private var cached: Pair<Long, List<Announcement>>? = null
    private var started = false

    /** Once per app: checks now and again on every account change. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged || event is AppEvent.DataWiped) {
                    job?.cancel()
                    setState { AnnouncementsState() }
                    check()
                }
            }
        }
        check()
    }

    /** Runs RN's effect again (after the welcome screen was accepted, the "new version launch" test changes). */
    fun check() {
        if (job?.isActive == true || current.prompt != null) return
        job = scope.launch {
            try {
                if (env.welcome.isNewVersionLaunch()) return@launch
                env.session.awaitLoaded()
                val account = env.session.current.username
                val first = announcements().firstOrNull { !it.isProposal } ?: return@launch
                if (first.auth && account == null) return@launch
                val metaId = metaId(first.id, account)
                val meta = readMeta()[metaId]
                val now = env.nowMillis()
                if (meta != null && (meta.processed || meta.lastSeen + PROMPT_AGAIN_MILLIS > now)) return@launch
                delay(SHOW_DELAY_MILLIS)
                // still the account it was chosen for, and nothing else took the slot
                if (env.session.current.username != account || current.prompt != null) return@launch
                setState {
                    AnnouncementsState(
                        AnnouncementPrompt(
                            id = first.id, account = account, title = first.title, body = first.description, later = LATER,
                            action = first.buttonText.ifEmpty { "OK" }, target = target(first.buttonLink),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no announcement this time
            }
        }
    }

    /** "Later", or the dialog closed any other way (RN `_markAsSeen`): asked again after 48 hours. */
    fun later() {
        val prompt = current.prompt ?: return
        mark(prompt, processed = false)
        setState { AnnouncementsState() }
    }

    /**
     * The primary button (RN `_onActionPress`): marked as processed, never shown again for this account. Returns
     * where to go, or null when there is nowhere or the prompt is no longer this account's.
     */
    fun act(): AnnouncementTarget? {
        val prompt = current.prompt ?: return null
        setState { AnnouncementsState() }
        if (prompt.account != env.session.current.username) return null
        mark(prompt, processed = true)
        return prompt.target
    }

    private suspend fun announcements(): List<Announcement> {
        cached?.takeIf { env.nowMillis() - it.first in 0 until STALE_MILLIS }?.let { return it.second }
        return env.onboardingApi.announcements().also { cached = env.nowMillis() to it }
    }

    private class Meta(val processed: Boolean, val lastSeen: Long)

    private fun readMeta(): Map<String, Meta> {
        val o = env.prefs.getString(META)?.let { SafeJson.parseOrNull(it, 4) } as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (key, value) ->
            val v = value as? JsonObject ?: return@mapNotNull null
            key to Meta((v["processed"] as? JsonPrimitive)?.booleanOrNull ?: false, (v["lastSeen"] as? JsonPrimitive)?.longOrNull ?: 0)
        }.toMap()
    }

    /** RN cacheReducer UPDATE_ANNOUNCEMENTS_META: processed stays true once set, lastSeen is now. */
    private fun mark(prompt: AnnouncementPrompt, processed: Boolean) {
        val id = metaId(prompt.id, prompt.account)
        val all = readMeta().toMutableMap()
        all[id] = Meta(processed || all[id]?.processed == true, env.nowMillis())
        // the newest entries only: old announcements do not pile up on the device
        val kept = all.entries.sortedByDescending { it.value.lastSeen }.take(MAX_META)
        env.prefs.putString(
            META,
            buildJsonObject { kept.forEach { (k, m) -> put(k, buildJsonObject { put("processed", m.processed); put("lastSeen", m.lastSeen) }) } }.toString(),
        )
    }

    companion object {
        const val META = "announcements.meta"
        val LATER: String get() = L.alert.later

        /** RN `PROMPT_AGAIN_INTERVAL`. */
        const val PROMPT_AGAIN_MILLIS = 48L * 3600 * 1000

        /** RN `await delay(3000)` before the sheet. */
        const val SHOW_DELAY_MILLIS = 3000L

        /** SDK `staleTime: 36e5`. */
        const val STALE_MILLIS = 3_600_000L
        const val MAX_META = 50

        fun metaId(id: String, account: String?): String = "${id}_${account ?: "guest"}"

        /**
         * RN resolveAnnouncementAction with the native checks: a link that starts with `https://` as it is, a path
         * with one leading slash on ecency.com, nothing else; then it must be a plain https URL, and it opens in
         * the app when it is ecency.com content, else as a web page. Null: the button only closes the prompt.
         */
        fun target(buttonLink: String): AnnouncementTarget? {
            if (buttonLink.isEmpty()) return null
            val url = when {
                buttonLink.startsWith("https://") -> buttonLink
                // a path on ecency.com: exactly one leading slash and nothing a browser could read as a host
                // ("//host", "/\\host"); anything else ("evil.example/x", ".evil.example/x") is no link at all,
                // since gluing it to "https://ecency.com" would name another host
                buttonLink.startsWith("/") && buttonLink.length > 1 && buttonLink[1] != '/' && buttonLink[1] != '\\' -> "https://ecency.com$buttonLink"
                else -> return null
            }
            val safe = SafeTarget.httpUrl(url)?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
            ContentLinks.entry(safe)?.let { return AnnouncementTarget.InApp(it) }
            return AnnouncementTarget.Web(safe)
        }
    }
}
