package com.camilo.cache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val LONG_TTL = TimeUnit.MINUTES.toNanos(1)
private val SHORT_TTL = TimeUnit.MILLISECONDS.toNanos(50)
private val FAST_SWEEP = TimeUnit.MILLISECONDS.toNanos(30)

class ConcurrentCacheTest : BehaviorSpec({

    val caches = mutableListOf<ConcurrentCache<*, *>>()
    afterSpec { caches.forEach { runCatching { it.close() } } }

    fun <K : Any, V : Any> newCache(
        ttl: Long = LONG_TTL,
        maxSize: Int = 100,
        sweep: Long = ttl / 2,
    ): ConcurrentCache<K, V> = ConcurrentCache<K, V>(ttl, maxSize, sweep).also { caches.add(it) }

    given("a fresh ConcurrentCache") {
        val cache = newCache<String, String>()

        `when`("size is checked") {
            then("it returns zero") { cache.size() shouldBe 0 }
        }

        `when`("a missing key is fetched") {
            then("it returns Miss and a miss is recorded") {
                cache.get("nope") shouldBe GetResult.Miss
                cache.stats().misses shouldBeGreaterThan 0L
            }
        }
    }

    given("a cache holding an entry") {
        val cache = newCache<String, String>()
        cache.put("k", "v")

        `when`("the key is read") {
            then("it returns Hit with the stored value") {
                cache.get("k") shouldBe GetResult.Hit("v")
            }
            then("a hit is recorded") { cache.stats().hits shouldBeGreaterThan 0L }
            then("size reports one entry") { cache.size() shouldBe 1 }
        }

        `when`("the key is overwritten") {
            cache.put("k", "v2")
            then("the latest value wins") { cache.get("k") shouldBe GetResult.Hit("v2") }
            then("size stays at one") { cache.size() shouldBe 1 }
        }
    }

    given("a cache with mixed TTLs") {
        val cache = newCache<String, String>(ttl = LONG_TTL)
        cache.put("short", "x", ttlNanos = SHORT_TTL)
        cache.put("long", "y")
        Thread.sleep(120)

        `when`("the short TTL has elapsed") {
            then("the short entry is gone") { cache.get("short") shouldBe GetResult.Miss }
            then("the long entry survives") { cache.get("long") shouldBe GetResult.Hit("y") }
            then("size reports only live entries") { cache.size() shouldBe 1 }
        }
    }

    given("a cache with an expired entry") {
        val cache = newCache<String, String>(ttl = SHORT_TTL)
        cache.put("k", "v")
        Thread.sleep(120)

        `when`("the expired key is read") {
            then("it returns Miss") { cache.get("k") shouldBe GetResult.Miss }
            then("an eviction is recorded") {
                cache.stats().evictions shouldBeGreaterThanOrEqualTo 1L
            }
        }
    }

    given("a cache used through getOrLoad") {
        val cache = newCache<String, String>()
        val invocations = AtomicInteger()

        `when`("the same key is loaded twice") {
            val first = cache.getOrLoad("k") { invocations.incrementAndGet(); "v" }
            val second = cache.getOrLoad("k") { invocations.incrementAndGet(); "DIFFERENT" }
            then("the first call reports Loaded") {
                first shouldBe LoadResult.Loaded("v")
            }
            then("the second call reports Hit with the cached value") {
                second shouldBe LoadResult.Hit("v")
            }
            then("the loader runs only once") { invocations.get() shouldBe 1 }
        }
    }

    given("many threads racing on the same missing key") {
        val cache = newCache<String, String>()
        val threadCount = 20
        val pool = Executors.newFixedThreadPool(threadCount)
        val barrier = CountDownLatch(1)
        val invocations = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<LoadResult<String>>())
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            pool.submit {
                barrier.await()
                val r = cache.getOrLoad("hot") {
                    invocations.incrementAndGet()
                    Thread.sleep(50)
                    "loaded"
                }
                results.add(r)
                done.countDown()
            }
        }
        barrier.countDown()
        done.await(5, TimeUnit.SECONDS)
        pool.shutdownNow()

        `when`("they all call getOrLoad simultaneously") {
            then("the loader runs exactly once") { invocations.get() shouldBe 1 }
            then("exactly one thread reports Loaded") {
                results.count { it is LoadResult.Loaded } shouldBe 1
            }
            then("every thread sees the same value") {
                results.size shouldBe threadCount
                results.forEach {
                    when (it) {
                        is LoadResult.Loaded -> it.value shouldBe "loaded"
                        is LoadResult.Hit -> it.value shouldBe "loaded"
                        is LoadResult.Failed -> error("unexpected failure: ${it.cause}")
                    }
                }
            }
        }
    }

    given("a getOrLoad whose loader fails") {
        val cache = newCache<String, String>()
        val invocations = AtomicInteger()

        `when`("the loader throws") {
            val first = cache.getOrLoad("k") {
                invocations.incrementAndGet()
                error("boom")
            }

            then("it returns Failed carrying the original cause") {
                first.shouldBeInstanceOf<LoadResult.Failed>()
                first.cause.shouldBeInstanceOf<IllegalStateException>()
                first.cause.message shouldBe "boom"
            }
            then("the failure is not cached — the next call retries") {
                cache.getOrLoad("k") {
                    invocations.incrementAndGet()
                    "ok"
                } shouldBe LoadResult.Loaded("ok")
                invocations.get() shouldBe 2
            }
        }
    }

    given("a cache from which an entry is removed") {
        val cache = newCache<String, String>()
        cache.put("a", "1")
        cache.put("b", "2")

        `when`("an existing key is removed") {
            then("it reports Removed") { cache.remove("a") shouldBe RemoveResult.Removed }
            then("the removed key now misses") { cache.get("a") shouldBe GetResult.Miss }
        }
        `when`("an unrelated key is read after the remove") {
            then("it still returns Hit with the value") {
                cache.get("b") shouldBe GetResult.Hit("2")
            }
        }
    }

    given("a cache cleared after writes") {
        val cache = newCache<String, String>()
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()

        `when`("the cleared cache is checked") {
            then("size is zero") { cache.size() shouldBe 0 }
            then("all keys miss") {
                cache.get("a") shouldBe GetResult.Miss
                cache.get("b") shouldBe GetResult.Miss
            }
        }
    }

    given("a cache asked to remove a missing key") {
        val cache = newCache<String, String>()

        `when`("remove is called") {
            then("it reports NotPresent") {
                cache.remove("ghost") shouldBe RemoveResult.NotPresent
            }
        }
    }

    given("a cache exceeding its maximum size") {
        val cache = newCache<String, Int>(ttl = LONG_TTL, maxSize = 3, sweep = FAST_SWEEP)
        cache.put("a", 1); Thread.sleep(5)
        cache.put("b", 2); Thread.sleep(5)
        cache.put("c", 3); Thread.sleep(5)
        cache.get("a") // touch — make "a" the most recently used
        cache.put("d", 4); Thread.sleep(5)
        cache.put("e", 5)
        Thread.sleep(200) // let the sweeper run multiple times

        `when`("the sweeper has run") {
            then("size is bounded by maxSize") {
                cache.size() shouldBeLessThanOrEqualTo 3
            }
            then("evictions are recorded") {
                cache.stats().evictions shouldBeGreaterThanOrEqualTo 1L
            }
            then("the recently-touched key survives") {
                cache.get("a").shouldBeInstanceOf<GetResult.Hit<Int>>()
            }
        }
    }

    given("a cache with a fast sweeper and short TTL") {
        val cache = newCache<String, String>(ttl = SHORT_TTL, sweep = FAST_SWEEP)
        cache.put("k", "v")
        Thread.sleep(250)

        `when`("the sweeper runs after expiry") {
            then("the background sweep removes the entry and counts an eviction") {
                cache.stats().evictions shouldBeGreaterThanOrEqualTo 1L
            }
        }
    }

    given("invalid constructor arguments") {
        `when`("defaultTtlNanos is zero") {
            then("it throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    ConcurrentCache<String, String>(0, 10)
                }
            }
        }
        `when`("maxSize is zero") {
            then("it throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    ConcurrentCache<String, String>(LONG_TTL, 0)
                }
            }
        }
        `when`("sweepPeriodNanos is zero") {
            then("it throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    ConcurrentCache<String, String>(LONG_TTL, 10, sweepPeriodNanos = 0)
                }
            }
        }
    }

    given("a cache used with non-string types") {
        val cache = newCache<Int, List<String>>()
        cache.put(42, listOf("hello", "world"))

        `when`("the key is read") {
            then("Hit carries the original list") {
                cache.get(42) shouldBe GetResult.Hit(listOf("hello", "world"))
            }
        }
    }

    given("a cache under heavy concurrent traffic") {
        val cache = newCache<Int, Int>(ttl = LONG_TTL, maxSize = 1000)
        val threads = 10
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.submit {
                repeat(perThread) { i ->
                    val key = (t * perThread + i) % 200
                    cache.put(key, i)
                    cache.get(key)
                }
                done.countDown()
            }
        }
        val finished = done.await(10, TimeUnit.SECONDS)
        pool.shutdownNow()

        `when`("many threads write and read at once") {
            then("all threads finish without deadlock") {
                finished shouldBe true
            }
            then("hits and misses are recorded") {
                val s = cache.stats()
                (s.hits + s.misses) shouldBeGreaterThan 0L
            }
        }
    }
})
