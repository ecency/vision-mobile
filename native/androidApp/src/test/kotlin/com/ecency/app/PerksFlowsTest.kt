package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.app.RouteName
import com.ecency.core.app.SpinCopy
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Perks (quests, streak freeze, ways to spend) and the spin game on Android, against fakes only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerksFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
        override fun openStoreForUpdate() {}
    }

    private val quests = """{"daily":[{"id":"checkin","progress":1},{"id":"post","progress":0},{"id":"comment","progress":2},{"id":"vote","progress":4}],""" +
        """"weekly":[{"id":"post","progress":3}],"monthly":[{"id":"post","progress":7}],"streak":{"current":4,"at_risk":false,"freezes_owned":1}}"""

    @Volatile private var freezeStatus = 200

    @Volatile private var game = """{"status":1,"key":"day-key","remaining":1,"next_date":"2026-10-07T12:00:00Z"}"""

    private fun signIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        node.auth.rest = { r ->
            when {
                r.url.endsWith("/private-api/quests") -> HttpResult(200, quests)
                r.url.endsWith("/private-api/streak-freeze/buy") -> HttpResult(freezeStatus, if (freezeStatus == 200) """{"owned":2,"points":10}""" else "{}")
                r.url.endsWith("/private-api/get-game") -> HttpResult(200, game)
                r.url.endsWith("/private-api/post-game") -> HttpResult(200, """{"score":25}""")
                else -> null
            }
        }
    }

    private fun launch(navigator: Navigator, fontScale: Float = 1f) = compose.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
            EcencyTheme(dark = false) { EcencyApp(env, host, navigator) }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The rings on the screen's centre line, round, and inside their part (shrunk where 230 dp do not fit). */
    private fun assertTheWheelIsCentred(rings: String) {
        val screen = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxBy { it.size.height }.boundsInRoot
        val part = bounds("spinWheelPart")
        val box = bounds(rings)
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("rings ($box) on the centre line of the screen ($screen)", screen.center.x, box.center.x, 2 * density)
        assertEquals("rings centred in their part ($part)", part.center.y, box.center.y, 2 * density)
        assertEquals("rings round", box.width, box.height, 1 * density)
        assertTrue("rings ($box) inside their part ($part)", box.height <= part.height + density && box.width <= part.width + density)
    }

    @Test
    fun theWheelIsCentredAtRestAndWhileItTurns() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.SpinGame)))
        waitFor("spinButton")
        assertTheWheelIsCentred("spinRings")
        val answers = ArrayDeque(listOf("""{"status":1,"key":"day-key","remaining":1}"""))
        val rest = node.auth.rest
        node.auth.rest = { r -> if (r.url.endsWith("/get-game")) HttpResult(200, synchronized(answers) { answers.removeFirstOrNull() } ?: game) else rest?.invoke(r) }
        compose.onNodeWithTag("spinButton").performClick()
        until { exists("spinRingsTurning") }
        assertTheWheelIsCentred("spinRingsTurning")
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun onAShortPhoneAtTheLargestFontTheWheelShrinksAndStaysCentred() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.SpinGame)), fontScale = 2f)
        waitFor("spinButton")
        assertTheWheelIsCentred("spinRings")
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the rings gave up room (${bounds("spinRings")})", bounds("spinRings").height < 230 * density)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 10 s")
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun waitFor(tag: String) = until { exists(tag) }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun click(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()

    private fun requests(suffix: String) = node.auth.requests.filter { it.url.endsWith(suffix) }

    @Test
    fun perksShowsTheQuestsTheStreakAndTheTiers() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.Perks)))
        waitFor("quest:daily:vote")
        assertEquals("4/10", text("questCount:vote"))
        assertEquals("1/1", text("questCount:checkin"))
        assertFalse("the spin has its own screen", exists("quest:daily:spin"))
        assertTrue(exists("perksStreak") && exists("perksFreezeOwned") && exists("perksFreezeBuy"))
        assertFalse("Esie cheers only a finished tier", exists("esie"))
        click("tier:weekly")
        waitFor("quest:weekly:post")
        assertEquals("3/5", text("questCount:post"))
        click("tier:monthly")
        waitFor("quest:monthly:post")
        assertEquals("7/20", text("questCount:post"))
        val sent = requests("/private-api/quests").first()
        assertEquals("""{"username":"tester"}""", sent.body)
    }

    @Test
    fun aFinishedTierGetsHappyEsie() {
        signIn()
        val done = """{"daily":[{"id":"checkin","progress":1},{"id":"post","progress":1},{"id":"comment","progress":3},{"id":"vote","progress":10},""" +
            """{"id":"reblog","progress":1}],"weekly":[],"monthly":[],"streak":{"current":5,"at_risk":false,"freezes_owned":0}}"""
        node.auth.rest = { r -> if (r.url.endsWith("/private-api/quests")) HttpResult(200, done) else null }
        launch(Navigator(NavState().push(AppScreen.Perks)))
        waitFor("quest:daily:vote")
        waitFor("esie")
        assertEquals(com.ecency.core.esie.EsieMood.HAPPY, compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().config[com.ecency.app.ui.EsieMoodKey])
    }

    @Test
    fun theStreakFreezeAsksFirstAndBuysOnce() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.Perks)))
        waitFor("perksFreezeBuy")
        click("perksFreezeBuy")
        waitFor("freezeCancel")
        compose.onNodeWithTag("freezeCancel").performClick()
        compose.waitForIdle()
        assertTrue("cancel spends nothing", requests("/streak-freeze/buy").isEmpty())
        click("perksFreezeBuy")
        waitFor("freezeConfirm")
        compose.onNodeWithTag("freezeConfirm").performClick()
        until { requests("/streak-freeze/buy").isNotEmpty() }
        compose.waitForIdle()
        val body = Json.parseToJsonElement(requests("/streak-freeze/buy").single().body!!).jsonObject
        assertEquals("access-tester", body.getValue("code").jsonPrimitive.content)
        assertEquals(32, body.getValue("idempotency_key").jsonPrimitive.content.length)
    }

    @Test
    fun notEnoughPointsForTheFreezeOpensGetPoints() {
        signIn()
        freezeStatus = 402
        val navigator = Navigator(NavState().push(AppScreen.Perks))
        launch(navigator)
        waitFor("perksFreezeBuy")
        click("perksFreezeBuy")
        waitFor("freezeConfirm")
        compose.onNodeWithTag("freezeConfirm").performClick()
        // RN: the Boost (Get Points) screen
        until { navigator.state.stack.lastOrNull()?.screen?.route == RouteName.BOOST }
        waitFor("getPoints")
    }

    @Test
    fun theSpendOptionsOpenTheirScreens() {
        signIn()
        val navigator = Navigator(NavState().push(AppScreen.Perks))
        launch(navigator)
        waitFor("spend:rc_topup")
        click("spend:rc_topup")
        until { navigator.state.stack.lastOrNull()?.screen == AppScreen.Redeem("rc_topup") }
        compose.runOnIdle { navigator.pop() }
        waitFor("spend:spin")
        click("spend:spin")
        waitFor("spinGame")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun onAShortPhoneTheSpinResultKeepsThePackAndTheCountdownOnScreen() {
        signIn()
        // the offline demo store answers the 5 spins product, so the pack row has its real height
        env.store = com.ecency.core.app.DemoStore(com.ecency.core.app.PurchaseService.PLAY)
        game = """{"status":1,"key":"day-key","remaining":1}"""
        launch(Navigator(NavState().push(AppScreen.SpinGame)))
        waitFor("spinButton")
        val answers = ArrayDeque(listOf("""{"status":1,"key":"day-key","remaining":1}""", """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""))
        val rest = node.auth.rest
        node.auth.rest = { r -> if (r.url.endsWith("/get-game")) HttpResult(200, synchronized(answers) { answers.removeFirstOrNull() } ?: """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}""") else rest?.invoke(r) }
        compose.onNodeWithTag("spinButton").performClick()
        until {
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            exists("spinTimer")
        }
        assertEquals(com.ecency.core.esie.EsieMood.HAPPY, compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().config[com.ecency.app.ui.EsieMoodKey])
        // the app's window (the toast layer is a second, smaller root)
        val screen = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxBy { it.size.height }.boundsInRoot
        for (tag in listOf("spinTimer", "spinProduct")) {
            val box = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag ($box) inside the screen ($screen)", box.bottom <= screen.bottom && box.top >= screen.top)
        }
        // not squeezed by the column either: each keeps its full height
        val density = compose.activity.resources.displayMetrics.density
        until { compose.onNodeWithTag("spinProduct", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height > 0f }
        for ((tag, min) in listOf("spinTimer" to 20f, "spinProduct" to 40f)) {
            val box = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $box keeps its height", box.height >= min * density)
        }
    }

    @Test
    fun spinClaimsOnceShowsTheScoreThenTheTimer() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.SpinGame)))
        waitFor("spinButton")
        assertEquals("1", text("spinCount"))
        assertTrue(exists("spinTags"))
        assertFalse("Esie cheers only a result", exists("esie"))
        game = """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""
        // the first status after the press must still hand out the key: answer it, then "none left"
        val answers = ArrayDeque(listOf("""{"status":1,"key":"day-key","remaining":1}""", """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""))
        val rest = node.auth.rest
        node.auth.rest = { r -> if (r.url.endsWith("/get-game")) HttpResult(200, synchronized(answers) { answers.removeFirstOrNull() } ?: game) else rest?.invoke(r) }
        compose.onNodeWithTag("spinButton").performClick()
        until { exists("spinRingsTurning") }
        until { requests("/post-game").isNotEmpty() }
        assertEquals("day-key", Json.parseToJsonElement(requests("/post-game").single().body!!).jsonObject.getValue("key").jsonPrimitive.content)
        // the rings turn for 8 s, then the score and the 5 spins pack with the countdown
        until {
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            exists("spinScore")
        }
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(SpinCopy.score(25))).fetchSemanticsNodes().isNotEmpty())
        // README "Esie": the won spin is a success moment
        assertEquals(com.ecency.core.esie.EsieMood.HAPPY, compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().config[com.ecency.app.ui.EsieMoodKey])
        waitFor("spinProduct")
        assertTrue(text("spinTimer").startsWith("Next free spin in "))
        assertEquals(1, requests("/post-game").size)
    }
}
