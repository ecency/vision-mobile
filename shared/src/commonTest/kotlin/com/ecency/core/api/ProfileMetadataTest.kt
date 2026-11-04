package com.ecency.core.api

import com.ecency.core.api.model.JsValue
import com.ecency.core.api.model.ProfileMetadata
import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.BroadcastException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** posting_json_metadata built exactly as @ecency/sdk builds it (fixtures from the SDK itself). */
class ProfileMetadataTest {
    @Test
    fun matchesTheSdkForEveryFixture() {
        ProfileMetadataFixtures.cases.forEach { c ->
            val partial = assertNotNull(JsValue.parse(c.partial) as? JsValue.Obj, c.name)
            if (c.error != null) {
                assertFailsWith<InvalidInputException>(c.name) { ProfileMetadata.buildPostingJsonMetadata(c.existing, partial) }
            } else {
                assertEquals(c.expected, ProfileMetadata.buildPostingJsonMetadata(c.existing, partial), c.name)
            }
        }
    }

    @Test
    fun numbersAreWrittenLikeJsonStringify() {
        ProfileMetadataFixtures.numbers.forEach { (literal, expected) ->
            assertEquals(expected, JsValue.stringify(JsValue.parse(literal)!!), literal)
        }
    }

    @Test
    fun parseProfileReadsOnlyTheProfileObject() {
        assertEquals(listOf("name"), ProfileMetadata.parseProfile("""{"profile":{"name":"a"},"x":1}""").keys)
        assertEquals(emptyList(), ProfileMetadata.parseProfile("""{"profile":"str"}""").keys)
        assertEquals(emptyList(), ProfileMetadata.parseProfile("not json").keys)
        assertEquals(emptyList(), ProfileMetadata.parseProfile(null).keys)
    }

    @Test
    fun accountUpdate2JsonRoundTripsAndRefusesKeyChanges() {
        val op = AccountUpdate2("alice", "", """{"profile":{"name":"A","version":2}}""")
        val json = OperationJson.encode(op)
        assertEquals("""["account_update2",{"account":"alice","json_metadata":"","posting_json_metadata":"{\"profile\":{\"name\":\"A\",\"version\":2}}","extensions":[]}]""", json.toString())
        assertEquals(op, OperationJson.decode(json))
        val withKey = Json.parseToJsonElement("""["account_update2",{"account":"alice","memo_key":"STM1","json_metadata":"","posting_json_metadata":"{}","extensions":[]}]""").jsonArray
        assertFailsWith<BroadcastException.InvalidOperation> { OperationJson.decode(withKey) }
        val withNulls = Json.parseToJsonElement("""["account_update2",{"account":"alice","owner":null,"json_metadata":"","posting_json_metadata":"{}"}]""").jsonArray
        assertEquals(AccountUpdate2("alice", "", "{}"), OperationJson.decode(withNulls))
    }
}
