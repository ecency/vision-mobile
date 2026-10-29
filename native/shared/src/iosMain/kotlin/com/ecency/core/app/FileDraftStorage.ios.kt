package com.ecency.core.app

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.kotlincrypto.hash.sha2.SHA256
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Local drafts as private files under [directory] (a folder in Application Support): one folder per account,
 * one file per slot, both named by the SHA-256 of their key, so account and post names never appear in a path.
 * The folder is marked excluded from iCloud and device backups each time it is created. Writes are atomic, and
 * a failed write throws so the editor can say the draft is not stored.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileDraftStorage(private val directory: String) : DraftStorage {
    private fun hash(text: String) = SHA256().digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun folder(account: String) = "$directory/${hash(account)}"

    private fun path(account: String, slot: String) = "${folder(account)}/${hash(slot)}.json"

    /** True when [directory] exists and carries the "excluded from backup" mark. */
    val isExcludedFromBackup: Boolean
        get() = NSURL.fileURLWithPath(directory).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
            ?.get(NSURLIsExcludedFromBackupKey) == true

    private fun ensureDirectory(account: String) {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(folder(account), withIntermediateDirectories = true, attributes = null, error = null)
        // unpublished text never goes into a backup; set on every write, since a recreated folder starts without it
        NSURL.fileURLWithPath(directory).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    override fun read(account: String, slot: String): String? =
        NSString.stringWithContentsOfFile(path(account, slot), encoding = NSUTF8StringEncoding, error = null)

    override fun write(account: String, slot: String, value: String?) {
        if (value == null) {
            NSFileManager.defaultManager.removeItemAtPath(path(account, slot), error = null)
            return
        }
        ensureDirectory(account)
        val ok = NSString.create(string = value).writeToFile(path(account, slot), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) throw IllegalStateException("could not store the draft")
    }

    override fun clear(account: String) {
        NSFileManager.defaultManager.removeItemAtPath(folder(account), error = null)
    }

    override fun clearAll() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}

/** Swift's picked image data into a [PickedImage]: one bulk copy, off the main thread at the call sites. */
@OptIn(ExperimentalForeignApi::class)
object PickedImages {
    fun from(data: NSData, name: String, mime: String, width: Int, height: Int): PickedImage {
        val size = data.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return PickedImage(bytes, name, mime, width, height)
    }

    /** The image's bytes for ImageIO (the avatar crop). */
    @OptIn(BetaInteropApi::class)
    fun data(image: PickedImage): NSData = image.bytes.let { b ->
        if (b.isEmpty()) NSData() else b.usePinned { NSData.create(bytes = it.addressOf(0), length = b.size.convert()) }
    }
}
