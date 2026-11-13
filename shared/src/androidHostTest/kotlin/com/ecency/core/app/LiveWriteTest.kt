package com.ecency.core.app

import com.ecency.core.api.HiveApi
import com.ecency.core.api.model.PostKey
import com.ecency.core.secure.InMemorySecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.fail

/**
 * Writes to the real chain with two test accounts, through the app's own models: sign in, publish, reply, vote and a
 * small transfer each way. Skipped unless ECENCY_LIVE_WRITE=1 and the local credentials file exists. Every step is
 * checked against the chain afterwards; problems are collected and reported together.
 *
 *     ECENCY_LIVE_WRITE=1 ./gradlew :shared:testAndroidHostTest --tests '*LiveWriteTest*' -i
 *
 * The credentials file (outside the repo, never committed) holds ECENCY_TEST_USERNAME / ECENCY_TEST_PASSWORD and
 * ECENCY_TEST_PEER / ECENCY_TEST_PEER_PASSWORD. Each run spends resource credits: run it on purpose, not in CI.
 */
class LiveWriteTest {
    private val enabled = System.getenv("ECENCY_LIVE_WRITE") == "1"
    private val credentials = File(System.getProperty("user.home"), ".config/ecency-native-test/credentials")
    private val problems = mutableListOf<String>()

    private fun note(step: String, what: String) {
        problems += "$step: $what"
        println("LIVE PROBLEM $step: $what")
    }

    private fun creds(): Map<String, String> = credentials.readLines()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1) } }
        .toMap()

    private fun env(): AppEnvironment {
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "live-main").apply { isDaemon = true } }.asCoroutineDispatcher()
        val env = AppEnvironment.create(InMemorySecureStore(), false)
        // the platform dispatchers expect an Android main thread: one single thread stands in for it
        return AppEnvironment(
            api = env.api,
            vault = com.ecency.core.secure.KeyVaultAsync(com.ecency.core.secure.KeyVault(InMemorySecureStore()), Dispatchers.IO),
            renderer = RenderHelperPostBodyRenderer(com.ecency.core.render.ImageProxy.DEFAULT_BASE, com.ecency.core.render.RenderHelper.Platform.ANDROID),
            dispatchers = AppDispatchers(main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = com.ecency.core.render.ImageProxy.DEFAULT_BASE,
            debugKeyImport = false,
            nowMillis = { System.currentTimeMillis() },
            auth = AuthServices.platform(null),
        ).also { it.setWelcomeDone(true) }
    }

    private suspend fun signIn(env: AppEnvironment, user: String, secret: String) {
        env.session.start()
        env.appLock.start()
        env.session.state.first { !it.isLoading }
        env.session.loginWithKey(user, secret)
        env.session.state.first { it.username == user }
    }

    /** Whether [account]'s latest operations hold one of [op] whose JSON contains every one of [parts]. */
    private suspend fun onChain(env: AppEnvironment, account: String, op: String, vararg parts: String): Boolean {
        repeat(10) {
            val history = env.api.client.call(
                "condenser_api.get_account_history",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(account)); add(kotlinx.serialization.json.JsonPrimitive(-1)); add(kotlinx.serialization.json.JsonPrimitive(30))
                },
            ).toString()
            val ops = Regex("\\[\"$op\",\\{[^}]*\\}").findAll(history).map { it.value }
            if (ops.any { o -> parts.all { it in o } }) return true
            delay(3_000)
        }
        return false
    }

    /** Unfollows [who] from the profile screen's model and waits until the profile no longer follows. */
    private suspend fun unfollow(env: AppEnvironment, who: String) {
        val p = env.profileModel(who, "blog")
        p.start()
        p.state.first { it.loaded && it.isFollowing }
        p.toggleFollow()
        p.state.first { !it.isFollowing }
        delay(4_000)
    }

    private suspend fun <T> step(name: String, millis: Long = 90_000, block: suspend () -> T): T? = try {
        withTimeout(millis) { block() }.also { println("LIVE OK $name") }
    } catch (e: Throwable) {
        note(name, "${e::class.simpleName}: ${e.message}")
        null
    }

    @Test
    fun publishReplyVoteAndTransferOnChain() = runBlocking {
        if (!enabled || !credentials.exists()) {
            println("LiveWriteTest skipped (set ECENCY_LIVE_WRITE=1 and keep the credentials file)")
            return@runBlocking
        }
        val c = creds()
        val author = c.getValue("ECENCY_TEST_USERNAME")
        val peer = c.getValue("ECENCY_TEST_PEER")
        val a = env()
        val b = env()
        step("sign in $author") { signIn(a, author, c.getValue("ECENCY_TEST_PASSWORD")) }
        step("sign in $peer") { signIn(b, peer, c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        val hive = HiveApi(a.api.client)

        // 1. a post on the author's own blog, through the editor
        val stamp = java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')
        val title = "Native app live test $stamp"
        val editor = a.editorModel(EditorArgs(EditorKind.NEW_POST))
        val published: PostKey? = step("publish") {
            editor.start()
            // the screen shows the editor once it has loaded (a draft read can replace what was typed before)
            editor.state.first { !it.loading && it.owner == author }
            editor.setTitle(title)
            val body = "Automated check of the Ecency native app: publish, reply, vote and transfer. Safe to ignore."
            editor.setBody(body, com.ecency.core.editor.TextSelection(body.length))
            editor.setTagInput("test ")
            val ready = editor.state.first { "test" in it.tags }
            if (!ready.canSubmit) error("cannot submit: $ready")
            editor.submit()
            val s = editor.state.first { it.done != null || it.error != null || it.publishConflict || it.grantPrompt || it.rcWarning || it.authorityPrompt }
            s.error?.let { error("editor error: ${it.message}") }
            if (s.done == null) error("editor asked: conflict=${s.publishConflict} grant=${s.grantPrompt} rc=${s.rcWarning} authority=${s.authorityPrompt}")
            (s.done as? EditorDone.Published)?.let { PostKey(it.author, it.permlink) } ?: error("not published: ${s.done}")
        }
        val post: com.ecency.core.api.ChainContent? = published?.let { key ->
            step<com.ecency.core.api.ChainContent>("post on chain") {
                var found: com.ecency.core.api.ChainContent? = null
                repeat(10) { if (found == null) { found = hive.content(key.author, key.permlink); if (found == null) delay(3_000) } }
                found ?: error("${key.author}/${key.permlink} not on chain")
            }
        }

        // 2. the peer replies, through the reply composer
        var reply: PostKey? = null
        if (post != null) {
            step("reply") {
                val parent = b.api.posts.getPost(post.author, post.permlink, refresh = true) ?: error("post not readable")
                val model = b.replyModel(PostKey(post.author, post.permlink), parent)
                model.setText("Native app live test reply. Safe to ignore.")
                model.send()
                val s = model.state.first { it.sent != null || it.error != null }
                s.error?.let { error(it.message) }
                reply = s.sent
            }
        }

        // 3. votes: the peer votes the post at 1%, the author the reply at 1%
        if (post != null) {
            step("vote on post") {
                b.votes.vote(post.author, post.permlink, 100)
                val s = b.votes.state.first { it.status(post.author, post.permlink)?.inFlight != true }
                s.status(post.author, post.permlink)?.error?.let { error(it.message) }
                val read = b.api.posts.getPost(post.author, post.permlink, observer = peer, refresh = true)
                if (read?.voteOf(peer) == null) note("vote on post", "the post's read does not hold the vote")
            }
        }
        reply?.let { r ->
            step("vote on reply") {
                a.votes.vote(r.author, r.permlink, 100)
                val s = a.votes.state.first { it.status(r.author, r.permlink)?.inFlight != true }
                s.status(r.author, r.permlink)?.error?.let { error(it.message) }
                if (!onChain(a, author, "vote", "\"permlink\":\"${r.permlink}\"")) note("vote on reply", "the vote is not in $author's history")
            }
        }

        // 4. a 0.001 HBD transfer from the peer to the author, and back
        suspend fun transfer(from: AppEnvironment, to: String, label: String) = step(label) {
            val m = from.transferModel(AppScreen.Transfer("transfer", "HBD", "hive")) ?: error("no transfer model")
            m.start()
            m.state.first { it.balance != null || it.balanceFailed }
            m.setRecipient(to)
            m.state.first { it.recipientValid }
            m.setAmount("0.001")
            m.setMemo("Native app live test")
            m.next()
            val prompt = m.state.first { it.prompt != null }.prompt
            if (prompt != TransferPrompt.Confirm) error("before confirm: $prompt")
            m.confirm()
            val s = m.state.first { it.closed || it.phase is SendPhase.Failed || it.prompt is TransferPrompt.Alert }
            (s.prompt as? TransferPrompt.Alert)?.let { error("alert: ${it.message}") }
            if (s.phase is SendPhase.Failed) error("send failed: ${s.phase}")
            val sender = from.session.current.username ?: error("no sender")
            if (!onChain(from, sender, "transfer", "\"to\":\"$to\"", "0.001 HBD")) note(label, "the transfer is not in $sender's history")
        }
        transfer(b, author, "transfer $peer to $author")
        transfer(a, peer, "transfer $author to $peer")

        // 5. points: the Ecency activity reports of what was just done
        step("points recorded") {
            delay(10_000)
            val ledger = java.net.URI("https://ecency.com/private-api/point-list").toURL().openConnection() as java.net.HttpURLConnection
            ledger.connectTimeout = 15_000
            ledger.readTimeout = 30_000
            ledger.requestMethod = "POST"
            ledger.doOutput = true
            ledger.setRequestProperty("Content-Type", "application/json")
            ledger.outputStream.use { it.write("""{"username":"$author"}""".toByteArray()) }
            val text = ledger.inputStream.bufferedReader().readText()
            if (post != null && "\"type\":100" !in text.take(4_000)) note("points recorded", "no post activity in the author's latest points")
        }

        println("LIVE post=${post?.let { "${it.author}/${it.permlink}" }} reply=$reply")
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /**
     * Powers up [ECENCY_LIVE_POWERUP] HIVE (e.g. "8.000") from the peer to itself and to the author, through the
     * power up screen's model, when ECENCY_LIVE_POWERUP is set: the accounts' resource credits for the other runs.
     */
    @Test
    fun powerUpThroughTheTransferScreen() = runBlocking {
        val amount = System.getenv("ECENCY_LIVE_POWERUP")
        if (amount.isNullOrBlank() || !credentials.exists()) return@runBlocking
        val c = creds()
        val peer = c.getValue("ECENCY_TEST_PEER")
        val b = env()
        step("sign in $peer") { signIn(b, peer, c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        for (to in listOf(peer, c.getValue("ECENCY_TEST_USERNAME"))) {
            step("power up $amount to $to") {
                val m = b.transferModel(AppScreen.Transfer("transfer_to_vesting", "HIVE", "hive")) ?: error("no power up model")
                m.start()
                m.state.first { it.balance != null || it.balanceFailed }
                m.setRecipient(to)
                m.state.first { it.recipientValid }
                m.setAmount(amount)
                m.next()
                val prompt = m.state.first { it.prompt != null }.prompt
                if (prompt != TransferPrompt.Confirm) error("before confirm: $prompt")
                m.confirm()
                val s = m.state.first { it.closed || it.phase is SendPhase.Failed || it.prompt is TransferPrompt.Alert }
                (s.prompt as? TransferPrompt.Alert)?.let { error("alert: ${it.message}") }
                if (s.phase is SendPhase.Failed) error("send failed: ${s.phase}")
                if (!onChain(b, peer, "transfer_to_vesting", "\"to\":\"$to\"", amount)) note("power up $amount to $to", "not in $peer's history")
            }
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /**
     * After a publish: the author edits the post, the peer reblogs it and undoes that, bookmarks it, edits its reply
     * and deletes it. Needs ECENCY_LIVE_LIFECYCLE="<post author>/<post permlink> <reply author>/<reply permlink>".
     */
    @Test
    fun editReblogBookmarkAndDelete() = runBlocking {
        val target = System.getenv("ECENCY_LIVE_LIFECYCLE")
        if (target.isNullOrBlank() || !credentials.exists()) return@runBlocking
        val (postRef, replyRef) = target.trim().split(" ")
        val postKey = postRef.split("/").let { PostKey(it[0], it[1]) }
        val replyKey = replyRef.split("/").let { PostKey(it[0], it[1]) }
        val c = creds()
        val a = env()
        val b = env()
        step("sign in author") { signIn(a, c.getValue("ECENCY_TEST_USERNAME"), c.getValue("ECENCY_TEST_PASSWORD")) }
        step("sign in peer") { signIn(b, c.getValue("ECENCY_TEST_PEER"), c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        val hive = HiveApi(a.api.client)

        suspend fun edit(env: AppEnvironment, key: PostKey, label: String, addition: String) = step(label) {
            val before = hive.content(key.author, key.permlink) ?: error("not on chain")
            val m = env.editorModel(EditorArgs(EditorKind.EDIT_POST, key.author, key.permlink))
            m.start()
            val loaded = m.state.first { !it.loading && it.owner != null }
            if (loaded.body.trim() != before.body.trim()) note(label, "the editor opened another body than the chain's")
            if (!loaded.isReply && loaded.title != before.title) note(label, "the editor opened another title than the chain's")
            val body = loaded.body + addition
            m.setBody(body, com.ecency.core.editor.TextSelection(body.length))
            m.state.first { it.body == body && it.canSubmit }
            m.submit()
            val s = m.state.first { it.done != null || it.error != null || it.publishConflict || it.authorityPrompt }
            s.error?.let { error(it.message) }
            if (s.done !is EditorDone.Edited) error("not edited: ${s.done} conflict=${s.publishConflict}")
            var after: com.ecency.core.api.ChainContent? = null
            repeat(10) { if (after?.body?.endsWith(addition.trim()) != true) { delay(3_000); after = hive.content(key.author, key.permlink) } }
            if (after?.body?.trim()?.endsWith(addition.trim()) != true) note(label, "the edit is not on chain")
            if (after?.title != before.title) note(label, "the edit changed the title: '${before.title}' -> '${after?.title}'")
            if (after?.tags != before.tags) note(label, "the edit changed the tags: ${before.tags} -> ${after?.tags}")
        }

        suspend fun menu(env: AppEnvironment, item: PostMenuItem, key: PostKey, label: String) = step(label) {
            val post = env.api.posts.getPost(key.author, key.permlink, env.session.current.username, true) ?: error("post not readable")
            when (val effect = env.postActions.select(item, post)) {
                is PostMenuEffect.Confirm -> env.postActions.confirm(effect.item, effect.post, effect.user)
                PostMenuEffect.None, PostMenuEffect.Started -> Unit
                else -> error("unexpected effect $effect")
            }
            delay(300)
            val s = env.postActions.state.first { key !in it.running }
            s.alert?.let { error("alert: ${it.title}: ${it.message}") }
        }

        val only = System.getenv("ECENCY_LIVE_STEPS")?.split(",")?.toSet()
        fun on(name: String) = only == null || name in only
        if (on("edit")) edit(a, postKey, "edit post", "\n\nEdited by the live test.")
        if (on("reblog")) {
            menu(b, PostMenuItem.REBLOG, postKey, "reblog")
            step("reblog on chain") {
                delay(4_000)
                val h = hive.client.call("condenser_api.get_account_history", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(c.getValue("ECENCY_TEST_PEER"))); add(kotlinx.serialization.json.JsonPrimitive(-1)); add(kotlinx.serialization.json.JsonPrimitive(10))
                }).toString()
                if ("reblog" !in h) note("reblog on chain", "no reblog custom_json in the peer's history")
            }
            menu(b, PostMenuItem.UNDO_REBLOG, postKey, "undo reblog")
        }
        if (on("bookmark")) menu(b, PostMenuItem.BOOKMARKS, postKey, "bookmark")
        if (on("bookmark")) step("bookmark listed") {
            val user = c.getValue("ECENCY_TEST_PEER")
            val marks = b.accountApi.bookmarks(user, 0).items
            val mine = marks.filter { it.author == postKey.author && it.permlink == postKey.permlink }
            if (mine.isEmpty()) note("bookmark listed", "the bookmark is not in the list")
            if (mine.size > 1) note("bookmark listed", "the post is bookmarked ${mine.size} times")
            mine.forEach { b.accountApi.deleteBookmark(user, it.id) }
        }
        if (on("reply")) edit(b, replyKey, "edit reply", " (edited)")
        if (on("reply")) menu(b, PostMenuItem.DELETE, replyKey, "delete reply")
        if (on("reply")) step("reply gone") {
            var gone = false
            repeat(10) { if (!gone) { gone = hive.content(replyKey.author, replyKey.permlink) == null; if (!gone) delay(3_000) } }
            if (!gone) note("reply gone", "the deleted reply is still on chain")
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /** Drafts, follow and favourite, notifications: ECENCY_LIVE_MISC=1. Follow and unfollow are the only chain writes. */
    @Test
    fun draftsFollowAndNotifications() = runBlocking {
        if (System.getenv("ECENCY_LIVE_MISC") != "1" || !credentials.exists()) return@runBlocking
        val c = creds()
        val author = c.getValue("ECENCY_TEST_USERNAME")
        val peer = c.getValue("ECENCY_TEST_PEER")
        val a = env()
        step("sign in author") { signIn(a, author, c.getValue("ECENCY_TEST_PASSWORD")) }

        // a draft saved from the editor is in the drafts list with its text and tags, and goes when deleted
        val title = "Live test draft ${System.currentTimeMillis() % 100000}"
        val draftId = step("save draft") {
            val m = a.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            m.state.first { !it.loading && it.owner == author }
            m.setTitle(title)
            val body = "Draft body of the live test."
            m.setBody(body, com.ecency.core.editor.TextSelection(body.length))
            m.setTagInput("test draft ")
            m.state.first { it.tags.size == 2 }
            m.saveDraft()
            val s = m.state.first { it.isDraftSaved || it.error != null }
            s.error?.let { error(it.message) }
            s.draftId ?: error("saved without an id")
        }
        step("draft listed") {
            val d = a.draftsModel(0)
            d.start()
            val s = d.state.first { (it.loaded && !it.loadingDrafts) || it.error != null }
            s.error?.let { error(it.message) }
            val mine = s.drafts.filter { it.title == title }
            if (mine.size != 1) note("draft listed", "${mine.size} drafts with the title")
            mine.firstOrNull()?.let { if (it.tags != listOf("test", "draft")) note("draft listed", "tags ${it.tags}") ; if (it.id != draftId) note("draft listed", "another id than the editor's") }
            d.deleteDraft(draftId ?: error("no id"))
            delay(3_000)
            d.refresh()
            val after = d.state.first { !it.loadingDrafts && !it.refreshing && draftId !in it.busy }
            if (after.drafts.any { it.id == draftId }) note("draft listed", "the deleted draft is still listed")
        }

        // follow and unfollow the peer from the quick profile; favourite and back
        step("follow") {
            val q = a.quickProfileModel(peer)
            q.start()
            val s = q.state.first { !it.isLoading && it.showActions }
            // a follow left by an earlier run is undone first, so every run checks a follow from scratch
            if (s.isFollowing) unfollow(a, peer)
            val q2 = a.quickProfileModel(peer).also { it.start() }
            q2.state.first { !it.isLoading && it.showActions && !it.isFollowing }
            q2.follow()
            val f = q2.state.first { !it.actionRunning && it.isFollowing }
            delay(4_000)
            val again = a.quickProfileModel(peer).also { it.start() }.state.first { !it.isLoading && it.showActions }
            if (!again.isFollowing) note("follow", "a fresh profile sheet does not show the follow")
            if (f.followers == null) note("follow", "no follower count")
            if (!onChain(a, author, "custom_json", "follow", peer)) note("follow", "not in $author's history")
            // and back: the relationship is left as it was found
            unfollow(a, peer)
        }
        step("favourite") {
            val q = a.quickProfileModel(peer)
            q.start()
            val s = q.state.first { !it.isLoading && it.showActions }
            q.toggleFavorite()
            val t = q.state.first { !it.actionRunning && it.isFavorite != s.isFavorite }
            q.toggleFavorite()
            q.state.first { !it.actionRunning && it.isFavorite == s.isFavorite }
            println("LIVE favourite toggled from ${s.isFavorite} to ${t.isFavorite} and back")
        }

        // notifications of the peer (the reply, votes and transfers of the other scenario): they load and mark read
        step("notifications") {
            val b = env()
            signIn(b, peer, c.getValue("ECENCY_TEST_PEER_PASSWORD"))
            val n = b.notifications
            n.start()
            n.show()
            val s = n.state.first { it.loaded || it.error != null }
            s.error?.let { error(it.message) }
            println("LIVE notifications: ${s.items.size} rows")
            if (s.items.isEmpty()) note("notifications", "no notifications although the account was voted and replied to")
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /** ECENCY_LIVE_SESSIONS=1: does a second sign-in of the same account end the first one's Ecency session? */
    @Test
    fun twoSignInsOfOneAccount() = runBlocking {
        if (System.getenv("ECENCY_LIVE_SESSIONS") != "1" || !credentials.exists()) return@runBlocking
        val c = creds()
        val user = c.getValue("ECENCY_TEST_PEER")
        val first = env()
        val second = env()
        step("first sign in") { signIn(first, user, c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        step("first private call") { first.accountApi.bookmarks(user, 0); println("LIVE first device call ok") }
        step("second sign in") { signIn(second, user, c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        step("first device after the second sign in") {
            val before = first.accounts.code(user)
            first.accountApi.bookmarks(user, 0)
            val after = first.accounts.code(user)
            println("LIVE first device call after second sign in ok; token renewed: ${before != after}")
            println("LIVE expired prompt: ${first.session.current.expiredSession}")
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /** ECENCY_LIVE_LOGOUT=1: two accounts on one device, the current one signs out: no "session expired" for the other. */
    @Test
    fun signOutOfOneOfTwoAccounts() = runBlocking {
        if (System.getenv("ECENCY_LIVE_LOGOUT") != "1" || !credentials.exists()) return@runBlocking
        val c = creds()
        val a = c.getValue("ECENCY_TEST_USERNAME")
        val b = c.getValue("ECENCY_TEST_PEER")
        val env = env()
        step("sign in both") {
            signIn(env, a, c.getValue("ECENCY_TEST_PASSWORD"))
            env.session.loginWithKey(b, c.getValue("ECENCY_TEST_PEER_PASSWORD"))
            env.session.state.first { it.username == b }
        }
        val n = env.notifications
        n.start()
        n.show()
        step("notifications of b") { n.state.first { it.loaded || it.error != null } }
        step("sign out b") {
            env.session.logout(b)
            env.session.state.first { it.username == a }
            delay(15_000)
            println("LIVE after sign out: current=${env.session.current.username} expired=${env.session.current.expiredSession} notifications error=${n.current.error?.message}")
            if (env.session.current.expiredSession != null) note("sign out b", "session expired prompt for ${env.session.current.expiredSession}")
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /** ECENCY_LIVE_VOTED="<author>/<permlink>": the peer's weightless vote on that post shows as voted (no writes). */
    @Test
    fun aWeightlessVoteShowsAsVoted() = runBlocking {
        val target = System.getenv("ECENCY_LIVE_VOTED")
        if (target.isNullOrBlank() || !credentials.exists()) return@runBlocking
        val c = creds()
        val b = env()
        step("sign in peer") { signIn(b, c.getValue("ECENCY_TEST_PEER"), c.getValue("ECENCY_TEST_PEER_PASSWORD")) }
        step("voted") {
            val (author, permlink) = target.split("/")
            val m = b.postModel(author, permlink)
            m.start()
            m.state.first { it.card != null }
            val s = m.state.first { it.card?.votedByMe == true }
            println("LIVE voted on $target: ${s.card?.votedByMe}")
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }

    /** ECENCY_LIVE_RENEW=1: a renewed Ecency session is taken by the private API (no chain writes). */
    @Test
    fun aRenewedSessionIsTakenByThePrivateApi() = runBlocking {
        if (System.getenv("ECENCY_LIVE_RENEW") != "1" || !credentials.exists()) return@runBlocking
        val c = creds()
        for ((user, pw) in listOf(c.getValue("ECENCY_TEST_PEER") to c.getValue("ECENCY_TEST_PEER_PASSWORD"), c.getValue("ECENCY_TEST_USERNAME") to c.getValue("ECENCY_TEST_PASSWORD"))) {
            val e = env()
            step("sign in $user") { signIn(e, user, pw) }
            step("renew $user") {
                val first = e.notificationsApi.unreadCount(user)
                val before = e.accounts.code(user) ?: error("no code")
                val renewed = e.accounts.renewAfterRejection(user, before)
                println("LIVE $user unread=$first renewed=${renewed != null && renewed != before}")
                repeat(3) { n ->
                    try {
                        println("LIVE $user after renewal call $n: unread=${e.notificationsApi.unreadCount(user)}")
                    } catch (x: Exception) {
                        println("LIVE $user after renewal call $n failed: ${x::class.simpleName} ${(x as? com.ecency.core.api.EcencyHttpException)?.status}")
                        note("renew $user", "call $n after renewal failed")
                    }
                    delay(2_000)
                }
            }
        }
        if (problems.isNotEmpty()) fail("Live problems:\n" + problems.joinToString("\n"))
    }
}
