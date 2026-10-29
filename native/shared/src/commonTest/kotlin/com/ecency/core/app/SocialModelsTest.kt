package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.ecency.EcencyAccountApi
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.PrivateKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Follows lists, profile edit, bookmarks and the profile navigation. */
class SocialModelsTest {
    // ---- follows ----

    private fun names(prefix: String, from: Int, count: Int) = (from until from + count).map { "$prefix${it.toString().padStart(3, '0')}" }

    @Test
    fun followersPageByNameAndDropTheInclusiveStartRow() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method != "condenser_api.get_followers") return@chain null
            val p = req.params.jsonArray
            val start = p[1].takeIf { it != JsonNull }?.jsonPrimitive?.content
            assertEquals("blog", p[2].jsonPrimitive.content)
            assertEquals(100, p[3].jsonPrimitive.content.toInt())
            val rows = when (start) {
                null -> names("u", 0, 100)
                "u099" -> names("u", 99, 51) // the node repeats the start row
                else -> error("unexpected start $start")
            }
            FakeTransport.result(req, rows.joinToString(",", "[", "]") { """{"follower":"$it","following":"alice","what":["blog"]}""" })
        }))
        val model = app.env.followsModel("alice", following = false, count = 150)
        assertEquals("Follower (150)", model.current.title)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(100, model.current.rows.size)
        assertFalse(model.current.endReached)
        assertEquals(50, model.current.rows.first().reputation)
        model.loadMore()
        testScheduler.advanceUntilIdle()
        val rows = model.current.rows.map { it.name }
        assertEquals(150, rows.size)
        assertEquals(rows.distinct(), rows)
        assertTrue(model.current.endReached)
        // search filters what is loaded, case-insensitively
        model.search("U14")
        assertEquals(listOf("u140", "u141", "u142", "u143", "u144", "u145", "u146", "u147", "u148", "u149"), model.current.visible.map { it.name })
        model.search("zzz")
        assertEquals("User is not found.", model.current.emptyText)
        model.close()
    }

    @Test
    fun followingUsesGetFollowingAndSkipsMissingProfiles() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method != "condenser_api.get_following") return@chain null
            FakeTransport.result(req, """[{"follower":"alice","following":"bob"},{"follower":"alice","following":"gone1"},{"follower":"alice","following":"carol"}]""")
        }))
        val model = app.env.followsModel("alice", following = true, count = -1)
        assertEquals("Following", model.current.title)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("bob", "carol"), model.current.rows.map { it.name })
        assertTrue(model.current.endReached)
        model.close()
    }

    @Test
    fun followsErrorShowsRnFailTextAndRetries() = runTest {
        var fail = true
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method == "condenser_api.get_followers") {
                if (fail) HttpResult(500, "down") else FakeTransport.result(req, """[{"follower":"bob","following":"alice"}]""")
            } else null
        }))
        val model = app.env.followsModel("alice", following = false, count = 1)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Fail!: ${AppError.NETWORK_MESSAGE}", model.current.emptyText)
        fail = false
        model.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("bob"), model.current.rows.map { it.name })
        assertNull(model.current.emptyText)
        model.close()
    }

    // ---- profile edit ----

    private val chainProfile =
        """{"profile":{"name":"Old","about":"A","pinned":"p1","version":2,"tokens":[{"symbol":"BTC","meta":{"address":"x","privateKey":"k"}}]},"beneficiary":{"keep":true}}"""

    @Test
    fun editLoadsTheOnChainProfileAndSavesTheSdkOperation() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile)),
        ))
        app.authBackend.privateApi = ProfileFixtures.ecency()
        with(ProfileFixtures) { signIn(app) }
        val profile = app.env.profileModel(TestApp.USER, "")
        profile.start()
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Old", edit.current.value(ProfileField.NAME))
        assertEquals("", edit.current.value(ProfileField.LOCATION))
        assertFalse(edit.current.saveEnabled)

        edit.setValue(ProfileField.NAME, "New 🐝")
        assertTrue(edit.current.saveEnabled)
        edit.setValue(ProfileField.NAME, "Old")
        assertFalse(edit.current.saveEnabled, "back to the loaded value")
        edit.setValue(ProfileField.NAME, "New 🐝")
        edit.setValue(ProfileField.LOCATION, "Berlin")
        assertFalse(edit.setImageUrl(ProfileField.COVER, "javascript:alert(1)"))
        assertNotNull(edit.current.alert)
        edit.dismissAlert()
        assertTrue(edit.setImageUrl(ProfileField.COVER, " https://img.example/c.png "))
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved)
        assertNull(edit.current.alert)

        val expected = com.ecency.core.api.model.ProfileMetadata.buildPostingJsonMetadata(
            chainProfile,
            JsValue.parse(
                """{"name":"New 🐝","about":"A","pinned":"p1","version":2,"tokens":[{"symbol":"BTC","meta":{"address":"x","privateKey":"k"}}],""" +
                    """"profile_image":null,"cover_image":"https://img.example/c.png","location":"Berlin"}""",
            ) as JsValue.Obj,
        )
        assertEquals(
            """{"profile":{"name":"New 🐝","about":"A","pinned":"p1","version":2,"tokens":[{"symbol":"BTC","meta":{"address":"x"}}],"profile_image":null,"cover_image":"https://img.example/c.png","location":"Berlin"},"beneficiary":{"keep":true}}""",
            expected,
        )
        val tx = app.broadcasts().single().params.jsonArray.single().jsonObject
        val op = tx["operations"]!!.jsonArray.single().jsonArray
        assertEquals("account_update2", op[0].jsonPrimitive.content)
        assertEquals(expected, op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content)
        assertEquals("", op[1].jsonObject["json_metadata"]!!.jsonPrimitive.content)
        // signed with the posting key the account holds
        val signed = Transaction(
            tx["ref_block_num"]!!.jsonPrimitive.content.toInt(), tx["ref_block_prefix"]!!.jsonPrimitive.content.toLong(),
            tx["expiration"]!!.jsonPrimitive.content, listOf(AccountUpdate2(TestApp.USER, "", expected)),
        )
        val sig = tx["signatures"]!!.jsonArray.single().jsonPrimitive.content
        assertEquals(PrivateKey.fromWif(GoldenVectors.WIF).publicKey().toString(),
            com.ecency.core.hive.Signature.fromHex(sig).recover(signed.digest()).toString())

        // the profile screen shows the new values at once, and the avatar URL is stamped
        testScheduler.advanceUntilIdle()
        assertEquals("New 🐝", profile.current.header!!.displayName)
        assertEquals("Berlin", profile.current.header!!.location)
        assertTrue(profile.current.header!!.avatarUrl.contains("?stamp="))
        // every avatar of the account carries the stamp (feed cards, comments, the drawer), other accounts do not
        assertTrue(PostCard.avatarUrl(TestApp.USER, TestApp.PROXY).contains("?stamp="))
        assertEquals("${TestApp.PROXY}/u/alice/avatar/128", PostCard.avatarUrl("alice", TestApp.PROXY))
        // the image service reads numeric sizes only (RN #3599: named sizes all came back 256 px)
        assertEquals("${TestApp.PROXY}/u/alice/avatar/256", PostCard.avatarUrl("alice", TestApp.PROXY, "medium"))
        assertEquals("${TestApp.PROXY}/u/alice/avatar/512", PostCard.avatarUrl("alice", TestApp.PROXY, "large"))
        assertEquals("${TestApp.PROXY}/u/alice/avatar/64", PostCard.avatarUrl("alice", TestApp.PROXY, "64"))
        assertEquals("${TestApp.PROXY}/u/alice/avatar/128", PostCard.avatarUrl("alice", TestApp.PROXY, "../x"))
        AvatarCacheStamps.clear()
        profile.close()
        edit.close()
    }

    @Test
    fun anUploadedAvatarIsSavedAtOnceWithoutClosingTheScreen() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile)),
        ))
        app.authBackend.privateApi = ProfileFixtures.ecency()
        val sent = mutableListOf<String>()
        // a fake image host: nothing is uploaded anywhere
        app.env.imageTransport = com.ecency.core.api.ecency.ImageUploadTransport { _, _, f, _ ->
            sent += f.name
            HttpResult(200, """{"url":"https://images.ecency.com/DQmAvatar/${f.name}"}""")
        }
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.uploadImage(ProfileField.AVATAR, null) // the picker was cancelled
        assertNull(edit.current.uploading)
        edit.uploadImage(ProfileField.AVATAR, PickedImage(ByteArray(10), "me.jpg", "image/jpeg", 512, 512))
        assertEquals(ProfileField.AVATAR, edit.current.uploading)
        edit.save() // nothing else saves while the upload runs
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("me.jpg"), sent)
        assertNull(edit.current.uploading)
        assertEquals("https://images.ecency.com/DQmAvatar/me.jpg", edit.current.value(ProfileField.AVATAR))
        // RN _handleOnSubmit({ goBack: false }): saved on chain, the screen stays
        assertFalse(edit.current.saved)
        assertFalse(edit.current.saveEnabled)
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertTrue(op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content.contains(""""profile_image":"https://images.ecency.com/DQmAvatar/me.jpg""""))
        AvatarCacheStamps.clear()
        edit.close()
    }

    @Test
    fun aFailedOrOversizedUploadSaysWhyAndSavesNothing() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile)),
        ))
        var calls = 0
        app.env.imageTransport = com.ecency.core.api.ecency.ImageUploadTransport { _, _, _, _ -> calls++; HttpResult(413, "") }
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        // RN _rejectTooLarge: refused before anything is sent
        edit.uploadImage(ProfileField.COVER, PickedImage(ByteArray(com.ecency.core.api.ecency.ImageUploader.MAX_BYTES + 1), "big.jpg", "image/jpeg"))
        assertEquals(UploadsModel.TOO_LARGE, edit.current.alert?.message)
        assertEquals(0, calls)
        edit.dismissAlert()
        edit.uploadImage(ProfileField.COVER, PickedImage(ByteArray(10), "c.jpg", "image/jpeg"))
        testScheduler.advanceUntilIdle()
        assertEquals(UploadsModel.TOO_LARGE, edit.current.alert?.message, "the host's 413")
        assertNull(edit.current.uploading)
        assertEquals("", edit.current.value(ProfileField.COVER))
        assertTrue(app.broadcasts().isEmpty())
        edit.close()
    }

    @Test
    fun theAvatarIsTheCentredSquareAtMost512() {
        assertEquals(Triple(0, 0, 100), AvatarCrop.square(100, 100))
        assertEquals(Triple(50, 0, 300), AvatarCrop.square(400, 300))
        assertEquals(Triple(0, 125, 750), AvatarCrop.square(750, 1000))
        assertEquals(512, AvatarCrop.target(1920))
        assertEquals(300, AvatarCrop.target(300), "never upscaled")
    }

    @Test
    fun editRefusesToOverwriteAStrippedAccountRow() = runTest {
        var stripped = false
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method == "condenser_api.get_accounts" && stripped) {
                FakeTransport.result(req, "[${ProfileFixtures.account(TestApp.USER, posting = "")}]")
            } else null
        }, accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile))))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.NAME, "X")
        stripped = true // a node now answers without metadata while hivemind still has the profile
        edit.save()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.saved)
        assertEquals(ErrorKind.NETWORK, edit.current.alert?.kind)
        assertTrue(app.broadcasts().isEmpty())
        edit.close()
    }

    @Test
    fun editParamsKeepRawValuesAndFollowRnOrder() {
        val base = JsValue.parse("""{"name":7,"website":"w","profile_image":0}""") as JsValue.Obj
        val original = mapOf(ProfileField.NAME to "", ProfileField.WEBSITE to "w", ProfileField.AVATAR to "")
        val p = ProfileEditModel.params(base, original + (ProfileField.ABOUT to "hi"), original)
        // name stays the number 7 (untouched), the falsy avatar becomes null (RN getAvatar), about is new
        assertEquals("""{"name":7,"website":"w","profile_image":null,"about":"hi","version":2}""", JsValue.stringify(p))
        val cleared = ProfileEditModel.params(base, original + (ProfileField.WEBSITE to ""), original)
        assertEquals("""{"name":7,"website":"","profile_image":null,"version":2}""", JsValue.stringify(cleared))
        // no avatar at all: RN writes null; a kept one stays; an entered one is the text
        val none = ProfileEditModel.params(JsValue.Obj(), mapOf(ProfileField.AVATAR to ""), mapOf(ProfileField.AVATAR to ""))
        assertEquals("""{"profile_image":null,"version":2}""", JsValue.stringify(none))
        val kept = JsValue.parse("""{"profile_image":"https://i/a.png"}""") as JsValue.Obj
        val keptValues = mapOf(ProfileField.AVATAR to "https://i/a.png")
        assertEquals("""{"profile_image":"https://i/a.png","version":2}""", JsValue.stringify(ProfileEditModel.params(kept, keptValues, keptValues)))
        assertEquals(
            """{"profile_image":"https://i/b.png","version":2}""",
            JsValue.stringify(ProfileEditModel.params(kept, mapOf(ProfileField.AVATAR to "https://i/b.png"), keptValues)),
        )
    }

    @Test
    fun rejectedSaveKeepsTheEditOpenWithTheError() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000 + 1000, "account_update2: invalid metadata") else null
        }, accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile))))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, "x")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.saved)
        assertFalse(edit.current.isSaving)
        assertEquals(ErrorKind.REJECTED, edit.current.alert?.kind)
        assertTrue(edit.current.saveEnabled, "the edit can be saved again")
        edit.close()
    }

    @Test
    fun cancelledAuthUpgradeLeavesTheEditOpenWithoutAnAlert() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000 + 1000, "missing required posting authority") else null
        }, accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = chainProfile))))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, "x")
        edit.save()
        testScheduler.advanceUntilIdle()
        // the key cannot sign: the auth upgrade asks, the user closes it
        assertNotNull(app.env.signing.current.prompt)
        assertTrue(edit.current.isSaving)
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.isSaving)
        assertFalse(edit.current.saved)
        assertNull(edit.current.alert)
        edit.close()
    }

    @Test
    fun legacyJsonMetadataProfileSeedsTheFormAndSurvivesTheSave() = runTest {
        // an old account: the profile lives in json_metadata only (the app shows it through the same fallback)
        val legacy = """{"profile":{"name":"Legacy","about":"Old about","website":"https://old.example","cover_image":"https://img.example/old.png","location":"Vilnius"}}"""
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = "", json = legacy)),
            // hivemind reads the legacy profile too while the posting one is missing
            rpc = { req -> if (req.method == "bridge.get_profile") FakeTransport.result(req, bridgeProfile(legacy.removePrefix("""{"profile":""").removeSuffix("}"))) else null },
        ))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Legacy", edit.current.value(ProfileField.NAME))
        assertEquals("Old about", edit.current.value(ProfileField.ABOUT))
        assertEquals("https://img.example/old.png", edit.current.value(ProfileField.COVER))
        edit.setValue(ProfileField.NAME, "Renamed")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(
            """{"profile":{"name":"Renamed","about":"Old about","website":"https://old.example","cover_image":"https://img.example/old.png","location":"Vilnius","profile_image":null,"version":2}}""",
            op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content,
        )
        AvatarCacheStamps.clear()
        edit.close()
    }

    @Test
    fun editRefusesWhenOnlyThePostingRowIsBlanked() = runTest {
        // the current profile is in posting_json_metadata (with tokens and unknown keys), an old one in json_metadata
        val oldLegacy = """{"profile":{"name":"Old Name","about":"From 2018","location":"Nowhere","website":"https://old.example","profile_image":"https://img.example/old-a.png","cover_image":"https://img.example/old-c.png"}}"""
        val current = """{"profile":{"name":"Current","about":"Now","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"version":2},"apps":["x"]}"""
        var blanked = false
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            when (req.method) {
                "bridge.get_profile" -> FakeTransport.result(req, bridgeProfile("""{"name":"Current","about":"Now","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","profile_image":""}"""))
                // the node blanks posting_json_metadata and answers everything else correctly
                "condenser_api.get_accounts" -> if (blanked) FakeTransport.result(req, "[${ProfileFixtures.account(TestApp.USER, posting = "", json = oldLegacy)}]") else null
                else -> null
            }
        }, accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = current, json = oldLegacy))))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Current", edit.current.value(ProfileField.NAME))
        edit.setValue(ProfileField.ABOUT, "Edited")
        blanked = true
        edit.save()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.saved)
        assertEquals(com.ecency.core.api.broadcast.ProfileMetadataGuard.REFUSED, edit.current.alert?.message)
        assertTrue(app.broadcasts().isEmpty(), "the 2018 profile must never replace the current one")

        blanked = false
        edit.dismissAlert()
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(
            """{"profile":{"name":"Current","about":"Edited","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"version":2,"profile_image":null},"apps":["x"]}""",
            op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content,
        )
        AvatarCacheStamps.clear()
        edit.close()
    }

    @Test
    fun editOfARowWithAnEmptyWebsiteIsNotLockedOutAndKeepsEverything() = runTest {
        // the shape Ecency web writes; hivemind shows the empty website as "http://"
        val row = """{"profile":{"name":"Web User","about":"Writes on the web","website":"","location":"Kaunas","profile_image":"https://img.example/a.png","pinned":"old-pin","tokens":[{"symbol":"LEO","type":"ENGINE"}],"custom":{"a":1},"version":2},"apps":["x"]}"""
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = row)),
            rpc = { req ->
                if (req.method == "bridge.get_profile") FakeTransport.result(req, bridgeProfile("""{"name":"Web User","about":"Writes on the web","website":"http://","location":"Kaunas","profile_image":"https://img.example/a.png","cover_image":"","blacklist_description":"","muted_list_description":""}"""))
                else null
            },
        ))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, "Edited")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(
            """{"profile":{"name":"Web User","about":"Edited","website":"","location":"Kaunas","profile_image":"https://img.example/a.png","pinned":"old-pin","tokens":[{"symbol":"LEO","type":"ENGINE"}],"custom":{"a":1},"version":2},"apps":["x"]}""",
            op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content,
        )
        AvatarCacheStamps.clear()
        edit.close()
    }

    @Test
    fun editOnAStaleSnapshotAccountShowsAndWritesTheCurrentProfile() = runTest {
        // hived's hardfork copy in posting (2018), the current profile in json_metadata, which hivemind shows
        val snapshot = """{"profile":{"name":"Old Name","about":"From 2018","location":"Nowhere","website":"https://old.example","tokens":[{"symbol":"LEO"}]},"apps":["x"]}"""
        val legacy = """{"profile":{"name":"Current","about":"Edited since","website":"https://now.example","cover_image":"https://img.example/now-c.png"}}"""
        var written: String? = null
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = snapshot, json = legacy)),
            rpc = { req ->
                when {
                    req.method == "bridge.get_profile" -> FakeTransport.result(req, bridgeProfile(legacy.removePrefix("""{"profile":""").removeSuffix("}")))
                    req.method == "condenser_api.get_accounts" && written != null -> FakeTransport.result(req, "[${ProfileFixtures.account(TestApp.USER, posting = written!!, json = legacy)}]")
                    else -> null
                }
            },
        ))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        // the form shows what every front end shows, not the 2018 snapshot
        assertEquals("Current", edit.current.value(ProfileField.NAME))
        assertEquals("Edited since", edit.current.value(ProfileField.ABOUT))
        assertEquals("", edit.current.value(ProfileField.LOCATION))
        edit.setValue(ProfileField.ABOUT, "Edited again")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(
            """{"profile":{"name":"Current","about":"Edited again","website":"https://now.example","cover_image":"https://img.example/now-c.png","tokens":[{"symbol":"LEO"}],"profile_image":null,"version":2},"apps":["x"]}""",
            op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content,
        )
        // the node shows the new row, hivemind still the legacy values (lag): a second edit builds on the app's
        // own write, it must not go back to the legacy profile and undo the first edit
        written = op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content
        edit.setValue(ProfileField.LOCATION, "Vilnius")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        val second = app.broadcasts().last().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(
            """{"profile":{"name":"Current","about":"Edited again","website":"https://now.example","cover_image":"https://img.example/now-c.png","tokens":[{"symbol":"LEO"}],"profile_image":null,"version":2,"location":"Vilnius"},"apps":["x"]}""",
            second[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content,
        )
        AvatarCacheStamps.clear()
        edit.close()
    }

    private fun bridgeProfile(profileJson: String) =
        """{"name":"${TestApp.USER}","reputation":25,"stats":{"followers":0,"following":0},"metadata":{"profile":$profileJson}}"""

    @Test
    fun anAccountWithNoProfileAnywhereCanSaveItsFirstOne() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = "", json = "")),
            rpc = { req ->
                // hivemind answers its usual object of empty strings for an account without a profile
                if (req.method == "bridge.get_profile") FakeTransport.result(req, bridgeProfile("""{"name":"","about":"","website":"","location":"","cover_image":"","profile_image":"","blacklist_description":"","muted_list_description":""}"""))
                else null
            },
        ))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, "First words")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        assertEquals(1, app.broadcasts().size)
        AvatarCacheStamps.clear()
        edit.close()
    }

    @Test
    fun anyHivemindProfileValueMarksAnEmptyRowAsStripped() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(TestApp.USER to ProfileFixtures.account(TestApp.USER, posting = "", json = "")),
            rpc = { req ->
                // none of the fields the app shows, but hivemind does hold metadata for the account
                if (req.method == "bridge.get_profile") FakeTransport.result(req, bridgeProfile("""{"name":"","about":"","blacklist_description":"my list"}"""))
                else null
            },
        ))
        with(ProfileFixtures) { signIn(app) }
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, "x")
        edit.save()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.saved)
        assertTrue(app.broadcasts().isEmpty())
        edit.close()
    }

    // ---- bookmarks ----

    @Test
    fun bookmarksWithoutAnIdAreKeptApartAndNotRemoved() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            when {
                r.url.contains("/private-api/bookmarks?") -> HttpResult(
                    200, """[{"author":"alice","permlink":"one","timestamp":3},{"author":"bob","permlink":"two","timestamp":2},{"_id":"b9","author":"carol","permlink":"three","timestamp":1}]""",
                )
                r.url.contains("?format=wrapped") -> HttpResult(200, "[]")
                else -> null
            }
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.bookmarksModel(0)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(3, model.current.bookmarks.items.size)
        // removing the one with an id leaves both id-less rows
        model.removeBookmark(model.current.bookmarks.items.last())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two"), model.current.bookmarks.items.map { it.permlink })
        // a row without an id cannot be deleted on the server: nothing is sent, nothing disappears
        model.removeBookmark(model.current.bookmarks.items.first())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two"), model.current.bookmarks.items.map { it.permlink })
        assertEquals(1, app.authBackend.sent.count { it.url.endsWith("bookmarks-delete") })
        model.close()
    }

    @Test
    fun bookmarksPageWrappedListsAndRemoveWithTheRightFields() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            when {
                r.url.contains("/private-api/bookmarks?format=wrapped&offset=0&limit=20") -> HttpResult(
                    200,
                    """{"data":[{"_id":"b1","author":"alice","permlink":"old","timestamp":1},{"_id":"b2","author":"bob","permlink":"new","timestamp":5}],""" +
                        """"pagination":{"total":3,"limit":20,"offset":0,"has_next":true}}""",
                )
                r.url.contains("/private-api/bookmarks?format=wrapped&offset=20&limit=20") -> HttpResult(
                    200, """{"data":[{"_id":"b3","author":"carol","permlink":"mid","timestamp":3}],"pagination":{"total":3,"limit":20,"offset":20,"has_next":false}}""",
                )
                // an old server answering a plain array: no further page
                r.url.contains("/private-api/favorites?format=wrapped") -> HttpResult(200, """[{"_id":"f1","account":"dave","timestamp":2}]""")
                r.url.contains("/private-api/favorite-tags?format=wrapped&offset=0&limit=100") ->
                    HttpResult(200, """{"data":[{"tag":"photography"},{"tag":"hive"}],"pagination":{"total":2,"limit":100,"offset":0,"has_next":false}}""")
                r.url.endsWith("/private-api/bookmarks-delete") -> HttpResult(500, "{}")
                else -> null
            }
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.bookmarksModel(2)
        assertEquals(BookmarksTab.TAGS, model.current.tab)
        model.start()
        testScheduler.advanceUntilIdle()
        val s = model.current
        assertEquals(listOf("b2", "b1"), s.bookmarks.items.map { it.id }, "newest first")
        assertTrue(s.bookmarks.hasMore)
        assertEquals(listOf("dave"), s.favorites.items.map { it.account })
        assertFalse(s.favorites.hasMore)
        assertEquals(listOf("photography", "hive"), s.tags.items.map { it.tag })
        model.loadMore(BookmarksTab.BOOKMARKS)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("b2", "b3", "b1"), model.current.bookmarks.items.map { it.id })
        assertFalse(model.current.bookmarks.hasMore)

        model.removeFavorite(model.current.favorites.items.single())
        testScheduler.advanceUntilIdle()
        val del = ProfileFixtures.body(app.authBackend.sent.single { it.url.endsWith("/private-api/favorites-delete") })
        assertEquals("dave", del["account"]!!.jsonPrimitive.content)
        assertTrue(model.current.favorites.items.isEmpty())
        assertTrue(model.current.isEmpty(BookmarksTab.FAVORITES))
        assertEquals("Success!", app.env.ui.current.toast?.text)
        app.env.ui.clear()

        model.removeTag(model.current.tags.items.first())
        testScheduler.advanceUntilIdle()
        assertEquals("photography", ProfileFixtures.body(app.authBackend.sent.single { it.url.endsWith("favorite-tags-delete") })["tag"]!!.jsonPrimitive.content)
        assertEquals("Unfollowed tag", app.env.ui.current.toast?.text)
        app.env.ui.clear()

        // a failed delete keeps the row and says "Fail!"
        model.removeBookmark(model.current.bookmarks.items.first())
        testScheduler.advanceUntilIdle()
        assertEquals("b2", ProfileFixtures.body(app.authBackend.sent.single { it.url.endsWith("bookmarks-delete") })["id"]!!.jsonPrimitive.content)
        assertEquals(3, model.current.bookmarks.items.size)
        assertEquals("Fail!", app.env.ui.current.toast?.text)
        model.close()
    }

    @Test
    fun bookmarksErrorIsPerTabAndRetries() = runTest {
        var fail = true
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.authBackend.privateApi = ProfileFixtures.ecency(extra = { r ->
            if (r.url.contains("/private-api/bookmarks?") && fail) HttpResult(502, "bad gateway") else if (r.url.contains("?format=wrapped")) HttpResult(200, "[]") else null
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.bookmarksModel(0)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, model.current.error(BookmarksTab.BOOKMARKS)?.kind)
        assertNull(model.current.error(BookmarksTab.FAVORITES))
        assertTrue(model.current.isEmpty(BookmarksTab.FAVORITES))
        fail = false
        model.retry(BookmarksTab.BOOKMARKS)
        testScheduler.advanceUntilIdle()
        assertNull(model.current.error(BookmarksTab.BOOKMARKS))
        assertTrue(model.current.isEmpty(BookmarksTab.BOOKMARKS))
        model.close()
    }

    @Test
    fun withoutAnEcencySessionTheListsFailAsSignIn() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.signIn() // a posting key, no Ecency token
        app.authBackend.down = true // and no token endpoint either
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val model = app.env.bookmarksModel(0)
        model.start()
        testScheduler.advanceUntilIdle()
        // nothing leaves without a code: the list says to sign in
        assertTrue(app.authBackend.sent.none { it.url.contains("/private-api/bookmarks") })
        assertEquals(ErrorKind.AUTH, model.current.error(BookmarksTab.BOOKMARKS)?.kind)
        model.close()
    }

    @Test
    fun normalizeTagMatchesTheSdk() {
        assertEquals("photography", EcencyAccountApi.normalizeTag("  #PhotoGraphy "))
        assertEquals("a-1", EcencyAccountApi.normalizeTag("a-1"))
        assertNull(EcencyAccountApi.normalizeTag("hive-123456"))
        assertNull(EcencyAccountApi.normalizeTag("##tag"))
        assertNull(EcencyAccountApi.normalizeTag("bad tag"))
        assertNull(EcencyAccountApi.normalizeTag("x".repeat(33)))
        assertNull(EcencyAccountApi.normalizeTag(""))
    }

    // ---- navigation ----

    @Test
    fun profileScreensSurviveEncodeAndDecode() {
        val screens = listOf(
            AppScreen.Profile("alice", "wallet"),
            AppScreen.Profile("bob"),
            AppScreen.Follows("alice", following = true, count = 12),
            AppScreen.Follows("alice", following = false),
            AppScreen.ProfileEdit,
            AppScreen.Bookmarks(2),
            AppScreen.AccountList("Voters", listOf(AccountListEntry("a", 5), AccountListEntry("b", 0))),
            AppScreen.AccountList("", emptyList()),
        )
        var state = NavState()
        screens.forEach { state = state.push(it) }
        assertEquals(screens, NavState.decode(state.encode()).screens)
        // an old line without a section still decodes
        assertEquals(AppScreen.Profile("carol"), NavState.decodeScreen("profile\u001Fcarol"))
    }

    @Test
    fun aLongAccountListIsSavedWithItsFirstRowsOnly() {
        val users = (0 until 1000).map { AccountListEntry("user$it", it.toLong()) }
        val line = NavState.encodeScreen(AppScreen.AccountList("Voters", users))
        val back = NavState.decodeScreen(line) as AppScreen.AccountList
        assertEquals(NavState.ACCOUNT_LIST_SAVED_ROWS, back.users.size)
        assertEquals(users.take(NavState.ACCOUNT_LIST_SAVED_ROWS), back.users)
        assertEquals("Voters", back.title)
    }

    @Test
    fun userinfoInALinkIsNeverATapTarget() {
        val safe = com.ecency.core.render.blocks.SafeTarget
        assertNull(safe.httpUrl("https://ecency.com@evil.example/path"))
        assertNull(safe.httpUrl("https://user:pass@evil.example"))
        assertNull(safe.httpUrl("http://ecency.com%40@evil.example"))
        assertEquals("https://ecency.com/@alice/post", safe.httpUrl("https://ecency.com/@alice/post"), "an @ in the path is fine")
        assertEquals("https://example.com/?to=a@b.c", safe.httpUrl("https://example.com/?to=a@b.c"))
        assertNull(ProfileFormat.websiteUrl("ecency.com@evil.example"))
        assertNull(ProfileFormat.websiteUrl("https://ecency.com@evil.example"))
        assertTrue(LinkRouter.route(com.ecency.core.render.blocks.LinkTarget.External("https://ecency.com@evil.example")) is Route.Unsupported)
    }

    @Test
    fun profileRoutesAreBuiltAndGatedAsRn() {
        listOf(RouteName.FOLLOWS, RouteName.PROFILE_EDIT, RouteName.BOOKMARKS, RouteName.ACCOUNT_LIST, RouteName.PROFILE).forEach {
            assertTrue(it.built, it.name)
        }
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.ProfileEdit, loggedIn = false).top)
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.Bookmarks(), loggedIn = false).top)
        assertEquals(AppScreen.Follows("a", false), NavState().pushGated(AppScreen.Follows("a", false), loggedIn = false).top)
        assertEquals(AppScreen.Bookmarks(0), AppScreen.of(RouteName.BOOKMARKS))
        assertEquals(AppScreen.ProfileEdit, AppScreen.of(RouteName.PROFILE_EDIT))
        // a sign-out drops the gated profile screens and keeps the public ones
        val s = NavState().push(AppScreen.Profile("a")).push(AppScreen.ProfileEdit).push(AppScreen.Follows("a", true))
        assertEquals(listOf(AppScreen.Profile("a"), AppScreen.Follows("a", true)), s.afterSignOut().screens)
    }

    @Test
    fun ecencyProfileLinksOpenTheirSection() {
        assertEquals(AppScreen.Profile("alice", "wallet"), ContentLinks.entry("https://ecency.com/@alice/wallet")?.screens?.single())
        assertEquals(AppScreen.Profile("alice", "wallet"), ContentLinks.entry("https://ecency.com/@alice/points")?.screens?.single())
        assertEquals(AppScreen.Profile("alice", "comments"), ContentLinks.entry("https://ecency.com/@alice/comments")?.screens?.single())
        assertEquals(AppScreen.Profile("alice", ""), ContentLinks.entry("https://ecency.com/@alice/blog")?.screens?.single())
        assertEquals(AppScreen.Follows("alice", true), ContentLinks.entry("https://ecency.com/@alice/following")?.screens?.single())
        assertEquals(AppScreen.Follows("alice", false), ContentLinks.entry("https://ecency.com/@alice/followers")?.screens?.single())
        assertNull(ContentLinks.entry("https://ecency.com/@Bad..Name/followers"))
    }

    @Test
    fun accountListFilterAndAvatarStamps() = runTest {
        val entries = listOf(AccountListEntry("Alice"), AccountListEntry("bob"), AccountListEntry("malice"))
        assertEquals(listOf("Alice", "malice"), AccountListFilter.filter(entries, "ali").map { it.account })
        assertEquals(entries, AccountListFilter.filter(entries, ""))
        val app = TestApp(this, handler = ProfileFixtures.chain())
        assertEquals("${TestApp.PROXY}/u/alice/avatar/128", app.env.avatars.url("alice", "small", TestApp.PROXY))
        app.env.avatars.bump("alice", 42)
        testScheduler.advanceUntilIdle()
        assertEquals("${TestApp.PROXY}/u/alice/avatar/128?stamp=42", app.env.avatars.url("alice", "small", TestApp.PROXY))
        assertEquals("${TestApp.PROXY}/u/bob/avatar/128", app.env.avatars.url("bob", "small", TestApp.PROXY))
        AvatarCacheStamps.clear()
    }

    @Test
    fun proMembersLoadOnceAndRetryAfterFailure() = runTest {
        var fail = true
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.authBackend.privateApi = { r -> if (fail) HttpResult(500, "") else ProfileFixtures.ecency()(r) }
        app.env.proMembers.ensureLoaded()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.proMembers.isPro("alice"))
        fail = false
        app.env.proMembers.ensureLoaded()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.proMembers.isPro("ALICE"))
        val calls = app.authBackend.sent.count { it.url.endsWith("pro-members") }
        app.env.proMembers.ensureLoaded()
        testScheduler.advanceUntilIdle()
        assertEquals(calls, app.authBackend.sent.count { it.url.endsWith("pro-members") })
        assertEquals("GET", app.authBackend.sent.last { it.url.endsWith("pro-members") }.method)
    }
}
