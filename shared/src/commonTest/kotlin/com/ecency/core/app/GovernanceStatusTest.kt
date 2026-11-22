package com.ecency.core.app

import com.ecency.core.api.model.Account
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #3724: an account's governance status from `get_accounts` and the clock. */
class GovernanceStatusTest {
    private val now = TestApp.NOW
    private val day = 86_400_000L

    /** The chain's spelling: seconds, no zone. */
    private fun chainTime(millis: Long) = kotlin.time.Instant.fromEpochMilliseconds(millis).toString().removeSuffix("Z")

    private fun account(expiration: String, proxy: String = "", votes: List<String> = emptyList()) =
        Account(name = "spike-alice", proxy = proxy, witnessVotes = votes, governanceVoteExpiration = expiration)

    @Test
    fun theAccountReadsItsProxyWitnessVotesAndExpiry() {
        val json = Json.parseToJsonElement(
            """{"name":"spike-alice","proxy":"ecency","witness_votes":["good-karma","ecency"],"governance_vote_expiration_ts":"2027-07-14T15:38:51"}""",
        )
        val a = Account.fromJson(json)!!
        assertEquals("ecency", a.proxy)
        assertEquals(listOf("good-karma", "ecency"), a.witnessVotes)
        assertEquals("2027-07-14T15:38:51", a.governanceVoteExpiration)
        val bare = Account.fromJson(Json.parseToJsonElement("""{"name":"spike-bob"}"""))!!
        assertEquals("", bare.proxy)
        assertEquals(emptyList(), bare.witnessVotes)
        assertEquals("", bare.governanceVoteExpiration)
    }

    @Test
    fun theExpiredValueWithNoProxyAndNoVotesIsInactive() {
        assertEquals(GovernanceStatus.INACTIVE, Governance.status(account(Governance.EXPIRED_TS), now))
        assertNull(Governance.expiresAt(account(Governance.EXPIRED_TS)))
        // a proxy or witness votes count, whatever the date says
        assertEquals(GovernanceStatus.ACTIVE, Governance.status(account(Governance.EXPIRED_TS, proxy = "ecency"), now))
        assertEquals(GovernanceStatus.ACTIVE, Governance.status(account(Governance.EXPIRED_TS, votes = listOf("good-karma")), now))
    }

    @Test
    fun thirtyDaysAheadTheVotesAreExpiring() {
        val in29 = account(chainTime(now + 29 * day))
        assertEquals(GovernanceStatus.EXPIRING, Governance.status(in29, now))
        assertEquals(now + 29 * day, Governance.expiresAt(in29))
        assertEquals(GovernanceStatus.EXPIRING, Governance.status(account(chainTime(now + 30 * day)), now))
        assertEquals(GovernanceStatus.ACTIVE, Governance.status(account(chainTime(now + 31 * day)), now))
        // past the date and not yet cleared by the chain: still expiring, not inactive
        assertEquals(GovernanceStatus.EXPIRING, Governance.status(account(chainTime(now - day)), now))
    }

    @Test
    fun aMissingOrUnreadableExpiryShowsNoCard() {
        assertEquals(GovernanceStatus.ACTIVE, Governance.status(account(""), now))
        assertEquals(GovernanceStatus.ACTIVE, Governance.status(account("not a date"), now))
    }
}
