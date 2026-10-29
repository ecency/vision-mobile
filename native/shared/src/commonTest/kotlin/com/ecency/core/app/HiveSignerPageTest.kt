package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.auth.HiveSigner
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.Transfer
import com.ecency.core.hive.Vote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HiveSignerPageTest {
    @Test
    fun onlyHiveSignerOverHttpsLoadsAndTheRedirectIsRead() {
        assertEquals(HiveSignerNavigation.LOAD, HiveSignerPage.decide(HiveSigner.authorizeUrl()))
        assertEquals(HiveSignerNavigation.LOAD, HiveSignerPage.decide("https://hivesigner.com/sign/ops/abc"))
        assertEquals(HiveSignerNavigation.REDIRECT, HiveSignerPage.decide("http://127.0.0.1:3000/auth?code=x"))
        assertEquals(HiveSignerNavigation.REDIRECT, HiveSignerPage.decide("http://127.0.0.1:3000/auth"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("http://127.0.0.1:3000/authx?code=x"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("http://hivesigner.com/login"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("https://hivesigner.com.evil.example/"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("https://evil.example/?u=https://hivesigner.com"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("intent://x#Intent;end"))
        assertEquals(HiveSignerNavigation.BLOCK, HiveSignerPage.decide("javascript:alert(1)"))
    }

    @Test
    fun operationJsonDecodesWhatItEncodes() {
        val ops = listOf(
            Vote("a", "b", "c", -500),
            Comment("p", "pp", "a", "re-x", "", "body", "{}"),
            CommentOptions("a", "re-x", Asset.parse("1000000.000 HBD"), 10000, true, true, listOf(Beneficiary("ecency", 300))),
            Transfer("a", "b", Asset.parse("1.000 HIVE"), "memo"),
            CustomJson(listOf("a"), emptyList(), "ecency_point_transfer", "{}"),
        )
        ops.forEach { assertEquals(it, OperationJson.decode(OperationJson.encode(it))) }
        assertFailsWith<BroadcastException.InvalidOperation> {
            OperationJson.decode(Json.parseToJsonElement("""["delegate_vesting_shares",{"delegator":"a"}]""").jsonArray)
        }
        assertFailsWith<BroadcastException.InvalidOperation> {
            OperationJson.decode(Json.parseToJsonElement("""["transfer",{"from":"a","to":"b","amount":"1 BTC","memo":""}]""").jsonArray)
        }
        assertFailsWith<BroadcastException.InvalidOperation> {
            OperationJson.decode(Json.parseToJsonElement("""["vote",{"voter":"a","author":"b","permlink":"c","weight":20000}]""").jsonArray)
        }
    }
}
