package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN qrModalView, the share intent flow and ProposalVoteRequest. */
class PlatformModelsTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun stored(name: String) = VaultAccount(
        name, AuthType.MASTER_PASSWORD,
        postingKey = AppTestKeys.wif(name, KeyRole.POSTING), activeKey = AppTestKeys.wif(name, KeyRole.ACTIVE), accessToken = "access-$name",
    )

    private suspend fun app(scope: TestScope, signedIn: Boolean = true, handler: (suspend FakeTransport.(Sent) -> HttpResult?)? = null): TestApp {
        val base = appWithAccounts(scope)
        val app = if (handler == null) base else TestApp(scope) { req -> handler(req) ?: base.handler(this, req) }
        if (signedIn) KeyVault(app.store).run { save(stored(alice)); setCurrentUsername(alice) }
        app.env.session.start()
        scope.testScheduler.advanceUntilIdle()
        return app
    }

    // ---- QR ----

    @Test
    fun theScannerTakesOneCodePerOpen() = runTest {
        val app = app(this)
        val qr = app.env.qrScanModel(QrPurpose.VALUE)
        qr.scanned("  @spike-bob  ")
        qr.scanned("spike-carol")
        assertEquals(QrOutcome.Value("@spike-bob"), qr.current.outcome)
        assertTrue(qr.current.handled)
        qr.reset()
        assertFalse(qr.current.handled)
        qr.scanned("spike-carol")
        assertEquals(QrOutcome.Value("spike-carol"), qr.current.outcome)
    }

    @Test
    fun scannedCodesGoThroughTheLinkHandling() = runTest {
        val app = app(this)
        fun scan(value: String): QrOutcome? = app.env.qrScanModel(QrPurpose.LINKS).also { it.scanned(value) }.current.outcome

        assertEquals(QrOutcome.LinkHandled, scan("ecency://transfer?to=spike-bob&amount=1&asset=HIVE"))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt, "a signing code still asks first")
        app.env.authLinks.decline()

        val post = assertIs<QrOutcome.Open>(scan("https://ecency.com/hive-125125/@spike-bob/a-post"))
        assertEquals(AppScreen.Post("spike-bob", "a-post"), post.entry.screens.last())
        assertEquals(QrOutcome.Browser("https://example.com/page"), scan("https://example.com/page"))
        assertIs<QrOutcome.Unsupported>(scan("just some text"))
        assertIs<QrOutcome.Unsupported>(scan("javascript:alert(1)"))
        assertIs<QrOutcome.Unsupported>(scan("https://example.com/" + "a".repeat(QrScanModel.MAX_VALUE)))
    }

    @Test
    fun aScannedRecipientFillsTheTransfer() = runTest {
        val app = app(this)
        val transfer = assertNotNull(app.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive")))
        val qr = app.env.qrScanModel(QrPurpose.VALUE)
        qr.scanned("https://ecency.com/@$bob")
        transfer.scanned(assertIs<QrOutcome.Value>(qr.current.outcome).text)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, transfer.current.recipient)
    }

    // ---- share ----

    @Test
    fun aShareWaitsForTheUnlockThenAsksWhereItGoes() = runTest {
        val app = app(this)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.isLocked)
        app.env.share.receive(listOf("hello", " https://example.com "), listOf("content://photos/1"))
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.share.current.choosing, "nothing opens past the PIN")
        "1234".forEach { app.env.appLock.enterDigit(it - '0') }
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.share.current.choosing)
        assertEquals(1, app.env.share.current.images)
    }

    @Test
    fun blogPostOpensTheEditorWithTheContentOnceAndOnlyForItsAccount() = runTest {
        val app = app(this)
        val share = app.env.share
        share.receive(listOf("hello", "https://example.com"), listOf("content://photos/1", "content://photos/2"))
        testScheduler.advanceUntilIdle()
        share.choose(null) // closing the sheet is Blog Post (RN)
        val nav = assertIs<ShareNavigation.Editor>(share.current.navigation)
        assertEquals(AppScreen.Editor(EditorArgs(EditorKind.NEW_POST)), nav.screen)
        assertFalse(share.current.choosing)
        assertNull(share.takeForEditor(bob))
        val taken = assertNotNull(share.takeForEditor(alice))
        assertEquals("hello\nhttps://example.com", taken.text)
        assertEquals(listOf("content://photos/1", "content://photos/2"), taken.images)
        assertNull(share.takeForEditor(alice), "once")
    }

    @Test
    fun waveOpensTheComposerWithTheText() = runTest {
        val app = app(this)
        val share = app.env.share
        share.receive(listOf("a wave"), listOf("content://photos/1"))
        testScheduler.advanceUntilIdle()
        share.choose(ShareTarget.WAVE)
        assertEquals(ShareNavigation.Wave, share.current.navigation)
        assertEquals("a wave", app.env.waves.takeComposeText())
        assertEquals(listOf("content://photos/1"), share.takeImagesForWave(alice))
        assertTrue(share.takeImagesForWave(alice).isEmpty())
    }

    @Test
    fun signedOutTheShareWaitsForTheLogin() = runTest {
        val app = app(this, signedIn = false)
        val share = app.env.share
        share.receive(listOf("hello"), emptyList())
        testScheduler.advanceUntilIdle()
        assertEquals(ShareNavigation.Login, share.current.navigation)
        assertFalse(share.current.choosing)
        share.consumeNavigation()
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertTrue(share.current.choosing, "after the sign-in the sheet comes up")
    }

    @Test
    fun backingOutOfTheLoginDropsTheShare() = runTest {
        val app = app(this, signedIn = false)
        val share = app.env.share
        share.receive(listOf("hello"), emptyList())
        testScheduler.advanceUntilIdle()
        share.loginDismissed()
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertFalse(share.current.choosing)
    }

    @Test
    fun aPickedShareNeverReachesAnotherAccount() = runTest {
        val app = app(this)
        KeyVault(app.store).save(stored(bob))
        val share = app.env.share
        share.receive(listOf("private draft"), emptyList())
        testScheduler.advanceUntilIdle()
        share.choose(ShareTarget.BLOG)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertNull(share.takeForEditor(bob))
        app.env.session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        assertNull(share.takeForEditor(alice), "dropped on the account change")
    }

    @Test
    fun aShareExpiresAndIsCapped() = runTest {
        val app = app(this)
        val share = app.env.share
        share.receive(listOf("x".repeat(ShareModel.MAX_TEXT + 10)), (1..20).map { "content://photos/$it" })
        testScheduler.advanceUntilIdle()
        assertEquals(ImageUploader.MAX_IMAGES_PER_PICK, share.current.images)
        share.choose(ShareTarget.BLOG)
        app.now += ShareModel.TTL_MILLIS + 1
        assertNull(share.takeForEditor(alice), "an unclaimed share expires")
        share.receive(listOf("   "), emptyList())
        testScheduler.advanceUntilIdle()
        assertFalse(share.current.choosing, "nothing to share")
    }

    // ---- proposal vote ----

    private val proposals = """{"proposals":[
        {"id":1,"proposal_id":300,"creator":"ecency","status":"expired"},
        {"id":2,"proposal_id":342,"creator":"ecency","status":"active"},
        {"id":3,"proposal_id":350,"creator":"ecency","status":"inactive"},
        {"id":4,"proposal_id":400,"creator":"someone","status":"active"}]}"""

    /** list_proposals fails while this is set (a node down at launch). */
    private var proposalsDown = false

    /** get_accounts fails while this is set. */
    private var accountsDown = false

    /** [newAccount]: one post, so the Getting started card still asks things of it (`WavesOnboarding.isNewAccount`). */
    private suspend fun proposalApp(scope: TestScope, voted: List<Long> = emptyList(), signedIn: Boolean = true, newAccount: Boolean = false) = app(scope, signedIn) { req ->
        when (req.method) {
            "database_api.list_proposals" -> if (proposalsDown) HttpResult(500, "") else FakeTransport.result(req, proposals)
            "condenser_api.get_accounts" -> if (accountsDown) HttpResult(500, "") else FakeTransport.result(
                req,
                "[" + AppTestKeys.accountJson(alice).replace("\"post_count\":1", "\"post_count\":${if (newAccount) 1 else 50}") + "]",
            )
            "database_api.list_proposal_votes" -> FakeTransport.result(
                req,
                """{"proposal_votes":[${voted.joinToString(",") { """{"voter":"$alice","proposal":{"proposal_id":$it}}""" }},{"voter":"zz-other","proposal":{"proposal_id":350}}]}""".replace("[,", "["),
            )
            else -> null
        }
    }

    @Test
    fun theCardShowsForTheNewestVotableEcencyProposal() = runTest {
        val app = proposalApp(this)
        val model = app.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
        assertEquals(350L, model.current.proposalId, "the newest non-expired @ecency proposal, upcoming ones too")
        assertEquals(ProposalCopy.TITLE, model.current.title)
    }

    @Test
    fun aNewAccountGetsTheProposalCardOnlyOnALaterRunAfterGettingStarted() = runTest {
        val app = proposalApp(this, newAccount = true)
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "not while the Getting started card still asks things")
        // the checklist is dismissed in this run: the card still waits for a later one
        app.env.wavesOnboarding.start()
        testScheduler.advanceUntilIdle()
        app.env.wavesOnboarding.dismiss()
        app.env.proposalVote.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "never in the same run as the checklist")
        val nextRun = ProposalVoteModel(app.env)
        nextRun.start()
        testScheduler.advanceUntilIdle()
        assertTrue(nextRun.current.visible, "a later run asks")
    }

    @Test
    fun aNewAccountsFailedFirstReadStillKeepsTheCardOutOfThisRun() = runTest {
        proposalsDown = true
        val app = proposalApp(this, newAccount = true)
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible)
        proposalsDown = false
        app.env.wavesOnboarding.start()
        testScheduler.advanceUntilIdle()
        app.env.wavesOnboarding.dismiss()
        app.env.proposalVote.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "Getting started was asked of it in this run")
    }

    @Test
    fun aNewAccountWhoseAccountReadFailsGetsNoCardBeforeGettingStartedLoads() = runTest {
        // PR #3730 review: unknown is not established; the proposal reads working must not let the card in on day one
        accountsDown = true
        val app = proposalApp(this, newAccount = true)
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "the account read failed: nothing says the account is established")
    }

    @Test
    fun anEstablishedAccountWhoseAccountReadFailedGetsTheCardOnTheNextRead() = runTest {
        accountsDown = true
        val app = proposalApp(this)
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "as for any failed query, the card waits")
        accountsDown = false
        app.env.proposalVote.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.proposalVote.current.visible, "the account read again: established")
    }

    @Test
    fun aVoteThatLandsAfterGettingStartedShowedLeavesTheCardHidden() = runTest {
        // PR #3730 review: the vote's thanks must not bring the card back beside Getting started
        val app = proposalApp(this)
        var posts = 50
        val changing = TestApp(this) { req ->
            when (req.method) {
                // established when the card read it, new by the time Getting started reads it (posts deleted meanwhile)
                "condenser_api.get_accounts" -> FakeTransport.result(
                    req,
                    "[" + AppTestKeys.accountJson(alice).replace("\"post_count\":1", "\"post_count\":$posts") + "]",
                )
                else -> app.handler(this, req)
            }
        }
        changing.authBackend.rest = { r -> if (r.url.endsWith("/private-api/quests")) HttpResult(200, """{"daily":[],"streak":{"current":0}}""") else null }
        KeyVault(changing.store).run { save(stored(alice)); setCurrentUsername(alice) }
        changing.env.session.start()
        testScheduler.advanceUntilIdle()
        val model = changing.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
        posts = 1
        changing.env.api.accounts.getAccount(alice, refresh = true)
        changing.env.wavesOnboarding.start()
        model.vote()
        testScheduler.advanceUntilIdle()
        assertTrue(changing.env.wavesOnboarding.current.visible, "Getting started shows")
        assertTrue(model.current.voted, "the vote went through")
        assertFalse(model.current.visible, "not beside Getting started")
    }

    @Test
    fun onceGettingStartedShowedTheCardStaysOutOfThisRunWhateverTheReads() = runTest {
        // PR #3730 review: the first account read failed, so only Getting started's own state can tell
        accountsDown = true
        val app = proposalApp(this, newAccount = true)
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/quests")) HttpResult(200, """{"daily":[],"streak":{"current":0}}""") else null }
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        accountsDown = false
        app.env.wavesOnboarding.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.wavesOnboarding.current.visible, "Getting started shows")
        assertFalse(app.env.proposalVote.current.visible, "not beside Getting started")
        app.env.wavesOnboarding.dismiss()
        app.env.proposalVote.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proposalVote.current.visible, "nor later in the same run")
        assertTrue(ProposalVoteModel(app.env).also { it.start(); testScheduler.advanceUntilIdle() }.current.visible, "a later run asks")
    }

    @Test
    fun anEstablishedAccountGetsTheProposalCardAtOnce() = runTest {
        val app = proposalApp(this)
        app.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.proposalVote.current.visible)
    }

    @Test
    fun theCardStartedBeforeTheAccountsWereReadShowsForTheAccount() = runTest {
        val base = appWithAccounts(this)
        val app = TestApp(this) { req ->
            when (req.method) {
                "database_api.list_proposals" -> FakeTransport.result(req, proposals)
                "database_api.list_proposal_votes" -> FakeTransport.result(req, """{"proposal_votes":[]}""")
                // an established account (a new one waits for Getting started, #3728)
                "condenser_api.get_accounts" -> FakeTransport.result(req, "[" + AppTestKeys.accountJson(alice).replace("\"post_count\":1", "\"post_count\":50") + "]")
                else -> base.handler(this, req)
            }
        }
        KeyVault(app.store).run { save(stored(alice)); setCurrentUsername(alice) }
        app.env.proposalVote.start() // at launch, before the session read the vault
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.proposalVote.current.username)
        assertTrue(app.env.proposalVote.current.visible)
    }

    @Test
    fun theCardHidesSignedOutAndWhenAlreadyVoted() = runTest {
        val out = proposalApp(this, signedIn = false)
        out.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(out.env.proposalVote.current.visible)
        val voted = proposalApp(this, voted = listOf(350))
        voted.env.proposalVote.start()
        testScheduler.advanceUntilIdle()
        assertFalse(voted.env.proposalVote.current.visible, "another voter's vote does not count")
    }

    @Test
    fun votingSignsUpdateProposalVotesWithTheActiveKey() = runTest {
        val app = proposalApp(this)
        val model = app.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        model.vote()
        testScheduler.advanceUntilIdle()
        val sent = app.broadcasts().single()
        assertTrue("update_proposal_votes" in sent.body && "\"proposal_ids\":[350]" in sent.body && "\"approve\":true" in sent.body, sent.body)
        assertTrue(model.current.voted)
        assertTrue(model.current.visible, "the thanks show for the rest of the run")
        assertEquals(ProposalCopy.TITLE_VOTED, model.current.title)
        assertEquals(ProposalCopy.THANK_YOU, app.env.ui.current.toast?.text)
        // the next start remembers it
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
    }

    @Test
    fun aFailedVoteSaysSoAndKeepsTheCard() = runTest {
        val app = proposalApp(this)
        val failing = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> FakeTransport.error(req, -32000 + 1000, "Assert Exception: proposal 350 is not votable")
                else -> app.handler(this, req)
            }
        }
        KeyVault(failing.store).run { save(stored(alice)); setCurrentUsername(alice) }
        failing.env.session.start()
        testScheduler.advanceUntilIdle()
        val model = failing.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        model.vote()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.voted)
        assertTrue(model.current.visible)
        assertTrue(failing.env.ui.current.toast?.text.orEmpty().startsWith("Fail!"), failing.env.ui.current.toast?.text)
    }

    @Test
    fun dismissingForeverOrForThreeDays() = runTest {
        val app = proposalApp(this)
        val model = app.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        model.askDismiss()
        assertTrue(model.current.askingDismiss)
        model.dismiss(null)
        assertTrue(model.current.visible, "closing the choice changes nothing")
        model.askDismiss()
        model.dismiss(ProposalDismiss.LATER)
        assertFalse(model.current.visible)
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
        app.now += ProposalVoteModel.RE_REQUEST_MILLIS + 1
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible, "back after 3 days")
        model.dismiss(ProposalDismiss.FOREVER)
        app.now += 10 * ProposalVoteModel.RE_REQUEST_MILLIS
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
        // the choice names the account: a wipe removes it
        assertTrue(app.env.prefs.keys().any { it.startsWith(ProposalVoteModel.KEY_PREFIX) })
        AccountPrefs.clear(app.env.prefs)
        assertFalse(app.env.prefs.keys().any { it.startsWith(ProposalVoteModel.KEY_PREFIX) })
    }

    @Test
    fun anAccountChangeResetsTheCard() = runTest {
        val app = proposalApp(this)
        KeyVault(app.store).save(stored(bob))
        val model = app.env.proposalVote
        model.start()
        testScheduler.advanceUntilIdle()
        model.dismiss(ProposalDismiss.FOREVER)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.username)
        assertFalse(model.current.voted)
    }
}
