package com.ecency.core.auth

import com.ecency.core.api.broadcast.TxReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Golden cases from RN `utils/hive-uri.test.ts` plus vectors encoded by the hive-uri library itself. */
class HiveUriTest {
    private fun json(s: String) = Json.parseToJsonElement(s)
    private val voteOp = json("""["vote",{"voter":"__signer","author":"good-karma","permlink":"hello","weight":10000}]""").jsonArray
    private val transferOp = json("""["transfer",{"from":"__signer","to":"ecency","amount":"1.5 HIVE","memo":""}]""").jsonArray
    private fun keys(vararg held: String) = mapOf("posting" to false, "active" to false) + held.associateWith { true }

    @Test
    fun normalisesEcencySignLinks() {
        assertEquals("hive://sign/op/abc", HiveUri.normalize("ecency://sign/op/abc"))
        assertEquals("hive://sign/tx/abc", HiveUri.normalize("  ECENCY://sign/tx/abc  "))
        assertEquals("hive://sign/op/abc", HiveUri.normalize("hive://sign/op/abc"))
        assertEquals("ecency://auth-request?callback=x", HiveUri.normalize("ecency://auth-request?callback=x"))
        assertEquals("https://ecency.com/@good-karma", HiveUri.normalize("https://ecency.com/@good-karma"))
    }

    @Test
    fun recognisesHiveLinksOnly() {
        listOf("hive://sign/op/abc", "ecency://sign/tx/abc", " hive://sign/op/abc ").forEach { assertTrue(HiveUri.isHiveUri(it), it) }
        listOf("ecency://auth-request?callback=honeyback%3A%2F%2Fhive", "ecency://login?username=a&callback=b", "https://ecency.com", "hive:sign/op/abc", "")
            .forEach { assertFalse(HiveUri.isHiveUri(it), it) }
        assertTrue(HiveUri.isWebUrl(" HTTP://example.com "))
        assertFalse(HiveUri.isWebUrl("mailto:someone@example.com"))
    }

    @Test
    fun keepsSigningLinksAndAuthRequestsApart() {
        val link = HiveUri.encodeOp(voteOp)
        assertTrue(HiveUri.isHiveUri(link))
        assertFalse(AuthLinks.isAuthRequest(link))
        assertFalse(AuthLinks.isAuthRequest("ecency://sign/op/abc"))
        assertFalse(HiveUri.isHiveUri("ecency://auth-request?callback=hive%3A%2F%2Fsign%2Fop%2Fabc"))
    }

    @Test
    fun decodesLibraryEncodedLinks() {
        AuthGoldenVectors.uris.forEach { v ->
            val d = HiveUri.decode(v.uri)
            assertEquals(json(v.txJson), d.tx, v.name)
            assertEquals(HiveUri.Params(v.callback, v.noBroadcast, v.authority, v.signer), d.params, v.name)
        }
    }

    @Test
    fun encodesLikeTheLibrary() {
        AuthGoldenVectors.uris.forEach { v ->
            val d = HiveUri.decode(v.uri)
            val again = when (d.type) {
                "op" -> HiveUri.encodeOp((d.tx as JsonObject)["operations"]!!.jsonArray.single().jsonArray, d.params)
                "ops" -> HiveUri.encodeOps((d.tx as JsonObject)["operations"]!!.jsonArray, d.params)
                else -> HiveUri.encodeTx(d.tx as JsonObject, d.params)
            }
            assertEquals(v.uri, again, v.name)
        }
    }

    @Test
    fun ecencySchemeDecodesToTheSameTransaction() {
        val hive = HiveUri.encodeOp(voteOp)
        val ecency = "ecency://" + hive.removePrefix("hive://")
        assertEquals(HiveUri.decode(hive), HiveUri.decode(HiveUri.normalize(ecency)))
        assertEquals(JsonArray(listOf(voteOp)), (HiveUri.decode(hive).tx as JsonObject)["operations"])
    }

    @Test
    fun rejectsBadLinks() {
        assertFailsWith<AuthException.InvalidLink> { HiveUri.decode("ecency://sign/op/abc") }
        assertFailsWith<AuthException.InvalidLink> { HiveUri.decode("hive://other/op/abc") }
        assertFailsWith<AuthException.InvalidLink> { HiveUri.decode("hive://sign/op/!!!") }
        assertFailsWith<AuthException.InvalidLink> { HiveUri.decode("hive://sign/nope/" + B64u.encode("{}")) }
        assertFailsWith<AuthException.InvalidLink> { HiveUri.decode("hive://sign/op") }
    }

    @Test
    fun resolvesPlaceholdersLikeTheLibrary() {
        val d = HiveUri.decode(AuthGoldenVectors.RESOLVE_INPUT)
        val tx = HiveUri.resolveTransaction(d.tx as JsonObject, d.params, TxReference(12345, 3367463123), "2026-10-06T12:00:00", AuthGoldenVectors.USER)
        assertEquals(json(AuthGoldenVectors.RESOLVED_TX), tx)
        val other = d.params.copy(signer = "someone-else")
        assertFailsWith<AuthException.InvalidLink> { HiveUri.resolveTransaction(d.tx as JsonObject, other, TxReference(1, 2), "x", AuthGoldenVectors.USER) }
        assertEquals("2026-10-06T12:02:00", HiveUri.expirationFrom(1_791_288_000_000))
    }

    @Test
    fun resolvesCallbackTemplates() {
        assertEquals("https://x/cb?id=abc&b=12&s=", HiveUri.resolveCallback("https://x/cb?id={{id}}&b={{block}}&s={{sig}}", mapOf("id" to "abc", "block" to "12")))
    }

    @Test
    fun operationTableMatchesOperationsJson() {
        assertEquals(AuthGoldenVectors.operations.size, HiveUriOperations.ALL.size)
        AuthGoldenVectors.operations.zip(HiveUriOperations.ALL).forEach { (rn, port) ->
            assertEquals(rn.name, port.name)
            assertEquals(rn.title, port.title)
            assertEquals(rn.authority, port.authority)
            // custom_json is the documented exception (RN reports a bogus "defaultValue" field).
            if (rn.name == "custom_json") assertEquals(null, port.signerField) else assertEquals(rn.signerField, port.signerField)
        }
    }

    private fun tx(vararg ops: JsonArray) = HiveUri.decode(HiveUri.encodeOps(JsonArray(ops.toList()))).tx as JsonObject

    @Test
    fun formatsASignableOperation() {
        val r = assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(voteOp), keys("posting")))
        assertEquals("Vote", r.title)
        assertEquals(JsonArray(listOf(voteOp)), r.tx["operations"])
        assertEquals("__expiration", r.tx["expiration"]!!.jsonPrimitive.content)
    }

    @Test
    fun fillsAnEmptySignerField() {
        val op = json("""["vote",{"voter":"","author":"good-karma","permlink":"hello","weight":1}]""").jsonArray
        val r = assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(op), keys("posting")))
        assertEquals("__signer", r.tx["operations"]!!.jsonArray[0].jsonArray[1].jsonObject["voter"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusesWhatTheKeysCannotSign() {
        assertEquals(
            FormattedHiveUriTx.Refused("qr.invalid_key", "qr.invalid_key_desc", "active"),
            HiveUriTx.format(tx(transferOp), keys("posting")),
        )
    }

    @Test
    fun formatsAmountsAndRefusesOtherAssets() {
        val r = assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(transferOp), keys("active")))
        assertEquals(com.ecency.core.l10n.L.wallet.transfer, r.title) // copy, in the language of the run
        assertEquals("1.500 HIVE", r.tx["operations"]!!.jsonArray[0].jsonArray[1].jsonObject["amount"]!!.jsonPrimitive.content)
        val btc = json("""["transfer",{"from":"__signer","to":"ecency","amount":"1.5 BTC","memo":""}]""").jsonArray
        assertEquals("qr.invalid_amount", (HiveUriTx.format(tx(btc), keys("active")) as FormattedHiveUriTx.Refused).errorKey1)
    }

    @Test
    fun refusesMultipleAndUnknownOperations() {
        assertEquals("qr.multi_array_ops_alert", (HiveUriTx.format(tx(voteOp, voteOp), keys("posting")) as FormattedHiveUriTx.Refused).errorKey1)
        val unknown = json("""["no_such_op",{"a":1}]""").jsonArray
        assertEquals("qr.invalid_op", (HiveUriTx.format(tx(unknown), keys("posting")) as FormattedHiveUriTx.Refused).errorKey1)
    }

    @Test
    fun ownerOperationsFromLinksAreAlwaysRefused() {
        val op = json("""["change_recovery_account",{"account_to_recover":"__signer","new_recovery_account":"x","extensions":[]}]""").jsonArray
        val refused = HiveUriTx.format(tx(op), keys("posting", "active", "owner"))
        assertEquals(FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "owner"), refused)
        assertEquals(FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "owner"), HiveUriTx.format(tx(op), null))
    }

    @Test
    fun linksCannotRotateAccountAuthorities() {
        val all = keys("posting", "active", "memo")
        val auth = """{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]}"""
        for (field in listOf("owner", "active", "posting")) {
            for (name in listOf("account_update", "account_update2")) {
                val op = json("""["$name",{"account":"","$field":$auth,"memo_key":"STM1","json_metadata":""}]""").jsonArray
                val refused = assertIs<FormattedHiveUriTx.Refused>(HiveUriTx.format(tx(op), all), "$name $field")
                assertEquals("qr.invalid_op", refused.errorKey1)
            }
        }
        // metadata-only updates still pass
        val meta = json("""["account_update",{"account":"","memo_key":"STM1","json_metadata":"{\"profile\":{}}"}]""").jsonArray
        assertEquals("active", assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(meta), all)).authority)
        val posting = json("""["account_update2",{"account":"","json_metadata":"","posting_json_metadata":"{}","posting":null}]""").jsonArray
        assertEquals("posting", assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(posting), all)).authority)
    }

    @Test
    fun accountUpdatesSettingOwnerAreRefusedAsOwner() {
        val auth = """{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]}"""
        for (name in listOf("account_update", "account_update2")) {
            val op = json("""["$name",{"account":"","owner":$auth,"memo_key":"STM1","json_metadata":""}]""").jsonArray
            assertEquals(FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "owner"), HiveUriTx.format(tx(op), keys("posting", "active", "owner")), name)
        }
    }

    @Test
    fun mixedAuthorityCustomJsonIsRefusedAsUnsupported() {
        val mixed = json("""["custom_json",{"required_auths":["__signer"],"required_posting_auths":["__signer"],"id":"x","json":"{}"}]""").jsonArray
        assertEquals(FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", HiveUriTx.MIXED), HiveUriTx.format(tx(mixed), keys("posting", "active")))
    }

    @Test
    fun activeCustomJsonNeedsAndNamesTheActiveKey() {
        val points = json("""["custom_json",{"required_auths":["__signer"],"required_posting_auths":[],"id":"ecency_point_transfer","json":"{}"}]""").jsonArray
        assertEquals(FormattedHiveUriTx.Refused("qr.invalid_key", "qr.invalid_key_desc", "active"), HiveUriTx.format(tx(points), keys("posting")))
        assertEquals("active", assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(points), keys("active"))).authority)
        val follow = json("""["custom_json",{"required_auths":[],"required_posting_auths":["__signer"],"id":"follow","json":"[]"}]""").jsonArray
        assertEquals("posting", assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(follow), keys("posting"))).authority)
        val profile = json("""["account_update2",{"account":"","json_metadata":"","posting_json_metadata":"{}"}]""").jsonArray
        assertEquals("posting", assertIs<FormattedHiveUriTx.Ready>(HiveUriTx.format(tx(profile), keys("posting"))).authority)
        val keysChange = json("""["account_update2",{"account":"","memo_key":"STM1","json_metadata":"","posting_json_metadata":""}]""").jsonArray
        assertEquals("active", (HiveUriTx.format(tx(keysChange), keys("posting")) as FormattedHiveUriTx.Refused).authorityKeyType)
    }

    @Test
    fun amountFormattingFollowsParseFloatAndToFixed() {
        assertEquals("1.500 HIVE", HiveUriTx.formatAmount("1.5 HIVE"))
        assertEquals("0.001 HBD", HiveUriTx.formatAmount("0.001 HBD"))
        assertEquals("2.000 HIVE", HiveUriTx.formatAmount("1.9999 HIVE"))
        assertEquals("1.234 HIVE", HiveUriTx.formatAmount("1.2341abc HIVE"))
        assertEquals("1500.000 HIVE", HiveUriTx.formatAmount("1.5e3 HIVE"))
        assertEquals("0.500 HIVE", HiveUriTx.formatAmount(".5 HIVE"))
        assertEquals("0.000 HIVE", HiveUriTx.formatAmount("0.0004 HIVE"))
        assertEquals("-1.000 HIVE", HiveUriTx.formatAmount("-1 HIVE"))
        listOf("0 HIVE", "abc HIVE", "1.5 hive", "1.5", "1.5  HIVE", "0.000 HIVE").forEach {
            assertEquals(null, HiveUriTx.formatAmount(it), it)
        }
    }
}
