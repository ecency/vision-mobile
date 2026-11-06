package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #3633: RN transferFavoritesSheet: the account's favourites to pick a recipient from, searched, and added to. */
class TransferFavoritesTest {
    @Test
    fun theSheetListsSearchesAndAddsAFavourite() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        val saved = mutableListOf("dave")
        var addStatus = 200
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            when {
                r.url.contains("/private-api/favorites?format=wrapped") -> HttpResult(
                    200,
                    saved.mapIndexed { i, a -> """{"_id":"f$i","account":"$a","timestamp":$i}""" }.joinToString(",", "[", "]"),
                )
                r.url.endsWith("/private-api/favorites-add") -> HttpResult(addStatus, "{}").also {
                    if (addStatus == 200) saved += ProfileFixtures.body(r)["account"]!!.jsonPrimitive.content
                }
                else -> null
            }
        })
        with(ProfileFixtures) { signIn(app) }
        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()
        val m = app.env.transferFavoritesModel()
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("dave"), m.current.visible)

        // RN normalizeUsername: a typed "@" and spaces and capitals do not count
        m.toggleAdd()
        assertTrue(m.current.adding)
        m.setAddName("  @Erin ")
        m.add()
        testScheduler.advanceUntilIdle()
        val sent = app.authBackend.sent.filter { it.url.endsWith("/private-api/favorites-add") }
        assertEquals("erin", ProfileFixtures.body(sent.single())["account"]!!.jsonPrimitive.content)
        assertFalse(m.current.adding)
        assertEquals("", m.current.addName)
        // the list is read again and the search shows the new one (RN setSearch(username) + refetch)
        assertEquals(listOf("dave", "erin"), m.current.accounts)
        assertEquals(listOf("erin"), m.current.visible)
        m.setQuery("@da")
        assertEquals(listOf("dave"), m.current.visible)

        // nothing typed: nothing sent
        m.toggleAdd()
        m.setAddName(" @ ")
        m.add()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.authBackend.sent.count { it.url.endsWith("/private-api/favorites-add") })

        // a refused add says so and keeps the typed name
        addStatus = 500
        m.setAddName("frank")
        m.add()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(TransferFavoritesCopy.ADD_ERROR), toasts())
        assertTrue(m.current.adding)
        assertEquals("frank", m.current.addName)
        assertFalse(m.current.isSaving)

        // a Hive name has no whitespace: "@ gina" is gina, not " gina" (RN sent the space)
        addStatus = 200
        assertEquals("gina", TransferFavoritesModel.normalize("@ gi na"))
        m.setAddName("@ gina")
        m.add()
        testScheduler.advanceUntilIdle()
        assertEquals("gina", ProfileFixtures.body(app.authBackend.sent.last { it.url.endsWith("/private-api/favorites-add") })["account"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSlowAddKeepsANewDraftAndAnOlderListReadNeverLandsLast() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        val saved = mutableListOf("dave")
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            when {
                r.url.contains("/private-api/favorites?format=wrapped") -> HttpResult(
                    200,
                    saved.mapIndexed { i, a -> """{"_id":"f$i","account":"$a","timestamp":$i}""" }.joinToString(",", "[", "]"),
                )
                r.url.endsWith("/private-api/favorites-add") -> HttpResult(200, "{}").also { saved += ProfileFixtures.body(r)["account"]!!.jsonPrimitive.content }
                else -> null
            }
        })
        with(ProfileFixtures) { signIn(app) }
        // the sheet's first read is slow: it answers the list from before the add, after the add's own read
        val firstRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val heldAdd = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        app.authBackend.gate = { r ->
            if (r.url.contains("/private-api/favorites?format=wrapped") && reads++ == 0) firstRead.await()
            if (r.url.endsWith("/private-api/favorites-add")) heldAdd.await()
        }
        val m = app.env.transferFavoritesModel()
        m.start()
        testScheduler.runCurrent()
        m.toggleAdd()
        m.setAddName("erin")
        m.add()
        testScheduler.runCurrent()
        // typed while saving: kept
        m.setAddName("fra")
        heldAdd.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.adding)
        assertEquals("fra", m.current.addName)
        assertEquals(listOf("dave", "erin"), m.current.accounts)
        firstRead.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("dave", "erin"), m.current.accounts)
        assertEquals(listOf("erin"), m.current.visible)
    }

    @Test
    fun anAddThatAnswersAfterAnAccountSwitchChangesNothing() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        var addStatus = 200
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            when {
                r.url.contains("/private-api/favorites?format=wrapped") -> HttpResult(200, """[{"_id":"f0","account":"dave","timestamp":0}]""")
                r.url.endsWith("/private-api/favorites-add") -> HttpResult(addStatus, "{}")
                else -> null
            }
        })
        com.ecency.core.secure.KeyVault(app.store).save(
            com.ecency.core.secure.VaultAccount("spike-bob", com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = com.ecency.core.hive.GoldenVectors.WIF, accessToken = "access-spike-bob"),
        )
        with(ProfileFixtures) { signIn(app) }
        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()
        val m = app.env.transferFavoritesModel()
        m.start()
        testScheduler.advanceUntilIdle()
        for (status in listOf(200, 500)) {
            addStatus = status
            val held = kotlinx.coroutines.CompletableDeferred<Unit>()
            app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/favorites-add")) held.await() }
            if (!m.current.adding) m.toggleAdd()
            m.setAddName("erin")
            m.add()
            testScheduler.runCurrent()
            app.env.session.switchTo(if (app.env.session.current.username == "spike-bob") TestApp.USER else "spike-bob")
            testScheduler.advanceUntilIdle()
            val seen = toasts()
            val reads = app.authBackend.sent.count { it.url.contains("/private-api/favorites?format=wrapped") }
            held.complete(Unit)
            testScheduler.advanceUntilIdle()
            // the old account's add says nothing to the new one, whether the switch cut it off or it answered after
            assertFalse(TransferFavoritesCopy.ADD_ERROR in seen + toasts(), "status $status")
            assertEquals("erin", m.current.addName, "status $status")
            assertEquals("", m.current.query, "status $status")
            assertFalse(m.current.isSaving, "status $status")
            assertEquals(reads, app.authBackend.sent.count { it.url.contains("/private-api/favorites?format=wrapped") }, "status $status")
        }
    }
}
