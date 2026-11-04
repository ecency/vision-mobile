package com.ecency.core.l10n

import com.ecency.core.l10n.LanguageSync.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sync rule between the saved app language and the OS per-app language (README "Localization", app language). */
class LanguageSyncTest {
    private fun sync(saved: String, system: String?, lastSynced: String?, device: String? = "en-US") =
        LanguageSync.reconcile(saved, system, lastSynced, device)

    private val follow = LanguageSync.FOLLOWING_SYSTEM

    @Test
    fun withoutAnOsSettingNothingIsWritten() {
        assertEquals(Outcome("de-DE", adopted = false, writeSystem = false, synced = "de-DE"), sync("de-DE", null, null))
        assertEquals(Outcome("de-DE", adopted = false, writeSystem = false, synced = "fr-FR"), sync("de-DE", null, "fr-FR"))
    }

    @Test
    fun theFirstSyncWritesTheSavedLanguage() {
        // first start: the language detected from the device (or RN's) goes to the OS
        assertEquals(Outcome("ru-RU", adopted = false, writeSystem = true, synced = "ru-RU"), sync("ru-RU", "", null, device = "ru-RU"))
        assertEquals(Outcome("en-US", adopted = false, writeSystem = true, synced = "en-US"), sync("en-US", "", null, device = "sw-KE"))
    }

    @Test
    fun aPerAppLanguageSetInTheSystemSettingsIsAdopted() {
        assertEquals(Outcome("de-DE", adopted = true, writeSystem = false, synced = "de-DE"), sync("fr-FR", "de", "fr-FR"))
        assertEquals(Outcome("zh-TW", adopted = true, writeSystem = false, synced = "zh-TW"), sync("en-US", "zh-Hant", "en-US"))
        assertEquals(Outcome("sr-CS", adopted = true, writeSystem = false, synced = "sr-CS"), sync("en-US", "sr-Latn", "en-US"))
        // set before the first start (Android lists the app's languages from install): beats the detected one
        assertEquals(Outcome("ja-JP", adopted = true, writeSystem = false, synced = "ja-JP"), sync("de-DE", "ja", null, device = "de-DE"))
    }

    @Test
    fun aDeviceLanguageChangeNeverMovesTheSavedChoice() {
        // the per-app setting is the one the app wrote; the device now runs Japanese: nothing changes
        assertEquals(Outcome("fr-FR", adopted = false, writeSystem = false, synced = "fr-FR"), sync("fr-FR", "fr", "fr-FR", device = "ja-JP"))
    }

    @Test
    fun theSavedChoiceWinsWhenTheOsStillShowsWhatTheAppLastWrote() {
        // a picker change or the RN import the OS has not seen yet
        assertEquals(Outcome("it-IT", adopted = false, writeSystem = true, synced = "it-IT"), sync("it-IT", "fr", "fr-FR"))
        // a tag the app has no language for
        assertEquals(Outcome("it-IT", adopted = false, writeSystem = true, synced = "it-IT"), sync("it-IT", "sw", "it-IT"))
        // equal by mapping, not by text ("iw" is Hebrew, a region read back is the same language)
        assertEquals(Outcome("he-IL", adopted = false, writeSystem = false, synced = "he-IL"), sync("he-IL", "iw-IL", "he-IL"))
        assertEquals(Outcome("en-US", adopted = false, writeSystem = false, synced = "en-US"), sync("en-US", "en-GB", "en-US"))
    }

    @Test
    fun systemDefaultFollowsTheDeviceWithoutWritingBack() {
        // cleared after the app had set a language: the device's, and the OS keeps "System default"
        assertEquals(Outcome("ja-JP", adopted = true, writeSystem = false, synced = follow), sync("fr-FR", "", "fr-FR", device = "ja-JP"))
        assertEquals(Outcome("en-US", adopted = true, writeSystem = false, synced = follow), sync("fr-FR", "", "fr-FR", device = "sw-KE"))
        assertEquals(Outcome("fr-FR", adopted = false, writeSystem = false, synced = follow), sync("fr-FR", "", "fr-FR", device = "fr-CA"))
        // still following: the next resume writes nothing, and a device language change is followed
        assertEquals(Outcome("ja-JP", adopted = false, writeSystem = false, synced = follow), sync("ja-JP", "", follow, device = "ja-JP"))
        assertEquals(Outcome("de-DE", adopted = true, writeSystem = false, synced = follow), sync("ja-JP", "", follow, device = "de-AT"))
    }

    @Test
    fun aLanguagePickedInTheSystemSettingsEndsFollowing() {
        assertEquals(Outcome("ko-KR", adopted = true, writeSystem = false, synced = "ko-KR"), sync("ja-JP", "ko", follow, device = "ja-JP"))
        // the same language as the one followed: nothing to switch, but it is a choice now
        assertEquals(Outcome("ja-JP", adopted = false, writeSystem = false, synced = "ja-JP"), sync("ja-JP", "ja", follow, device = "ja-JP"))
    }

    @Test
    fun theWrittenTagKeepsTheDeviceRegionForTheSameLanguage() {
        assertEquals("en-GB", LanguageSync.tagToWrite("en-US", "en-GB"))
        assertEquals("fr-CA", LanguageSync.tagToWrite("fr-FR", "fr_CA"))
        assertEquals("zh-Hant-TW", LanguageSync.tagToWrite("zh-TW", "zh-Hant-TW"))
        assertEquals("sr-Latn-RS", LanguageSync.tagToWrite("sr-CS", "sr-Latn-RS"))
        assertEquals("zh-Hant", LanguageSync.tagToWrite("zh-TW", "zh-TW"), "the script stays explicit")
        assertEquals("pt-PT", LanguageSync.tagToWrite("pt-PT", "pt-BR"), "Brazilian is not the app's Portuguese")
        assertEquals("sr-Latn", LanguageSync.tagToWrite("sr-CS", "sr-RS"), "Serbian without a script is Cyrillic")
        assertEquals("de", LanguageSync.tagToWrite("de-DE", "en-GB"))
        assertEquals("de", LanguageSync.tagToWrite("de-DE", null))
        assertEquals("de", LanguageSync.tagToWrite("de-DE", ""))
        // another script than the chosen language's: the app's ku-TR is Latin, an Arabic script device must not win
        assertEquals("ku", LanguageSync.tagToWrite("ku-TR", "ku-Arab-IQ"))
        assertEquals("ku-TR", LanguageSync.tagToWrite("ku-TR", "ku-TR"))
        assertEquals("sr-Latn", LanguageSync.tagToWrite("sr-CS", "sr-Cyrl-RS"))
        // no ping-pong: whatever is written reads back as the same language, so the next sync changes nothing
        for (device in listOf("en-GB", "fr-CA", "zh-Hant-TW", "pt-BR", "sr-Latn-RS", "de-AT", "sw-KE", "ku-Arab-IQ", null)) {
            for (id in L10n.supported) {
                val tag = LanguageSync.tagToWrite(id, device)
                assertEquals(id, LocaleMatch.registered(tag), "$id on $device wrote $tag")
                assertEquals(Outcome(id, adopted = false, writeSystem = false, synced = id), sync(id, tag, id, device), "$id on $device")
            }
        }
    }

    @Test
    fun systemTagsAreBareLanguagesExceptWhereTheVariantMatters() {
        assertEquals(39, L10n.supported.size)
        assertEquals("en", L10n.systemTag("en-US"))
        assertEquals("ru", L10n.systemTag("ru-RU"))
        assertEquals("zh-Hans", L10n.systemTag("zh-CN"))
        assertEquals("zh-Hant", L10n.systemTag("zh-TW"))
        assertEquals("sr-Latn", L10n.systemTag("sr-CS"))
        assertEquals("pt-PT", L10n.systemTag("pt-PT"))
        assertEquals("fil", L10n.systemTag("fil-PH"))
        assertEquals("en", L10n.systemTag("xx-XX"), "unknown is English's")
        val tags = L10n.supported.map(L10n::systemTag)
        assertEquals(tags.size, tags.toSet().size, "one tag per language")
        for (id in L10n.supported) assertEquals(id, LocaleMatch.registered(L10n.systemTag(id)), "$id round trips")
    }

    @Test
    fun rtlIsArabicPersianAndHebrewNotKurdish() {
        for (id in listOf("ar-SA", "fa-IR", "he-IL")) assertTrue(L10n.isRtl(id), id)
        for (id in listOf("ku-TR", "en-US", "tr-TR", "ja-JP")) assertFalse(L10n.isRtl(id), id)
    }
}
