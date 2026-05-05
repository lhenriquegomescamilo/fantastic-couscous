# Code Review: `SimpleCache`

**Context** — This cache will run in a busy app:

- Thousands of reads per second
- Hundreds of writes per second
- Tens of threads at the same time
- Long-running JVM

The code:

```kotlin
class SimpleCache<K, V> {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val ttlMs = 60000 // 1 minute

    data class CacheEntry<V>(val value: V, val timestamp: Long)

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(value, System.currentTimeMillis())
    }

    fun get(key: K): V? {
        val entry = cache[key]
        if (entry != null) {
            if (System.currentTimeMillis() - entry.timestamp < ttlMs) {
                return entry.value
            }
        }
        return null
    }

    fun size(): Int = cache.size
}
```

`ConcurrentHashMap` handles thread safety, but the cache itself has problems. Listed below from worst to least.

---

## 1. Memory keeps growing — OOM is certain

**Problem.** Old entries are never deleted. `get()` returns `null` for stale entries but keeps them in the map. Keys written once and never read again stay forever.

**Impact.**
- The heap grows non-stop.
- The JVM dies with `OutOfMemoryError`.
- Before that, GC pauses get longer and latency spikes hit every user of the JVM.

**Fix.** Add a max size with LRU/LFU eviction, plus a background sweeper thread that removes expired entries. Use `cache.remove(key, expectedEntry)` (the two-arg form on `ConcurrentHashMap`) so the sweeper does not delete an entry that was rewritten by another thread between the read and the remove.

---

## 2. No max size

**Problem.** Nothing limits how many entries can be stored. A burst of unique keys fills the heap fast.

**Impact.**
- One bad client can take the service down.
- You cannot plan capacity — heap usage depends on callers, not config.

**Fix.** Add a max size with eviction.

---

## 3. `size()` lies

**Problem.** `size()` counts expired entries too — entries `get()` would never return.

**Impact.**
- Dashboards and alerts show wrong numbers.
- Capacity decisions are based on bad data.

**Fix.** Either expose two metrics (live vs total) or remove expired entries (see #1).

---

## 4. `System.currentTimeMillis()` can jump

**Problem.** Wall-clock time can move backward or forward (NTP fixes, manual changes, VM drift).

**Impact.**
- Backward jump → entries live longer than they should.
- Forward jump → many entries expire at once (a stampede, see #5).
- Bugs are hard to reproduce.

**Fix.** Use `System.nanoTime()`. It only goes forward.

---

## 5. No stampede protection

**Problem.** When a popular key expires, every reader gets `null` at the same time. Each one calls the backend, multiplying load by the number of threads.

**Impact.**
- Load spikes hit the database every time a hot key expires.
- Tail latency jumps for everyone.
- Can take down the upstream service.

**Fix.** Store a `CompletableFuture<V>` in the map instead of the value, and insert it with `cache.computeIfAbsent(key) { CompletableFuture<V>() }`. The first thread for a missing key gets the new future and runs the load; every other thread gets the same future and blocks on `.get()`. The N concurrent calls collapse into one backend call.

---

## 6. No way to remove or clear entries

**Problem.** The API only has `put`, `get`, and `size`. No `remove`, no `clear`.

**Impact.**
- Stale data stays until TTL expires (up to 60s).
- Admin tools cannot purge bad data.
- Tests need reflection or sleeps.

**Fix.** Add `remove(key)` and `clear()`.

---

## 7. TTL is hard-coded

**Problem.** `ttlMs` is fixed at `60000`. No config, no per-entry TTL.

**Impact.**
- Different use cases need different TTLs but cannot share this class.
- Changing the TTL needs a code change and a deploy.

**Fix.** Pass TTL in the constructor. Allow per-entry TTL on `put`.

---

## 8. No metrics

**Problem.** No hits, misses, evictions, or load times.

**Impact.**
- You cannot tell if the cache helps. A 2% hit rate cache is worse than no cache.
- Incidents are hard to debug.

**Fix.** Track hits, misses, and evictions with `LongAdder` fields (one per counter) and expose read-only accessors. `LongAdder` is built for high-write contention — it splits writes across cells so threads do not fight over a single `AtomicLong`.

---

## 9. `CacheEntry` is public

**Problem.** `CacheEntry` is an internal detail but is visible outside the class.

**Impact.**
- Changing the storage format breaks the public API.
- Callers can build `CacheEntry` directly and skip the timestamp logic.

**Fix.** Make `CacheEntry` `private`.

---

## 10. Mutable values are stored by reference

**Problem.** If `V` is mutable, the cache stores the reference, not a copy. A caller who changes the value changes it for everyone.

**Impact.**
- Hard-to-find bugs where cached data drifts from what was put.
- Concurrent writes to a stored value can corrupt it.

**Fix.** Document that values must be immutable, or copy on `put`/`get`. Immutable is simpler.

---

## 11. Expiry only happens on read

**Problem.** A key written and never read stays in the map forever, even if expired. With #1, this is the main leak.

**Impact.** Same as #1 — memory keeps growing.

**Fix.** Run a background cleaner.

---

## Recommendation

For this workload, **do not ship this code as-is**. Two blockers:

- **Memory grows forever** (#1, #2, #11) — OOM is certain.
- **No stampede protection** (#5) — hot keys will overload the backend.
