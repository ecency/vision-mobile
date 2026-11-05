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
    }
}
