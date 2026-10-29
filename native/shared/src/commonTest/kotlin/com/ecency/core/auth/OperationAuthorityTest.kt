package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationAuthorityTest {
    private fun op(s: String) = Json.parseToJsonElement(s).jsonArray
    private fun ops(vararg s: String) = JsonArray(s.map(::op))

    @Test
    fun sdkMap() {
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["vote",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["transfer",{}]""")))
        // metadata only: posting (what the profile edit and pin to blog build); anything more: active
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["account_update2",{"posting_json_metadata":"{}"}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["account_update2",{"account":"alice","json_metadata":"","posting_json_metadata":"{\"profile\":{}}","extensions":[]}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["account_update2",{"account":"alice","memo_key":null,"json_metadata":"","posting_json_metadata":"{}"}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["account_update2",{"account":"alice","json_metadata":"{\"x\":1}","posting_json_metadata":"{}"}]""")))
        for (key in listOf("owner", "active", "posting")) {
            // an owner field makes it an owner operation (RN #3535), the other key fields active
            val expected = if (key == "owner") KeyRole.OWNER else KeyRole.ACTIVE
            assertEquals(expected, OperationAuthority.of(op("""["account_update2",{"account":"alice","$key":{"weight_threshold":1},"json_metadata":"","posting_json_metadata":"{}"}]""")), key)
        }
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["account_update2",{"account":"alice","memo_key":"STM8x","json_metadata":"","posting_json_metadata":"{}"}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.required(ops("""["account_update2",{"account":"alice","json_metadata":"","posting_json_metadata":"{}"}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.required(ops("""["account_update2",{"posting_json_metadata":"{}"}]""", """["account_update2",{"json_metadata":"{}"}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["account_update",{"account":"alice","json_metadata":""}]""")))
        assertEquals(KeyRole.OWNER, OperationAuthority.of(op("""["change_recovery_account",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["create_proposal",{}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["some_future_op",{}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.of(op("""["custom_json",{"required_auths":[],"required_posting_auths":["a"]}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["custom_json",{"required_auths":["a"],"required_posting_auths":[]}]""")))
    }

    @Test
    fun requiredTakesTheHighest() {
        assertEquals(KeyRole.POSTING, OperationAuthority.required(ops("""["vote",{}]""", """["comment",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.required(ops("""["comment",{}]""", """["transfer",{}]""")))
        assertEquals(KeyRole.OWNER, OperationAuthority.required(ops("""["transfer",{}]""", """["change_recovery_account",{}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.required(JsonArray(emptyList())))
    }

    @Test
    fun hiveUriRulesFromRnTests() {
        assertEquals(KeyRole.POSTING, OperationAuthority.forHiveUri(op("""["account_update2",{"account":"alice","json_metadata":"","posting_json_metadata":"{\"profile\":{}}","extensions":[]}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(op("""["account_update2",{"account":"alice","posting":{"weight_threshold":1},"json_metadata":"","posting_json_metadata":""}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(op("""["account_update2",{"account":"alice","json_metadata":"{\"x\":1}","posting_json_metadata":""}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(op("""["account_update2",{"account":"alice","memo_key":"STM8x","json_metadata":"","posting_json_metadata":""}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.forHiveUri(op("""["account_update2",{"account":"alice","memo_key":null}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(op("""["account_update",{"account":"alice","json_metadata":""}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.forHiveUri(op("""["vote",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(op("""["custom_json",{"required_auths":["alice"]}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUriTx(ops("""["vote",{}]""", """["transfer",{}]""")))
        // Owner-map operations are OWNER for links (RN resolved them to active).
        assertEquals(KeyRole.OWNER, OperationAuthority.forHiveUri(op("""["change_recovery_account",{}]""")))
        assertEquals(KeyRole.OWNER, OperationAuthority.forHiveUri(op("""["recover_account",{}]""")))
        assertEquals(KeyRole.OWNER, OperationAuthority.forHiveUriTx(ops("""["vote",{}]""", """["transfer",{}]""", """["set_reset_account",{}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.forHiveUriTx(JsonArray(emptyList())))
    }

    private val auth = """{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]}"""

    @Test
    fun accountUpdatesThatSetOwnerNeedTheOwnerKey() {
        for (name in listOf("account_update", "account_update2")) {
            val owner = op("""["$name",{"account":"alice","owner":$auth,"memo_key":"STM1","json_metadata":""}]""")
            assertEquals(KeyRole.OWNER, OperationAuthority.of(owner), name)
            assertEquals(KeyRole.OWNER, OperationAuthority.forHiveUri(owner), name)
            assertEquals(KeyRole.OWNER, OperationAuthority.required(ops("""["vote",{}]""", owner.toString())), name)
            assertEquals(KeyRole.OWNER, OperationAuthority.forHiveUriTx(ops(owner.toString())), name)
            assertEquals(KeyRole.OWNER, OperationAuthority.hiveAuthKeyType(ops(owner.toString())), name)
            // an explicit null owner changes nothing
            val nullOwner = op("""["$name",{"account":"alice","owner":null,"active":$auth,"json_metadata":""}]""")
            assertEquals(KeyRole.ACTIVE, OperationAuthority.of(nullOwner), name)
            assertEquals(KeyRole.ACTIVE, OperationAuthority.forHiveUri(nullOwner), name)
        }
        // v1 without owner stays active, even metadata-only
        assertEquals(KeyRole.ACTIVE, OperationAuthority.of(op("""["account_update",{"account":"alice","posting":$auth,"json_metadata":""}]""")))
    }

    @Test
    fun customJsonWithBothAuthorityListsIsUnsupported() {
        val mixed = op("""["custom_json",{"required_auths":["a"],"required_posting_auths":["a"],"id":"x","json":"{}"}]""")
        assertTrue(OperationAuthority.isMixedCustomJson(mixed))
        assertEquals(OperationAuthority.MIXED_AUTHORITIES, OperationAuthority.unsupported(ops("""["vote",{}]""", mixed.toString())))
        assertFalse(OperationAuthority.isMixedCustomJson(op("""["custom_json",{"required_auths":["a"],"required_posting_auths":[]}]""")))
        assertFalse(OperationAuthority.isMixedCustomJson(op("""["custom_json",{"required_auths":[],"required_posting_auths":["a"]}]""")))
        assertFalse(OperationAuthority.isMixedCustomJson(op("""["transfer",{"required_auths":["a"],"required_posting_auths":["a"]}]""")))
        // review M3: posting and active operations together cannot be signed with one key since HF28
        assertEquals(OperationAuthority.MIXED_AUTHORITIES, OperationAuthority.unsupported(ops("""["vote",{}]""", """["transfer",{}]""")))
        assertEquals(OperationAuthority.MIXED_AUTHORITIES, OperationAuthority.unsupported(ops("""["custom_json",{"required_posting_auths":["a"]}]""", """["custom_json",{"required_auths":["a"]}]""")))
        assertNull(OperationAuthority.unsupported(ops("""["comment",{}]""", """["comment_options",{}]""")))
        assertNull(OperationAuthority.unsupported(ops("""["transfer",{}]""", """["transfer_to_vesting",{}]""")))
        assertNull(OperationAuthority.unsupported(JsonArray(emptyList())))
    }

    @Test
    fun hiveAuthKeyType() {
        assertEquals(KeyRole.POSTING, OperationAuthority.hiveAuthKeyType(ops("""["vote",{}]""", """["account_update2",{"json_metadata":"","posting_json_metadata":"{}"}]""")))
        // a key or json_metadata change is active in the wallet too (RN sent every account_update2 as posting)
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["vote",{}]""", """["account_update2",{"json_metadata":"x"}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["account_update2",{"memo_key":"STM1","json_metadata":""}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["account_update",{"json_metadata":""}]""")))
        assertEquals(KeyRole.OWNER, OperationAuthority.hiveAuthKeyType(ops("""["vote",{}]""", """["change_recovery_account",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["some_future_op",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["vote",{}]""", """["transfer",{}]""")))
        assertEquals(KeyRole.ACTIVE, OperationAuthority.hiveAuthKeyType(ops("""["custom_json",{"required_auths":["a"]}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.hiveAuthKeyType(ops("""["custom_json",{"required_posting_auths":["a"]}]""")))
        assertEquals(KeyRole.POSTING, OperationAuthority.hiveAuthKeyType(JsonArray(emptyList())))
    }

    @Test
    fun tempActiveKeyIsSingleUseAndExpires() {
        var now = 0L
        val temp = TempActiveKey({ now })
        assertNull(temp.take("alice"))
        temp.set("alice", "5Kkey")
        assertTrue(temp.isHeld)
        assertEquals("5Kkey", temp.take("alice"))
        assertNull(temp.take("alice"))

        temp.set("alice", "5Kkey")
        now = 59_999
        assertTrue(temp.isHeld)
        now = 60_000
        assertFalse(temp.isHeld)
        assertNull(temp.take("alice"))

        temp.set("alice", "5Kkey")
        temp.clear()
        assertNull(temp.take("alice"))
        assertFalse("5Kkey" in temp.toString())
    }

    @Test
    fun tempActiveKeyOnlyServesItsAccount() {
        val temp = TempActiveKey({ 0 })
        temp.set("alice", "5Kkey")
        // Another account never gets it, and the attempt consumes it.
        assertNull(temp.take("bob"))
        assertNull(temp.take("alice"))
    }

    @Test
    fun authFallbackClassification() {
        assertTrue(AuthFallback.shouldTrigger(AuthException.MissingKey(KeyRole.ACTIVE)))
        assertTrue(AuthFallback.shouldTrigger(Exception("missing required active authority: Missing Active Authority alice")))
        assertTrue(AuthFallback.shouldTrigger(Exception("Missing posting authority")))
        assertTrue(AuthFallback.shouldTrigger(Exception("401 Unauthorized")))
        assertTrue(AuthFallback.shouldTrigger(AuthException.HiveSignerApi(401, "unauthorized_access", null)))
        assertFalse(AuthFallback.shouldTrigger(Exception("Please wait to transact, or power up HIVE")))
        assertFalse(AuthFallback.shouldTrigger(AuthException.HiveSignerApi(500, "server_error", null)))
        assertFalse(AuthFallback.shouldTrigger(Exception("unauthorizedx")))
    }
}
