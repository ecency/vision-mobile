package com.ecency.app

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.widgets.WidgetLanguage
import com.ecency.app.widgets.composeLabels
import com.ecency.app.widgets.streakLines
import com.ecency.app.widgets.unreadLines
import com.ecency.core.app.SettingsModel
import com.ecency.core.app.WidgetSnapshot
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * README "Localization", the strings Android shows outside the app: the generated resources in every language, the
 * widgets' text in the app language (also in a process the app has not started in), and the notification channels
 * and widgets following a language change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemStringsFlowsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun english() {
        L10n.setLanguage(L10n.SOURCE)
        context.getSharedPreferences(EcencyGraph.PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun inLanguage(id: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(L10n.systemTag(id))) })

    @Test
    fun everyLanguageReadsItsGeneratedWidgetLabels() {
        assertEquals(39, L10n.supported.size)
        for (id in L10n.supported) {
            L10n.setLanguage(id)
            val res = inLanguage(id)
            // the resource the system shows equals the catalog's message in that language (a reused RN key, translated in
            // all 39); the catalog starts an RTL message with RLM for in-app first-strong layouts, the OS lays out its own
            fun app(text: String) = text.removePrefix(L10n.RLM.toString())
            assertEquals(id, app(L.notification.notification), res.getString(R.string.widget_notifications_label))
            assertEquals(id, app(L.native.widget.streakName), res.getString(R.string.widget_streak_label))
            assertEquals(id, app(L.native.widget.composeDescription), res.getString(R.string.widget_compose_description))
        }
        assertEquals("Benachrichtigungen", inLanguage("de-DE").getString(R.string.widget_notifications_label))
        assertEquals("Ecency", inLanguage("de-DE").getString(R.string.app_name))
    }

    @Test
    fun aDeviceInAnotherRegionOrScriptGetsTheLanguageTheAppPicks() {
        // pt-BR, a Cyrillic Serbian device and Hong Kong Chinese: no folder names them; the labels must be in the
        // language the app maps them to (LocaleMatch), not English
        for (tag in listOf("pt-BR", "sr-RS", "sr-Cyrl-RS", "zh-HK", "zh-SG", "zh-MO")) {
            val id = checkNotNull(com.ecency.core.app.SettingsOptions.registeredLocale(tag)) { tag }
            L10n.setLanguage(id)
            val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            // the in-app catalog starts an RTL message with RLM for first-strong layouts; the OS lays out its resources itself
            assertEquals(tag, L.notification.notification.removePrefix(L10n.RLM.toString()), res.getString(R.string.widget_notifications_label))
        }
    }

    @Test
    fun widgetTextFollowsTheAppLanguage() {
        val noCount = WidgetSnapshot(signedIn = true, locked = false)
        assertEquals("Notifications", unreadLines(noCount).caption)
        L10n.setLanguage("de-DE")
        assertEquals("Benachrichtigungen", unreadLines(noCount).caption)
        assertEquals(L.native.widgets.lockedText, unreadLines(WidgetSnapshot(signedIn = true, locked = true)).caption)
        L10n.setLanguage("ar-SA")
        // the language's digits, as everywhere in the app
        assertEquals("١٢", unreadLines(WidgetSnapshot(signedIn = true, locked = false, unread = 12)).value)
        assertEquals("٥", streakLines(WidgetSnapshot(signedIn = true, locked = false, streak = 5)).value)
        assertEquals(L.native.widget.write to L.native.widget.newWave, composeLabels())
    }

    @Test
    fun aWidgetDrawnWithoutTheAppReadsTheSavedLanguage() {
        context.getSharedPreferences(EcencyGraph.PREFS_FILE, Context.MODE_PRIVATE).edit().putString(SettingsModel.KEY_LANGUAGE, "ru-RU").commit()
        WidgetLanguage.ensure(context, appDrivesCatalog = true)
        assertEquals("the running app's catalog is left alone", L10n.SOURCE, L10n.language.value)
        WidgetLanguage.ensure(context, appDrivesCatalog = false)
        assertEquals("ru-RU", L10n.language.value)
        // nothing saved yet: the device language, as the first start would pick it
        context.getSharedPreferences(EcencyGraph.PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        val device = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fr-CA"))
            WidgetLanguage.ensure(context, appDrivesCatalog = false)
            assertEquals("fr-FR", L10n.language.value)
        } finally {
            Locale.setDefault(device)
        }
    }

    @Test
    fun channelsTakeTheirNamesFromTheCatalogAndFollowALanguageChange() {
        val manager = context.getSystemService(NotificationManager::class.java)
        SystemChannels.ensure(manager, SystemChannels.LISTEN)
        assertEquals("Listen", manager.getNotificationChannel(SystemChannels.LISTEN).name)
        assertEquals(L.native.system.listenChannelDescription, manager.getNotificationChannel(SystemChannels.LISTEN).description)
        SystemLanguageSurfaces.follow(context)
        L10n.setLanguage("de-DE")
        // the German catalog's names (native keys stay English until Crowdin translates them; RN dropped post.listen)
        val (name, description) = SystemChannels.texts(SystemChannels.LISTEN)
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.getNotificationChannel(SystemChannels.LISTEN).description != description && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(name, manager.getNotificationChannel(SystemChannels.LISTEN).name)
        assertEquals(description, manager.getNotificationChannel(SystemChannels.LISTEN).description)
        assertNull("a channel never used is not made by a rename", manager.getNotificationChannel(SystemChannels.DICTATION))
    }

    @Test
    fun theDictationNotificationIsInTheAppLanguage() {
        L10n.setLanguage("de-DE")
        val shown = DictationService.notification(context)
        assertEquals(L.native.system.dictationTitle, shown.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(listOf(L.dictation.stop), shown.actions.map { it.title.toString() })
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(L.native.system.dictationChannel, manager.getNotificationChannel(SystemChannels.DICTATION).name)
        assertNotEquals("", manager.getNotificationChannel(SystemChannels.DICTATION).description)
    }
}
