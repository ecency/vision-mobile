package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.editor.BeneficiaryRoute
import com.ecency.core.editor.EditorFormats
import com.ecency.core.editor.EditorMeta
import com.ecency.core.editor.EditorTags
import com.ecency.core.editor.PollDraft
import com.ecency.core.editor.RewardType
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor, drafts, uploads and snippets models against a scripted node, Ecency API and image host. */
class EditorModelTest {
    private val user = TestApp.USER
    private val other = "spike-other"

    /** A scripted world: posts on chain, drafts on the server, and switches for failures. */
    private inner class World(scope: TestScope) {
        val posts = mutableMapOf<String, String>()
        val drafts = mutableListOf<JsonObject>()
        val schedules = mutableListOf<JsonObject>()
        val api = mutableListOf<Pair<String, JsonObject>>()
        var broadcastAnswer: suspend (Sent) -> HttpResult = { FakeTransport.result(it, "{}") }
        var supportPercent = 0
        var apiDown = false
        var postingAuths = """[["ecency.app",1]]"""
        var accountsDown = false

        /** Keys ("author/permlink") the chain has but hivemind has not indexed yet. */
        val hivemindLags = mutableSetOf<String>()

        /** From the second get_accounts answer on, the posting authority lists this extra account. */
        var secondReadAdds: String? = null

        /** The posting account auths by read number (0 based), for reads that differ in more than names. */
        var postingAuthsByRead: ((Int) -> String)? = null
        var accountReads = 0

        /** The node accepted [req]: what the chain holds follows the first operation, json_metadata as sent. */
        fun land(req: Sent) {
            val op = req.params.jsonArray.single().jsonObject["operations"]!!.jsonArray[0].jsonArray[1].jsonObject
            val link = op["permlink"]!!.jsonPrimitive.content
            posts["$user/$link"] = JsonObject(
                op + mapOf(
                    "category" to op.getValue("parent_permlink"), "created" to kotlinx.serialization.json.JsonPrimitive("2026-10-06T12:00:00"),
                    "updated" to kotlinx.serialization.json.JsonPrimitive("2026-10-06T12:00:00"), "depth" to kotlinx.serialization.json.JsonPrimitive(if (op["parent_author"]!!.jsonPrimitive.content.isEmpty()) 0 else 1),
                ),
            ).toString()
        }
        var stripped = false
        val storage = InMemoryDraftStorage()

        /** Holds the editor's RC read (the last part of its load) until completed. */
        var rcGate: CompletableDeferred<Unit>? = null

        val app = TestApp(scope) { req ->
            when (req.method) {
                "rc_api.find_rc_accounts" -> {
                    rcGate?.await()
                    if (rcGate != null) FakeTransport.result(req, """{"rc_accounts":[]}""") else TestApp.chain(req) ?: HttpResult(500, "")
                }
                "rc_api.get_rc_stats" -> if (rcGate != null) FakeTransport.result(req, """{"rc_stats":{}}""") else HttpResult(500, "")
                "condenser_api.broadcast_transaction" -> broadcastAnswer(req)
                "bridge.get_post" -> {
                    val p = req.params.jsonObject
                    val k = "${p["author"]!!.jsonPrimitive.content}/${p["permlink"]!!.jsonPrimitive.content}"
                    posts[k]?.takeIf { k !in hivemindLags }?.let { FakeTransport.result(req, it) } ?: FakeTransport.result(req, "null")
                }
                "condenser_api.get_content" -> {
                    val p = req.params.jsonArray
                    val a = p[0].jsonPrimitive.content
                    val l = p[1].jsonPrimitive.content
                    posts["$a/$l"]?.let { FakeTransport.result(req, it) } ?: FakeTransport.result(req, """{"author":"","permlink":""}""")
                }
                "condenser_api.get_accounts" -> if (accountsDown) HttpResult(500, "") else if (stripped) FakeTransport.result(
                    req, """[{"name":"$user","posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${GoldenVectors.PUBLIC_KEY}",1]]},"memo_key":"${GoldenVectors.PUBLIC_KEY}","json_metadata":""}]""",
                ) else FakeTransport.result(
                    req,
                    """[{"name":"$user","posting":{"weight_threshold":1,"account_auths":${postingAuthsByRead?.let { it(accountReads++) } ?: (if (accountReads++ > 0 && secondReadAdds != null) """[["$secondReadAdds",1]]""" else postingAuths)},"key_auths":[["${GoldenVectors.PUBLIC_KEY}",1]]},"active":{"weight_threshold":1,"account_auths":[],"key_auths":[["${GoldenVectors.PUBLIC_KEY}",1]]},"owner":{"weight_threshold":1,"account_auths":[],"key_auths":[["${GoldenVectors.PUBLIC_KEY}",1]]},"memo_key":"${GoldenVectors.PUBLIC_KEY}","json_metadata":"{\"legacy\":true}"}]""",
                )
                "condenser_api.lookup_accounts" -> FakeTransport.result(req, """["bob","bobby","carol"]""")
                "bridge.get_community" -> FakeTransport.result(req, """{"name":"hive-125125","title":"Ecency","type_id":1,"context":{"role":"guest","subscribed":true}}""")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }

        init {
            app.env.draftStorage = storage
            app.env.imageHost = "https://images.example"
            app.env.randomString = { "rnd1" }
            app.authBackend.privateApi = { r -> answer(r) }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
            // the lock's settings read (no PIN): nothing private is sent while they are unread
            app.env.appLock.start()
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            if (apiDown) return HttpResult(503, "{}")
            val path = r.url.substringAfter("/private-api/")
            val body = Json.parseToJsonElement(r.body ?: "{}").jsonObject
            api += path to body
            fun wrapped(items: List<JsonObject>) = HttpResult(200, """{"data":${JsonArray(items)},"pagination":{"total":${items.size},"limit":20,"offset":0,"has_next":false}}""")
            return when {
                path.startsWith("drafts?") -> wrapped(drafts)
                path == "drafts" -> HttpResult(200, JsonArray(drafts).toString())
                path == "drafts-add" -> {
                    val d = Json.parseToJsonElement("""{"_id":"d${drafts.size + 1}","modified":"2026-10-06T11:00:00.000Z","created":"2026-10-06T11:00:00.000Z"}""").jsonObject
                    val full = JsonObject(d + body.filterKeys { it != "code" })
                    drafts += full
                    HttpResult(200, """{"drafts":[$full]}""")
                }
                path == "drafts-update" -> HttpResult(200, "{}")
                path == "drafts-delete" -> {
                    drafts.removeAll { it["_id"]!!.jsonPrimitive.content == body["id"]!!.jsonPrimitive.content }
                    HttpResult(200, "{}")
                }
                path.startsWith("schedules?") -> wrapped(schedules)
                path == "schedules-add" -> HttpResult(200, "{}")
                path == "schedules-delete" || path == "schedules-move" -> {
                    schedules.removeAll { it["_id"]!!.jsonPrimitive.content == body["id"]!!.jsonPrimitive.content }
                    HttpResult(200, "{}")
                }
                path == "support-settings" -> HttpResult(200, """{"beneficiary_percent":$supportPercent,"curation_percent":0}""")
                path.startsWith("images?") -> wrapped(emptyList())
                path.startsWith("fragments?") -> wrapped(emptyList())
                else -> HttpResult(200, "{}")
            }
        }

        /** The points reports (`usr-activity`): type and transaction id. */
        fun activities() = api.filter { it.first == "usr-activity" }.map { it.second["ty"]!!.jsonPrimitive.content.toInt() to it.second["tx"]?.jsonPrimitive?.content }

        fun ops() = app.broadcasts().map { it.params.jsonArray.single().jsonObject["operations"]!!.jsonArray }

        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()

        /** A started new post editor with a title, a body and two tags. */
        fun newPost(scope: TestScope, args: EditorArgs = EditorArgs(EditorKind.NEW_POST)): EditorModel {
            val m = app.env.editorModel(args)
            m.start()
            scope.testScheduler.advanceUntilIdle()
            return m
        }

        fun fill(m: EditorModel, scope: TestScope, title: String = "My first post", body: String = "Hello world") {
            m.setTitle(title)
            m.setBody(body, TextSelection(body.length))
            m.setTagInput("ecency test ")
            scope.testScheduler.advanceUntilIdle()
        }
    }

    private fun entry(author: String, permlink: String, title: String, body: String, extra: String = "") =
        """{"author":"$author","permlink":"$permlink","category":"ecency","parent_permlink":"ecency","title":${Json.encodeToString(title)},"body":${Json.encodeToString(body)},"json_metadata":{"tags":["ecency","old"],"app":"peakd/1","format":"markdown"},"created":"2026-10-01T00:00:00","updated":"2026-10-01T00:00:00","depth":0$extra}"""

    private fun opNames(ops: JsonArray) = ops.map { it.jsonArray[0].jsonPrimitive.content }

    private fun field(ops: JsonArray, index: Int, name: String) = ops[index].jsonArray[1].jsonObject[name]!!.jsonPrimitive.content

    // ---- new post ----

    @Test
    fun aNewPostGoesOutAsCommentAndCommentOptionsAndClearsTheLocalDraft() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        assertTrue(w.storage.values.containsKey(LocalDrafts.key(user, LocalDrafts.NEW_POST)), "typed text is on the device 300 ms later")
        assertEquals(listOf("ecency", "test"), m.current.tags)
        assertTrue(m.current.canSubmit)
        m.setReward(RewardType.POWER_UP)
        m.submit()
        testScheduler.advanceUntilIdle()
        val ops = w.ops().single()
        assertEquals(listOf("comment", "comment_options"), opNames(ops))
        assertEquals("my-first-post", field(ops, 0, "permlink"))
        assertEquals("ecency", field(ops, 0, "parent_permlink"))
        assertEquals("", field(ops, 0, "parent_author"))
        assertEquals("0", field(ops, 1, "percent_hbd"))
        val meta = Json.parseToJsonElement(field(ops, 0, "json_metadata")).jsonObject
        assertEquals(listOf("links", "type", "tags", "app", "format"), meta.keys.toList())
        assertEquals(EditorDone.Published(user, "my-first-post", null), m.current.done)
        assertEquals(listOf(100), w.activities().map { it.first }, "a new post reports a post (SDK useComment)")
        assertEquals(40, w.activities().single().second?.length)
        assertFalse(w.storage.values.containsKey(LocalDrafts.key(user, LocalDrafts.NEW_POST)), "published: no ghost draft")
        assertEquals(listOf("Success! Content submitted!"), w.toasts())
        // a late autosave or leaving must not bring the post back as a draft, locally or on the server
        m.setBody("Hello world!", TextSelection(0))
        m.leave()
        testScheduler.advanceUntilIdle()
        assertTrue(w.storage.values.isEmpty())
        assertTrue(w.api.none { it.first == "drafts-add" })
    }

    @Test
    fun aTakenPermlinkGetsRnsRandomSuffix() = runTest {
        val w = World(this)
        w.posts["$user/my-first-post"] = entry(user, "my-first-post", "Older", "older body")
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("my-first-post-rnd1", field(w.ops().single(), 0, "permlink"))
    }

    @Test
    fun aFailedPublishKeepsEverythingAndTheRetrySendsTheSameTransaction() = runTest {
        val w = World(this)
        w.broadcastAnswer = { throw IllegalStateException("connection reset") }
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.done)
        assertFalse(m.current.isSending)
        assertEquals("My first post", m.current.title)
        assertEquals("Hello world", m.current.body)
        assertTrue(w.toasts().single().startsWith("Something went wrong\n"))
        val saved = LocalDraft.decode(w.storage.values[LocalDrafts.key(user, LocalDrafts.NEW_POST)])!!
        assertEquals("my-first-post", saved.pending?.permlink, "the permlink that may be on chain is remembered on disk")
        val sentBefore = w.app.broadcasts().map { it.params.toString() }.distinct()
        // the node answers again and the post did not land: the same signed transaction goes out
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(sentBefore.single(), w.app.broadcasts().last().params.toString(), "same transaction, so the chain can never hold it twice")
        assertEquals(EditorDone.Published(user, "my-first-post", null), m.current.done)
    }

    @Test
    fun aPublishWhoseAnswerWasLostIsNotPostedAgain() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        // the node takes the transaction, the answer never arrives
        w.broadcastAnswer = { req ->
            w.land(req)
            throw IllegalStateException("timeout")
        }
        m.submit()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.done)
        val sent = w.app.broadcasts().size
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(sent, w.app.broadcasts().size, "the post is on chain already: nothing is sent, and no second permlink is made")
        assertEquals(EditorDone.Published(user, "my-first-post", null), m.current.done)
    }

    /** Publishes "My first post" on a node that takes the transaction and loses the answer. */
    private fun lostAnswer(w: World, scope: TestScope): EditorModel {
        val m = w.newPost(scope)
        w.fill(m, scope)
        w.broadcastAnswer = { req ->
            w.land(req)
            throw IllegalStateException("timeout")
        }
        m.submit()
        scope.testScheduler.advanceUntilIdle()
        w.broadcastAnswer = { req ->
            // what the node holds follows what it accepts
            w.land(req)
            FakeTransport.result(req, "{}")
        }
        return m
    }

    @Test
    fun aTypoFixAfterALostAnswerAsksAndUpdatesThePostWhenChosen() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        m.setTitle("A better title")
        m.setBody("Hello world, fixed", TextSelection(0))
        val sent = w.app.broadcasts().size
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict, "already published and changed: the user is asked")
        assertEquals(sent, w.app.broadcasts().size, "nothing is sent before the answer")
        assertNull(m.current.done)
        m.resolvePublishConflict(update = true)
        testScheduler.advanceUntilIdle()
        val ops = w.ops().last()
        assertEquals(listOf("comment"), opNames(ops), "comment_options is create-only on chain")
        assertEquals("my-first-post", field(ops, 0, "permlink"))
        assertEquals("A better title", field(ops, 0, "title"))
        assertEquals(EditorDone.Published(user, "my-first-post", null), m.current.done)
    }

    @Test
    fun aTypoFixAfterALostAnswerCanGoOutAsANewPostInstead() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        m.setBody("Hello world, fixed", TextSelection(0))
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict)
        m.dismissPublishConflict()
        assertFalse(m.current.publishConflict)
        assertEquals("Hello world, fixed", m.current.body, "closing the question keeps everything")
        m.submit()
        testScheduler.advanceUntilIdle()
        m.resolvePublishConflict(update = false)
        testScheduler.advanceUntilIdle()
        val ops = w.ops().last()
        assertEquals(listOf("comment", "comment_options"), opNames(ops))
        assertEquals("my-first-post-rnd1", field(ops, 0, "permlink"), "a new post gets a new permlink")
        assertTrue(w.posts.getValue("$user/my-first-post").contains("\"body\":\"Hello world\""), "the first post is untouched")
    }

    @Test
    fun clearingTheEditorAfterALostAnswerNeverOverwritesThePublishedPost() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        // the user sees the post on their profile, clears the editor and writes something unrelated
        m.clear()
        testScheduler.advanceUntilIdle()
        w.fill(m, this, title = "Holiday photos", body = "Unrelated content")
        m.submit()
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.publishConflict)
        val ops = w.ops().last()
        assertEquals(listOf("comment", "comment_options"), opNames(ops))
        assertEquals("holiday-photos", field(ops, 0, "permlink"))
        assertTrue(w.posts.getValue("$user/my-first-post").contains("\"body\":\"Hello world\""), "the first post still holds its own text")
        assertEquals(EditorDone.Published(user, "holiday-photos", null), m.current.done)
    }

    @Test
    fun retypedTextAfterProcessDeathNeverSilentlyEditsThePublishedPost() = runTest {
        val w = World(this)
        lostAnswer(w, this).close()
        testScheduler.advanceUntilIdle()
        // a new process restores the draft with its pending publish; the user replaces the text
        val again = w.newPost(this)
        assertEquals("My first post", again.current.title)
        again.setTitle("Holiday photos")
        again.setBody("Unrelated content", TextSelection(0))
        val sent = w.app.broadcasts().size
        again.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(again.current.publishConflict, "asked, never edited silently")
        assertEquals(sent, w.app.broadcasts().size)
        again.resolvePublishConflict(update = false)
        testScheduler.advanceUntilIdle()
        assertEquals("holiday-photos", field(w.ops().last(), 0, "permlink"))
        assertTrue(w.posts.getValue("$user/my-first-post").contains("\"body\":\"Hello world\""))
    }

    @Test
    fun aPendingPostThatWasChangedElsewhereAsksInsteadOfMakingADuplicate() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        // the post that landed is edited from another device: the chain no longer holds anything sent from here
        w.posts["$user/my-first-post"] = entry(user, "my-first-post", "My first post", "edited on the web")
        val sent = w.app.broadcasts().size
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict, "asked: neither a silent duplicate nor a silent overwrite")
        assertTrue(m.current.conflictChangedElsewhere)
        assertEquals(sent, w.app.broadcasts().size)
        m.resolvePublishConflict(update = false)
        testScheduler.advanceUntilIdle()
        assertEquals("my-first-post-rnd1", field(w.ops().last(), 0, "permlink"))
        assertTrue(w.posts.getValue("$user/my-first-post").contains("edited on the web"))
    }

    @Test
    fun publishAsNewNeverLandsOnThePendingPermlinkWhileHivemindLags() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        // hivemind has not indexed the post yet; the title (and so the slug) is unchanged
        w.hivemindLags += "$user/my-first-post"
        m.setBody("Hello world, another take", TextSelection(0))
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict)
        m.resolvePublishConflict(update = false)
        testScheduler.advanceUntilIdle()
        val ops = w.ops().last()
        assertEquals("my-first-post-rnd1", field(ops, 0, "permlink"), "never the permlink of the post the user declined to change")
        assertEquals(listOf("comment", "comment_options"), opNames(ops))
        assertTrue(w.posts.getValue("$user/my-first-post").contains("\"body\":\"Hello world\""))
    }

    @Test
    fun aTakenPermlinkIsDecidedByTheChainNotByHivemind() = runTest {
        val w = World(this)
        w.posts["$user/my-first-post"] = entry(user, "my-first-post", "Older", "older body")
        w.hivemindLags += "$user/my-first-post"
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("my-first-post-rnd1", field(w.ops().single(), 0, "permlink"))
    }

    @Test
    fun aTagsOnlyChangeAfterALostAnswerIsNotDroppedSilently() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        m.setTagInput("extra ")
        val sent = w.app.broadcasts().size
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict, "the tags differ from what is on chain: asked, not counted as published")
        assertFalse(m.current.conflictChangedElsewhere)
        assertEquals(sent, w.app.broadcasts().size)
        m.resolvePublishConflict(update = true)
        testScheduler.advanceUntilIdle()
        val meta = Json.parseToJsonElement(field(w.ops().last(), 0, "json_metadata")).jsonObject
        assertEquals(listOf("ecency", "test", "extra"), meta["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("my-first-post", field(w.ops().last(), 0, "permlink"))
    }

    @Test
    fun aResendWithChangedTextThatLostTheRaceToTheFirstAttemptIsReported() = runTest {
        val w = World(this)
        w.broadcastAnswer = { throw IllegalStateException("timeout") } // sent, not (yet) on chain
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        val first = w.app.broadcasts().last()
        // within the landing window the user fixes the text and retries: the same permlink goes out again
        m.setBody("Hello world, fixed", TextSelection(0))
        w.broadcastAnswer = { req ->
            w.land(first) // the node took this one, and then the first attempt after it
            FakeTransport.result(req, "{}")
        }
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("my-first-post", field(w.ops().last(), 0, "permlink"))
        assertNull(m.current.done, "the chain holds the older text: not reported as published")
        assertTrue(m.current.publishConflict)
        assertTrue(w.toasts().any { it.contains("earlier attempt") })
    }

    @Test
    fun aClockSteppingBackwardsKeepsThePublishPending() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        m.setBody("Hello world, changed", TextSelection(0))
        w.app.now -= 3 * 24 * 60 * 60 * 1000L
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict, "a negative age is not 'older than a day'")
    }

    @Test
    fun aPendingPublishOlderThanADayIsForgotten() = runTest {
        val w = World(this)
        val m = lostAnswer(w, this)
        m.setBody("Hello world, much later", TextSelection(0))
        w.app.now += PendingPublish.MAX_AGE_MILLIS + 1
        m.submit()
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.publishConflict)
        assertEquals("my-first-post-rnd1", field(w.ops().last(), 0, "permlink"))
    }

    @Test
    fun thePendingPermlinkSurvivesProcessDeath() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        w.broadcastAnswer = { req ->
            w.land(req)
            throw IllegalStateException("timeout")
        }
        m.submit()
        testScheduler.advanceUntilIdle()
        m.close()
        // a new process: a fresh model over the same storage
        val again = w.newPost(this)
        assertEquals("My first post", again.current.title)
        assertEquals("Hello world", again.current.body)
        assertEquals(listOf("ecency", "test"), again.current.tags)
        val sent = w.app.broadcasts().size
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        again.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(sent, w.app.broadcasts().size)
        assertEquals(EditorDone.Published(user, "my-first-post", null), again.current.done)
    }

    // ---- account ownership ----

    @Test
    fun anEditorNeverPublishesAsAnotherAccount() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        assertEquals(other, w.app.env.session.current.username)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "nothing is broadcast after an account switch")
        assertNull(m.current.done)
        assertEquals(user, m.current.owner)
        assertTrue(w.toasts().any { it.contains("@$user") })
        // leaving saves the draft for the account that wrote it, with that account's code
        m.leave()
        testScheduler.advanceUntilIdle()
        val add = w.api.last { it.first == "drafts-add" }.second
        assertEquals("token-$user", add["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLocalDraftIsNeverShownToAnotherAccount() = runTest {
        val w = World(this)
        w.apiDown = true // the server save on leaving fails, so the draft stays on the device
        val m = w.newPost(this)
        w.fill(m, this, title = "Private thoughts", body = "for my eyes only")
        m.leave()
        testScheduler.advanceUntilIdle()
        m.close()
        assertTrue(w.storage.values.keys.all { it.startsWith("draft/$user/") })
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        val theirs = w.newPost(this)
        assertEquals(other, theirs.current.owner)
        assertEquals("", theirs.current.title)
        assertEquals("", theirs.current.body)
        // and it is back for its owner
        w.app.env.session.switchTo(user)
        testScheduler.advanceUntilIdle()
        val mine = w.newPost(this)
        assertEquals("Private thoughts", mine.current.title)
        assertEquals("for my eyes only", mine.current.body)
    }

    // ---- edits ----

    @Test
    fun anEditSendsThePatchKeepsTheOldAppAndNeverSendsOptions() = runTest {
        val w = World(this)
        val old = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        w.posts["$user/golden-post"] = entry(user, "golden-post", "Old title", old)
        val m = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "golden-post"))
        assertEquals(EditorKind.EDIT_POST, m.current.kind)
        assertEquals("Old title", m.current.title)
        assertEquals(listOf("ecency", "old"), m.current.tags)
        assertFalse(m.leavingLosesChanges)
        val edited = old + "One more sentence."
        m.setBody(edited, TextSelection(edited.length))
        assertTrue(m.leavingLosesChanges)
        m.submit()
        testScheduler.advanceUntilIdle()
        val ops = w.ops().single()
        assertEquals(listOf("comment"), opNames(ops))
        assertTrue(field(ops, 0, "body").startsWith("@@ -"), "a patch, since it is shorter than the old body")
        assertEquals("ecency", field(ops, 0, "parent_permlink"))
        val meta = Json.parseToJsonElement(field(ops, 0, "json_metadata")).jsonObject
        assertEquals("peakd/1", meta["app"]!!.jsonPrimitive.content, "an edit keeps the app the post was written with")
        assertEquals(EditorDone.Edited(com.ecency.core.api.model.PostKey(user, "golden-post")), m.current.done)
        assertTrue(w.activities().isEmpty(), "an edit earns nothing")
    }

    @Test
    fun anEditOfSomeoneElsesPostIsRefusedAndAReplyEditHasNoTitle() = runTest {
        val w = World(this)
        w.posts["alice/theirs"] = entry("alice", "theirs", "Theirs", "body")
        val m = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, "alice", "theirs"))
        assertNotNull(m.current.error)
        assertFalse(m.current.canSubmit)

        w.posts["$user/re-alice-1"] = entry(user, "re-alice-1", "", "my reply", ""","parent_author":"alice"""").replace("\"depth\":0", "\"depth\":1").replace("\"parent_permlink\":\"ecency\"", "\"parent_permlink\":\"theirs\"")
        val r = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "re-alice-1"))
        assertEquals(EditorKind.EDIT_REPLY, r.current.kind)
        r.setBody("my reply, edited", TextSelection(0))
        assertTrue(r.current.canSubmit, "a reply needs no title")
        r.submit()
        testScheduler.advanceUntilIdle()
        val ops = w.ops().single()
        assertEquals(listOf("comment"), opNames(ops))
        assertEquals("alice", field(ops, 0, "parent_author"))
        assertEquals("theirs", field(ops, 0, "parent_permlink"))
        assertEquals("", field(ops, 0, "title"))
        assertTrue(w.activities().isEmpty(), "a reply edit earns nothing")
    }

    // ---- replies ----

    @Test
    fun aReplyFromTheEditorKeepsItsPermlinkAcrossARetry() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val m = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        assertEquals("root body", m.current.parentSummary)
        m.setBody("Nice post https://img.io/a.png", TextSelection(0))
        w.broadcastAnswer = { throw IllegalStateException("offline") }
        m.submit()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.done)
        val first = w.ops().last()
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        m.submit()
        testScheduler.advanceUntilIdle()
        val second = w.ops().last()
        assertEquals(listOf("comment"), opNames(second))
        assertEquals(field(first, 0, "permlink"), field(second, 0, "permlink"))
        assertEquals("root", field(second, 0, "parent_permlink"))
        val meta = Json.parseToJsonElement(field(second, 0, "json_metadata")).jsonObject
        assertEquals("comment", meta["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("ecency", "old"), meta["tags"]!!.jsonArray.map { it.jsonPrimitive.content }, "the parent's tags")
        assertTrue(m.current.done is EditorDone.Replied)
        assertEquals(listOf(110), w.activities().map { it.first }, "a new reply reports a comment, once (the failed send reported nothing)")
        assertEquals(40, w.activities().single().second?.length)
        assertTrue(w.storage.values.isEmpty())
    }

    @Test
    fun theReplyAccountTileHandsTheReplyToTheAccountSwitchedTo() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val args = EditorArgs(EditorKind.REPLY, "alice", "root")
        val m = w.newPost(this, args)
        m.setBody("Half a reply", TextSelection(12))
        val handoff = w.app.env.replyHandoff
        handoff.begin(m)
        assertTrue(handoff.current.askAccounts, "RN changeUser: the accounts sheet")
        handoff.sheetShown()
        assertFalse(handoff.current.askAccounts)
        testScheduler.advanceUntilIdle()
        // the switch: the editor goes with the other gated screens, then comes back for the new account
        val before = NavState().push(AppScreen.Post("alice", "root")).push(AppScreen.Editor(args))
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        val after = handoff.reopen(before.afterAccountChange(), other)
        assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "root"), AppScreen.Editor(args)), after.screens)
        val next = w.newPost(this, args)
        assertEquals(other, next.current.owner)
        assertEquals("Half a reply", next.current.body, "the text carries over")
        // the first account's draft stays its own
        assertEquals("Half a reply", w.app.env.localDrafts.load(user, LocalDrafts.reply("alice", "root"))?.body)
        // used once: a later change reopens nothing
        assertEquals(NavState(), handoff.reopen(NavState(), user))
    }

    @Test
    fun aCarriedReplyStaysWithTheReopenedEditorThroughAFailedLoadAndNoOtherEditorTakesIt() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val args = EditorArgs(EditorKind.REPLY, "alice", "root")
        val m = w.newPost(this, args)
        m.setBody("Half a reply", TextSelection(12))
        w.app.env.replyHandoff.begin(m)
        testScheduler.advanceUntilIdle()
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        w.app.env.replyHandoff.reopen(NavState(), other)
        // the parent cannot be read for a moment: the reopened editor fails to load
        w.posts.remove("alice/root")
        w.app.env.api.posts.invalidate(com.ecency.core.api.model.PostKey("alice", "root"))
        val reopened = w.newPost(this, args)
        assertNotNull(reopened.current.error)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        // no other editor can take the text, even for the same reply
        val other = w.newPost(this, args)
        assertEquals("", other.current.body, "the first account's text stays its own draft")
        // the reopened editor itself still has it when its load is retried
        reopened.retryLoad()
        testScheduler.advanceUntilIdle()
        assertNull(reopened.current.error)
        assertEquals("Half a reply", reopened.current.body)
    }

    @Test
    fun aRetriedLoadIsTheEditorsAccountsAndNeverRunsNextToTheFailedOne() = runTest {
        val w = World(this)
        val args = EditorArgs(EditorKind.REPLY, "alice", "root")
        val gate = CompletableDeferred<Unit>()
        w.rcGate = gate
        val m = w.app.env.editorModel(args)
        m.start()
        testScheduler.runCurrent() // the parent is missing: the load fails, its RC read is still held
        assertNotNull(m.current.error)
        assertFalse(m.current.loading)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        m.retryLoad()
        testScheduler.runCurrent()
        assertNull(m.current.error)
        assertEquals("root body", m.current.parentSummary)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // the failed load was cancelled: only the retry went on to its second RC read
        assertEquals(1, w.app.transport.sent.count { it.method == "rc_api.get_rc_stats" })
    }

    @Test
    fun aRetriedLoadIsRefusedForAnotherAccount() = runTest {
        val w = World(this)
        val m = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        assertNotNull(m.current.error)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        m.retryLoad()
        testScheduler.advanceUntilIdle()
        assertNotNull(m.current.error, "the editor belongs to the first account: nothing loads for the second")
        assertEquals(null, m.current.parentSummary)
    }

    @Test
    fun theTileHandsNothingOverWithoutASwitchOrForAnEdit() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val m = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        m.setBody("Text", TextSelection(4))
        val handoff = w.app.env.replyHandoff
        handoff.begin(m)
        // the sheet closed and the same account stays (or signs out): nothing comes back
        assertEquals(NavState(), handoff.reopen(NavState(), user))
        handoff.begin(m)
        assertEquals(NavState(), handoff.reopen(NavState(), null))
        // too late to count as the tile's switch
        handoff.begin(m)
        w.app.now += ReplyAccountHandoff.WINDOW_MILLIS + 1
        assertEquals(NavState(), handoff.reopen(NavState(), other))
        // a new post has no tile
        val post = w.newPost(this)
        handoff.sheetShown()
        handoff.begin(post)
        assertFalse(handoff.current.askAccounts)
    }

    @Test
    fun theQuickReplySheetAndTheEditorShareOneReplyDraft() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        testScheduler.advanceUntilIdle()
        val parent = w.app.env.api.posts.getPost("alice", "root")!!
        val quick = w.app.env.replyModel(parent.key, parent)
        quick.setText("half a thought")
        testScheduler.advanceUntilIdle()
        val m = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        assertEquals("half a thought", m.current.body)
    }

    // ---- fields ----

    @Test
    fun tagsFollowRnsRules() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        m.setTagInput("Photo,")
        assertEquals(listOf("photo"), m.current.tags)
        m.setTagInput("#bees photo hive-125125 ")
        assertEquals(listOf("hive-125125", "photo", "bees"), m.current.tags, "the community tag goes first")
        assertEquals(listOf(EditorTags.DUPLICATE, EditorTags.COMMUNITY_SELECTED), w.toasts())
        testScheduler.advanceUntilIdle()
        assertEquals("Ecency", m.current.community?.title)
        m.setTagInput("un")
        assertEquals("un", m.current.tagInput)
        m.endTagEditing()
        assertEquals("un", m.current.tags.last())
        m.setTagInput("a-b-c ")
        assertEquals("Use one dash in each tag", m.current.tagWarning)
        m.removeTag("a-b-c")
        assertNull(m.current.tagWarning)
        m.setTagInput((1..8).joinToString(" ") { "t$it" } + " ")
        assertEquals("Only 10 tags allowed, remove some", m.current.tagWarning)
        m.setTitle("T")
        m.setBody("B", TextSelection(1))
        assertFalse(m.current.isFormValid, "more than 10 tags")
        m.removeTag("t8")
        m.removeTag("t7")
        assertTrue(m.current.isFormValid)
        // "My Blog" drops the community tag
        m.selectCommunity(null)
        assertFalse(m.current.tags.contains("hive-125125"))
    }

    @Test
    fun aDuplicatePastedAfterTheFieldClearedToastsOnce() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        m.setTagInput("a ")
        assertEquals(emptyList(), w.toasts())
        // the field shows the cleared text: the next "a " is the user's, a real duplicate
        m.tagFieldShown("")
        m.setTagInput("a ")
        assertEquals(listOf(EditorTags.DUPLICATE), w.toasts())
        assertEquals("", m.current.tagInput)
        // a field still showing old text (not yet cleared) does not reset the guard
        m.setTagInput("b ")
        m.tagFieldShown("b ")
        m.setTagInput("b c")
        assertEquals("c", m.current.tagInput)
        assertEquals(emptyList(), w.toasts())
    }

    @Test
    fun aTagThatBecameAChipLeavesTheFieldAndIsNeverParsedAgain() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        val before = m.current.tags
        m.setTagInput("a ")
        assertEquals("", m.current.tagInput)
        // the field still showed "a " when "b" was typed (it applies the cleared text a frame later)
        m.setTagInput("a b")
        assertEquals("b", m.current.tagInput)
        m.setTagInput("b ")
        assertEquals(before + listOf("a", "b"), m.current.tags)
        assertEquals(emptyList(), w.toasts(), "no duplicate toast for normal typing")
        // a removed tag pasted again right away is a new tag, not stale text
        m.removeTag("a")
        m.setTagInput("a ")
        assertEquals(before + listOf("b", "a"), m.current.tags)
        // a real duplicate: one toast, nothing added, the field cleared
        m.setTagInput("b")
        m.setTagInput("b ")
        assertEquals(listOf(EditorTags.DUPLICATE), w.toasts())
        assertEquals(before + listOf("b", "a"), m.current.tags)
        assertEquals("", m.current.tagInput)
        m.setTagInput("lastone")
        m.endTagEditing()
        assertEquals("lastone", m.current.tags.last())
        assertEquals("", m.current.tagInput)
    }

    @Test
    fun toolbarActionsEditAtTheSelection() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        m.setBody("hello world", TextSelection(6, 11))
        val revision = m.current.bodyRevision
        m.bold()
        assertEquals("hello **world**", m.current.body)
        assertEquals(TextSelection(15), m.current.selection)
        assertEquals(revision + 1, m.current.bodyRevision, "the field takes the model's text over")
        assertFalse(m.insertLink("x", "not a link", EditorFormats.LinkType.PLAIN))
        assertTrue(m.insertLink("Ecency", "https://ecency.com", EditorFormats.LinkType.PLAIN))
        assertEquals("hello **world**[Ecency](https://ecency.com)", m.current.body)
        m.setBody("hi @bo", TextSelection(6))
        assertEquals("bo", m.mentionQuery)
        m.insertUsername("bob")
        assertEquals("hi @bob ", m.current.body)
        assertEquals(2, m.current.wordsCount)
        m.clear()
        testScheduler.advanceUntilIdle()
        assertEquals("", m.current.body)
        assertTrue(w.storage.values.isEmpty())
    }

    // ---- beneficiaries ----

    @Test
    fun beneficiariesAndTheEcencySupportRoute() = runTest {
        val w = World(this)
        w.supportPercent = 5
        val m = w.newPost(this)
        w.fill(m, this)
        // untouched list: the saved support percent is injected at publish
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("""[[0,{"beneficiaries":[{"account":"ecency","weight":500}]}]]""", w.ops().single()[1].jsonArray[1].jsonObject["extensions"].toString())

        val w2 = World(this)
        w2.supportPercent = 5
        val m2 = w2.newPost(this)
        w2.fill(m2, this)
        assertEquals("Invalid username, please check and try again", m2.addBeneficiary("nobody", 10))
        assertEquals("Invalid percent", m2.addBeneficiary("bob", 0))
        assertEquals("Invalid percent", m2.addBeneficiary("bob", 101))
        assertNull(m2.addBeneficiary("@Bob", 10))
        assertEquals("Beneficiary exists already", m2.addBeneficiary("bob", 5))
        assertEquals("Beneficiary exists already", m2.addBeneficiary(user, 5))
        assertEquals(9000, m2.current.authorWeight)
        m2.removeBeneficiary("bob")
        assertTrue(m2.current.beneficiariesExplicit, "an emptied list is the author's choice")
        m2.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("[]", w2.ops().single()[1].jsonArray[1].jsonObject["extensions"].toString(), "an explicit list is published as is: no injection")
    }

    @Test
    fun aSlowSupportSettingsLookupNeverBlocksPublishing() = runTest {
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        val m = w.newPost(this)
        w.fill(m, this)
        w.app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/support-settings")) throw IllegalStateException("hangs") else null }
        m.submit()
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertEquals("[]", w.ops().single()[1].jsonArray[1].jsonObject["extensions"].toString())
        assertNotNull(m.current.done)
    }

    // ---- drafts ----

    @Test
    fun publishWaitsForADraftSaveInFlight() = runTest {
        // RN #3527: a draft save that lands after the publish leaves a stray server draft
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        w.app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/drafts-add")) gate.await() }
        val m = w.newPost(this)
        w.fill(m, this)
        m.saveDraft()
        // the request timeout runs on virtual time: only run what is due now while the answer is held back
        repeat(5) { testScheduler.runCurrent() }
        assertTrue(m.current.isDraftSaving)
        assertFalse(m.current.canSubmit)
        // review L9: the button says why it waits
        assertEquals(SAVING_DRAFT, m.current.submitLabel)
        m.submit()
        repeat(5) { testScheduler.runCurrent() }
        assertTrue(w.ops().isEmpty(), "nothing published while the save is in flight")
        assertFalse(m.current.isSending)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.isDraftSaving)
        assertTrue(m.current.canSubmit)
        assertEquals("Publish", m.current.submitLabel)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(1, w.ops().size)
        assertEquals(1, w.api.count { it.first == "drafts-add" })
    }

    @Test
    fun leavingWhileADraftSaveRunsMakesNoSecondDraft() = runTest {
        // review M4: leave() started its own save next to the running one: two server drafts
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        w.app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/drafts-add")) gate.await() }
        val m = w.newPost(this)
        w.fill(m, this)
        m.saveDraft()
        repeat(5) { testScheduler.runCurrent() }
        assertTrue(m.current.isDraftSaving)
        m.leave()
        repeat(5) { testScheduler.runCurrent() }
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        m.close()
        assertEquals(1, w.api.count { it.first == "drafts-add" }, "one draft")
        assertTrue(w.api.none { it.first == "drafts-update" }, "nothing changed after the save")
    }

    @Test
    fun leavingAfterEditsWhileADraftSaveRunsUpdatesThatDraft() = runTest {
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        w.app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/drafts-add")) gate.await() }
        val m = w.newPost(this)
        w.fill(m, this)
        m.saveDraft()
        repeat(5) { testScheduler.runCurrent() }
        m.setBody("Hello world, more", TextSelection(17))
        m.leave()
        repeat(5) { testScheduler.runCurrent() }
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        m.close()
        assertEquals(1, w.api.count { it.first == "drafts-add" })
        val update = w.api.single { it.first == "drafts-update" }.second
        assertEquals("Hello world, more", update["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun leavingAfterAMetaOnlyChangeWhileADraftSaveRunsUpdatesThatDraft() = runTest {
        // delta review 2: a description, reward or tag change is part of the draft too
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        w.app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/drafts-add")) gate.await() }
        val m = w.newPost(this)
        w.fill(m, this)
        m.saveDraft()
        repeat(5) { testScheduler.runCurrent() }
        m.setDescription("a new summary")
        m.leave()
        repeat(5) { testScheduler.runCurrent() }
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        m.close()
        assertEquals(1, w.api.count { it.first == "drafts-add" })
        val update = w.api.single { it.first == "drafts-update" }.second
        assertEquals("a new summary", update["meta"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDraftSaveThatFailsAfterALogoutSaysNothing() = runTest {
        // delta review 3: "Failed to save draft" landed on the next screen after a logout
        val w = World(this)
        val gate = CompletableDeferred<Unit>()
        w.app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/drafts-add")) { gate.await(); throw IllegalStateException("gone") } }
        val m = w.newPost(this)
        w.fill(m, this)
        w.toasts()
        m.saveDraft()
        repeat(5) { testScheduler.runCurrent() }
        w.app.env.session.logout(user)
        repeat(5) { testScheduler.runCurrent() }
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue("Failed to save draft" !in w.toasts())
        m.close()
    }

    @Test
    fun leavingSavesAServerDraftAndOpeningItLoadsItsMeta() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        m.setReward(RewardType.DECLINE)
        m.setDescription("short one")
        m.setPoll(PollDraft(title = "Q?", choices = listOf("a", "b"), endTime = "2026-10-19T12:00:00.000Z"))
        assertNull(m.addBeneficiary("bob", 10))
        m.leave()
        testScheduler.advanceUntilIdle()
        m.close()
        val add = w.api.single { it.first == "drafts-add" }.second
        assertEquals("ecency test", add["tags"]!!.jsonPrimitive.content, "tags as RN's space-joined string")
        val meta = add["meta"]!!.jsonObject
        assertEquals("dp", meta["rewardType"]!!.jsonPrimitive.content)
        assertEquals("short one", meta["description"]!!.jsonPrimitive.content)
        assertEquals("Q?", meta["poll"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("""[{"account":"bob","weight":1000}]""", meta["beneficiaries"].toString())
        assertEquals(listOf("Draft Saved"), w.toasts())
        assertTrue(w.storage.values.isEmpty(), "the id-less local copy became the server draft")

        val opened = w.newPost(this, EditorArgs(EditorKind.NEW_POST, draftId = "d1"))
        assertEquals("My first post", opened.current.title)
        assertEquals(RewardType.DECLINE, opened.current.reward)
        assertEquals("short one", opened.current.description)
        assertEquals("Q?", opened.current.poll?.title)
        assertEquals(1000, opened.current.beneficiaries.single().weight)
        assertTrue(opened.current.beneficiariesExplicit)
        // publishing from a saved draft reports its id, so the UI can offer to delete it
        opened.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(EditorDone.Published(user, "my-first-post", "d1"), opened.current.done)
        assertEquals("poll", Json.parseToJsonElement(field(w.ops().single(), 0, "json_metadata")).jsonObject["content_type"]!!.jsonPrimitive.content)
        opened.deletePublishedDraft()
        testScheduler.advanceUntilIdle()
        assertTrue(w.drafts.isEmpty())
    }

    @Test
    fun aNewerLocalCopyWinsOverTheServerDraftAndDeadPlaceholdersAreSwept() = runTest {
        val w = World(this)
        w.drafts += Json.parseToJsonElement("""{"_id":"d7","title":"Server","body":"server body","tags":"a b","modified":"2026-10-06T11:00:00.000Z","meta":{}}""").jsonObject
        LocalDrafts(w.storage, w.app.env.dispatchers).save(
            user, LocalDrafts.draft("d7"),
            LocalDraft(title = "Local", body = "typed offline\n![](Uploading... a.jpg)\nmore", tags = listOf("a"), updated = TestApp.NOW, caret = 5),
        )
        val m = w.newPost(this, EditorArgs(EditorKind.NEW_POST, draftId = "d7"))
        assertEquals("Local", m.current.title)
        assertEquals("typed offline\nmore", m.current.body)
        assertEquals(TextSelection(5), m.current.selection, "the caret is restored")
    }

    @Test
    fun anEmptyEditorOffersTheMostRecentDraftButNeverATemplate() = runTest {
        val w = World(this)
        w.drafts += Json.parseToJsonElement("""{"_id":"t1","title":"Template","body":"scaffold","tags":"","modified":"2026-10-06T11:30:00.000Z","meta":{"postTemplate":true,"templateName":"Weekly"}}""").jsonObject
        w.drafts += Json.parseToJsonElement("""{"_id":"d1","title":"Older","body":"older","tags":"","modified":"2026-10-01T11:00:00.000Z","meta":{}}""").jsonObject
        w.drafts += Json.parseToJsonElement("""{"_id":"d2","title":"Newest","body":"newest","tags_arr":["x"],"modified":"2026-10-05T11:00:00.000Z","meta":{}}""").jsonObject
        val m = w.newPost(this)
        assertTrue(m.current.recentDraftAvailable)
        m.loadRecentDraft()
        testScheduler.advanceUntilIdle()
        assertEquals("Newest", m.current.title)
        assertEquals("d2", m.current.draftId)
        // a template starts a new post: no draft id, so saving never overwrites the template
        val fromTemplate = w.newPost(this, EditorArgs(EditorKind.NEW_POST, templateId = "t1"))
        assertEquals("scaffold", fromTemplate.current.body)
        assertNull(fromTemplate.current.draftId)
    }

    @Test
    fun draftsScreenListsDeletesClonesAndMovesSchedules() = runTest {
        val w = World(this)
        w.drafts += Json.parseToJsonElement("""{"_id":"t1","title":"Template","body":"scaffold","tags":"","modified":"2026-10-06T11:30:00.000Z","meta":{"postTemplate":true,"templateName":"Weekly"}}""").jsonObject
        w.drafts += Json.parseToJsonElement("""{"_id":"d1","title":"Draft one","body":"b","tags":"a","modified":"2026-10-01T11:00:00.000Z","meta":{}}""").jsonObject
        w.schedules += Json.parseToJsonElement("""{"_id":"s1","title":"Later","body":"b","permlink":"later","tags_arr":["a"],"schedule":"2026-11-01T10:00:00+00:00","status":1}""").jsonObject
        LocalDrafts(w.storage, w.app.env.dispatchers).save(user, LocalDrafts.NEW_POST, LocalDraft(title = "Unsynced", updated = TestApp.NOW))
        val m = w.app.env.draftsModel(0)
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Draft one"), m.current.drafts.map { it.title })
        assertEquals(listOf("Weekly"), m.current.templates.map { it.templateName })
        assertEquals("Pending", m.current.schedules.single().statusLabel)
        assertEquals("Unsynced", m.current.localDraft?.title)
        m.cloneDraft("d1")
        testScheduler.advanceUntilIdle()
        assertEquals("Copy of Draft one", w.api.last { it.first == "drafts-add" }.second["title"]!!.jsonPrimitive.content)
        assertEquals(2, m.current.drafts.size)
        m.setTab(1)
        m.moveScheduleToDrafts("s1")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.schedules.isEmpty())
        assertEquals(listOf("Moved to draft"), w.toasts())
        m.setTab(0)
        m.current.drafts.forEach { m.toggleSelected(it.id) }
        assertTrue(m.current.hasSelection)
        m.deleteSelected()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.drafts.isEmpty())
        assertEquals(listOf("Success!"), w.toasts())
        // another account sees none of it
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        assertEquals(other, m.current.username)
        assertNull(m.current.localDraft)
    }

    @Test
    fun schedulingSendsTheOptionsAndNeedsThePostingAuthority() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        assertEquals("Schedule", m.current.submitLabel)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "a schedule is not a broadcast")
        val add = w.api.single { it.first == "schedules-add" }.second
        assertEquals("my-first-post", add["permlink"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T10:00:00+02:00", add["schedule"]!!.jsonPrimitive.content)
        assertEquals("false", add["reblog"]!!.jsonPrimitive.content)
        assertEquals("1000000.000 HBD", add["options"]!!.jsonObject["max_accepted_payout"]!!.jsonPrimitive.content)
        assertEquals(EditorDone.Scheduled, m.current.done)

        // without the authority nothing is changed before the user said yes
        val w2 = World(this)
        w2.postingAuths = "[]"
        val m2 = w2.newPost(this)
        w2.fill(m2, this)
        m2.setSchedule("2026-11-01T10:00:00+02:00")
        m2.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m2.current.grantPrompt)
        assertTrue(w2.app.broadcasts().isEmpty())
        assertTrue(w2.api.none { it.first == "schedules-add" })
        // yes: a posting-key account is asked for its active key (auth upgrade) before anything is signed
        m2.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        val prompt = w2.app.env.signing.current.prompt as SigningPrompt.AuthUpgrade
        assertEquals(com.ecency.core.hive.KeyRole.ACTIVE, prompt.authority)
        assertEquals("account_update", prompt.operation)
        assertTrue(w2.app.broadcasts().isEmpty())
        w2.app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertNull(m2.current.done)
        assertTrue(w2.api.none { it.first == "schedules-add" }, "no grant, no schedule")
    }

    @Test
    fun theGrantKeepsEveryExistingAuthorityAndThenTheScheduleGoesOut() = runTest {
        val w = World(this)
        w.postingAuths = """[["peakd.app",1]]"""
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = GoldenVectors.WIF, activeKey = GoldenVectors.WIF, accessToken = "token-$user"))
        w.broadcastAnswer = { req ->
            w.postingAuths = """[["ecency.app",1],["peakd.app",1]]"""
            FakeTransport.result(req, "{}")
        }
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        m.submit()
        testScheduler.advanceUntilIdle()
        m.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        val op = w.ops().single().single().jsonArray
        assertEquals("account_update", op[0].jsonPrimitive.content)
        assertEquals(
            """{"account":"$user","posting":{"weight_threshold":1,"account_auths":[["ecency.app",1],["peakd.app",1]],"key_auths":[["${GoldenVectors.PUBLIC_KEY}",1]]},"memo_key":"${GoldenVectors.PUBLIC_KEY}","json_metadata":"{\"legacy\":true}"}""",
            op[1].toString(),
        )
        assertEquals(1, w.api.count { it.first == "schedules-add" })
        assertEquals(EditorDone.Scheduled, m.current.done)
    }

    @Test
    fun theGrantIsRefusedWhenTheRowLacksTheAccountsOwnPostingKeyOrTwoReadsDisagree() = runTest {
        // (a) a key account whose own posting key is not in the authority a node returned
        val w = World(this)
        w.postingAuths = "[]"
        val otherKey = com.ecency.core.hive.PrivateKey.fromSeed("ecency-native-spike-other-posting-key").toWif()
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = otherKey, activeKey = GoldenVectors.WIF, accessToken = "token-$user"))
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        m.submit()
        testScheduler.advanceUntilIdle()
        m.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty())
        assertTrue(w.toasts().single().contains("does not contain your own posting key"))

        // (b) the row read for the grant differs from the authority read a moment before
        val w2 = World(this)
        w2.postingAuths = "[]"
        KeyVault(w2.app.store).save(VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = GoldenVectors.WIF, activeKey = GoldenVectors.WIF, accessToken = "token-$user"))
        val m2 = w2.newPost(this)
        w2.fill(m2, this)
        m2.setSchedule("2026-11-01T10:00:00+02:00")
        m2.submit()
        testScheduler.advanceUntilIdle()
        // from here: the authority check reads the account as it is, the row for the grant comes back different
        w2.secondReadAdds = "someone.else"
        w2.accountReads = 0
        m2.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        assertTrue(w2.app.broadcasts().isEmpty())
        assertTrue(w2.toasts().single().contains("disagree"))
        assertNull(m2.current.done)
    }

    @Test
    fun theGrantIsRefusedWhenTwoReadsAgreeOnNamesButNotOnWeights() = runTest {
        val w = World(this)
        // the same account auth in every read, its weight different from one read to the next
        w.postingAuthsByRead = { n -> """[["peakd.app",${1 + n % 2}]]""" }
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = GoldenVectors.WIF, activeKey = GoldenVectors.WIF, accessToken = "token-$user"))
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        m.submit()
        testScheduler.advanceUntilIdle()
        m.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "no authority is rewritten from reads that disagree")
        assertTrue(w.toasts().single().contains("disagree"))
        assertEquals(0, w.api.count { it.first == "schedules-add" })
    }

    @Test
    fun theGrantFingerprintCoversWeightsThresholdAndMemoWhateverTheOrder() {
        fun row(threshold: Int = 1, keys: String = """[["STM1",1],["STM2",1]]""", accounts: String = """[["a",1],["b",2]]""", memo: String = "STMmemo") =
            Json.parseToJsonElement("""{"name":"x","posting":{"weight_threshold":$threshold,"account_auths":$accounts,"key_auths":$keys},"memo_key":"$memo"}""")
        val grant = com.ecency.core.api.broadcast.PostingGrant
        val base = grant.fingerprint(row())
        assertNotNull(base)
        assertEquals(base, grant.fingerprint(row(keys = """[["STM2",1],["STM1",1]]""", accounts = """[["b",2],["a",1]]""")), "a node's order does not matter")
        assertTrue(base != grant.fingerprint(row(threshold = 2)))
        assertTrue(base != grant.fingerprint(row(keys = """[["STM1",2],["STM2",1]]""")), "a key's weight")
        assertTrue(base != grant.fingerprint(row(accounts = """[["a",1],["b",1]]""")), "an account's weight")
        assertTrue(base != grant.fingerprint(row(memo = "STMother")))
        assertTrue(base != grant.fingerprint(row(accounts = """[["a",1]]""")))
        assertNull(grant.fingerprint(Json.parseToJsonElement("""{"name":"x"}""")))
        assertNull(grant.fingerprint(null))
    }

    @Test
    fun aTitleWhosePermlinksAreAllTakenIsRefusedInsteadOfPublishedOverAnExistingPost() = runTest {
        val w = World(this)
        // the slug and every regenerated permlink (the test's random suffix is constant) exist on chain already
        w.posts["$user/my-first-post"] = entry(user, "my-first-post", "Old", "old body")
        w.posts["$user/my-first-post-rnd1"] = entry(user, "my-first-post-rnd1", "Older", "older body")
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "nothing is sent under a permlink that belongs to another post")
        assertNull(m.current.done)
        assertTrue((w.toasts() + listOfNotNull(m.current.error?.message)).any { "new link" in it }, "the user is told to change the title")
    }

    @Test
    fun aStrippedAccountReadRefusesTheGrant() = runTest {
        val w = World(this)
        w.postingAuths = "[]"
        w.stripped = true
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = GoldenVectors.WIF, activeKey = GoldenVectors.WIF, accessToken = "token-$user"))
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        m.submit()
        testScheduler.advanceUntilIdle()
        m.confirmPostingGrant()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "a row without an owner authority is not a complete account: nothing is signed")
        assertTrue(w.toasts().single().contains("Could not read your account's current authorities"))
        assertNull(m.current.done)
    }

    // ---- uploads ----

    private class FakeImageHost : ImageUploadTransport {
        val requests = mutableListOf<Triple<String, String, String>>()
        var answers = ArrayDeque<() -> HttpResult>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun upload(url: String, authorization: String, file: com.ecency.core.api.ecency.UploadFile, onProgress: (Long, Long) -> Unit): HttpResult {
            requests += Triple(url, authorization, file.name)
            onProgress(file.bytes.size.toLong() / 2, file.bytes.size.toLong())
            gate?.await()
            return (answers.removeFirstOrNull() ?: { HttpResult(200, """{"url":"https://images.example/${file.name}"}""") })()
        }
    }

    private fun image(name: String, size: Int = 10) = PickedImage(ByteArray(size), name, "image/jpeg", 1200, 800)

    @Test
    fun anUploadIsSignedLikeRnInsertsAPlaceholderAndThenTheImage() = runTest {
        val w = World(this)
        val host = FakeImageHost()
        host.gate = CompletableDeferred()
        w.app.env.imageTransport = host
        w.app.env.imageHost = "https://images.example"
        val m = w.newPost(this)
        m.setTitle("With image")
        m.setBody("before after", TextSelection(7))
        val uploads = w.app.env.uploadsModel(m)
        uploads.upload(listOf(image("a.jpg")), insert = true)
        testScheduler.runCurrent() // the host has not answered yet (advancing time would run into the 120 s timeout)
        assertEquals("before \n![](Uploading... a.jpg)\nafter", m.current.body)
        assertEquals(1, m.current.uploading)
        assertFalse(m.current.canSubmit, "publishing waits for uploads")
        assertEquals(0.5f, uploads.current.progress)
        host.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("before \n![](https://images.example/a.jpg)\nafter", m.current.body)
        assertEquals(0, m.current.uploading)
        assertNull(uploads.current.progress)
        val (url, authorization, _) = host.requests.single()
        assertEquals("https://images.example", authorization)
        val token = url.removePrefix("https://images.example/hs/")
        assertEquals(HiveSignerMessages.imageUploadToken(user, com.ecency.core.hive.PrivateKey.fromWif(GoldenVectors.WIF), TestApp.NOW), token)
        assertEquals(listOf(user), HiveSignerMessages.decode(token).authors)
        // the size known at upload gives the ratio at publish
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals("[1.5]", Json.parseToJsonElement(field(w.ops().single(), 0, "json_metadata")).jsonObject["image_ratios"].toString())
    }

    @Test
    fun addingToTheGalleryUploadsWithoutTouchingTheBody() = runTest {
        val w = World(this)
        val host = FakeImageHost()
        w.app.env.imageTransport = host
        w.app.env.imageHost = "https://images.example"
        val m = w.newPost(this)
        m.setBody("before after", TextSelection(7))
        val uploads = w.app.env.uploadsModel(m)
        // RN uploads gallery "+" (addToUploads): uploaded, nothing inserted
        uploads.upload(listOf(image("g.jpg")), insert = false)
        testScheduler.advanceUntilIdle()
        assertEquals(1, host.requests.size)
        assertEquals("before after", m.current.body)
        assertEquals(0, m.current.uploading)
    }

    @Test
    fun aFailedOrCancelledUploadLeavesNoPlaceholderAndRetriesOnlyServerHiccups() = runTest {
        val w = World(this)
        val host = FakeImageHost()
        w.app.env.imageTransport = host
        w.app.env.uploadRetryDelay = { 1 }
        val m = w.newPost(this)
        m.setBody("text", TextSelection(4))
        val uploads = w.app.env.uploadsModel(m)
        host.answers.addAll(listOf({ HttpResult(503, "") }, { HttpResult(502, "") }, { HttpResult(200, """{"url":"https://images.example/ok.jpg"}""") }))
        uploads.upload(listOf(image("ok.jpg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertEquals(3, host.requests.size, "two retries after 503 and 502")
        assertTrue(m.current.body.contains("(https://images.example/ok.jpg)"))

        host.requests.clear()
        host.answers.add { HttpResult(413, "") }
        uploads.upload(listOf(image("big.jpg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertEquals(1, host.requests.size, "413 is not retried")
        assertFalse(m.current.body.contains("Uploading..."))
        assertEquals("File size too big, please resize or upload smaller image", uploads.current.alert)
        uploads.clearAlert()

        // over 30 MB never leaves the device
        host.requests.clear()
        uploads.upload(listOf(PickedImage(ByteArray(30_000_001), "huge.jpg", "image/jpeg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertTrue(host.requests.isEmpty())
        assertEquals(UploadsModel.TOO_LARGE, uploads.current.alert)

        host.gate = CompletableDeferred()
        uploads.upload(listOf(image("slow.jpg")), insert = true)
        testScheduler.runCurrent()
        assertTrue(m.current.body.contains("Uploading... slow.jpg"))
        uploads.cancelUploads()
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.body.contains("Uploading..."), "a cancelled upload takes its placeholder with it")
        assertEquals(0, m.current.uploading)
    }

    @Test
    fun uploadsBelongToTheEditorsAccountAndTokenAccountsSendTheirToken() = runTest {
        val w = World(this)
        val host = FakeImageHost()
        w.app.env.imageTransport = host
        val m = w.newPost(this)
        val uploads = w.app.env.uploadsModel(m)
        w.app.env.session.switchTo(other)
        testScheduler.advanceUntilIdle()
        uploads.upload(listOf(image("a.jpg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(user), HiveSignerMessages.decode(host.requests.single().first.substringAfter("/hs/")).authors, "signed for the editor's account, not the one signed in now")

        val hs = VaultAccount("hs-user", AuthType.HIVE_SIGNER, accessToken = "hs-access-token")
        assertEquals("hs-access-token", com.ecency.core.api.ecency.ImageSigning.token(hs, TestApp.NOW))
        assertTrue(runCatching { com.ecency.core.api.ecency.ImageSigning.token(VaultAccount("x", AuthType.HIVE_AUTH), TestApp.NOW) }.exceptionOrNull() is com.ecency.core.api.ecency.ImageSigningUnavailable)
        assertTrue(runCatching { com.ecency.core.api.ecency.ImageSigning.token(VaultAccount("x", AuthType.MEMO_KEY), TestApp.NOW) }.exceptionOrNull() is com.ecency.core.api.ecency.ImageSigningUnavailable)
    }

    // ---- replies: one draft slot, pending publish, conflicts ----

    private fun replyEntry(permlink: String, body: String) =
        entry(user, permlink, "", body, ""","parent_author":"alice"""").replace("\"depth\":0", "\"depth\":1").replace("\"parent_permlink\":\"ecency\"", "\"parent_permlink\":\"root\"")

    @Test
    fun anEditorReplyThatAlreadyLandedAsksBeforeChangingIt() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val m = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        m.setBody("first thought", TextSelection(0))
        w.broadcastAnswer = { req ->
            val link = req.params.jsonArray.single().jsonObject["operations"]!!.jsonArray[0].jsonArray[1].jsonObject["permlink"]!!.jsonPrimitive.content
            w.posts["$user/$link"] = replyEntry(link, "first thought")
            throw IllegalStateException("timeout")
        }
        m.submit()
        testScheduler.advanceUntilIdle()
        val link = field(w.ops().last(), 0, "permlink")
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        // unchanged: it is posted, nothing is sent
        val sent = w.app.broadcasts().size
        m.setBody("second thought", TextSelection(0))
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.publishConflict)
        assertEquals(sent, w.app.broadcasts().size)
        m.resolvePublishConflict(update = false)
        testScheduler.advanceUntilIdle()
        assertTrue(field(w.ops().last(), 0, "permlink") != link, "a new reply, the first one stays as it is")
        assertTrue(m.current.done is EditorDone.Replied)
    }

    @Test
    fun theQuickReplyAsksTooAndKeepsThePendingPublishTheEditorWrote() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        testScheduler.advanceUntilIdle()
        val parent = w.app.env.api.posts.getPost("alice", "root")!!
        val quick = w.app.env.replyModel(parent.key, parent)
        quick.setText("first thought")
        w.broadcastAnswer = { req ->
            val link = req.params.jsonArray.single().jsonObject["operations"]!!.jsonArray[0].jsonArray[1].jsonObject["permlink"]!!.jsonPrimitive.content
            w.posts["$user/$link"] = replyEntry(link, "first thought")
            throw IllegalStateException("timeout")
        }
        quick.send()
        testScheduler.advanceUntilIdle()
        assertNotNull(quick.current.error)
        val link = field(w.ops().last(), 0, "permlink")
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        // typing in the sheet keeps the pending publish in the shared slot
        quick.setText("first thought, edited")
        testScheduler.advanceUntilIdle()
        val stored = LocalDraft.decode(w.storage.values[LocalDrafts.key(user, LocalDrafts.reply("alice", "root"))])!!
        assertEquals(link, stored.pending?.permlink, "one slot, one format: the pending publish survives the other writer")
        // the editor's reply mode continues from the same slot and asks as well
        val editor = w.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        assertEquals("first thought, edited", editor.current.body)
        val sent = w.app.broadcasts().size
        editor.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(editor.current.publishConflict)
        assertEquals(sent, w.app.broadcasts().size)
        // and so does the sheet; "update" edits that reply without options
        quick.send()
        testScheduler.advanceUntilIdle()
        assertTrue(quick.current.conflict)
        quick.resolveConflict(update = true)
        testScheduler.advanceUntilIdle()
        val ops = w.ops().last()
        assertEquals(listOf("comment"), opNames(ops))
        assertEquals(link, field(ops, 0, "permlink"))
        assertNotNull(quick.current.sent)
    }

    @Test
    fun aQuickReplyThatLandedUnchangedCountsAsSentWithoutSendingAgain() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        testScheduler.advanceUntilIdle()
        val parent = w.app.env.api.posts.getPost("alice", "root")!!
        val quick = w.app.env.replyModel(parent.key, parent)
        quick.setText("only thought")
        w.broadcastAnswer = { req ->
            val link = req.params.jsonArray.single().jsonObject["operations"]!!.jsonArray[0].jsonArray[1].jsonObject["permlink"]!!.jsonPrimitive.content
            w.posts["$user/$link"] = replyEntry(link, "only thought")
            throw IllegalStateException("timeout")
        }
        quick.send()
        testScheduler.advanceUntilIdle()
        val sent = w.app.broadcasts().size
        w.broadcastAnswer = { FakeTransport.result(it, "{}") }
        quick.send()
        testScheduler.advanceUntilIdle()
        assertEquals(sent, w.app.broadcasts().size)
        assertNotNull(quick.current.sent)
        assertTrue(w.storage.values.isEmpty())
    }

    @Test
    fun expandingTheQuickReplyContinuesItInTheEditorAndTheSheetStartsFreshAfterwards() = runTest {
        val w = World(this)
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        testScheduler.advanceUntilIdle()
        val parent = w.app.env.api.posts.getPost("alice", "root")!!
        val quick = w.app.env.replyModel(parent.key, parent)
        quick.setText("typed in the sheet")
        var args: EditorArgs? = null
        quick.expand { args = it } // no wait for the autosave: expand writes the text first
        testScheduler.runCurrent()
        assertEquals(EditorArgs(EditorKind.REPLY, "alice", "root", rootAuthor = "alice", rootPermlink = "root"), args)
        val editor = w.newPost(this, args!!)
        assertEquals("typed in the sheet", editor.current.body)
        editor.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(editor.current.done is EditorDone.Replied)
        val next = w.app.env.replyModel(parent.key, parent)
        testScheduler.advanceUntilIdle()
        assertTrue(next !== quick, "the sheet's model for that parent is new")
        assertEquals("", next.current.text)
    }

    // ---- edit recovery, cleanup, validation ----

    @Test
    fun aRecoveryCopyFromAnOlderVersionOfThePostIsOfferedNotApplied() = runTest {
        val w = World(this)
        w.posts["$user/golden-post"] = entry(user, "golden-post", "Title", "version two, edited elsewhere").replace("\"updated\":\"2026-10-01T00:00:00\"", "\"updated\":\"2026-10-03T00:00:00\"")
        val drafts = LocalDrafts(w.storage, w.app.env.dispatchers)
        drafts.save(user, LocalDrafts.edit(user, "golden-post"), LocalDraft(title = "Title", body = "version one plus my unsaved words", updated = 1, baseUpdated = "2026-10-01T00:00:00"))
        val m = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "golden-post"))
        assertEquals("version two, edited elsewhere", m.current.body, "the live post, not the stale copy")
        assertTrue(m.current.recoveryAvailable)
        m.restoreRecovery()
        assertEquals("version one plus my unsaved words", m.current.body)
        assertFalse(m.current.recoveryAvailable)

        // a copy made from this very version comes back by itself (the app died mid-edit)
        drafts.save(user, LocalDrafts.edit(user, "golden-post"), LocalDraft(title = "Title", body = "my unfinished edit", updated = 1, baseUpdated = "2026-10-03T00:00:00"))
        val same = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "golden-post"))
        assertEquals("my unfinished edit", same.current.body)
        assertFalse(same.current.recoveryAvailable)

        // declined: the copy is gone
        drafts.save(user, LocalDrafts.edit(user, "golden-post"), LocalDraft(title = "Title", body = "stale", updated = 1, baseUpdated = "old"))
        val declined = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "golden-post"))
        declined.discardRecovery()
        testScheduler.advanceUntilIdle()
        assertNull(drafts.load(user, LocalDrafts.edit(user, "golden-post")))
    }

    @Test
    fun anEditIsRefusedWhenThePostChangedOnChainSinceItWasOpened() = runTest {
        val w = World(this)
        w.posts["$user/golden-post"] = entry(user, "golden-post", "Title", "the body as opened, long enough to patch. ".repeat(5))
        val m = w.newPost(this, EditorArgs(EditorKind.EDIT_POST, user, "golden-post"))
        m.setBody(m.current.body + "my addition", TextSelection(0))
        w.posts["$user/golden-post"] = entry(user, "golden-post", "Title", "changed elsewhere").replace("\"updated\":\"2026-10-01T00:00:00\"", "\"updated\":\"2026-10-09T00:00:00\"")
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty(), "no patch against a version that is gone")
        assertTrue(w.toasts().single().contains("changed elsewhere"))
        assertTrue(m.current.body.endsWith("my addition"), "the text stays")
    }

    @Test
    fun aSaveRefusedBecauseTheAccountLeftIsNotReportedButADeviceThatRefusesIs() = runTest {
        val w = World(this)
        val m = w.newPost(this)
        // the teardown marked the account as gone; the editor is still open and the user's last keystroke saves
        w.app.env.localDrafts.departed(listOf(user))
        m.setTitle("Typed as the account left")
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.localSaveFailed, "the refusal is the teardown at work, not a failed save")
        assertTrue(w.storage.values.isEmpty(), "and nothing was written")
        // the account is back and the device refuses the write: that is reported
        w.app.env.localDrafts.returned(user)
        w.app.env.draftStorage = object : DraftStorage by w.storage {
            override fun write(account: String, slot: String, value: String?) = throw IllegalStateException("disk full")
        }
        m.setTitle("Typed on a full disk")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.localSaveFailed)
        w.app.env.draftStorage = w.storage
        m.setTitle("Typed after space was made")
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.localSaveFailed)
    }

    @Test
    fun logoutAndWipeRemoveLocalDrafts() = runTest {
        val w = World(this)
        val drafts = LocalDrafts(w.storage, w.app.env.dispatchers)
        drafts.save(user, LocalDrafts.NEW_POST, LocalDraft(title = "mine", updated = 1))
        drafts.save(user, LocalDrafts.reply("alice", "root"), LocalDraft(body = "reply", updated = 1))
        drafts.save(user, LocalDrafts.edit(user, "p"), LocalDraft(body = "recovery", updated = 1))
        drafts.save(other, LocalDrafts.NEW_POST, LocalDraft(title = "theirs", updated = 1))
        w.app.env.session.logout(user)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(LocalDrafts.key(other, LocalDrafts.NEW_POST)), w.storage.values.keys.toSet(), "the signed-out account leaves nothing; the other keeps its draft")
        w.app.env.resetAfterWipe()
        testScheduler.advanceUntilIdle()
        assertTrue(w.storage.values.isEmpty(), "a wipe clears every account's drafts")
    }

    @Test
    fun beneficiariesAreValidatedBeforeAnythingIsSent() = runTest {
        assertNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute("bob", 10000)), user))
        assertNotNull(EditorMeta.beneficiaryProblem((1..9).map { BeneficiaryRoute("u$it", 100) }, user))
        assertNotNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute("bob", 0)), user))
        assertNotNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute("bob", 10001)), user))
        assertNotNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute("bob", 6000), BeneficiaryRoute("carol", 4001)), user))
        assertNotNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute("bob", 1), BeneficiaryRoute("bob", 2)), user))
        assertNotNull(EditorMeta.beneficiaryProblem(listOf(BeneficiaryRoute(user, 100)), user))
        // a draft whose metadata carries routes the chain would refuse: 9500 + the forced 3Speak 1100 is over 100%
        val w = World(this)
        w.drafts += Json.parseToJsonElement("""{"_id":"d5","title":"Video post","body":"https://3speak.tv/embed?v=a/b","tags":"x","modified":"2026-10-06T11:00:00.000Z","meta":{"beneficiaries":[{"account":"bob","weight":9500},{"account":"zero","weight":0},{"account":"$user","weight":100}]}}""").jsonObject
        val m = w.newPost(this, EditorArgs(EditorKind.NEW_POST, draftId = "d5"))
        assertEquals(listOf(BeneficiaryRoute("bob", 9500)), m.current.beneficiaries, "unusable routes of a draft are dropped on load")
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty())
        assertTrue(w.toasts().single().contains("more than 100%"))
    }

    @Test
    fun aFailedAuthorityLookupSaysSoInsteadOfBlamingTheAccount() = runTest {
        val w = World(this)
        w.accountsDown = true
        val m = w.newPost(this)
        w.fill(m, this)
        m.setSchedule("2026-11-01T10:00:00+02:00")
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(w.api.none { it.first == "schedules-add" })
        assertEquals(listOf("Something went wrong\nCould not check your account's posting authority. Check your connection and try again."), w.toasts())
    }

    @Test
    fun onlyACleanUrlOnTheImageHostAndASafeFileNameReachTheBody() = runTest {
        val host = "https://images.ecency.com"
        assertTrue(UploadNames.isSafeUrl("https://images.ecency.com/DQm/abc.jpg", host))
        assertTrue(UploadNames.isSafeUrl("https://images.example/a.jpg", "https://images.example"))
        listOf(
            "http://images.ecency.com/a.jpg", "https://evil.example/a.jpg", "https://images.ecency.com@evil.example/a.jpg", "https://images.ecency.com/a.jpg) [x](https://evil.example",
            "https://images.ecency.com/a b.jpg", "https://images.ecency.com/a.jpg\n![](x)", "javascript:alert(1)", "https://images.ecency.com:8443/a.jpg", "",
        ).forEach { assertFalse(UploadNames.isSafeUrl(it, host), it) }
        assertEquals("IMG_2024__1_.jpg", UploadNames.safe("IMG_2024 (1).jpg"))
        assertEquals("a__x__evil_.png", UploadNames.safe("a](x) evil\n.png"))
        assertEquals("photo-2.jpg", UploadNames.numbered("photo.jpg", 2))

        val w = World(this)
        val fake = FakeImageHost()
        w.app.env.imageTransport = fake
        val m = w.newPost(this)
        m.setBody("text", TextSelection(4))
        val uploads = w.app.env.uploadsModel(m)
        // a host answering with something that would break out of the image markdown: nothing is inserted
        fake.answers.add { HttpResult(200, """{"url":"https://images.example/a.jpg) [click](https://evil.example"}""") }
        uploads.upload(listOf(image("pic (1).jpg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertEquals("text", m.current.body.trim())
        assertNotNull(uploads.current.alert)
        uploads.clearAlert()
        // two picks with the same display name get their own placeholders and both resolve
        uploads.upload(listOf(image("same.jpg"), image("same.jpg")), insert = true)
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.body.contains("(https://images.example/same.jpg)") && m.current.body.contains("(https://images.example/same-2.jpg)"), m.current.body)
        assertFalse(m.current.body.contains("Uploading..."))
    }

    // ---- RC offer, quest chip, short reply hint, rating, posting authority prompt ----

    @Test
    fun questRulesCountCodePointsWithoutLinks() {
        assertEquals(12, Quests.contentLength("hello https://x.io/a?b=1 world"))
        assertEquals(3, Quests.contentLength("a\uD83D\uDC1Db"))
        assertEquals(9, Quests.contentLength("http:// x"), "a scheme with nothing after it is not a link")
        assertFalse(Quests.earnsContentCredit("x".repeat(25)))
        assertTrue(Quests.earnsContentCredit("x".repeat(26)))
        assertFalse(Quests.earnsContentCredit("https://example.com/" + "x".repeat(40)))
        assertTrue(Quests.showsShortHint("me", false, "too short"))
        assertFalse(Quests.showsShortHint("me", true, "too short"))
        assertFalse(Quests.showsShortHint(null, false, "too short"))
        assertFalse(Quests.showsShortHint("me", false, "   "))
        val chip = QuestChip.parse("""{"daily":[{"id":"checkin","progress":1},{"id":"post","progress":0,"cap":1}],"streak":{"current":3,"best":9,"at_risk":true}}""")!!
        assertEquals("Daily post 0/1 \u00B7 \uD83D\uDD25 3 day streak \u00B7 Keep your streak alive", chip.text)
        assertTrue(chip.visible)
        assertFalse(QuestChip.parse("""{"daily":[{"id":"post","progress":1}],"streak":{"current":0,"at_risk":true}}""")!!.visible, "posted today and no streak: nothing to show")
        assertEquals("Daily post 0/1", QuestChip.parse("{}")!!.text)
        assertNull(QuestChip.parse("[]"))
        assertTrue(RcErrors.matches("Please wait to transact, or power up HIVE."))
        assertTrue(RcErrors.matches("Account: spike has 12 RC, needs 3400 RC. Please wait"))
        assertFalse(RcErrors.matches("missing required posting authority"))
    }

    @Test
    fun runningOutOfResourceCreditsIsAnOfferNotAToast() = runTest {
        val w = World(this)
        w.broadcastAnswer = { FakeTransport.error(it, -32000, "Account: $user has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE.") }
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        // the app-wide offer (RcOfferModel), as every other broadcast that ran out
        assertTrue(w.app.env.rcOffer.current.open)
        assertTrue(w.toasts().isEmpty())
        assertEquals("Hello world", m.current.body)
        assertNull(w.app.env.rcOffer.choose(RcOfferChoice.CANCEL))
        assertFalse(w.app.env.rcOffer.current.open)
    }

    @Test
    fun theQuestChipShowsOnANewPostAndStaysClosedOnceDismissed() = runTest {
        val w = World(this)
        w.app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/quests")) HttpResult(200, """{"daily":[{"id":"post","progress":0}],"streak":{"current":2,"at_risk":false}}""") else null
        }
        val m = w.newPost(this)
        assertEquals("Daily post 0/1 \u00B7 \uD83D\uDD25 2 day streak", m.current.questChip)
        m.dismissQuestChip()
        assertNull(m.current.questChip)
        assertNull(w.newPost(this).current.questChip, "closed for this run of the app")
        // replies and edits never ask for quests
        w.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val asOther = World(this)
        asOther.app.authBackend.rest = w.app.authBackend.rest
        asOther.posts["alice/root"] = entry("alice", "root", "Root", "root body")
        val reply = asOther.newPost(this, EditorArgs(EditorKind.REPLY, "alice", "root"))
        assertNull(reply.current.questChip)
        reply.setBody("too short", TextSelection(0))
        assertTrue(reply.current.showsShortReplyHint)
        reply.setBody("this reply is clearly long enough to count", TextSelection(0))
        assertFalse(reply.current.showsShortReplyHint)
    }

    @Test
    fun theStoreRatingIsAskedOnceAfterAPublishWhenTheAppWasUsedEnough() = runTest {
        val w = World(this)
        var asked = 0
        w.app.env.requestReview = { asked++; true }
        // the prompt is only asked for over an unlocked app
        w.app.env.appLock.start()
        val rating = w.app.env.rating
        assertFalse(rating.shouldRequest(), "one session so far")
        repeat(4) { rating.recordSession() }
        assertFalse(rating.shouldRequest(), "five sessions, but first used today")
        w.app.now += 3 * AppRating.DAY_MILLIS
        assertTrue(rating.shouldRequest())
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(1, asked)
        assertFalse(rating.shouldRequest(), "asked once, never again")
        val again = w.newPost(this)
        w.fill(again, this, title = "Second post")
        again.submit()
        testScheduler.advanceUntilIdle()
        assertEquals(1, asked)
    }

    @Test
    fun aTokenAccountWithoutThePostingGrantIsOfferedItOncePerSubmit() = runTest {
        val w = World(this)
        w.postingAuths = "[]"
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "token-$user"))
        val m = w.newPost(this)
        w.fill(m, this)
        m.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.authorityPrompt, "RN: Enable Faster Posting")
        assertNull(w.app.env.signing.current.prompt, "nothing is signed before the answer")
        // skip: the submit goes on (with the account's token) and the prompt does not come back in this submit
        m.answerAuthorityPrompt(grant = false)
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.authorityPrompt)
        assertEquals(EditorDone.Published(user, "my-first-post", null), m.current.done)

        // a skip is not remembered: the next submit asks again; "grant" goes to signing the account_update (active)
        val w2 = World(this)
        w2.postingAuths = "[]"
        KeyVault(w2.app.store).save(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "token-$user"))
        val m2 = w2.newPost(this)
        w2.fill(m2, this)
        m2.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(m2.current.authorityPrompt)
        m2.answerAuthorityPrompt(grant = true)
        testScheduler.advanceUntilIdle()
        val prompt = w2.app.env.signing.current.prompt
        assertNotNull(prompt, "the grant needs the active authority: HiveSigner's page or the upgrade sheet, never a silent broadcast")
        assertNull(m2.current.done, "the post waits for the grant")
        w2.app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertNull(m2.current.done, "RN: a grant that did not happen aborts this submit")
        assertTrue(PostingAuthorityPrompt.usesWalletOrToken(VaultAccount("a", AuthType.HIVE_AUTH)))
        assertTrue(PostingAuthorityPrompt.usesWalletOrToken(VaultAccount("a", AuthType.ACTIVE_KEY, activeKey = "k", accessToken = "t")))
        assertFalse(PostingAuthorityPrompt.usesWalletOrToken(VaultAccount("a", AuthType.POSTING_KEY, postingKey = "k", accessToken = "t")))
        assertFalse(PostingAuthorityPrompt.usesWalletOrToken(VaultAccount("a", AuthType.ACTIVE_KEY, activeKey = "k")))
    }

    // ---- navigation ----

    @Test
    fun editorScreensSaveOnlyIdsAndLeaveTheStackOnAnAccountChange() {
        val screens = listOf(
            AppScreen.Editor(), AppScreen.Editor.of("hive-125125"), AppScreen.Editor.of("alice/post"), AppScreen.Editor.of("reply:alice/post"),
            AppScreen.Editor.of("draft:d1"), AppScreen.Editor.of("template:t1"), AppScreen.Drafts(1),
            AppScreen.Editor(EditorArgs(EditorKind.REPLY, "a", "p", tags = listOf("x", "y"), rootAuthor = "r", rootPermlink = "rp")),
        )
        screens.forEach { assertEquals(it, NavState.decodeScreen(NavState.encodeScreen(it))) }
        assertEquals(EditorKind.EDIT_POST, (AppScreen.Editor.of("alice/post")).args.kind)
        assertEquals("hive-125125", AppScreen.Editor.of("hive-125125").args.community)
        assertEquals(AppScreen.Editor(), AppScreen.of(RouteName.EDITOR))
        assertEquals(AppScreen.Drafts(0), AppScreen.of(RouteName.DRAFTS))
        val nav = NavState().push(AppScreen.Post("a", "p")).push(AppScreen.Editor()).push(AppScreen.Drafts())
        assertEquals(listOf<AppScreen>(AppScreen.Post("a", "p")), nav.afterAccountChange().screens, "an editor belongs to its account")
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.Editor(), loggedIn = false).top)
    }
}
