package com.ecency.core.app

import com.ecency.core.editor.RewardType
import com.ecency.core.legacy.LegacyImportError
import com.ecency.core.legacy.MapLegacyStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyEditorDraftsTest {
    private val now = 1_790_000_000_000L

    /** persist:root as redux-persist v6 writes it: each reducer a JSON string, `cache` with RN's draftsCollection. */
    private fun storage(drafts: String) = MapLegacyStorage(
        mapOf(
            "persist:root" to buildJsonObject {
                put("cache", "{\"votesCollection\":[],\"draftsCollection\":$drafts}")
                put("_persist", "{\"version\":22,\"rehydrated\":true}")
            }.toString(),
        ),
    )

    private val rnDrafts = """{
        "DEFAULT_USER_DRAFT_ID_alice":{"title":"Hello","body":"Body text","tags":"hive,photography","author":"alice",
            "meta":{"description":"Short","image":["https://images.ecency.com/a.png"],"rewardType":"sp","poll":{"title":"x"}},
            "created":${now - 5000},"updated":${now - 1000},"expiresAt":${now + 600000000}},
        "srv-123":{"title":"Server draft","body":"b","tags":"","author":"bob","updated":${now - 2000},"expiresAt":${now + 1}},
        "DEFAULT_USER_DRAFT_ID_carol":{"title":"Old","body":"b","author":"carol","expiresAt":${now - 1}},
        "DEFAULT_USER_DRAFT_ID_dave":{"title":"Mismatch","body":"b","author":"alice","expiresAt":${now + 1000}},
        "srv-empty":{"title":"","body":"","tags":"","author":"bob","expiresAt":${now + 1000}},
        "srv-bad":{"title":"t","body":"b","author":"Not A User","expiresAt":${now + 1000}},
        "alice/ecency.waves":{"title":"","body":"an old wave","author":"alice","expiresAt":${now + 1000}},
        "bob/some-post":{"title":"","body":"an old reply","author":"alice","expiresAt":${now + 1000}}
    }"""

    @Test
    fun rnEditorDraftsMapToLocalDraftSlots() {
        val entries = LegacyEditorDrafts.read(storage(rnDrafts), now).associateBy { it.account to it.slot }
        assertEquals(setOf("alice" to LocalDrafts.NEW_POST, "bob" to LocalDrafts.draft("srv-123")), entries.keys)
        val alice = entries.getValue("alice" to LocalDrafts.NEW_POST).draft
        assertEquals("Hello", alice.title)
        assertEquals("Body text", alice.body)
        assertEquals(listOf("hive", "photography"), alice.tags)
        assertEquals(now - 1000, alice.updated)
        assertEquals("Short", alice.description)
        assertEquals("https://images.ecency.com/a.png", alice.thumbUrl)
        assertEquals(RewardType.POWER_UP, alice.reward)
        assertEquals(null, alice.poll, "poll is not carried over")
        assertEquals(emptyList(), entries.getValue("bob" to LocalDrafts.draft("srv-123")).draft.tags)
    }

    @Test
    fun nothingUsableReadsAsNoDrafts() {
        assertTrue(LegacyEditorDrafts.read(MapLegacyStorage(), now).isEmpty())
        assertTrue(LegacyEditorDrafts.read(MapLegacyStorage(mapOf("persist:root" to "{not json")), now).isEmpty())
        assertTrue(LegacyEditorDrafts.read(storage("[]"), now).isEmpty())
        val unreadable = MapLegacyStorage(mapOf("persist:root" to "{}")).apply { this.unreadable = true }
        assertFailsWith<LegacyImportError.StorageUnreadable> { LegacyEditorDrafts.read(unreadable, now) }
    }

    @Test
    fun aDraftWrittenSinceTheUpdateWins() = runTest {
        val disk = InMemoryDraftStorage()
        val d = StandardTestDispatcher(testScheduler)
        val drafts = LocalDrafts(disk, AppDispatchers(d, d, d))
        drafts.save("bob", LocalDrafts.draft("srv-123"), LocalDraft(title = "Native"))
        LegacyEditorDrafts.apply(LegacyEditorDrafts.read(storage(rnDrafts), now), drafts)
        assertEquals("Native", drafts.load("bob", LocalDrafts.draft("srv-123"))?.title)
        assertEquals("Hello", drafts.load("alice", LocalDrafts.NEW_POST)?.title)
    }
}
