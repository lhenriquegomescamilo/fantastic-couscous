package com.camilo.cache

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

sealed class GetResult<out V> {
    data class Hit<V>(val value: V) : GetResult<V>()
    data object Miss : GetResult<Nothing>()
}

sealed class LoadResult<out V> {
    data class Hit<V>(val value: V) : LoadResult<V>()
    data class Loaded<V>(val value: V) : LoadResult<V>()
    data class Failed(val cause: Throwable) : LoadResult<Nothing>()
}

sealed class RemoveResult {
    data object Removed : RemoveResult()
    data object NotPresent : RemoveResult()
}

/**
 * Concurrent in-memory cache with TTL, bounded size, stampede protection, and metrics.
 *
 * Stored values must be effectively immutable — the cache stores references, not copies.
 */
class ConcurrentCache<K : Any, V : Any>(
    private val defaultTtlNanos: Long,
    private val maxSize: Int,
    sweepPeriodNanos: Long = defaultTtlNanos / 2,
) : AutoCloseable {

    init {
        require(defaultTtlNanos > 0) { "defaultTtlNanos must be > 0" }
        require(maxSize > 0) { "maxSize must be > 0" }
        require(sweepPeriodNanos > 0) { "sweepPeriodNanos must be > 0" }
    }

    private val store = ConcurrentHashMap<K, Entry<V>>()

    private val hits = LongAdder()
    private val misses = LongAdder()
    private val evictions = LongAdder()

    private val sweeper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "concurrent-cache-sweeper").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(::sweep, sweepPeriodNanos, sweepPeriodNanos, TimeUnit.NANOSECONDS)
        }

    fun put(key: K, value: V, ttlNanos: Long = defaultTtlNanos) {
        val now = System.nanoTime()
        store[key] = Entry.completed(value, now + ttlNanos, now)
    }

    fun get(key: K): GetResult<V> {
        val now = System.nanoTime()
        val entry = store[key]

        return when {
            entry == null -> GetResult.Miss.also { misses.increment() }

            !entry.isUsable(now) -> GetResult.Miss.also {
                evictIfPresent(key, entry)
                misses.increment()
            }

            else -> runCatching { entry.future.get() }.fold(
                onSuccess = { value ->
                    entry.lastAccessNanos.set(now)
                    hits.increment()
                    GetResult.Hit(value)
                },
                onFailure = {
                    store.remove(key, entry)
                    misses.increment()
                    GetResult.Miss
                },
            )
        }
    }

    /**
     * Stampede-safe load. Concurrent calls for the same missing key share a single backend call.
     * Returns [LoadResult.Hit] when served from cache, [LoadResult.Loaded] when the loader ran,
     * or [LoadResult.Failed] when the loader threw — failures are not cached.
     */
    fun getOrLoad(key: K, ttlNanos: Long = defaultTtlNanos, loader: (K) -> V): LoadResult<V> {
        val now = System.nanoTime()
        val pending = Entry.pending<V>(now + ttlNanos, now)

        val winner = store.compute(key) { _, current ->
            current?.takeIf { it.isUsable(now) } ?: pending
        }!!

        return if (winner === pending) {
            misses.increment()
            // Fail the future so any waiters see the error, then drop the entry
            // so the next call retries instead of caching the failure.
            runCatching { loader(key) }.fold(
                onSuccess = { value ->
                    pending.future.complete(value)
                    LoadResult.Loaded(value)
                },
                onFailure = { t ->
                    pending.future.completeExceptionally(t)
                    store.remove(key, pending)
                    LoadResult.Failed(t)
                },
            )
        } else {
            runCatching { winner.future.get() }.fold(
                onSuccess = { value ->
                    winner.lastAccessNanos.set(now)
                    hits.increment()
                    LoadResult.Hit(value)
                },
                onFailure = { LoadResult.Failed(it) },
            )
        }
    }

    fun remove(key: K): RemoveResult =
        if (store.remove(key) != null) RemoveResult.Removed else RemoveResult.NotPresent

    fun clear() {
        store.clear()
    }

    fun size(): Int = System.nanoTime().let { now ->
        store.values.count { it.isUsable(now) }
    }

    fun stats(): Stats = Stats(hits.sum(), misses.sum(), evictions.sum())

    override fun close() {
        sweeper.shutdownNow()
    }

    private fun evictIfPresent(key: K, entry: Entry<V>) {
        if (store.remove(key, entry)) evictions.increment()
    }

    private fun sweep() {
        val now = System.nanoTime()

        store.entries
            .filter { (_, v) -> !v.isUsable(now) }
            .forEach { (k, v) -> evictIfPresent(k, v) }

        (store.size - maxSize)
            .takeIf { it > 0 }
            ?.let { overflow ->
                store.entries
                    .sortedBy { it.value.lastAccessNanos.get() }
                    .take(overflow)
                    .forEach { (k, v) -> evictIfPresent(k, v) }
            }
    }

    data class Stats(val hits: Long, val misses: Long, val evictions: Long)

    private class Entry<V>(
        val future: CompletableFuture<V>,
        val expiresAt: Long,
        accessNanos: Long,
    ) {
        val lastAccessNanos = AtomicLong(accessNanos)

        fun isUsable(now: Long): Boolean =
            expiresAt - now > 0 && !future.isCompletedExceptionally

        companion object {
            fun <V> completed(value: V, expiresAt: Long, now: Long): Entry<V> =
                Entry(CompletableFuture.completedFuture(value), expiresAt, now)

            fun <V> pending(expiresAt: Long, now: Long): Entry<V> =
                Entry(CompletableFuture<V>(), expiresAt, now)
        }
    }
}
