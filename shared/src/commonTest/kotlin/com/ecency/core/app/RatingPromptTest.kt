package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RN maybeRequestReview: the store rating prompt is asked for once, after a vote or publish that went out, when
 * the app was used for 3 days and 5 app starts. Here through votes (the publish path is in EditorModelTest),
 * with the platform's prompt as a counter.
 */
class RatingPromptTest {
    private inner class World(val scope: TestScope) {
        var reject = false
        val app = TestApp(scope) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> if (reject) HttpResult(500, "down") else FakeTransport.result(req, "null")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }

        init {
            app.signIn()
            app.env.session.start()
            // the prompt is only asked for over an unlocked app: the lock model runs, as in the apps
            app.env.appLock.start()
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        /** Used long enough for RN's thresholds. */
        fun engaged() {
            repeat(4) { app.env.rating.recordSession() }
            app.now += 3 * AppRating.DAY_MILLIS
            assertTrue(app.env.rating.shouldRequest())
        }

        fun vote(permlink: String, weight: Int = 10000) {
            app.env.votes.vote("alice", permlink, weight)
            idle()
        }
    }

    @Test
    fun aVoteThatWentOutAsksOnceAndNeverAgain() = runTest {
        val w = World(this)
        var asked = 0
        w.app.env.requestReviewAsync = { asked++; true }
        // not yet: one app start, first used today
        w.vote("early")
        assertEquals(0, asked)
        w.engaged()
        w.vote("one")
        assertEquals(1, asked)
        assertFalse(w.app.env.rating.shouldRequest())
        w.vote("two")
        w.vote("three", weight = -10000)
        assertEquals(1, asked, "once for the life of the installation")
    }

    @Test
    fun aFailedOrRemovedVoteNeverAsks() = runTest {
        val w = World(this)
        var asked = 0
        w.app.env.requestReviewAsync = { asked++; true }
        w.engaged()
        w.reject = true
        w.vote("refused")
        assertEquals(0, asked, "never on a failure path")
        w.reject = false
        w.vote("removed", weight = 0)
        assertEquals(0, asked, "RN skips a removed vote")
        assertTrue(w.app.env.rating.shouldRequest(), "the one request is still there")
        w.vote("fine")
        assertEquals(1, asked)
    }

    @Test
    fun aPromptThePlatformCouldNotShowLeavesTheRequestUnspent() = runTest {
        val w = World(this)
        var tries = 0
        var available = false
        // Android: the Play flow answers later, and false when it could not run (no Play Store, app not in front)
        w.app.env.requestReviewAsync = { tries++; available }
        w.engaged()
        w.vote("one")
        assertEquals(1, tries)
        assertTrue(w.app.env.rating.shouldRequest(), "RN: requested only when the request was made")
        available = true
        w.vote("two")
        assertEquals(2, tries)
        assertFalse(w.app.env.rating.shouldRequest())
        w.vote("three")
        assertEquals(2, tries)
        // a platform prompt that throws is not a request either, and does not wedge the next one
        val w2 = World(this)
        var throwing = true
        var asked = 0
        w2.app.env.requestReviewAsync = { if (throwing) throw IllegalStateException("no store") else { asked++; true } }
        w2.engaged()
        w2.vote("one")
        assertTrue(w2.app.env.rating.shouldRequest())
        throwing = false
        w2.vote("two")
        assertEquals(1, asked)
    }

    @Test
    fun twoActionsInTheDelayWindowAskOnce() = runTest {
        val w = World(this)
        var asked = 0
        w.app.env.requestReview = { asked++; true }
        w.engaged()
        w.app.env.votes.vote("alice", "one", 10000)
        w.app.env.votes.vote("alice", "two", 10000)
        w.idle()
        assertEquals(1, asked)
    }
}
