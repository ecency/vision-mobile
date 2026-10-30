package com.ecency.l10n.gen

/**
 * The language tags the OS per-app language settings use for the app's locales (Android 13+ `locales_config.xml` and
 * `LocaleManager`, iOS `AppleLanguages` and the app's localizations), and Android's `locales_config.xml`.
 *
 * A tag is the bare language ("de", "ru") so the system lists the language without a region, except where the
 * language alone would name a different variant: Chinese by script (zh-CN is "zh-Hans", zh-TW is "zh-Hant"), Serbian
 * in Latin script ("sr-Latn", RN's sr-CS file is Latin), and European Portuguese ("pt-PT"; Apple reads a bare "pt" as
 * Brazilian). Every tag maps back to its locale through the shared `LocaleMatch`.
 */
object SystemLocales {
    private val SPECIAL = mapOf("zh-CN" to "zh-Hans", "zh-TW" to "zh-Hant", "sr-CS" to "sr-Latn", "pt-PT" to "pt-PT")

    fun tag(id: String): String = SPECIAL[id] ?: id.substringBefore('-')

    /** Android `res/xml/locales_config.xml` listing [ids] (the app's locales, any order) by [tag], sorted by id. */
    fun localeConfigXml(ids: Collection<String>): String {
        val tags = ids.sorted().map(::tag)
        require(tags.toSet().size == tags.size) { "two locales share a system tag: $tags" }
        val b = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        b.append("<!-- Generated from src/config/locales by native/build-logic (SystemLocales). Do not edit. -->\n")
        b.append("<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
        for (t in tags) b.append("    <locale android:name=\"").append(t).append("\" />\n")
        b.append("</locale-config>\n")
        return b.toString()
    }
}
