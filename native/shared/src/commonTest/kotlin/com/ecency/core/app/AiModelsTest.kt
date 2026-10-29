package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The paid AI features (assist, image) against a scripted Ecency API. What is checked here is the money: one
 * key per intent, no retry by itself, pending answers, and the account that pays.
 */
class AiModelsTest {
    private val user = TestApp.USER
    private val other = "spike-other"
    private val body = "This body is long enough for every action of the writing assistant, since it has well over one hundred characters in it."

    private inner class World(val scope: TestScope, val storage: InMemoryDraftStorage = InMemoryDraftStorage()) {
        /** Every paid request: path and body. */
        val paid = mutableListOf<Pair<String, JsonObject>>()
        val codes = mutableListOf<String>()
        var answers = ArrayDeque<() -> HttpResult>()
        var assistOutput = "Improved text"
        var gate: CompletableDeferred<Unit>? = null
        var pointsDown = false
        var pricesDown = false
        var freeTags = 1
        var keys = 0
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.env.draftStorage = storage
            app.env.imageHost = "https://images.example"
            app.env.idempotencyKey = { "key-${++keys}-abcdef" }
            app.authBackend.privateApi = { r -> answer(r) }
            app.authBackend.gate = { r -> if (r.url.endsWith("/ai-assist") || r.url.endsWith("/ai-generate-image")) gate?.await() }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            val path = r.url.substringAfter("/private-api/")
            val o = Json.parseToJsonElement(r.body ?: "{}").jsonObject
            return when (path) {
                "points" -> if (pointsDown) HttpResult(500, "") else HttpResult(200, """{"points":"1,000.500","unclaimed_points":"0"}""")
                "ai-assist-price" -> if (pricesDown) HttpResult(500, "") else HttpResult(
                    200,
                    """[{"action":"improve","cost":5,"free_remaining":0},{"action":"suggest_tags","cost":2,"free_remaining":$freeTags},{"action":"generate_title","cost":2,"free_remaining":0},""" +
                        """{"action":"summarize","cost":2000,"free_remaining":0},{"action":"check_grammar","cost":"x"},{"action":"translate","cost":1}]""",
                )
                "ai-generate-price" -> if (pricesDown) HttpResult(500, "") else HttpResult(200, """{"prices":[{"aspect_ratio":"1:1","cost":100},{"aspect_ratio":"4:3","cost":150}],"power":[{"power":1,"multiplier":1},{"power":2,"multiplier":2}]}""")
                "ai-assist", "ai-generate-image" -> {
                    paid += path to o
                    codes += o["code"]!!.jsonPrimitive.content
                    answers.removeFirstOrNull()?.invoke() ?: if (path == "ai-assist") HttpResult(200, """{"action":${o["action"]},"output":${Json.encodeToString(assistOutput)},"cost":5}""")
                    else HttpResult(200, """{"url":"https://images.example/ai/one.png","cost":150}""")
                }
                else -> HttpResult(200, "{}")
            }
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun keysSent() = paid.map { it.second["idempotency_key"]!!.jsonPrimitive.content }

        fun editor(text: String = body, title: String = "A title"): EditorModel {
            val m = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            idle()
            m.setTitle(title)
            m.setBody(text, TextSelection(text.length))
            idle()
            return m
        }

        fun assist(editor: EditorModel, action: String? = "improve"): AiAssistModel {
            val m = app.env.aiAssistModel(editor)
            m.start()
            idle()
            action?.let(m::selectAction)
            return m
        }

        fun image(editor: EditorModel, prompt: String? = "A lighthouse at dawn"): AiImageModel {
            val m = app.env.aiImageModel(editor)
            m.start()
            idle()
            prompt?.let(m::setPrompt)
            return m
        }
    }

    private fun down(): HttpResult = throw IllegalStateException("connection lost")

    // ---- the wave composer (RN quick post: dictation and AI assist) ----

    @Test
    fun waveAssistOffersRnsThreeActionsAndItsResultReplacesTheText() = runTest {
        val w = World(this)
        w.idle()
        val composer = w.app.env.waves.composer()
        composer.setText("A wave that is long enough to be improved by the assistant, surely, yes.")
        val m = w.app.env.aiAssistModel(composer)
        m.start()
        w.idle()
        // RN supportedActions: improve, check_grammar, summarize (in RN's ACTIONS order)
        assertEquals(listOf("improve", "summarize", "check_grammar"), m.current.actions)
        m.selectAction("generate_title")
        assertNull(m.current.action, "not offered for a wave")
        m.selectAction("improve")
        m.submit()
        w.idle()
        assertEquals(1, w.paid.size, "one charge through the fake")
        assertEquals(WaveComposeModel.PAID_SCOPE, composer.paidScope)
        val seq = composer.current.prefillSeq
        assertTrue(m.apply())
        assertEquals("Improved text", composer.current.text)
        assertEquals(seq + 1, composer.current.prefillSeq, "the screen's text field takes it")
        assertFalse(composer.applyAiResult("generate_title", "x"), "a title has nowhere to go in a wave")
    }

    @Test
    fun aDictatedTranscriptJoinsTheWaveOnlyForItsOwner() = runTest {
        val w = World(this)
        w.idle()
        val composer = w.app.env.waves.composer()
        assertTrue(composer.insertDictation("Hello"))
        assertTrue(composer.insertDictation("world"))
        composer.setText(composer.current.text + "\n")
        assertTrue(composer.insertDictation("again"))
        assertEquals("Hello world\nagain", composer.current.text)
        assertFalse(composer.insertDictation(""))
        // another account signed in: the paid text does not land in this composer
        w.app.env.session.switchTo(other)
        w.idle()
        assertFalse(composer.insertDictation("late"))
    }

    // ---- what is shown before the charge ----

    @Test
    fun priceAndBalanceComeFromTheServerAndTheButtonStatesTheCost() = runTest {
        val w = World(this)
        val m = w.assist(w.editor(), action = null)
        // unknown actions and rows without a sane cost are dropped
        assertEquals(listOf("improve", "suggest_tags", "generate_title", "summarize"), m.current.prices.map { it.action })
        assertEquals("1,000.500 POINTS", m.current.balanceLabel)
        assertEquals("Run AI Assist", m.current.submitLabel)
        assertFalse(m.current.canSubmit)
        m.selectAction("improve")
        assertEquals("Run AI Assist (5 POINTS)", m.current.submitLabel)
        assertTrue(m.current.canSubmit)
        m.selectAction("suggest_tags")
        assertEquals("Run AI Assist (Free)", m.current.submitLabel)
        m.selectAction("summarize")
        assertFalse(m.current.canSubmit, "2000 points are more than the balance")
        assertEquals("Insufficient points. You need 2000 but have 1000.5.", m.current.insufficientText)
        m.selectAction("check_grammar")
        assertFalse(m.current.canSubmit, "an action without a price from the server cannot be run")
        assertTrue(w.paid.isEmpty())
    }

    @Test
    fun withoutPricesOrBalanceNothingCanBeCharged() = runTest {
        val w = World(this)
        w.pricesDown = true
        w.pointsDown = true
        val a = w.assist(w.editor())
        assertFalse(a.current.canSubmit)
        a.submit()
        val i = w.image(w.editor())
        assertNull(i.current.cost, "no made-up price")
        assertEquals("Generate Image", i.current.generateLabel)
        assertFalse(i.current.canGenerate)
        i.generate()
        w.idle()
        assertTrue(w.paid.isEmpty())
    }

    @Test
    fun tooShortTextCannotBeSent() = runTest {
        val w = World(this)
        val m = w.assist(w.editor("x".repeat(60)))
        assertTrue(m.current.canSubmit, "improve needs 50")
        m.selectAction("generate_title")
        assertFalse(m.current.canSubmit, "titles need 100")
        assertEquals("Minimum 100 characters required", m.current.countText)
    }

    // ---- one run ----

    @Test
    fun aRecapIsCopyOnlyAndNeverReplacesTheBody() = runTest {
        val w = World(this)
        val editor = w.editor()
        assertFalse(AiAssistResult("summarize", "A short summary.").canApply)
        assertTrue(AiAssistResult("improve", "x").canApply)
        assertFalse(editor.applyAiResult("summarize", "A short summary.", emptyList()))
        assertEquals(body, editor.current.body, "RN #3599: the summary replaced the whole post")
        assertTrue(editor.disclosedAiTools.isEmpty())
        val composer = w.app.env.waves.composer()
        composer.setText("A wave that stays as it is.")
        assertFalse(composer.applyAiResult("summarize", "Summary", emptyList()))
        assertEquals("A wave that stays as it is.", composer.current.text)
    }

    @Test
    fun aRunIsSentOnceWithItsKeyAndTheResultReplacesTheBody() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.assist(editor)
        m.submit()
        w.idle()
        assertEquals(1, w.paid.size)
        val sent = w.paid.single().second
        assertEquals(user, sent["us"]!!.jsonPrimitive.content)
        assertEquals("improve", sent["action"]!!.jsonPrimitive.content)
        assertEquals(body, sent["text"]!!.jsonPrimitive.content)
        assertEquals("key-1-abcdef", sent["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals("Improved text", m.current.result?.output)
        // delivered: the stored intent is gone
        assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
        assertTrue(m.apply())
        assertEquals("Improved text", editor.current.body)
        assertEquals(setOf("writing_edit"), editor.disclosedAiTools)
        // RN "Try again" is a new run: a new key
        m.tryAgain()
        m.submit()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.keysSent())
    }

    @Test
    fun titlesAndTagsAreAppliedToTheirFields() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.assist(editor, "generate_title")
        w.assistOutput = "```json\n[\"First title\", \"Second title \", \"First title\"]\n```"
        m.submit()
        w.idle()
        assertEquals(listOf("First title", "Second title"), m.current.result?.titles)
        m.selectTitle(1)
        assertEquals("Second title", m.copyValue)
        assertTrue(m.apply())
        assertEquals("Second title", editor.current.title)
        assertEquals(body, editor.current.body)
        assertTrue(editor.disclosedAiTools.isEmpty(), "a title is not a writing edit")
        m.tryAnother()
        m.selectAction("suggest_tags")
        w.assistOutput = "[\"Photo\", \"travel\", \"photo\", \"Bad Tag!\"]"
        m.submit()
        w.idle()
        assertEquals(listOf("photo", "travel", "bad tag!"), m.current.result?.tags)
        assertEquals("photo, travel, bad tag!", m.copyValue)
        assertTrue(m.apply())
        // through the tag field's own rules: the answer is not trusted
        assertTrue(editor.current.tags.containsAll(listOf("photo", "travel")))
        assertEquals(listOf("photo", "travel", "bad", "tag!"), editor.current.tags)
        assertNotNull(editor.current.tagWarning, "a tag the chain would refuse is flagged, as when it is typed")
    }

    // ---- a lost answer ----

    @Test
    fun aLostAnswerIsNeverSentAgainByItselfAndCheckAgainReusesTheKey() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.assist(editor)
        w.answers += ::down
        m.submit()
        w.idle()
        testScheduler.advanceTimeBy(10 * 60_000)
        w.idle()
        assertEquals(1, w.paid.size, "no retry by itself")
        assertTrue(m.current.unresolved)
        assertEquals(PaidCopy.UNKNOWN, m.current.notice)
        assertEquals("Check again", m.current.submitLabel)
        // the request is fixed while it has no answer
        m.selectAction("summarize")
        m.setText("something else entirely")
        assertEquals("improve", m.current.action)
        assertEquals(body, m.current.text)
        assertNotNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
        m.submit()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.keysSent(), "the same key, never a new one")
        assertEquals(w.paid[0].second - "code", w.paid[1].second - "code")
        assertEquals("Improved text", m.current.result?.output)
        assertFalse(m.current.unresolved)
    }

    @Test
    fun theTransportTimeoutIsResultUnknown() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.gate = CompletableDeferred()
        m.submit()
        // a slow generation is waited for: longer than the gateway's own 120 s
        testScheduler.advanceTimeBy(125_000)
        assertTrue(m.current.running)
        assertFalse(m.current.unresolved)
        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()
        assertFalse(m.current.running)
        assertTrue(m.current.unresolved)
        w.gate = null
        testScheduler.advanceTimeBy(5 * 60_000)
        w.idle()
        assertTrue(w.paid.isEmpty(), "the held request never got an answer, and none followed by itself")
        m.submit()
        w.idle()
        assertEquals(setOf("key-1-abcdef"), w.keysSent().toSet())
    }

    @Test
    fun anUnfinishedRunSurvivesTheAppBeingKilled() = runTest {
        val storage = InMemoryDraftStorage()
        val first = World(this, storage)
        val m = first.assist(first.editor())
        first.answers += ::down
        m.submit()
        first.idle()
        assertTrue(m.current.unresolved)
        // a new process: same device storage, nothing in memory
        val second = World(this, storage)
        second.keys = 50
        val again = second.assist(second.editor("Another text in the editor after the restart, which is not what was paid for the first time around, really."), action = null)
        assertTrue(again.current.unresolved)
        assertEquals(PaidCopy.RESTORED, again.current.notice)
        assertEquals("improve", again.current.action)
        assertEquals(body, again.current.text, "what was asked the first time")
        again.submit()
        second.idle()
        val sent = second.paid.single().second
        assertEquals("key-1-abcdef", sent["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(body, sent["text"]!!.jsonPrimitive.content)
        assertEquals("Improved text", again.current.result?.output)
        assertNull(second.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
    }

    @Test
    fun givingUpDropsTheKeyAndAnOldIntentExpires() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.answers += ::down
        m.submit()
        w.idle()
        m.giveUp()
        w.idle()
        assertFalse(m.current.unresolved)
        assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
        m.submit()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.keysSent())
        // an intent older than the server's replay window is not asked for again: its key would pay anew
        w.answers += ::down
        m.tryAgain()
        m.submit()
        w.idle()
        assertNotNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
        w.app.now += PaidIntent.MAX_AGE_MILLIS + 1
        assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
    }

    @Test
    fun theIntentIsOnDiskBeforeTheRequestLeaves() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.gate = CompletableDeferred()
        m.submit()
        testScheduler.runCurrent()
        // the request is held at the transport: nothing was answered, and the key is already stored
        assertTrue(m.current.running)
        assertTrue(w.paid.isEmpty())
        val stored = w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new")
        assertEquals("key-1-abcdef", stored?.key)
        assertEquals(body, stored?.payload?.get("text")?.jsonPrimitive?.content)
        w.gate!!.complete(Unit)
        w.idle()
        assertEquals("key-1-abcdef", w.keysSent().single())
    }

    @Test
    fun twoEditorsNeverShareOrDeleteEachOthersUnansweredRequest() = runTest {
        val w = World(this)
        val first = w.assist(w.editor())
        w.answers += ::down
        first.submit()
        w.idle()
        assertTrue(first.current.unresolved)
        // a second editor of the same account, on a saved draft: it sees no earlier request, runs and is delivered
        val draft = w.app.env.editorModel(EditorArgs(EditorKind.NEW_POST, draftId = "d7"))
        draft.start()
        w.idle()
        draft.setBody(body, TextSelection(0))
        val second = w.assist(draft)
        assertFalse(second.current.unresolved, "another editor's request is not shown here")
        second.submit()
        w.idle()
        assertEquals("Improved text", second.current.result?.output)
        // the first editor's request is still to be fetched, with its own key
        assertEquals("key-1-abcdef", w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new")?.key)
        first.submit()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef", "key-1-abcdef"), w.keysSent())
        // a record with another key in the same place is not this intent's to delete
        val mine = PaidIntent(AiAssistModel.KIND, user, "key-mine-0001", kotlinx.serialization.json.JsonObject(emptyMap()), w.app.now)
        val theirs = PaidIntent(AiAssistModel.KIND, user, "key-theirs-01", kotlinx.serialization.json.JsonObject(emptyMap()), w.app.now)
        w.app.env.localDrafts.saveRaw(user, "paid:assist:new", theirs.encode())
        w.app.env.paidIntents.finish(mine, "new")
        assertEquals("key-theirs-01", w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new")?.key)
        w.app.env.paidIntents.finish(theirs, "new")
        assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
    }

    @Test
    fun aRecordFromTheFutureIsNotTrustedToBeYoung() = runTest {
        val w = World(this)
        val later = PaidIntent(AiAssistModel.KIND, user, "key-future-01", kotlinx.serialization.json.JsonObject(emptyMap()), w.app.now + 60 * 60 * 1000)
        w.app.env.localDrafts.saveRaw(user, "paid:assist:new", later.encode())
        assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"), "the clock was set back since: its age is unknown")
        assertTrue(PaidIntent.MAX_AGE_MILLIS <= 20L * 60 * 60 * 1000)
    }

    @Test
    fun aFreeLabelThatWentStaleNeverCharges() = runTest {
        val w = World(this)
        val m = w.assist(w.editor(), "suggest_tags")
        assertEquals("Run AI Assist (Free)", m.current.submitLabel)
        // the sheet stays open; the free run is used up elsewhere
        w.app.now += 5 * 60_000
        w.freeTags = 0
        m.submit()
        w.idle()
        assertTrue(w.paid.isEmpty(), "nothing is sent under a label that is no longer true")
        assertEquals("This run is no longer free today. It costs 2 POINTS.", m.current.alert)
        assertEquals("Run AI Assist (2 POINTS)", m.current.submitLabel)
        m.clearAlert()
        m.submit()
        w.idle()
        assertEquals(1, w.paid.size, "the next tap is made knowing the cost")
    }

    @Test
    fun withoutAKnownBalanceNoPaidRunAndNoClaimAboutIt() = runTest {
        val w = World(this)
        w.pointsDown = true
        val m = w.assist(w.editor(), "improve")
        assertNull(m.current.insufficientText, "an unread balance is not a balance of 0")
        assertFalse(m.current.canSubmit)
        m.selectAction("suggest_tags")
        assertTrue(m.current.canSubmit, "a free run needs no balance")
    }

    // ---- pending and refused ----

    @Test
    fun inProgressIsAskedAgainWithTheSameKeyAndOnlySoOften() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        repeat(2) { w.answers += { HttpResult(409, """{"error":"in_progress","retry_after":5}""") } }
        m.submit()
        w.idle()
        assertEquals(3, w.paid.size)
        assertEquals(setOf("key-1-abcdef"), w.keysSent().toSet())
        assertEquals("Improved text", m.current.result?.output)
        // a request that stays pending stops being asked for and waits for the user
        m.tryAgain()
        repeat(40) { w.answers += { HttpResult(409, """{"error":"in_progress","retry_after":"soon"}""") } }
        m.submit()
        w.idle()
        assertEquals(3 + 1 + PaidCalls.MAX_POLLS, w.paid.size)
        assertTrue(m.current.unresolved)
        assertEquals(PaidCopy.PENDING, m.current.notice)
        assertEquals(setOf("key-2-abcdef"), w.keysSent().drop(3).toSet())
        assertNotNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"), "still to be fetched")
    }

    @Test
    fun refusalsEndTheRunAndSayWhy() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        val cases = listOf(
            HttpResult(402, """{"error":"insufficient_points","required":5,"available":1.5}""") to "Insufficient points. You need 5 but have 1.5.",
            HttpResult(422, """{"error":"content_policy"}""") to "Content violates our content policy. Please modify your text. Points were not refunded.",
            HttpResult(429, """{"error":"rate_limited"}""") to "Too many requests. Please wait a moment and try again.",
            HttpResult(500, """{"error":"generation_failed"}""") to "Something went wrong. Please try again.",
            HttpResult(400, "not json") to "Something went wrong. Please try again.",
        )
        cases.forEachIndexed { i, (answer, text) ->
            w.answers += { answer }
            m.submit()
            w.idle()
            assertEquals(text, m.current.alert)
            assertFalse(m.current.unresolved)
            assertNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
            assertEquals(i + 1, w.paid.size, "never sent a second time")
            m.clearAlert()
        }
        assertEquals(cases.size, w.keysSent().toSet().size, "a new run after a refusal has a new key")
    }

    @Test
    fun aBareServerErrorOrAnUnreadableAnswerIsUnknownNotAFailure() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        for (answer in listOf(HttpResult(502, ""), HttpResult(504, "<html>"), HttpResult(200, "garbage"), HttpResult(200, """{"output":42}"""))) {
            w.answers += { answer }
            m.submit()
            w.idle()
            assertTrue(m.current.unresolved, "status ${answer.status}")
            assertNull(m.current.alert)
        }
        assertEquals(setOf("key-1-abcdef"), w.keysSent().toSet())
    }

    @Test
    fun anExpiredSessionKeepsTheRunToBeFetchedLater() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.answers += ::down
        m.submit()
        w.idle()
        w.answers += { HttpResult(401, "Unauthorized") }
        m.submit()
        w.idle()
        assertEquals(PaidCopy.SIGN_IN_AGAIN, m.current.alert)
        assertTrue(m.current.unresolved)
        assertNotNull(w.app.env.paidIntents.load(user, AiAssistModel.KIND, "new"))
    }

    // ---- the account that pays ----

    @Test
    fun afterAnAccountSwitchNothingIsChargedOrInserted() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.assist(editor)
        w.answers += ::down
        m.submit()
        w.idle()
        assertEquals(1, w.paid.size)
        // a result is on screen for the image, and a run without an answer for the assist
        val image = w.image(editor)
        image.generate()
        w.idle()
        assertNotNull(image.current.resultUrl)
        val sentBefore = w.paid.size
        w.app.env.session.switchTo(other)
        w.idle()
        m.submit()
        w.idle()
        image.generateAgain()
        image.generate()
        w.idle()
        assertEquals(sentBefore, w.paid.size, "nothing is sent for the editor's owner while another account is signed in")
        assertEquals(PaidCopy.otherAccount(user), m.current.alert)
        assertTrue(w.codes.all { it == "token-$user" }, "only ever the owner's session")
        // and nothing paid for by the owner goes into the text
        val before = editor.current.body
        assertFalse(editor.applyAiResult("improve", "text from the other account's session"))
        assertFalse(editor.insertAiImage("https://images.example/ai/one.png"))
        assertFalse(editor.insertDictation("spoken"))
        assertEquals(before, editor.current.body)
        // the owner's unfinished run is still there when the owner comes back
        w.app.env.session.switchTo(user)
        w.idle()
        m.clearAlert()
        m.submit()
        w.idle()
        assertEquals("key-1-abcdef", w.keysSent().last())
        assertEquals("Improved text", m.current.result?.output)
    }

    @Test
    fun anAssistAnswerThatArrivesAfterASwitchIsKeptForTheOwner() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.gate = CompletableDeferred()
        m.submit()
        repeat(50) { testScheduler.runCurrent() }
        assertTrue(m.current.running)
        launch { w.app.env.session.switchTo(other) }
        repeat(50) { testScheduler.runCurrent() }
        w.gate!!.complete(Unit)
        repeat(50) { testScheduler.runCurrent() }
        assertNull(m.current.result, "the owner's result is not shown under another account")
        assertTrue(m.current.unresolved)
        w.gate = null
        w.app.env.session.switchTo(user)
        w.idle()
        m.submit()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.keysSent())
        assertEquals("Improved text", m.current.result?.output)
    }

    @Test
    fun anIntentIsNeverReadForAnotherAccount() = runTest {
        val w = World(this)
        val m = w.assist(w.editor())
        w.answers += ::down
        m.submit()
        w.idle()
        assertNull(w.app.env.paidIntents.load(other, AiAssistModel.KIND, "new"))
        // even a record copied under the other account's name is refused: it names its owner
        val raw = w.app.env.localDrafts.loadRaw(user, "paid:assist:new")!!
        w.app.env.localDrafts.saveRaw(other, "paid:assist:new", raw)
        assertNull(w.app.env.paidIntents.load(other, AiAssistModel.KIND, "new"))
        assertNull(PaidIntent.decode(raw.replace("key-1-abcdef", "bad key!"), AiAssistModel.KIND, user))
    }

    // ---- images ----

    @Test
    fun anImageShowsItsCostAndIsInsertedWithItsDisclosure() = runTest {
        val w = World(this)
        val editor = w.editor("Start", title = "My title")
        val m = w.image(editor, prompt = null)
        assertEquals("Use: \"My title\"", m.current.suggestionText)
        assertFalse(m.current.canGenerate, "no prompt")
        m.useSuggestion()
        assertEquals("150 POINTS", m.current.costLabel)
        assertEquals("1,000.500 POINTS", m.current.balanceLabel, "the same format as the assist sheet")
        assertEquals("Generate Image (150 POINTS)", m.current.generateLabel)
        m.setAspectRatio("1:1")
        m.setPower(2)
        assertEquals("Generate Image (200 POINTS)", m.current.generateLabel)
        m.setPower(7)
        assertEquals(2, m.current.power, "only a tier the server offers")
        m.generate()
        w.idle()
        val sent = w.paid.single().second
        assertEquals("My title", sent["prompt"]!!.jsonPrimitive.content)
        assertEquals("1:1", sent["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("2", sent["power"]!!.jsonPrimitive.content)
        assertEquals("https://images.example/ai/one.png", m.current.resultUrl)
        assertTrue(m.insert())
        assertTrue("![](https://images.example/ai/one.png)" in editor.current.body)
        assertEquals(setOf("media_generation"), editor.disclosedAiTools)
    }

    @Test
    fun anImagesIntentIsOnDiskBeforeItsRequestLeaves() = runTest {
        val w = World(this)
        // from an editor, and opened on its own: each in its own place
        val inEditor = w.image(w.editor())
        w.gate = CompletableDeferred()
        inEditor.setAspectRatio("1:1")
        inEditor.generate()
        testScheduler.runCurrent()
        assertTrue(inEditor.current.running)
        assertTrue(w.paid.isEmpty(), "held at the transport: nothing was answered")
        val stored = w.app.env.paidIntents.load(user, AiImageModel.KIND, "new")
        assertEquals("key-1-abcdef", stored?.key, "the key is stored before the paid request is out")
        assertEquals("A lighthouse at dawn", stored?.payload?.get("prompt")?.jsonPrimitive?.content)
        assertEquals("1:1", stored?.payload?.get("aspect_ratio")?.jsonPrimitive?.content)

        val alone = w.app.env.aiImageScreenModel()
        alone.start()
        testScheduler.runCurrent()
        alone.setPrompt("A harbour at night")
        alone.generate()
        testScheduler.runCurrent()
        assertTrue(alone.current.running)
        assertTrue(w.paid.isEmpty())
        val standalone = w.app.env.paidIntents.load(user, AiImageModel.KIND, AiImageModel.STANDALONE_SCOPE)
        assertEquals("key-2-abcdef", standalone?.key)
        assertEquals("A harbour at night", standalone?.payload?.get("prompt")?.jsonPrimitive?.content)

        w.gate!!.complete(Unit)
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.keysSent(), "each request went out once, with the key that was stored for it")
        assertTrue(w.paid.all { it.first == "ai-generate-image" })
    }

    @Test
    fun anImageIsWaitedForLongerThanTheGatewayAndThenIsResultUnknown() = runTest {
        val w = World(this)
        val m = w.image(w.editor())
        w.gate = CompletableDeferred()
        m.generate()
        // a slow generation is waited for: longer than the gateway's own 120 s
        testScheduler.advanceTimeBy(129_000)
        testScheduler.runCurrent()
        assertTrue(m.current.running, "still waiting at 129 s")
        assertFalse(m.current.unresolved)
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertFalse(m.current.running, "given up at 130 s")
        assertTrue(m.current.unresolved, "the charge is unknown, not refused")
        assertNull(m.current.resultUrl)
        assertEquals("key-1-abcdef", w.app.env.paidIntents.load(user, AiImageModel.KIND, "new")?.key, "the intent stays for Check again")
        w.gate = null
        testScheduler.advanceTimeBy(5 * 60_000)
        w.idle()
        assertTrue(w.paid.isEmpty(), "the held request never got an answer, and none followed by itself")
        m.generate()
        w.idle()
        assertEquals(listOf("key-1-abcdef"), w.keysSent(), "asked again with the same key: one image, one charge")
        assertEquals("https://images.example/ai/one.png", m.current.resultUrl)
    }

    @Test
    fun aPaidImageThatIsNotDeliveredYetIsFetchedWithTheSameKey() = runTest {
        val w = World(this)
        val m = w.image(w.editor())
        w.answers += { HttpResult(202, """{"error":"delivery_pending","generation_id":"7","retry_after":5}""") }
        w.answers += { HttpResult(202, "") }
        m.generate()
        w.idle()
        assertEquals(3, w.paid.size)
        assertEquals(setOf("key-1-abcdef"), w.keysSent().toSet(), "one image, one key, one charge")
        assertEquals("https://images.example/ai/one.png", m.current.resultUrl)
    }

    @Test
    fun anAddressThatIsNotOnTheImageHostIsNeverShownOrInserted() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.image(editor)
        for (url in listOf("https://evil.example/x.png", "http://images.example/x.png", "https://images.example/x.png)![](https://evil.example/y.png", "javascript:alert(1)")) {
            w.answers += { HttpResult(200, """{"url":${Json.encodeToString(url)}}""") }
            m.generate()
            w.idle()
            assertNull(m.current.resultUrl, url)
            assertFalse(m.insert())
            assertFalse(editor.insertAiImage(url))
        }
        assertEquals(body, editor.current.body)
        assertEquals(setOf("key-1-abcdef"), w.keysSent().toSet())
    }

    @Test
    fun theGeneratorOpenedOnItsOwnPaysForTheSignedInAccountAndHasNothingToInsertInto() = runTest {
        val w = World(this)
        val m = w.app.env.aiImageScreenModel()
        m.start()
        w.idle()
        assertEquals(user, m.current.owner)
        assertFalse(m.current.canInsert)
        assertNull(m.current.suggestionText)
        m.setPrompt("A lighthouse")
        assertEquals("Generate Image (150 POINTS)", m.current.generateLabel)
        // its unanswered request waits in its own place, apart from any editor's
        w.answers += ::down
        m.generate()
        w.idle()
        assertTrue(m.current.unresolved)
        assertEquals("key-1-abcdef", w.app.env.paidIntents.load(user, AiImageModel.KIND, AiImageModel.STANDALONE_SCOPE)?.key)
        assertNull(w.app.env.paidIntents.load(user, AiImageModel.KIND, "new"))
        assertFalse(w.image(w.editor()).current.unresolved, "an editor does not see the screen's request")
        // the screen opened again (or after a restart) finds it, and fetches it with the same key
        val again = w.app.env.aiImageScreenModel()
        again.start()
        w.idle()
        assertTrue(again.current.unresolved)
        assertEquals("A lighthouse", again.current.prompt)
        // not while another account is signed in
        w.app.env.session.switchTo(other)
        w.idle()
        again.generate()
        w.idle()
        assertEquals(1, w.paid.size)
        assertEquals(PaidCopy.otherAccount(user), again.current.alert)
        w.app.env.session.switchTo(user)
        w.idle()
        again.clearAlert()
        again.generate()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.keysSent())
        assertEquals("https://images.example/ai/one.png", again.current.resultUrl)
        assertFalse(again.insert(), "no editor: the image can be shared, as in RN")
        // the route is a real screen now, needs an account, and survives a restart as itself
        val screen = AppScreen.of(RouteName.AI_IMAGE_GENERATOR, "")
        assertEquals(AppScreen.AiImageGenerator, screen)
        assertTrue(RouteName.AI_IMAGE_GENERATOR.built && RouteName.AI_IMAGE_GENERATOR.requiresLogin)
    }

    // ---- reading answers ----

    @Test
    fun answersAreClassifiedByWhatTheySayAboutTheCharge() {
        fun c(status: Int, text: String) = PaidCalls.classify(HttpResult(status, text)) { it.toString() }
        assertTrue(c(200, "{}") is PaidOutcome.Delivered)
        assertEquals(PaidOutcome.Pending(5), c(409, "{}"))
        assertEquals(PaidOutcome.Pending(60), c(202, """{"retry_after":86400}"""))
        assertEquals(PaidOutcome.Pending(2), c(409, """{"retry_after":0}"""))
        assertEquals(PaidOutcome.Refused(402, "insufficient_points", 5.0, 1.0), c(402, """{"error":"insufficient_points","required":5,"available":1}"""))
        assertEquals(PaidOutcome.Refused(402, null, null, null), c(402, """{"required":"NaN","available":-3}"""))
        assertEquals(PaidOutcome.Refused(503, "ai_unavailable"), c(503, """{"error":"ai_unavailable"}"""))
        assertEquals(PaidOutcome.Refused(500, "transcription_failed"), c(500, """{"error":"transcription_failed"}"""))
        assertEquals(PaidOutcome.Unknown, c(500, """{"error":"something_else"}"""))
        assertEquals(PaidOutcome.Unknown, c(502, ""))
        assertEquals(PaidOutcome.Unknown, c(301, ""))
        assertEquals(1000.5, PaidCalls.points("1,000.500"))
        assertNull(PaidCalls.points("lots"))
        assertTrue(PaidIntent.isValidKey("0123456789abcdef0123456789abcdef"))
        assertFalse(PaidIntent.isValidKey("short"))
    }

    @Test
    fun theDefaultKeyIsOneTheServerAccepts() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        val a = app.env.idempotencyKey()
        val b = app.env.idempotencyKey()
        assertTrue(PaidIntent.isValidKey(a))
        assertEquals(32, a.length)
        assertTrue(a != b)
    }

    @Test
    fun aBalanceIsGroupedWithThreeDecimalsOnEveryPaidSheet() {
        assertEquals("1,250.500", PaidCalls.balance(1250.5))
        assertEquals("0.000", PaidCalls.balance(0.0))
        assertEquals("999.999", PaidCalls.balance(999.999))
        assertEquals("1,000.000", PaidCalls.balance(1000.0))
        assertEquals("12,345,678.120", PaidCalls.balance(12345678.12))
        assertEquals("0.570", PaidCalls.balance(0.57))
        assertEquals("1.001", PaidCalls.balance(1.0016), "cut, never more than is held")
        assertEquals("0.999", PaidCalls.balance(0.9999))
        assertEquals("1,250.500", PaidCalls.balance("1250.5"))
        assertEquals("1,250.500", PaidCalls.balance("1,250.500"))
        assertEquals("500.000", PaidCalls.balance("500"))
        assertEquals("n/a", PaidCalls.balance("n/a"), "not a number: as it came")
    }

    @Test
    fun resultsAreReadLikeRn() {
        assertEquals("[1]", AiAssistResult.stripFence("```JSON\n[1]\n```  "))
        assertEquals("plain", AiAssistResult.stripFence("plain"))
        assertEquals(listOf("a", "b"), AiAssistResult.of("generate_title", "```\n[\"a\",\" b \",\"\"]\n```").titles)
        assertTrue(AiAssistResult.of("generate_title", "[\"a\", 2]").titles.isEmpty(), "not all strings: plain text")
        assertTrue(AiAssistResult.of("improve", "[\"a\"]").titles.isEmpty())
        assertEquals(listOf("x"), AiAssistResult.of("suggest_tags", "[\"X\",\"x \"]").tags)
    }
}
