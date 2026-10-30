package com.ecency.core.app

import com.ecency.core.api.ecency.SupportSettings
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LanguageSync
import com.ecency.core.l10n.LocaleMatch
import com.ecency.core.legacy.LegacyImportError
import com.ecency.core.legacy.LegacySettings
import com.ecency.core.legacy.LegacySettingsReader
import com.ecency.core.render.ImageProxy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `settings.*` and the `alert.*` texts the settings screen shows. */
object SettingsCopy {
    val TITLE: String get() = L.settings.settings
    val GENERAL: String get() = L.settings.general
    val CURRENCY: String get() = L.settings.currency
    /** Read through the localization catalog (the l10n core's end to end proof; the rest follows in later branches). */
    val LANGUAGE: String get() = L.settings.language
    val SERVER: String get() = L.settings.server
    val IMAGE_SERVER: String get() = L.settings.imageServer
    val NSFW: String get() = L.settings.nsfwContent
    val APPEARANCE: String get() = L.settings.colorTheme
    val SHOW_IMAGES: String get() = L.settings.showImgs
    val PIN_CODE: String get() = L.settings.pincode
    val BIOMETRIC: String get() = L.settings.biometric
    val RESET_PIN: String get() = L.settings.resetPin
    val RESET: String get() = L.settings.reset
    val PUSH: String get() = L.settings.pushNotification
    val SUPPORT: String get() = L.settings.supportEcency
    val SUPPORT_BENEFICIARY: String get() = L.settings.supportBeneficiary
    val SUPPORT_BENEFICIARY_PERCENT: String get() = L.settings.supportBeneficiaryPercent
    val SUPPORT_CURATION: String get() = L.settings.supportCuration
    val SUPPORT_CURATION_PERCENT: String get() = L.settings.supportCurationPercent
    val RATE_APP: String get() = L.settings.rateApp
    val RATE: String get() = L.settings.rate
    val SEND_FEEDBACK: String get() = L.settings.sendFeedback
    val SEND: String get() = L.settings.send
    val EMAIL_DIGESTS: String get() = L.settings.emailDigests
    val MANAGE: String get() = L.settings.backup
    val MANAGE_AUTHORITIES: String get() = L.settings.backupPrivateKeys
    /** The Manage Authorities row's button: RN's "Manage" repeated the row's own verb (#3599). */
    val OPEN: String get() = L.native.settings.open
    val CHECKING: String get() = L.alert.checking
    val CONNECTION_SUCCESS: String get() = L.alert.connectionSuccess
    val CONNECTION_FAIL: String get() = L.alert.connectionFail
    val SERVER_FAIL: String get() = L.settings.serverFail
    val SUCCESSFUL: String get() = L.alert.successful
    val FAIL: String get() = L.alert.fail
    val FEEDBACK_SUCCESS: String get() = L.settings.feedbackSuccess
    val FEEDBACK_FAIL: String get() = L.settings.feedbackFail
    const val FEEDBACK_TO = "bug@ecency.com"
    val FEEDBACK_SUBJECT: String get() = L.native.settings.feedbackSubject

    /** RN `settings.feedback_body`. */
    fun feedbackBody(username: String, appVersion: String, platform: String) =
        L.settings.feedbackBody(username = username, appVersion = appVersion, platform = platform)

    /** The toast for a server check (RN `_changeApi`'s alertMessage). */
    fun serverToast(check: ServerCheck) = when (check) {
        ServerCheck.OK -> CONNECTION_SUCCESS
        ServerCheck.UNREACHABLE -> CONNECTION_FAIL
        ServerCheck.STALE -> SERVER_FAIL
    }
}

/** One choice of a settings dropdown: what it shows and what is stored. */
data class SettingsOption(val label: String, val value: String)

/** RN src/constants/options (currency, language, imageServer) and supportBeneficiary's presets. */
object SettingsOptions {
    /** RN currency.ts: labels upper case, values lower case (what `settings.currency` holds; the wallet reads it). */
    val CURRENCIES: List<SettingsOption> = listOf(
        "btc", "usd", "eur", "rub", "gbp", "jpy", "krw", "inr", "cny", "uah", "sek", "try",
        "cad", "chf", "aud", "nok", "pln", "php", "idr", "zar", "thb", "pkr", "vnd", "ngn",
    ).map { SettingsOption(it.uppercase(), it) }

    const val DEFAULT_CURRENCY = "usd"

    /** RN language.ts, in RN's order. */
    val LANGUAGES: List<SettingsOption> = listOf(
        "English" to "en-US", "Hindī" to "hi-IN", "Русский" to "ru-RU", "한국어" to "ko-KR", "Türkçe" to "tr-TR",
        "Bahasa Indonesia" to "id-ID", "Bangla" to "bn-BD", "日本語" to "ja-JP", "汉语 - 简体字" to "zh-CN",
        "漢語 - 繁體字" to "zh-TW", "Bahasa Melayu" to "ms-MY", "Deutsch" to "de-DE", "Español" to "es-ES",
        "Eesti keel" to "et-EE", "Français" to "fr-FR", "Italiano" to "it-IT", "Srpski" to "sr-CS", "Polski" to "pl-PL",
        "Magyar" to "hu-HU", "Български" to "bg-BG", "Ukraïnska" to "uk-UA", "Kurdî‎" to "ku-TR",
        "Azərbaycanca" to "az-AZ", "O`zbekcha" to "uz-UZ", "فارسی" to "fa-IR", "العربية" to "ar-SA",
        "Lietuvių" to "lt-LT", "Suomi" to "fi-FI", "Filipino" to "fil-PH", "Português" to "pt-PT", "Românește" to "ro-RO",
        "Tiếng Việt" to "vi-VN", "Dansk" to "da-DK", "ไทย" to "th-TH", "Ελληνικά" to "el-GR", "Svenska" to "sv-SE",
        "Nederlands" to "nl-NL", "עברית" to "he-IL", "Čeština" to "cs-CZ",
    ).map { (label, value) -> SettingsOption(label, value) }

    const val DEFAULT_LANGUAGE = "en-US"

    /** RN imageServer.ts, labels in the current language. */
    val IMAGE_SERVERS: List<SettingsOption> get() = listOf(
        SettingsOption(L.settings.imageServerDefault, ImageProxy.DEFAULT_BASE),
        SettingsOption(L.settings.imageServerHiveblog, "https://images.hive.blog"),
        SettingsOption(L.settings.imageServerNocdn, "https://img.ecency.com"),
    )

    /** RN SUPPORT_BENEFICIARY_PERCENTS, SUPPORT_CURATION_PERCENTS and DEFAULT_SUPPORT_PERCENT. */
    val BENEFICIARY_PERCENTS = listOf(1, 5, 10, 25)
    val CURATION_PERCENTS = listOf(5, 10, 25, 50, 100)
    const val DEFAULT_SUPPORT_PERCENT = 5

    /** RN autoLocale mapToRegisteredLocale: a device locale to a language of [LANGUAGES], or null ([LocaleMatch]). */
    fun registeredLocale(deviceLocale: String?): String? = LocaleMatch.registered(deviceLocale, LANGUAGES.map { it.value })

    /** RN groomingServerName: the server without `https://`. */
    fun serverLabel(server: String): String = server.removePrefix("https://")

    /** RN sendEmail: `mailto:<to>?subject=...&body=...`, encoded as qs.stringify does (RFC 3986). */
    fun feedbackMailto(username: String?, appVersion: String, platform: String): String =
        "mailto:${SettingsCopy.FEEDBACK_TO}?subject=${qsEncode(SettingsCopy.FEEDBACK_SUBJECT.removePrefix("\u200F"))}&body=" +
            qsEncode(SettingsCopy.feedbackBody(username ?: L.native.settings.unknownUser, appVersion, platform).removePrefix("\u200F"))

    private const val HEX = "0123456789ABCDEF"

    /** qs's encoder: only `A-Z a-z 0-9 - _ . ~` stay, everything else is UTF-8 percent encoded. */
    internal fun qsEncode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~") append(c)
            else append('%').append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
        }
    }
}

/** RN openStoreListing: where "Rate Ecency" goes. */
object StoreListing {
    /** Android: the Play app, else the listing on the web ([UpdateLinks.PLAY_WEB]). */
    const val ANDROID = "market://details?id=${UpdateLinks.ANDROID_APP_ID}"

    /**
     * iOS fallback when the lookup fails (offline) or finds no page: RN's own web listing (`getStoreListingUrl(true)`,
     * the Ecency listing this app updates) on its review form.
     */
    const val IOS_WEB = "https://apps.apple.com/app/id1451896376?action=write-review"

    /** iOS: the App Store page Apple's lookup gave ([UpdateLinks.APP_STORE_LOOKUP]) opened on its review form. */
    fun iosReview(page: String): String? = if (UpdateLinks.isAppStorePage(page)) page + (if ('?' in page) "&" else "?") + "action=write-review" else null
}

data class SettingsState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Lower case code from [SettingsOptions.CURRENCIES]. */
    val currency: String = SettingsOptions.DEFAULT_CURRENCY,
    /** The language in effect: the saved one (the device's, saved once at the first start), else English. */
    val language: String = SettingsOptions.DEFAULT_LANGUAGE,
    /**
     * [language] is final for this start: the settings started and the RN import (first native start only) applied
     * its language. iOS holds the first frame of its screens until then, so they never rebuild for the import.
     */
    val languageSettled: Boolean = false,
    val nsfw: NsfwMode = NsfwMode.WARN,
    val imageServer: String = ImageProxy.DEFAULT_BASE,
    /** The picker's servers (RN useGetServersQuery); empty until loaded. */
    val servers: List<String> = emptyList(),
    /** The server the row shows: the saved one, else the node the pool starts with. */
    val server: String? = null,
    /** RN `setApi('')` plus the RefreshControl: a check is running, the row reads "Checking...". */
    val checkingServer: Boolean = false,
    /** The account [support] belongs to. */
    val supportAccount: String? = null,
    /** RN supportSettings: null while not loaded (or the load failed), and then the card stays hidden. */
    val support: SupportSettings? = null,
) {
    val currencyIndex: Int get() = SettingsOptions.CURRENCIES.indexOfFirst { it.value == currency }
    val languageIndex: Int get() = SettingsOptions.LANGUAGES.indexOfFirst { it.value == language }
    val imageServerIndex: Int get() = SettingsOptions.IMAGE_SERVERS.indexOfFirst { it.value == imageServer }.coerceAtLeast(0)
    val serverLabel: String get() = if (checkingServer || server == null) SettingsCopy.CHECKING else SettingsOptions.serverLabel(server)

    /**
     * The picker's highlighted row: the shown server in [servers], compared without case or a trailing slash (a saved
     * or imported "https://API.example/" is the list's "https://api.example"); -1 when it is not listed. RN #2672
     * highlighted rpc.ecency.com whatever was saved.
     */
    val serverIndex: Int
        get() {
            fun key(s: String) = s.trim().trimEnd('/').lowercase()
            val shown = server?.let(::key) ?: return -1
            return servers.indexOfFirst { key(it) == shown }
        }
    val beneficiaryPercent: Int get() = support?.beneficiaryPercent?.toInt() ?: 0
    val curationPercent: Int get() = support?.curationPercent?.toInt() ?: 0
}

/**
 * RN settingsContainer: the device settings (currency, language, server, image server, NSFW, appearance) kept in
 * [Prefs], the account's "Support Ecency" percents kept on Ecency's server, and the one-time import of what the RN
 * app had chosen. The PIN rows ([AppLockModel]), push switches ([PushModel]), "Show Images"
 * ([NotificationsModel]), keys and delete flows have their own models. One per app ([AppEnvironment.settings]).
 *
 * Support settings follow RN's rules: the card shows only after a valid load; every save carries both percents,
 * shows at once and goes out in order (a save that a newer one supersedes is not sent); only the newest operation
 * applies its answer; a failed save goes back to the last values the server confirmed; an account change hides the
 * card and loads the new account's; [onShow] loads again after the queued saves (the editor chip writes them too).
 */
class SettingsModel internal constructor(private val env: AppEnvironment) : StateModel<SettingsState>(SettingsState(), env.dispatchers) {
    private var started = false
    private var visible = false

    private var supportOpSeq = 0
    private var supportSaveSeq = 0
    private var confirmed: SupportSettings? = null
    private var confirmedSeq = 0
    private val saves = Mutex()

    /** The app's one model: screens do not own it. */
    override fun close() {}

    /**
     * Call once at app start, after the platform set [AppEnvironment.prefs], with the device locale (Android
     * `Locale.getDefault().toLanguageTag()`, iOS `Locale.preferredLanguages.first`). Reads the saved settings at
     * once (the image server applies before any image is built) and imports the RN app's settings in the
     * background. Main thread.
     */
    fun start(deviceLocale: String?) {
        if (started) return
        started = true
        detectLocale(deviceLocale)
        reload()
        syncSystemLanguage()
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged && event.username != current.supportAccount) accountChanged(event.username)
                if (event == AppEvent.DataWiped) accountChanged(null)
            }
        }
        if (env.legacy == null || env.prefs.getString(KEY_IMPORTED) != null) {
            setState { it.copy(languageSettled = true) }
        } else {
            scope.launch {
                try {
                    importLegacy()
                } finally {
                    setState { it.copy(languageSettled = true) }
                }
            }
        }
    }

    private fun reload() {
        val prefs = env.prefs
        val image = prefs.getString(KEY_IMAGE_SERVER)?.takeIf { s -> SettingsOptions.IMAGE_SERVERS.any { it.value == s } } ?: ImageProxy.DEFAULT_BASE
        env.setImageServer(image)
        setState {
            it.copy(
                theme = ThemeMode.of(prefs.getString(KEY_THEME)),
                currency = prefs.getString(KEY_CURRENCY)?.lowercase()?.takeIf { c -> SettingsOptions.CURRENCIES.any { o -> o.value == c } }
                    ?: SettingsOptions.DEFAULT_CURRENCY,
                language = savedLanguage() ?: SettingsOptions.DEFAULT_LANGUAGE,
                nsfw = NsfwMode.of(prefs),
                imageServer = image,
                server = shownServer(),
            )
        }
        applyCatalog()
    }

    /** The UI catalog shows [SettingsState.language] (when this app drives it: [AppEnvironment.drivesLanguageCatalog]). */
    private fun applyCatalog() {
        if (env.drivesLanguageCatalog) L10n.setLanguage(current.language)
    }

    /**
     * Reconciles the saved language with the OS per-app language ([LanguageSync]): adopts a language the user set
     * in the system settings, else writes the saved one there. At start, after the RN import, and whenever the app
     * comes back to the front (Android: the user may have changed it in the system settings meanwhile; iOS ends the
     * app on that change, so its start covers it). Main thread.
     */
    fun syncSystemLanguage() {
        if (!started) return
        val system = env.systemLanguage ?: return
        val prefs = env.prefs
        val outcome = LanguageSync.reconcile(
            saved = current.language,
            system = system.read(),
            lastSynced = prefs.getString(KEY_LANGUAGE_SYNCED),
            device = system.deviceLanguage(),
            ids = SettingsOptions.LANGUAGES.map { it.value },
        )
        if (outcome.adopted) {
            prefs.putString(KEY_LANGUAGE, outcome.language)
            setState { it.copy(language = outcome.language) }
            applyCatalog()
        }
        if (outcome.writeSystem) system.write(LanguageSync.tagToWrite(outcome.language, system.deviceLanguage(), SettingsOptions.LANGUAGES.map { it.value }))
        prefs.putString(KEY_LANGUAGE_SYNCED, outcome.synced)
    }

    /**
     * RN autoDetectLocale: once per install, when no language was chosen, the device language (mapped to one of RN's)
     * is saved; English needs no entry. A later device change does not move it, as in RN.
     */
    private fun detectLocale(deviceLocale: String?) {
        val prefs = env.prefs
        if (prefs.getString(KEY_LOCALE_DETECTED) != null) return
        val mapped = SettingsOptions.registeredLocale(deviceLocale)
        if (prefs.getString(KEY_LANGUAGE) == null && mapped != null && mapped != SettingsOptions.DEFAULT_LANGUAGE) prefs.putString(KEY_LANGUAGE, mapped)
        // the mark holds what detection chose ("" for nothing), so the RN import can still replace it
        prefs.putString(KEY_LOCALE_DETECTED, prefs.getString(KEY_LANGUAGE).orEmpty())
    }

    private fun savedLanguage(): String? = env.prefs.getString(KEY_LANGUAGE)?.takeIf { l -> SettingsOptions.LANGUAGES.any { it.value == l } }

    private fun shownServer(): String? = env.hiveNodes.selected() ?: env.api.client.nodes.firstOrNull()

    /** The language content features use (translation target, reading time language): RN `application.language`. */
    val contentLanguage: String get() = current.language

    // ---- device settings ----

    /** RN "Appearance". */
    fun setTheme(mode: ThemeMode) {
        env.prefs.putString(KEY_THEME, mode.name)
        setState { it.copy(theme = mode) }
    }

    /**
     * An earlier preview build kept the theme in the platform's own storage: it moves here once (when nothing is
     * saved here yet). Main thread.
     */
    fun adoptPlatformTheme(name: String?) {
        if (name == null || env.prefs.getString(KEY_THEME) != null) return
        setTheme(ThemeMode.of(name))
    }

    /** RN `_currencyChange`. */
    fun setCurrency(code: String) {
        val c = code.lowercase()
        if (SettingsOptions.CURRENCIES.none { it.value == c }) return
        env.prefs.putString(KEY_CURRENCY, c)
        setState { it.copy(currency = c) }
    }

    /**
     * RN language dropdown: the app's copy, layout direction and content features (translation target, reader
     * language) switch at once, and the OS per-app language follows (iOS applies it to the bundle at the next launch).
     */
    fun setLanguage(code: String) {
        if (SettingsOptions.LANGUAGES.none { it.value == code }) return
        env.prefs.putString(KEY_LANGUAGE, code)
        setState { it.copy(language = code) }
        applyCatalog()
        env.systemLanguage?.let { system ->
            // the picker's choice wins over whatever the OS shows now, and ends "System default"
            val ids = SettingsOptions.LANGUAGES.map { it.value }
            if (LocaleMatch.registered(system.read(), ids) != code) system.write(LanguageSync.tagToWrite(code, system.deviceLanguage(), ids))
            env.prefs.putString(KEY_LANGUAGE_SYNCED, code)
        }
    }

    /** RN setNsfw: feeds and waves read it when they load their next page. */
    fun setNsfw(mode: NsfwMode) {
        env.prefs.putString(NsfwMode.PREFS_KEY, mode.value)
        setState { it.copy(nsfw = mode) }
    }

    /** RN IMAGE_SERVER: saved, applied at once, toast "Successful". */
    fun setImageServer(server: String) {
        if (SettingsOptions.IMAGE_SERVERS.none { it.value == server }) return
        env.prefs.putString(KEY_IMAGE_SERVER, server.takeIf { it != ImageProxy.DEFAULT_BASE })
        env.setImageServer(server)
        setState { it.copy(imageServer = server) }
        env.ui.toast(SettingsCopy.SUCCESSFUL)
    }

    // ---- server ----

    /** The screen shows: the server list (RN useGetServersQuery) and the support settings again. Main thread. */
    fun onShow() {
        setState { it.copy(server = if (it.checkingServer) it.server else shownServer()) }
        scope.launch {
            val list = env.hiveNodes.servers()
            setState { it.copy(servers = list) }
        }
        visible = true
        // after the queued saves, so the load never sees the server from before them (RN chains it the same way)
        scope.launch {
            // opened at launch: the accounts are read first
            env.session.awaitLoaded()
            if (visible && env.session.current.username != null) saves.withLock { fetchSupport() }
        }
    }

    /** The screen went away: an account change no longer loads support settings. */
    fun onHide() {
        visible = false
    }

    /** RN `_changeApi`: checked, saved only when it answers in time with a recent block, one of RN's three toasts. */
    fun selectServer(server: String) {
        if (current.checkingServer) return
        val list = current.servers.ifEmpty { HiveNodes.DEFAULT }
        setState { it.copy(checkingServer = true) }
        scope.launch {
            val result = try {
                env.hiveNodes.select(server, list)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ServerCheck.UNREACHABLE
            }
            setState { it.copy(checkingServer = false, server = shownServer()) }
            env.ui.toast(SettingsCopy.serverToast(result))
        }
    }

    /**
     * iOS "Rate Ecency": the App Store page Apple's lookup names for this app, on its review form, asked for at most
     * [RATE_LOOKUP_MILLIS]; offline, slow or unlisted, RN's web listing ([StoreListing.IOS_WEB]).
     */
    @Throws(CancellationException::class)
    suspend fun iosRatePage(): String {
        val page = withTimeoutOrNull(RATE_LOOKUP_MILLIS) {
            try {
                env.onboardingApi.appStorePage(UpdateLinks.APP_STORE_LOOKUP)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        return page?.let(StoreListing::iosReview) ?: StoreListing.IOS_WEB
    }

    // ---- support settings ----

    private fun accountChanged(username: String?) {
        supportOpSeq++
        // a late answer of the previous account's save can never become this account's rollback target
        confirmedSeq = supportOpSeq
        confirmed = null
        setState { it.copy(supportAccount = username, support = null) }
        if (username != null && visible) scope.launch { saves.withLock { fetchSupport() } }
    }

    private suspend fun fetchSupport() {
        val user = env.session.current.username ?: return
        val seq = ++supportOpSeq
        // the card shows only after a valid load: until then nothing can save over unknown values
        try {
            val loaded = env.editorApi.supportSettings(user)
            if (seq != supportOpSeq) return
            if (loaded != null && seq > confirmedSeq) {
                confirmed = loaded
                confirmedSeq = seq
            }
            setState { it.copy(supportAccount = user, support = loaded) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the card stays hidden; showing the screen again retries
            if (seq == supportOpSeq) setState { it.copy(supportAccount = user, support = null) }
        }
    }

    /** RN SUPPORT_BENEFICIARY toggle: on is 5 %, off 0. */
    fun setSupportBeneficiary(on: Boolean) = saveSupport(beneficiary = if (on) SettingsOptions.DEFAULT_SUPPORT_PERCENT else 0)

    fun setSupportBeneficiaryPercent(percent: Int) {
        if (percent in SettingsOptions.BENEFICIARY_PERCENTS) saveSupport(beneficiary = percent)
    }

    /** RN SUPPORT_CURATION toggle: on is 5 %, off 0. */
    fun setSupportCuration(on: Boolean) = saveSupport(curation = if (on) SettingsOptions.DEFAULT_SUPPORT_PERCENT else 0)

    fun setSupportCurationPercent(percent: Int) {
        if (percent in SettingsOptions.CURATION_PERCENTS) saveSupport(curation = percent)
    }

    private fun saveSupport(beneficiary: Int? = null, curation: Int? = null) {
        val user = current.supportAccount ?: return
        // both percents always travel: never write from unknown values
        val prev = current.support ?: return
        if (env.session.current.username != user) return
        val seq = ++supportOpSeq
        val saveSeq = ++supportSaveSeq
        val next = SupportSettings(beneficiary?.toDouble() ?: prev.beneficiaryPercent, curation?.toDouble() ?: prev.curationPercent)
        setState { it.copy(support = next) }
        scope.launch {
            // strict FIFO: a request goes out only after every earlier one settled
            saves.withLock {
                // a newer save supersedes this one (each carries the full payload)
                if (saveSeq != supportSaveSeq) return@withLock
                try {
                    val answer = env.editorApi.updateSupportSettings(user, next.beneficiaryPercent, next.curationPercent)
                    if (answer != null && seq > confirmedSeq) {
                        confirmed = answer
                        confirmedSeq = seq
                    }
                    if (seq != supportOpSeq) return@withLock
                    if (answer != null) setState { it.copy(support = answer) } else fetchSupport()
                    env.ui.toast(SettingsCopy.SUCCESSFUL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (seq != supportOpSeq) return@withLock
                    setState { it.copy(support = confirmed ?: prev) }
                    env.ui.toast(SettingsCopy.FAIL)
                }
            }
        }
    }

    // ---- RN settings import ----

    /**
     * Once per install: what the RN app had chosen (RN `settings` schema and `persist:root` application) fills the
     * settings this app has no value for yet, then the models that read them load them again. Storage that cannot be
     * read now is tried again at the next start; anything else (nothing there, bad data) counts as done.
     */
    private suspend fun importLegacy() {
        val source = env.legacy ?: return
        if (env.prefs.getString(KEY_IMPORTED) != null) return
        val legacy = try {
            withContext(dispatchers.io) { LegacySettingsReader.read(source.storage) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: LegacyImportError.StorageUnreadable) {
            return
        } catch (_: Exception) {
            null
        }
        if (legacy != null) {
            applyLegacy(legacy)
            // the language is final before the editor drafts are read (the screens may wait for it)
            reload()
            syncSystemLanguage()
            setState { it.copy(languageSettled = true) }
        }
        // the editor's unsaved RN drafts, in the same once-per-install step (README "Importing accounts from the RN app")
        try {
            LegacyEditorDrafts.apply(withContext(dispatchers.io) { LegacyEditorDrafts.read(source.storage, env.nowMillis()) }, env.localDrafts)
        } catch (e: CancellationException) {
            throw e
        } catch (_: LegacyImportError.StorageUnreadable) {
            return
        } catch (_: Exception) {
        }
        env.prefs.putString(KEY_IMPORTED, "1")
        if (legacy == null) return
        // the language was synced with the OS above, right after the settings import; nothing since changed it
        reload()
        env.push.reloadSettings()
        env.notifications.reloadShowImages()
        env.hiveNodes.applySaved()
    }

    internal fun applyLegacy(legacy: LegacySettings) {
        val prefs = env.prefs
        fun fill(key: String, value: String?) {
            if (value != null && prefs.getString(key) == null) prefs.putString(key, value)
        }
        // the published list's rule: an http or user@host server stays behind
        fill(HiveNodeSettings.PREFS_KEY, HiveNodes.userServer(legacy.server))
        fill(KEY_CURRENCY, legacy.currency?.lowercase()?.takeIf { c -> SettingsOptions.CURRENCIES.any { it.value == c } })
        // RN's own choice wins over what this app detected from the device at its first start
        val detected = prefs.getString(KEY_LOCALE_DETECTED)
        // RN's en-US is its initial state unless its own detection ran (`locale_auto_detected`): only then is it a choice
        val rnLanguage = legacy.language?.takeIf { l -> SettingsOptions.LANGUAGES.any { it.value == l } }
            ?.takeUnless { it == SettingsOptions.DEFAULT_LANGUAGE && !legacy.localeDetected }
        if (rnLanguage != null && (prefs.getString(KEY_LANGUAGE) ?: "") == detected) prefs.putString(KEY_LANGUAGE, rnLanguage) else fill(KEY_LANGUAGE, rnLanguage)
        fill(NsfwMode.PREFS_KEY, legacy.nsfw?.takeIf { n -> NsfwMode.entries.any { it.value == n } })
        fill(KEY_THEME, legacy.colorTheme?.let { i -> ThemeMode.entries.firstOrNull { it.rnValue == i }?.name })
        if (legacy.hideImages == true) fill(NotificationsModel.KEY_SHOW_IMAGES, "0")
        env.walkthrough.importShown(legacy.walkthroughShown)
        fill(KEY_IMAGE_SERVER, legacy.imageServer?.takeIf { s -> s != ImageProxy.DEFAULT_BASE && SettingsOptions.IMAGE_SERVERS.any { it.value == s } })
        // the push switches move together, and only onto switches this app never saved
        if (prefs.getString(PushSettings.KEY_ENABLED) == null && prefs.getString(PushSettings.KEY_OFF) == null &&
            (legacy.pushEnabled != null || legacy.pushTypes.isNotEmpty())
        ) {
            val off = PushType.entries.filter { legacy.pushTypes[it.key] == false }.toSet()
            PushSettings(enabled = legacy.pushEnabled != false, off = off).save(prefs)
        }
    }

    companion object {
        /** The wallet reads it (lower case code). */
        const val KEY_CURRENCY = "settings.currency"
        const val KEY_LANGUAGE = "settings.language"

        /** RN `locale_auto_detected`: detection ran; the value is the language it saved ("" for none). */
        const val KEY_LOCALE_DETECTED = "settings.localeDetected"

        /** The app language last written to or adopted from the OS per-app setting, or [LanguageSync.FOLLOWING_SYSTEM]. */
        const val KEY_LANGUAGE_SYNCED = "settings.languageSynced"
        const val KEY_THEME = "settings.theme"

        /** Absent means the default ([ImageProxy.DEFAULT_BASE]). */
        const val KEY_IMAGE_SERVER = "settings.imageServer"

        /** How long "Rate Ecency" waits for Apple's lookup before the web listing. */
        const val RATE_LOOKUP_MILLIS = 5_000L

        /** The RN settings import ran (or there was nothing to import). */
        const val KEY_IMPORTED = "settings.legacyImported"
    }
}
