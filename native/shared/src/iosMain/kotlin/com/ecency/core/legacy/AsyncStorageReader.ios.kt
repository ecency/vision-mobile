package com.ecency.core.legacy

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.md.MD5
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The RN app's AsyncStorage on iOS (@react-native-async-storage/async-storage 2.2.0, RNCAsyncStorage.mm):
 * Application Support/<bundle id>/RCTAsyncLocalStorage_V1. Values up to 1024 chars sit in manifest.json; longer ones
 * are null there and live in a file named md5(key) (RCTMD5Hash, lowercase hex). Native 4.x is an update of the RN app
 * (same bundle id, app.esteem.mobile.ios), so this is the RN app's own folder ([defaultDirectory]).
 *
 * No manifest file means no RN data. A manifest or value file that exists but cannot be read (data
 * protection before first unlock, I/O error) or parsed is [LegacyImportError.StorageUnreadable].
 * Writes and removes are only for [LegacyCleanup].
 */
class AsyncStorageReader(
    private val directory: String,
    /** Older RN copies to delete with the secrets ([LegacyStorage.hasStaleCopies]); see [forApp]. */
    private val staleDirectories: List<String> = emptyList(),
) : LegacyStorage {
    private val manifestPath = "$directory/manifest.json"
    private var manifest: MutableMap<String, JsonElement>? = null

    /**
     * The account migration (and its cleanup) and the settings import read this storage from different threads, and
     * the cleanup rewrites the cached manifest: every call holds this lock.
     */
    private val lock = com.ecency.core.api.rpc.PlatformLock()

    /** The manifest, or null when there is no RN storage at all. */
    private fun manifest(): MutableMap<String, JsonElement>? {
        manifest?.let { return it }
        if (!exists(manifestPath)) return null
        val text = readText(manifestPath) ?: throw LegacyImportError.StorageUnreadable()
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw LegacyImportError.StorageUnreadable(it) }
        return parsed.toMutableMap().also { manifest = it }
    }

    override fun read(key: String): String? = lock.withLock { readLocked(key) }

    private fun readLocked(key: String): String? {
        val entry = manifest()?.get(key) ?: return null
        if (entry !is JsonNull) {
            return runCatching { entry.jsonPrimitive.content }.getOrElse { throw LegacyImportError.StorageUnreadable(it) }
        }
        val path = valuePath(key)
        if (!exists(path)) throw LegacyImportError.StorageUnreadable()
        return readText(path) ?: throw LegacyImportError.StorageUnreadable()
    }

    override fun write(key: String, value: String) = lock.withLock { writeLocked(key, value) }

    private fun writeLocked(key: String, value: String) {
        val manifest = manifest() ?: throw LegacyImportError.StorageUnreadable()
        if (value.length <= INLINE_LIMIT) {
            manifest[key] = JsonPrimitive(value)
            deleteFile(valuePath(key))
        } else {
            writeText(valuePath(key), value)
            manifest[key] = JsonNull
        }
        saveManifest(manifest)
    }

    override fun remove(key: String) = lock.withLock { removeLocked(key) }

    override fun hasStaleCopies(): Boolean = lock.withLock { staleDirectories.any(::exists) }

    override fun removeStaleCopies() = lock.withLock {
        staleDirectories.filter(::exists).forEach { deleteFile(it) }
    }

    private fun removeLocked(key: String) {
        val manifest = manifest() ?: return
        if (manifest.remove(key) == null && !exists(valuePath(key))) return
        saveManifest(manifest)
        deleteFile(valuePath(key))
    }

    private fun saveManifest(manifest: Map<String, JsonElement>) =
        writeText(manifestPath, Json.encodeToString(JsonObject.serializer(), JsonObject(manifest)))

    private fun valuePath(key: String) = "$directory/${md5Hex(key)}"

    companion object {
        /** RCTInlineValueThreshold. */
        private const val INLINE_LIMIT = 1024

        /** RCTStorageDirectory, RCTOldStorageDirectory and RCTExpoStorageDirectory. */
        private const val CURRENT = "RCTAsyncLocalStorage_V1"
        private val OLD_DOCUMENTS_FOLDERS = listOf(CURRENT, "RNCAsyncLocalStorage_V1", "RCTAsyncLocalStorage")

        /** The reader the app uses: the RN app's live folder, with every other RN folder as a stale copy. */
        fun forApp(): AsyncStorageReader = forLocations(
            applicationSupport = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String,
            documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String,
            bundleId = NSBundle.mainBundle.bundleIdentifier ?: "",
        )

        internal fun forLocations(applicationSupport: String, documents: String, bundleId: String): AsyncStorageReader {
            val live = directoryFor(applicationSupport, documents, bundleId)
            return AsyncStorageReader(live, OLD_DOCUMENTS_FOLDERS.map { "$documents/$it" }.filter { it != live })
        }

        fun defaultDirectory(): String = directoryFor(
            applicationSupport = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String,
            documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String,
            bundleId = NSBundle.mainBundle.bundleIdentifier ?: "",
        )

        /**
         * Where the RN app's data is: Application Support/<bundle id>/RCTAsyncLocalStorage_V1, where every RN launch
         * since async-storage 1.12 moves it. Only when that has no manifest, one of the older Documents folders that
         * has one (an RN install not opened since an old version; RN itself would have moved it on its next launch).
         * With none, the current folder (no RN data).
         */
        internal fun directoryFor(applicationSupport: String, documents: String, bundleId: String): String {
            val current = "$applicationSupport/$bundleId/$CURRENT"
            if (exists("$current/manifest.json")) return current
            return OLD_DOCUMENTS_FOLDERS.map { "$documents/$it" }.firstOrNull { exists("$it/manifest.json") } ?: current
        }

        internal fun md5Hex(key: String): String =
            MD5().digest(key.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

private fun exists(path: String) = NSFileManager.defaultManager.fileExistsAtPath(path)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readText(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeText(path: String, text: String) {
    if (!NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
        throw LegacyImportError.StorageUnreadable()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteFile(path: String) {
    if (exists(path) && !NSFileManager.defaultManager.removeItemAtPath(path, null)) {
        throw LegacyImportError.StorageUnreadable()
    }
}
