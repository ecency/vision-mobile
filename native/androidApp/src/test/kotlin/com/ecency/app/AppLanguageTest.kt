package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import com.ecency.app.ui.AppLanguageRoot
import com.ecency.app.ui.FollowAppLanguage
import com.ecency.app.ui.LocalAppLanguage
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Compose language root: a live switch recomposes copy everywhere below it and sets the layout direction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = L10n.setLanguage(L10n.SOURCE)

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun switchTo(id: String) {
        compose.runOnUiThread { L10n.setLanguage(id) }
        compose.waitForIdle()
    }

    /** A sample screen: copy read straight from the catalog in a plain child, a lazy list item and a dialog. */
    private fun sample() = compose.setContent {
        AppLanguageRoot {
            var taps by remember { mutableIntStateOf(0) }
            Column {
                Text(L.settings.language, Modifier.testTag("title"))
                Text("$taps", Modifier.testTag("taps"))
                Text(LocalLayoutDirection.current.name, Modifier.testTag("direction"))
                Text(LocalAppLanguage.current, Modifier.testTag("language"))
                LazyColumn { item { Text(L.settings.language, Modifier.testTag("listItem")) } }
                androidx.compose.material3.Button(onClick = { taps++ }, modifier = Modifier.testTag("button")) { Text("+") }
            }
            // a separate window composes on its own: its content follows through FollowAppLanguage
            Dialog(onDismissRequest = {}) { FollowAppLanguage { Text(L.settings.language, Modifier.testTag("dialog")) } }
        }
    }

    @Test
    fun switchingTheLanguageRecomposesTheScreenAndKeepsItsState() {
        sample()
        assertEquals("Language", text("title"))
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()
        assertEquals("1", text("taps"))

        switchTo("ru-RU")
        assertEquals("Язык", text("title"))
        assertEquals("Язык", text("listItem"))
        assertEquals("Язык", text("dialog"))
        assertEquals("ru-RU", text("language"))
        assertEquals("remembered state survives the switch", "1", text("taps"))

        switchTo("de-DE")
        assertEquals("Sprache", text("title"))
        assertEquals("Sprache", text("listItem"))
    }

    @Test
    fun theSystemPerAppLanguageReadsBackWhatWasWritten() {
        val system = com.ecency.app.ui.AndroidSystemLanguage(compose.activity)
        assertEquals("", system.read())
        system.write(L10n.systemTag("zh-TW"))
        assertEquals("zh-Hant", system.read())
        assertEquals("zh-TW", com.ecency.core.l10n.LocaleMatch.registered(system.read()))
        system.write("")
        assertEquals("", system.read())
    }

    @Test
    fun arabicPersianAndHebrewMirrorTheRootKurdishDoesNot() {
        sample()
        assertEquals("Ltr", text("direction"))
        for (id in listOf("ar-SA", "fa-IR", "he-IL")) {
            switchTo(id)
            assertEquals(id, "Rtl", text("direction"))
        }
        switchTo("ku-TR")
        assertEquals("ku-TR is Latin script Kurmanji", "Ltr", text("direction"))
        switchTo("ar-SA")
        switchTo("en-US")
        assertEquals("Ltr", text("direction"))
    }

    @Test
    fun theAppsWindowsFollowASwitchWithoutAnyCodeOfTheirOwn() {
        compose.setContent {
            AppLanguageRoot {
                com.ecency.app.ui.AlertDialog(
                    onDismissRequest = {}, confirmButton = { Text(L.alert.confirm, Modifier.testTag("confirm")) },
                    title = { Text(L.settings.language, Modifier.testTag("alertTitle")) },
                )
                com.ecency.app.ui.DropdownMenu(expanded = true, onDismissRequest = {}) { Text(L.settings.language, Modifier.testTag("menu")) }
            }
        }
        compose.waitForIdle()
        assertEquals("Language", text("alertTitle"))
        switchTo("ru-RU")
        assertEquals("Язык", text("alertTitle"))
        assertEquals("Язык", text("menu"))
        assertEquals(L.alert.confirm, text("confirm"))
    }

    @Test
    fun aPlainWindowFollowsThroughTheWrapper() {
        // the platform Dialog is skipped on the root's recomposition: only the wrapper's FollowAppLanguage redraws it
        compose.setContent { AppLanguageRoot { com.ecency.app.ui.Dialog(onDismissRequest = {}) { Text(L.settings.language, Modifier.testTag("window")) } } }
        compose.waitForIdle()
        switchTo("ru-RU")
        assertEquals("Язык", text("window"))
    }
}
