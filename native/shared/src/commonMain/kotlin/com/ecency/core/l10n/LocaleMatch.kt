package com.ecency.core.l10n

/** Matching a device or stored locale id to one of the app's languages, as RN's autoLocale does. */
object LocaleMatch {
    private val LEGACY_ALIASES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * RN autoLocale mapToRegisteredLocale: [locale] ("de_DE", "pt-BR", "zh-Hant-TW", "en") to one of [ids], or null.
     * Exact match first (any case, `_` or `-`), then Chinese by script or region, then the base language (with the old
     * ISO aliases iw, in, ji).
     */
    fun registered(locale: String?, ids: List<String> = L10nData.LOCALES): String? {
        if (locale.isNullOrEmpty()) return null
        val norm = locale.replace('_', '-').lowercase()
        ids.firstOrNull { it.lowercase() == norm }?.let { return it }
        if (norm.startsWith("zh")) {
            val traditional = "hant" in norm || listOf("tw", "hk", "mo").any { "-$it" in norm }
            ids.firstOrNull { it.lowercase() == if (traditional) "zh-tw" else "zh-cn" }?.let { return it }
        }
        val raw = norm.split('-')[0]
        val base = LEGACY_ALIASES[raw] ?: raw
        return ids.firstOrNull { it.split('-')[0].lowercase() == base }
    }
}
