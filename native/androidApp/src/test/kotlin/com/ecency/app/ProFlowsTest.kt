package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.DemoStore
import com.ecency.core.app.NavState
import com.ecency.core.app.ProCopy
import com.ecency.core.app.PurchaseService
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ecency Pro on the Perks screen (issue #3555) against the offline demo store and a fake Ecency: nothing is bought. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val store = DemoStore(PurchaseService.PLAY)
    private val env by lazy { node.env().also { it.store = store } }

    /** Ecency's Pro roster; an accepted Pro order puts its account on it. */
    private val roster = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var orderStatus = 201

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
        override fun openStoreForUpdate() {}
    }

    private fun signIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        node.auth.rest = { r ->
            when {
                r.url.endsWith("/private-api/purchase-order") -> HttpResult(orderStatus, "{}").also {
                    if (orderStatus == 201) roster += Json.parseToJsonElement(r.body!!).jsonObject.getValue("user").jsonPrimitive.content
                }
                r.url.endsWith("/private-api/pro-members") -> HttpResult(200, """{"members":[${roster.joinToString(",") { "\"$it\"" }}]}""")
                r.url.endsWith("/private-api/quests") -> HttpResult(200, """{"daily":[],"weekly":[],"monthly":[]}""")
                else -> null
            }
        }
    }

    private fun launch() = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, Navigator(NavState().push(AppScreen.Perks))) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun click(tag: String) {
        val n = compose.onNodeWithTag(tag, useUnmergedTree = true)
        runCatching { n.performScrollTo() }
        // the sheet's nodes: a semantics click (a touch may land while the sheet still slides in)
        n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun hasText(t: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun orders() = node.auth.requests.filter { it.url.endsWith("/private-api/purchase-order") }

    @Test
    fun aNonMemberBuysProAndSeesItActivate() {
        signIn()
        launch()
        waitFor("proCardText")
        compose.await("the store price") { text("proCardText").contains("$19.99") }
        click("proCard")
        waitFor("proSheet")
        assertTrue(exists("proBenefit:0") && exists("proBenefit:3"))
        waitFor("proBuy")
        click("proBuy")
        compose.await("the order") { orders().isNotEmpty() }
        val o = Json.parseToJsonElement(orders().single().body!!).jsonObject
        assertEquals("1999pro", o.getValue("product").jsonPrimitive.content)
        assertEquals("tester", o.getValue("user").jsonPrimitive.content)
        // activating, then the success once the roster (read again past its cache) has the account
        compose.await("the success") { exists("proSuccess") }
        assertTrue(hasText(ProCopy.SUCCESS_TITLE) && hasText(ProCopy.SUCCESS_BODY))
        assertFalse("no second buy button after the purchase", exists("proBuy"))
        click("proClose")
        compose.await("the member card") { exists("proCardMember") }
        assertEquals(ProCopy.MEMBER, text("proCardMember"))
    }

    @Test
    fun aMemberSeesTheMemberStateAndCanExtend() {
        signIn()
        roster += "tester"
        launch()
        waitFor("proCardMember")
        click("proCard")
        waitFor("proMember")
        waitFor("proBuy")
        compose.await("the extend label") { hasText("Extend by 12 months for $19.99") }
        assertTrue(hasText(ProCopy.EXTEND_NOTE))
    }

    @Test
    fun aCancelledPurchaseOrdersNothing() {
        signIn()
        store.mode = DemoStore.Mode.CANCEL
        launch()
        waitFor("proCard")
        click("proCard")
        waitFor("proBuy")
        click("proBuy")
        compose.waitForIdle()
        compose.await("the buy button back") { exists("proBuy") && !exists("proBuying") }
        assertTrue(orders().isEmpty())
        assertFalse(exists("proAlertOk"))
        assertFalse(exists("proActivating"))
    }

    @Test
    fun aRefusedOrderAlerts() {
        signIn()
        orderStatus = 400
        launch()
        waitFor("proCard")
        click("proCard")
        waitFor("proBuy")
        click("proBuy")
        compose.await("the alert") { exists("proAlertOk") }
        assertFalse(exists("proSuccess"))
    }
}
