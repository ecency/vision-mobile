package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dictation against a fake recorder and a scripted transcription endpoint: no microphone, no audio sent anywhere. */
class DictationModelTest {
    private val user = TestApp.USER
    private val other = "spike-other"

    private class FakeRecorder : DictationRecorder {
        var permission = true
        var startFails = false
        var recording = false
        var clips = 0
        val files = mutableMapOf<String, ByteArray>()
        val events = mutableListOf<String>()

        override fun hasPermission() = permission

        var tag = ""

        override fun start(ownerTag: String): Boolean {
            if (startFails || !DictationClips.isTag(ownerTag)) return false
            tag = ownerTag
            recording = true
            events += "start"
            return true
        }

        override fun stop(): String? {
            if (!recording) return null
            recording = false
            val path = "${tag}_clip-${++clips}.m4a"
            files[path] = "audio-$clips".encodeToByteArray()
            events += "stop"
            return path
        }

        override fun cancel() {
            if (recording) events += "cancel"
            recording = false
        }

        var unreadable = false

        override fun read(path: String): ByteArray? = if (unreadable) null else files[path]

        override fun delete(path: String) {
            files.remove(path)
        }

        override fun clipNames(): List<String> = files.keys.toList()
    }

    private inner class World(val scope: TestScope, val storage: InMemoryDraftStorage = InMemoryDraftStorage(), val recorder: FakeRecorder = FakeRecorder()) {
        /** Every upload that reached the endpoint: the raw multipart body. */
        val uploads = mutableListOf<String>()
        val answers = ArrayDeque<() -> HttpResult>()
        var transcript = "Hello from the microphone."
        var freeUnits = 0
        var keys = 0
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.env.draftStorage = storage
            app.env.dictationRecorder = recorder
            app.env.idempotencyKey = { "key-${++keys}-abcdef" }
            app.authBackend.privateApi = { r -> answer(r) }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            PinLock(KeyVault(app.store), 16).setPin("1234")
            app.env.session.start()
            app.env.appLock.start()
            idle()
            unlock()
        }

        fun unlock() {
            "1234".forEach { app.env.appLock.enterDigit(it.digitToInt()) }
            idle()
        }

        private fun answer(r: AuthHttpRequest): HttpResult = when (r.url.substringAfter("/private-api/")) {
            "ai-transcribe-price" -> HttpResult(200, """{"unit_seconds":30,"unit_cost":15,"free_limit":0,"free_remaining":$freeUnits,"max_seconds":300,"max_bytes":26214400}""")
            "points" -> HttpResult(200, """{"points":"500.000"}""")
            "ai-transcribe" -> {
                assertTrue(r.contentType!!.startsWith("multipart/form-data; boundary="))
                uploads += r.bytes!!.decodeToString()
                answers.removeFirstOrNull()?.invoke() ?: HttpResult(200, """{"text":${kotlinx.serialization.json.JsonPrimitive(transcript)},"units":1,"cost":15}""")
            }
            else -> HttpResult(200, "{}")
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        /** The clock the model bills from moves with the scheduler. */
        fun pass(millis: Long) {
            var left = millis
            while (left > 0) {
                val step = minOf(left, DictationModel.TICK_MILLIS)
                app.now += step
                scope.testScheduler.advanceTimeBy(step)
                scope.testScheduler.runCurrent()
                left -= step
            }
        }

        fun editor(text: String = "Typed first."): EditorModel {
            val m = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            idle()
            m.setBody(text, TextSelection(text.length))
            idle()
            return m
        }

        val sheets = mutableListOf<DictationModel>()

        fun sheet(editor: EditorModel): DictationModel {
            val m = app.env.dictationModel(editor)
            sheets += m
            m.start()
            scope.testScheduler.runCurrent()
            return m
        }

        fun field(upload: String, name: String): String = upload.substringAfter("name=\"$name\"\r\n\r\n").substringBefore("\r\n--")
    }

    private fun down(): HttpResult = throw IllegalStateException("connection lost")

    /** Runs [block] and closes every sheet afterwards, also after a failed assertion (a recording left running ticks forever). */
    private fun world(block: suspend TestScope.(World) -> Unit) = runTest {
        val w = World(this)
        try {
            block(w)
        } finally {
            w.sheets.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun theRateShowsBeforeRecordingAndTheStopButtonStatesTheCost() = world { w ->
        val m = w.sheet(w.editor())
        assertEquals("15 Points per 30 seconds", m.current.costLine)
        assertEquals("Balance: 500.000 POINTS", m.current.balanceLine)
        assertEquals("Record", m.current.mainLabel)
        assertEquals("Stop to transcribe and add it to your post. Up to 5 minutes.", m.current.hintLine)
        m.main()
        assertEquals(DictationPhase.RECORDING, m.current.phase)
        w.pass(1_000)
        assertEquals("0:01", m.current.clock)
        assertEquals("15 Points so far", m.current.costLine)
        assertEquals("Stop (15 Points)", m.current.mainLabel)
        w.pass(30_000)
        assertEquals("Stop (30 Points)", m.current.mainLabel, "a started unit is a billed unit")
        assertTrue(w.uploads.isEmpty(), "nothing is uploaded or charged while recording")
        m.interrupt()
    }

    @Test
    fun stopUploadsTheClipOnceAndTheTranscriptGoesInAtTheCaret() = runTest {
        val w = World(this)
        val editor = w.editor("Typed first.")
        val m = w.sheet(editor)
        m.main()
        w.pass(4_300)
        m.main()
        w.idle()
        val upload = w.uploads.single()
        assertEquals("token-$user", w.field(upload, "code"))
        assertEquals("4300", w.field(upload, "duration_ms"))
        assertEquals("key-1-abcdef", w.field(upload, "idempotency_key"))
        assertTrue("name=\"audio\"; filename=\"dictation.m4a\"\r\nContent-Type: audio/m4a\r\n\r\naudio-1\r\n--" in upload)
        assertFalse("name=\"us\"" in upload, "the server takes the account from the code")
        assertEquals("Typed first. Hello from the microphone.", editor.current.body)
        assertEquals(1, m.current.segments)
        assertEquals("Record more", m.current.mainLabel)
        assertTrue(w.recorder.files.isEmpty(), "the clip is deleted once its transcript arrived")
        assertNull(w.app.env.paidIntents.load(user, DictationModel.KIND, "new"))
        // the next segment is a new clip with a new key; no space is doubled after whitespace
        editor.setBody(editor.current.body + "\n", TextSelection(editor.current.body.length + 1))
        m.main()
        w.pass(1_000)
        m.main()
        w.idle()
        assertEquals("Typed first. Hello from the microphone.\nHello from the microphone.", editor.current.body)
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.uploads.map { w.field(it, "idempotency_key") })
    }

    @Test
    fun theTranscriptReplacesTheSelection() = runTest {
        val w = World(this)
        val editor = w.editor("one two three")
        editor.setSelection(TextSelection(4, 7))
        w.transcript = "2"
        val m = w.sheet(editor)
        m.main()
        w.pass(500)
        m.main()
        w.idle()
        assertEquals("one 2 three", editor.current.body)
        assertEquals(TextSelection(5), editor.current.selection)
    }

    @Test
    fun withoutThePermissionNothingRecords() = world { w ->
        w.recorder.permission = false
        val m = w.sheet(w.editor())
        m.main()
        assertTrue(m.current.askPermission)
        assertEquals(DictationPhase.IDLE, m.current.phase)
        m.permissionAnswered(false)
        assertTrue(m.current.denied)
        assertTrue(w.recorder.events.isEmpty())
        m.main()
        w.recorder.permission = true
        m.permissionAnswered(true)
        assertEquals(DictationPhase.RECORDING, m.current.phase)
        assertFalse(m.current.denied)
        m.interrupt()
        // a recorder that refuses to start says so and can be tried again
        w.recorder.startFails = true
        m.main()
        assertTrue(m.current.recorderFailed)
        assertEquals(DictationPhase.IDLE, m.current.phase)
    }

    @Test
    fun aLostAnswerKeepsTheClipAndCheckAgainSendsItWithTheSameKey() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        w.answers += ::down
        m.main()
        w.pass(2_000)
        m.main()
        w.idle()
        w.pass(5 * 60_000)
        assertEquals(1, w.uploads.size, "no second upload by itself")
        assertEquals(DictationState.Retry.CHECK, m.current.retry)
        assertEquals("Check again", m.current.mainLabel)
        assertEquals(1, w.recorder.files.size, "the clip waits for its result")
        assertEquals("Typed first.", editor.current.body)
        m.main()
        w.idle()
        assertEquals(2, w.uploads.size)
        for (name in listOf("code", "duration_ms", "idempotency_key")) assertEquals(w.field(w.uploads[0], name), w.field(w.uploads[1], name), name)
        assertEquals("key-1-abcdef", w.field(w.uploads[1], "idempotency_key"))
        assertTrue(w.uploads.all { "\r\n\r\naudio-1\r\n--" in it }, "the same clip")
        assertEquals("Typed first. Hello from the microphone.", editor.current.body)
        assertTrue(w.recorder.files.isEmpty())
        assertEquals(listOf("start", "stop"), w.recorder.events, "nothing was recorded again")
    }

    /** A clip whose paid request has no answer: the stored request and the file both wait. */
    private fun waiting(w: World): DictationModel {
        val m = w.sheet(w.editor())
        w.answers += ::down
        m.main()
        w.pass(2_000)
        m.main()
        w.idle()
        assertEquals(DictationState.Retry.CHECK, m.current.retry)
        assertEquals(1, w.recorder.files.size)
        assertEquals(1, w.storage.values.keys.count { it.startsWith("draft/$user/paid:") }, "the request is stored under its account")
        return m
    }

    @Test
    fun aSignedOutAccountLeavesNoClipAndNoPaidRequestAndWritesNeitherAgain() = world { w ->
        // the sheet was closed with its request unanswered: the clip waits on the device, held by no model
        waiting(w).close()
        assertEquals(1, w.recorder.files.size)
        w.storage.values[LocalDrafts.key(other, LocalDrafts.NEW_POST)] = "theirs"
        w.app.env.session.logout(user)
        w.idle()
        assertEquals(setOf(LocalDrafts.key(other, LocalDrafts.NEW_POST)), w.storage.values.keys.toSet(), "the one teardown path removes the stored request")
        assertTrue(w.recorder.files.isEmpty(), "and the clip")
        val (_, stored) = w.app.env.paidIntents.begin(user, DictationModel.KIND, "new", kotlinx.serialization.json.buildJsonObject { })
        assertFalse(stored, "a paid request of an account that left is not written")
        assertFalse(w.app.env.localDrafts.saveRaw(user, "paid:image:standalone", "{}"))
        assertEquals(setOf(LocalDrafts.key(other, LocalDrafts.NEW_POST)), w.storage.values.keys.toSet())
    }

    @Test
    fun anotherAccountLeavingKeepsThisAccountsWaitingClipAndItsRetryUsesTheSameKey() = world { w ->
        val m = waiting(w)
        val clip = w.recorder.files.keys.single()
        assertEquals(DictationClips.tag(user), DictationClips.tagOf(clip), "the clip's name carries its owner's tag, not the name")
        assertFalse(user in clip)
        // bob has a clip of his own waiting, and there is one from a build that did not tag them
        val bobs = "${DictationClips.tag(other)}_theirs.m4a"
        w.recorder.files[bobs] = byteArrayOf(1)
        w.recorder.files["untagged.m4a"] = byteArrayOf(2)
        w.storage.values[LocalDrafts.key(other, "paid:${DictationModel.KIND}:new")] = "{}"
        w.app.env.session.logout(other)
        w.idle()
        assertEquals(setOf(clip), w.recorder.files.keys.toSet(), "bob's clip and the untagged one go, alice's stays")
        assertEquals(1, w.storage.values.keys.count { it.startsWith("draft/$user/paid:") }, "her paid request stays")
        assertTrue(w.storage.values.keys.none { it.startsWith("draft/$other/") })
        assertEquals(DictationState.Retry.CHECK, m.current.retry)
        // "Check again": the same clip under the same key, no second charge
        m.main()
        w.idle()
        assertEquals(2, w.uploads.size)
        for (name in listOf("code", "duration_ms", "idempotency_key")) assertEquals(w.field(w.uploads[0], name), w.field(w.uploads[1], name), name)
        assertNull(m.current.alert, "no lost clip")
        assertTrue(w.recorder.files.isEmpty(), "delivered: the clip is gone")
    }

    @Test
    fun aSheetTidiesOnlyItsOwnAccountsClips() = world { w ->
        val bobs = "${DictationClips.tag(other)}_theirs.m4a"
        w.recorder.files[bobs] = byteArrayOf(1)
        w.recorder.files["${DictationClips.tag(user)}_stray.m4a"] = byteArrayOf(2)
        val m = w.sheet(w.editor())
        w.idle()
        assertEquals(setOf(bobs), w.recorder.files.keys.toSet(), "this account's stray clip goes when its sheet opens, another account's does not")
        m.close()
        assertEquals(setOf(bobs), w.recorder.files.keys.toSet())
    }

    @Test
    fun aClipHeldInMemoryForAnAccountThatLeftIsNotKeptWhenItsSheetCloses() = world { w ->
        val m = waiting(w)
        // the account left without the recorder being purged (a teardown step that failed): the sheet itself lets go
        w.app.env.dictationRecorder = null
        w.app.env.session.logout(user)
        w.app.env.dictationRecorder = w.recorder
        w.idle()
        assertTrue(w.recorder.files.isEmpty(), "the session change deletes the clip of the account that left")
        w.recorder.files["${DictationClips.tag(user)}_stray.m4a"] = byteArrayOf(1)
        m.close()
        assertTrue(w.recorder.files.isEmpty())
    }

    @Test
    fun aWipeRemovesEveryClipAndPaidRequest() = world { w ->
        waiting(w).close()
        assertEquals(1, w.recorder.files.size)
        w.app.env.resetAfterWipe()
        w.idle()
        assertTrue(w.storage.values.isEmpty())
        assertTrue(w.recorder.files.isEmpty())
        assertFalse(w.app.env.localDrafts.saveRaw(user, "paid:${DictationModel.KIND}:new", "{}"))
        assertFalse(w.app.env.localDrafts.saveRaw(other, "paid:assist:new", "{}"), "a wipe: every account left")
    }

    @Test
    fun anUnfinishedClipSurvivesTheAppBeingKilled() = runTest {
        val storage = InMemoryDraftStorage()
        val recorder = FakeRecorder()
        val first = World(this, storage, recorder)
        val m = first.sheet(first.editor())
        first.answers += ::down
        m.main()
        first.pass(2_000)
        m.main()
        first.idle()
        val second = World(this, storage, recorder)
        second.keys = 40
        val editor = second.editor("After the restart.")
        val again = second.sheet(editor)
        second.idle()
        assertEquals(DictationState.Retry.CHECK, again.current.retry)
        assertEquals(PaidCopy.RESTORED, again.current.notice)
        again.main()
        second.idle()
        assertEquals("key-1-abcdef", second.field(second.uploads.single(), "idempotency_key"))
        assertEquals("After the restart. Hello from the microphone.", editor.current.body)
        // a stored request whose clip is gone cannot be asked for again and is dropped
        second.answers += ::down
        again.main()
        second.pass(1_000)
        again.main()
        second.idle()
        assertEquals(DictationState.Retry.CHECK, again.current.retry)
        recorder.files.clear()
        val third = World(this, storage, recorder)
        val gone = third.sheet(third.editor())
        third.idle()
        assertEquals(DictationModel.CLIP_LOST, gone.current.alert, "a paid clip that is gone is said, not dropped silently")
        assertNull(gone.current.retry)
        assertNull(third.app.env.paidIntents.load(user, DictationModel.KIND, "new"))
    }

    @Test
    fun aClipThatCannotBeReadIsNeverAPaidRequest() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        // too large or unreadable right after Stop: nothing is sent, nothing is stored, and it says so
        w.recorder.unreadable = true
        m.main()
        w.pass(1_000)
        m.main()
        w.idle()
        assertEquals(DictationModel.NOT_READ, m.current.alert)
        assertNull(m.current.retry, "no endless Check again for something that never left the device")
        assertTrue(w.uploads.isEmpty())
        assertNull(w.app.env.paidIntents.load(user, DictationModel.KIND, "new"))
        // a clip that was sent, got no answer, and is gone at Check again: the charge is possible, the transcript is not
        w.recorder.unreadable = false
        m.clearAlert()
        w.answers += ::down
        m.main()
        w.pass(1_000)
        m.main()
        w.idle()
        assertEquals(DictationState.Retry.CHECK, m.current.retry)
        w.recorder.files.clear()
        m.main()
        w.idle()
        assertEquals(DictationModel.CLIP_LOST, m.current.alert)
        assertNull(m.current.retry)
        assertEquals(1, w.uploads.size)
        assertNull(w.app.env.paidIntents.load(user, DictationModel.KIND, "new"))
    }

    @Test
    fun aClipStoppedAsFreeThatIsNoLongerFreeWaitsForAYesToItsCost() = world { w ->
        w.freeUnits = 2
        val editor = w.editor()
        val m = w.sheet(editor)
        w.idle()
        // the price was read a while ago
        w.app.now += 5 * 60_000
        m.main()
        // a long pause before recording ends: the price on screen is minutes old, and the free units went elsewhere
        w.pass(50_000)
        assertEquals("Stop (Free)", m.current.mainLabel)
        w.freeUnits = 0
        m.main()
        w.idle()
        assertTrue(w.uploads.isEmpty(), "nothing is uploaded under a label that is no longer true")
        assertEquals("This recording is no longer free today. It costs 30 Points.", m.current.alert)
        assertEquals("Transcribe (30 Points)", m.current.mainLabel)
        assertEquals(1, w.recorder.files.size)
        m.clearAlert()
        m.main()
        w.idle()
        assertEquals(1, w.uploads.size)
        assertEquals("Typed first. Hello from the microphone.", editor.current.body)
        // the same, declined: the clip is thrown away and nothing is charged
        w.freeUnits = 2
        m.reloadPrice()
        w.idle()
        w.app.now += 5 * 60_000
        m.main()
        w.pass(50_000)
        w.freeUnits = 0
        m.main()
        w.idle()
        // "Give up" on a clip that never left the device must not talk about spent points
        assertEquals(DictationModel.GIVE_UP_NOT_SENT, m.current.giveUpConfirm)
        assertFalse("spent" in m.current.giveUpConfirm)
        m.giveUp()
        w.idle()
        assertEquals(PaidCopy.GIVE_UP_CONFIRM, m.current.giveUpConfirm, "the generic text is for requests that did go out")
        assertEquals(1, w.uploads.size)
        assertTrue(w.recorder.files.isEmpty())
        assertNull(m.current.retry)
        // a fresh price that still says free goes straight out
        w.freeUnits = 5
        m.reloadPrice()
        w.idle()
        m.main()
        w.pass(50_000)
        m.main()
        w.idle()
        assertEquals(2, w.uploads.size)
    }

    @Test
    fun aRecordingStopsOnLockAndWaitsUnsentForTheUsersYes() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(3_000)
        assertTrue(w.recorder.recording)
        w.app.env.appLock.lockIfEnabled()
        testScheduler.runCurrent()
        assertFalse(w.recorder.recording, "the microphone is off behind the lock screen")
        assertEquals(DictationPhase.IDLE, m.current.phase)
        assertEquals(listOf("start", "stop"), w.recorder.events, "stopped, the clip kept")
        assertEquals(DictationState.Retry.CONFIRM, m.current.retry)
        assertEquals(DictationModel.HELD_LOCKED, m.current.alert)
        assertFalse(w.app.env.dictationSessions.isRecording, "the background session and its notification went")
        w.idle()
        assertTrue(w.uploads.isEmpty(), "nothing is sent behind the lock")
        // and nothing is sent or started while locked
        m.main()
        w.idle()
        assertTrue(w.uploads.isEmpty())
        assertFalse(w.recorder.recording)
        w.unlock()
        assertEquals("Transcribe (15 Points)", m.current.mainLabel)
        m.main()
        w.idle()
        assertEquals(1, w.uploads.size, "sent once the user said yes, unlocked")
        assertTrue(w.recorder.files.isEmpty())
    }

    @Test
    fun aClipHeldByTheLockOutlivesItsSheetAndTheNextSheetOfThatEditorTakesItBack() = world { w ->
        val editor = w.editor()
        val m = w.sheet(editor)
        m.main()
        w.pass(5_000)
        w.app.env.appLock.lockIfEnabled()
        testScheduler.runCurrent()
        // Android leaves the composer out behind the lock cover: the sheet closes
        m.close()
        w.idle()
        assertEquals(1, w.recorder.files.size, "the held clip stays on the device")
        w.unlock()
        val again = w.sheet(editor)
        w.idle()
        assertEquals(DictationState.Retry.CONFIRM, again.current.retry)
        assertEquals(DictationModel.HELD_LOCKED, again.current.alert)
        assertEquals("0:05", again.current.clock)
        again.main()
        w.idle()
        assertEquals(1, w.uploads.size)
        assertEquals("5000", w.field(w.uploads[0], "duration_ms"))
        assertTrue(w.recorder.files.isEmpty())
    }

    @Test
    fun aSheetClosedByTheLockBeforeTheLocksCollectorRanStillHoldsTheClip() = world { w ->
        val editor = w.editor()
        val m = w.sheet(editor)
        m.main()
        w.pass(3_000)
        w.app.env.appLock.lockIfEnabled()
        // no dispatch in between: the platform closed the sheet first
        m.close()
        w.idle()
        assertEquals(listOf("start", "stop"), w.recorder.events)
        assertEquals(1, w.recorder.files.size)
        w.unlock()
        val again = w.sheet(editor)
        w.idle()
        assertEquals(DictationState.Retry.CONFIRM, again.current.retry)
        assertTrue(w.uploads.isEmpty())
    }

    @Test
    fun withoutItsBackgroundNotificationARecordingIsHeldOnLeavingTheForeground() = world { w ->
        w.app.env.dictationSessions.host = object : DictationHost {
            override fun recordingStarted() = false // notifications off, the service refused
            override fun recordingEnded() {}
        }
        val m = w.sheet(w.editor())
        m.main()
        w.pass(2_000)
        assertTrue(w.recorder.recording, "in front it records as before")
        w.app.env.isForeground = false
        w.app.env.appLock.onBackground()
        testScheduler.runCurrent()
        assertFalse(w.recorder.recording)
        assertEquals(DictationState.Retry.CONFIRM, m.current.retry)
        assertEquals(DictationModel.HELD_NO_BACKGROUND, m.current.alert)
        assertTrue(w.uploads.isEmpty())
        w.app.env.isForeground = true
    }

    @Test
    fun aServiceRefusedWhileInTheBackgroundHoldsAtOnce() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(1_000)
        w.app.env.isForeground = false
        w.pass(1_000)
        assertTrue(w.recorder.recording)
        w.app.env.dictationSessions.backgroundUnavailable()
        testScheduler.runCurrent()
        assertFalse(w.recorder.recording)
        assertEquals(DictationModel.HELD_NO_BACKGROUND, m.current.alert)
        w.app.env.isForeground = true
    }

    @Test
    fun aHeldClipGoesWithItsAccount() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(2_000)
        w.app.env.appLock.lockIfEnabled()
        testScheduler.runCurrent()
        m.close()
        w.unlock()
        w.app.env.session.logout(user)
        w.idle()
        assertTrue(w.recorder.files.isEmpty())
        assertNull(w.app.env.dictationSessions.heldClip(user))
    }

    @Test
    fun aClipHeldByTheLockCanBeGivenUpForFree() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(2_000)
        w.app.env.appLock.lockIfEnabled()
        testScheduler.runCurrent()
        w.unlock()
        assertEquals(DictationModel.GIVE_UP_NOT_SENT, m.current.giveUpConfirm)
        m.giveUp()
        w.idle()
        assertNull(m.current.retry)
        assertTrue(w.recorder.files.isEmpty(), "the held clip is deleted")
        assertTrue(w.uploads.isEmpty())
    }

    @Test
    fun aRecordingGoesOnInTheBackgroundAndTheNotificationStopHoldsItUnsent() = world { w ->
        val host = object : DictationHost {
            val calls = mutableListOf<String>()
            override fun recordingStarted(): Boolean { calls += "started"; return true }
            override fun recordingEnded() { calls += "ended" }
        }
        w.app.env.dictationSessions.host = host
        val m = w.sheet(w.editor())
        m.main()
        assertEquals(listOf("started"), host.calls, "the platform's background session starts with the recording")
        assertTrue(w.app.env.dictationSessions.isRecording)
        w.app.env.isForeground = false
        w.app.env.appLock.onBackground()
        w.pass(20_000)
        assertTrue(w.recorder.recording, "leaving the foreground does not stop it")
        assertEquals(DictationPhase.RECORDING, m.current.phase)
        assertEquals("0:20", m.current.clock)
        w.app.env.dictationSessions.stopFromNotification()
        w.idle()
        assertEquals(listOf("started", "ended"), host.calls)
        assertTrue(w.uploads.isEmpty(), "a Stop on the lock screen spends nothing")
        assertEquals(DictationState.Retry.CONFIRM, m.current.retry)
        assertEquals(DictationModel.HELD_STOPPED, m.current.alert)
        assertFalse(w.app.env.dictationSessions.isRecording)
        // a second Stop from a stale notification does nothing
        w.app.env.dictationSessions.stopFromNotification()
        w.idle()
        assertTrue(w.uploads.isEmpty())
        // back in the app the user confirms the cost
        w.app.env.isForeground = true
        assertEquals("Transcribe (15 Points)", m.current.mainLabel)
        m.main()
        w.idle()
        assertEquals(1, w.uploads.size)
    }

    @Test
    fun aCallTakingTheMicrophoneKeepsTheClipUnsent() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(4_000)
        w.app.env.dictationSessions.systemInterrupted()
        w.idle()
        assertEquals(DictationState.Retry.CONFIRM, m.current.retry)
        assertEquals(DictationModel.HELD_INTERRUPTED, m.current.alert)
        assertTrue(w.uploads.isEmpty())
        assertEquals(1, w.recorder.files.size)
    }

    @Test
    fun aLostAudioFocusHoldsTheClipAndSaysSo() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(2_000)
        w.app.env.dictationSessions.audioFocusLost()
        w.idle()
        assertEquals(DictationState.Retry.CONFIRM, m.current.retry)
        assertEquals(DictationModel.HELD_FOCUS_LOST, m.current.alert)
        assertTrue(w.uploads.isEmpty())
    }

    @Test
    fun logoutStopsABackgroundRecordingAndItsNotification() = world { w ->
        val ended = mutableListOf<String>()
        w.app.env.dictationSessions.host = object : DictationHost {
            override fun recordingStarted() = true
            override fun recordingEnded() { ended += "ended" }
        }
        val m = w.sheet(w.editor())
        m.main()
        w.app.env.isForeground = false
        w.pass(2_000)
        w.app.env.session.logout(user)
        w.idle()
        assertFalse(w.recorder.recording)
        assertEquals(DictationPhase.IDLE, m.current.phase)
        assertEquals(listOf("ended"), ended)
        assertTrue(w.uploads.isEmpty(), "thrown away, never charged")
        w.app.env.isForeground = true
    }

    @Test
    fun anotherAccountLeavingKeepsTheCurrentRecording() = world { w ->
        val m = w.sheet(w.editor())
        m.main()
        w.pass(1_000)
        w.app.env.session.logout(other)
        // not idle: the recording's clock ticks for as long as it runs
        w.pass(2_000)
        assertTrue(w.recorder.recording)
        assertEquals(DictationPhase.RECORDING, m.current.phase)
        m.interrupt()
    }

    @Test
    fun nothingRecordsInTheBackgroundOrForAnotherAccount() = world { w ->
        val m = w.sheet(w.editor())
        w.app.env.isForeground = false
        m.main()
        assertEquals(DictationPhase.IDLE, m.current.phase)
        // the permission prompt answered after the app left the foreground does not start one either
        w.app.env.isForeground = true
        w.recorder.permission = false
        m.main()
        w.app.env.isForeground = false
        m.permissionAnswered(true)
        assertEquals(DictationPhase.IDLE, m.current.phase)
        w.app.env.isForeground = true
        w.recorder.permission = true
        m.main()
        w.pass(2_000)
        assertTrue(w.recorder.recording)
        w.app.env.session.switchTo(other)
        testScheduler.runCurrent()
        assertFalse(w.recorder.recording, "an account change ends the recording")
        m.main()
        assertEquals(DictationPhase.IDLE, m.current.phase)
        assertTrue(w.uploads.isEmpty())
        assertTrue(w.recorder.events.none { it == "stop" })
    }

    @Test
    fun closingTheSheetStopsTheMicrophoneAndRemovesStrayClips() = runTest {
        val w = World(this)
        w.recorder.files["${DictationClips.tag(user)}_stray.m4a"] = byteArrayOf(1)
        val m = w.sheet(w.editor())
        w.idle()
        assertTrue(w.recorder.files.isEmpty(), "this account's stray clips go when the sheet opens")
        m.main()
        w.pass(1_000)
        m.close()
        assertFalse(w.recorder.recording)
        assertTrue(w.uploads.isEmpty())
    }

    @Test
    fun theRecordingStopsItselfBeforeTheServersLimit() = runTest {
        val w = World(this)
        val m = w.sheet(w.editor())
        m.main()
        w.pass(299_000)
        w.idle()
        assertFalse(w.recorder.recording)
        assertEquals("299000", w.field(w.uploads.single(), "duration_ms"))
    }

    @Test
    fun refusalsAndSilentClips() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        fun clip() {
            m.main()
            w.pass(1_000)
            m.main()
            w.idle()
        }
        // RN keeps the clip and its key after 402 and 429: "Try again"
        w.answers += { HttpResult(429, """{"error":"rate_limited"}""") }
        clip()
        assertEquals("Too many recordings for now. Please try again shortly.", m.current.alert)
        assertEquals("Try again", m.current.mainLabel)
        assertEquals(DictationModel.GIVE_UP_NOT_CHARGED, m.current.giveUpConfirm, "a refusal charged nothing")
        assertEquals(1, w.recorder.files.size)
        m.clearAlert()
        m.main()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.uploads.map { w.field(it, "idempotency_key") })
        assertEquals(1, m.current.segments)
        // too long, refunded failures: the clip is dropped
        w.answers += { HttpResult(400, """{"error":"audio_too_long"}""") }
        clip()
        assertEquals("That recording is too long to transcribe.", m.current.alert)
        assertNull(m.current.retry)
        assertTrue(w.recorder.files.isEmpty())
        w.answers += { HttpResult(500, """{"error":"transcription_failed"}""") }
        clip()
        assertEquals(DictationModel.FAILED, m.current.alert)
        assertNull(m.current.retry)
        // a silent clip was charged: it is said, and nothing is inserted
        val before = editor.current.body
        w.transcript = "   "
        clip()
        assertEquals(DictationModel.EMPTY, m.current.alert)
        assertEquals(before, editor.current.body)
        // in progress on the server: asked again with the same key
        w.transcript = "Later."
        w.answers += { HttpResult(409, """{"error":"in_progress","retry_after":5}""") }
        clip()
        assertTrue(editor.current.body.endsWith("Later."))
        val keys = w.uploads.map { w.field(it, "idempotency_key") }
        assertEquals(keys[keys.size - 2], keys.last())
        // giving up drops clip and key
        w.answers += ::down
        clip()
        assertNotNull(m.current.retry)
        m.giveUp()
        w.idle()
        assertNull(m.current.retry)
        assertTrue(w.recorder.files.isEmpty())
        assertNull(w.app.env.paidIntents.load(user, DictationModel.KIND, "new"))
    }
}
