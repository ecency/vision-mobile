package com.ecency.core.api.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A small LRU cache with a time-to-live, safe to share between coroutines. */
class MemoryCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
) {
    private class Entry<V>(val value: V, val storedAt: Long)

    private val mutex = Mutex()
    private val map = LinkedHashMap<K, Entry<V>>()

    suspend fun get(key: K): V? = mutex.withLock {
        val entry = map.remove(key) ?: return@withLock null
        if (nowMillis() - entry.storedAt > ttlMillis) return@withLock null
        map[key] = entry // most recently used goes last
        entry.value
    }

    suspend fun put(key: K, value: V): Unit = mutex.withLock {
        map.remove(key)
        map[key] = Entry(value, nowMillis())
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    suspend fun remove(key: K) { mutex.withLock { map.remove(key) } }

    suspend fun removeIf(predicate: (K, V) -> Boolean): Unit = mutex.withLock {
        map.entries.filter { predicate(it.key, it.value.value) }.map { it.key }.forEach { map.remove(it) }
    }


    suspend fun clear(): Unit = mutex.withLock { map.clear() }

    suspend fun size(): Int = mutex.withLock { map.size }
}
