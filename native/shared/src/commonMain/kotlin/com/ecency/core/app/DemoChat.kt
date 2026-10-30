package com.ecency.core.app

import com.ecency.core.api.chat.text
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.obj
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HasSocket
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An offline stand-in for Ecency's chat API (`/api/mattermost/...`) and its websocket: synthetic channels, people and
 * messages kept in memory for the run. The fake-data runs of the apps (screenshots, UI tests) and the shared tests
 * use it; nothing of it reaches a server. Everything it says is made up.
 *
 * The viewer is whoever bootstraps (`u-<name>`); the others are spike-alice, spike-bob, spike-carol and spike-dave.
 */
class DemoChat(private val nowMillis: () -> Long) {
    private class Room(
        val id: String,
        var name: String,
        var display: String,
        val type: String,
        var header: String = "",
        var favorite: Boolean = false,
        var muted: Boolean = false,
        var lastViewed: Long = 0,
        var groupName: String? = null,
        var groupOwner: Boolean = false,
        val members: MutableList<String> = mutableListOf(),
        var joined: Boolean = true,
    )

    private class Post(
        val id: String,
        val channel: String,
        val user: String,
        var message: String,
        val at: Long,
        var type: String = "",
        val rootId: String = "",
        var props: JsonObject = JsonObject(emptyMap()),
        var editAt: Long = 0,
        var pinned: Boolean = false,
        val reactions: MutableList<Pair<String, String>> = mutableListOf(),
        val pendingId: String = "",
    )

    private val people = listOf("spike-alice", "spike-bob", "spike-carol", "spike-dave")

    /** Ecency's own accounts: reachable by a direct message (Ask Esie), not found by the search. */
    private val official = listOf(OfficialAccounts.ESIE_USERNAME)
    private var viewer = "spike-viewer"
    private val channels = mutableListOf<Room>()
    private val posts = mutableListOf<Post>()
    private var seq = 0
    var privacy = "all"

    /** Posts per page (tests page with a small one). */
    var pageSize = PAGE

    /** Requests answered so far (tests). */
    val sent = mutableListOf<AuthHttpRequest>()

    /** The channel ids of the `user_typing` events sent over the websocket (tests). */
    val typingSent = mutableListOf<String>()

    /** Status codes to answer instead, by a path fragment (tests): "posts" to 403 refuses reading a channel. */
    val refuse = mutableMapOf<String, Pair<Int, String>>()

    private val outgoing = mutableListOf<kotlinx.coroutines.channels.Channel<String>>()

    private val me get() = "u-$viewer"

    init {
        seed()
    }

    private fun seed() {
        val t = nowMillis()
        val min = 60_000L
        channels += Room("ch-ecency", "hive-125125", "Ecency", "O", header = "hive-125125 community chat", lastViewed = t - 50 * min, members = (people + "VIEWER").toMutableList())
        channels += Room("ch-dm-alice", "u-VIEWER__u-spike-alice", "spike-alice", "D", lastViewed = t - 30 * min, members = mutableListOf("VIEWER", "spike-alice"))
        channels += Room("ch-group", "group-1", "spike-alice, spike-bob, spike-carol", "G", groupOwner = true, lastViewed = t - 10 * min, members = mutableListOf("VIEWER", "spike-alice", "spike-bob", "spike-carol"))
        channels += Room("ch-photo", "hive-139531", "Photography", "O", header = "hive-139531", favorite = true, lastViewed = t - 5 * min, members = (people + "VIEWER").toMutableList())
        channels += Room("ch-muted", "hive-174578", "Off topic", "O", header = "hive-174578", muted = true, lastViewed = t - 300 * min, members = (people + "VIEWER").toMutableList())
        channels += Room("ch-public", "hive-100001", "Hive Devs", "O", header = "hive-100001", joined = false, members = people.toMutableList())

        fun post(ch: String, user: String, msg: String, ago: Long, type: String = "", props: JsonObject = JsonObject(emptyMap()), root: String = "") =
            Post("p${++seq}", ch, user, msg, t - ago * min, type, root, props).also { posts += it }
        post("ch-ecency", "spike-dave", "", 120, "system_join_channel", buildJsonObject { put("username", "spike-dave") })
        post("ch-ecency", "spike-carol", "", 119, "system_join_channel", buildJsonObject { put("username", "spike-carol") })
        post("ch-ecency", "spike-alice", "Welcome to the **Ecency** chat! Say hi :wave:", 90)
        val q = post("ch-ecency", "spike-bob", "Has anyone tried the _new_ editor? It feels `fast`.", 60)
        post("ch-ecency", "spike-carol", "Yes, works well for me @spike-bob", 45, root = q.id, props = buildJsonObject {
            put("parent_id", q.id); put("parent_username", "spike-bob"); put("parent_message", q.message)
        }).also { it.reactions += "+1" to "spike-alice"; it.reactions += "+1" to "spike-bob"; it.reactions += "heart" to "spike-dave" }
        post("ch-ecency", "spike-alice", "Read this: https://ecency.com/@spike-alice/fake-post-1", 20, props = buildJsonObject {
            put("link_url", "https://ecency.com/@spike-alice/fake-post-1"); put("link_title", "Fixture post"); put("link_summary", "A synthetic post for the demo."); put("link_image", "")
        })
        post("ch-ecency", "spike-dave", "Another ~~old~~ new message after you looked.", 3)
        post("ch-dm-alice", "spike-alice", "Hi! This is a synthetic direct message.", 40)
        post("ch-dm-alice", "spike-alice", "Do you have a minute? :smile:", 2)
        post("ch-group", "spike-alice", "Group conversation, made up for the demo.", 15)
        post("ch-group", "spike-bob", "", 12, ChatPostTypes.HEADER_CHANGE, buildJsonObject { put("new_header", "Book club") })
        post("ch-group", "spike-carol", "Nice name :+1:", 8)
        channels.first { it.id == "ch-group" }.groupName = "Book club"
        post("ch-photo", "spike-bob", "Golden hour today", 30)
        post("ch-muted", "spike-dave", "Muted chatter", 200)
        post("ch-public", "spike-alice", "Public channel you have not joined.", 500)
    }

    /** A demo person who shares the viewer's name (the seeded spike-alice) still gets an id of their own. */
    private fun userId(name: String) = if (name == "VIEWER") me else "u-$name".let { if (it == me) "$it-other" else it }

    private fun userJson(name: String): JsonObject = buildJsonObject {
        val n = if (name == "VIEWER") viewer else name
        put("id", userId(name))
        put("username", n)
        put("nickname", "")
        putJsonObject("props") { put("hive_username", n) }
    }

    private fun lastPostAt(c: Room) = posts.filter { it.channel == c.id }.maxOfOrNull { it.at } ?: 0

    private fun unread(c: Room) = posts.count { it.channel == c.id && it.at > c.lastViewed && it.user != "VIEWER" && it.type.isEmpty() }

    private fun channelJson(c: Room): JsonObject = buildJsonObject {
        put("id", c.id)
        put("name", c.name.replace("VIEWER", viewer))
        put("display_name", c.display)
        put("type", c.type)
        put("header", if (c.type == "G") c.groupName.orEmpty() else c.header)
        put("last_post_at", lastPostAt(c))
        put("last_viewed_at", c.lastViewed)
        put("is_favorite", c.favorite)
        put("is_muted", c.muted)
        put("message_count", unread(c))
        if (c.type == "D") {
            val other = c.members.first { it != "VIEWER" }
            put("teammate_id", userId(other))
            put("directUser", userJson(other))
        }
        if (c.type == "G") {
            c.groupName?.let { put("group_name", it) }
            put("group_owner", c.groupOwner)
            putJsonArray("groupUsers") { c.members.filter { it != "VIEWER" }.forEach { add(userJson(it)) } }
        }
    }

    private fun postJson(p: Post): JsonObject = buildJsonObject {
        put("id", p.id)
        put("channel_id", p.channel)
        put("user_id", userId(p.user))
        put("message", p.message)
        put("type", p.type)
        put("create_at", p.at)
        put("update_at", p.at)
        put("edit_at", p.editAt)
        put("root_id", p.rootId)
        put("is_pinned", p.pinned)
        if (p.pendingId.isNotEmpty()) put("pending_post_id", p.pendingId)
        put("props", p.props)
        putJsonObject("metadata") {
            putJsonArray("reactions") {
                p.reactions.forEach { (e, u) -> addJsonObject(e, userId(u), p.id) }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(emoji: String, user: String, post: String) =
        add(buildJsonObject { put("emoji_name", emoji); put("user_id", user); put("post_id", post); put("create_at", nowMillis()) })

    private fun ok(e: JsonElement) = HttpResult(200, e.toString())

    private fun body(r: AuthHttpRequest): JsonObject = SafeJson.parseOrNull(r.body ?: "{}").obj() ?: JsonObject(emptyMap())

    /** The answer to a chat API request, or null when [request] is not one. */
    fun http(request: AuthHttpRequest): HttpResult? {
        val url = request.url
        val path = url.substringAfter("/api/mattermost/", "").takeIf { "/api/mattermost/" in url } ?: return null
        sent += request
        refuse.entries.firstOrNull { path.contains(it.key) }?.let { (_, v) -> return HttpResult(v.first, v.second) }
        val m = request.method
        val bare = path.substringBefore('?')
        val parts = bare.split('/')
        if (bare == "bootstrap") {
            viewer = body(request)["username"].text() ?: viewer
            return ok(buildJsonObject { put("token", "demo-chat-token"); put("user", userJson("VIEWER")) })
        }
        if (request.headers["X-MM-Token"].isNullOrEmpty()) return HttpResult(401, """{"error":"unauthorized"}""")
        return when {
            bare == "channels" -> ok(buildJsonObject { put("channels", JsonArray(channels.filter { it.joined }.map(::channelJson))) })
            bare == "channels/search" -> {
                val term = (if (m == "GET") url.substringAfter("term=").substringBefore('&') else body(request)["term"].text().orEmpty()).lowercase()
                ok(buildJsonObject { put("channels", JsonArray(channels.filter { it.type == "O" && (it.display.lowercase().contains(term) || it.name.contains(term)) }.map(::channelJson))) })
            }
            bare == "users/search" -> {
                val q = url.substringAfter("q=", "").substringBefore('&').lowercase()
                ok(buildJsonObject { put("users", JsonArray(people.filter { it.contains(q) }.map(::userJson))) })
            }
            bare == "users/ids" -> {
                val ids = (body(request)["ids"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
                ok(JsonArray((people + official + "VIEWER").filter { userId(it) in ids }.map(::userJson)))
            }
            bare == "direct" -> {
                val name = body(request)["username"].text().orEmpty()
                val who = (people + official).firstOrNull { it == name || "u-$it" == body(request)["userId"].text() } ?: return HttpResult(404, """{"error":"User has not joined chats"}""")
                val existing = channels.firstOrNull { it.type == "D" && who in it.members }
                    ?: Room("ch-dm-$who", "u-VIEWER__u-$who", who, "D", lastViewed = nowMillis(), members = mutableListOf("VIEWER", who)).also {
                        channels += it
                        if (who in official) posts += Post("p${++seq}", it.id, who, "Hi, I am Esie. Ask me anything about Ecency and Hive.", nowMillis() - 1_000)
                    }
                existing.joined = true
                ok(buildJsonObject { put("channel", channelJson(existing)) })
            }
            bare == "group" -> {
                val names = (body(request)["usernames"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
                if (names.any { it !in people }) return HttpResult(400, """{"error":"${names.first { it !in people }} is not on chat yet."}""")
                val c = Room("ch-g${++seq}", "group-$seq", names.joinToString(", "), "G", groupOwner = true, lastViewed = nowMillis(), members = (listOf("VIEWER") + names).toMutableList())
                channels += c
                ok(buildJsonObject { put("channelId", c.id) })
            }
            bare == "me/dm-privacy" -> {
                if (m == "PUT") privacy = body(request)["privacy"].text() ?: privacy
                ok(buildJsonObject { put("privacy", privacy) })
            }
            parts.size >= 3 && parts[0] == "channels" -> channelCall(request, parts[1], parts.drop(2), m)
            else -> HttpResult(404, """{"error":"not found"}""")
        }
    }

    private fun channelCall(request: AuthHttpRequest, id: String, rest: List<String>, m: String): HttpResult {
        val c = channels.firstOrNull { it.id == id } ?: return HttpResult(404, """{"error":"channel not found"}""")
        val what = rest[0]
        return when {
            what == "join" -> {
                c.joined = true
                if ("VIEWER" !in c.members) c.members += "VIEWER"
                ok(buildJsonObject { put("channel", channelJson(c)) })
            }
            what == "leave" || what == "members" && rest.getOrNull(1) == "me" -> {
                c.joined = false
                c.members.remove("VIEWER")
                ok(buildJsonObject { put("status", "OK") })
            }
            what == "favorite" -> { c.favorite = body(request)["favorite"].toString() == "true"; ok(channelJson(c)) }
            what == "mute" -> { c.muted = body(request)["muted"].toString() == "true"; ok(channelJson(c)) }
            what == "view" -> { c.lastViewed = nowMillis(); ok(buildJsonObject { put("status", "OK") }) }
            what == "name" -> {
                if (!c.groupOwner) return HttpResult(403, """{"error":"forbidden","code":"not_owner"}""")
                val name = body(request)["name"].text().orEmpty()
                c.groupName = name.ifEmpty { null }
                val notice = Post("p${++seq}", c.id, "VIEWER", "", nowMillis(), ChatPostTypes.HEADER_CHANGE, props = buildJsonObject { put("new_header", name) })
                posts += notice
                emit("posted", c.id, buildJsonObject { put("post", postJson(notice).toString()) })
                ok(buildJsonObject { put("name", name) })
            }
            what == "members" -> ok(buildJsonObject {
                putJsonArray("members") { c.members.forEach { add(buildJsonObject { put("user_id", userId(it)); put("last_viewed_at", if (it == "VIEWER") c.lastViewed else 0) }) } }
            })
            what == "pinned" -> {
                val list = posts.filter { it.channel == c.id && it.pinned }
                ok(buildJsonObject { putJsonArray("order") { list.forEach { add(it.id) } }; putJsonObject("posts") { list.forEach { put(it.id, postJson(it)) } } })
            }
            what == "posts" && rest.size == 1 && m == "GET" -> {
                val before = request.url.substringAfter("before=", "").substringBefore('&')
                val all = posts.filter { it.channel == c.id }.sortedByDescending { it.at }
                val from = if (before.isEmpty()) 0 else all.indexOfFirst { it.id == before } + 1
                val page = if (from <= 0 && before.isNotEmpty()) emptyList() else all.drop(from).take(pageSize)
                ok(buildJsonObject {
                    putJsonArray("order") { page.forEach { add(it.id) } }
                    putJsonObject("posts") { page.forEach { put(it.id, postJson(it)) } }
                    putJsonObject("users") { c.members.forEach { put(userId(it), userJson(it)) } }
                    put("memberCount", c.members.size)
                    putJsonArray("onlineUserIds") { add(userId("spike-alice")) }
                    putJsonObject("member") { put("user_id", me); put("last_viewed_at", c.lastViewed) }
                })
            }
            what == "posts" && rest.size == 1 && m == "POST" -> {
                val b = body(request)
                val pending = b["pendingPostId"].text().orEmpty()
                // Mattermost dedupes creates on the pending post id: a retry gets the post it already made
                posts.firstOrNull { pending.isNotEmpty() && it.channel == c.id && it.pendingId == pending }?.let { return ok(postJson(it)) }
                val p = Post("p${++seq}", c.id, "VIEWER", b["message"].text().orEmpty(), nowMillis(), rootId = b["rootId"].text().orEmpty(),
                    props = b["props"].obj() ?: JsonObject(emptyMap()), pendingId = pending)
                posts += p
                c.lastViewed = p.at
                emit("posted", c.id, buildJsonObject { put("post", postJson(p).toString()) })
                ok(postJson(p))
            }
            what == "posts" && rest.size >= 2 -> postCall(request, c, rest[1], rest.drop(2), m)
            else -> HttpResult(404, """{"error":"not found"}""")
        }
    }

    private fun postCall(request: AuthHttpRequest, c: Room, id: String, rest: List<String>, m: String): HttpResult {
        val p = posts.firstOrNull { it.id == id && it.channel == c.id } ?: return HttpResult(404, """{"error":"post not found"}""")
        return when {
            rest.isEmpty() && m == "GET" -> ok(postJson(p))
            rest.isEmpty() && m == "PATCH" -> {
                p.message = body(request)["message"].text().orEmpty()
                p.editAt = nowMillis()
                ok(postJson(p))
            }
            rest.isEmpty() && m == "DELETE" -> {
                posts.remove(p)
                ok(buildJsonObject { put("status", "OK") })
            }
            rest.firstOrNull() == "reactions" -> {
                val emoji = body(request)["emoji"].text().orEmpty()
                if (m == "POST") p.reactions += emoji to "VIEWER" else p.reactions.remove(emoji to "VIEWER")
                ok(postJson(p))
            }
            rest.firstOrNull() == "pin" -> {
                p.pinned = m == "POST"
                ok(buildJsonObject { put("status", "OK") })
            }
            else -> HttpResult(404, """{"error":"not found"}""")
        }
    }

    // ---- websocket ----

    /** A socket for the chat websocket: answers `hello` and pings, and carries the events of the run. */
    fun socket(): HasSocket {
        val incoming = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        outgoing += incoming
        return object : HasSocket {
            override suspend fun send(text: String) {
                val o = SafeJson.parseOrNull(text).obj() ?: return
                when (o["action"].text()) {
                    "authentication_challenge" -> incoming.trySend("""{"event":"hello","data":{},"broadcast":{},"seq":0}""")
                    "ping" -> incoming.trySend("""{"status":"OK","data":{"text":"pong"},"seq_reply":0}""")
                    "user_typing" -> typingSent += o["data"].obj()?.get("channel_id").text().orEmpty()
                }
            }
            override val incoming: ReceiveChannel<String> = incoming
            override suspend fun close() {
                incoming.close()
                outgoing.remove(incoming)
            }
        }
    }

    private fun emit(event: String, channelId: String, data: JsonObject) {
        val text = buildJsonObject {
            put("event", event)
            put("data", data)
            putJsonObject("broadcast") { put("channel_id", channelId) }
            put("seq", ++seq)
        }.toString()
        outgoing.toList().forEach { it.trySend(text) }
    }

    /** Someone else types in [channelId] (demo runs and tests). */
    fun typing(channelId: String, who: String = "spike-alice") = emit("typing", channelId, buildJsonObject { put("user_id", userId(who)) })

    /** Someone else posts [message] in [channelId] (demo runs and tests). */
    fun incoming(channelId: String, message: String, who: String = "spike-alice") {
        val p = Post("p${++seq}", channelId, who, message, nowMillis())
        posts += p
        emit("posted", channelId, buildJsonObject { put("post", postJson(p).toString()) })
    }

    /** The viewer's own post [pendingId] lands and its echo comes before the create's answer (tests). */
    fun echoOwn(channelId: String, message: String, pendingId: String) {
        val p = Post("p${++seq}", channelId, "VIEWER", message, nowMillis(), pendingId = pendingId)
        posts += p
        emit("posted", channelId, buildJsonObject { put("post", postJson(p).toString()) })
    }

    /** Someone else reacts to [postId] (demo runs and tests). */
    fun react(channelId: String, postId: String, emoji: String, who: String = "spike-bob") {
        posts.firstOrNull { it.id == postId }?.reactions?.add(emoji to who)
        emit("reaction_added", channelId, buildJsonObject {
            put("reaction", buildJsonObject { put("emoji_name", emoji); put("user_id", userId(who)); put("post_id", postId) }.toString())
        })
    }

    companion object {
        const val PAGE = 30
        const val WEBSOCKET_PATH = "/api/mattermost/websocket"
    }
}

/** Mattermost post types the chat code checks. */
internal object ChatPostTypes {
    const val HEADER_CHANGE = "system_header_change"
}
