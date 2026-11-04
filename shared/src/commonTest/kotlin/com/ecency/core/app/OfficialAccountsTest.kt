package com.ecency.core.app

import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatPost
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.json.SafeJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's official-accounts.spec.ts cases, plus lookalike usernames and where chat applies the rules. */
class OfficialAccountsTest {
    @Test
    fun theOfficialAccountsAreExactlyEsieAndEcency() {
        assertEquals(listOf("esie", "ecency"), OfficialAccounts.USERNAMES)
        assertTrue(OfficialAccounts.isOfficial("esie"))
        assertTrue(OfficialAccounts.isOfficial("@Ecency"))
        assertTrue(OfficialAccounts.isOfficial("ESIE"))
        val lookalikes = listOf(
            "esie1", "esie-help", "ecency.help", "ecency-official", "official-ecency", "e-sie", "esie.", " esie", "esie ",
            "\u0435sie", // Cyrillic ie
            "es\u0456e", // Cyrillic i
            "\u0435c\u0435ncy", // two Cyrillic ie
            "ecen\u0441y", // Cyrillic es
            "\uff45\uff53\uff49\uff45", // fullwidth
            "es\u200bie", // zero-width space
            "esi\u0307e", // combining dot
            "ESI\u0307E", "ecenc", "esi", "@@esie", "", null,
        )
        for (name in lookalikes) assertFalse(OfficialAccounts.isOfficial(name), "badge for ${name?.map { it.code.toString(16) }}")
        assertEquals("esie", OfficialAccounts.ESIE_USERNAME)
    }

    @Test
    fun displayNamesPosingAsAnOfficialAccountAreCaught() {
        val names = listOf(
            "Esie", "ESIE support", "E-s-i-e", "E.s.i.e", "3sie", "E\$ie", "\u0415sie", "Es\u0131e", "\u0190sie", "Ecency Team",
            "Ecen\u00a2y", "\u0395cency", "E\u03f2ency", "Ecen\u0441\u04af", "e c e n c y", "Support: Esie", "EsieBot",
            "Es\u200bie", "E\u2060s\u200di\u00ade", "Ec\u202eency", "\u200bE\u200bs\u200bi\u200be",
            // beyond the web's list: fullwidth, accents, mathematical letters, a letter we cannot read
            "\uff25\uff53\uff49\uff45", "\u00c9sie", "\u00e9cency", "\ud835\udc04\ud835\udc2c\ud835\udc22\ud835\udc1e", "Es\u4e00e",
        )
        for (name in names) assertTrue(OfficialAccounts.impersonates(name, "scammer"), name)
    }

    @Test
    fun ordinaryNamesAreLeftAlone() {
        val names = listOf(
            "Jessie", "Alice", "Desiree", "Ecosystem", "Agency", "Jane Siegel", "Jose Sierra", "Cassie Sievers",
            "Kate Sie", "Agnes Iem", "Mike 51e", "Decency", "Recency Bias", "\u0421\u0435\u0440\u0433\u0435\u0439",
            "\u738b\u5c0f\u660e", "\u738b\u5c0f\u660e\u83ef", "\u0394\u03b7\u03bc\u03ae\u03c4\u03c1\u03b7\u03c2", "2024", "",
        )
        for (name in names) assertFalse(OfficialAccounts.impersonates(name, "someone"), name)
        assertFalse(OfficialAccounts.impersonates(null, "someone"))
    }

    @Test
    fun theOfficialAccountsMayUseTheirOwnNames() {
        assertFalse(OfficialAccounts.impersonates("Esie", "esie"))
        assertFalse(OfficialAccounts.impersonates("Ecency", "ecency"))
        assertFalse(OfficialAccounts.impersonates("Ecency", "@Ecency"))
        assertEquals("Esie", OfficialAccounts.safeName("Esie", "esie"))
        assertNull(OfficialAccounts.safeName("Esie", "esie1"))
        assertNull(OfficialAccounts.safeName("Esie", null), "a group name poses as no one")
        assertEquals("Alice", OfficialAccounts.safeName("Alice", "alice"))
        assertNull(OfficialAccounts.safeName("", "alice"))
    }

    @Test
    fun lettersAreReadAsLatinWordsAsTheWebReadsThem() {
        assertEquals(listOf("esie", "support"), OfficialAccounts.nameWords("E s i e  Support"))
        assertEquals(listOf("ecency"), OfficialAccounts.nameWords("\u0415c\u0435ncy"))
        assertEquals(listOf("es*e"), OfficialAccounts.nameWords("Es\u4e00e"))
        assertEquals(listOf("aiice"), OfficialAccounts.nameWords("Alice"))
    }

    @Test
    fun chatShowsTheUsernameInsteadOfAPosingName() {
        // the Hive account is kept for the avatar, profile and mentions; the posing nickname is not shown
        val fan = ChatUser("u-1", username = "fan-chat", nickname = "Esie Support", name = "Ecency", hiveProp = "ecencyfan")
        assertEquals("ecencyfan", fan.hiveName)
        assertEquals("ecencyfan", fan.displayName)
        assertNull(fan.safeNickname)
        assertNull(fan.safeName)
        assertFalse(fan.isOfficial)
        assertEquals("ecencyfan", ChatGroups.userName(fan))
        assertEquals("ecencyfan", ChatText.mentionName(fan))
        assertEquals("ecencyfan", ChatAvatar.account(fan.hiveName, fan.displayName).let { (it as ChatAvatar.Account).name })
        // without a Hive account the chat username stands in for a posing nickname
        val scammer = ChatUser("u-5", username = "scammer", nickname = "Esie Support")
        assertEquals("scammer", scammer.hiveName)
        assertEquals("scammer", scammer.displayName)

        val esie = ChatUser("u-2", username = "esie", nickname = "Esie", hiveProp = "esie")
        assertEquals("esie", esie.hiveName)
        assertEquals("Esie", esie.displayName)
        assertTrue(esie.isOfficial)

        val alice = ChatUser("u-3", username = "alice", nickname = "Alice", hiveProp = "alice")
        assertEquals("Alice", alice.displayName)
        assertFalse(alice.isOfficial)

        // a name that is not the username never earns the badge
        assertFalse(ChatUser("u-4", username = "esie1", nickname = "esie", hiveProp = "esie").isOfficial)
    }

    @Test
    fun aGroupNamedLikeAnOfficialAccountShowsItsMembers() {
        val members = listOf(ChatUser("u-a", username = "spike-alice"), ChatUser("u-b", username = "spike-bob"))
        val posing = ChatChannel("g", type = "G", groupName = "Ecency Support", groupUsers = members)
        assertEquals("spike-alice, spike-bob", ChatGroups.title(posing))
        assertEquals("Book club", ChatGroups.title(posing.copy(groupName = " Book club ")))
    }

    @Test
    fun onlyTheOfficialChatUsersOwnPostsAreOfficial() {
        val users = mapOf("u-esie" to ChatUser("u-esie", username = "esie"), "u-x" to ChatUser("u-x", username = "x", nickname = "esie"))
        fun post(user: String, type: String = "", props: String = "{}") =
            ChatPost.parse(SafeJson.parseOrNull("""{"id":"p","user_id":"$user","type":"$type","props":$props}"""))!!
        assertTrue(OfficialAccounts.postedByOfficial(post("u-esie"), users))
        assertFalse(OfficialAccounts.postedByOfficial(post("u-esie", props = """{"override_username":"esie"}"""), users))
        assertFalse(OfficialAccounts.postedByOfficial(post("u-esie", props = """{"from_webhook":"true"}"""), users))
        assertFalse(OfficialAccounts.postedByOfficial(post("u-x", props = """{"username":"esie"}"""), users))
        assertFalse(OfficialAccounts.postedByOfficial(post("unknown"), users))
        assertFalse(OfficialAccounts.postedByOfficial(post("u-esie", type = "system_add_to_channel"), users))
        // as web, only the user list counts: a user the post embeds does not, even under the post's own id
        val embedded = ChatPost.parse(SafeJson.parseOrNull("""{"id":"p","user_id":"u-esie","user":{"id":"u-esie","username":"esie"}}"""))!!
        assertFalse(OfficialAccounts.postedByOfficial(embedded, emptyMap()))
        assertTrue(OfficialAccounts.postedByOfficial(embedded, users))
    }

    @Test
    fun aPostCarryingAnotherNameIsRelayed() {
        fun post(props: String) = ChatPost.parse(SafeJson.parseOrNull("""{"id":"p","user_id":"u","props":$props}"""))!!
        assertFalse(post("{}").relayed)
        assertTrue(post("""{"override_username":"esie"}""").relayed)
        assertTrue(post("""{"from_webhook":"true"}""").relayed)
        assertFalse(post("""{"override_username":""}""").relayed)
    }
}

/** Ask Esie end to end over the offline chat: the list row, the header and her messages carry the badge. */
class OfficialChatTest {
    @Test
    fun aDirectMessageWithEsieIsMarkedOfficialWhereChatShowsHer() = runTest {
        val f = ChatFixture(this)
        f.start()
        val action = SideMenu.action(SideMenuItem.ASK_ESIE, ChatFixture.USER, false) as MenuAction.DirectMessage
        val id = f.env.chat.openDirect(action.username)
        assertEquals("ch-dm-esie", id)
        val thread = f.env.chat.threadModel(id, "D").also { it.start(); f.idle() }
        assertEquals("esie", thread.current.title)
        assertTrue(thread.current.official)
        val greeting = thread.current.items.filterIsInstance<ChatMessageItem>().single()
        assertTrue(greeting.authorOfficial)
        assertEquals("esie", greeting.authorAccount)
        thread.close()

        val list = f.env.chat.chatsModel().also { it.show(); f.idle() }
        val rows = list.current.rows
        assertEquals(listOf("esie"), rows.filter { it.official }.map { it.title })
        // nobody else is official: the other direct message, groups and channels
        assertFalse(rows.single { it.id == "ch-dm-alice" }.official)

        val alice = f.env.chat.threadModel("ch-dm-alice", "D").also { it.start(); f.idle() }
        assertFalse(alice.current.official)
        assertTrue(alice.current.items.filterIsInstance<ChatMessageItem>().none { it.authorOfficial })
        alice.close()
    }
}
