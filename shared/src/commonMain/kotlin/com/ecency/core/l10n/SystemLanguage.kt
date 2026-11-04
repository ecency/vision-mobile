package com.ecency.core.l10n

/**
 * The OS per-app language setting: Android 13+ `LocaleManager.applicationLocales` (Settings > Apps > Ecency >
 * Language), iOS the app's own `AppleLanguages` default (Settings > Ecency > Language). Each platform sets one on
 * [com.ecency.core.app.AppEnvironment.systemLanguage] where the OS has the setting; the in-app picker stays the source
 * of truth and the two are kept in sync by [LanguageSync] (README "Localization", app language).
 */
interface SystemLanguage {
    /** The per-app language the OS holds now (a language tag such as "de" or "zh-Hans"), or "" when none is set. */
    fun read(): String

    /** Sets the per-app language to [tag] ([LanguageSync.tagToWrite]). iOS applies it to the bundle at the next launch. */
    fun write(tag: String)

    /** The device's own language (its first preferred language, with region), whatever the per-app setting is. */
    fun deviceLanguage(): String?
}

/**
 * How the saved app language and the OS per-app language are reconciled, at start and whenever the app comes back
 * to the front. Pure: [SettingsModel][com.ecency.core.app.SettingsModel] does the reading and writing, and stores
 * [Outcome.synced] as the next call's `lastSynced`.
 *
 * The OS setting differs from the device language: changing the device's language leaves the per-app setting as it
 * is, so the saved choice is kept (RN detects the device language only once). What counts as the user acting in the
 * system settings is a change of the per-app setting since the app last wrote or adopted it (`lastSynced`):
 * - a per-app language that maps to one of the app's ([LocaleMatch]) and is neither `lastSynced` nor the saved one
 *   is adopted as the new choice (also on a first start, before which Android lets the user set it);
 * - a per-app setting cleared after the app had set one ("System default") puts the app in [FOLLOWING_SYSTEM]: the
 *   device language, mapped (English when the app lacks it), becomes the language, nothing is written back (the
 *   system keeps showing "System default"), and later device language changes are followed, since the user chose
 *   the system. A language picked in the app or in the system settings ends that state;
 * - anything else keeps the saved language and writes it to the OS when the OS shows another one (the first sync,
 *   a picker change, the RN settings import, or a tag the app does not know).
 */
object LanguageSync {
    /** `lastSynced` value: the user chose "System default" for the app, so the app follows the device language. */
    const val FOLLOWING_SYSTEM = "system"

    /**
     * [language] is the app language from now on; [adopted]: it came from the OS (or the device it follows);
     * [writeSystem]: write it there; [synced]: the next `lastSynced`.
     */
    data class Outcome(val language: String, val adopted: Boolean, val writeSystem: Boolean, val synced: String)

    /**
     * [saved]: the app language in effect (one of the app's ids). [system]: [SystemLanguage.read], or null when the OS
     * has no per-app setting (nothing to sync). [lastSynced]: the app id last written to or adopted from the OS,
     * [FOLLOWING_SYSTEM], or null before the first sync. [device]: [SystemLanguage.deviceLanguage].
     */
    fun reconcile(saved: String, system: String?, lastSynced: String?, device: String?, ids: List<String> = L10n.supported): Outcome {
        if (system == null) return Outcome(saved, adopted = false, writeSystem = false, synced = lastSynced ?: saved)
        if (system.isEmpty()) {
            if (lastSynced == null) return Outcome(saved, adopted = false, writeSystem = true, synced = saved)
            val followed = LocaleMatch.registered(device, ids) ?: L10n.SOURCE
            return Outcome(followed, adopted = followed != saved, writeSystem = false, synced = FOLLOWING_SYSTEM)
        }
        val chosen = LocaleMatch.registered(system, ids)
        if (chosen != null && chosen != lastSynced && chosen != saved) return Outcome(chosen, adopted = true, writeSystem = false, synced = chosen)
        return Outcome(saved, adopted = false, writeSystem = chosen != saved, synced = saved)
    }

    /**
     * The tag to write for [language]: the device's own tag when it is the same language and extends the language's
     * [L10n.systemTag] (en-GB, fr-CA, zh-Hant-TW, sr-Latn-RS keep their region, so dates, times and numbers keep the
     * device's conventions), else the system tag (pt-BR is not the app's pt-PT, zh-TW without a script is written
     * zh-Hant). Either maps back to [language], so the next [reconcile] sees no change. The device tag is kept only
     * when it names no script or the same script as [language]'s tag: a ku-Arab-IQ device choosing the app's Latin
     * ku-TR writes plain "ku", so the OS does not format in Arabic script for a Latin language.
     */
    fun tagToWrite(language: String, device: String?, ids: List<String> = L10n.supported): String {
        val base = L10n.systemTag(language)
        val tag = device?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return base
        val extends = tag.equals(base, ignoreCase = true) || tag.lowercase().startsWith(base.lowercase() + "-")
        val deviceScript = script(tag)
        val sameScript = deviceScript == null || deviceScript.equals(script(base), ignoreCase = true)
        return if (extends && sameScript && LocaleMatch.registered(tag, ids) == language) tag else base
    }

    /** The script subtag of [tag] ("Hant" in zh-Hant-TW), or null when it names none. */
    private fun script(tag: String): String? =
        tag.split('-').drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }
}
