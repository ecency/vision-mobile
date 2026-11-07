package com.ecency.core.api


import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.TestApp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `HiveApi.content` (`database_api.find_comments`): the "is this permlink taken" read every publish relies on. */
class ChainContentTest {
    private fun api(answer: (Sent) -> HttpResult): Pair<HiveApi, FakeTransport> {
        val transport = FakeTransport { answer(it) }
        return HiveApi(clientWith(transport)) to transport
    }

    @Test
    fun itAsksTheChainsOwnStateForExactlyThisPost() = runTest {
        val (api, transport) = api { TestApp.comments(it, null) }
        assertNull(api.content("alice", "new-post"))
        val sent = transport.sent.single()
        assertEquals("database_api.find_comments", sent.method)
        assertEquals("""[["alice","new-post"]]""", sent.params.jsonObject.getValue("comments").jsonArray.toString())
    }

    @Test
    fun anExistingPostIsReturned() = runTest {
        val (api, _) = api {
            TestApp.comments(it, """{"author":"alice","permlink":"new-post","title":"T","body":"B","parent_author":"","parent_permlink":"hive","json_metadata":"{\"tags\":[\"hive\"]}"}""")
        }
        val c = api.content("alice", "new-post")
        assertEquals("B", c?.body)
        assertEquals("hive", c?.parentPermlink)
        assertEquals(listOf("hive"), c?.tags)
    }

    @Test
    fun anotherPostInTheAnswerIsNotThisOne() = runTest {
        val (api, _) = api { TestApp.comments(it, """{"author":"alice","permlink":"new-post-2","body":"B"}""") }
        assertNull(api.content("alice", "new-post"))
    }

    @Test
    fun anAnswerWithoutTheListIsNeverReadAsAFreePermlink() = runTest {
        for (body in listOf("{}", "null", """{"comments":null}""", "[]")) {
            val (api, _) = api { FakeTransport.result(it, body) }
            assertFailsWith<EcencyException>(body) { api.content("alice", "new-post") }
        }
    }

    @Test
    fun anErrorIsNeverReadAsAFreePermlink() = runTest {
        val (api, _) = api { FakeTransport.error(it, -32602, "Assert Exception:Post alice/new-post does not exist") }
        assertFailsWith<EcencyException> { api.content("alice", "new-post") }
    }
}
