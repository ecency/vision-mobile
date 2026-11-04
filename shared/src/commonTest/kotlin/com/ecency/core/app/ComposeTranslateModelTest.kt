package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN composeTranslateModal's rules over a scripted translation service. */
class ComposeTranslateModelTest {
    private val english = "This is the body of the post and it is long enough for the language to be guessed with the words that are in it."
    private val spanish = "Este es el cuerpo de la entrada y es lo bastante largo para que se pueda adivinar el idioma por las palabras que tiene."

    private inner class World(val scope: TestScope) {
        val asked = mutableListOf<Triple<String, String, String>>()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            app.authBackend.rest = { r -> if (r.url.endsWith("/translate")) answer(r) else null }
            app.authBackend.gate = { r -> if (r.url.endsWith("/translate")) gate?.await() }
            KeyVault(app.store).save(VaultAccount(TestApp.USER, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "t"))
            KeyVault(app.store).setCurrentUsername(TestApp.USER)
            app.env.session.start()
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            val o = Json.parseToJsonElement(r.body!!).jsonObject
            assertEquals("text", o["format"]!!.jsonPrimitive.content)
            assertEquals("", o["api_key"]!!.jsonPrimitive.content)
            asked += Triple(o["q"]!!.jsonPrimitive.content, o["source"]!!.jsonPrimitive.content, o["target"]!!.jsonPrimitive.content)
            if (fail) return HttpResult(500, "{}")
            return HttpResult(200, """{"translatedText":${JsonPrimitive("<" + o["q"]!!.jsonPrimitive.content + ">")}}""")
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun editor(title: String, body: String): EditorModel {
            val m = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            idle()
            m.setTitle(title)
            m.setBody(body, TextSelection(0))
            idle()
            return m
        }

        fun sheet(editor: EditorModel, language: String = "en"): ComposeTranslateModel {
            val m = app.env.composeTranslateModel(editor, language)
            m.start()
            idle()
            return m
        }
    }

    @Test
    fun shortBodiesAreNotTranslated() = runTest {
        val w = World(this)
        val m = w.sheet(w.editor("Title", "Too short"))
        assertTrue(m.current.tooShort)
        assertFalse(m.current.canTranslate)
        m.translate()
        w.idle()
        assertTrue(w.asked.isEmpty())
    }

    @Test
    fun languagePickFollowsRn() = runTest {
        val w = World(this)
        // an English draft goes to the app language, or to Spanish
        assertEquals("en" to "es", w.sheet(w.editor("T", english), "en").current.let { it.source to it.target })
        assertEquals("en" to "de", w.sheet(w.editor("T", english), "de-AT").current.let { it.source to it.target })
        assertEquals("en" to "es", w.sheet(w.editor("T", english), "xx").current.let { it.source to it.target })
        // another language goes to English and says what was detected
        val es = w.sheet(w.editor("T", spanish), "de")
        assertEquals("es" to "en", es.current.source to es.current.target)
        assertEquals("Detected: Spanish", es.current.detectedText)
        assertFalse("es" in es.current.targets)
        // choosing a source resets the target; the target can never be the source
        es.setSource("en")
        assertEquals("de", es.current.target)
        es.setTarget("en")
        assertEquals("de", es.current.target)
        es.setTarget("fr")
        es.setSource("fr")
        assertEquals("fr" to "en", es.current.source to es.current.target)
    }

    @Test
    fun applyAppendsTheTranslationAndMarksTheTitle() = runTest {
        val w = World(this)
        val body = "# Heading\n\n$english\n\n```\ncode\n```"
        val editor = w.editor("My title", body)
        val m = w.sheet(editor)
        assertFalse(m.apply())
        m.translate()
        w.idle()
        assertEquals(listOf("Heading", english), w.asked.map { it.first })
        assertTrue(w.asked.all { it.second == "en" && it.third == "es" })
        val translated = "# <Heading>\n\n<$english>\n\n```\ncode\n```"
        assertEquals(translated, m.current.translated)
        assertTrue(m.apply())
        val expected = "$body\n\n---\n\n## Spanish\n\n$translated"
        assertEquals(expected, editor.current.body)
        assertEquals(TextSelection(expected.length), editor.current.selection)
        assertEquals("My title [EN | ES]", editor.current.title)
    }

    @Test
    fun theTitleMarkerIsOptionalAndMustFit() = runTest {
        val w = World(this)
        val editor = w.editor("My title", english)
        val m = w.sheet(editor)
        m.setAddTitleMarker(false)
        m.translate()
        w.idle()
        assertTrue(m.apply())
        assertEquals("My title", editor.current.title)
        assertNull(ComposeTranslateModel.titleMarker("  ", "en", "es"))
        assertNull(ComposeTranslateModel.titleMarker("x".repeat(241), "en", "es"))
        assertEquals(" [EN | ES]", ComposeTranslateModel.titleMarker("x".repeat(240), "en", "es"))
    }

    @Test
    fun aFailureKeepsNothingAndCanBeTriedAgain() = runTest {
        val w = World(this)
        val editor = w.editor("T", "# One\n\n$english")
        val m = w.sheet(editor)
        w.fail = true
        m.translate()
        w.idle()
        assertTrue(m.current.failed)
        assertEquals("", m.current.translated)
        assertFalse(m.apply())
        assertEquals("# One\n\n$english", editor.current.body)
        w.fail = false
        m.translate()
        w.idle()
        assertFalse(m.current.failed)
        assertTrue(m.current.canApply)
    }

    @Test
    fun aRunForAnotherLanguagePairIsDropped() = runTest {
        val w = World(this)
        val editor = w.editor("T", english)
        val m = w.sheet(editor)
        w.gate = CompletableDeferred()
        m.translate()
        w.idle()
        assertTrue(m.current.translating)
        m.setTarget("fr")
        assertFalse(m.current.translating)
        w.gate!!.complete(Unit)
        w.idle()
        // the answer for Spanish never shows as the French translation
        assertEquals("", m.current.translated)
        assertFalse(m.apply())
        assertEquals(english, editor.current.body)
    }
}
