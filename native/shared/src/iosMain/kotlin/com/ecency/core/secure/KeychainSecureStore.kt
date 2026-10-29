package com.ecency.core.secure

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * [SecureStore] on iOS: one Keychain generic password per entry, account = entry name, service scoped to
 * the app (bundle id + ".secure" by default). Items are readable after the first unlock since boot, never
 * leave this device (no iCloud Keychain, not restored to other devices from backups).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecureStore(val service: String) : SecureStore {

    /** The app's own service, bundle id + ".secure". */
    constructor() : this(defaultService())

    private val lock: NSRecursiveLock = lockFor(service)

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Keychain items outlive an uninstall, so a reinstalled app would find the previous install's accounts.
     * Call once at launch, before reading the vault: wipes this service when [defaults] has no install
     * mark yet, then sets it. UserDefaults are removed on uninstall and kept across updates (including the
     * update from the RN app, which never used this service). Returns true when it wiped.
     */
    @Throws(SecureStoreException::class)
    fun wipeIfFreshInstall(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults): Boolean = withLock {
        val mark = "$service.installed"
        if (defaults.boolForKey(mark)) return@withLock false
        wipe()
        defaults.setBool(true, forKey = mark)
        true
    }

    override fun put(name: String, value: ByteArray) = withLock {
        SecureStore.requireValidName(name)
        val status = withQuery(item(name) + listOf(kSecValueData to value.toNSData(), kSecAttrAccessible to ACCESSIBLE)) {
            SecItemAdd(it, null)
        }
        when (status) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> {
                val update = withQuery(item(name)) { query ->
                    withQuery(listOf(kSecValueData to value.toNSData(), kSecAttrAccessible to ACCESSIBLE)) { attrs ->
                        SecItemUpdate(query, attrs)
                    }
                }
                if (update != errSecSuccess) throw SecureStoreException.Unavailable(update)
            }
            else -> throw SecureStoreException.Unavailable(status)
        }
    }

    override fun get(name: String): ByteArray? = withLock {
        SecureStore.requireValidName(name)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withQuery(item(name) + listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) {
                SecItemCopyMatching(it, result.ptr)
            }
            when (status) {
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
                    ?: throw SecureStoreException.Corrupted(name)
                errSecItemNotFound -> null
                else -> throw SecureStoreException.Unavailable(status)
            }
        }
    }

    override fun delete(name: String) = withLock {
        SecureStore.requireValidName(name)
        val status = withQuery(item(name)) { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw SecureStoreException.Unavailable(status)
    }

    override fun names(): Set<String> = withLock { names0() }

    private fun names0(): Set<String> = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(serviceQuery() + listOf(kSecReturnAttributes to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitAll)) {
            SecItemCopyMatching(it, result.ptr)
        }
        when (status) {
            errSecSuccess -> {
                val accountKey = CFBridgingRelease(CFRetain(kSecAttrAccount))
                (CFBridgingRelease(result.value) as List<*>)
                    .mapNotNull { (it as? Map<*, *>)?.get(accountKey) as? String }
                    .toSet()
            }
            errSecItemNotFound -> emptySet()
            else -> throw SecureStoreException.Unavailable(status)
        }
    }

    /** On iOS a delete query without an account removes every item of the service. */
    override fun wipe() = withLock {
        val status = withQuery(serviceQuery()) { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw SecureStoreException.Unavailable(status)
    }

    private fun serviceQuery(): List<Pair<CFTypeRef?, Any?>> = listOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        // Only this device's items; never match or create iCloud Keychain copies.
        kSecAttrSynchronizable to kCFBooleanFalse(),
    )

    private fun item(name: String) = serviceQuery() + (kSecAttrAccount to name)

    companion object {
        private val ACCESSIBLE: CFTypeRef? = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        fun defaultService(): String = (NSBundle.mainBundle.bundleIdentifier ?: "com.ecency") + ".secure"
    }
}

/**
 * Builds a CFDictionary for one Security call. Values may be CF constants (passed through), Kotlin strings
 * or NSData (bridged and released after the call); the dictionary retains what it holds.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private inline fun <T> withQuery(pairs: List<Pair<CFTypeRef?, Any?>>, call: (CFDictionaryRef?) -> T): T {
    val dict = CFDictionaryCreateMutable(null, pairs.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    try {
        for ((key, value) in pairs) {
            when (value) {
                null -> throw SecureStoreException.InvalidArgument("Keychain query value missing")
                is CPointer<*> -> CFDictionaryAddValue(dict, key, value)
                else -> {
                    val bridged = CFBridgingRetain(if (value is String) NSString.create(string = value) else value)
                    CFDictionaryAddValue(dict, key, bridged)
                    CFRelease(bridged)
                }
            }
        }
        return call(dict)
    } finally {
        CFRelease(dict)
    }
}

/** One lock per Keychain service for the whole process, so separate instances never interleave. */
private val locksGuard = NSLock()
private val locks = mutableMapOf<String, NSRecursiveLock>()

private fun lockFor(service: String): NSRecursiveLock {
    locksGuard.lock()
    try {
        return locks.getOrPut(service) { NSRecursiveLock() }
    } finally {
        locksGuard.unlock()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kCFBooleanFalse(): CFTypeRef? = platform.CoreFoundation.kCFBooleanFalse

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
}
