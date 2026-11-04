package com.ecency.core.app

import com.ecency.core.api.model.PostKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small rules of the parity gaps wave (gaps2) that need no fake server. */
class Gaps2Test {
    private fun card(image: String?, nsfw: Boolean = false) = PostCard(
        PostKey("alice", "p"), "alice", "", 50, "t", "s", image, "2026-10-06T10:00:00", 0, "test", null, null,
        0.0, "$0.000", false, 0, 0, false, false, false, false, emptyList(), null,
    ).copy(isNsfw = nsfw)

    @Test
    fun showImagesOffHoldsBackContentImagesUntilThatImageIsRevealed() {
        val reveals = ImageReveals()
        assertFalse(reveals.hides(card("https://img/a.png"), showImages = true))
        assertTrue(reveals.hides(card("https://img/a.png"), showImages = false))
        assertFalse(reveals.hides(card(null), showImages = false), "no image, no placeholder")
        assertFalse(reveals.hides(card("https://img/a.png", nsfw = true), showImages = false), "the NSFW placeholder is never gated")
        reveals.reveal("https://img/a.png")
        assertFalse(reveals.hides(card("https://img/a.png"), showImages = false))
        assertTrue(reveals.hides(card("https://img/b.png"), showImages = false), "one image at a time")
    }
}

class ScrollTopRuleTest {
    @Test
    fun postListsShowThePopupWhenScrollingUpFarDownAndHideItOnlyOnTap() {
        var now = 0L
        val rule = ScrollTopRule { now }
        assertFalse(rule.onScroll(6000.0), "scrolling down never shows it")
        assertFalse(rule.onScroll(4000.0).also { rule.onScroll(9000.0) }, "up but within 5000")
        assertTrue(rule.onScroll(8000.0), "up past 5000")
        assertTrue(rule.onScroll(12000.0), "post lists keep it while scrolling down again")
        rule.onTap()
        assertFalse(rule.visible)
        now = 500
        assertFalse(rule.onScroll(11000.0), "blocked for a second after the tap")
        now = 1500
        assertTrue(rule.onScroll(10000.0))
    }

    @Test
    fun wavesHideItAgainOnScrollDownOrNearTheTop() {
        val rule = ScrollTopRule(hidesOnScrollDown = true) { 0L }
        rule.onScroll(9000.0)
        assertTrue(rule.onScroll(8000.0))
        assertFalse(rule.onScroll(8500.0), "down")
        assertTrue(rule.onScroll(8000.0))
        assertFalse(rule.onScroll(4000.0), "within the threshold")
    }
}

class PayoutDetailsTest {
    private val now = 1_791_288_000_000L // 2026-10-06T12:00:00Z

    private fun post(
        pending: String = "1.500 HBD", author: String = "0.000 HBD", curator: String = "0.000 HBD", promoted: String = "0.000 HBD",
        max: String = "1000000.000 HBD", percentHbd: Int = 10000, payoutAt: String = "2026-10-13T12:00:00",
        beneficiaries: List<com.ecency.core.api.model.PostBeneficiary> = emptyList(),
    ) = com.ecency.core.api.model.Post(
        author = "alice", permlink = "p", pendingPayoutValue = pending, authorPayoutValue = author, curatorPayoutValue = curator,
        promoted = promoted, maxAcceptedPayout = max, percentHbd = percentHbd, payoutAt = payoutAt, beneficiaries = beneficiaries,
    )

    @Test
    fun aPendingPostShowsPotentialBreakdownBeneficiariesAndDateInRnOrder() {
        val props = PayoutProps(base = 0.25, quote = 1.0, hbdPrintRate = 10000.0)
        val rows = PayoutDetails.rows(
            post(beneficiaries = listOf(com.ecency.core.api.model.PostBeneficiary("ecency", 500), com.ecency.core.api.model.PostBeneficiary("bob", 1250)), promoted = "2.000 HBD"),
            props, now,
        )
        assertEquals(listOf(PayoutCopy.PROMOTED, PayoutCopy.POTENTIAL, PayoutCopy.BREAKDOWN, PayoutCopy.BENEFICIARIES, PayoutCopy.PAYOUT_DATE), rows.map { it.label })
        assertEquals("~$ 2.000", rows[0].value)
        assertEquals("~$ 1.500", rows[1].value)
        // half in HBD (all printed at a full print rate), half in HP at $0.25 a HIVE
        assertEquals("0.750 HBD\n3.000 HP", rows[2].value)
        assertEquals("ecency: 5.00%\nbob: 12.50%", rows[3].value)
        assertEquals("in 7 days", rows[4].value)
    }

    @Test
    fun aLowPrintRateMovesPartOfTheHbdIntoHive() {
        assertEquals("0.375 HBD\n1.500 HIVE\n3.000 HP", PayoutDetails.breakdown(1.5, 10000, PayoutProps(0.25, 1.0, 5000.0)))
        val p = PayoutProps(0.25, 1.0, 10000.0)
        assertEquals(PayoutDetails.breakdown(1.5, 10000, p), PayoutDetails.breakdown(1.5, 0, p), "RN: percent_hbd || 10000")
    }

    @Test
    fun paidOutDeclinedAndTinyPayouts() {
        val paid = PayoutDetails.rows(post(pending = "0.000 HBD", author = "3.000 HBD", curator = "1.000 HBD", payoutAt = "2026-09-29T12:00:00"), null, now)
        assertEquals(listOf(PayoutCopy.AUTHOR, PayoutCopy.CURATION, PayoutCopy.PAYOUT_DATE), paid.map { it.label })
        assertEquals("7 days ago", paid.last().value)
        val declined = PayoutDetails.rows(post(pending = "0.000 HBD", max = "0.000 HBD"), null, now)
        assertTrue(declined.any { it.label == PayoutCopy.MAX_ACCEPTED && it.value == "~$ 0.000" }, "RN shows the cap of a declined payout")
        val tiny = PayoutDetails.rows(post(pending = "0.010 HBD"), null, now)
        assertEquals(PayoutRow(PayoutCopy.WARN_ZERO, ""), tiny.last())
        assertFalse(tiny.any { it.label == PayoutCopy.BREAKDOWN }, "no breakdown before the chain values are read")
    }

    @Test
    fun fromNowFollowsMomentsThresholds() {
        fun at(seconds: Long) = PayoutDetails.fromNow(com.ecency.core.hive.ChainTime.fromEpochSeconds(now / 1000 + seconds), now)
        assertEquals("in a few seconds", at(30))
        assertEquals("in a minute", at(60))
        assertEquals("in 5 minutes", at(300))
        assertEquals("in an hour", at(3600))
        assertEquals("in 3 hours", at(3 * 3600))
        assertEquals("in a day", at(30 * 3600))
        assertEquals("a month ago", at(-30L * 86400))
        assertEquals("in 3 months", at(91L * 86400))
        assertEquals("in a year", at(400L * 86400))
        assertEquals("in 2 years", at(2 * 365L * 86400))
    }
}

class PostBadgesTest {
    private fun meta(json: String) = com.ecency.core.api.model.PostMetadata.parse(kotlinx.serialization.json.Json.parseToJsonElement(json))

    @Test
    fun aiDisclosureIsReadAsRnReadsIt() {
        assertTrue(meta("""{"ai_tools":{"writing_edit":true}}""").aiDisclosed)
        assertTrue(meta("""{"ai_tools":{"media_generation":1}}""").aiDisclosed)
        assertFalse(meta("""{"ai_tools":{"writing_edit":false,"media_generation":0}}""").aiDisclosed)
        assertFalse(meta("""{"ai_tools":{"other":true}}""").aiDisclosed)
        assertFalse(meta("""{"ai_tools":true}""").aiDisclosed)
        assertFalse(meta("""{}""").aiDisclosed)
    }

    @Test
    fun theSourceBadgeRulesDifferBetweenHeaderAndFooterAsInRn() {
        assertTrue(PostBadges.headerFromEcency("ecency/4.0.0-mobile"))
        assertTrue(PostBadges.headerFromEcency("Ecency"))
        assertFalse(PostBadges.headerFromEcency("notecency/1"), "lookalikes are not Ecency on cards")
        assertFalse(PostBadges.headerFromEcency(null))
        assertTrue(PostBadges.footerFromEcency(null), "the footer counts no app as Ecency")
        assertTrue(PostBadges.footerFromEcency("notecency/1"))
        assertFalse(PostBadges.footerFromEcency("peakd/2"))
        val post = com.ecency.core.api.model.Post(author = "alice", permlink = "p", created = "2026-10-05T12:00:00", metadata = meta("""{"app":"peakd/2026"}"""))
        assertEquals("Posted by alice via Peakd a day ago", PostBadges.postedBy(post, 1_791_288_000_000L))
    }
}

class LinkSheetTest {
    @Test
    fun exploreIsOfferedOnlyWhenTheDappBrowserCanOpenAUrl() {
        val url = "https://example.com/page"
        // the "coming soon" screen and the bare directory hide the entry
        assertEquals(null, LinkSheet.exploreScreen(url) { AppScreen.Pending(RouteName.DAPP_BROWSER, it) })
        assertEquals(null, LinkSheet.exploreScreen(url) { AppScreen.Explore })
        // a built browser screen for the URL shows it and is where the entry goes
        val browser = AppScreen.Post("stand-in", "dapp-browser")
        assertEquals(browser, LinkSheet.exploreScreen(url) { browser })
        // only web pages, whatever the browser can do
        assertEquals(null, LinkSheet.exploreScreen("mailto:a@b.co") { browser })
        assertEquals(null, LinkSheet.exploreScreen("javascript:alert(1)") { browser })
        assertTrue(LinkSheet.exploreWarning("https://x.example").endsWith("\n\nhttps://x.example"))
    }
}

class CommentThreadsTest {
    private fun item(permlink: String, level: Int, children: Int = 0, local: Boolean = false): CommentItem {
        val post = com.ecency.core.api.model.Post(author = "a", permlink = permlink, children = children)
        val card = PostCard(post.key, "a", "", 50, "", "", null, "", 0, "", null, null, 0.0, "", false, 0, 0, false, false, false, false, emptyList(), null)
        return CommentItem(post, level, false, local, card, emptyList())
    }

    private val list = listOf(item("c1", 1, children = 2), item("r1", 2, children = 1), item("r2", 3), item("c2", 1), item("c3", 1, children = 1), item("r3", 2, local = true))

    @Test
    fun repliesAreCollapsedUntilTheirThreadIsOpened() {
        assertEquals(listOf("c1", "c2", "c3", "r3"), CommentThreads.visible(list, emptySet()).map { it.key.permlink }, "a local reply opens its thread")
        val open = CommentThreads.toggle(emptySet(), com.ecency.core.api.model.PostKey("a", "c1"))
        assertEquals(listOf("c1", "r1", "r2", "c2", "c3", "r3"), CommentThreads.visible(list, open).map { it.key.permlink })
        assertEquals(emptySet(), CommentThreads.toggle(open, com.ecency.core.api.model.PostKey("a", "c1")))
        assertTrue(CommentThreads.hasToggle(list[0]))
        assertFalse(CommentThreads.hasToggle(list[3]), "no replies, no pill")
        assertFalse(CommentThreads.hasToggle(list[1]), "only top-level comments")
        assertEquals(2, CommentThreads.loadedReplies(list, com.ecency.core.api.model.PostKey("a", "c1")))
        assertEquals(0, CommentThreads.loadedReplies(list, com.ecency.core.api.model.PostKey("a", "c2")))
    }
}

class ProfileWalletTest {
    private val account = com.ecency.core.api.wallet.WalletAccount(
        name = "bob", balance = "12.3456 HIVE", hbdBalance = "5.000 HBD", savingsBalance = "1.000 HIVE", savingsHbdBalance = "2.500 HBD",
        rewardHive = "1.234 HIVE", rewardHbd = "0.500 HBD", vestingShares = "2000000.000000 VESTS",
        delegatedVestingShares = "1000000.000000 VESTS", receivedVestingShares = "0.000000 VESTS",
        nextVestingWithdrawal = "2026-10-06T15:00:00", rewardVestingHive = "3.200 HIVE",
    )

    @Test
    fun anotherAccountsWalletReadsLikeRns() {
        // 500 HIVE per million VESTS
        val lines = ProfileWalletModel.lines(account, 500.0)
        assertEquals(
            listOf("Hive" to "12.346 HIVE", "Staked HIVE" to "1000 HP", "" to "- 500 HP", "" to "= 500 HP", "Hive Dollars" to "$5", "Savings" to "1 HIVE", "" to "$2.5"),
            lines.map { it.label to it.value },
        )
        assertTrue(lines.single { it.net }.value.startsWith("="))
        assertEquals("1.234 HIVE 0.5 HBD 3.2 HP", ProfileWalletModel.unclaimed(account))
        assertEquals(null, ProfileWalletModel.unclaimed(account.copy(rewardHive = "0.000 HIVE", rewardHbd = "0.000 HBD", rewardVestingHive = "0.000 HIVE")))
        assertEquals("Next unstaking is in 3 hours", ProfileWalletModel.powerDown(account, 1_791_288_000_000L))
        assertEquals(null, ProfileWalletModel.powerDown(account.copy(nextVestingWithdrawal = com.ecency.core.api.wallet.WalletAccount.NO_WITHDRAWAL), 0))
    }
}
