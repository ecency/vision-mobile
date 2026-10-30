package com.ecency.core.legacy

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AsyncStorageReaderTest {
    private fun write(path: String, text: String) {
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /** Lays out storage the way RNCAsyncStorage does: short values inline, long ones in md5(key) files. */
    private fun storageDir(values: Map<String, String>): String {
        val dir = NSTemporaryDirectory() + "asyncstorage-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val manifest = values.mapValues { (key, value) ->
            if (value.length <= 1024) JsonPrimitive(value) else JsonNull.also { write("$dir/${AsyncStorageReader.md5Hex(key)}", value) }
        }
        write("$dir/manifest.json", Json.encodeToString(JsonObject.serializer(), JsonObject(manifest)))
        return dir
    }

    @Test
    fun md5MatchesRctMd5Hash() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", AsyncStorageReader.md5Hex(""))
        assertEquals("ee11cbb19052e40b07aac0ca060c23ee", AsyncStorageReader.md5Hex("user"))
    }

    @Test
    fun readsInlineAndOverflowValues() {
        val dir = storageDir(mapOf("short" to "inline", "long" to "x".repeat(5000)))
        val reader = AsyncStorageReader(dir)
        assertEquals("inline", reader.read("short"))
        assertEquals("x".repeat(5000), reader.read("long"))
        assertNull(reader.read("missing"))
    }

    @Test
    fun missingStorageReadsAsEmpty() {
        assertNull(AsyncStorageReader(NSTemporaryDirectory() + "does-not-exist").read("user"))
    }

    @Test
    fun importsFromOnDiskStorage() {
        val dir = storageDir(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS))
        val result = LegacyImporter.import(AsyncStorageReader(dir)::read, LegacyFixtures.PIN_KEY)
        assertEquals(LegacyFixtures.POSTING, result.accounts.single { it.username == "spike-master" }.postingKey)
    }

    @Test
    fun writesAndRemovesLikeRnAsyncStorage() {
        val dir = storageDir(mapOf("user" to "x".repeat(5000), "auth" to "{}", "draft" to "keep"))
        val storage = AsyncStorageReader(dir)
        storage.remove("user")
        storage.write("persist:root", "y".repeat(3000))
        storage.write("auth", "short")
        storage.remove("never-there")
        val fresh = AsyncStorageReader(dir)
        assertNull(fresh.read("user"))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$dir/${AsyncStorageReader.md5Hex("user")}"))
        assertEquals("y".repeat(3000), fresh.read("persist:root"))
        assertEquals("short", fresh.read("auth"))
        assertEquals("keep", fresh.read("draft"))
        assertTrue(LegacyCleanup.scrub(fresh))
        assertNull(AsyncStorageReader(dir).read("auth"))
    }

    @Test
    fun storageThatExistsButCannotBeReadIsTyped() {
        val broken = storageDir(emptyMap()).also { write("$it/manifest.json", "{not json") }
        assertFailsWith<LegacyImportError.StorageUnreadable> { AsyncStorageReader(broken).read("user") }

        // Manifest points at an overflow file that is not there (or not readable yet).
        val dangling = storageDir(emptyMap()).also { write("$it/manifest.json", "{\"user\":null}") }
        assertFailsWith<LegacyImportError.StorageUnreadable> { AsyncStorageReader(dangling).read("user") }
    }

    private fun dir(path: String) = path.also { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) }

    @Test
    fun findsTheRnFolderLikeRncAsyncStorage() {
        val root = NSTemporaryDirectory() + "asyncstorage-root-" + NSUUID().UUIDString
        val support = "$root/Library/Application Support"
        val documents = "$root/Documents"
        val current = "$support/app.esteem.mobile.ios/RCTAsyncLocalStorage_V1"
        // nothing at all: the current folder, which reads as no RN data
        assertEquals(current, AsyncStorageReader.directoryFor(support, documents, "app.esteem.mobile.ios"))
        // an RN install last opened before async-storage moved its data out of Documents
        write(dir("$documents/RNCAsyncLocalStorage_V1") + "/manifest.json", "{}")
        assertEquals("$documents/RNCAsyncLocalStorage_V1", AsyncStorageReader.directoryFor(support, documents, "app.esteem.mobile.ios"))
        write(dir("$documents/RCTAsyncLocalStorage_V1") + "/manifest.json", "{}")
        assertEquals("$documents/RCTAsyncLocalStorage_V1", AsyncStorageReader.directoryFor(support, documents, "app.esteem.mobile.ios"))
        // RN copies Documents into Application Support without deleting the old copy: the moved data wins
        write(dir(current) + "/manifest.json", "{}")
        assertEquals(current, AsyncStorageReader.directoryFor(support, documents, "app.esteem.mobile.ios"))
        // another bundle id (the old preview app) never sees the RN folder
        assertEquals("$documents/RCTAsyncLocalStorage_V1", AsyncStorageReader.directoryFor(support, documents, "com.ecency.mobile"))
    }

    /**
     * The layout an RN 3.5.11 install leaves (checked on a simulator against the real app, keys only): long values
     * ("persist:root", "user", the query cache) are null in a manifest RCTJSONStringify wrote (NSJSONSerialization,
     * which escapes "/"), short ones inline, files named by RCTMD5Hash.
     */
    @Test
    fun readsTheRn3StorageLayout() {
        val dir = dir(NSTemporaryDirectory() + "asyncstorage-rn3-" + NSUUID().UUIDString)
        assertEquals("817e01dfc9ca7287f4e9a0f6a1c6830b", AsyncStorageReader.md5Hex("persist:root"))
        assertEquals("b6fc409153bed742ecb0853f303fb9f1", AsyncStorageReader.md5Hex("REACT_QUERY_OFFLINE_CACHE"))
        write(
            "$dir/manifest.json",
            """{"application":"{\"api\":\"https:\/\/api.hive.blog\"}","auth":"{}","persist:root":null,"user":null,""" +
                """"REACT_QUERY_OFFLINE_CACHE":null,"locale_auto_detected":"true"}""",
        )
        write("$dir/817e01dfc9ca7287f4e9a0f6a1c6830b", LegacyFixtures.PERSIST_ROOT)
        write("$dir/${AsyncStorageReader.md5Hex("user")}", LegacyFixtures.USERS)
        write("$dir/b6fc409153bed742ecb0853f303fb9f1", "{\"clientState\":{\"queries\":[]},\"pad\":\"" + "q".repeat(300_000) + "\"}")
        val reader = AsyncStorageReader(dir)
        assertEquals("{\"api\":\"https://api.hive.blog\"}", reader.read("application"))
        assertEquals("true", reader.read("locale_auto_detected"))
        assertTrue(reader.read("REACT_QUERY_OFFLINE_CACHE")!!.length > 300_000)
        val result = LegacyImporter.import(reader::read, LegacyFixtures.PIN_KEY)
        assertEquals(LegacyFixtures.POSTING, result.accounts.single { it.username == "spike-master" }.postingKey)
        // the cleanup leaves what is not a secret
        assertTrue(LegacyCleanup.scrub(reader))
        val after = AsyncStorageReader(dir)
        assertNull(after.read("user"))
        assertNull(after.read("auth"))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$dir/${AsyncStorageReader.md5Hex("user")}"))
        assertEquals("true", after.read("locale_auto_detected"))
        assertTrue(after.read("REACT_QUERY_OFFLINE_CACHE")!!.length > 300_000)
    }

    /** RN copied Documents into Application Support without deleting it: the old copy goes with the secrets. */
    @Test
    fun staleDocumentsCopiesAreDeletedWithTheSecrets() {
        val root = NSTemporaryDirectory() + "asyncstorage-stale-" + NSUUID().UUIDString
        val support = "$root/Library/Application Support"
        val documents = "$root/Documents"
        val live = dir("$support/app.esteem.mobile.ios/RCTAsyncLocalStorage_V1")
        write("$live/manifest.json", "{\"locale_auto_detected\":\"true\"}")
        for (old in listOf("RCTAsyncLocalStorage_V1", "RNCAsyncLocalStorage_V1")) {
            write(dir("$documents/$old") + "/manifest.json", "{\"user\":\"[]\",\"auth\":\"{}\"}")
        }
        val storage = AsyncStorageReader.forLocations(support, documents, "app.esteem.mobile.ios")
        // the live copy has no secrets left, the stale ones do
        assertTrue(LegacyCleanup.hasSecrets(storage))
        assertTrue(LegacyCleanup.scrub(storage))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$documents/RCTAsyncLocalStorage_V1"))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$documents/RNCAsyncLocalStorage_V1"))
        assertEquals("true", storage.read("locale_auto_detected"))
        assertFalse(LegacyCleanup.hasSecrets(storage))
        assertFalse(LegacyCleanup.scrub(storage))
    }

    @Test
    fun aDocumentsFolderThatIsTheLiveCopyIsKept() {
        val root = NSTemporaryDirectory() + "asyncstorage-live-docs-" + NSUUID().UUIDString
        val documents = "$root/Documents"
        val live = dir("$documents/RCTAsyncLocalStorage_V1")
        write("$live/manifest.json", "{\"user\":\"[]\",\"draft\":\"keep\"}")
        write(dir("$documents/RNCAsyncLocalStorage_V1") + "/manifest.json", "{\"user\":\"[]\"}")
        val storage = AsyncStorageReader.forLocations("$root/Library/Application Support", documents, "app.esteem.mobile.ios")
        assertTrue(LegacyCleanup.scrub(storage))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$live/manifest.json"))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$documents/RNCAsyncLocalStorage_V1"))
        assertNull(storage.read("user"))
        assertEquals("keep", storage.read("draft"))
    }
}
