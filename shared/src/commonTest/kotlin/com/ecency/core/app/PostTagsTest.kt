package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** RN postDisplayView's footer `<Tags>` and tagContainer: the post screen's tags row. */
class PostTagsTest {
    private fun post(
        tags: String,
        category: String = "hive-100001",
        community: String? = "hive-100001",
        communityTitle: String? = "Test Community",
    ) = Post(
        author = "alice", permlink = "p", category = category, community = community, communityTitle = communityTitle,
        metadata = PostMetadata.parse(Json.parseToJsonElement("""{"tags":$tags}""")),
    )

    @Test
    fun theCategoryComesFirstThenTheTagsOnceEach() {
        assertEquals(listOf("hive-100001", "photography", "travel"), PostTags.names(post("""["photography","travel","photography"]""")))
        // already in the list: it keeps its place (RN only puts it first when it is missing)
        assertEquals(listOf("photography", "hive-100001"), PostTags.names(post("""["photography","hive-100001"]""")))
        // case as RN: an exact match only
        assertEquals(listOf("Travel", "travel"), PostTags.names(post("""["travel"]""", category = "Travel", community = null)))
    }

    @Test
    fun aStringOrJunkMetadataStillGivesARow() {
        assertEquals(listOf("hive-100001", "photography"), PostTags.names(post("\"photography\"")))
        assertEquals(listOf("hive-100001"), PostTags.names(post("""[1, null, "", "  ", {"a":1}]""")))
        assertEquals(listOf("hive-100001"), PostTags.names(post("42")))
        assertEquals(listOf("hive-100001"), PostTags.names(post("{}")))
        assertEquals(emptyList(), PostTags.names(post("[]", category = "", community = null)))
        assertEquals(listOf("art"), PostTags.names(post("""["art"]""", category = "  ", community = null)))
    }

    @Test
    fun communitiesShowTheirTitleAndOpenTheCommunityPlainTagsOpenTheirResults() {
        val tags = PostTags.of(post("""["photography","hive-200002","hive-300003","hive-900009"]"""), mapOf("hive-200002" to "Photo Hub", "hive-300003" to null))
        assertEquals(
            listOf(
                PostTag("hive-100001", "Test Community", isCommunity = true, pinned = true),
                PostTag("photography", "photography", isCommunity = false, pinned = false),
                PostTag("hive-200002", "Photo Hub", isCommunity = true, pinned = false),
                // looked up and not found: RN shows the name and opens its tag results
                PostTag("hive-300003", "hive-300003", isCommunity = false, pinned = false),
                // not RN's numbering (hive-1xxxx to hive-3xxxxxx): a plain tag
                PostTag("hive-900009", "hive-900009", isCommunity = false, pinned = false),
            ),
            tags,
        )
        assertEquals(AppScreen.Community("hive-100001"), tags[0].screen)
        assertEquals(AppScreen.TagResult("photography"), tags[1].screen)
        assertEquals(AppScreen.TagResult("hive-300003"), tags[3].screen)
        assertEquals("Test Community, community", tags[0].accessibilityLabel)
        assertEquals("photography, tag", tags[1].accessibilityLabel)
    }

    @Test
    fun aCommunityNotLookedUpYetShowsItsNameAndStillOpensTheCommunity() {
        val tags = PostTags.of(post("""["hive-200002"]""", communityTitle = null))
        assertEquals(listOf(PostTag("hive-100001", "hive-100001", true, true), PostTag("hive-200002", "hive-200002", true, false)), tags)
        // the post's own community is looked up only when the post carries no title for it
        assertEquals(listOf("hive-100001", "hive-200002"), PostTags.lookups(post("""["hive-200002","art"]""", communityTitle = null)))
        assertEquals(listOf("hive-200002"), PostTags.lookups(post("""["hive-200002","art"]""")))
    }

    @Test
    fun thePostScreenLooksUpTheOtherCommunitiesTitles() = runTest {
        val looked = mutableListOf<String>()
        val app = TestApp(this) { req ->
            when (req.method) {
                "bridge.get_post" -> FakeTransport.result(
                    req, entry("alice", "root", metadata = """{"tags":["hive-100001","photography","hive-200002"]}"""),
                )
                "bridge.get_discussion" -> FakeTransport.result(req, "{}")
                "bridge.get_community" -> {
                    val name = req.params.jsonObject["name"]!!.jsonPrimitive.content
                    looked += name
                    FakeTransport.result(req, """{"id":2,"name":"$name","title":"Photo Hub"}""")
                }
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        val model = app.env.postModel("alice", "root")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("hive-200002"), looked)
        assertEquals(
            listOf("hive-100001" to "Test Community", "photography" to "photography", "hive-200002" to "Photo Hub"),
            model.current.tags.map { it.name to it.label },
        )
        model.close()
    }
}
