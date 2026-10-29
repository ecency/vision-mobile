package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.RcComment
import com.ecency.core.hive.RcCommentOptions
import com.ecency.core.hive.RcCost
import com.ecency.core.math.RcGoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RN RcPrecheckBanner's rules in the editor, over a scripted node that answers rc_api like the golden snapshot. */
class RcPrecheckEditorTest {
    private val user = TestApp.USER

    private inner class World(val scope: TestScope, var mana: String = "1000", var statsDown: Boolean = false) {
        val rcCalls = mutableListOf<String>()
        val app = TestApp(scope) { req ->
            when (req.method) {
                "rc_api.find_rc_accounts" -> {
                    rcCalls += req.method
                    assertEquals(user, req.params.jsonObject["accounts"]!!.jsonArray.single().jsonPrimitive.content)
                    FakeTransport.result(req, """{"rc_accounts":[{"account":"$user","max_rc":"$mana","rc_manabar":{"current_mana":"$mana","last_update_time":${TestApp.NOW / 1000}}}]}""")
                }
                "rc_api.get_rc_stats" -> {
                    rcCalls += req.method
                    if (statsDown) HttpResult(500, "") else FakeTransport.result(req, """{"rc_stats":${RcGoldenVectors.STATS}}""")
                }
                "rc_api.get_resource_params" -> {
                    rcCalls += req.method
                    FakeTransport.result(req, RcGoldenVectors.PARAMS)
                }
                "bridge.get_post" -> FakeTransport.result(req, """{"author":"alice","permlink":"her-post","category":"ecency","title":"Hers","body":"Parent body","json_metadata":{"tags":["ecency"]},"created":"2026-10-01T00:00:00","updated":"2026-10-01T00:00:00","depth":0}""")
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "{}")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }

        init {
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            app.env.randomString = { "rnd1" }
            KeyVault(app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "t"))
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun editor(args: EditorArgs = EditorArgs(EditorKind.NEW_POST)): EditorModel {
            val m = app.env.editorModel(args)
            m.start()
            idle()
            return m
        }
    }

    @Test
    fun theBannerShowsAfterAPauseWhenTheDraftCostsMoreThanTheAccountHas() = runTest {
        val w = World(this, mana = "1000")
        val m = w.editor()
        assertFalse(m.current.rcWarning, "nothing written, nothing to cost")
        m.setTitle("A title")
        m.setBody("Some text", TextSelection(9))
        testScheduler.advanceTimeBy(EditorModel.RC_DEBOUNCE_MILLIS - 1)
        assertFalse(m.current.rcWarning, "not on every keystroke")
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertTrue(m.current.rcWarning)
        // advice only: the form is as valid as before
        m.setTagInput("ecency ")
        w.idle()
        assertTrue(m.current.canSubmit)
        // a tap opens the offer a failed broadcast raises
        assertFalse(w.app.env.rcOffer.current.open)
        m.showRcOffer()
        assertTrue(w.app.env.rcOffer.current.open)
        // an emptied body has nothing to warn about
        m.setBody("", TextSelection(0))
        w.idle()
        assertFalse(m.current.rcWarning)
    }

    @Test
    fun anAccountWithEnoughCreditsSeesNoBanner() = runTest {
        val w = World(this, mana = "900000000000000")
        val m = w.editor()
        m.setTitle("A title")
        m.setBody("x".repeat(20_000), TextSelection(0))
        w.idle()
        assertFalse(m.current.rcWarning)
        assertEquals(listOf("rc_api.find_rc_accounts", "rc_api.get_rc_stats", "rc_api.get_resource_params"), w.rcCalls)
        // the curve parameters are kept: a second editor does not ask for them again
        w.editor()
        assertEquals(1, w.rcCalls.count { it == "rc_api.get_resource_params" })
    }

    @Test
    fun withoutTheChainsNumbersNothingIsSaid() = runTest {
        val w = World(this, mana = "1", statsDown = true)
        val m = w.editor()
        m.setTitle("A title")
        m.setBody("Some text", TextSelection(9))
        w.idle()
        assertFalse(m.current.rcWarning, "never a made-up warning")
    }

    @Test
    fun aReplyIsCostedAsAReply() = runTest {
        val w = World(this, mana = "1000")
        val m = w.editor(EditorArgs(EditorKind.REPLY, author = "alice", permlink = "her-post"))
        m.setBody("A reply", TextSelection(7))
        w.idle()
        assertTrue(m.current.rcWarning)
    }

    @Test
    fun theCostedOperationIsTheOneThatIsBroadcast() = runTest {
        val w = World(this, mana = "900000000000000")
        val m = w.editor()
        m.setTitle("My first post")
        m.setBody("Hello world, no images here.", TextSelection(0))
        m.setTagInput("ecency test ")
        w.idle()
        m.submit()
        w.idle()
        val ops = w.app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray
        val comment = ops[0].jsonArray[1].jsonObject
        fun f(name: String) = comment[name]!!.jsonPrimitive.content
        val op = RcComment(f("author"), f("permlink"), f("parent_author"), f("parent_permlink"), f("title"), f("body"), f("json_metadata"))
        assertEquals("my-first-post", op.permlink)
        // the estimate's byte count is the real transaction's: its serialization, the signature count and one signature
        assertEquals(2, ops.size)
        val operations = ops.map { com.ecency.core.api.broadcast.OperationJson.decodeOwn(it.jsonArray) }
        val transaction = com.ecency.core.hive.Transaction(refBlockNum = 1, refBlockPrefix = 2, expiration = "2026-10-06T12:10:00", operations = operations)
        assertEquals(transaction.serialize().size + 1 + 65, RcCost.transactionBytes(op, RcCommentOptions(null)))
    }
}
