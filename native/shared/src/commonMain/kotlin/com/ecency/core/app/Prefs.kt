package com.ecency.core.app

/**
 * Small, non-secret app preferences (RN redux-persist values such as the feed tabs or the NSFW setting).
 * Android backs it with SharedPreferences, iOS with UserDefaults; tests and fresh environments use
 * [InMemoryPrefs]. Never put credentials here: those belong in the vault.
 */
interface Prefs {
    fun getString(key: String): String?

    /** Null removes the key. */
    fun putString(key: String, value: String?)

    /** Every stored key (the wipe finds the entries that name an account, [AccountPrefs]). */
    fun keys(): Set<String>
}

/**
 * Preferences that name an account or what it read: the digest offer's "already offered" flag, the Waves
 * onboarding of an account, the posts whose translate banner was dismissed and the spotlights an account dismissed. A wipe removes them, so nothing
 * on the device says who used it. Device settings without a name stay (rating counters, the waves vote weight).
 */
object AccountPrefs {
    val PREFIXES = listOf(
        DigestModel.OFFERED_PREFIX, WavesOnboardingModel.KEY_PREFIX, TranslateModel.DISMISSED_PREFIX, PointActivities.KEY_PREFIX,
        SpotlightModel.DISMISSED_PREFIX, ProposalVoteModel.KEY_PREFIX, PURCHASES_PREFIX, DigestTarget.PROMPT_PREFIX,
    )

    /** Saved purchase targets (account names, a paid account's e-mail) and receipt marks ([PurchaseService]). */
    const val PURCHASES_PREFIX = "purchases."

    fun clear(prefs: Prefs) {
        prefs.keys().filter { key -> PREFIXES.any(key::startsWith) }.forEach { prefs.putString(it, null) }
    }
}

class InMemoryPrefs : Prefs {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun keys(): Set<String> = values.keys.toSet()
}
