package com.ecency.core.app

import com.ecency.core.api.PollChoice
import com.ecency.core.api.PollResults
import com.ecency.core.api.PollVoter
import com.ecency.core.api.PostPoll
import com.ecency.core.api.broadcast.PostActionOps
import com.ecency.core.api.model.PostKey
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round

/** One choice row as RN's PollChoices draws it. */
data class PollRow(
    val num: Int,
    val text: String,
    /** 0..1 bar fill (results only; 0 while selecting, as RN). */
    val progress: Double,
    /** "5 voted" style label. */
    val label: String,
    val votedByMe: Boolean,
    val selected: Boolean,
)

data class PollState(
    val poll: PostPoll? = null,
    val results: PollResults? = null,
    val isLoading: Boolean = false,
    /** RN mode RESULT (else SELECT). */
    val showResults: Boolean = false,
    val selection: List<Int> = emptyList(),
    /** "number_of_votes" or "tokens". */
    val interpretation: String = "number_of_votes",
    val isVoting: Boolean = false,
    val rows: List<PollRow> = emptyList(),
    val expired: Boolean = false,
    /** RN header chip: "Ended" or "Ends in 3 days". */
    val endText: String = "",
    /** RN header sub-lines, in RN's order. */
    val notes: List<String> = emptyList(),
    val canVote: Boolean = false,
    /** RN "View Stats"/"Hide Stats" toggle shown (author, or peeking allowed). */
    val canToggleStats: Boolean = false,
    /** Author only, results shown: "Interpret by Tokens"/"Interpret by Votes". */
    val canSwitchInterpretation: Boolean = false,
    /** RN hideVoters: the per-choice voters list is off. */
    val hideVoters: Boolean = false,
)

/**
 * RN postPoll: the poll in a post's json_metadata, its results from poll.ecency.com, and the `polls`
 * custom_json vote, shown optimistically until the API catches up.
 */
class PollModel internal constructor(private val env: AppEnvironment, val key: PostKey, private val poll: PostPoll) :
    StateModel<PollState>(PollState(poll = poll, interpretation = poll.preferredInterpretation), env.dispatchers) {
    private var job: Job? = null
    private var accountCreated: String? = null

    init {
        scope.launch { env.events.collect { if (it is AppEvent.SessionChanged) refresh() } }
        rebuild()
    }

    fun start() {
        if (current.results != null || job?.isActive == true) return
        refresh()
    }

    fun refresh() {
        job?.cancel()
        setState { it.copy(isLoading = true) }
        job = scope.launch {
            val results = try {
                env.rest.poll(key.author, key.permlink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val user = env.session.current.username
            if (user != null) {
                runCatching { env.api.accounts.getAccount(user) }.getOrNull()?.let { accountCreated = it.created }
            }
            val voted = results?.choicesOf(user).orEmpty()
            setState {
                it.copy(
                    isLoading = false, results = results ?: it.results,
                    interpretation = results?.preferredInterpretation ?: it.interpretation,
                    showResults = voted.isNotEmpty() || poll.isExpired(env.nowMillis()),
                )
            }
            rebuild()
        }
    }

    fun toggle(num: Int) {
        if (!current.canVote) return
        val max = current.results?.maxChoicesVoted ?: poll.maxChoicesVoted
        setState { s ->
            val sel = s.selection
            val next = if (max > 1) {
                if (num in sel) sel - num else if (sel.size < max) sel + num else sel
            } else listOf(num)
            s.copy(selection = next)
        }
        rebuild()
    }

    fun toggleStats() {
        setState { it.copy(showResults = !it.showResults) }
        rebuild()
    }

    fun switchInterpretation() {
        setState { it.copy(interpretation = if (it.interpretation == TOKENS) VOTES else TOKENS) }
        rebuild()
    }

    /** RN "Vote": the `polls` custom_json; the selection clears and the vote shows at once. */
    fun vote() {
        val user = env.session.current.username ?: return
        val choices = current.selection
        if (choices.isEmpty() || current.isVoting) return
        val results = current.results
        if (results == null || results.trxId.isEmpty()) {
            env.ui.toast(L.postPoll.pollNotReady)
            return
        }
        setState { it.copy(isVoting = true, selection = emptyList()) }
        scope.launch {
            val before = current.results
            setState { it.copy(results = withVote(results, user, choices), showResults = true) }
            rebuild()
            try {
                env.signing.broadcast(user, PostActionOps.pollVote(user, results.trxId, choices))
                setState { it.copy(isVoting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e)
                setState { it.copy(isVoting = false, results = before, showResults = before?.choicesOf(user)?.isNotEmpty() == true) }
                if (error.kind != ErrorKind.CANCELLED) env.ui.toast(L.native.poll.fail(message = error.message))
            }
            rebuild()
        }
    }

    /** RN injectPollVoteCache: the user's choices moved, counts adjusted. */
    private fun withVote(results: PollResults, user: String, choices: List<Int>): PollResults {
        val old = results.choicesOf(user).toSet()
        val next = choices.toSet()
        return results.copy(
            choices = results.choices.map { c ->
                when {
                    c.num in old && c.num !in next -> c.copy(totalVotes = (c.totalVotes - 1).coerceAtLeast(0))
                    c.num in next && c.num !in old -> c.copy(totalVotes = c.totalVotes + 1)
                    else -> c
                }
            },
            voters = results.voters.filter { it.name != user } + PollVoter(user, choices),
        )
    }

    private fun rebuild() {
        val s = current
        val user = env.session.current.username
        val now = env.nowMillis()
        val expired = poll.isExpired(now)
        val results = s.results
        val myVote = results?.choicesOf(user).orEmpty()
        val ageLimit = results?.accountAgeDays?.takeIf { it > 0 } ?: poll.accountAgeDays
        val tooYoung = accountCreated?.let { created ->
            val days = (now - PostCard.epochMillis(created)) / 86_400_000
            days < ageLimit
        } ?: false
        val canVote = !expired && user != null && !(poll.allowVoteChanges == false && myVote.isNotEmpty()) && !tooYoung
        val isAuthor = user == key.author
        val allowPeeking = myVote.isEmpty() && !poll.hideResultsUntilVoted
        val choices: List<PollChoice> = results?.choices?.takeIf { it.isNotEmpty() }
            ?: poll.choices.mapIndexed { i, t -> PollChoice(i + 1, t, 0, 0.0) }
        val tokens = s.interpretation == TOKENS && (poll.token ?: results?.token ?: "HIVE:HP").startsWith("HIVE")
        val values = choices.map { if (tokens) round(it.hiveHp * 1000) / 1000 else it.totalVotes.toDouble() }
        val total = values.sum()
        val symbol = if (tokens) "HIVE" else L.postPoll.voted
        val rows = choices.mapIndexed { i, c ->
            PollRow(
                num = c.num, text = c.text,
                progress = if (s.showResults && total > 0) values[i] / total else 0.0,
                label = (if (tokens) LocaleFormat.posix(Num.plain(values[i]), grouping = false) else LocaleFormat.integer(values[i].toLong())) + " $symbol",
                votedByMe = c.num in myVote, selected = c.num in s.selection,
            )
        }
        val notes = buildList {
            if (poll.accountAgeDays > 0) add(L.native.plurals.postPoll.ageLimit(days = poll.accountAgeDays))
            if (poll.preferredInterpretation == TOKENS) add(L.postPoll.interpretationToken(token = poll.token ?: "HIVE:HP"))
            val max = results?.maxChoicesVoted ?: poll.maxChoicesVoted
            if (max > 1) add(L.native.plurals.postPoll.maxChoices(choices = max))
            if (poll.communityMembership.isNotEmpty()) add(L.postPoll.communityRestricted(communities = poll.communityMembership.joinToString(", ")))
        }
        setState {
            it.copy(
                rows = rows, expired = expired, notes = notes, canVote = canVote,
                endText = if (expired) L.postPoll.ended else L.postPoll.ends(inTime = FromNow.format(poll.endTime * 1000, now)),
                canToggleStats = isAuthor || allowPeeking,
                canSwitchInterpretation = isAuthor && it.showResults,
                hideVoters = poll.hideVotes && !isAuthor,
            )
        }
    }

    /** Voters of one choice (RN opens them in an account list). */
    fun votersOf(num: Int): List<String> = current.results?.voters?.filter { num in it.choices }?.map { it.name }.orEmpty()

    companion object {
        const val TOKENS = "tokens"
        const val VOTES = "number_of_votes"
    }
}
